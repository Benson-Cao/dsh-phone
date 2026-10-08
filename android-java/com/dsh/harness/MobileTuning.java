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
        /* ===== 设计稿《移动端 UI 设计系统》token（唯一依据：资料库设计稿 :root 变量）=====
         * 之前顶栏用的是 dsh 深色主题（#101215 底 + 白字），与设计稿的浅色体系冲突。
         * 现在统一到设计稿配色，并顺带把品牌 token 注入为 CSS 变量，
         * 供下方设置页/输入区等规则引用。 */
        ":root{--ds-brand-500:#2b8ae8;--ds-brand-50:#eef7fe;--ds-brand-100:#d6ecfc;"
        + "--ds-ink-900:#0d1b2e;--ds-ink-700:#1e3450;--ds-ink-500:#5a6d84;--ds-ink-400:#8296ab;"
        + "--ds-ink-200:#d8e1ea;--ds-ink-100:#eef2f6;--ds-ink-50:#f7f9fc;"
        + "--ds-r-sm:12px;--ds-r-md:16px;--ds-r-lg:22px;--ds-r-full:999px;}\n"
        /* 注入的外壳：默认 display:none，只有窄屏媒体查询里才启用 */
        + "#dsh-mtop{position:fixed;top:0;left:0;right:0;height:" + TOPBAR_H + "px;z-index:61;"
        + "display:none;align-items:center;gap:8px;padding:0 10px 0 6px;box-sizing:border-box;"
        + "background:var(--ds-ink-50);"
        + "border-bottom:1px solid var(--ds-ink-200);}\n"
        // 设计稿「首页布局规格」：工具条按钮 42×42pt；标题用 ink-900
        + "#dsh-mtop .dsh-mtitle{font-size:16px;font-weight:700;"
        + "color:var(--ds-ink-900);white-space:nowrap;"
        + "overflow:hidden;text-overflow:ellipsis;letter-spacing:0;}\n"
        + "#dsh-mbtn{width:42px;height:42px;border:0;border-radius:12px;background:transparent;"
        + "color:var(--ds-ink-900);display:flex;align-items:center;justify-content:center;padding:0;"
        + "-webkit-tap-highlight-color:transparent;cursor:pointer;}\n"
        // 按压反馈用 ink-100（设计稿浅色体系下的hover/active 面）
        + "#dsh-mbtn:active{background:var(--ds-ink-100);}\n"
        + "#dsh-scrim{position:fixed;inset:0;z-index:60;background:rgba(13,27,46,.42);"
        + "opacity:0;visibility:hidden;transition:opacity .2s ease,visibility .2s;}\n"
        + "body.dsh-drawer-open #dsh-scrim{opacity:1;visibility:visible;}\n"
        + "@media (max-width:1023px){\n"
        + "  #dsh-mtop{display:flex;}\n"
        // 主区独占第一列；右栏列宽交给内容（关着时为 0），避免 auto 隐式列出怪
        + "  div[class*=\"frame\"]{grid-template-columns:minmax(0,1fr) auto !important;"
        + "padding-top:" + TOPBAR_H + "px !important;box-sizing:border-box !important;}\n"
        // 侧栏脱离网格流 -> 抽屉。窄屏下开到接近全屏：dsh 的设置面板是「导航 + 内容」
        // 两栏并排（导航列约 180px），侧栏太窄会把内容列压成一字一行。
        + "  div[class*=\"sidebarCol\"]{position:absolute !important;"
        + "top:" + TOPBAR_H + "px !important;bottom:0 !important;left:0 !important;"
        + "width:calc(100vw - 20px) !important;max-width:none !important;z-index:62 !important;"
        + "transform:translate3d(-102%,0,0);"
        + "transition:transform .22s cubic-bezier(.4,0,.2,1);"
        + "box-shadow:0 8px 40px rgba(0,0,0,.55);will-change:transform;}\n"
        + "  body.dsh-drawer-open div[class*=\"sidebarCol\"]"
        + "{transform:translate3d(0,0,0) !important;}\n"
        // 挤成竖排的根因：flex/grid 子项默认 min-width:auto 会拒绝收缩，
        // 叠上中文的 word-break 规则就成了「一字一行」。允许收缩 + 允许折行即可。
        + "  div[class*=\"sidebarCol\"] *{min-width:0;}\n"
        + "  div[class*=\"sidebarCol\"] p,div[class*=\"sidebarCol\"] span,"
        + "div[class*=\"sidebarCol\"] label,div[class*=\"sidebarCol\"] div,"
        + "div[class*=\"sidebarCol\"] button{overflow-wrap:anywhere;}\n"
        // 窄屏隐藏 dsh 原生折叠按钮：入口统一到顶栏汉堡键
        + "  div[class*=\"sidebarCol\"] button[aria-label=\"收起侧边栏\"],"
        + "  div[class*=\"sidebarCol\"] button[aria-label=\"打开侧边栏\"],"
        + "  div[class*=\"sidebarCol\"] button[aria-label=\"Collapse sidebar\"],"
        + "  div[class*=\"sidebarCol\"] button[aria-label=\"Open sidebar\"]"
        + "{display:none !important;}\n"
        // 右栏别把主区挤没
        + "  div[class*=\"rightbarCol\"]{max-width:min(92vw,420px);}\n"
        // ===== 设置面板重写（窄屏）·对齐《移动端 UI 设计系统》设置页规格 =====
        // dsh 真实结构（从 dsh-client-ui-settings-general 的 CSS module 挖出）：
        //   [class*="_overlay"] position:fixed inset:0 display:flex（独立浮层）
        //   [class*="_panel"]   width:800px; max-width:calc(100vw - 48px); display:flex
        //   [class*="_nav"]     width:188px  ← 硬编码固定宽，两栏并排的根源
        //   [class*="_content"] flex-direction:column; flex:1; min-width:0
        //   [class*="_options"] padding:0 24px 24px; overflow-y:auto
        //   [class*="_navCell"] / [class*="_navLabel"] / [class*="_navList"] / [class*="_navTitle"]
        //
        // ⚠️ 面板是 position:fixed 的独立浮层，宽度参照**视口**而非侧栏 ——
        // 所以把侧栏拉宽对它毫无作用。手机上 100vw-48px 再减 188px 导航列，
        // 内容区只剩约 130px，中文标签 min-width:auto 就是 1 个字宽 → 一字一行。
        //
        // 设计稿要求的形态（不是横向 tab！）：**单列全宽列表**
        //   列表项 15.5px/#111 + 图标 21pt、行高 15px 内边距、圆角胶囊选择器 #F1F3F5、
        //   分组标签 13px/#8B9098 左缩进 26px。
        // dsh 是单页结构（导航与内容同一容器），所以让导航项竖排占满一行、内容在下，
        // 点哪个导航项就显示对应内容 —— 视觉上就是设计稿的全屏单列设置页。
        // 用属性选择器 [class*="_nav"] 前缀匹配，规避 CSS module 哈希随版本变化。
        + "  [class*=\"_overlay\"]{padding:0 !important;align-items:stretch !important;"
        + "justify-content:stretch !important;}\n"
        + "  [class*=\"_panel\"]{width:100vw !important;max-width:100vw !important;"
        + "height:100vh !important;border-radius:0 !important;flex-direction:column !important;}\n"
        // ⚠️ 用 .dsh-s-nav 限定作用域，**不要再用 [class*="_nav"]**：
        //   `_nav` 是 `_navTitle`/`_navList`/`_navCell`/`_navIcon`/`_navLabel`
        //   的**共同前缀**。模糊匹配会把容器样式（width:100%、flex-direction:column）
        //   套到图标和文字上 -> 图标独占一行、文字另起一行，每项撑到~100px
        //   （真机反馈"布局太丑了"）。`.dsh-s-nav` 由 JS 打在导航容器上。
        + "  .dsh-s-nav{width:100% !important;max-width:100% !important;"
        + "flex:0 0 auto!important;flex-direction:column!important;gap:0!important;"
        + "padding:8px 0 6px!important;overflow:visible!important;"
        + "border-bottom:1px solid var(--ds-ink-200,#d8e1ea)!important;}\n"
        // 导航标题（"设置"）隐藏：面板 header 已有标题，避免重复
        + "  .dsh-s-nav [class*=\"_navTitle\"]{display:none!important;}\n"
        // 导航列表：竖排
        + "  .dsh-s-nav [class*=\"_navList\"]{flex-direction:column!important;gap:2px!important;}\n"
        // 列表项：设计稿「设置页规格」15.5px + 图标 21pt + 行高 15px 内边距。
        //关键是 flex-direction:row（图标与文字**同一行**）+ 上下 padding + 圆角。
        + "  .dsh-s-nav [class*=\"_navCell\"]{width:100%!important;height:auto!important;"
        + "min-height:46px!important;padding:12px 20px!important;gap:12px!important;"
        + "flex-direction:row!important;align-items:center!important;"
        + "border-radius:12px!important;font-size:15.5px!important;"
        + "white-space:normal!important;text-align:left!important;}\n"
        + "  .dsh-s-nav [class*=\"_navIcon\"]{width:21px!important;height:21px!important;"
        + "flex:none!important;}\n"
        + "  .dsh-s-nav [class*=\"_navLabel\"]{font-size:15.5px!important;"
        + "font-weight:600!important;color:var(--ds-ink-900,#0d1b2e)!important;"
        + "white-space:normal!important;overflow-wrap:normal!important;"
        + "word-break:normal!important;flex:1 1 auto!important;min-width:0!important;}\n"
        // 选中态：设计稿 #F1F3F5 胶囊（作用在 navCell 本身）
        + "  .dsh-s-nav [class*=\"_active\"]{background:#f1f3f5!important;border-radius:12px!important;}\n"
        // 内容：全宽单列
        + "  [class*=\"_content\"]{width:100% !important;min-width:0 !important;"
        + "flex:1 1 auto !important;}\n"
        + "  [class*=\"_options\"]{padding:0 26px 24px !important;}\n"
        // 覆盖 r18 的 anywhere —— 强制正常换行，杜绝一字一行
        + "  [class*=\"_panel\"] *{overflow-wrap:normal !important;word-break:normal !important;}\n"
        + "  [class*=\"_options\"] *{max-width:100% !important;}\n"
        // ===== 设置页分级：二级列表 → 三级详情（对齐设计稿的两级页面）=====
        // 二级页：只显示导航列表（通用设置/ 模型 / 插件 / Agent 预设…），标题为「设置」
        // 三级页：点某个导航项后，内容全屏显示，顶部出现返回箭头
        //实现：body 上挂 dsh-s-l2 / dsh-s-l3 两个状态类，由 JS 切换。
        // ⚠️ 所有状态规则都必须**收窄到 _panel 内**：`_content`/`_nav` 是通用后缀，
        //    不限定作用域会误伤 dsh 主界面上同后缀的元素（r25 灰屏的放大器）。
        + "  body.dsh-s-l2 [class*=\"_panel\"] [class*=\"_content\"]{display:none !important;}\n"
        + "  body.dsh-s-l3 .dsh-s-nav{display:none!important;}\n"
        // 三级页头部：显示返回箭头 + 标题
        + "  [class*=\"_panel\"] [class*=\"_header\"]{display:flex !important;align-items:center !important;"
        + "gap:10px !important;padding:14px 18px 8px 12px !important;}\n"
        + "  [class*=\"_panel\"] div[class*=\"_headerTitle\"]{font-size:18px !important;font-weight:750 !important;"
        + "color:var(--ds-ink-900,#0d1b2e) !important;flex:1 !important;}\n"
        // 我们自建的返回按钮（仅三级页可见）
        + "  #dsh-s-back{display:none !important;width:40px !important;height:40px !important;"
        + "border:0 !important;border-radius:999px !important;background:var(--ds-ink-100,#eef2f6) !important;"
        + "align-items:center !important;justify-content:center !important;cursor:pointer;"
        + "-webkit-tap-highlight-color:transparent;}\n"
        + "  body.dsh-s-l3 #dsh-s-back{display:inline-flex !important;}\n"
        // 二级页的面板/导航间距
        + "  body.dsh-s-l2 .dsh-s-nav{padding-top:4px!important;}\n"
        // ===== 插件市场（三级页）· 对齐设计稿单列全宽风格 =====
        // dsh-client-ui-settings-plugin-inventory 自带网格 `_cards`
        // (minmax(0,1fr) / repeat(2,...))，手机上两列会把插件名挤成竖排，
        // 统一压成单列；卡片圆角/边框按设计稿 ink-200 走。
        + "  [class*=\"_cards\"]{grid-template-columns:minmax(0,1fr) !important;gap:10px !important;}\n"
        + "  [class*=\"_card\"]{border-radius:var(--ds-r-md,16px) !important;}\n"
        + "  [class*=\"_cardTitle\"]{font-size:15.5px !important;font-weight:600 !important;}\n"
        + "  [class*=\"_group\"]{border-top:1px solid var(--ds-ink-200,#d8e1ea) !important;"
        + "padding-top:14px !important;margin-top:4px !important;}\n"
        + "  [class*=\"_catalogHeading\"]{font-size:13px !important;color:var(--ds-ink-500,#5a6d84) !important;}\n"
        + "  [class*=\"_details\"]{grid-template-columns:76px minmax(0,1fr) !important;}\n"
        + "  [class*=\"_entryValue\"],.dsh-s-l3 [class*=\"_options\"] *{overflow-wrap:anywhere !important;}\n"
        // ===== 首页/对话区 · 对齐设计稿「首页布局规格· Home Layout」=====
        // 输入卡圆角 24px、边框 #E6E8EB（聚焦转 #C3CFE0）
        + "  div[class*=\"frame\"] textarea,div[class*=\"frame\"] input[type=\"text\"]{"
        + "border-radius:24px !important;background:var(--ds-ink-50,#f7f9fc) !important;"
        + "border:1px solid var(--ds-ink-200,#d8e1ea) !important;}\n"
        + "  div[class*=\"frame\"] textarea:focus,div[class*=\"frame\"] input[type=\"text\"]:focus{"
        + "border-color:#c3cfe0 !important;background:#fff !important;}\n"
        // 发送按钮 38pt，占位态淡紫蓝 #B8C7F2
        + "  div[class*=\"frame\"] button[aria-label*=\"发送\"],"
        + "div[class*=\"frame\"] button[aria-label*=\"Send\"]{"
        + "width:38px !important;height:38px !important;border-radius:var(--ds-r-full,999px) !important;"
        + "background:#b8c7f2 !important;transition:background 150ms cubic-bezier(.4,0,.2,1) !important;}\n"
        // 预览版徽章胶囊（设计稿：#DFE8FF 底 / #2B4A7D 字 / 10.5px 半粗）
        + "  span[class*=\"badge\"],div[class*=\"badge\"]{border-radius:var(--ds-r-full,999px) !important;"
        + "background:#dfe8ff !important;color:#2b4a7d !important;font-size:10.5px !important;"
        + "font-weight:600 !important;}\n"
        // 主内容垂直居中，底部预留 48px 视觉配重（设计稿）
        + "  div[class*=\"frame\"] > div[class*=\"col\"]{justify-content:center !important;"
        + "padding-bottom:48px !important;}\n"
        + "  [class*=\"_content\"]{width:100% !important;min-width:0 !important;flex:1 1 auto !important;}\n"
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
        // ===== 设置页二级/三级分级逻辑 =====
        // dsh 的设置面板是单页结构（导航与内容同容器）。这里用 CSS 变量类把
        // 它拆成设计稿那样的两级页面：
        //   二级（dsh-s-l2）：只显示导航列表（通用设置/模型/插件/Agent 预设）
        //   三级（dsh-s-l3）：点导航项后内容全屏，左上角换成分级返回箭头
        // 关键：**纯 CSS 做不了"点击切页"**，必须有 JS 记状态；但**定位用属性选择器前缀**，
        // 不依赖 CSS module 哈希（r20 用文本定位失败过——文字被包在 _navLabel 里）。
        + "    (function(){\n"
        + "      if(!mq.matches){return;}\n"
        + "      var b=document.body;\n"
        // ---- 二级/三级状态管理 ----
        // L2 = 只显示导航列表（设置首页）；L3 = 只显示某个设置项的详情。
        // 关键：状态类**只在设置面板 overlay 存在时**才生效，且必须由
        // syncState() 真正调用（r26 漏调，导致 L2 从未加上，
        // 导航与内容一直并排显示 —— 真机反馈"还是放在同一个页面里"）。
        + "      function overlay(){return document.querySelector('[class*=\"_overlay\"]');}\n"
        + "      function setL2(){b.classList.remove('dsh-s-l3');b.classList.add('dsh-s-l2');}\n"
        + "      function setL3(){b.classList.remove('dsh-s-l2');b.classList.add('dsh-s-l3');}\n"
        // 自建返回按钮（设计稿：40pt 圆底 #F1F3F5 + chevron-left）
        + "      var back=document.createElement('button');back.id='dsh-s-back';\n"
        + "      back.type='button';back.setAttribute('aria-label','返回');\n"
        + "      back.innerHTML='<svg width=\"20\" height=\"20\" viewBox=\"0 0 24 24\" fill=\"none\"'\n"
        + "        +' stroke=\"currentColor\" stroke-width=\"2.2\" stroke-linecap=\"round\"'\n"
        + "        +' stroke-linejoin=\"round\"><path d=\"M15 5l-7 7 7 7\"/></svg>';\n"
        + "      back.addEventListener('click',function(e){e.preventDefault();e.stopPropagation();setL2();sync();});\n"
        // 单一 sync：面板开->按当前层级显示；面板关->清状态类
        // 给导航**容器**打标记：CSS 靠它限定作用域，避免 [class*="_nav"]
        // 过度匹配到 _navIcon/_navLabel（那会把图标和文字也变成 column 布局）。
        // 定位方式：_navList 的父元素就是容器（结构 nav > navTitle + navList）。
        + "      function tagNav(){\n"
        + "        var nl=document.querySelector('[class*=\"_navList\"]');\n"
        + "        if(nl&&nl.parentElement&&!nl.parentElement.classList.contains('dsh-s-nav')){\n"
        + "          nl.parentElement.classList.add('dsh-s-nav');\n"
        + "        }\n"
        + "      }\n"
        + "      function sync(){\n"
        + "        tagNav();\n"
        + "        var ov=overlay();\n"
        + "        if(!ov){b.classList.remove('dsh-s-l2');b.classList.remove('dsh-s-l3');syncBack();return;}\n"
        + "        if(!b.classList.contains('dsh-s-l2')&&!b.classList.contains('dsh-s-l3')){setL2();}\n"
        + "        syncBack();\n"
        + "      }\n"
        + "      function syncBack(){\n"
        + "        var hdr=document.querySelector('[class*=\"_header\"]');\n"
        + "        if(!hdr){return;}\n"
        + "        if(b.classList.contains('dsh-s-l3')){\n"
        + "          if(!hdr.contains(back)){hdr.insertBefore(back,hdr.firstChild);}\n"
        + "        }else if(hdr.contains(back)){hdr.removeChild(back);}\n"
        + "      }\n"
        // 唯一的事件入口（capture阶段，先于 dsh 自己的处理）
        + "      document.addEventListener('click',function(e){\n"
        + "        var t=e.target;\n"
        + "        if(!t||!t.closest){sync();return;}\n"
        // 点右上角关闭 / 点遮罩 -> 让dsh 正常关闭，只清状态类（**绝不拦截**）
        + "        var closer=t.closest('[class*=\"_close\"],button[aria-label*=\"关闭\"],button[aria-label*=\"Close\"]');\n"
        + "        var ov=overlay();\n"
        + "        var onMask=ov&&(t===ov||(t.className&&String(t.className).indexOf('_mask')>=0));\n"
        + "        if(closer||onMask){b.classList.remove('dsh-s-l2');b.classList.remove('dsh-s-l3');syncBack();return;}\n"
        // 点导航项 -> 进三级页（不preventDefault，让 dsh 自己切内容）
        + "        var cell=t.closest('[class*=\"_navCell\"]');\n"
        + "        if(cell&&b.classList.contains('dsh-s-l2')){setL3();syncBack();}\n"
        + "        else{sync();}\n"
        + "      },true);\n"
        // Esc = 返回上一级
        + "      document.addEventListener('keydown',function(e){\n"
        + "        if(e.key==='Escape'&&b.classList.contains('dsh-s-l3')){\n"
        + "          e.preventDefault();e.stopPropagation();setL2();syncBack();\n"
        + "        }\n"
        + "      },true);\n"
        // 面板是动态挂载/卸载的，DOM 变化时同步
        // 去抖：sync() 自身会改 DOM（插/拔返回按钮），直接observe 会自激循环
        + "      var pend=null;\n"
        + "      function sched(){\n"
        + "        if(pend){return;}\n"
        + "        pend=setTimeout(function(){pend=null;sync();},60);\n"
        + "      }\n"
        + "      if(window.MutationObserver){new MutationObserver(sched).observe(b,{childList:true,subtree:true});}\n"
        + "      sync();\n"
        + "    })();\n"
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
        // dsh 会把 narrowExpanded 持久化。r16 那版点过原生折叠按钮，
        // 于是侧栏被持久化成「rail 形态」——抽屉里就只剩一列图标。
        // 这里在窄屏下把它恢复成展开态（走 dsh 自己的 toggle，状态会被它自己持久化）。
        // 按钮虽然被 CSS 隐藏，但 .click() 依然有效。
        + "    var tries=0;\n"
        + "    var poll=setInterval(function(){\n"
        + "      if(!mq.matches){clearInterval(poll);return;}\n"
        + "      expandSidebarOnce();\n"
        + "      if(++tries>20){clearInterval(poll);}\n"
        + "    },300);\n"
        + "    if(mq.addEventListener){mq.addEventListener('change',onMq);}\n"
        + "    else if(mq.addListener){mq.addListener(onMq);}\n"
        + "  }\n"
        // 只在 dsh 认为侧栏「已折叠」时才点一次展开，避免把它点反
        + "  function expandSidebarOnce(){\n"
        + "    var f=document.querySelector('div[class*=\"frame\"]');\n"
        + "    if(!f||!f.hasAttribute('data-sidebar-collapsed')){return;}\n"
        + "    var bs=document.querySelectorAll('div[class*=\"sidebarCol\"] button[aria-label]');\n"
        + "    for(var i=0;i<bs.length;i++){\n"
        + "      var al=bs[i].getAttribute('aria-label')||'';\n"
        + "      if(/^(收起侧边栏|Collapse sidebar|打开侧边栏|Open sidebar)$/.test(al)){\n"
        + "        bs[i].click();\n"
        + "        return;\n"
        + "      }\n"
        + "    }\n"
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