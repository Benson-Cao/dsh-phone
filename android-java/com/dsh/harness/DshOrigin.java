package com.dsh.harness;

import java.net.URI;
import java.util.Locale;

/**
 * 来源判定（A-02 · JS 桥纵深防御的**核心判定**）。
 *
 * ⚠️ 刻意**不 import 任何 android.\*** —— 这样它能在 JVM 上直接编译并跑单元测试，
 *    判定逻辑（尤其是 userinfo 欺骗这类边界）可以脱离设备被验证。
 *    见 D:/work/dsh-a02-test/DshOriginTest.java
 *
 * 为什么需要它：{@code addJavascriptInterface} 暴露的 dshNative 能下载并执行代码，
 * 而 dsh 页面渲染的是远端 LLM 内容、自身 XSS 风险不低。此前「WebView 只 load
 * 127.0.0.1」只是**假设**；本类把假设变成可判定的硬门槛。
 *
 * 判定策略：**fail-safe**。任何无法确定的情况（null / 空 / 解析失败）一律判为「非本地」。
 * 安全默认值优先于可用性——这里错判的代价是执行远程代码。
 */
public final class DshOrigin {

    private DshOrigin() {}

    /** URL 是否指向本机 dsh（127.0.0.1 / localhost / ::1）。 */
    public static boolean isLocalUrl(String url) {
        if (url == null) return false;
        String s = url.trim();
        if (s.isEmpty()) return false;
        try {
            return isLocalHost(new URI(s).getHost());
        } catch (Throwable e) {
            // 含中文/非法字符时 URI 解析会失败 —— 判为不可信，而不是放行
            return false;
        }
    }

    /** host 是否为本机回环地址。 */
    public static boolean isLocalHost(String host) {
        if (host == null) return false;
        String h = host.trim().toLowerCase(Locale.ROOT);
        // URI.getHost() 对 IPv6 会带上方括号
        if (h.length() >= 2 && h.charAt(0) == '[' && h.charAt(h.length() - 1) == ']') {
            h = h.substring(1, h.length() - 1);
        }
        if ("::1".equals(h) || "0:0:0:0:0:0:0:1".equals(h)) return true;
        return "127.0.0.1".equals(h) || "localhost".equals(h);
    }

    /**
     * v2.3 批 B：url 与给定 origin（scheme://host[:port]）是否同源。
     *
     * <p>用途：非 local 档案（relay 是远端 origin）下，WebViewClient 的外链分流
     * 不能再只认回环 —— 当前档案自己的 origin 也要留在 WebView 内。
     * 判定仍归 {@link DshOrigin} 管（本类保持「不 import android」的可单测铁律）。
     *
     * <p>fail-safe：任一侧解析失败 / origin 为空 → false（宁可当外链丢出去，
     * 也不能把陌生源当可信源留在带桥的 WebView 里）。
     * 端口缺省补全：http=80、https=443（{@code https://r.example.com} 与
     * {@code https://r.example.com:443/d/...} 必须判同）。
     */
    public static boolean sameOrigin(String url, String origin) {
        if (url == null || origin == null) return false;
        String o = origin.trim();
        if (o.isEmpty()) return false;
        try {
            URI u = new URI(url.trim());
            URI b = new URI(o);
            String us = u.getScheme(), bs = b.getScheme();
            String uh = u.getHost(), bh = b.getHost();
            if (us == null || bs == null || uh == null || bh == null) return false;
            if (!us.toLowerCase(Locale.ROOT).equals(bs.toLowerCase(Locale.ROOT))) return false;
            if (!uh.toLowerCase(Locale.ROOT).equals(bh.toLowerCase(Locale.ROOT))) return false;
            // 两边各按「显式端口优先，否则 scheme 缺省端口」归一后比较。
            // （首版曾写成 defaultPort(scheme) == explicit —— 任何带端口的同源
            //   判定都会误判 false，单测抓出。）
            return portOf(us, u.getPort()) == portOf(bs, b.getPort());
        } catch (Throwable e) {
            return false;
        }
    }

    /** scheme 的默认端口；未知 scheme 返回 -1（与「未写端口」同值 → 判等通过）。 */
    private static int defaultPort(String scheme) {
        String s = scheme.toLowerCase(Locale.ROOT);
        if ("http".equals(s)) return 80;
        if ("https".equals(s)) return 443;
        return -1;
    }

    /** 显式端口优先，未写（-1/0）时回 scheme 缺省端口。 */
    private static int portOf(String scheme, int explicitPort) {
        return explicitPort > 0 ? explicitPort : defaultPort(scheme);
    }
}