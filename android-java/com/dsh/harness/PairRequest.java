package com.dsh.harness;

/**
 * `/pair/verify` 请求体组装 —— 手写 JSON，不用 {@code org.json}。
 *
 * <p>为什么手写：本类要在**桌面 JDK** 上跑单测（`scripts/test-java.sh`），
 * 而 `android.jar` 里的 `org.json` 只是桩、调用即抛 {@code Stub!}。
 * 请求体只有 5 个短字符串字段，同一份代码配一份转义即可两端复用。
 *
 * <p>三条硬约束（都来自 r24 契约，写错就变成「静默降级」）：
 * <ol>
 *   <li>{@code devicePubKey} 只有归一化通过才发送；非法值**整个字段省略**，
 *       等价于旧客户端 —— 协议规定非法按未携带处理，且不给指纹探测留缝；</li>
 *   <li>{@code phoneId} 必须满足 {@code ^[A-Za-z0-9_-]{8,64}$}，否则省略
 *       （省略 = 服务端分配新手机行）；</li>
 *   <li>{@code clientName} 去控制字符、截断 40 字符（服务端同样只截断展示，不参与判定）。</li>
 * </ol>
 */
public final class PairRequest {

    /** 设备名上限，与服务端 {@code sanitizeLabel} 的 slice(0,40) 对齐。 */
    public static final int MAX_NAME = 40;
    /** 手机 ID 上限，与服务端 {@code PHONE_ID_RE} 对齐。 */
    public static final int MAX_PHONE_ID = 64;

    private PairRequest() {}

    /**
     * 组装请求体。
     *
     * @param challenge   必填，第 2 步拿到的一次性挑战
     * @param sig         必填，{@link PairProof#sign} 的结果
     * @param phoneId     可空；不合法视为未携带
     * @param clientName  可空；会去控制字符并截断
     * @param devicePubKey 可空；不合法视为未携带（r24）
     */
    public static String body(String challenge, String sig,
                              String phoneId, String clientName, String devicePubKey) {
        String ch = trimOrNull(challenge);
        String sg = trimOrNull(sig);
        if (ch == null) throw new IllegalArgumentException("缺少 challenge");
        if (sg == null) throw new IllegalArgumentException("缺少 sig");

        StringBuilder sb = new StringBuilder(256);
        sb.append('{');
        sb.append("\"challenge\":").append(quote(ch));
        sb.append(",\"sig\":").append(quote(sg));

        String phone = sanitizePhoneId(phoneId);
        if (phone != null) sb.append(",\"phoneId\":").append(quote(phone));

        String name = sanitizeClientName(clientName);
        if (name != null) sb.append(",\"clientName\":").append(quote(name));

        String key = DeviceKeyFingerprint.normalize(devicePubKey);
        if (key != null) sb.append(",\"devicePubKey\":").append(quote(key));

        return sb.append('}').toString();
    }

    /** 手机 ID 清洗：不合法返回 {@code null}（= 让服务端分配）。 */
    public static String sanitizePhoneId(String raw) {
        String s = trimOrNull(raw);
        if (s == null || s.length() > MAX_PHONE_ID) return null;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean ok = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
                    || (c >= '0' && c <= '9') || c == '_' || c == '-';
            if (!ok) return null;
        }
        return s.length() >= 8 ? s : null;   // 服务端要求 ≥8
    }

    /** 设备名清洗：去控制字符、截断 40；空串返回 {@code null}。 */
    public static String sanitizeClientName(String raw) {
        String s = trimOrNull(raw);
        if (s == null) return null;
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x20 || c == 0x7f) continue;
            sb.append(c);
        }
        String out = sb.toString().trim();
        if (out.isEmpty()) return null;
        if (out.length() > MAX_NAME) out = out.substring(0, MAX_NAME);
        return out;
    }

    /** 最小 JSON 字符串转义（本类只产出短字符串，不处理嵌套/数字）。 */
    static String quote(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 2);
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':  sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.append('"').toString();
    }

    private static String trimOrNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
