package com.dsh.harness;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.TrafficStats;
import android.os.PowerManager;
import android.util.Log;

/**
 * 后台任务保活：按「有没有真在干活」自动持有 / 释放 PARTIAL_WAKE_LOCK。
 *
 * ## 为什么需要（r230）
 * {@link DshService} 早就以前台服务常驻（`isForeground=true`），但那只能让系统
 * 不把进程当缓存进程冻结/回收，**管不住 CPU 睡眠**。实测把 App 切后台后：
 *   `dumpsys power` 里本 App 一个 `PARTIAL_WAKE_LOCK` 都没有（只有系统 AudioMix），
 *   于是屏幕一关 CPU 就 suspend，node 的事件循环停摆 —— 正在流的模型 SSE 直接断，
 *   回到前台看到「已重试模型请求 / DeepSeek Messages transport failed」。
 *
 * Manifest 里 `WAKE_LOCK` 权限一直声明着，但 java 侧一次都没 `newWakeLock()`。
 *
 * ## 为什么不常驻加锁
 * 一直持有 PARTIAL_WAKE_LOCK = 屏幕关了 CPU 也不睡，待机耗电会明显变差
 * （尤其这台机器一天有十几个小时在后台）。所以这里做的是**按需续期**：
 *
 *   · 每 30 秒采样一次本 UID 的收发字节数（{@link TrafficStats}）
 *   · 30 秒内动过 ≥4KB → 认为「有活在干」→ 续 10 分钟
 *   · 一直没有流量 → 上一份授权自然过期，锁自动释放，回到正常休眠
 *
 * 选流量而不是 CPU，是因为**卡住的正是等网络**：模型流式返回时进程几乎不占 CPU，
 * 全靠收包推进；本地工具跑得再快也不怕（在跑的任务会阻止内核 suspend）。
 * 一次续期 10 分钟而不是 30 秒，是为了避免采样间隔之间出现"刚释放又来包"的空窗。
 *
 * ## 怎么关掉
 * `dsh_wakelock_enabled` 偏好项设成 false（默认 true）。改偏好不用重新打包：
 *   adb shell run-as com.dsh.harness ... 或以后接进设置面板。
 */
public final class DshWakeLock {

    private static final String TAG = "DshWakeLock";
    private static final String LOCK_TAG = "dsh:background-task";
    private static final String PREF = "dsh_prefs";
    private static final String KEY_ENABLED = "dsh_wakelock_enabled";

    /** 一次授权覆盖多久。采样间隔的 20 倍，留足余量。 */
    private static final long WINDOW_MS = 10 * 60 * 1000L;
    /** 采样间隔。太密费电，太疏会漏掉短请求。 */
    private static final long SAMPLE_MS = 30 * 1000L;
    /** 一个采样间隔内动过这么多字节就算「在干活」。心跳/轮询级别的小包不会误触发。 */
    private static final long BYTES_THRESHOLD = 4 * 1024L;

    private static PowerManager.WakeLock lock;
    private static Thread sampler;
    private static volatile boolean running;
    private static volatile long lastRenewAt;

    private DshWakeLock() {
    }

    /** 开关（默认开）。改这个偏好项即可在不重编 APK 的情况下关掉保活。 */
    public static boolean enabled(Context ctx) {
        try {
            SharedPreferences sp = ctx.getApplicationContext()
                    .getSharedPreferences(PREF, Context.MODE_PRIVATE);
            return sp.getBoolean(KEY_ENABLED, true);
        } catch (Throwable t) {
            return true;
        }
    }

    public static void setEnabled(Context ctx, boolean on) {
        try {
            ctx.getApplicationContext()
                    .getSharedPreferences(PREF, Context.MODE_PRIVATE)
                    .edit().putBoolean(KEY_ENABLED, on).apply();
        } catch (Throwable t) {
            Log.w(TAG, "写入保活开关失败: " + t.getMessage());
        }
        if (!on) stop();
    }

    /** 幂等启动：已有锁/采样线程就只续一次期。 */
    public static synchronized void begin(Context ctx) {
        if (!enabled(ctx)) {
            Log.i(TAG, "保活已被偏好项关闭，跳过");
            return;
        }
        if (lock == null) {
            try {
                PowerManager pm = (PowerManager) ctx.getApplicationContext()
                        .getSystemService(Context.POWER_SERVICE);
                if (pm == null) return;
                lock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, LOCK_TAG);
                // 不计数：重复 acquire(timeout) 是「重置超时」而不是叠加引用，
                // 否则每续一次期就+1，永远 release 不完（经典坑）。
                lock.setReferenceCounted(false);
            } catch (Throwable t) {
                Log.e(TAG, "创建唤醒锁失败", t);
                lock = null;
                return;
            }
        }
        renew(WINDOW_MS);
        if (!running) {
            running = true;
            sampler = new Thread(new Runnable() {
                @Override
                public void run() {
                    sampleLoop();
                }
            }, "dsh-wakelock");
            sampler.setDaemon(true);
            sampler.start();
        }
    }

    /** 释放并停采样。服务销毁时调用。 */
    public static synchronized void stop() {
        running = false;
        if (sampler != null) {
            sampler.interrupt();
            sampler = null;
        }
        if (lock != null) {
            try {
                if (lock.isHeld()) lock.release();
            } catch (Throwable t) {
                Log.w(TAG, "释放唤醒锁失败: " + t.getMessage());
            }
            lock = null;
        }
        lastRenewAt = 0L;
        Log.i(TAG, "保活已停止");
    }

    public static boolean isHeld() {
        PowerManager.WakeLock l = lock;
        return l != null && l.isHeld();
    }

    private static void sampleLoop() {
        final int uid = android.os.Process.myUid();
        long lastRx = TrafficStats.getUidRxBytes(uid);
        long lastTx = TrafficStats.getUidTxBytes(uid);

        while (running) {
            try {
                Thread.sleep(SAMPLE_MS);
            } catch (InterruptedException e) {
                return;
            }
            long rx = TrafficStats.getUidRxBytes(uid);
            long tx = TrafficStats.getUidTxBytes(uid);
            long delta;
            if (rx < 0 || tx < 0) {
                // 少数机器不支持按 UID 统计（返回 UNSUPPORTED）。此时保守续期：
                // 保活失效的代价（任务断流）比多耗一点电更严重。
                delta = BYTES_THRESHOLD;
            } else {
                delta = (rx - lastRx) + (tx - lastTx);
            }
            lastRx = rx;
            lastTx = tx;
            if (delta >= BYTES_THRESHOLD) {
                renew(WINDOW_MS);
            }
        }
    }

    private static synchronized void renew(long windowMs) {
        PowerManager.WakeLock l = lock;
        if (l == null) return;
        try {
            // referenceCounted=false 时，重复 acquire(timeout) 只重置超时时间。
            l.acquire(windowMs);
            long now = System.currentTimeMillis();
            // 日志按 10 分钟节流：每次采样都打会把 logcat 刷满
            if (now - lastRenewAt > WINDOW_MS) {
                lastRenewAt = now;
                Log.i(TAG, "保活续期 " + (windowMs / 60000) + " 分钟（后台任务/同步可继续跑）");
            }
        } catch (Throwable t) {
            Log.w(TAG, "续期失败: " + t.getMessage());
        }
    }
}
