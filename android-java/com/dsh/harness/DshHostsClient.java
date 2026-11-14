package com.dsh.harness;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URL;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;

/**
 * 主机清单与切换（CP `GET /api/hosts`、`POST /api/hosts/select`）。
 *
 * <p>凭据由壳持有（{@link DshTokenStore}），令牌只在本类内部拼进 `Authorization: Bearer`，
 * 不出现在任何返回值、日志或页面对象里。这是「给能力、不给凭据」的具体落地。
 *
 * <p>响应封套是 {@code {ok:true, data:{…}, protocol}}；失败体是
 * {@code {ok:false, error:{code,message,retryable}}}。早期按顶层字段解析的写法
 * 对真 Relay 永远拿到 null（配对页就栽在这上面），所以这里统一走 {@link #payload}。
 */
public final class DshHostsClient {

    /** 一台电脑（已脱敏：不含 phones[]、不含任何令牌）。 */
    public static final class Host {
        public final String hostId;
        public final String name;
        public final String platform;
        public final boolean online;
        public final String engineState;
        public final boolean bound;
        public final String dshCommit;
        public final int sessionCount;

        Host(String hostId, String name, String platform, boolean online,
             String engineState, boolean bound, String dshCommit, int sessionCount) {
            this.hostId = hostId;
            this.name = name;
            this.platform = platform;
            this.online = online;
            this.engineState = engineState;
            this.bound = bound;
            this.dshCommit = dshCommit;
            this.sessionCount = sessionCount;
        }

        /** 一行摘要：名字 · 平台 · 在线态（列表渲染与无障碍朗读共用）。 */
        public String summary() {
            StringBuilder sb = new StringBuilder();
            sb.append(name == null || name.isEmpty() ? hostId : name);
            if (platform != null && !platform.isEmpty()) sb.append(" · ").append(platform);
            sb.append(online ? " · 在线" : " · 离线");
            if (sessionCount > 0) sb.append(" · ").append(sessionCount).append(" 个会话");
            if ("safe-mode".equals(engineState)) sb.append(" · 引擎安全模式");
            return sb.toString();
        }
    }

    /** 一次调用的结果。{@code rows} 仅在 {@link #ok} 为 true 时有意义。 */
    public static final class Result {
        public final int status;
        public final String code;
        public final List<Host> rows;
        public final boolean switched;
        public final String hostId;
        public final String notice;

        Result(int status, String code, List<Host> rows,
               boolean switched, String hostId, String notice) {
            this.status = status;
            this.code = code;
            this.rows = rows;
            this.switched = switched;
            this.hostId = hostId;
            this.notice = notice;
        }

        public boolean ok() {
            return code == null && status == 200;
        }

        public HostsError.Finding finding() {
            return HostsError.of(status, code);
        }
    }

    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final int CONNECT_MS = 8000;
    private static final int READ_MS = 12000;

    private DshHostsClient() {}

    /** 拉可切主机清单。令牌为空时直接返回未授权，不发请求（不拿空 Bearer 去试）。 */
    public static Result list(String relayBase, String token) {
        if (token == null || token.trim().isEmpty()) {
            return new Result(401, HostsError.UNAUTHORIZED, null, false, null, null);
        }
        Call c = get(relayBase, "/api/hosts", token);
        if (c == null) {
            return new Result(0, HostsError.NETWORK, null, false, null, null);
        }
        if (c.status != 200) {
            return new Result(c.status, c.code, null, false, null, null);
        }
        List<Host> rows = new ArrayList<Host>();
        JSONArray arr = c.data == null ? null : c.data.optJSONArray("rows");
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                String id = str(o, "hostId");
                if (id == null) id = str(o, "deviceId");
                if (id == null) continue;   // 没有主键的行无法切换，直接跳过
                rows.add(new Host(
                        id,
                        str(o, "name"),
                        str(o, "platform"),
                        o.optBoolean("online", false),
                        str(o, "engineState"),
                        o.optBoolean("bound", false),
                        str(o, "dshCommit"),
                        o.optInt("sessionCount", 0)));
            }
        }
        return new Result(c.status, null, rows, false, null, null);
    }

    /**
     * 切换主机：**总是**带 `confirmNoMigration:true`。
     *
     * <p>服务端要求显式确认「不带走会话/文件/插件」；界面上的确认框是给人看的，
     * 协议层的确认是参数。两处都要有 —— 只在界面确认、请求里不带参数，
     * 会拿到 `CONFIRM_REQUIRED` 且用户无从理解。
     */
    public static Result select(String relayBase, String token, String hostId) {
        if (token == null || token.trim().isEmpty()) {
            return new Result(401, HostsError.UNAUTHORIZED, null, false, null, null);
        }
        if (hostId == null || hostId.trim().isEmpty()) {
            return new Result(400, HostsError.INVALID_ARGUMENT, null, false, null, null);
        }
        String body = "{\"hostId\":" + PairRequest.quote(hostId.trim())
                + ",\"confirmNoMigration\":true}";
        Call c = post(relayBase, "/api/hosts/select", token, body);
        if (c == null) {
            return new Result(0, HostsError.NETWORK, null, false, null, null);
        }
        if (c.status != 200) {
            return new Result(c.status, c.code, null, false, null, null);
        }
        return new Result(c.status, null, null,
                c.data != null && c.data.optBoolean("switched", false),
                c.data == null ? null : str(c.data, "hostId"),
                c.data == null ? null : str(c.data, "notice"));
    }

    // ---- 内部 ----

    private static final class Call {
        final int status;
        final String code;
        final JSONObject data;

        Call(int status, String code, JSONObject data) {
            this.status = status;
            this.code = code;
            this.data = data;
        }
    }

    private static Call get(String relayBase, String path, String token) {
        String target = PairUrl.endpoint(relayBase, path);
        if (target == null) return null;
        try {
            DshHttp.Resp r = DshHttp.request("GET", new URL(target), null, null,
                    (byte[]) null, CONNECT_MS, READ_MS, "Authorization", "Bearer " + token.trim());
            return parse(r);
        } catch (Exception e) {
            return null;
        }
    }

    private static Call post(String relayBase, String path, String token, String body) {
        String target = PairUrl.endpoint(relayBase, path);
        if (target == null) return null;
        try {
            DshHttp.Resp r = DshHttp.request("POST", new URL(target), null, "application/json",
                    body.getBytes(UTF8), CONNECT_MS, READ_MS,
                    "Authorization", "Bearer " + token.trim());
            return parse(r);
        } catch (Exception e) {
            return null;
        }
    }

    private static Call parse(DshHttp.Resp r) {
        JSONObject json;
        try {
            json = new JSONObject(new String(r.body, UTF8));
        } catch (Exception e) {
            json = new JSONObject();
        }
        JSONObject data = json.optJSONObject("data");
        if (r.code == 200) {
            // 成功体缺 data 也算结构异常：宁可报错，也不返回半份清单让人以为只有 0 台
            return new Call(r.code, data == null ? "BAD_RESPONSE" : null, data);
        }
        String code = null;
        JSONObject err = json.optJSONObject("error");
        if (err != null) code = str(err, "code");
        if (code == null) code = str(json, "code");
        return new Call(r.code, code, data);
    }

    private static String str(JSONObject o, String key) {
        if (o == null || !o.has(key) || o.isNull(key)) return null;
        String v = o.optString(key, null);
        return (v == null || v.trim().isEmpty()) ? null : v.trim();
    }
}
