package com.dsh.harness;

import java.nio.charset.Charset;
import java.security.MessageDigest;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * 配对第 3 步的持有性证明：`sig = HMAC-SHA256(pairSecret, challenge)`。
 *
 * <p>**坑位（照搬运就会出现「HMAC 校验失败」）**：Relay 侧写的是
 * {@code createHmac('sha256', c.pairSecret)}，Node 把 {@code pairSecret}
 * 当作**字符串**传进 HMAC —— 也就是用 hex 文本自身的 ASCII 字节当密钥，
 * **不是**把它 hex 解码成 16 字节二进制。这里必须逐字节对齐，
 * 否则本地自测一切正常、连上 Relay 永远 401。
 *
 * <p>纯逻辑、零 Android 依赖，桌面可测；用例里的期望值由 Node {@code crypto}
 * 现算（见 {@code tests/java/.../PairProofTest.java}），不写死常量。
 */
public final class PairProof {

    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private PairProof() {}

    /** 计算配对签名（64 位小写 hex）。 */
    public static String sign(String pairSecret, String challenge) {
        if (pairSecret == null || pairSecret.isEmpty()) {
            throw new IllegalArgumentException("pairSecret 为空");
        }
        if (challenge == null || challenge.isEmpty()) {
            throw new IllegalArgumentException("challenge 为空");
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            // 关键：以 hex **文本**作密钥（对齐 Node 的 createHmac(key=string)）
            mac.init(new SecretKeySpec(pairSecret.getBytes(UTF8), "HmacSHA256"));
            return hex(mac.doFinal(challenge.getBytes(UTF8)));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC-SHA256 不可用: " + e.getMessage(), e);
        }
    }

    /** 常量时间比较（避免把签名比较写成短路比较）。 */
    public static boolean equal(String a, String b) {
        if (a == null || b == null) return false;
        byte[] x = a.getBytes(UTF8);
        byte[] y = b.getBytes(UTF8);
        if (x.length != y.length) return false;
        int diff = 0;
        for (int i = 0; i < x.length; i++) diff |= x[i] ^ y[i];
        return diff == 0;
    }

    private static String hex(byte[] d) {
        char[] out = new char[d.length * 2];
        for (int i = 0; i < d.length; i++) {
            out[i * 2] = HEX[(d[i] >> 4) & 0x0f];
            out[i * 2 + 1] = HEX[d[i] & 0x0f];
        }
        return new String(out);
    }

    /** 仅用于诊断：不会把密钥写进日志。 */
    static String digestOf(byte[] data) {
        try {
            return hex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (Exception e) {
            return "?";
        }
    }
}
