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

    /**
     * 是否是「手机上的回环 / 未指定地址」——在手机上永远连不到电脑。
     *
     * <p>为什么需要这个判断：Relay 回给客户端的 {@code relayUrl} 是**按请求 Host 头回填**的，
     * 所以只要历史上从回环地址成功配对过一次（例如电脑侧自测、或经端口转发），
     * 手机就会把 {@code http://127.0.0.1:8899} 记成「上次用的地址」，
     * 之后每次打开配对页都预填这个死地址 —— 用户点十次也连不上，还以为是码的问题。
     * 写入与读取两侧都要挡：写入侧不落盘，读取侧清掉历史脏数据。
     */
    public static boolean isLoopback(String raw) {
        String b = normalizeBase(raw);
        if (b == null) return false;
        String rest = b.substring(b.indexOf("://") + 3);
        int slash = rest.indexOf('/');
        String authority = slash < 0 ? rest : rest.substring(0, slash);
        String host;
        if (authority.startsWith("[")) {          // IPv6 字面量：[::1]:8899
            int close = authority.indexOf(']');
            if (close < 0) return false;
            host = authority.substring(1, close);
        } else {
            int colon = authority.lastIndexOf(':');   // 去端口（可能没有）
            host = colon < 0 ? authority : authority.substring(0, colon);
        }
        host = host.trim().toLowerCase(java.util.Locale.ROOT);
        if (host.isEmpty()) return false;
        return host.equals("localhost") || host.equals("::1") || host.equals("0.0.0.0")
                || host.equals("127.0.0.1") || host.startsWith("127.");
    }
}
