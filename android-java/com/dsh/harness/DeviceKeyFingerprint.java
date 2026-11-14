package com.dsh.harness;

import java.security.MessageDigest;

/**
 * 设备公钥指纹 —— 协议 `wbsync/2.3 r24` §5.1 里 `devicePubKey` 的**唯一**计算口径。
 *
 * <p>口径必须与 Relay / CP 完全一致，写错不会报错、只会静默钉住一个错值：
 * <ul>
 *   <li>输入是公钥的 **X.509 SubjectPublicKeyInfo**（即 {@code PublicKey.getEncoded()}），
 *       不是证书 DER、不是 PEM 文本、不是 OpenSSH 的 {@code ssh-rsa}</li> 指纹；</li>
 *   <li>摘要 SHA-256，输出 **64 位小写 hex**，无分隔符（Relay 侧
 *       {@code DEVICE_KEY_RE = /^[A-Fa-f0-9]{64}$/}，只做大小写归一）。</li>
 * </ul>
 *
 * <p>纯逻辑、零 Android 依赖 —— 桌面 JDK 可直接跑 `scripts/test-java.sh`，
 * 与服务端 `sanitizeDeviceKey` 的行为做对照。
 */
public final class DeviceKeyFingerprint {

    /** 指纹长度：SHA-256 → 32 字节 → 64 位 hex。 */
    public static final int HEX_LEN = 64;

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private DeviceKeyFingerprint() {}

    /** SPKI → SHA-256 → 64 位小写 hex。 */
    public static String ofSpki(byte[] spki) {
        if (spki == null || spki.length == 0) {
            throw new IllegalArgumentException("设备公钥为空");
        }
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(spki);
            char[] out = new char[d.length * 2];
            for (int i = 0; i < d.length; i++) {
                out[i * 2] = HEX[(d[i] >> 4) & 0x0f];
                out[i * 2 + 1] = HEX[d[i] & 0x0f];
            }
            return new String(out);
        } catch (Exception e) {
            // JDK / Android 都保证有 SHA-256；走到这里说明运行时被裁剪，宁可显式失败
            throw new IllegalStateException("SHA-256 不可用: " + e.getMessage(), e);
        }
    }

    /**
     * 归一化：去空白、去 `:` 分隔、转小写；结果不是 64 位 hex 一律返回 {@code null}。
     *
     * <p>返回 {@code null} 的语义是**「按未携带处理」**（协议：不带 / 格式非法 →
     * 放行且不改钉住值）。调用方不得把非法值塞进请求体 —— 那会给指纹探测留缝，
     * 也会让服务端日志出现噪声。
     */
    public static String normalize(String raw) {
        if (raw == null) return null;
        StringBuilder sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char ch = raw.charAt(i);
            if (ch == ':' || ch == ' ' || ch == '\t' || ch == '\n' || ch == '\r') continue;
            sb.append(ch);
        }
        String s = sb.toString();
        if (s.length() != HEX_LEN) return null;
        char[] out = new char[HEX_LEN];
        for (int i = 0; i < HEX_LEN; i++) {
            char ch = s.charAt(i);
            if (ch >= '0' && ch <= '9') {
                out[i] = ch;
            } else if (ch >= 'a' && ch <= 'f') {
                out[i] = ch;
            } else if (ch >= 'A' && ch <= 'F') {
                out[i] = (char) (ch + 32);
            } else {
                return null;   // 非 hex（含全角字符、unicode 数字）一律拒绝
            }
        }
        return new String(out);
    }

    /** 是否是合法指纹（归一化后可判定）。 */
    public static boolean isValid(String raw) {
        return normalize(raw) != null;
    }
}
