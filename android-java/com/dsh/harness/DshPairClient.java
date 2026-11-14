package com.dsh.harness;

import org.json.JSONObject;

import java.net.URL;
import java.nio.charset.Charset;

/**
 * 配对客户端：`POST /pair` → `POST /pair/verify` 两步走完（协议 §5.1）。
 *
 * <p>不依赖 `client-core`（那是 WebView 侧 JS）：配对是**原生页**的职责，
 * 原生面必须在没有 WebView、没有 dsh 引擎的情况下也能完成接入 —— 首次安装时
 * 引擎可能还没解包完，用户先配对完全合理。
 *
 * <p>网络走 {@link DshHttp}（裸 Socket）：Relay 常在局域网明文 HTTP 上，
 * 且该客户端零第三方依赖、不受 Android `HttpURLConnection` 的 method 白名单限制。
 */
public final class DshPairClient {

    /** 第二步（挑战）结果。 */
    public static final class Challenge {
        public final String challenge;
        public final String pairSecret;
        public final int status;
        public final String code;

        Challenge(String challenge, String pairSecret, int status, String code) {
            this.challenge = challenge;
            this.pairSecret = pairSecret;
            this.status = status;
            this.code = code;
        }

        public boolean ok() {
            return challenge != null && pairSecret != null;
        }
    }

    /** 第三步（校验）结果。 */
    public static final class Verified {
        public final int status;
        public final String code;
        public final String deviceToken;
        public final String phoneId;
        public final String hostDeviceId;
        public final String relayUrl;
        public final String protocol;

        Verified(int status, String code, String deviceToken, String phoneId,
                 String hostDeviceId, String relayUrl, String protocol) {
            this.status = status;
            this.code = code;
            this.deviceToken = deviceToken;
            this.phoneId = phoneId;
            this.hostDeviceId = hostDeviceId;
            this.relayUrl = relayUrl;
            this.protocol = protocol;
        }

        public boolean ok() {
            return deviceToken != null && hostDeviceId != null;
        }

        /** 处置建议（错误码 → 用户下一步）。 */
        public PairError.Finding finding() {
            return PairError.of(status, code);
        }
    }

    private static final Charset UTF8 = Charset.forName("UTF-8");

    private DshPairClient() {}

    /** 第 2 步：用配对码换一次性挑战（含 pairSecret）。 */
    public static Challenge requestChallenge(String relayBase, String code) {
        String body = "{\"code\":" + PairRequest.quote(String.valueOf(code).trim()) + "}";
        JSONObject json = post(relayBase, "/pair", body);
        if (json == null) return new Challenge(null, null, 0, PairError.NETWORK);
        int status = statusOf(json);
        if (status != 200) return new Challenge(null, null, status, codeOf(json));
        return new Challenge(optString(json, "challenge"), optString(json, "pairSecret"),
                status, null);
    }

    /** 第 3 步：提交 sig + 设备指纹，换设备令牌。 */
    public static Verified verify(String relayBase, String challenge, String sig,
                                  String phoneId, String clientName, String devicePubKey) {
        String body;
        try {
            body = PairRequest.body(challenge, sig, phoneId, clientName, devicePubKey);
        } catch (IllegalArgumentException e) {
            return new Verified(0, null, null, null, null, null, null);
        }
        JSONObject json = post(relayBase, "/pair/verify", body);
        if (json == null) return new Verified(0, PairError.NETWORK, null, null, null, null, null);
        int status = statusOf(json);
        if (status != 200) {
            return new Verified(status, codeOf(json), null, null, null, null, null);
        }
        return new Verified(status, null,
                optString(json, "deviceToken"),
                optString(json, "deviceId"),
                optString(json, "hostDeviceId"),
                optString(json, "relayUrl"),
                optString(json, "protocol"));
    }

    /**
     * 一次配完：挑战 → 签名 → 校验。
     *
     * @param devicePubKey 可为 {@code null}（Keystore 不可用时按未携带处理）
     */
    public static Verified pairOnce(String relayBase, String code,
                                    String phoneId, String clientName, String devicePubKey) {
        Challenge ch = requestChallenge(relayBase, code);
        if (!ch.ok()) {
            // 第 2 步失败时把状态码/错误码原样带出，界面按同一张表处置
            return new Verified(ch.status, ch.code, null, null, null, null, null);
        }
        String sig;
        try {
            sig = PairProof.sign(ch.pairSecret, ch.challenge);
        } catch (RuntimeException e) {
            return new Verified(0, PairError.NETWORK, null, null, null, null, null);
        }
        return verify(relayBase, ch.challenge, sig, phoneId, clientName, devicePubKey);
    }

    /**
     * 规整用户输入的电脑地址（见 {@link PairUrl#normalizeBase}）。
     * 非法返回 {@code null}（界面据此提示格式，而不是发一个注定失败的请求）。
     */
    public static String normalizeBase(String raw) {
        return PairUrl.normalizeBase(raw);
    }

    // ---- 健康探测（离线页用）----

    /** `GET /health` 的结果：地址通不通、有没有电脑在线。 */
    public static final class Health {
        /** 能建立 TCP 且拿到 200。 */
        public final boolean reachable;
        /** 在线的**电脑**数量（Relay 已把手机排除，混进来「电脑在线吗」就没有答案）。 */
        public final int online;
        public final String protocol;
        public final int status;

        Health(boolean reachable, int online, String protocol, int status) {
            this.reachable = reachable;
            this.online = online;
            this.protocol = protocol;
            this.status = status;
        }
    }

    /**
     * 探测 Relay 健康状态。
     *
     * <p>为什么不探 `/status`：那个要设备令牌，而离线页的存在意义恰恰是
     * 「令牌还没拿到 / 已经不用了」的时候也要能给用户一句人话。
     * `/health` 免鉴权、只回计数与协议号，不泄露任何设备信息。
     */
    public static Health health(String relayBase) {
        String target = PairUrl.endpoint(relayBase, "/health");
        if (target == null) return new Health(false, 0, null, 0);
        try {
            // body 显式转型：DshHttp 同时有 byte[] 与 File 两个重载，裸 null 会歧义
            DshHttp.Resp r = DshHttp.request("GET", new URL(target), null, null,
                    (byte[]) null, 6000, 8000);
            if (r.code != 200) return new Health(true, 0, null, r.code);
            JSONObject json = new JSONObject(new String(r.body, UTF8));
            return new Health(true, json.optInt("online", 0),
                    optString(json, "protocol"), r.code);
        } catch (Exception e) {
            return new Health(false, 0, null, 0);
        }
    }

    // ---- 内部 ----

    /** 发 JSON POST；失败返回 {@code null}（网络层错误），成功返回带 `__status` 的对象。 */
    private static JSONObject post(String relayBase, String path, String body) {
        String target = PairUrl.endpoint(relayBase, path);
        if (target == null) return null;
        try {
            URL u = new URL(target);
            DshHttp.Resp r = DshHttp.request("POST", u, null, "application/json",
                    body.getBytes(UTF8), 10000, 15000);
            JSONObject json;
            try {
                json = new JSONObject(new String(r.body, UTF8));
            } catch (Exception e) {
                json = new JSONObject();
            }
            json.put("__status", r.code);
            return json;
        } catch (Exception e) {
            return null;
        }
    }

    private static int statusOf(JSONObject json) {
        return json.optInt("__status", 0);
    }

    private static String codeOf(JSONObject json) {
        String c = optString(json, "code");
        if (c != null) return c;
        // 有些失败体把原因放在 error 里
        return optString(json, "error");
    }

    private static String optString(JSONObject json, String key) {
        if (json == null || !json.has(key) || json.isNull(key)) return null;
        String v = json.optString(key, null);
        return (v == null || v.trim().isEmpty()) ? null : v.trim();
    }
}
