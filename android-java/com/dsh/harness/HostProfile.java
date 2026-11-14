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

    /** 隧道远端（电脑侧）dsh 端口的默认值。见 {@link #setSsh} 的说明。 */
    static final int DEFAULT_TARGET_PORT = SshForm.DEFAULT_TARGET_PORT;

    private static final String TAG = "DshProfile";
    private static final String PREF = "dsh_profiles";
    private static final String KEY_STATE = "state";
    /** 启动是否自动进入远端档案；默认缺省 = false（按需连接）。 */
    private static final String KEY_AUTO_ENTER_REMOTE = "autoEnterRemote";

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
        public final int sshTargetPort; // 隧道**远端**（电脑侧）dsh 端口
        public final String sshToken;   // 电脑侧 dsh 的访问令牌（回环也鉴权，可为空）

        Profile(String kind, String origin, String hostId, String deviceToken,
                String sshHost, int sshPort, String sshUser, int localPort) {
            this(kind, origin, hostId, deviceToken, sshHost, sshPort, sshUser, localPort,
                    DEFAULT_TARGET_PORT, "");
        }

        Profile(String kind, String origin, String hostId, String deviceToken,
                String sshHost, int sshPort, String sshUser, int localPort,
                int sshTargetPort, String sshToken) {
            this.kind = kind;
            this.origin = origin;
            this.hostId = hostId;
            this.deviceToken = deviceToken;
            this.sshHost = sshHost;
            this.sshPort = sshPort;
            this.sshUser = sshUser;
            this.localPort = localPort;
            this.sshTargetPort = sshTargetPort > 0 ? sshTargetPort : DEFAULT_TARGET_PORT;
            this.sshToken = sshToken == null ? "" : sshToken;
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
                // 令牌必须拼在这里：`showWebView()` 对所有远端档统一用 loadUrl() 覆盖传进来的
                // url（r113 的防串档措施），所以在调用点拼令牌会被它冲掉 —— 真机上表现为
                // 「配置里令牌填对了，请求里却没有 ?token，WebView 拿到 401」。
                return SshForm.sshUrl(localPort > 0 ? localPort : 3081, sshToken);
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
            // 明文令牌迁移必须**无条件**跑：早先写在 build() 的 relay 分支里，
            // 而当前档常常是 local（例如切回本机之后），那条分支根本不执行，
            // 明文就一直留在文件里 —— 真机验证时正是这么漏掉的。
            migrateLegacyToken(o);
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
            // 令牌**只**从 DshTokenStore（Keystore 包裹的密文）取，绝不留在本档案 JSON 里。
            // 早期版本把明文写进 conf.deviceToken —— 真机验证时在 dsh_profiles.xml 里
            // 直接读到了它，与「加密落盘」自相矛盾。这里做一次性迁移：收进 store 后抹掉。
            String token = DshTokenStore.load(appCtx);
            Profile p = new Profile(k,
                    conf.optString("origin", ""), conf.optString("hostId", ""),
                    token == null ? "" : token,
                    "", 22, "", 3081);
            // 未配置完整的 relay 档不能当当前档案用（会加载坏 URL）→ 回落 local
            return p.relayConfigured() ? p : build(KIND_LOCAL, null);
        }
        if (KIND_SSH.equals(k)) {
            return new Profile(k, "", "", "",
                    conf.optString("host", ""), conf.optInt("port", 22),
                    conf.optString("user", ""), conf.optInt("localPort", 3081),
                    conf.optInt("targetPort", DEFAULT_TARGET_PORT),
                    conf.optString("token", ""));
        }
        return new Profile(KIND_LOCAL, "", "", "", "", 22, "", 3081);
    }

    /** 当前档案（不可变快照，直接读无锁）。 */
    public Profile current() { return cur; }

    /**
     * 用给定参数造一个 ssh 档快照：**不落盘、不改当前档**。
     *
     * <p>配置页的「测试连接」需要 prof 来起隧道，但用户此时还没点保存 ——
     * 拿它测，不能顺手把档案改掉。
     */
    public static Profile sshProfileFor(String host, int port, String user, int localPort) {
        return sshProfileFor(host, port, user, localPort, DEFAULT_TARGET_PORT, "");
    }

    /** 同上，带远端 dsh 端口与访问令牌（配置页「测试连接」按用户当次填写起隧道）。 */
    public static Profile sshProfileFor(String host, int port, String user, int localPort,
                                        int targetPort, String token) {
        return new Profile(KIND_SSH, "", "", "", host, port, user, localPort, targetPort, token);
    }

    /**
     * 读已存的 ssh 档配置（**即使当前不是 ssh 档**）；没配过或不完整返回 {@code null}。
     *
     * <p>为什么需要：配置页要回填上一次填的地址。若用 `setCurrent(KIND_SSH)` 取快照
     * 再设回原档，中间会落盘两次 —— 用户只是打开一次配置页，档案就变了两次。
     */
    public Profile savedSsh() {
        try {
            JSONObject o = new JSONObject(prefs().getString(KEY_STATE, "{}"));
            JSONObject conf = o.optJSONObject(KIND_SSH);
            if (conf == null) return null;
            Profile p = new Profile(KIND_SSH, "", "", "",
                    conf.optString("host", ""), conf.optInt("port", 22),
                    conf.optString("user", ""), conf.optInt("localPort", 3081),
                    conf.optInt("targetPort", DEFAULT_TARGET_PORT),
                    conf.optString("token", ""));
            return p.sshConfigured() ? p : null;
        } catch (Throwable t) {
            Log.w(TAG, "读 ssh 档失败: " + t.getMessage());
            return null;
        }
    }

    /** 当前档案 id（local/ssh/relay）。 */
    public String currentId() { return cur.kind; }

    /**
     * 启动时是否自动进入远端档案（relay / ssh）。默认 {@code false} —— **按需连接**。
     *
     * <p>为什么默认关：远端档案意味着连另一台机器，用户开 App 的意图多数是「用手机上的
     * dsh」。启动即连会把「打开应用」变成「连一台可能已经离线/被收回的电脑」，
     * 失败时还停在白屏或错误页。关掉之后启动停在选择页，用户点了才连。
     *
     * <p>显式进入的路径有两条：选择页上的「进入这台电脑」，或 `MainActivity` 收到
     * `enterRemote=true` 的 extra（切换器/离线页在用户确认后拉起时带）。
     */
    public boolean autoEnterRemote() {
        try {
            JSONObject o = new JSONObject(prefs().getString(KEY_STATE, "{}"));
            return o.optBoolean(KEY_AUTO_ENTER_REMOTE, false);
        } catch (Throwable t) {
            return false;   // 读不出来就最保守：不自动连
        }
    }

    /** 设置启动是否自动进入远端档案。 */
    public void setAutoEnterRemote(boolean on) {
        try {
            JSONObject state = new JSONObject(prefs().getString(KEY_STATE, "{}"));
            state.put(KEY_AUTO_ENTER_REMOTE, on);
            prefs().edit().putString(KEY_STATE, state.toString()).apply();
        } catch (Throwable t) {
            Log.w(TAG, "写 autoEnterRemote 失败: " + t.getMessage());
        }
    }

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
            // 令牌进 DshTokenStore（密文），档案里只留 origin/hostId
            if (deviceToken != null && !deviceToken.trim().isEmpty()) {
                DshTokenStore.save(appCtx, deviceToken.trim());
            }
            JSONObject conf = new JSONObject()
                    .put("origin", normalizeOrigin(origin))
                    .put("hostId", hostId == null ? "" : hostId.trim());
            state.put(KIND_RELAY, conf);
            prefs().edit().putString(KEY_STATE, state.toString()).apply();
            if (KIND_RELAY.equals(cur.kind)) cur = build(KIND_RELAY, state);
            Log.i(TAG, "relay 档已保存（" + conf.optString("origin")
                    + " hostId=" + conf.optString("hostId") + "）");
        } catch (Throwable t) {
            Log.w(TAG, "setRelay 失败: " + t.getMessage());
        }
    }

    /**
     * 一次性迁移：把旧版本写在档案 JSON 里的明文 `deviceToken` 收进
     * {@link DshTokenStore}（Keystore AES-GCM 密文），并从 JSON 里抹掉。
     *
     * <p>幂等：没有明文键就什么都不做。**必须与"当前是哪个档案"无关**——
     * 用户切回本机后当前档是 local，但明文还躺在文件里，照样得清。
     */
    private void migrateLegacyToken(JSONObject state) {
        try {
            JSONObject relay = state == null ? null : state.optJSONObject(KIND_RELAY);
            if (relay == null || !relay.has("deviceToken")) return;
            String legacy = relay.optString("deviceToken", "");
            if (legacy != null && !legacy.trim().isEmpty()
                    && (DshTokenStore.load(appCtx) == null || DshTokenStore.load(appCtx).isEmpty())) {
                DshTokenStore.save(appCtx, legacy.trim());
            }
            relay.remove("deviceToken");
            prefs().edit().putString(KEY_STATE, state.toString()).apply();
            Log.i(TAG, "已在档案里抹掉明文令牌（已迁入 DshTokenStore）");
        } catch (Throwable t) {
            Log.w(TAG, "明文令牌迁移失败: " + t.getMessage());
        }
    }

    /** 写 ssh 档配置（远端端口按默认 3080、无令牌）。 */
    public void setSsh(String host, int port, String user, int localPort) {
        setSsh(host, port, user, localPort, DEFAULT_TARGET_PORT, "");
    }

    /**
     * 写 ssh 档配置。
     *
     * <p>{@code targetPort} 是**电脑侧** dsh 的端口：隧道 `-L localPort:127.0.0.1:targetPort`。
     * 早先写死 3080 是拿手机端的引擎端口想当然，实测电脑上 dsh 根本不在 3080，
     * 隧道打通也只会打到空端口。
     *
     * <p>{@code token} 是电脑侧 dsh 的访问令牌：dsh web 对**回环请求也要鉴权**
     * （`GET /` 直接 401），不带令牌进隧道只会看到 401 页面。
     */
    public void setSsh(String host, int port, String user, int localPort,
                       int targetPort, String token) {
        try {
            JSONObject state = new JSONObject(prefs().getString(KEY_STATE, "{}"));
            JSONObject conf = new JSONObject()
                    .put("host", host == null ? "" : host.trim())
                    .put("port", port > 0 ? port : 22)
                    .put("user", user == null ? "" : user.trim())
                    .put("localPort", localPort > 0 ? localPort : 3081)
                    .put("targetPort", targetPort > 0 ? targetPort : DEFAULT_TARGET_PORT)
                    .put("token", token == null ? "" : token.trim());
            state.put(KIND_SSH, conf);
            prefs().edit().putString(KEY_STATE, state.toString()).apply();
            if (KIND_SSH.equals(cur.kind)) cur = build(KIND_SSH, state);
            Log.i(TAG, "ssh 档已保存（" + conf.optString("user") + "@"
                    + conf.optString("host") + ":" + conf.optInt("port", 22)
                    + " → 远端 " + conf.optInt("targetPort", DEFAULT_TARGET_PORT)
                    + (conf.optString("token").isEmpty() ? "，无令牌" : "，带令牌") + "）");
        } catch (Throwable t) {
            Log.w(TAG, "setSsh 失败: " + t.getMessage());
        }
    }
}
