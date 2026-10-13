package com.dsh.harness;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 把 dsh 的**会话/工作区数据**同步到 NAS（飞牛 fnOS 的 WebDAV）。
 *
 * ## 同步什么、不同步什么（这是本类最重要的设计约束）
 *
 * 同步：{@code .dsh/storages/} 下的会话索引与工作区数据 —— 这是跨端该共享的部分。
 * **不同步**：{@code .dsh/profiles/}（含 node_modules / cordis.yml / 平台二进制）。
 *
 * <p>⚠️ 绝对不能整个 {@code .dsh} 目录同步：那些文件是**平台相关**的
 *（Termux 的 {@code .so} 与 Windows 的 {@code .node} 根本无法互换），
 * 整目录同步会把两端的插件环境互相破坏，且症状隐蔽（"装好的插件突然失效"）。
 *
 * ## 冲突策略：保留双份，不静默覆盖
 *
 * 判定：远端存在同名文件，且**本地与远端内容不同**，且本地 mtime 比上次同步新
 * → 说明两边都改过。此时把本地另存为 {@code xxx.conflict-<设备>-<时间戳>.json}，
 * 再用远端版本作为最新同步下去。
 *
 * <p>这样做的理由：会话数据丢一份的代价远高于多留一个文件。用户自己合并即可，
 * 而静默覆盖（按 mtime 决胜）会让人**在几天后才发现丢了对话**。
 *
 * ## 为什么不用「目录直接指向 NAS」
 *
 * Android 应用无法把网络文件系统挂成普通目录（需 root；SAF 不支持 SMB/NFS），
 * 详见 {@code docs/nas-sync-feasibility.md}。所以数据留本地，只同步选定文件。
 *
 * @see DshWebDavClient
 */
public final class DshSync {

    private static final String TAG = "DshSync";
    private static final String PREFS = "dsh_sync";

    /** 远端根目录（对应 NAS 上给 dsh 用的文件夹）。 */
    private static final String REMOTE_ROOT = "dsh-phone/storages";
    /** 本地源目录（相对 DshBootstrap.home()）。 */
    private static final String LOCAL_REL = ".dsh/storages";
    /** 冲突副本后缀。 */
    private static final String CONFLICT_MARK = ".conflict-";

    /** 同步结果，供 UI 展示。 */
    public static final class Result {
        public boolean ran = false;          // 是否真的执行了（未配置则false）
        public int uploaded = 0;
        public int downloaded = 0;
        public int conflicts = 0;
        public String message = "";
        public boolean ok() { return ran && "".equals(message); }
    }

    private DshSync() {}

    // ================= 最近一次结果（跨线程共享）=================
    // 同步跑在后台线程、UI 在主线程读，所以必须 volatile；
    // 只保留"最近一次"——历史同步记录对用户没有价值，存着只会占内存。
    private static volatile String sLastResultJson = "{}";

    /** 最近一次同步结果的 JSON，供 {@code DshSafBridge.syncLastResult()} 返回。 */
    public static String lastResultJson(Context ctx) {
        return sLastResultJson;
    }

    private static void recordResult(Result r) {
        try {
            org.json.JSONObject o = new org.json.JSONObject();
            o.put("ran", r.ran);
            o.put("uploaded", r.uploaded);
            o.put("downloaded", r.downloaded);
            o.put("conflicts", r.conflicts);
            o.put("message", r.message);
            o.put("at", System.currentTimeMillis());
            sLastResultJson = o.toString();
        } catch (Throwable ignored) { }
    }

    // ================= 配置 =================

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static boolean isConfigured(Context ctx) {
        SharedPreferences p = prefs(ctx);
        return p.getString("url", "").trim().length() > 0
            && p.getString("user", "").trim().length() > 0;
    }

    public static void configure(Context ctx, String url, String user, String pass, int intervalSec) {
        prefs(ctx).edit()
            .putString("url", url == null ? "" : url.trim())
            .putString("user", user == null ? "" : user.trim())
            .putString("pass", pass == null ? "" : pass)
            .putInt("interval", intervalSec < 30 ? 30 : intervalSec)
            .apply();
        Log.i(TAG, "同步配置已更新：url=" + url + " interval=" + intervalSec + "s");
    }

    public static int intervalSec(Context ctx) {
        return prefs(ctx).getInt("interval", 60);
    }

    public static String url(Context ctx)   { return prefs(ctx).getString("url", ""); }
    public static String user(Context ctx)  { return prefs(ctx).getString("user", ""); }
    // 密码不提供读取接口 —— 避免被日志/UI 意外带出

    /** 设备标识：用于冲突副本名，让用户一眼看出是哪台设备产生的冲突。 */
    static String deviceTag() {
        String m = Build.MODEL == null ? "" : Build.MODEL.trim();
        if (m.length() == 0) return "dev";
        // 去掉空格与斜杠，避免文件名问题
        String s = m.replaceAll("[\\s/\\\\]+", "-");
        return s.length() > 20 ? s.substring(0, 20) : s;
    }

    // ================= 同步主流程 =================

    /**
     * 执行一次同步。**必须在后台线程调用**（会做网络 IO）。
     *
     * <p>设计为「永不抛异常」：NAS 掉线/凭证错误都只体现在 {@link Result#message}，
     * 绝不让同步异常波及dsh 的启动与保活流程。
     */
    public static Result syncOnce(Context ctx) {
        Result r;
        try {
            r = syncOnceInner(ctx);
        } catch (Throwable t) {
            // 最外层兜底：syncOnce 内层已经try 过，但万一有遗漏也不能抛给调用方
            r = new Result();
            r.message = "同步异常: " + t.getClass().getSimpleName();
            Log.w(TAG, "syncOnce 外层兜底", t);
        }
        recordResult(r);
        return r;
    }

    private static Result syncOnceInner(Context ctx) {
        Result r = new Result();
        SharedPreferences p = prefs(ctx);
        String url = p.getString("url", "").trim();
        String user = p.getString("user", "").trim();
        String pass = p.getString("pass", "");
        if (url.length() == 0 || user.length() == 0) {
            r.message = "未配置";
            return r;
        }
        r.ran = true;

        File localDir = new File(DshBootstrap.home(ctx), LOCAL_REL);
        //⚠️ 本地目录不存在 = 用户还没产生过任何会话，不做任何事（也不该为此在 NAS 上建目录）
        if (!localDir.isDirectory()) {
            r.message = "尚无会话数据";
            return r;
        }
        File[] localFiles = localDir.listFiles();
        if (localFiles == null || localFiles.length == 0) {
            r.message = "本地无文件";
            return r;
        }

        DshWebDavClient dav = new DshWebDavClient(url, user, pass);
        try {
            if (!dav.ping()) {
                r.message = "无法连接 NAS（检查地址/凭证/是否开启 WebDAV）";
                return r;
            }
            if (!dav.mkdirs(REMOTE_ROOT)) {
                r.message = "无法创建远端目录（检查团队文件夹是否允许协议挂载）";
                return r;
            }

            String stampKey = "lastSync_" + REMOTE_ROOT;
            long lastSync = p.getLong(stampKey, 0L);
            java.util.Set<String> remoteNames = dav.list(REMOTE_ROOT);

            // ---- 1) 上传：本地比上次同步新、或远端没有 ----
            for (File f : localFiles) {
                if (!f.isFile()) continue;
                if (f.getName().contains(CONFLICT_MARK)) continue;   // 冲突副本不参与同步
                String remotePath = REMOTE_ROOT + "/" + f.getName();
                boolean remoteExists = remoteNames.contains(f.getName());
                boolean changed = f.lastModified() > lastSync;
                if (!changed && !remoteExists) continue;
                if (!changed && remoteExists) {
                    // 远端有且本地没更新 —— 交给下载分支处理
                    continue;
                }
                if (dav.put(remotePath, f)) {
                    r.uploaded++;
                    // 记住已上传，避免下次重复 PUT
                    p.edit().putLong("synced_" + remotePath, f.lastModified()).apply();
                } else {
                    r.message = "上传失败: " + f.getName();
                }
            }

            // ---- 2) 下载：远端更新（mtime 比上次同步新）或本地缺失 ----
            for (String name : remoteNames) {
                if (name.contains(CONFLICT_MARK)) continue;
                File local = new File(localDir, name);
                String remotePath = REMOTE_ROOT + "/" + name;
                long remoteStamp = dav.lastModified(remotePath);   // 0 =取不到
                boolean localExists = local.isFile();

                if (!localExists) {
                    if (dav.get(remotePath, local)) {
                        r.downloaded++;
                        Log.i(TAG, "从 NAS 拉取: " + name);
                    }
                    continue;
                }
                // 远端取不到 mtime 时不冒险覆盖（宁可不同步，也别丢数据）
                if (remoteStamp <= 0) continue;
                boolean remoteNewer = remoteStamp > lastSync;
                boolean localChanged = local.lastModified() > lastSync;
                if (!remoteNewer) continue;

                if (localChanged) {
                    // ⚠️ 两边都改过 → 保留双份，绝不静默覆盖
                    File kept = keepConflictCopy(local);
                    if (kept != null) r.conflicts++;
                    Log.w(TAG, "检测到冲突（本地与 NAS 都改过），已保留双份: " + name);
                    // 冲突副本留本地不上传（避免污染 NAS），把远端版本拉下来
                    if (dav.get(remotePath, local)) r.downloaded++;
                } else {
                    if (dav.get(remotePath, local)) r.downloaded++;
                }
            }

            p.edit().putLong(stampKey, System.currentTimeMillis()).apply();
            Log.i(TAG, "同步完成: ↑" + r.uploaded + " ↓" + r.downloaded
                    + " 冲突" + r.conflicts);
            if ("".equals(r.message)) r.message = "ok";
        } catch (Throwable t) {
            // 兜底：同步绝不能把异常抛给调用方
            r.message = "同步异常: " + t.getClass().getSimpleName();
            Log.w(TAG, "同步异常", t);
        }
        return r;
    }

    /**
     * 把本地文件另存为冲突副本，返回新文件。
     *
     * <p>命名：{@code <原名去扩展>.conflict-<设备>-<yyyyMMdd-HHmmss>.<扩展>}
     * 例：{@code workspace.conflict-Pixel-7-20261013-134512.json}
     */
    static File keepConflictCopy(File src) {
        try {
            String name = src.getName();
            int dot = name.lastIndexOf('.');
            String stem = dot > 0 ? name.substring(0, dot) : name;
            String ext = dot > 0 ? name.substring(dot) : "";
            String ts = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
                    .format(new Date());
            File dst = new File(src.getParentFile(),
                    stem + CONFLICT_MARK + deviceTag() + "-" + ts + ext);
            java.io.FileInputStream in = new java.io.FileInputStream(src);
            java.io.FileOutputStream out = new java.io.FileOutputStream(dst);
            try {
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            } finally {
                try { in.close(); } catch (Exception ignored) { }
                try { out.close(); } catch (Exception ignored) { }
            }
            Log.i(TAG, "冲突副本: " + dst.getName());
            return dst;
        } catch (Throwable t) {
            Log.w(TAG, "保留冲突副本失败: " + t.getMessage());
            return null;
        }
    }
}