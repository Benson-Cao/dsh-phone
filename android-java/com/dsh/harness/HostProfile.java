package com.dsh.harness;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONObject;

import java.net.URLEncoder;

/**
 * v2.3 三档案模型（S-6.4 / S-6.7）：手机壳以「档案」为单位决定
 * WebView 开哪个 dsh、桥能不能注入、cookie 归谁。
 *
 * <h3>三种档案</h3>
 * <ul>
 *   <li><b>local</b> —— 本机引擎：Termux 底座在本 App 内拉起 node，WebView 开
 *       {@code http://127.0.0.1:3080}。现状行为的唯一档案。</li>
 *   <li><b>ssh</b> —— SSH 隧道档：App 内起 {@code ssh -L 127.0.0.1:<localPort>:127.0.0.1:3080}
 *       连到远端机器，WebView 开 {@code http://127.0.0.1:<localPort>}。
 *       对 WebView 而言仍是回环 —— A-02 的信任边界不变、桥照常注入。
 *       隧道本身批 C 接（TOFU 指纹、断线重连），本批先落档案与占位页。</li>
 *   <li><b>relay</b> —— Relay 反代档（用户裁定：直连 Relay origin，不走 node 反代）：
 *       WebView 开 {@code <origin>/d/<hostId>/ui/?pt=<deviceToken>}（r18 反代形态，
 *       令牌载体是 {@code ?pt=} query）。<b>远端 origin</b> ——
 *       一切 JS 桥（dshNative / dshSaf / dsh-bridge）与脚本注入**全部关闭**：
 *       桥能下载并执行代码，绝不能暴露给远端页面。</li>
 * </ul>
 *
 * <h3>为什么不用 EngineEndpoints 当档案存储</h3>
 * EngineEndpoints 是「三引擎切换」的端点清单（本机 3080 / 远程 3082 反代），
 * 全部是回环 URL、桥照常注入 —— 它是 <b>local 档内部</b>的一个子功能。
 * 档案是更高一层：决定「有没有本机引擎」「桥开不开」「cookie 归谁」，
 * 两者正交，不合并（否则 EngineEndpoints 的 JSON 里会混进 token 这类高敏字段）。
 *
 * <h3>存储</h3>
 * SharedPreferences（与 EngineEndpoints 同一形态）：小、同步读、无迁移负担。
 * deviceToken 属高敏信息，存 app 私有目录（MODE_PRIVATE），不落外部存储、不打日志。
 *
 * <h3>切换语义（本批落骨架，悬浮球 UI 批 D）</h3>
 * {@link #setCurrent} 只持久化；真正的「切」由 MainActivity.switchProfile() 执行：
 * 清空进程级 cookie（挡 R-22 串会话）→ 销毁 WebView → recreate()，
 * onCreate 按新档案走对应分支。CookieManager 是进程级单例，切档案必须清，
 * 否则上一台主机的 dsh-auth / 反代会话 cookie 会带进下一台。
 */
public final class HostProfile {

    private static final String TAG = "DshProfile";
    private static final String PREF = "dsh_profiles";
    private static final String KEY_STATE = "state";

    public static final String KIND_LOCAL = "local";
    public static final String KIND_SSH = "ssh";
    public static final String KIND_RELAY = "relay";

    /** 懒初始化单例；Context 一律取 ApplicationContext，避免泄漏 Activity。 */
    private static volatile HostProfile sInstance;

    /** 当前档案。不可变快照，读侧无锁。 */
    private volatile Profile cur;

    private final Context appCtx;

    private HostProfile(Context ctx) {
        this.appCtx = ctx.getApplicationContext();
        this.cur = read();
    }

    public static HostProfile get(Context ctx) {
        if (sInstance == null) {
            synchronized (HostProfile.class) {
                if (sInstance == null) sInstance = new HostProfile(ctx);
            }
        }
        return sInstance;
    }

    // ==================================================================
    // Profile：不可变档案快照
    // ==================================================================

    public static final class Profile {
        public final String kind;      // KIND_LOCAL / KIND_SSH / KIND_RELAY
        // relay
        public final String origin;    // 形如 https://r.example.com（无尾斜杠）
        public final String hostId;    // Relay 侧主机 id（CP 层分配）
        public final String deviceToken; // 手机配对令牌（?pt= 载体）——高敏，禁止打日志
        // ssh
        public final String sshHost;
        public final int sshPort;
        public final String sshUser;
        public final int localPort;    // 隧道本地端口（ssh 档的 WebView 地址）

        Profile(String kind, String origin, String hostId, String deviceToken,
                String sshHost, int sshPort, String sshUser, int localPort) {
            this.kind = kind;
            this.origin = origin;
            this.hostId = hostId;
            this.deviceToken = deviceToken;
            this.sshHost = sshHost;
            this.sshPort = sshPort;
            this.sshUser = sshUser;
            this.localPort = localPort;
        }

        /** 桥（dshNative / dshSaf / dsh-bridge / 脚本注入）是否允许。 */
        public boolean bridgeAllowed() { return !KIND_RELAY.equals(kind); }

        /** 是否需要在本机拉起 dsh 引擎（只有 local 档需要）。 */
        public boolean needsLocalEngine() { return KIND_LOCAL.equals(kind); }

        /** relay 三要素是否齐（origin/hostId/token），不齐=未配置。 */
        public boolean relayConfigured() {
            return KIND_RELAY.equals(kind)
                && origin != null && origin.length() > 0
                && hostId != null && hostId.length() > 0
                && deviceToken != null && deviceToken.length() > 0;
        }

        /** ssh 连接参数是否齐。 */
        public boolean sshConfigured() {
            return KIND_SSH.equals(kind)
                && sshHost != null && sshHost.length() > 0
                && sshUser != null && sshUser.length() > 0
                && sshPort > 0 && localPort > 0;
        }

        /**
         * WebView 应加载的完整 URL（fail-safe：任何不确定都回落本机 3080）。
         * relay 形态来自 r18 反代（docs/04 §6）：{@code /d/<hostId>/ui/?pt=<token>}。
         */
        public String loadUrl() {
            if (KIND_RELAY.equals(kind)) {
                if (!relayConfigured()) return localUrl();
                try {
                    return relayUrlOf(origin, hostId, deviceToken);
                } catch (Throwable t) {
                    Log.w(TAG, "relayUrl 构造失败，回落本机: " + t.getMessage());
                    return localUrl();
                }
            }
            if (KIND_SSH.equals(kind)) {
                return "http://127.0.0.1:" + (localPort > 0 ? localPort : 3081) + "/";
            }
            return localUrl();
        }

        /**
         * 本档案的「可信来源基座」（scheme://host[:port]，无路径）。
         * 外链放行 / cookie 清理 / 错误页重试都以它为界。
         */
        public String baseOrigin() {
            if (KIND_RELAY.equals(kind) && relayConfigured()) {
                return normalizeOrigin(origin);
            }
            if (KIND_SSH.equals(kind)) {
                return "http://127.0.0.1:" + (localPort > 0 ? localPort : 3081);
            }
            return "http://127.0.0.1:" + DshProcessManager.DSH_PORT;
        }

        /** 日志友好描述；deviceToken 一律不出现。 */
        public String describe() {
            if (KIND_RELAY.equals(kind)) {
                return "relay origin=" + origin + " hostId=" + hostId
                    + " token=" + (deviceToken != null && deviceToken.length() > 0 ? "已配置" : "缺失");
            }
            if (KIND_SSH.equals(kind)) {
                return "ssh " + sshUser + "@" + sshHost + ":" + sshPort
                    + " -> 127.0.0.1:" + localPort;
            }
            return "local 127.0.0.1:" + DshProcessManager.DSH_PORT;
        }
    }

    // ==================================================================
    // 纯函数（无 android 依赖，可单测）
    // ==================================================================

    /** 本机档案的固定地址。 */
    static String localUrl() {
        return "http://127.0.0.1:" + DshProcessManager.DSH_PORT + "/";
    }

    /**
     * Relay 档的完整加载地址（r18 反代形态）。
     * @throws IllegalArgumentException 任一要素为空
     */
    public static String relayUrlOf(String origin, String hostId, String token) {
        if (origin == null || origin.trim().isEmpty()
                || hostId == null || hostId.trim().isEmpty()
                || token == null || token.trim().isEmpty()) {
            throw new IllegalArgumentException("relay origin/hostId/token 缺失");
        }
        String base = normalizeOrigin(origin);
        try {
            // URLEncoder 是 form 编码：空格 → '+'。query 参数按 form 解码时 '+' 会
            // 还原成空格，但反过来「令牌本身含 +」会被服务端错读成空格 → 静默失效。
            // 所以 '+' 必须再转义成 %20，保证字节级一致（r18 反代按原文比对令牌）。
            String pt = URLEncoder.encode(token, "UTF-8").replace("+", "%20");
            return base + "/d/" + URLEncoder.encode(hostId, "UTF-8") + "/ui/?pt=" + pt;
        } catch (java.io.UnsupportedEncodingException e) {
            // UTF-8 恒在，理论不可达；防御性处理
            throw new IllegalArgumentException("UTF-8 编码失败", e);
        }
    }

    /**
     * 归一 origin：补 scheme（无 scheme 时按 https，Relay 公网部署默认 TLS）、
     * 主机名转小写、去掉路径与尾斜杠（origin 是「基座」，带 path 会拼出错误 URL）。
     * 解析失败时原样返回（后续 sameOrigin 的 fail-safe 会兜住）。
     */
    public static String normalizeOrigin(String origin) {
        String s = origin == null ? "" : origin.trim();
        if (s.isEmpty()) return s;
        if (!s.startsWith("http://") && !s.startsWith("https://")) s = "https://" + s;
        try {
            java.net.URI u = new java.net.URI(s);
            String scheme = u.getScheme() == null ? "https" : u.getScheme().toLowerCase(java.util.Locale.ROOT);
            String host = u.getHost() == null ? "" : u.getHost().toLowerCase(java.util.Locale.ROOT);
            if (host.isEmpty()) return s;
            int port = u.getPort();
            StringBuilder b = new StringBuilder(scheme).append("://").append(host);
            if (port > 0 && port != defaultPort(scheme)) b.append(':').append(port);
            return b.toString();
        } catch (Throwable t) {
            return s;
        }
    }

    /** scheme 缺省端口（与 DshOrigin 同一套规则）；未知 scheme 返回 -1。 */
    private static int defaultPort(String scheme) {
        String s = scheme.toLowerCase(java.util.Locale.ROOT);
        if ("http".equals(s)) return 80;
        if ("https".equals(s)) return 443;
        return -1;
    }

    // ==================================================================
    // 读写
    // ==================================================================

    private SharedPreferences prefs() {
        return appCtx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    /** 从 prefs 读并解析；任何异常回落 local（fail-safe：最保守的档案）。 */
    private Profile read() {
        try {
            JSONObject o = new JSONObject(prefs().getString(KEY_STATE, "{}"));
            String cur = o.optString("current", KIND_LOCAL);
            return build(cur, o);
        } catch (Throwable t) {
            Log.w(TAG, "档案读取失败，回落 local: " + t.getMessage());
            return build(KIND_LOCAL, null);
        }
    }

    /** 按 id 组装档案快照；id 非法或配置缺失时回落 local。 */
    private Profile build(String id, JSONObject state) {
        String k = KIND_LOCAL.equals(id) || KIND_SSH.equals(id) || KIND_RELAY.equals(id)
                ? id : KIND_LOCAL;
        JSONObject conf = state == null ? null : state.optJSONObject(k);
        if (conf == null) conf = new JSONObject();
        if (KIND_RELAY.equals(k)) {
            Profile p = new Profile(k,
                    conf.optString("origin", ""), conf.optString("hostId", ""),
                    conf.optString("deviceToken", ""),
                    "", 22, "", 3081);
            // 未配置完整的 relay 档不能当当前档案用（会加载坏 URL）→ 回落 local
            return p.relayConfigured() ? p : build(KIND_LOCAL, null);
        }
        if (KIND_SSH.equals(k)) {
            return new Profile(k, "", "", "",
                    conf.optString("host", ""), conf.optInt("port", 22),
                    conf.optString("user", ""), conf.optInt("localPort", 3081));
        }
        return new Profile(KIND_LOCAL, "", "", "", "", 22, "", 3081);
    }

    /** 当前档案（不可变快照，直接读无锁）。 */
    public Profile current() { return cur; }

    /** 当前档案 id（local/ssh/relay）。 */
    public String currentId() { return cur.kind; }

    /**
     * 仅持久化切换（不执行切换动作）。
     * @return 是否生效；未知 id / relay 未配置完整 → false 且不落盘。
     */
    public boolean setCurrent(String id) {
        if (id == null) return false;
        try {
            JSONObject state = new JSONObject(prefs().getString(KEY_STATE, "{}"));
            Profile next = build(id, state);
            if (!id.equals(next.kind)) return false;   // 未知 id
            if (KIND_RELAY.equals(id) && !next.relayConfigured()) return false;
            state.put("current", id);
            prefs().edit().putString(KEY_STATE, state.toString()).apply();
            cur = next;
            Log.i(TAG, "当前档案 -> " + id + "（" + cur.describe() + "）");
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "setCurrent 失败: " + t.getMessage());
            return false;
        }
    }

    /** 写 relay 档配置（origin 归一化后存储；token 不打日志）。 */
    public void setRelay(String origin, String hostId, String deviceToken) {
        try {
            JSONObject state = new JSONObject(prefs().getString(KEY_STATE, "{}"));
            JSONObject conf = new JSONObject()
                    .put("origin", normalizeOrigin(origin))
                    .put("hostId", hostId == null ? "" : hostId.trim())
                    .put("deviceToken", deviceToken == null ? "" : deviceToken.trim());
            state.put(KIND_RELAY, conf);
            prefs().edit().putString(KEY_STATE, state.toString()).apply();
            if (KIND_RELAY.equals(cur.kind)) cur = build(KIND_RELAY, state);
            Log.i(TAG, "relay 档已保存（" + conf.optString("origin")
                    + " hostId=" + conf.optString("hostId") + "）");
        } catch (Throwable t) {
            Log.w(TAG, "setRelay 失败: " + t.getMessage());
        }
    }

    /** 写 ssh 档配置。 */
    public void setSsh(String host, int port, String user, int localPort) {
        try {
            JSONObject state = new JSONObject(prefs().getString(KEY_STATE, "{}"));
            JSONObject conf = new JSONObject()
                    .put("host", host == null ? "" : host.trim())
                    .put("port", port > 0 ? port : 22)
                    .put("user", user == null ? "" : user.trim())
                    .put("localPort", localPort > 0 ? localPort : 3081);
            state.put(KIND_SSH, conf);
            prefs().edit().putString(KEY_STATE, state.toString()).apply();
            if (KIND_SSH.equals(cur.kind)) cur = build(KIND_SSH, state);
            Log.i(TAG, "ssh 档已保存（" + conf.optString("user") + "@"
                    + conf.optString("host") + ":" + conf.optInt("port", 22) + "）");
        } catch (Throwable t) {
            Log.w(TAG, "setSsh 失败: " + t.getMessage());
        }
    }
}
