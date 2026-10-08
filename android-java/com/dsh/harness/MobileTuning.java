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
        // 汉堡做成**悬浮毛玻璃圆钮**：容器背景透明、高度贴合按钮，
        // 这样它只占左上角一小块，不再在内容上方留一整行空白
        // （用户反馈"顶部汉堡不要单独占据一行"）。
        // ⚠️ 容器必须 display:flex，否则 justify-content 无效（block 布局下不生效）。
        + "#dsh-mtop{position:fixed;top:0;left:0;right:0;z-index:61;"
        + "display:none;align-items:flex-start;justify-content:flex-start;gap:8px;"
        + "padding:6px 6px 0 6px;box-sizing:border-box;"
        + "background:transparent;pointer-events:none;}\n"
        + "#dsh-mtop>*{pointer-events:auto;}\n"
        // 抽屉打开时汉堡**移到右上角**（用户要求：不单独占一行，但始终可见可点）。
        // 只改 justify-content —— 关闭态在左上、打开态在右上，位置切换零延迟。
        + "  body.dsh-drawer-open #dsh-mtop{justify-content:flex-end !important;}\n"
        // 设计稿「首页布局规格」：工具条按钮 42×42pt；标题用 ink-900
        + "#dsh-mtop .dsh-mtitle{font-size:16px;font-weight:700;"
        + "color:var(--ds-ink-900);white-space:nowrap;"
        + "overflow:hidden;text-overflow:ellipsis;letter-spacing:0;}\n"
        + "#dsh-mbtn{width:42px;height:42px;border:0;border-radius:12px;"
        + "background:rgba(247,249,252,.86);backdrop-filter:blur(10px);"
        + "-webkit-backdrop-filter:blur(10px);box-shadow:0 1px 3px rgba(13,27,46,.10);"
        + "color:var(--ds-ink-900);display:flex;align-items:center;justify-content:center;padding:0;"
        + "-webkit-tap-highlight-color:transparent;cursor:pointer;}\n"
        // 按压反馈用 ink-100（设计稿浅色体系下的hover/active 面）
        + "#dsh-mbtn:active{background:var(--ds-ink-200,#d8e1ea);}\n"
        + "#dsh-scrim{position:fixed;inset:0;z-index:60;background:rgba(13,27,46,.42);"
        + "opacity:0;visibility:hidden;transition:opacity .2s ease,visibility .2s;}\n"
        + "body.dsh-drawer-open #dsh-scrim{opacity:1;visibility:visible;}\n"
        + "@media (max-width:1023px){\n"
        // 汉堡做成**悬浮圆钮**（不再整行占位、下面不需要留白）——
        // 用户反馈"顶部汉堡不要单独占据一行"。
        + "  #dsh-mtop{display:flex;align-items:center;}\n"
        // 主区独占第一列；右栏列宽交给内容（关着时为 0），避免 auto 隐式列出怪。
        // padding-top 收到 0：顶栏已是 position:fixed 悬浮，不需要再给主区让位。
        + "  div[class*=\"frame\"]{grid-template-columns:minmax(0,1fr) auto !important;"
        + "padding-top:0 !important;box-sizing:border-box !important;}\n"
        // 侧栏（设置面板真实宿主）脱离网格流 -> 抽屉。
        // ⚠️ 宽度必须**精确等于视口宽**：之前用 calc(100vw - 20px) 想留出"抽屉感"，
        //   结果右侧永远有一条 20px 灰条（用户反馈"没有铺满整个手机屏幕"）。
        //   现在用 left:0 + right:0 + width:100% 三重保险，并把 top 也交给 topbar 高度变量。
        + "  div[class*=\"sidebarCol\"]{position:absolute !important;"
        + "top:0 !important;bottom:0 !important;left:0 !important;"
        + "right:0 !important;width:100% !important;max-width:none !important;"
        + "margin:0 !important;padding:0 !important;box-sizing:border-box !important;"
        + "z-index:62 !important;"
        + "transform:translate3d(-102%,0,0);"
        + "transition:transform .22s cubic-bezier(.4,0,.2,1);"
        + "box-shadow:0 8px 40px rgba(0,0,0,.55);will-change:transform;}\n"
        // ⚠️ 面板必须**能滚动**：dsh 的设置面板内容可能很长，
        //   但它的滚动容器被 flex 布局限死，导致"页面无法往下滚动"（用户反馈）。
        + "  div[class*=\"sidebarCol\"]{overflow-y:auto !important;"
        + "-webkit-overflow-scrolling:touch !important;overscroll-behavior:contain !important;}\n"
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
        // ⚠️ 宽度必须**同时**给 left/right 拉伸和 width —— 只给 width:100vw 时，
        //   若 dsh 的 body 有纵向滚动条或边框，实际宽度会差几个 px，右侧留白
        //   （用户反馈"宽度为手机屏幕宽度"）。这里 flex 拉伸 + 100% 双保险。
        + "  [class*=\"_overlay\"]{padding:0 !important;margin:0 !important;"
        + "align-items:stretch !important;justify-content:stretch !important;"
        + "left:0 !important;right:0 !important;top:0 !important;bottom:0 !important;"
        + "width:100% !important;height:100% !important;max-width:none !important;}\n"
        + "  [class*=\"_panel\"]{width:100% !important;max-width:none !important;"
        + "align-self:stretch !important;flex:1 1 auto !important;"
        + "height:100% !important;border-radius:0 !important;flex-direction:column !important;}\n"
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
        // ===== 返回上一级：复用 dsh 自带的 ✘（_close），移到 header 左侧 =====
        // 用户反馈"自建返回箭头和 ✘ 重复了" —— 所以**删掉自建按钮**，
        // 直接把原生 ✘ 用 flex `order` 调到最左（不脱离 React DOM，只改视觉顺序）。
        // 二级页：✘ 保持原位（右侧，语义=关闭面板）。
        // 三级页：✘ 移到左侧第一项，语义=返回上一级（由 JS 拦截点击）。
        + "  body.dsh-s-l3 [class*=\"_header\"]{padding-left:14px !important;}\n"
        + "  body.dsh-s-l3 [class*=\"_close\"]{order:-1 !important;margin-right:auto !important;}\n"
        // 三级页的 header 标题独占剩余空间（✘ 在左，标题居中偏右）
        + "  body.dsh-s-l3 [class*=\"_headerTitle\"]{flex:1 1 auto !important;"
        + "padding-left:12px !important;}\n"
        // 三级页里 ✘ 换成 chevron-left 的视觉（用 CSS 旋转90° 的十字→箭头不现实，
        // 改为放大点击区+品牌色，和设计稿的圆形返回键观感一致）
        + "  body.dsh-s-l3 [class*=\"_close\"]{width:34px !important;height:34px !important;"
        + "border-radius:999px !important;background:var(--ds-ink-100,#eef2f6) !important;"
        + "color:var(--ds-ink-900,#0d1b2e) !important;transition:background 150ms !important;}\n"
        + "  body.dsh-s-l3 [class*=\"_close\"]:active{background:var(--ds-ink-200,#d8e1ea) !important;}\n"
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
        // 内容列允许滚动：dsh 的 _content 是 flex:1，若不给 overflow 会把
        // 长内容截断且无法滚动（用户反馈"页面无法往下滚动"）。
        + "  [class*=\"_content\"]{overflow-y:auto !important;"
        + "-webkit-overflow-scrolling:touch !important;}\n"
        + "  [class*=\"_options\"]{overflow-y:auto !important;"
        + "-webkit-overflow-scrolling:touch !important;}\n"
        // ===== 插件市场入口（注入卡片，挂在设置二级页顶部）=====
        + "  #dsh-market-card{display:none !important;margin:10px 26px 4px !important;"
        + "padding:14px 16px !important;border:1px solid var(--ds-ink-200,#d8e1ea) !important;"
        + "border-radius:16px !important;background:var(--ds-ink-50,#f7f9fc) !important;}\n"
        + "  body.dsh-s-l2 #dsh-market-card{display:block !important;}\n"
        + "  #dsh-market-card .dsh-mk-title{font-size:15.5px !important;font-weight:600 !important;"
        + "color:var(--ds-ink-900,#0d1b2e) !important;}\n"
        + "  #dsh-market-card .dsh-mk-desc{font-size:12.5px !important;"
        + "color:var(--ds-ink-500,#5a6d84) !important;margin-top:4px !important;}\n"
        + "  #dsh-market-btn{margin-top:10px !important;height:38px !important;padding:0 18px !important;"
        + "border:0 !important;border-radius:999px !important;background:var(--ds-brand-500,#2b8ae8) !important;"
        + "color:#fff !important;font-size:14px !important;font-weight:600 !important;"
        + "cursor:pointer !important;box-shadow:0 4px 12px rgba(43,138,232,.24) !important;}\n"
        + "  #dsh-market-btn[disabled]{background:var(--ds-ink-200,#d8e1ea) !important;"
        + "color:var(--ds-ink-500,#5a6d84) !important;box-shadow:none !important;}\n"
        // pnpm 行：市场"装插件"功能依赖它，缺失时市场会报"找不到 npm/corepack"
        + "  #dsh-pnpm-row{margin-top:10px !important;padding-top:10px !important;"
        + "border-top:1px solid var(--ds-ink-200,#d8e1ea) !important;}\n"
        + "  #dsh-pnpm-row .dsh-mk-desc{color:var(--ds-ink-500,#5a6d84) !important;}\n"
        + "  #dsh-pnpm-btn{margin-top:8px !important;height:34px !important;padding:0 14px !important;"
        + "border:1px solid var(--ds-ink-200,#d8e1ea) !important;border-radius:999px !important;"
        + "background:#fff !important;color:var(--ds-ink-900,#0d1b2e) !important;"
        + "font-size:13px !important;font-weight:600 !important;cursor:pointer !important;}\n"
        + "  #dsh-pnpm-btn[disabled]{background:var(--ds-ink-100,#eef2f6) !important;"
        + "color:var(--ds-ink-500,#5a6d84) !important;}\n"
        // 手机上没有文件管理器，dsh 自带的「无法打开配置文件」红字只会让人困惑 -> 隐藏
        + "  [class*=\"_header\"] [class*=\"error\"],"
        + "[class*=\"_header\"] [class*=\"Error\"],"
        + "[class*=\"_header\"] span[style*=\"error\"]{display:none !important;}\n"
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
        // ===== 插件市场入口卡片 =====
        // 插在导航容器之后（设置二级页顶部）。**挂在 body 上**，避免被 React 清掉（r30 教训）。
        + "      function mkMarketCard(){\n"
        + "        if(document.getElementById('dsh-market-card')){return;}\n"
        + "        var nav=document.querySelector('.dsh-s-nav');\n"
        + "        if(!nav||!nav.parentNode){return;}\n"
        + "        var c=document.createElement('div');c.id='dsh-market-card';\n"
        + "        c.innerHTML='<div class=\"dsh-mk-title\">插件市场</div>'\n"
        + "          +'<div class=\"dsh-mk-desc\">浏览并一键安装社区插件（dshmarket）</div>'\n"
        + "          +'<button id=\"dsh-market-btn\" type=\"button\">检测中…</button>'\n"
        + "          +'<div id=\"dsh-pnpm-row\"><div class=\"dsh-mk-desc\">'\n"
        + "          +'安装插件需要 pnpm（本机内置 Node 运行，无需额外环境）</div>'\n"
        + "          +'<button id=\"dsh-pnpm-btn\" type=\"button\">检测中…</button></div>';\n"
        + "        c.querySelector('#dsh-market-btn').addEventListener('click',onMarket);\n"
        + "        c.querySelector('#dsh-pnpm-btn').addEventListener('click',onPnpm);\n"
        + "        nav.parentNode.insertBefore(c, nav);\n"
        + "        refreshMarket();\n"
        + "      }\n"
        + "      function refreshMarket(){\n"
        + "        var btn=document.getElementById('dsh-market-btn');\n"
        + "        if(!btn||!window.dshNative){return;}\n"
        + "        var v='';\n"
        + "        try{ v=window.dshNative.marketInstalled(); }catch(e){}\n"
        // 已安装 -> 按钮变成"打开市场"（**可点**）。
        // dshmarket 是**独立路由页** /dsh-market（lib/routes.js 实测），
        // 并不注册进设置面板，所以装完必须自己给出入口，否则"装了却用不了"。
        + "        if(v){ btn.textContent='打开市场'; btn.removeAttribute('disabled');\n"
        + "          btn.setAttribute('data-open','1'); }\n"
        + "        else { btn.textContent='安装'; btn.removeAttribute('disabled');\n"
        + "          btn.removeAttribute('data-open'); }\n"
        + "        refreshPnpm();\n"
        + "      }\n"
        + "      var pnpmBusy=false;\n"
        + "      function refreshPnpm(){\n"
        + "        var pb=document.getElementById('dsh-pnpm-btn');\n"
        + "        if(!pb||!window.dshNative){return;}\n"
        + "        var ready='';\n"
        + "        try{ ready=window.dshNative.pnpmReady(); }catch(e){}\n"
        + "        if(ready){ pb.textContent='pnpm 已就绪'; pb.setAttribute('disabled','disabled'); }\n"
        + "        else { pb.textContent='安装 pnpm'; pb.removeAttribute('disabled'); }\n"
        + "      }\n"
        + "      function onPnpm(e){\n"
        + "        e.preventDefault();e.stopPropagation();\n"
        + "        if(pnpmBusy){return;}\n"
        + "        pnpmBusy=true;\n"
        + "        var pb=document.getElementById('dsh-pnpm-btn');\n"
        + "        pb.textContent='安装中…';pb.setAttribute('disabled','disabled');\n"
        + "        window.__dshPnpmDone=function(id,res){\n"
        + "          pnpmBusy=false;\n"
        + "          if(res&&res.ok){ pb.textContent='pnpm 已就绪'; }\n"
        + "          else { pb.textContent='重试'; pb.removeAttribute('disabled');\n"
        + "            alert('pnpm 安装失败：'+((res&&res.message)||'未知错误')); }\n"
        + "        };\n"
        + "        try{ window.dshNative.installPnpm('pnpm'); }\n"
        + "        catch(err){ pnpmBusy=false; pb.textContent='重试'; pb.removeAttribute('disabled'); }\n"
        + "      }\n"
        + "      var installing=false;\n"
        + "      function onMarket(e){\n"
        + "        e.preventDefault();e.stopPropagation();\n"
        + "        if(installing){return;}\n"
        // 已安装 -> 直接打开市场页面（同源相对路径，dsh 自己的 webserver 提供）
        + "        if(btn.getAttribute('data-open')==='1'){\n"
        + "          location.href='/dsh-market';\n"
        + "          return;\n"
        + "        }\n"
        + "        installing=true;\n"
        + "        var btn=document.getElementById('dsh-market-btn');\n"
        + "        btn.textContent='安装中…';btn.setAttribute('disabled','disabled');\n"
        + "        window.__dshMarketDone=function(id,res){\n"
        + "          installing=false;\n"
        + "          if(res&&res.ok){ btn.textContent='已安装 v'+res.version; }\n"
        + "          else { btn.textContent='重试'; btn.removeAttribute('disabled');\n"
        + "            alert('安装失败：'+((res&&res.message)||'未知错误')); }\n"
        + "        };\n"
        + "        try{ window.dshNative.installMarket('mkt'); }\n"
        + "        catch(err){ installing=false; btn.textContent='重试'; btn.removeAttribute('disabled'); }\n"
        + "      }\n"
        + "      function sync(){\n"
        + "        var ov=overlay();\n"
        + "        if(!ov){\n"
        // 面板已关闭：清状态类**并把注入卡片摘掉**。
        // 否则卡片会变成孤儿节点留在 body 上，而 dsh 的 _content 又因状态类残留
        // 被display:none 永久隐藏 -> 整页只剩卡片那块空白（真机反馈"设置返回时
        // 会出现图三的情况"：一大片空白 + 只有插件市场卡片）。
        + "          b.classList.remove('dsh-s-l2');b.classList.remove('dsh-s-l3');\n"
        + "          var mc=document.getElementById('dsh-market-card');\n"
        + "          if(mc&&mc.parentNode){mc.parentNode.removeChild(mc);}\n"
        + "          syncBack();\n"
        + "          return;\n"
        + "        }\n"
        + "        tagNav();\n"
        + "        mkMarketCard();\n"
        + "        if(!b.classList.contains('dsh-s-l2')&&!b.classList.contains('dsh-s-l3')){setL2();}\n"
        + "        syncBack();\n"
        + "      }\n"
        // 返回按钮不再自建：改用dsh 自带的 `_close`（✘），把它移到左侧。
        // 理由：两个按钮功能重复（用户反馈"和 ✘ 号重复了"），且自建那个要额外
        // 维护定位/层级。复用原生按钮只改 CSS 位置，行为也保持原生。
        + "      function syncBack(){\n"
        // 三级页点 ✘ 的语义由下面的 click 处理器切换（返回上一级 / 关闭面板），这里无需做事
        + "      }\n"
        // 唯一的事件入口（capture阶段，先于 dsh 自己的处理）
        + "      document.addEventListener('click',function(e){\n"
        + "        var t=e.target;\n"
        + "        if(!t||!t.closest){sync();return;}\n"
        // 点 ✘（_close）：**分级语义**——
        //   二级页 -> 关闭面板（放行给 dsh，绝不拦截，否则 overlay 永不卸载、
        //             _mask 兄弟节点会一直盖在全屏，r26 踩过这个坑）
        //   三级页 -> 返回上一级（拦截，阻止关闭）
        + "        var closer=t.closest('[class*=\"_close\"],button[aria-label*=\"关闭\"],button[aria-label*=\"Close\"]');\n"
        + "        if(closer){\n"
        + "          if(b.classList.contains('dsh-s-l3')){\n"
        + "            e.preventDefault();e.stopPropagation();setL2();syncBack();\n"
        + "          }else{\n"
        + "            b.classList.remove('dsh-s-l2');b.classList.remove('dsh-s-l3');\n"
        + "          }\n"
        + "          return;\n"
        + "        }\n"
        // 点遮罩空白处关闭 -> 清状态类，放行给 dsh
        + "        var ov=overlay();\n"
        + "        var onMask=ov&&(t===ov||(t.className&&String(t.className).indexOf('_mask')>=0));\n"
        + "        if(onMask){b.classList.remove('dsh-s-l2');b.classList.remove('dsh-s-l3');syncBack();return;}\n"
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
        //顶栏不显示标题文字（用户要求去掉），只留汉堡按钮
        + "      +'';\n"
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