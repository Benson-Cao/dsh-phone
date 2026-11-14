package com.dsh.harness;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 配对失败的**处置映射** —— 把 Relay 错误码翻成「用户下一步该做什么」。
 *
 * <p>为什么单独一层：`DEVICE_KEY_MISMATCH` / `CLIENT_LIMIT` / `PAIR_CODE_INVALID`
 * 在界面上如果都显示成「配对失败，请重试」，用户会一直重试同一件不可能成功的事。
 * 这三个码的处置完全不同（换身份重配 / 先去管理台收回一台 / 重新生成配对码）。
 *
 * <p>纯逻辑、零 Android 依赖，桌面可测。
 */
public final class PairError {

    public static final String CODE_INVALID = "PAIR_CODE_INVALID";
    public static final String CHALLENGE_EXPIRED = "CHALLENGE_EXPIRED";
    public static final String UNAUTHORIZED = "UNAUTHORIZED";
    public static final String CLIENT_LIMIT = "CLIENT_LIMIT";
    public static final String DEVICE_KEY_MISMATCH = "DEVICE_KEY_MISMATCH";
    public static final String HOST_OFFLINE = "HOST_OFFLINE";
    /** 客户端侧合成码：连不上 / 超时 / 响应不是 JSON。 */
    public static final String NETWORK = "NETWORK";

    /** 处置建议。 */
    public static final class Finding {
        public final String code;
        public final String title;
        public final String action;
        /** 是否可以「原样重试」（false 表示重试必然还是同一个结果）。 */
        public final boolean retryable;
        /** 是否需要换一套设备身份（新 phoneId + 新密钥）后重配。 */
        public final boolean needsRotate;

        Finding(String code, String title, String action, boolean retryable, boolean needsRotate) {
            this.code = code;
            this.title = title;
            this.action = action;
            this.retryable = retryable;
            this.needsRotate = needsRotate;
        }

        @Override
        public String toString() {
            return title + "（" + code + "）→ " + action;
        }
    }

    private static final Map<String, Finding> TABLE = new LinkedHashMap<String, Finding>();

    static {
        TABLE.put(CODE_INVALID, new Finding(CODE_INVALID, "配对码不对",
                "配对码一次性使用，且 10 分钟内有效。请在电脑上重新生成一个再输入。", false, false));
        TABLE.put(CHALLENGE_EXPIRED, new Finding(CHALLENGE_EXPIRED, "配对超时",
                "挑战 60 秒内要完成校验。请重新扫描或重新输入配对码。", false, false));
        TABLE.put(UNAUTHORIZED, new Finding(UNAUTHORIZED, "验证未通过",
                "这台手机可能已被收回，或配对凭证已失效。请在电脑管理台确认后重新配对。", false, false));
        TABLE.put(CLIENT_LIMIT, new Finding(CLIENT_LIMIT, "手机数量已达上限",
                "该电脑已绑定 8 台手机。先在电脑管理台收回一台不用了的设备，再配这台。", false, false));
        TABLE.put(DEVICE_KEY_MISMATCH, new Finding(DEVICE_KEY_MISMATCH, "设备身份已变化",
                "这台手机此前用另一把设备密钥配过（重装、清数据或恢复备份都会这样）。"
                        + "要让本机重新作为新设备接入，请点「重新开始」；否则请先在电脑管理台收回这台手机。",
                false, true));
        TABLE.put(HOST_OFFLINE, new Finding(HOST_OFFLINE, "电脑当前不可达",
                "那台电脑可能刚休眠或掉线。确认它开着并连着同一网络，稍后再试。", true, false));
        TABLE.put(NETWORK, new Finding(NETWORK, "连不上电脑",
                "检查地址是否写全（含端口）、手机与电脑是否同一网络、电脑端 Relay 是否在运行。", true, false));
    }

    private PairError() {}

    /**
     * 按 HTTP 状态码 + 错误码给出处置。
     *
     * @param status HTTP 状态码；{@code <=0} 表示没拿到响应（连接失败/超时）
     * @param code   响应体里的 {@code code}，可为空
     */
    public static Finding of(int status, String code) {
        String key = code == null ? "" : code.trim();
        if (status <= 0) key = NETWORK;
        Finding f = TABLE.get(key);
        if (f != null) return f;
        if (status == 401) return TABLE.get(UNAUTHORIZED);
        if (status == 409) {
            // 未识别的 409 不要冒充「电脑不可达」——那会让用户反复重试网络，
            // 而真实原因是服务端拒绝（名额/状态冲突）。
            return new Finding(key.isEmpty() ? "CONFLICT" : key, "电脑端拒绝配对",
                    "电脑端返回冲突（HTTP 409）。确认配对码是刚生成的，并在电脑管理台检查这台"
                            + "电脑名下的手机名额。", false, false);
        }
        if (status >= 500) {
            return new Finding(key.isEmpty() ? "SERVER_ERROR" : key, "电脑端出错",
                    "Relay 返回 " + status + "。稍后重试；若持续失败，看电脑端 Relay 日志。", true, false);
        }
        return new Finding(key.isEmpty() ? ("HTTP_" + status) : key, "配对未完成",
                "服务端未给出可识别的拒绝理由（HTTP " + status + "）。稍后重试，或换一个配对码。", true, false);
    }

    /** 供界面显示的一行摘要。 */
    public static String describe(int status, String code) {
        return of(status, code).toString();
    }
}
