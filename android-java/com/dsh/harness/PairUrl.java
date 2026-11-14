package com.dsh.harness;

/**
 * 配对端点地址规整 —— 用户输入「192.168.0.5:8787」这种半截地址是常态。
 *
 * <p>不规整会以两种方式失败，且报错都指向错误的地方：
 * <ul>
 *   <li>缺 scheme → {@code new URL("192.168.0.5:8787")} 抛 MalformedURLException，
 *       界面显示「连不上电脑」，用户以为自己地址写错了，其实只是少写 `http://`；</li>
 *   <li>带尾斜杠 → 拼出 `//pair`，Relay 按未知路径回 404，错误码又变成「配对码不对」。</li>
 * </ul>
 *
 * <p>纯逻辑、零 Android 依赖，桌面可测。
 */
public final class PairUrl {

    private PairUrl() {}

    /**
     * 规整为 {@code scheme://host[:port][/path]} 且无尾斜杠；非法返回 {@code null}。
     *
     * <p>不擅自补端口：Relay 的默认端口由部署决定，猜错比不猜更糟
     * （会连到一个不相干的、恰好监听 80 的服务并报证书/协议错）。
     */
    public static String normalizeBase(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.isEmpty()) return null;
        if (!s.startsWith("http://") && !s.startsWith("https://")) {
            s = "http://" + s;
        }
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        int scheme = s.indexOf("://");
        if (scheme <= 0) return null;
        String rest = s.substring(scheme + 3);
        int slash = rest.indexOf('/');
        String authority = slash < 0 ? rest : rest.substring(0, slash);
        if (authority.isEmpty()) return null;
        // 拒绝空白与明显非法的 authority（尾斜杠在 :port/ 之后已剥掉）
        for (int i = 0; i < authority.length(); i++) {
            char c = authority.charAt(i);
            if (c <= 0x20 || c == '\\') return null;
        }
        if (authority.contains("@")) return null;   // 不在地址栏里处理凭据
        return s;
    }

    /** 拼端点：{@code base + path}，base 必须先经 {@link #normalizeBase}。 */
    public static String endpoint(String base, String path) {
        String b = normalizeBase(base);
        if (b == null) return null;
        String p = path == null ? "" : path.trim();
        if (!p.startsWith("/")) p = "/" + p;
        return b + p;
    }
}
