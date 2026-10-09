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
}