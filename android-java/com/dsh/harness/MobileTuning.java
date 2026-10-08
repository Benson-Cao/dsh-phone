package com.dsh.harness;

import android.webkit.WebView;

/**
 * 手机端适配补丁（由 App 注入到 dsh Web UI 里）。
 *
 * dsh 的 Web UI 是**桌面优先**的三栏布局：窄屏下
 * -侧栏折叠与否由 {@code narrowExpanded} 决定，初值为 true → 侧栏不自动折叠，
 *  而未折叠时宽度直接取默认 280px（{@code sidebarPreference}），
 *  在 393px 宽的手机上会把中间的对话区挤成一条竖排；
 * - 侧栏展开时也是并排的网格列（没有覆盖层形态），所以必须限宽。
 *
 * 注入内容分两部分，都做幂等：
 *  1. 一段窄屏 CSS：限宽侧栏 + 修掉中文标题逐字竖排 + 输入控件 ≥16px（避免系统自动缩放）；
 * 2. 一次性点击侧栏折叠按钮（走应用自己的状态机，状态会被它自己持久化），
 *     找不到按钮、检测到已折叠、或非窄屏时都会停下。
 */
public final class MobileTuning {

    private MobileTuning() {}

    private static final String CSS =
        "/* dsh-harness 手机适配（App 注入） */\n"
        + "@media (max-width:1023px){\n"
        // 侧栏展开时把它压到 58vw（上限 260px），保证中间对话区可用。
        // 只在右栏收起时生效；用户打开右栏后这个属性消失，内联样式自动恢复。
        + "div[class*=\"frame\"][data-rightbar-collapsed]:not([data-sidebar-collapsed]){\n"
        + "  grid-template-columns:min(58vw,260px) minmax(0,1fr) 0 !important;\n"
        + "}\n"
        // 拖拽把手在触屏上没用，还会吃掉边缘手势
        + "div[class*=\"handle\"]{display:none !important;}\n"
        // 中文/西文标题不要逐字竖排
        + "h1,h2,h3,h4,h5,h6{word-break:keep-all;overflow-wrap:normal;}\n"
        // 输入控件 ≥16px，否则 Android 会自动放大整页
        + "input,textarea,select{font-size:16px !important;}\n"
        // 接近原生的滚动手感
        + "html,body{overscroll-behavior:none;}\n"
        + "}\n";

    private static final String JS =
        "(function(){\n"
        + "  var CSSID='dsh-mobile-css';\n"
        + "  var CSS=" + jsString(CSS) + ";\n"
        + "  var DONE='dsh-mobile-collapsed-v1';\n"
        + "  var OPEN=/^(打开侧边栏|Open sidebar)$/;\n"
        + "  var COLLAPSE=/^(收起侧边栏|Collapse sidebar)$/;\n"
        + "  function css(){\n"
        + "    if(document.getElementById(CSSID))return;\n"
        + "    var s=document.createElement('style');s.id=CSSID;s.textContent=CSS;\n"
        + "    (document.head||document.documentElement).appendChild(s);\n"
        + "  }\n"
        + "  function mark(){try{sessionStorage.setItem(DONE,'1');}catch(e){}}\n"
        + "  function done(){try{return sessionStorage.getItem(DONE)===null?false:true;}catch(e){return true;}}\n"
        + "  function collapse(){\n"
        + "    css();\n"
        + "    if(window.innerWidth>=1024){return true;}\n"
        + "    var bs=document.querySelectorAll('button[aria-label]');\n"
        + "    for(var i=0;i<bs.length;i++){\n"
        + "      var al=bs[i].getAttribute('aria-label')||'';\n"
        + "      if(COLLAPSE.test(al)){bs[i].click();mark();return true;}\n"
        + "      if(OPEN.test(al)){mark();return true;}\n"
        + "    }\n"
        + "    return false;\n"
        + "  }\n"
        + "  css();\n"
        + "  if(done()){return;}\n"
        + "  // 侧边栏是插件异步渲染的，轮询到出现或超时就停\n"
        + "  var n=0;\n"
        + "  var t=setInterval(function(){\n"
        + "    if(collapse()||++n>40){clearInterval(t);}\n"
        + "  },300);\n"
        + "  window.addEventListener('resize',css);\n"
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

    /** 幂等注入。 */
    public static void apply(WebView webView) {
        if (webView == null) return;
        webView.evaluateJavascript(JS, null);
    }
}