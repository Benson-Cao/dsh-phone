package com.dsh.harness;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

/**
 * 让 dsh 服务**跨 App 生命周期常驻**的前台服务。
 *
 * ## 为什么需要
 * 用户实测：第二次冷启动仍要 8s。根因是 Android 按 UID 杀整个进程组 ——
 * App 被划掉/系统回收后，dsh 子进程一起死，下次启动必须重新
 * 冷启动 node 加载 73 个插件包/7473 个 JS 文件。
 *
 * 前台服务让 dsh 在 App 退到后台甚至界面关闭后仍存活，
 * 下次打开只需探测端口（<1s），实测 8s → 亚秒级。
 *
 * ## 关键设计
 * - {@code START_STICKY}：进程被系统杀死后系统会重启服务并重新拉起 dsh。
 * - 通知常驻（用户可见，这是前台服务的硬性要求，也是可接受的代价）。
 * - **不重复拉起**：onStartCommand 里先探测端口，已在跑就只更新通知。
 * - 用 {@code startForeground} 而非仅 {@code startService}，
 *   否则系统会在几分钟内以"后台服务"名义杀掉它。
 *
 * ## targetSdk=28 的好处（当前配置）
 * - 无需 `android:foregroundServiceType`（Android 14+ 才强制）
 * - 无需 POST_NOTIFICATIONS 运行时权限（Android 13 才要求，且 targetSdk<33 时
 *   系统仍会弹窗，但不会因"未授权"而 crash）
 */
public final class DshService extends Service {

    private static final String TAG = "DshService";
    private static final String CHANNEL_ID = "dsh_service";
    private static final int NOTIF_ID = 0xD511;

    public static void start(Context ctx) {
        try {
            Intent i = new Intent(ctx, DshService.class);
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i);
            else ctx.startService(i);
            Log.i(TAG, "已请求启动常驻服务");
        } catch (Exception e) {
            // Android 12+ 不允许从后台启动前台服务；但我们从 Activity 调，正常不会失败
            Log.e(TAG, "启动服务失败", e);
        }
    }

    public static void stop(Context ctx) {
        try {
            ctx.stopService(new Intent(ctx, DshService.class));
        } catch (Exception e) {
            Log.e(TAG, "停止服务失败", e);
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;   // 不需要绑定
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForegroundCompat();
        ensureDshRunning();
        // START_STICKY：被系统杀掉后自动重启并回调 onStartCommand
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        Log.i(TAG, "服务被销毁");
        super.onDestroy();
    }

    /** 拉起 dsh（幂等）：已在监听就跳过。 */
    private void ensureDshRunning() {
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    if (DshProcessManager.isPortOpen(DshProcessManager.DSH_PORT)) {
                        Log.i(TAG, "dsh 已在运行，跳过拉起");
                        return;
                    }
                    DshBootstrap.setupIfNeeded(DshService.this);
                    String err = DshBootstrap.getLastError();
                    if (err != null) {
                        Log.e(TAG, "环境准备失败: " + err);
                        updateNotification("环境准备失败");
                        return;
                    }
                    boolean ok = DshProcessManager.start(DshService.this);
                    Log.i(TAG, "拉起 dsh: " + (ok ? "成功" : "失败"));
                    updateNotification(null);
                } catch (Throwable t) {
                    Log.e(TAG, "保活异常", t);
                    updateNotification("保活异常");
                }
            }
        }, "dsh-keepalive").start();
    }

    private void updateNotification(final String text) {
        if (text == null) return;
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.notify(NOTIF_ID, buildNotification(text));
        } catch (Exception e) {
            Log.e(TAG, "更新通知失败", e);
        }
    }

    private void startForegroundCompat() {
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
                if (nm != null && nm.getNotificationChannel(CHANNEL_ID) == null) {
                    NotificationChannel ch = new NotificationChannel(
                        CHANNEL_ID, "本地服务", NotificationManager.IMPORTANCE_LOW);
                    ch.setDescription("保持 dsh 本地服务常驻运行");
                    ch.setShowBadge(false);
                    nm.createNotificationChannel(ch);
                }
            }
            startForeground(NOTIF_ID, buildNotification(null));
        } catch (Exception e) {
            // Android 12+ 前台服务启动失败会抛 ForegroundServiceStartNotAllowedException。
            // 此时 App 仍能正常用（只是失去保活），不能因此崩。
            Log.e(TAG, "startForeground 失败，降级为普通后台服务", e);
        }
    }

    private Notification buildNotification(String text) {
        String title = "DeepSeek Harness";
        String body = text != null ? text : "本地服务运行中";
        Intent open = getPackageManager().getLaunchIntentForPackage(getPackageName());
        PendingIntent pi = null;
        if (open != null) {
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
            pi = PendingIntent.getActivity(this, 0, open, flags);
        }
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            b = new Notification.Builder(this, CHANNEL_ID);
        } else {
            b = new Notification.Builder(this);
            b.setPriority(Notification.PRIORITY_LOW);
        }
        b.setContentTitle(title)
            .setContentText(body)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setOngoing(true);
        if (pi != null) b.setContentIntent(pi);
        return b.build();
    }
}