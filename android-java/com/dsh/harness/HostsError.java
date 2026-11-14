package com.dsh.harness;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 主机切换失败的处置映射（纯逻辑，桌面可测）。
 *
 * <p>为什么单独一层：`UNAUTHORIZED` / `CONFIRM_REQUIRED` / `HOST_OFFLINE` 在界面上
 * 如果都显示成「切换失败」，用户会反复点同一件事。三者的下一步完全不同：
 * 重新配对 / 补确认参数 / 等电脑上线。
 *
 * <p>错误码来源是 Relay `{ok:false, error:{code,message,retryable}}`（不是顶层 code）。
 */
public final class HostsError {

    public static final String UNAUTHORIZED = "UNAUTHORIZED";
    public static final String CONFIRM_REQUIRED = "CONFIRM_REQUIRED";
    public static final String HOST_OFFLINE = "HOST_OFFLINE";
    public static final String CLIENT_LIMIT = "CLIENT_LIMIT";
    public static final String INVALID_ARGUMENT = "INVALID_ARGUMENT";
    /** 客户端侧合成码：连不上 / 超时 / 响应不是 JSON。 */
    public static final String NETWORK = "NETWORK";

    public static final class Finding {
        public final String code;
        public final String title;
        public final String action;
        public final boolean retryable;

        Finding(String code, String title, String action, boolean retryable) {
            this.code = code;
            this.title = title;
            this.action = action;
            this.retryable = retryable;
        }
    }

    private static final Map<String, Finding> TABLE = new LinkedHashMap<String, Finding>();

    static {
        TABLE.put(UNAUTHORIZED, new Finding(UNAUTHORIZED, "令牌已失效",
                "这台手机可能被收回了，或配对已过期。重新配对一次。", false));
        TABLE.put(CONFIRM_REQUIRED, new Finding(CONFIRM_REQUIRED, "缺少切换确认",
                "切换主机必须显式声明「不带走会话、文件与插件」。这是客户端的确认框问题，重试前先确认弹窗已勾选。",
                false));
        TABLE.put(HOST_OFFLINE, new Finding(HOST_OFFLINE, "目标电脑不在了",
                "那台电脑已休眠、离线或被收回。先确认它在运行，或换一台。", true));
        TABLE.put(CLIENT_LIMIT, new Finding(CLIENT_LIMIT, "目标电脑的手机名额满了",
                "先在那台电脑的管理台收回一台不用的手机，再切过去。", false));
        TABLE.put(INVALID_ARGUMENT, new Finding(INVALID_ARGUMENT, "请求参数不合法",
                "缺少 hostId —— 列表可能已过期，刷新后重选。", false));
        TABLE.put(NETWORK, new Finding(NETWORK, "连不上电脑",
                "检查地址与网络；电脑端 Relay 是否在运行。", true));
    }

    private HostsError() {}

    /** 按 HTTP 状态码 + 错误码给处置；{@code status <= 0} 视为没拿到响应。 */
    public static Finding of(int status, String code) {
        String key = code == null ? "" : code.trim();
        if (status <= 0) return TABLE.get(NETWORK);
        Finding f = TABLE.get(key);
        if (f != null) return f;
        if (status == 401) return TABLE.get(UNAUTHORIZED);
        if (status >= 500) {
            return new Finding(key.isEmpty() ? "SERVER_ERROR" : key, "电脑端出错",
                    "Relay 返回 " + status + "。稍后重试；持续失败就看电脑端 Relay 日志。", true);
        }
        return new Finding(key.isEmpty() ? ("HTTP_" + status) : key, "切换未完成",
                "服务端未给出可识别的拒绝理由（HTTP " + status + "）。稍后重试。", true);
    }

    public static String describe(int status, String code) {
        Finding f = of(status, code);
        return f.title + "（" + f.code + "）→ " + f.action;
    }
}
