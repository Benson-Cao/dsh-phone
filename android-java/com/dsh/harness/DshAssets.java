package com.dsh.harness;

/**
 * A-04：注入资源（CSS/JS）的读取与拼装。
 *
 * 刻意**不 import 任何 android.\*** —— 拼装逻辑因此能在 JVM 上直接跑单测
 * （D:/work/dsh-a04-test/DshAssetsTest.java）。搬 95KB 字符串这种体量的重构，
 * 只有「与黄金基准逐字节比对」才敢说没改错；靠肉眼看 diff 是看不出来的。
 *
 * 资源布局（真源在 ③ 发布仓库 {@code android-java/assets/dsh/}，
 * 由 scripts/sync-from-source.sh 同步到构建树的 {@code app/src/main/assets/dsh/}）：
 * <pre>
 *   mobile.css            设置面板 + 首页外壳样式   （原 MobileTuning.CSS）
 *   market.css            /dsh-market 市场页适配    （原 MARKET_CSS）
 *   market-chrome.css     市场页顶栏/汉堡安全区      （原 MARKET_CHROME_CSS）
 *   mobile.js             注入脚本                   （原 JS）
 * </pre>
 *
 * 为什么 JS 里用 token 而不是把 CSS 直接嵌进去：CSS 有 19KB，嵌成 JS 字面量后
 * 脚本会涨到 40KB，且改一处 CSS 要同步改两个地方。token 化之后 CSS 与 JS
 * 各自独立，{@code mobile.css} 改动不会牵动脚本。
 */
public final class DshAssets {

    public static final String F_CSS           = "dsh/mobile.css";
    public static final String F_MARKET        = "dsh/market.css";
    public static final String F_MARKET_CHROME = "dsh/market-chrome.css";
    public static final String F_JS            = "dsh/mobile.js";

    /** mobile.js 里三处占位符，运行时替换成转义后的 CSS 字面量。 */
    public static final String T_CSS           = "__DSH_CSS__";
    public static final String T_MARKET        = "__DSH_MARKET_CSS__";
    public static final String T_MARKET_CHROME = "__DSH_MARKET_CHROME_CSS__";

    private DshAssets() {}

    /** 读取资源的抽象，使拼装逻辑不依赖 Android、可被单测替换。 */
    public interface Reader {
        String read(String assetPath) throws Exception;
    }

    /** 把 JS 模板中的 token 换成转义后的 CSS 字面量，得到最终注入脚本。 */
    public static String buildJs(String jsTemplate, String css,
                                 String marketCss, String marketChromeCss) {
        return jsTemplate
            .replace(T_MARKET,        quote(marketCss))
            .replace(T_MARKET_CHROME, quote(marketChromeCss))
            .replace(T_CSS,           quote(css));
    }

    /** 读齐 4 个资源并拼出最终注入脚本。 */
    public static String loadJs(Reader r) throws Exception {
        return buildJs(
            r.read(F_JS),
            r.read(F_CSS),
            r.read(F_MARKET),
            r.read(F_MARKET_CHROME));
    }

    /**
     * 把一段文本安全地嵌进 JS 字符串字面量。
     *
     * ⚠️ 必须与搬 assets 之前的 {@code MobileTuning.jsString} **逐字节一致**，
     *    否则注入脚本里的 CSS 会被错误转义、样式整体失效。
     *    {@code DshAssetsTest} 会拿黄金基准逐字节校验这一点。
     */
    public static String quote(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':  sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n");  break;
                case '\r': sb.append("\\r");  break;
                case '\t': sb.append("\\t");  break;
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
}