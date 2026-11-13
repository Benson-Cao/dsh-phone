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
 * 把 dsh 的**会话/工作区数据**同步到 NAS。
 *
 * ## 支持的传输协议
 *
 * - WebDAV：飞牛 fnOS 的 WebDAV（{@link DshWebDavClient}，纯 JDK，零第三方库）。
 * - SFTP：基于 SSH 的文件传输（{@link DshSftpClient}，依赖 JSch）。
 * - SMB：Windows/NAS 的 SMB/CIFS 共享（{@link DshSmbClient}，依赖 jcifs-ng）。
 *
 * 三者共用同一套同步与冲突策略，只是「传输层」不同。选择哪种协议由
 * {@code type} 配置字段决定，{@link #createTransport} 据此返回对应的
 * {@link NasTransport} 实现。
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
 */
public final class DshSync {

    private static final String TAG = "DshSync";
    private static final String PREFS = "dsh_sync";

    /** 传输层接口：三种协议（WebDAV / SFTP / SMB）共用同一组动作。 */
    public interface NasTransport {
        /** 连通性 + 鉴权探测。能连且凭证正确返回 true。永不抛异常。 */
        boolean ping();
        /** 最近一次失败的人类可读原因（ping/put 等失败后读取；成功后为空串）。 */
        String lastError();
        /** 递归创建远端目录（已存在视为成功）。 */
        boolean mkdirs(String relPath);
        /** 上传本地文件到远端 relPath。 */
        boolean put(String relPath, File local);
        /** 下载远端 relPath 到本地 dest（覆盖）。 */
        boolean get(String relPath, File dest);
        /** 列出远端某目录的直接子项文件名（不含路径）。失败返回空集合。 */
        java.util.Set<String> list(String relPath);
        /** 远端文件最后修改时间（毫秒）；取不到返回 0（调用方据此放弃覆盖）。 */
        long lastModified(String relPath);
        /** 释放底层连接。 */
        void close();
    }

    /** 远端根目录（相对各协议的基础连接，如 <share>/dsh-phone/storages）。 */
    static final String REMOTE_ROOT = "dsh-phone/storages";

    /**
     * 遥控电脑集群：NAS 上「宿主状态」JSON 的相对路径（与桌面发布脚本约定一致）。
     * 桌面端把在线/锁/心跳/任务队列写到这里，手机端（S54–S60）读取。
     */
    public static final String REMOTE_HOST_PATH = "dsh-phone/remote/host.json";
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

    /**
     * 旧版本把连接信息存成单个 {@code url}（仅 WebDAV）。新版本拆成
     * type/host/port/share。这里做一次迁移，保证已存的 WebDAV 连接不丢。
     */
    private static void migrateIfNeeded(Context ctx) {
        SharedPreferences p = prefs(ctx);
        if (!p.getString("host", "").isEmpty()) return;          // 已迁移
        String u = p.getString("url", "");
        String us = p.getString("user", "");
        if (u.isEmpty() || us.isEmpty()) return;
        StringBuilder h = new StringBuilder(), po = new StringBuilder(), sh = new StringBuilder();
        parseUrl(u, h, po, sh);
        String type = u.startsWith("smb://") ? "smb" : (u.startsWith("sftp://") ? "sftp" : "webdav");
        p.edit()
            .putString("type", type)
            .putString("host", h.toString())
            .putString("port", po.toString())
            .putString("share", sh.toString())
            .apply();
    }

    /** 把 http(s)://host:port/path 拆成 host / port / share(含前导/)。 */
    private static void parseUrl(String url, StringBuilder host, StringBuilder port, StringBuilder share) {
        String s = url == null ? "" : url.trim();
        int sc = s.indexOf("://");
        String rest = sc >= 0 ? s.substring(sc + 3) : s;
        int slash = rest.indexOf('/');
        String auth = slash >= 0 ? rest.substring(0, slash) : rest;
        String sh = slash >= 0 ? rest.substring(slash) : "";
        int at = auth.lastIndexOf('@');
        if (at >= 0) auth = auth.substring(at + 1);              // 去掉 user:pass@
        int colon = auth.lastIndexOf(':');
        if (colon >= 0) {
            host.append(auth.substring(0, colon));
            port.append(auth.substring(colon + 1));
        } else {
            host.append(auth);
        }
        share.append(sh);
    }

    /** 由 type/host/port/share 拼出给用户看的连接串（也写回 url 字段）。 */
    static String displayUrl(String type, String host, String port, String share) {
        String u = buildBaseUrl(type, host, port);
        if (share != null && !share.isEmpty()) {
            if (!share.startsWith("/")) u += "/";
            u += share;
        }
        return u;
    }

    /**
     * 由 type/host/port 构造 baseUrl。createTransport 与 displayUrl **必须共用**这一个
     * 实现，保证「显示的地址」=「实际请求的地址」——v30.29 真机实锉两者不一致的代价：
     * createTransport 容忍用户把完整 http:// 地址填进 host（v30.6），而 displayUrl
     * 不剥前缀直接拼 "http://" + host → 存出 "http://http://192.168.0.108:5005"，
     * 设备卡标题 hostOf() 只剥一次协议变成 "http:"。
     */
    static String buildBaseUrl(String type, String host, String port) {
        String h = host == null ? "" : host.trim();
        String lower = h.toLowerCase(java.util.Locale.ROOT);
        boolean hasScheme = lower.startsWith("http://") || lower.startsWith("https://")
                || lower.startsWith("smb://") || lower.startsWith("sftp://");
        StringBuilder u = new StringBuilder();
        if (hasScheme) {
            // 用户直接粘贴了完整地址：协议原样保留（无显式端口才补 :port）
            u.append(h);
            if (port != null && !port.isEmpty() && !h.matches(".*:\\d+$")) u.append(':').append(port);
        } else {
            if ("smb".equals(type)) u.append("smb://");
            else if ("sftp".equals(type)) u.append("sftp://");
            else u.append("http://");
            u.append(h);
            if (port != null && !port.isEmpty()) u.append(':').append(port);
        }
        return u.toString();
    }

    public static boolean isConfigured(Context ctx) {
        migrateIfNeeded(ctx);
        SharedPreferences p = prefs(ctx);
        return p.getString("host", "").trim().length() > 0
            && p.getString("user", "").trim().length() > 0;
    }

    /** 新签名：按协议保存完整连接配置。pass == null 表示「保留原密码」。 */
    public static void configure(Context ctx, String type, String host, String port,
                                 String share, String user, String pass, int intervalSec) {
        SharedPreferences.Editor e = prefs(ctx).edit()
            .putString("type", type == null ? "webdav" : type.trim())
            .putString("host", host == null ? "" : host.trim())
            .putString("port", port == null ? "" : port.trim())
            .putString("share", share == null ? "" : share.trim())
            .putString("user", user == null ? "" : user.trim())
            .putString("url", displayUrl(type, host, port, share))
            .putInt("interval", intervalSec < 30 ? 30 : intervalSec);
        if (pass != null) e.putString("pass", pass);
        e.apply();
        // 换连接 = 换目标，旧连通性结论作废（否则会一直挂在只读降级态）
        resetPing(ctx);
        Log.i(TAG, "同步配置已更新：type=" + type + " host=" + host + " interval=" + intervalSec + "s");
    }

    /**
     * v30.7：连同 S17 的同步选项一起保存。
     *
     * @param direction     {@link #DIR_BOTH} / {@link #DIR_UPLOAD} / {@link #DIR_MANUAL}
     * @param scopeMask     {@link #SCOPE_*} 位掩码
     * @param conflict      {@link #CF_KEEP} / {@link #CF_NEWEST}
     * @param offlineRO     离线时转只读
     * @param mountAsWs     挂载为工作区来源
     */
    public static void configure(Context ctx, String type, String host, String port,
                                 String share, String user, String pass, int intervalSec,
                                 String direction, int scopeMask, String conflict,
                                 boolean offlineRO, boolean mountAsWs) {
        configure(ctx, type, host, port, share, user, pass, intervalSec);
        setDirection(ctx, direction);
        setScopeMask(ctx, scopeMask);
        setConflictPolicy(ctx, conflict);
        setOfflineReadOnly(ctx, offlineRO);
        setMountAsWorkspace(ctx, mountAsWs);
    }

    /** 旧签名（WebDAV 兼容，SAF 桥仍在用）：从 url 解析成 host/port 后走新逻辑。 */
    public static void configure(Context ctx, String url, String user, String pass, int intervalSec) {
        migrateIfNeeded(ctx);
        StringBuilder h = new StringBuilder(), po = new StringBuilder(), sh = new StringBuilder();
        parseUrl(url, h, po, sh);
        String type = url.startsWith("smb://") ? "smb" : (url.startsWith("sftp://") ? "sftp" : "webdav");
        configure(ctx, type, h.toString(), po.toString(), sh.toString(), user, pass, intervalSec);
    }

    // ================= 同步选项（设计稿 v22 S17，全部真实生效） =================

    /** 同步方向取值。 */
    public static final String DIR_BOTH = "both";      // 双向（默认）
    public static final String DIR_UPLOAD = "upload";  // 仅上传：跳过下载，绝不覆盖本地
    public static final String DIR_MANUAL = "manual";  // 仅手动：定时任务不自动跑

    /** 同步范围位掩码。 */
    public static final int SCOPE_SESSION = 1;    // 会话（session*）
    public static final int SCOPE_WORKSPACE = 2;  // 工作区文件（workspace*）
    public static final int SCOPE_ARTIFACT = 4;   // 产物与轨迹（其余 json）
    public static final int SCOPE_ALL = SCOPE_SESSION | SCOPE_WORKSPACE | SCOPE_ARTIFACT;

    /** 冲突处理取值。 */
    public static final String CF_KEEP = "keep";        // 保留两份（默认，最安全）
    public static final String CF_NEWEST = "newest";    // 以最新为准（直接覆盖，不留副本）

    public static String direction(Context ctx) {
        String d = prefs(ctx).getString("direction", DIR_BOTH);
        return d == null || d.isEmpty() ? DIR_BOTH : d;
    }

    public static void setDirection(Context ctx, String d) {
        prefs(ctx).edit().putString("direction", d == null ? DIR_BOTH : d).apply();
        Log.i(TAG, "同步方向=" + d);
    }

    public static int scopeMask(Context ctx) {
        // 默认全选；显式存 0 视为「一项都没选」，用 containsKey 区分
        if (!prefs(ctx).contains("scope")) return SCOPE_ALL;
        return prefs(ctx).getInt("scope", SCOPE_ALL);
    }

    public static void setScopeMask(Context ctx, int mask) {
        prefs(ctx).edit().putInt("scope", mask).apply();
        Log.i(TAG, "同步范围 mask=" + mask);
    }

    public static String conflictPolicy(Context ctx) {
        String p = prefs(ctx).getString("conflict", CF_KEEP);
        return p == null || p.isEmpty() ? CF_KEEP : p;
    }

    public static void setConflictPolicy(Context ctx, String p) {
        prefs(ctx).edit().putString("conflict", p == null ? CF_KEEP : p).apply();
        Log.i(TAG, "冲突处理=" + p);
    }

    public static boolean offlineReadOnly(Context ctx) {
        return prefs(ctx).getBoolean("offlineReadOnly", true);
    }

    public static void setOfflineReadOnly(Context ctx, boolean on) {
        prefs(ctx).edit().putBoolean("offlineReadOnly", on).apply();
        Log.i(TAG, "离线转只读=" + on);
    }

    public static boolean mountAsWorkspace(Context ctx) {
        return prefs(ctx).getBoolean("mountAsWorkspace", false);
    }

    public static void setMountAsWorkspace(Context ctx, boolean on) {
        prefs(ctx).edit().putBoolean("mountAsWorkspace", on).apply();
        Log.i(TAG, "挂载为工作区来源=" + on);
    }

    // ---- 连通性状态：S18「NAS 离线转只读」的数据源 ----

    /** 记录一次探测结果（syncOnce 与手动测试都会调）。 */
    private static void markPing(Context ctx, boolean ok) {
        prefs(ctx).edit()
            .putBoolean("lastPingOk", ok)
            .putLong("lastPingAt", System.currentTimeMillis())
            .apply();
    }

    /** 最近一次探测是否成功。未探测过按「成功」处理（不误报降级）。 */
    public static boolean lastPingOk(Context ctx) {
        return prefs(ctx).getBoolean("lastPingOk", true);
    }

    public static long lastPingAt(Context ctx) {
        return prefs(ctx).getLong("lastPingAt", 0L);
    }

    /**
     * 当前是否处于「NAS 断连 → 只读」降级态（设计稿 v22 S18）。
     *
     * 条件：已配置 + 开了「离线时转只读」+ 最近一次探测失败。
     * 这是**挂载状态**触发的，不是 Agent 自己判断的——所以只读探针放在这里，
     * UI（首页横幅 / NAS 页）只读这个结论即可。
     */
    public static boolean readOnlyNow(Context ctx) {
        if (!isConfigured(ctx)) return false;
        if (!offlineReadOnly(ctx)) return false;
        return !lastPingOk(ctx);
    }

    /** 清掉连通性缓存（改配置后必须调，否则旧的失败状态会一直挂着只读）。 */
    public static void resetPing(Context ctx) {
        prefs(ctx).edit().remove("lastPingOk").remove("lastPingAt").apply();
    }

    /**
     * 主动探测一次 NAS 可达性并记录（供 UI 手动「重连」用）。
     *
     * @return true 表示此刻真的连得上
     */
    public static boolean probeHealth(Context ctx) {
        if (!isConfigured(ctx)) return false;
        boolean ok = testConnection(ctx, type(ctx), host(ctx), port(ctx), share(ctx),
                user(ctx), null);
        markPing(ctx, ok);
        return ok;
    }

    public static int intervalSec(Context ctx) {
        return prefs(ctx).getInt("interval", 60);
    }

    public static String type(Context ctx) {
        migrateIfNeeded(ctx);
        String t = prefs(ctx).getString("type", "webdav");
        return t == null || t.isEmpty() ? "webdav" : t;
    }

    public static String host(Context ctx) {
        migrateIfNeeded(ctx);
        return prefs(ctx).getString("host", "");
    }

    public static String port(Context ctx) {
        migrateIfNeeded(ctx);
        return prefs(ctx).getString("port", "");
    }

    public static String share(Context ctx) {
        migrateIfNeeded(ctx);
        return prefs(ctx).getString("share", "");
    }

    public static String user(Context ctx) {
        return prefs(ctx).getString("user", "");
    }

    /** 给用户看的连接串（含协议）。 */
    public static String url(Context ctx) {
        migrateIfNeeded(ctx);
        String stored = prefs(ctx).getString("url", "");
        if (!stored.isEmpty()) return stored;
        return displayUrl(type(ctx), host(ctx), port(ctx), share(ctx));
    }

    /**
     * 连通性测试（设置页「测试连接」）。不落盘；pass 为 null 时用已存密码。
     *
     * @return "ok" 或人类可读的失败原因（v30.6：直接透出到「连接并测试」的结果条）。
     */
    public static String testConnectionEx(Context ctx, String type, String host, String port,
                                          String share, String user, String pass) {
        if (host == null || host.trim().isEmpty()) return "地址不能为空";
        if (user == null || user.trim().isEmpty()) return "账号不能为空";
        NasTransport t = createTransport(
            type, host.trim(), port == null ? "" : port.trim(),
            share == null ? "" : share.trim(), user.trim(),
            pass == null ? prefs(ctx).getString("pass", "") : pass);
        try {
            return t.ping() ? "ok"
                : (t.lastError() == null || t.lastError().isEmpty()
                    ? "连接失败（无详细信息）" : t.lastError());
        } catch (Throwable e) {
            Log.w(TAG, "testConnection 异常", e);
            return "连接异常：" + e.getClass().getSimpleName();
        } finally {
            try { t.close(); } catch (Throwable ignored) { }
        }
    }

    /** 连通性测试（布尔版，S51 状态卡用）。 */
    public static boolean testConnection(Context ctx, String type, String host, String port,
                                         String share, String user, String pass) {
        return "ok".equals(testConnectionEx(ctx, type, host, port, share, user, pass));
    }

    /** 旧签名（S51 沿用）：从 url 解析协议后走新逻辑。 */
    public static boolean testConnection(Context ctx, String url, String user, String pass) {
        StringBuilder h = new StringBuilder(), po = new StringBuilder(), sh = new StringBuilder();
        parseUrl(url, h, po, sh);
        String type = url.startsWith("smb://") ? "smb" : (url.startsWith("sftp://") ? "sftp" : "webdav");
        return testConnection(ctx, type, h.toString(), po.toString(), sh.toString(), user, pass);
    }

    /** 清除 NAS 同步配置（凭据一并删除）。设置页「断开」按钮调用。 */
    public static void clear(Context ctx) {
        prefs(ctx).edit()
            .remove("url").remove("user").remove("pass").remove("interval")
            .remove("type").remove("host").remove("port").remove("share")
            .remove("direction").remove("scope").remove("conflict")
            .remove("offlineReadOnly").remove("mountAsWorkspace")
            .remove("lastPingOk").remove("lastPingAt")
            .apply();
        Log.i(TAG, "同步配置已清除");
    }

    // 密码不提供读取接口 —— 避免被日志/UI 意外带出

    /** 设备标识：用于冲突副本名，让用户一眼看出是哪台设备产生的冲突。 */
    static String deviceTag() {
        String m = Build.MODEL == null ? "" : Build.MODEL.trim();
        if (m.length() == 0) return "dev";
        String s = m.replaceAll("[\\s/\\\\]+", "-");
        return s.length() > 20 ? s.substring(0, 20) : s;
    }

    // ================= 传输工厂 =================

    /** 用当前已存配置创建传输实例。 */
    static NasTransport createTransport(Context ctx) {
        SharedPreferences p = prefs(ctx);
        return createTransport(
            p.getString("type", "webdav"),
            p.getString("host", ""),
            p.getString("port", ""),
            p.getString("share", ""),
            p.getString("user", ""),
            p.getString("pass", ""));
    }

    /** 用给定参数创建传输实例（按协议分派实现）。 */
    static NasTransport createTransport(String type, String host, String port,
                                       String share, String user, String pass) {
        if ("smb".equals(type)) return new DshSmbClient(host, port, share, user, pass);
        if ("sftp".equals(type)) return new DshSftpClient(host, port, share, user, pass);
        // 默认 WebDAV/SMB/SFTP 的 baseUrl 统一走 buildBaseUrl（与 displayUrl 共用，
        // 保证显示 = 实际请求；host 容忍用户直接粘贴完整 http(s):// 地址，v30.6/v30.30）。
        return new DshWebDavClient(buildBaseUrl(type, host, port), user, pass);
    }

    // ================= 同步主流程 =================

    /**
     * 执行一次同步。**必须在后台线程调用**（会做网络 IO）。
     */
    public static Result syncOnce(Context ctx) {
        Result r;
        try {
            r = syncOnceInner(ctx);
        } catch (Throwable t) {
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
        String host = p.getString("host", "").trim();
        String user = p.getString("user", "").trim();
        String pass = p.getString("pass", "");
        if (host.length() == 0 || user.length() == 0) {
            r.message = "未配置";
            return r;
        }
        r.ran = true;

        // v30.7：S17 的三个选项在这里真实生效，不是摆设
        final String dir = direction(ctx);
        final boolean uploadOnly = DIR_UPLOAD.equals(dir);
        final boolean newestWins = CF_NEWEST.equals(conflictPolicy(ctx));
        final int scope = scopeMask(ctx);

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

        NasTransport tx = createTransport(ctx);
        try {
            if (!tx.ping()) {
                String why = tx.lastError();
                markPing(ctx, false);
                r.message = "无法连接 NAS" + (why == null || why.isEmpty()
                        ? "（检查地址/凭证/是否开启对应服务）" : ("：" + why));
                return r;
            }
            markPing(ctx, true);
            if (!tx.mkdirs(REMOTE_ROOT)) {
                r.message = "无法创建远端目录（检查共享目录权限）";
                return r;
            }

            String stampKey = "lastSync_" + REMOTE_ROOT;
            long lastSync = p.getLong(stampKey, 0L);
            java.util.Set<String> remoteNames = tx.list(REMOTE_ROOT);

            // ---- 1) 上传：本地比上次同步新、或远端没有 ----
            for (File f : localFiles) {
                if (!f.isFile()) continue;
                if (f.getName().contains(CONFLICT_MARK)) continue;   // 冲突副本不参与同步
                if (!inScope(f.getName(), scope)) continue;           // v30.7：同步范围过滤
                String remotePath = REMOTE_ROOT + "/" + f.getName();
                boolean remoteExists = remoteNames.contains(f.getName());
                boolean changed = f.lastModified() > lastSync;
                if (!changed && !remoteExists) continue;
                if (!changed && remoteExists) continue;               // 交给下载分支
                if (tx.put(remotePath, f)) {
                    r.uploaded++;
                    p.edit().putLong("synced_" + remotePath, f.lastModified()).apply();
                } else {
                    r.message = "上传失败: " + f.getName();
                }
            }

            // ---- 2) 下载：仅上传模式下整段跳过（本地绝不被动）----
            if (!uploadOnly) {
                for (String name : remoteNames) {
                    if (name.contains(CONFLICT_MARK)) continue;
                    if (!inScope(name, scope)) continue;
                    File local = new File(localDir, name);
                    String remotePath = REMOTE_ROOT + "/" + name;
                    long remoteStamp = tx.lastModified(remotePath);   // 0 =取不到
                    boolean localExists = local.isFile();

                    if (!localExists) {
                        if (tx.get(remotePath, local)) {
                            r.downloaded++;
                            Log.i(TAG, "从 NAS 拉取: " + name);
                        }
                        continue;
                    }
                    if (remoteStamp <= 0) continue;                  // 取不到 mtime 不冒险覆盖
                    boolean remoteNewer = remoteStamp > lastSync;
                    boolean localChanged = local.lastModified() > lastSync;
                    if (!remoteNewer) continue;

                    if (localChanged && !newestWins) {
                        // ⚠️ 两边都改过 + 保留两份 → 绝不静默覆盖
                        File kept = keepConflictCopy(local);
                        if (kept != null) r.conflicts++;
                        Log.w(TAG, "检测到冲突（本地与 NAS 都改过），已保留双份: " + name);
                        if (tx.get(remotePath, local)) r.downloaded++;
                    } else {
                        // 以最新为准（含 localChanged 情况）：直接采信远端
                        if (tx.get(remotePath, local)) r.downloaded++;
                    }
                }
            }

            p.edit().putLong(stampKey, System.currentTimeMillis()).apply();
            Log.i(TAG, "同步完成: ↑" + r.uploaded + " ↓" + r.downloaded
                    + " 冲突" + r.conflicts + " 方向=" + dir);
            if ("".equals(r.message)) r.message = "ok";
        } catch (Throwable t) {
            r.message = "同步异常: " + t.getClass().getSimpleName();
            Log.w(TAG, "同步异常", t);
        } finally {
            try { tx.close(); } catch (Throwable ignored) { }
        }
        return r;
    }

    /**
     * 文件名是否落在当前同步范围内（v30.7）。
     *
     * <p>映射规则（对应 S17「会话 · 工作区文件 · 产物与轨迹」三项）：
     * <ul>
     *   <li>{@code session*} → 会话</li>
     *   <li>{@code workspace*} → 工作区文件</li>
     *   <li>其余 {@code .json}（产物/轨迹索引）→ 产物与轨迹</li>
     * </ul>
     * 非 json（如 lock 临时文件）任何范围都不含，避免把运行时垃圾同步上去。
     */
    static boolean inScope(String name, int mask) {
        if (name == null || name.isEmpty()) return false;
        String n = name.toLowerCase(Locale.US);
        if (n.endsWith(".tmp") || n.endsWith(".lock") || n.endsWith("~")) return false;
        if (n.startsWith("session")) return (mask & SCOPE_SESSION) != 0;
        if (n.startsWith("workspace")) return (mask & SCOPE_WORKSPACE) != 0;
        if (n.endsWith(".json")) return (mask & SCOPE_ARTIFACT) != 0;
        return false;
    }

    /**
     * 把本地文件另存为冲突副本，返回新文件。
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

    // ================= 遥控电脑集群：远端宿主状态读写 =================

    /**
     * 读取 NAS 上的任意文本文件内容（遥控电脑集群用：读取桌面宿主发布的 host.json）。
     *
     * <p>必须在后台线程调用。未配置 / 连不上 / 远端没有该文件 / 解析 IO 异常，均返回 {@code null}；
     * 失败时 {@link NasTransport#lastError()} 可取人类可读原因（调用方也可忽略返回值）。
     *
     * <p>路径约定见 {@link #REMOTE_HOST_PATH}；相对路径以 NAS 共享根目录为基准，与
     * {@link #REMOTE_ROOT} 同构。
     */
    public static String readRemoteText(Context ctx, String relPath) {
        if (!isConfigured(ctx)) return null;
        File tmp = null;
        NasTransport tx = createTransport(ctx);
        try {
            if (!tx.ping()) { markPing(ctx, false); return null; }
            markPing(ctx, true);
            tmp = File.createTempFile("dsh_remote_", ".tmp", ctx.getCacheDir());
            if (!tx.get(relPath, tmp)) return null;
            return readFileText(tmp);
        } catch (Throwable t) {
            Log.w(TAG, "readRemoteText 失败: " + relPath, t);
            return null;
        } finally {
            try { tx.close(); } catch (Throwable ignored) { }
            if (tmp != null) try { tmp.delete(); } catch (Throwable ignored) { }
        }
    }

    /**
     * 写入 NAS 上的任意文本文件（备用：桌面发布脚本一般直接用 WebDAV PUT；
     * 手机侧目前以只读为主，此方法留作本地运行时回写自身状态等场景）。
     *
     * @return 是否写入成功
     */
    public static boolean writeRemoteText(Context ctx, String relPath, String text) {
        if (!isConfigured(ctx)) return false;
        File tmp = null;
        NasTransport tx = createTransport(ctx);
        try {
            if (!tx.ping()) { markPing(ctx, false); return false; }
            markPing(ctx, true);
            int slash = relPath.lastIndexOf('/');
            if (slash > 0) tx.mkdirs(relPath.substring(0, slash));
            tmp = File.createTempFile("dsh_remote_", ".tmp", ctx.getCacheDir());
            java.io.FileOutputStream out = new java.io.FileOutputStream(tmp);
            try {
                out.write(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            } finally {
                try { out.close(); } catch (Throwable ignored) { }
            }
            return tx.put(relPath, tmp);
        } catch (Throwable t) {
            Log.w(TAG, "writeRemoteText 失败: " + relPath, t);
            return false;
        } finally {
            try { tx.close(); } catch (Throwable ignored) { }
            if (tmp != null) try { tmp.delete(); } catch (Throwable ignored) { }
        }
    }

    /**
     * 列出 NAS 共享根目录下某相对路径的文件/目录名（供 NativeBridge 的 nas.list 调用）。
     * 内部复用已持久化的连接配置；未配置或连通失败返回空集合（不抛异常、不造假数据）。
     */
    public static java.util.Set<String> listRemote(Context ctx, String relPath) {
        if (!isConfigured(ctx)) return new java.util.HashSet<>();
        NasTransport tx = createTransport(ctx);
        try {
            if (!tx.ping()) { markPing(ctx, false); return new java.util.HashSet<>(); }
            markPing(ctx, true);
            return tx.list(relPath);
        } catch (Throwable t) {
            Log.w(TAG, "listRemote 失败: " + relPath, t);
            return new java.util.HashSet<>();
        } finally {
            try { tx.close(); } catch (Throwable ignored) { }
        }
    }

    /** 读取整个文件为 UTF-8 文本（手动循环，避免 Files.readAllBytes 的 API 级别要求）。 */
    private static String readFileText(File f) throws java.io.IOException {
        java.io.FileInputStream in = new java.io.FileInputStream(f);
        try {
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return new String(bos.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
        } finally {
            try { in.close(); } catch (Throwable ignored) { }
        }
    }
}
