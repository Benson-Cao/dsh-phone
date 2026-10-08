package com.dsh.harness;

import android.webkit.WebView;

/**
 * 手机端适配外壳（注入到 dsh Web UI 里）。
 *
 * ## 为什么不能只靠 CSS 打补丁
 * dsh 的 Web UI 是**桌面优先**的三栏网格（{@code dsh-client-ui-layout}）：
 * <pre>
 *   narrow      = viewport &lt; 1024
 *   collapsed   = narrow ? !narrowExpanded : sidebar === 0
 *   sidebarPref = collapsed ? 0 : (sidebar === 0 ? 280 : sidebar)
 * </pre>
 * {@code narrowExpanded} 初值是 true，所以窄屏下侧栏**永不自动折叠**，
 * 而且未折叠时宽度直接取默认 **280px**。393px 的手机上侧栏吃掉 280px，
 * 中间对话区被压成一条竖排（标题逐字换行、输入框变成竖条）。
 *
 * 更关键的是：侧栏**只有"并排网格列"一种形态**，没有覆盖层/抽屉形态；
 * 而折叠态依赖 dsh 自己的状态机，原生折叠按钮又在侧栏内部——
 * 侧栏一旦被藏起来就再也打不开。所以 r16 那种"点一次原生折叠按钮"的补丁式做法体验不行。
 *
 * ## 这一版：注入自己的外壳，不碰 dsh 的状态机
 * 窄屏时：
 * <ol>
 *   <li>注入固定顶栏（汉堡按钮 + 标题），frame 加 padding-top 让出空间；</li>
 *   <li>侧栏 {@code position:absolute} 脱离网格流 → 变成抽屉，默认滑出屏幕外；</li>
 *   <li>遮罩 + 汉堡按钮控制抽屉，点遮罩或 Esc 关闭；</li>
 *   <li>隐藏 dsh 原生折叠按钮（避免两套导航入口打架）；</li>
 *   <li>主区 {@code minmax(0,1fr)} 独占整列，右栏列宽交给内容自适应。</li>
 * </ol>
 * 宽屏（≥1024px）下所有规则都不生效，桌面行为原样保留。
 *
 * 选择器都基于 dsh 自身的类名/属性（{@code frame} / {@code sidebarCol} / {@code handle}
 * 在各自 CSS module 内唯一；{@code aria-label} 用中英双语精确匹配），
 * 且全部幂等：重复注入不会叠加。
 */
public final class MobileTuning {

    private MobileTuning() {}

    /** 顶栏高度，同时用作侧栏抽屉的 top 偏移。 */
    private static final int TOPBAR_H = 48;

    private static final String CSS =
        /* 注入的外壳：默认 display:none，只有窄屏媒体查询里才启用 */
        "#dsh-mtop{position:fixed;top:0;left:0;right:0;height:" + TOPBAR_H + "px;z-index:61;"
        + "display:none;align-items:center;gap:8px;padding:0 8px 0 4px;box-sizing:border-box;"
        + "background:var(--dsw-alias-bg-base,#101215);"
        + "border-bottom:1px solid var(--dsw-alias-border-l3,rgba(255,255,255,.08));}\n"
        + "#dsh-mtop .dsh-mtitle{font-size:15px;font-weight:600;"
        + "color:var(--dsw-alias-text-primary,#e6e8eb);white-space:nowrap;"
        + "overflow:hidden;text-overflow:ellipsis;}\n"
        + "#dsh-mbtn{width:44px;height:44px;border:0;border-radius:10px;background:transparent;"
        + "color:inherit;display:flex;align-items:center;justify-content:center;padding:0;"
        + "-webkit-tap-highlight-color:transparent;cursor:pointer;}\n"
        + "#dsh-mbtn:active{background:rgba(255,255,255,.09);}\n"
        + "#dsh-scrim{position:fixed;inset:0;z-index:60;background:rgba(0,0,0,.5);"
        + "opacity:0;visibility:hidden;transition:opacity .2s ease,visibility .2s;}\n"
        + "body.dsh-drawer-open #dsh-scrim{opacity:1;visibility:visible;}\n"
        + "@media (max-width:1023px){\n"
        + "  #dsh-mtop{display:flex;}\n"
        // 主区独占第一列；右栏列宽交给内容（关着时为 0），避免 auto 隐式列出怪
        + "  div[class*=\"frame\"]{grid-template-columns:minmax(0,1fr) auto !important;"
        + "padding-top:" + TOPBAR_H + "px !important;box-sizing:border-box !important;}\n"
        // 侧栏脱离网格流 -> 抽屉
        + "  div[class*=\"sidebarCol\"]{position:absolute !important;"
        + "top:" + TOPBAR_H + "px !important;bottom:0 !important;left:0 !important;"
        + "width:min(80vw,320px) !important;z-index:62 !important;"
        + "transform:translate3d(-102%,0,0);"
        + "transition:transform .22s cubic-bezier(.4,0,.2,1);"
        + "box-shadow:0 8px 40px rgba(0,0,0,.55);will-change:transform;}\n"
        + "  body.dsh-drawer-open div[class*=\"sidebarCol\"]"
        + "{transform:translate3d(0,0,0) !important;}\n"
        // 窄屏隐藏 dsh 原生折叠按钮：入口统一到顶栏汉堡键
        + "  div[class*=\"sidebarCol\"] button[aria-label=\"收起侧边栏\"],"
        + "  div[class*=\"sidebarCol\"] button[aria-label=\"打开侧边栏\"],"
        + "  div[class*=\"sidebarCol\"] button[aria-label=\"Collapse sidebar\"],"
        + "  div[class*=\"sidebarCol\"] button[aria-label=\"Open sidebar\"]"
        + "{display:none !important;}\n"
        // 右栏别把主区挤没
        + "  div[class*=\"rightbarCol\"]{max-width:min(92vw,420px);}\n"
        // 拖拽把手在触屏上无用，还会吃掉边缘手势
        + "  div[class*=\"handle\"]{display:none !important;}\n"
        // 侧栏按钮给足触摸目标
        + "  div[class*=\"sidebarCol\"] button{min-height:40px;}\n"
        // 中文/西文标题不要逐字竖排
        + "  h1,h2,h3,h4,h5,h6{word-break:keep-all;overflow-wrap:normal;}\n"
        // 输入控件 >=16px，否则 Android 会自动放大整页
        + "  input,textarea,select{font-size:16px !important;}\n"
        // 禁掉橡皮筋与双击缩放，更接近原生手感
        + "  html,body{overscroll-behavior:none;}\n"
        + "  body{touch-action:manipulation;}\n"
        + "}\n"
        + "@media (prefers-reduced-motion:reduce){"
        + "div[class*=\"sidebarCol\"],#dsh-scrim{transition:none !important;}}\n";

    private static final String JS =
        "(function(){\n"
        + "  var SID='dsh-m-shell';\n"
        + "  if(document.getElementById(SID)){return;}\n"          // 幂等
        + "  var mq=window.matchMedia('(max-width:1023px)');\n"
        + "  function close(){document.body.classList.remove('dsh-drawer-open');}\n"
        + "  function mount(){\n"
        + "    if(document.getElementById(SID)){return;}\n"
        + "    var mark=document.createElement('div');mark.id=SID;\n"
        + "    document.body.appendChild(mark);\n"
        + "    var st=document.createElement('style');st.id=SID+'-css';\n"
        + "    st.textContent=" + jsString(CSS) + ";\n"
        + "    (document.head||document.documentElement).appendChild(st);\n"
        // 顶栏：汉堡按钮 + 标题
        + "    var bar=document.createElement('div');bar.id='dsh-mtop';\n"
        + "    bar.innerHTML='<button id=\"dsh-mbtn\" type=\"button\" aria-label=\"菜单\">'\n"
        + "      +'<svg width=\"22\" height=\"22\" viewBox=\"0 0 24 24\" fill=\"none\"'\n"
        + "      +' stroke=\"currentColor\" stroke-width=\"2\" stroke-linecap=\"round\">'\n"
        + "      +'<path d=\"M3 6h18M3 12h18M3 18h18\"/></svg></button>'\n"
        + "      +'<span class=\"dsh-mtitle\">DeepSeek Harness</span>';\n"
        + "    document.body.appendChild(bar);\n"
        + "    var scrim=document.createElement('div');scrim.id='dsh-scrim';\n"
        + "    document.body.appendChild(scrim);\n"
        + "    bar.querySelector('#dsh-mbtn').addEventListener('click',function(e){\n"
        + "      e.preventDefault();e.stopPropagation();\n"
        + "      document.body.classList.toggle('dsh-drawer-open');\n"
        + "    });\n"
        + "    scrim.addEventListener('click',close);\n"
        + "    document.addEventListener('keydown',function(e){if(e.key==='Escape'){close();}});\n"
        + "    if(mq.addEventListener){mq.addEventListener('change',onMq);}\n"
        + "    else if(mq.addListener){mq.addListener(onMq);}\n"
        + "  }\n"
        // 转宽屏时别把抽屉状态带过去
        + "  function onMq(e){if(!e.matches){close();}}\n"
        + "  if(document.body){mount();}\n"
        + "  else{document.addEventListener('DOMContentLoaded',mount);}\n"
        + "})();\n";

    /** 把一段文本安全地嵌进 JS 字符串字面量。 */
    private static String jsString(String s) {
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

    /** 幂等注入。页面每次加载完成时调用一次即可。 */
    public static void apply(WebView webView) {
        if (webView == null) return;
        webView.evaluateJavascript(JS, null);
    }
}