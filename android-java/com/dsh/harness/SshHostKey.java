package com.dsh.harness;

import java.security.MessageDigest;

/**
 * SSH 主机公钥指纹（OpenSSH `SHA256:…` 形态）与 TOFU 判定 —— 纯逻辑，桌面可测。
 *
 * <h3>为什么单独抽出来</h3>
 * 指纹比对是「连到自己以为的那台机器」的唯一依据。它的算法有两条容易写错的地方：
 * <ul>
 *   <li>指纹算的是**公钥 blob**（base64 解码后的原始字节）的 SHA-256，
 *       不是 base64 文本本身的哈希；</li>
 *   <li>输出是 base64 **去掉尾部 `=` 填充**，前缀 `SHA256:`（OpenSSH 自 6.8 起的默认展示）。</li>
 * </ul>
 * 写错了不会报错，只会每次都比不上 —— 于是「每次连接都提示指纹变化」或「每次都当新主机」，
 * 两种表现都会让用户学会忽略提示，这比不能连更危险。
 *
 * <p>判定是纯函数（{@link #decide}），与服务端/客户端的 TOFU 语义一致：
 * 首次记录、相同放行、不同**阻断**（不是提醒后继续）。
 */
public final class SshHostKey {

    /** 与 client-core 的 `SSH_FINGERPRINT_MISMATCH` 同语义：不一致就拒绝，不"提醒后继续"。 */
    public enum Decision {
        /** 首次连接：记录并放行。 */
        FIRST_SEEN,
        /** 与已记录一致：放行。 */
        MATCH,
        /** 与已记录不一致：阻断（可能是中间人，也可能是对端重装）。 */
        MISMATCH
    }

    private static final char[] B64 =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".toCharArray();

    private SshHostKey() {}

    /** 判定：已记录为空 → 首次；相同 → 匹配；不同 → 冲突。 */
    public static Decision decide(String pinned, String actual) {
        String a = normalize(actual);
        if (a == null) return Decision.MISMATCH;      // 拿不到有效指纹时不能当"首次"放行
        String p = normalize(pinned);
        if (p == null) return Decision.FIRST_SEEN;
        return p.equals(a) ? Decision.MATCH : Decision.MISMATCH;
    }

    /**
     * 公钥 blob → `SHA256:<base64 无填充>`。
     *
     * @param blob base64 解码后的公钥原始字节
     */
    public static String fingerprintOfBlob(byte[] blob) {
        if (blob == null || blob.length == 0) {
            throw new IllegalArgumentException("公钥 blob 为空");
        }
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(blob);
            return "SHA256:" + base64NoPad(d);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用: " + e.getMessage(), e);
        }
    }

    /**
     * 从 `ssh-keyscan` 的输出里取指纹：行格式 `host ssh-ed25519 AAAA…`。
     *
     * @return 指纹；行非法返回 {@code null}
     */
    public static String fingerprintOfKeyLine(String line) {
        byte[] blob = blobOfKeyLine(line);
        return blob == null ? null : fingerprintOfBlob(blob);
    }

    /** 从 `host ssh-ed25519 AAAA…` 里解出 blob；非法返回 {@code null}。 */
    public static byte[] blobOfKeyLine(String line) {
        if (line == null) return null;
        String s = line.trim();
        if (s.isEmpty() || s.startsWith("#")) return null;
        String[] parts = s.split("\\s+");
        if (parts.length < 3) return null;            // host / 算法 / base64 三列
        return decodeBase64(parts[parts.length - 1]);
    }

    /**
     * 自带的最小 base64 解码（标准字母表，容忍 `=` 与空白）。
     *
     * <p>不用 {@code android.util.Base64}：那会让本类依赖 Android，桌面单测跑不起来。
     * 也不用 {@code java.util.Base64}：那是 API 26+，而本项目 minSdk 更低。
     */
    static byte[] decodeBase64(String in) {
        if (in == null || in.isEmpty()) return null;
        int[] map = new int[128];
        for (int i = 0; i < map.length; i++) map[i] = -1;
        for (int i = 0; i < B64.length; i++) map[B64[i]] = i;

        byte[] out = new byte[in.length() * 3 / 4 + 3];
        int len = 0, buf = 0, bits = 0;
        for (int i = 0; i < in.length(); i++) {
            char c = in.charAt(i);
            if (c == '=' ) break;
            if (c == ' ' || c == '\t' || c == '\r' || c == '\n') continue;
            if (c >= 128 || map[c] < 0) return null;
            buf = (buf << 6) | map[c];
            bits += 6;
            if (bits >= 8) {
                bits -= 8;
                out[len++] = (byte) ((buf >> bits) & 0xff);
            }
        }
        if (len == 0) return null;
        if (len == out.length) return out;
        byte[] trimmed = new byte[len];
        System.arraycopy(out, 0, trimmed, 0, len);
        return trimmed;
    }

    /** 归一化：去空白、补 `SHA256:` 前缀；空/非法返回 {@code null}。 */
    public static String normalize(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.isEmpty()) return null;
        int space = s.indexOf(' ');
        if (space > 0) s = s.substring(0, space);     // 容忍「指纹 + 注释」
        if (s.startsWith("SHA256:")) s = s.substring(7);
        if (s.isEmpty()) return null;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean ok = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
                    || (c >= '0' && c <= '9') || c == '+' || c == '/' || c == '=';
            if (!ok) return null;
        }
        return "SHA256:" + s;
    }

    private static String base64NoPad(byte[] d) {
        StringBuilder sb = new StringBuilder((d.length + 2) / 3 * 4);
        int i = 0;
        while (i + 2 < d.length) {
            int n = ((d[i] & 0xff) << 16) | ((d[i + 1] & 0xff) << 8) | (d[i + 2] & 0xff);
            sb.append(B64[(n >> 18) & 0x3f]).append(B64[(n >> 12) & 0x3f])
              .append(B64[(n >> 6) & 0x3f]).append(B64[n & 0x3f]);
            i += 3;
        }
        int rem = d.length - i;
        if (rem == 1) {
            int n = (d[i] & 0xff) << 16;
            sb.append(B64[(n >> 18) & 0x3f]).append(B64[(n >> 12) & 0x3f]);
        } else if (rem == 2) {
            int n = ((d[i] & 0xff) << 16) | ((d[i + 1] & 0xff) << 8);
            sb.append(B64[(n >> 18) & 0x3f]).append(B64[(n >> 12) & 0x3f])
              .append(B64[(n >> 6) & 0x3f]);
        }
        return sb.toString();
    }
}
