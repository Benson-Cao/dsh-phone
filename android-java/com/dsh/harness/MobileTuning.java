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
 *
 * <h2>⚠️ 铁律：新增 CSS 必须带作用域前缀（r38 血的教训）</h2>
 *
 * <b>本文件里的 CSS 一律裸写在 {@code @media(max-width:1023px)} 里，会命中
 * 「当前页面里的任何元素」，而不是只命中设置面板。</b>
 * 而 dsh 的不同页面<b>类名同源</b>：设置面板与 dsh-market（{@code /dsh-market}
 * 独立路由页）都用 {@code ..._card} / {@code ..._cards} / {@code ..._group} /
 * {@code ..._options} / {@code ..._content} / {@code ..._header}。
 *
 * <p>r38 的真机后果：市场页被为设置面板写的规则污染 ——
 * 标题竖排成一列、插件名一字一行、网格被 {@code minmax(0,1fr)} 压成单列。
 *
 * <p><b>所以新增任何一条规则前，先问三个问题：</b>
 * <ol>
 *   <li>这条规则<b>只应该</b>作用在设置面板吗？→ 是则必须加
 *       {@code [class*="_panel"] } 前缀；</li>
 *   <li>目标元素的<b>真实宿主</b>是谁？（r34踩过：一直在改 {@code _panel}，
 *       但设置面板真正挂在 {@code sidebarCol} 下）；</li>
 *   <li>这个后缀<b>是否作为别的元素的前缀</b>？（r29 踩过：{@code [class*="_nav"]}
 *       同时命中 {@code _navIcon}/{@code _navLabel}，把图标和文字也变成 block）</li>
 * </ol>
 *
 * <p>若目标元素在 {@link #MARKET_CSS} 覆盖的页面里，则走市场页那一套，
 * <b>不要</b>往 {@link #CSS} 里加。
 *
 * <p><b>铁律二：「元素不见了」先查 z-index，不是查显示逻辑。</b>
 * r37踩过：汉堡 {@code z-index:61} 低于侧栏 {@code 62} → 被盖住，
 * 却花时间去改 display/位置。当前层级台账见下方常量注释。
 *
 * <p><b>铁律三（r40）：往别人的 flex 容器里塞节点 = 把宽度/高度交给对方决定。</b>
 * 真机实测（截图逐像素量过）：{@code #dsh-market-card} 直接挂在
 * {@code [class*="_panel"]} 下时，{@code _panel} 在该机型上仍是 dsh 的
 * <b>行方向</b> flex，于是卡片
 * <ul>
 *   <li>宽度取 {@code max-content}（≈420px）→ 撑出 360px 的屏幕，文字不换行被裁切；</li>
 *   <li>高度被 {@code align-self:stretch} 拉成整屏（实测 727px），大片空白；</li>
 *   <li>兄弟 {@code _nav} 被挤到 x&gt;420 → <b>整个设置导航列表看不见</b>。</li>
 * </ul>
 * <b>所以：注入节点必须挂到自己 100% 可控的容器里</b>（这里是 JS 打标记的
 * {@code .dsh-s-nav}），并显式写死 {@code flex:0 0 auto} /
 * {@code align-self:stretch} / {@code box-sizing:border-box} / {@code max-width:100%}。
 * 再加一层 {@code guardCard()} 用实测几何自愈 —— <b>不要指望一条 {@code !important}
 * 的 CSS 一定命中</b>。
 *
 * <p><b>铁律四（r40）：非法 CSS 值会被静默丢弃，dsh 自己的规则随之复活。</b>
 * 原 {@code _overlay} 写的是 {@code justify-content:stretch !important} ——
 * {@code stretch} 对 {@code justify-content} <b>不是合法值</b>，整条声明被丢弃，
 * 于是 dsh 的 {@code justify-content:center} 生效：面板一旦宽于视口就被
 * "居中"到屏幕外。写覆盖前先确认值合法。
 *
 * <p><b>铁律五（r40）：{@code var} 提升会制造"看起来对、实际必崩"的死代码。</b>
 * r39 的 {@code onMarket()} 里 {@code btn.getAttribute(...)} 写在
 * {@code var btn=...} 之前 —— 不报语法错、不报编译错，但每次点击都
 * {@code TypeError}，"打开市场"永远点不动。<b>变量声明一律提到函数最前面。</b>
 */
public final class MobileTuning {

    private MobileTuning() {}

    /** 顶栏高度，同时用作侧栏抽屉的 top 偏移。 */
    private static final int TOPBAR_H = 48;

    /*
     * z-index 层级台账（改任何fixed/absolute 元素前先对一眼）：
     *   60  #dsh-scrim      遮罩
     *   61  #dsh-mtop        汉堡（抽屉**关闭**态，在左上）
     *   62  sidebarCol       侧栏 / 抽屉本体
     *   65  #dsh-mtop        汉堡（抽屉**打开**态，在右上）—— 必须 > 62
     *  1000 _overlay        设置面板浮层
     *  （r29 曾用过 1001 = 自建返回按钮，已删除，勿复用此段位）
     *
     * ===== 铁律（每条都是真机事故换来的，改代码前先读）=====
     * 一、注入节点必须挂到**自己打标的容器**上，别塞进别人的 flex 容器。
     * 二、CSS 值必须合法：非法值被静默丢弃，dsh 自己的规则会复活。
     * 三、`var` 提升会造"语法合法、运行时必崩"的死代码。
     * 四、**dsh 的类名后缀是跨模块共享的**，不要裸匹配：
     *      `_panel`  在 chat / conversation / cordis / settings-plugins / sidebar-right 都有；
     *      `_overlay` 有 3 个（设置浮层 VOzbGW、布局浮层 pI_x6G_overlayLayer、
     *                 输入框锚点 uV2eYG_overlayAnchor）；
     *      `_header` / `_close` / `_options` / `_cards` 同理。
     *      设置浮层的**唯一指纹**是「同一个 `_overlay` 里同时有 `_panel` 与 `_navList`」，
     *      JS 用它判定并打上 `.dsh-s-ov`，CSS 只认这个自打的类。
     * 七、**跳转插件页面不要用 `location.href='/xxx'`**。
     *      dsh web 入口地址带 `?token=`，相对路径整页跳会把 query 丢掉；而插件页是
     *      **插件自己注册的客户端路由**，服务端不保证为该路径吐 SPA 外壳。
     *      正确做法：点该插件在 UI 上注册的那个入口（`.click()`），交给 dsh 的前端路由；
     *      实在要整页跳就 `location.assign(path + location.search)` 把 token 带上。
     * 六、**别写 `容器 * {…}` 这种全通配的"重置"规则**。
     *      `div[class*="sidebarCol"] *{min-width:0}` 的特异性 (0,1,1) 高于 dsh 自己
     *      的组件规则 (0,1,0)，会把 `.addButton{min-width:180px}` 之类的设计参数
     *      一起干掉，按钮里的中文随即按字换行竖排（真机模型页/插件市场页）。
     *      要放开收缩就点名文本元素，别碰控件。
     * 五、**任何"隐藏 UI"的规则都必须由指纹判定驱动**。
     *      r40 事故：`overlay()` 裸查 `_overlay` -> 首页命中布局浮层 -> body 被加上
     *      dsh-ov-open -> 汉堡按钮 display:none -> 「无法打开设置」（应用基本不可用）。
     *      启发式判定 + 立即生效的隐藏规则 = 一次误判全盘皆输。现在 dsh-ov-open
     *      只由 syncOvFlag() 写，并有 500ms 看门狗兜底重算。
     */

    private static final String CSS =
        /* ===== 设计稿《移动端 UI 设计系统》token（唯一依据：资料库设计稿 :root 变量）=====
         * 之前顶栏用的是 dsh 深色主题（#101215 底 + 白字），与设计稿的浅色体系冲突。
         * 现在统一到设计稿配色，并顺带把品牌 token 注入为 CSS 变量，
         * 供下方设置页/输入区等规则引用。 */
        // P0 品牌蓝压暗：#2b8ae8 作文字/承载白字只有 3.56:1（需 4.5）。
        //   保持色相 H210 / S80，只降明度 L54%→L40% -> 白字 5.80、白底 5.80、浅底 5.35。
        // ===== 设计token =====
        // P3-3：**表面/ 文字色一律别名到 dsh 的语义变量**，主题切换自动跟随。
        //   dsh 自己支持深色（跟随系统 + 可手动锁定），但**不打 DOM 主题标记**，
        //   所以不能用 @media 硬写一套 —— 那样会和用户手动锁定的主题打架。
        //   别名后无论 dsh 走 system 还是 manual，我们永远和它一致。
        //   fallback 是 r45 审查过的浅色值，变量缺失也不会崩。
        //⚠️ 必须定义在 **body** 上，不能用 :root ——
        //   dsh 把全部 --dsw-* 变量定义在 body 上（源码：body{...} / body[data-ds-dark-theme]{...}），
        //   :root 上var() 取不到 body 上定义的值，会静默退回 fallback，深色下等于没生效。
        "body{"
        + "--ds-ink-900:var(--dsw-alias-label-primary,#0d1b2e);"
        + "--ds-ink-700:var(--dsw-alias-label-secondary,#1e3450);"
        + "--ds-ink-500:var(--dsw-alias-label-tertiary,#5a6d84);"
        + "--ds-ink-400:var(--dsw-alias-label-caption,#8296ab);"
        + "--ds-ink-200:var(--dsw-alias-border-l3,#d8e1ea);"
        + "--ds-ink-100:var(--dsw-alias-interactive-bg-hover,#eef2f6);"
        + "--ds-ink-50:var(--dsw-alias-bg-layer-1,#f7f9fc);"
        + "--ds-surface:var(--dsw-alias-bg-layer-2,#fff);"
        // 品牌色是**我们的装饰色**，dsh 不管；深色下的覆写见 .dsh-dark 块（JS 打标）。
        + "--ds-brand-500:#1466b8;--ds-brand-600:#0f5296;--ds-brand-50:#eef7fe;--ds-brand-100:#d6ecfc;"
        + "--ds-r-sm:12px;--ds-r-md:16px;--ds-r-lg:22px;--ds-r-full:999px;}\n"
        /* 注入的外壳：默认 display:none，只有窄屏媒体查询里才启用 */
        // 汉堡做成**悬浮毛玻璃圆钮**：容器背景透明、高度贴合按钮，
        // 这样它只占左上角一小块，不再在内容上方留一整行空白
        // （用户反馈"顶部汉堡不要单独占据一行"）。
        // ⚠️ 容器必须 display:flex，否则 justify-content 无效（block 布局下不生效）。
        // ===== 深色模式：装饰色覆写 =====
        //由 JS 实测 dsh 渲染出的背景亮度后打 html.dsh-dark（见JS detectDark()）。
        // 不用 @media (prefers-color-scheme)：dsh 允许用户手动锁定主题，
        // 媒体查询只认系统，会和用户设置打架。
        //深色覆写直接跟随 dsh 的真实标记 **body[data-ds-dark-theme]**（源码取证）：
        //   零延迟、零轮询。比「实测背景亮度 + 500ms 轮询」可靠得多。
        //   品牌蓝要提亮 —— #1466b8 在深底上只有 2.3:1。
        + "  body[data-ds-dark-theme],html.dsh-dark{--ds-brand-500:#5aa9f5;--ds-brand-600:#8cc2f9;"
        + "    --ds-brand-50:#143253;--ds-brand-100:#1d4a7a;}\n"
        + "#dsh-mtop{position:fixed;top:0;left:0;right:0;z-index:61;"
        + "display:none;align-items:flex-start;justify-content:flex-start;gap:8px;"
        + "padding:8px 8px 0 6px;box-sizing:border-box;"
        + "background:transparent;pointer-events:none;}\n"
        + "#dsh-mtop>*{pointer-events:auto;}\n"
        // 抽屉打开时汉堡**移到右上角**（用户要求：不单独占一行，但始终可见可点）。
        // 只改 justify-content —— 关闭态在左上、打开态在右上，位置切换零延迟。
        // ⚠️ z-index 必须**高于 sidebarCol(62)**：抽屉打开时侧栏是 62，
        //   汉堡原本 61 -> 被侧栏**盖住看不见**（真机反馈"右上角没有汉堡按钮"，
        //   截图里侧栏已打开、右上角却空着）。这里提到 65。
        + "  body.dsh-drawer-open #dsh-mtop{justify-content:flex-end !important;"
        + "z-index:65 !important;}\n"
        // 设计稿「首页布局规格」：工具条按钮 42×42pt
        // 注：`.dsh-mtitle` 规则已在 r33 删除（顶栏不再显示标题文字），
        //     此处不再保留空样式，避免死代码误导后续维护。
        // P2：42px 低于 44px 触控下限 -> 44px。
        + "#dsh-mbtn{width:44px;height:44px;border:0;border-radius:13px;"
        + "background:var(--ds-surface,rgba(247,249,252,.86));backdrop-filter:blur(10px);"
        + "-webkit-backdrop-filter:blur(10px);box-shadow:0 1px 3px rgba(13,27,46,.10);"
        + "color:var(--ds-ink-900);display:flex;align-items:center;justify-content:center;padding:0;"
        + "-webkit-tap-highlight-color:transparent;cursor:pointer;}\n"
        // 按压反馈用 ink-100（设计稿浅色体系下的hover/active 面）
        + "#dsh-mbtn:active{background:var(--ds-ink-200,#d8e1ea);}\n"
        + "#dsh-scrim{position:fixed;inset:0;z-index:60;background:rgba(13,27,46,.42);"
        + "opacity:0;visibility:hidden;transition:opacity .2s ease,visibility .2s;}\n"
        + "body.dsh-drawer-open #dsh-scrim{opacity:1;visibility:visible;}\n"
        // 设置浮层打开时**显式隐藏**汉堡：浮层 z-index:1000、汉堡 61/65，
        // 本来就会被盖住 —— 但不同机型/版本的层叠上下文不完全一致，
        // 真机截图里出现过"汉堡浮在设置卡片右上角"的观感。显式隐藏，行为确定。
        + "body.dsh-ov-open #dsh-mtop,body.dsh-ov-open #dsh-scrim{display:none !important;}\n"
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
        + "  {transform:translate3d(0,0,0) !important;"
        + "display:block !important;visibility:visible !important;opacity:1 !important;"
        + "background:var(--dsw-specific-sidebar-fill,var(--dsw-alias-bg-base,#fff)) !important;}\n"
        + "  body.dsh-drawer-open div[class*=\"sidebarCol\"] [class*=\"_collapsed\"]"
        + "  {padding:6px 12px !important;}\n"
        + "  body.dsh-drawer-open div[class*=\"sidebarCol\"] [class*=\"_newSessionLabel\"],"
        + "  body.dsh-drawer-open div[class*=\"sidebarCol\"] [class*=\"_panelTitle\"]"
        + "  {max-width:none !important;}\n"
        + "  body.dsh-drawer-open div[class*=\"sidebarCol\"] [class*=\"_panelRow\"]"
        + "  {width:100% !important;height:auto !important;min-height:36px !important;"
        + "justify-content:flex-start !important;padding:0 8px !important;}\n"
        + "  body.dsh-drawer-open div[class*=\"sidebarCol\"] [class*=\"_newSession\"]:not([class*=\"Label\"])"
        + "  {width:100% !important;height:auto !important;min-height:40px !important;"
        + "gap:8px !important;padding:0 10px !important;justify-content:flex-start !important;}\n"
        // 挤成竖排的根因：flex/grid 子项默认 min-width:auto 会拒绝收缩，
        // 叠上中文的 word-break 规则就成了「一字一行」。允许收缩 + 允许折行即可。
        + "  div[class*=\"sidebarCol\"] p,div[class*=\"sidebarCol\"] span,"
        + "div[class*=\"sidebarCol\"] label,div[class*=\"sidebarCol\"] li,"
        + "div[class*=\"sidebarCol\"] h1,div[class*=\"sidebarCol\"] h2,"
        + "div[class*=\"sidebarCol\"] h3,div[class*=\"sidebarCol\"] div{min-width:0;}\n"
        + "  div[class*=\"sidebarCol\"] p,div[class*=\"sidebarCol\"] span,"
        + "div[class*=\"sidebarCol\"] label,div[class*=\"sidebarCol\"] li,"
        + "div[class*=\"sidebarCol\"] div{overflow-wrap:anywhere;}\n"
        + "  div[class*=\"sidebarCol\"] p,div[class*=\"sidebarCol\"] span,"
        + "div[class*=\"sidebarCol\"] label,div[class*=\"sidebarCol\"] li,"
        + "div[class*=\"sidebarCol\"] div{word-break:keep-all !important;}\n"
        // 窄屏隐藏 dsh 原生折叠按钮：入口统一到顶栏汉堡键
        + "  div[class*=\"sidebarCol\"] button[aria-label=\"收起侧边栏\"],"
        + "  div[class*=\"sidebarCol\"] button[aria-label=\"打开侧边栏\"],"
        + "  div[class*=\"sidebarCol\"] button[aria-label=\"Collapse sidebar\"],"
        + "  div[class*=\"sidebarCol\"] button[aria-label=\"Open sidebar\"]"
        + "  {display:none !important;}\n"
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
        // ⚠️ justify-content 原来写的是 stretch —— 那是**非法值**，浏览器直接丢弃，
        //   于是 dsh 自己的 center 生效。面板一旦宽于视口就会被"居中"到屏幕外。
        //   改 flex-start：面板从 x=0 开始，即使宽度覆盖失败也不会跑到屏幕外。
        + "  .dsh-s-ov{padding:0 !important;margin:0 !important;"
        + "align-items:stretch !important;justify-content:flex-start !important;"
        + "left:0 !important;right:0 !important;top:0 !important;bottom:0 !important;"
        + "width:100% !important;height:100% !important;max-width:100% !important;"
        + "overflow:hidden !important;}\n"
        // ⚠️ 真机实测：这条 flex-direction:column 在部分机型上**没生效**，
        //   面板仍是 dsh 的 row → 注入卡片成了 row 的 flex 子项：
        //     宽度取 max-content（≈420px，撑出 360px 屏幕）、高度被 stretch 拉到整屏
        //     （真机截图实测卡片高 727px、右侧越界、导航被挤到 x>420 看不见）。
        //   这里补齐 display / flex-wrap / align-items / min-* 五项，
        //   并额外用 JS 的 guardCard() 做兜底（不指望单条 CSS 一定命中）。
        + "  .dsh-s-ov [class*=\"_panel\"]{display:flex !important;width:100% !important;"
        + "max-width:100% !important;align-self:stretch !important;flex:1 1 auto !important;"
        + "min-width:0 !important;min-height:0 !important;"
        + "height:100% !important;border-radius:0 !important;"
        + "flex-direction:column !important;flex-wrap:nowrap !important;"
        + "align-items:stretch !important;overflow-x:hidden !important;"
        + "box-sizing:border-box !important;}\n"
        // ⚠️ 用 .dsh-s-nav 限定作用域，**不要再用 [class*="_nav"]**：
        //   `_nav` 是 `_navTitle`/`_navList`/`_navCell`/`_navIcon`/`_navLabel`
        //   的**共同前缀**。模糊匹配会把容器样式（width:100%、flex-direction:column）
        //   套到图标和文字上 -> 图标独占一行、文字另起一行，每项撑到~100px
        //   （真机反馈"布局太丑了"）。`.dsh-s-nav` 由 JS 打在导航容器上。
        // ⚠️ 这里是**唯一 100% 可控的宿主**：类名由我们自己的 JS 打上去，
        //   dsh 的任何规则都不可能覆盖。所以把卡片和列表都挂进来，
        //   几何(=width/flex-direction/padding)钉死在这里，不再依赖 _panel 的覆盖。
        + "  .dsh-s-nav{display:flex!important;width:100% !important;"
        + "max-width:100% !important;min-width:0!important;"
        + "flex:0 0 auto!important;flex-direction:column!important;"
        + "flex-wrap:nowrap!important;align-items:stretch!important;gap:0!important;"
        + "padding:0 16px 16px!important;box-sizing:border-box!important;"
        + "overflow-x:hidden!important;}\n"
        // 导航标题（"设置"）隐藏：面板 header 已有标题，避免重复
        + "  .dsh-s-nav [class*=\"_navTitle\"]{display:none!important;}\n"
        // 导航列表：竖排、满宽、零间隙（行分隔线由 navCell 的 ::before 画，
        // 比让每项自带 gap 更接近原生设置页的观感）
        + "  .dsh-s-nav [class*=\"_navList\"]{display:flex!important;"
        + "flex-direction:column!important;gap:0!important;"
        + "width:100%!important;min-width:0!important;}\n"
        // 列表项：54px 触控高度（≥48dp 无障碍下限）、row 布局、圆角、相对定位
        + "  .dsh-s-nav [class*=\"_navCell\"]{display:flex!important;width:100%!important;"
        + "height:auto!important;min-height:54px!important;padding:14px 12px!important;"
        + "gap:14px!important;flex-direction:row!important;align-items:center!important;"
        + "border-radius:14px!important;font-size:15.5px!important;"
        + "box-sizing:border-box!important;position:relative!important;"
        + "white-space:normal!important;text-align:left!important;"
        + "transition:background .15s cubic-bezier(.4,0,.2,1)!important;}\n"
        + "  .dsh-s-nav [class*=\"_navCell\"]:active{"
        + "background:var(--ds-ink-100,#eef2f6)!important;}\n"
        // 行分隔线：从图标右侧（48px）起，右边收 12px
        + "  .dsh-s-nav [class*=\"_navCell\"]+[class*=\"_navCell\"]::before{"
        // 颜色从 ink-100 提到 ink-200：ink-100(#eef2f6) 在白底上几乎看不见，
        // 逐页截图复核时发现导航项之间"没有分隔线"（真机②也是同样观感）。
        + "content:'';position:absolute;left:48px;right:12px;top:0;height:1px;"
        + "background:var(--ds-ink-200,#d8e1ea);}\n"
        + "  .dsh-s-nav [class*=\"_navIcon\"]{width:22px!important;height:22px!important;"
        + "flex:none!important;color:var(--ds-ink-500,#5a6d84)!important;}\n"
        + "  .dsh-s-nav [class*=\"_navLabel\"]{font-size:15.5px!important;"
        + "font-weight:600!important;color:var(--ds-ink-900,#0d1b2e)!important;"
        + "white-space:normal!important;overflow-wrap:normal!important;"
        + "word-break:normal!important;flex:1 1 auto!important;min-width:0!important;}\n"
        // 选中态：浅品牌底 + 左侧 3px 色条（比整块灰底更有"当前位置"的方向感）
        + "  .dsh-s-nav [class*=\"_active\"]{background:var(--ds-brand-50,#eef7fe)!important;"
        + "border-radius:14px!important;}\n"
        // 深色下品牌浅底变深，文字/图标要跟着换成浅品牌色才看得清
        + "  body[data-ds-dark-theme] .dsh-s-nav [class*=\"_active\"] [class*=\"_navLabel\"],"
        + "  body[data-ds-dark-theme] .dsh-s-nav [class*=\"_active\"] [class*=\"_navIcon\"],"
        + "  html.dsh-dark .dsh-s-nav [class*=\"_active\"] [class*=\"_navLabel\"],"
        + "  html.dsh-dark .dsh-s-nav [class*=\"_active\"] [class*=\"_navIcon\"]{"
        + "color:var(--ds-brand-500,#5aa9f5)!important;}\n"
        + "  .dsh-s-nav [class*=\"_active\"]::after{content:'';position:absolute;"
        + "left:0;top:14px;bottom:14px;width:3px;border-radius:0 3px 3px 0;"
        + "background:var(--ds-brand-500,#1466b8);}\n"
        + "  .dsh-s-nav [class*=\"_active\"] [class*=\"_navLabel\"],"
        + ".dsh-s-nav [class*=\"_active\"] [class*=\"_navIcon\"]{"
        + "color:var(--ds-brand-500,#1466b8)!important;}\n"
        // 内容：全宽单列
        + "  .dsh-s-ov [class*=\"_panel\"] [class*=\"_content\"]{width:100% !important;min-width:0 !important;"
        + "flex:1 1 auto !important;}\n"
        + "  .dsh-s-ov [class*=\"_panel\"] [class*=\"_options\"]{padding:14px 16px 28px !important;}\n"
        // 覆盖 r18 的 anywhere —— 强制正常换行，杜绝一字一行
        + "  .dsh-s-ov [class*=\"_panel\"] *{overflow-wrap:normal !important;word-break:normal !important;}\n"
        + "  .dsh-s-ov [class*=\"_panel\"] [class*=\"_options\"] *{max-width:100% !important;}\n"
        // ===== 设置页分级：二级列表 → 三级详情（对齐设计稿的两级页面）=====
        // 二级页：只显示导航列表（通用设置/ 模型 / 插件 / Agent 预设…），标题为「设置」
        // 三级页：点某个导航项后，内容全屏显示，顶部出现返回箭头
        //实现：body 上挂 dsh-s-l2 / dsh-s-l3 两个状态类，由 JS 切换。
        // ⚠️ 所有状态规则都必须**收窄到 _panel 内**：`_content`/`_nav` 是通用后缀，
        //    不限定作用域会误伤 dsh 主界面上同后缀的元素（r25 灰屏的放大器）。
        + "  body.dsh-s-l2 .dsh-s-ov [class*=\"_panel\"] [class*=\"_content\"]{display:none !important;}\n"
        + "  body.dsh-s-l3 .dsh-s-nav{display:none!important;}\n"
        // 三级页头部：显示返回箭头 + 标题
        // 头栏：固定最小高度 + 底部细分割线（L3 详情页的层次靠它撑起来）；
        // padding-top 叠 safe-area-inset-top，刘海/挖孔屏不压字（非全面屏机型为 0，无副作用）
        + "  .dsh-s-ov [class*=\"_panel\"] [class*=\"_header\"]{display:flex !important;"
        + "align-items:center !important;gap:10px !important;flex-wrap:wrap !important;"
        + "row-gap:10px !important;"
        + "box-sizing:border-box !important;height:auto !important;min-height:58px !important;"
        + "padding:calc(12px + env(safe-area-inset-top,0px)) 16px 10px !important;"
        + "border-bottom:1px solid var(--ds-ink-100,#eef2f6) !important;}\n"
        + "  .dsh-s-ov [class*=\"_panel\"] [class*=\"_header\"] button,"
        + "  .dsh-s-ov [class*=\"_panel\"] [class*=\"_header\"] a{flex:none !important;"
        + "white-space:nowrap !important;}\n"
        + "  .dsh-s-ov [class*=\"_panel\"] [class*=\"_header\"] [class*=\"_title\"],"
        + "  .dsh-s-ov [class*=\"_panel\"] [class*=\"_header\"] [class*=\"_name\"],"
        + "  .dsh-s-ov [class*=\"_panel\"] [class*=\"_header\"] span{white-space:nowrap !important;"
        + "word-break:keep-all !important;}\n"
        // ⚠️ r48：插件市场三级页（dshmarket 渲染）的头部元素类名**不受我们控制**，
        //   上面按 _title/_name/span 兜底漏掉了它用的 div -> 标题被压到最窄、
        //   「插件市场」竖成一列（同族问题在 ③ 修过一次，这里是另一个页面）。
        //   通用防御：header 的**直接子元素**一律 nowrap + 不参与收缩 ——
        //   放不下时整块 wrap 到下一行（header 已有 flex-wrap:wrap），而不是把中文压成竖排。
        + "  .dsh-s-ov [class*=\"_panel\"] [class*=\"_header\"] > *{white-space:nowrap !important;"
        + "word-break:keep-all !important;flex-shrink:0 !important;}\n"
        + "  .dsh-s-ov [class*=\"_panel\"] [class*=\"_header\"] *{word-break:keep-all !important;}\n"
        + "  .dsh-s-ov [class*=\"_panel\"] div[class*=\"_headerTitle\"]{font-size:19px !important;"
        + "font-weight:750 !important;letter-spacing:.2px !important;"
        + "color:var(--ds-ink-900,#0d1b2e) !important;flex:1 !important;min-width:0 !important;}\n"
        // 我们自建的返回按钮（仅三级页可见）
        // ===== 返回上一级：复用 dsh 自带的 ✘（_close），移到 header 左侧 =====
        // 用户反馈"自建返回箭头和 ✘ 重复了" —— 所以**删掉自建按钮**，
        // 直接把原生 ✘ 用 flex `order` 调到最左（不脱离 React DOM，只改视觉顺序）。
        // 二级页：✘ 保持原位（右侧，语义=关闭面板）。
        // 三级页：✘ 移到左侧第一项，语义=返回上一级（由 JS 拦截点击）。
        + "  body.dsh-s-l3 .dsh-s-ov [class*=\"_header\"]{padding-left:14px !important;}\n"
        + "  body.dsh-s-l3 .dsh-s-ov [class*=\"_close\"]{order:-1 !important;margin-right:auto !important;}\n"
        // 三级页的 header 标题独占剩余空间（✘ 在左，标题居中偏右）
        + "  body.dsh-s-l3 .dsh-s-ov [class*=\"_headerTitle\"]{flex:1 1 auto !important;"
        + "padding-left:12px !important;}\n"
        // 三级页里 ✘ 换成 chevron-left 的视觉（用 CSS 旋转90° 的十字→箭头不现实，
        // 改为放大点击区+品牌色，和设计稿的圆形返回键观感一致）
        // P2：视觉保持 34px（三级页头部空间紧张），用**伪元素把点击区撑到 44×44**，
        //   这样不改变视觉重量，但手指命中面积达标（WCAG 2.5.5 / Android 44dp）。
        + "  body.dsh-s-l3 .dsh-s-ov [class*=\"_close\"]{width:34px !important;height:34px !important;"
        + "border-radius:999px !important;background:var(--ds-ink-100,#eef2f6) !important;"
        + "color:var(--ds-ink-900,#0d1b2e) !important;transition:background 150ms !important;"
        + "position:relative !important;}\n"
        + "  body.dsh-s-l3 .dsh-s-ov [class*=\"_close\"]::after{content:'' !important;"
        + "position:absolute !important;left:50% !important;top:50% !important;"
        + "width:44px !important;height:44px !important;transform:translate(-50%,-50%) !important;}\n"
        + "  body.dsh-s-l3 .dsh-s-ov [class*=\"_close\"]:active{background:var(--ds-ink-200,#d8e1ea) !important;}\n"
        // 二级页的面板/导航间距
        + "  body.dsh-s-l2 .dsh-s-nav{padding-top:4px!important;}\n"
        // ===== 插件市场（三级页）· 对齐设计稿单列全宽风格 =====
        // dsh-client-ui-settings-plugin-inventory 自带网格 `_cards`
        // (minmax(0,1fr) / repeat(2,...))，手机上两列会把插件名挤成竖排，
        // 统一压成单列；卡片圆角/边框按设计稿 ink-200 走。
        + "  .dsh-s-ov [class*=\"_panel\"] [class*=\"_cards\"]{grid-template-columns:minmax(0,1fr) !important;gap:10px !important;}\n"
        + "  .dsh-s-ov [class*=\"_panel\"] [class*=\"_card\"]{border-radius:var(--ds-r-md,16px) !important;}\n"
        + "  .dsh-s-ov [class*=\"_panel\"] [class*=\"_cardTitle\"]{font-size:15.5px !important;font-weight:600 !important;}\n"
        + "  .dsh-s-ov [class*=\"_panel\"] [class*=\"_group\"]{border-top:1px solid var(--ds-ink-200,#d8e1ea) !important;"
        + "padding-top:14px !important;margin-top:4px !important;}\n"
        + "  .dsh-s-ov [class*=\"_panel\"] [class*=\"_catalogHeading\"]{font-size:13.5px !important;color:var(--ds-ink-500,#5a6d84) !important;}\n"
        + "  .dsh-s-ov [class*=\"_panel\"] [class*=\"_details\"]{grid-template-columns:76px minmax(0,1fr) !important;}\n"
        // 收窄到 _panel：原来靠 .dsh-s-l3 兜着，但那是"碰巧"（body 上只有它一个），
        // 统一成显式作用域前缀，才和文件头的「作用域铁律」一致。
        + "  .dsh-s-ov [class*=\"_panel\"] [class*=\"_entryValue\"],"
        + ".dsh-s-ov [class*=\"_panel\"] [class*=\"_options\"] *{overflow-wrap:anywhere !important;}\n"
        // ⚠️ r42：上面这条会连**按钮**一起打上 anywhere（`*` 选择器），按钮被 flex 挤窄时
        //   中文会按字断行竖排（真机③「添加自定义提供 方」）。所以表单控件单独恢复：
        //   不断字 + keep-all；宽度不够时交给 flex-wrap 换行，而不是竖排。
        + "  .dsh-s-ov [class*=\"_panel\"] [class*=\"_options\"] button,"
        + "  .dsh-s-ov [class*=\"_panel\"] [class*=\"_options\"] input,"
        + "  .dsh-s-ov [class*=\"_panel\"] [class*=\"_options\"] select,"
        + "  .dsh-s-ov [class*=\"_panel\"] [class*=\"_options\"] textarea"
        + "  {overflow-wrap:normal !important;word-break:keep-all !important;}\n"
        // 内容列允许滚动：dsh 的 _content 是 flex:1，若不给 overflow 会把
        // 长内容截断且无法滚动（用户反馈"页面无法往下滚动"）。
        + "  .dsh-s-ov [class*=\"_panel\"] [class*=\"_content\"]{overflow-y:auto !important;"
        + "-webkit-overflow-scrolling:touch !important;}\n"
        + "  .dsh-s-ov [class*=\"_panel\"] [class*=\"_options\"]{overflow-y:auto !important;"
        + "-webkit-overflow-scrolling:touch !important;}\n"
        // ===== 插件市场入口卡片（注入，挂在设置二级页最上方）=====
        // ⚠️ 宿主是 .dsh-s-nav 而**不是 _panel**。
        //   真机实测：卡片作为 _panel 的裸子项时，宽度取 max-content（≈420px）撑出
        //   360px 的屏幕、高度被 stretch 拉到 727px（面板整屏），右侧内容被裁切、
        //   导航被挤到屏幕外。放进 .dsh-s-nav 后宿主几何 100% 由我们控制。
        // 视觉：图标 + 标题/副标题 + 状态胶囊 / 主按钮 + 次要按钮 / 一行说明。
        + "  #dsh-market-card{display:none !important;"
        + "flex:0 0 auto !important;align-self:stretch !important;"
        + "box-sizing:border-box !important;width:auto !important;"
        + "max-width:100% !important;min-width:0 !important;height:auto !important;"
        + "margin:14px 0 8px !important;padding:16px !important;"
        + "border:1px solid var(--ds-ink-200,#d8e1ea) !important;"
        + "border-radius:18px !important;overflow:hidden !important;"
        + "background:linear-gradient(180deg,var(--ds-surface,#f9fcff) 0%,"
        + "var(--ds-ink-50,#f2f7ff) 100%) !important;"
        + "box-shadow:0 1px 2px rgba(13,27,46,.04) !important;}\n"
        + "  body[data-ds-dark-theme] #dsh-market-card,html.dsh-dark #dsh-market-card"
        + "  {border-color:var(--ds-ink-400,#8296ab) !important;}\n"
        + "  .dsh-s-nav [data-dsh-dup]{display:none !important;}\n"
        + "  body.dsh-s-l2 #dsh-market-card{display:flex !important;"
        + "flex-direction:column !important;gap:12px !important;}\n"
        // —— 头部
        + "  #dsh-market-card .dsh-mk-head{display:flex !important;"
        + "align-items:flex-start !important;gap:12px !important;min-width:0 !important;}\n"
        + "  #dsh-market-card .dsh-mk-ico{flex:none !important;width:36px !important;"
        + "height:36px !important;border-radius:11px !important;"
        + "background:var(--ds-brand-500,#1466b8) !important;color:#fff !important;"
        + "display:flex !important;align-items:center !important;justify-content:center !important;"
        + "box-shadow:0 4px 10px rgba(43,138,232,.26) !important;}\n"
        + "  #dsh-market-card .dsh-mk-htxt{flex:1 1 auto !important;min-width:0 !important;}\n"
        + "  #dsh-market-card .dsh-mk-title{font-size:16px !important;font-weight:700 !important;"
        + "line-height:1.35 !important;color:var(--ds-ink-900,#0d1b2e) !important;"
        + "white-space:normal !important;overflow-wrap:normal !important;}\n"
        + "  #dsh-market-card .dsh-mk-sub{font-size:12.5px !important;line-height:1.45 !important;"
        + "margin-top:2px !important;color:var(--ds-ink-500,#5a6d84) !important;"
        + "white-space:normal !important;overflow-wrap:normal !important;}\n"
        + "  #dsh-market-card .dsh-mk-chip{flex:none !important;align-self:flex-start !important;"
        // P1：11px 在真机只有 38.5 物理像素，"已安装 v1.66.9"在户外读不出来 -> 提到 12.5px。
        + "padding:4px 8px !important;border-radius:999px !important;font-size:12.5px !important;"
        + "font-weight:700 !important;line-height:18px !important;white-space:nowrap !important;"
        + "background:var(--ds-ink-100,#eef2f6) !important;"
        + "color:var(--ds-ink-500,#5a6d84) !important;}\n"
        // 用 dsh 的 success 语义色，深色模式下自动跟随
        + "  #dsh-market-card .dsh-mk-chip[data-on=\"1\"]{background:var(--dsw-alias-state-success-surface,#e7f7ee) !important;"
        + "color:var(--dsw-alias-state-success-primary,#12794a) !important;}\n"
        // —— 操作区
        + "  #dsh-market-card .dsh-mk-actions{display:flex !important;flex-wrap:wrap !important;"
        + "align-items:center !important;gap:8px !important;min-width:0 !important;}\n"
        + "  #dsh-market-btn{flex:1 1 auto !important;min-width:0 !important;height:44px !important;"
        + "padding:0 16px !important;border:0 !important;border-radius:12px !important;"
        + "background:var(--ds-brand-500,#1466b8) !important;color:#fff !important;"
        + "font-size:15px !important;font-weight:600 !important;cursor:pointer !important;"
        + "box-shadow:0 4px 12px rgba(43,138,232,.24) !important;"
        + "-webkit-tap-highlight-color:transparent !important;}\n"
        + "  #dsh-market-btn[disabled]{background:var(--ds-ink-200,#d8e1ea) !important;"
        + "color:var(--ds-ink-500,#5a6d84) !important;box-shadow:none !important;}\n"
        + "  #dsh-pnpm-btn{flex:0 0 auto !important;height:44px !important;padding:0 14px !important;"
        + "border:1px solid var(--ds-ink-200,#d8e1ea) !important;border-radius:12px !important;"
        + "background:#fff !important;color:var(--ds-ink-700,#1e3450) !important;"
        + "font-size:13.5px !important;line-height:19px !important;font-weight:600 !important;"
        + "cursor:pointer !important;-webkit-tap-highlight-color:transparent !important;}\n"
        + "  #dsh-pnpm-btn[hidden]{display:none !important;}\n"
        + "  #dsh-pnpm-btn[disabled]{color:var(--ds-ink-500,#5a6d84) !important;"
        + "background:var(--ds-ink-100,#eef2f6) !important;}\n"
        + "  #dsh-market-card .dsh-mk-note{font-size:12.5px !important;line-height:18px !important;"
        + "color:var(--ds-ink-500,#5a6d84) !important;white-space:normal !important;"
        + "overflow-wrap:anywhere !important;}\n"
        // 手机上没有文件管理器，dsh 自带的「无法打开配置文件」红字只会让人困惑 -> 隐藏
        // 同样收窄到 _panel 内（见类注释的「作用域铁律」）
        + "  .dsh-s-ov [class*=\"_panel\"] [class*=\"_header\"] [class*=\"error\"],"
        + ".dsh-s-ov [class*=\"_panel\"] [class*=\"_header\"] [class*=\"Error\"],"
        + ".dsh-s-ov [class*=\"_panel\"] [class*=\"_header\"] span[style*=\"error\"]{display:none !important;}\n"
        // ===== 首页/对话区 · 对齐设计稿「首页布局规格· Home Layout」=====
        // 输入卡圆角 24px、边框 #E6E8EB（聚焦转 #C3CFE0）
        + "  div[class*=\"frame\"] textarea,div[class*=\"frame\"] input[type=\"text\"]{"
        + "border-radius:24px !important;background:var(--ds-ink-50,#f7f9fc) !important;"
        // P0：控件边界属WCAG 1.4.11 非文本对比，需 >=3:1；ink-200 只有 1.32 -> 改 ink-400(3.04)。
        + "border:1px solid var(--ds-ink-400,#8296ab) !important;}\n"
        + "  div[class*=\"frame\"] textarea:focus,div[class*=\"frame\"] input[type=\"text\"]:focus{"
        + "border-color:var(--ds-brand-500,#1466b8) !important;background:#fff !important;}\n"
        // 发送按钮 38pt，占位态淡紫蓝 #B8C7F2
        + "  div[class*=\"frame\"] button[aria-label*=\"发送\"],"
        + "div[class*=\"frame\"] button[aria-label*=\"Send\"]{"
        + "width:38px !important;height:38px !important;border-radius:var(--ds-r-full,999px) !important;"
        + "background:#b8c7f2 !important;transition:background 150ms cubic-bezier(.4,0,.2,1) !important;}\n"
        // 预览版徽章胶囊（设计稿：#DFE8FF 底 / #2B4A7D 字 / 半粗）
        // P1：10.5px 在真机(dpr3.5)只有 36.8 物理像素，中文笔画粘连 ->提到 Caption 档 12.5px。
        + "  span[class*=\"badge\"],div[class*=\"badge\"]{border-radius:var(--ds-r-full,999px) !important;"
        + "background:#dfe8ff !important;color:#2b4a7d !important;font-size:12.5px !important;"
        + "line-height:18px !important;font-weight:600 !important;}\n"
        // 主内容垂直居中，底部预留 48px 视觉配重（设计稿）
        + "  div[class*=\"frame\"] > div[class*=\"col\"]{justify-content:center !important;"
        + "padding-bottom:48px !important;}\n"
        + "  .dsh-s-ov [class*=\"_panel\"] [class*=\"_content\"]{width:100% !important;min-width:0 !important;flex:1 1 auto !important;}\n"
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
        + "    body{touch-action:manipulation;}\n"
        + "}\n"
        // ===== 平板（681~1023px）：内容限宽居中 =====
        // 面板本身铺满全屏（否则 dsh 的 mask 会在四周露出暗色边框，观感很差），
        // 但把**内容列**限宽到 760px 并居中 —— 平板上设置项一行拉满 768px 会显得很散。
        + "@media (min-width:681px) and (max-width:1023px){\n"
        + "  .dsh-s-nav{max-width:760px!important;margin-left:auto!important;"
        + "margin-right:auto!important;}\n"
        + "  .dsh-s-ov [class*=\"_panel\"] [class*=\"_header\"],"
        + ".dsh-s-ov [class*=\"_panel\"] [class*=\"_options\"]{max-width:760px!important;"
        + "margin-left:auto!important;margin-right:auto!important;}\n"
        + "}\n"
        // ===== P2 无障碍：焦点可见 + 悬停态 =====
        // :focus-visible 全项目此前0 处 —— 外接键盘 / 蓝牙键盘 / TalkBack 导航时看不到焦点位置
        //（WCAG 2.4.7 Focus Visible, AA）。:hover 补平板/桌面触屏有指针时的悬停反馈。
        + "  #dsh-mbtn:focus-visible,#dsh-market-btn:focus-visible,#dsh-pnpm-btn:focus-visible,"
        + "  .dsh-s-nav [class*=\"_navCell\"]:focus-visible,"
        + "  .dsh-s-ov [class*=\"_close\"]:focus-visible{outline:2px solid var(--ds-brand-500,#1466b8) !important;"
        + "outline-offset:2px !important;}\n"
        + "  #dsh-mbtn:hover,#dsh-pnpm-btn:hover{background:var(--ds-ink-100,#eef2f6) !important;}\n"
        + "  #dsh-market-btn:hover{background:var(--ds-brand-600,#0f5296) !important;}\n"
        + "  #dsh-market-btn:active{background:var(--ds-brand-600,#0f5296) !important;"
        + "transform:translateY(1px) !important;}\n"
        + "  .dsh-s-nav [class*=\"_navCell\"]:hover{background:var(--ds-ink-100,#eef2f6) !important;}\n"
        + "  .dsh-s-ov [class*=\"_close\"]:hover{background:var(--ds-ink-200,#d8e1ea) !important;}\n"
        + "@media (prefers-reduced-motion:reduce){"
        + "div[class*=\"sidebarCol\"],#dsh-scrim{transition:none !important;}}\n";

    /**
     * dsh-market（/dsh-market）**专用**移动/平板适配。
     *
     * ## 为什么必须独立一套
     * 市场页的类名与设置面板**同源**（都是 `..._card` / `..._cards` / `..._group` /
     * `..._options`），而通用 CSS 里那些规则是为**设置面板**写的，裸放在
     * `@media(max-width:1023px)` 里 -> 市场页同样吃到 -> 真机后果：
     * 标题竖排成一列、网格被 `minmax(0,1fr)` 压成单列、间距错乱。
     *
     * 所以 {@link JS} 里按 `data-dsh-market-root` 判定：命中市场页时
     * **只注入这段**，不注入设置面板那套 —— 两套规则永不相遇。
     *
     *## 手机 + 平板都要兼顾
     * 市场页**自带** `@media(max-width:680px)` 与 `(max-width:560px)` 两个断点，
     * 网格用 `auto-fit,minmax(280px,1fr)` 自适应 —— 所以**不要抢它的网格**，
     * 只补它缺的：① 顶部头部在窄屏会挤成竖排 ② 长路径/报错文本撑破布局
     * ③ 触控目标偏小 ④ 没有安全区适配 ⑤ 平板上留白过多。
     *
     * 断点策略：≤680px 手机（对齐它自带断点）/ ≥681px 平板（加大留白与两栏）
     */
    private static final String MARKET_CSS =
        "  /*顶部头部：图标+名称+版本+主按钮 在窄屏会被挤成竖排，改为可换行横排 */\n"
        + "  [data-dsh-market-root] [class*=\"_header\"]{flex-wrap:wrap !important;"
        + "align-items:center !important;gap:10px !important;}\n"
        // ⚠️ r44：这条原来是 min-width:0 + overflow-wrap:anywhere ——anywhere 允许**按字断行**，
        //   头部被挤窄后「插件市场」直接竖成「插/件/市/场」（真机 10:19 截图）。
        //   市场页与设置面板是两套**互不相遇**的 CSS（按 pathname 分流），
        //   所以 r42 在 .dsh-s-ov 里做的修复不会覆盖到这里，必须单独改。
        + "  [data-dsh-market-root] [class*=\"_headerTitle\"],"
        + "[data-dsh-market-root] [class*=\"_title\"]{white-space:nowrap !important;"
        + "word-break:keep-all !important;overflow-wrap:normal !important;"
        + "line-height:1.35 !important;}\n"
        // 头部内所有文本都不许按字断行（中文竖排的通用防线）
        + "  [data-dsh-market-root] [class*=\"_header\"] span,"
        + "[data-dsh-market-root] [class*=\"_header\"] p,"
        + "[data-dsh-market-root] [class*=\"_header\"] div{word-break:keep-all !important;}\n"
        // 图标类名在不同版本会变，用通用属性兜住：任何固定宽高的小方块不参与挤压
        + "  [data-dsh-market-root] [class*=\"_logo\"],"
        + "[data-dsh-market-root] [class*=\"_icon\"]{flex:none !important;}\n"
        // 主按钮（下拉/安装类）保持单行不撑破
        // ⚠️ r44：原来是 white-space:normal —— 头部按钮（「更新插件市场」「重启前都不再提醒」）
        //   在窄屏会被挤成多行并被裁掉。改成单行 + 不收缩，放不下就整块换行到下一行。
        + "  [data-dsh-market-root] button{white-space:nowrap !important;"
        + "flex:none !important;max-width:100% !important;}\n"
        + "  [data-dsh-market-root] input{white-space:normal !important;}\n"
        // 报错/日志这类长文本：允许在任意位置断行，避免整块撑宽导致横向滚动
        + "  [data-dsh-market-root] pre,[data-dsh-market-root] code,"
        + "[data-dsh-market-root] [class*=\"_log\"],"
        + "[data-dsh-market-root] [class*=\"_diag\"],"
        + "[data-dsh-market-root] [class*=\"_error\"]{"
        + "overflow-wrap:anywhere !important;word-break:break-word !important;"
        + "white-space:pre-wrap !important;max-width:100% !important;}\n"
        // 页面整体：禁止横向溢出
        + "  [data-dsh-market-root]{overflow-x:hidden !important;max-width:100% !important;}\n"
        // 真机 10:19 截图底部有一条横向滚动条：窄屏下头部把内容顶出了视口宽度。
        // 根因已在 1/2 里修掉，这里再加一道兜底，别让整页能横向拖。
        + "  html,body{overflow-x:hidden !important;}\n"
        // 注入的汉堡是 position:fixed 悬浮在右上角，会压住市场页自己的头部/工具条
        // （真机截图里它就叠在内容卡片右上角）。给页面整体让出「安全区 + 52px」，
        // 52 = 按钮 42px + 上下各 5px 余量。
        + "  [data-dsh-market-root]{padding-top:calc(52px + env(safe-area-inset-top,0px)) !important;"
        + "padding-bottom:env(safe-area-inset-bottom,0px) !important;}\n"
        // 触控目标 ≥44px（WCAG AA）：搜索框、筛选、标签页、列表项
        + "  [data-dsh-market-root] input,[data-dsh-market-root] select,"
        + "[data-dsh-market-root] [role=\"tab\"],[data-dsh-market-root] [role=\"button\"]{"
        + "min-height:44px !important;box-sizing:border-box !important;}\n"
        // 安全区（刘海/手势条）—— 全项目此前没有，这里补上
        + "  [data-dsh-market-root]{padding-left:max(0px,env(safe-area-inset-left)) !important;"
        + "padding-right:max(0px,env(safe-area-inset-right)) !important;}\n"
        + "  @media (max-width:680px){\n"
        + "    [data-dsh-market-root]{padding-left:16px !important;padding-right:16px !important;}\n"
        + "    [data-dsh-market-root] [class*=\"_header\"]{padding-top:12px !important;"
        + "padding-bottom:8px !important;}\n"
        // 手机：卡片标题不再被压窄（它自带 680px 断点会切单列，别再叠压）
        + "    [data-dsh-market-root] [class*=\"_cardTitle\"]{font-size:15px !important;"
        + "line-height:1.4 !important;}\n"
        + "  }\n"
        + "  @media (min-width:681px){\n"
        // 平板：内容别铺满整个屏宽，限制可读宽度并居中
        + "    [data-dsh-market-root]{max-width:1100px !important;margin:0 auto !important;}\n"
        + "  }\n";

    /**
     * 市场页专属的「外壳」样式：汉堡按钮 + 安全区。
     * 与 {@link MARKET_CSS} 分开是因为它服务于**注入的按钮**（我们自己的 DOM），
     * 而不是市场页的内容排版。
     */
    private static final String MARKET_CHROME_CSS =
        "[data-dsh-market-root]{--dsh-sat:env(safe-area-inset-top,0px);}\n"
        + "#dsh-mtop{position:fixed;top:0;left:0;right:0;z-index:65;display:flex;\n"
        + "  align-items:flex-start;justify-content:flex-end;\n"
        + "  padding:calc(6px + var(--dsh-sat,0px)) 6px 0 6px;\n"
        + "  pointer-events:none;background:transparent;}\n"
        + "#dsh-mtop>*{pointer-events:auto;}\n"
        + "body.dsh-ov-open #dsh-mtop{display:none !important;}\n"
        + "#dsh-scrim{z-index:60;}\n";

    private static final String JS =
        "(function(){\n"
        + "  var SID='dsh-m-shell';\n"
        + "  if(document.getElementById(SID)){return;}\n"          // 幂等
        // ===== P3-3f 深色探测（兜底）=====
        // dsh 自己的深色标记是 body[data-ds-dark-theme]，CSS 已直接跟随；
        // 这里只在「有深色底但没有 dsh 标记」时补一个 .dsh-dark（未来 dsh 改标记名的保险）。
        //
        // ⚠️ 必须定义在 **IIFE 顶层**（与 mq 同作用域）。
        //   之前它被放在 if(!inMarket){...} 块内，而 setInterval(detectDark,500)
        //   在块外 -> ReferenceError 打断 mount() -> 汉堡的 click 监听器**根本没绑上**
        //   -> 「点汉堡无反应」（真机 11:39 事故）。
        + "  function detectDark(){\n"
        + "    var b=document.body;\n"
        + "    if(!b){return;}\n"
        + "    if(b.hasAttribute('data-ds-dark-theme')){\n"
        + "      document.documentElement.classList.remove('dsh-dark');\n"
        + "      return;\n"
        + "    }\n"
        + "    var c=window.getComputedStyle(b).backgroundColor||'';\n"
        + "    var m=c.match(/rgba?\\((\\d+),\\s*(\\d+),\\s*(\\d+)(?:,\\s*([\\d.]+))?/);\n"
        + "    if(!m){return;}\n"
        // 完全透明（alpha=0）不能当暗色：实测浅色页 body 背景是 transparent
        + "    var a=m[4]===undefined?1:parseFloat(m[4]);\n"
        + "    if(a<0.5){document.documentElement.classList.remove('dsh-dark');return;}\n"
        + "    var lum=(0.2126*+m[1]+0.7152*+m[2]+0.0722*+m[3])/255;\n"
        + "    document.documentElement.classList.toggle('dsh-dark',lum<0.5);\n"
        + "  }\n"
        + "  var mq=window.matchMedia('(max-width:1023px)');\n"
        + "  function close(){document.body.classList.remove('dsh-drawer-open');}\n"
        + "  function mount(){\n"
        + "    if(document.getElementById(SID)){return;}\n"
        + "    var mark=document.createElement('div');mark.id=SID;\n"
        + "    document.body.appendChild(mark);\n"
        // P3-2：aria-live 播报区（视觉隐藏，屏幕阅读器可读）
        + "    var live=document.createElement('div');\n"
        + "    live.id='dsh-live';\n"
        + "    live.setAttribute('role','status');\n"
        + "    live.setAttribute('aria-live','polite');\n"
        + "    live.setAttribute('aria-atomic','true');\n"
        + "    live.style.cssText='position:absolute;width:1px;height:1px;margin:-1px;padding:0;overflow:hidden;clip:rect(0 0 0 0);white-space:nowrap;border:0';\n"
        + "    document.body.appendChild(live);\n"
        // ⚠️ 作用域隔离（最重要的结构性问题修复）：
        //原方案把所有规则裸写在 @media(max-width:1023px) 里，
        //   而 dsh-market（/dsh-market 独立路由页）的类名与设置面板**同源**
        //   （都叫 _card / _cards / _group / _options...），
        //   于是市场页吃到了为设置面板写的规则 —— 真机后果：标题竖排、
        //   网格被压成单列、间距错乱。
        //修法：给 <style> 打个 data-dsh-s 标记，CSS 全部用 body[data-dsh-s] 限定，
        //   市场页走下面那段独立适配（只补它缺的，不抢它的网格）。
        + "    var st=document.createElement('style');st.id=SID+'-css';\n"
        // ===== 作用域分流：市场页只注入 MARKET_CSS，其余注入 CSS =====
        // dsh-market 的类名与设置面板同源，两套规则会互相污染
        // （标题竖排/ 网格被压单列）。这里用 pathname 先分流，永不相遇。
        + "    var inMarket=/[\\/]dsh-market(\\/|$|[?#])/.test(location.pathname);\n"
        + "    st.textContent=inMarket?" + jsString(MARKET_CSS) + ":" + jsString(CSS) + ";\n"
        + "    (document.head||document.documentElement).appendChild(st);\n"
        // 市场页：另建一个 style 补安全区/汉堡遮罩（设置页那套不含这些）
        + "    if(inMarket){\n"
        + "      var ms=document.createElement('style');ms.id=SID+'-mkt';\n"
        + "      ms.textContent=" + jsString(MARKET_CHROME_CSS) + ";\n"
        + "      (document.head||document.documentElement).appendChild(ms);\n"
        + "    }\n"
        // 市场页需要汉堡（返回/开抽屉），所以**不return**，
        // 只把设置页专属的那段二级/三级逻辑用 inMarket 跳过。
        // ===== 真·设置浮层指纹判定（r41 核心修复）=====
        // ⚠️ r40 的严重事故：`[class*="_overlay"]` **不是设置浮层独有**。
        //   源码实测（dsh-client-ui-*，全仓仅 3 个类含 `_overlay`）：
        //     VOzbGW_overlay      = settings-general 的设置浮层  ← 只有它是真的
        //     pI_x6G_overlayLayer = layout 的主布局浮层层（常驻首页，inset:0）
        //     uV2eYG_overlayAnchor= conversation 的输入框锚点（height:0，首页就有）
        //   旧代码取第一个命中的就算"浮层已开" -> 首页被误判 -> body 加上
        //   dsh-ov-open -> r40 新加的 `body.dsh-ov-open #dsh-mtop{display:none}`
        //   把汉堡藏掉 -> 真机反馈「汉堡按钮没了，无法打开设置」。
        //   指纹：只有设置浮层同时具备 `_panel` 与 `_navList`（两者组合全仓唯一），
        //   再排除 display:none/visibility:hidden 的卸载残留。
        + "    function settingsOverlay(){\n"
        + "      var ovs=document.querySelectorAll('[class*=\"_overlay\"]');\n"
        + "      for(var i=0;i<ovs.length;i++){\n"
        + "        var o=ovs[i];\n"
        + "        if(!o.querySelector('[class*=\"_panel\"]')){continue;}\n"
        + "        if(!o.querySelector('[class*=\"_navList\"]')){continue;}\n"
        + "        var cs=window.getComputedStyle(o);\n"
        + "        if(cs.display!=='none'&&cs.visibility!=='hidden'){return o;}\n"
        + "      }\n"
        + "      return null;\n"
        + "    }\n"
        // 给**我们自己的**宿主打标：.dsh-s-ov = 设置浮层本体、.dsh-s-nav = 导航容器。
        // CSS 只认这两个类（铁律六：跨模块借类名 = 迟早翻车，"_panel" 有 6 个模块在用）。
        + "    function tagHosts(){\n"
        + "      var ov=settingsOverlay();\n"
        + "      if(!ov){return null;}\n"
        + "      if(!ov.classList.contains('dsh-s-ov')){ov.classList.add('dsh-s-ov');}\n"
        + "      var nl=ov.querySelector('[class*=\"_navList\"]');\n"
        + "      if(nl&&nl.parentElement&&!nl.parentElement.classList.contains('dsh-s-nav')){\n"
        + "        nl.parentElement.classList.add('dsh-s-nav');\n"
        + "      }\n"
        + "      return ov;\n"
        + "    }\n"
        // dsh-ov-open 的**唯一写入点**：由指纹决定，别处不许直接 add/remove。
        // （r40 是"启发式判定 + 立即生效的隐藏规则"，判错一次 = 入口彻底消失。）
        + "    function syncOvFlag(){\n"
        + "      var b=document.body;\n"
        + "      if(!b){return;}\n"
        + "      var on=!!settingsOverlay();\n"
        + "      if(on!==b.classList.contains('dsh-ov-open')){\n"
        + "        if(on){b.classList.add('dsh-ov-open');}else{b.classList.remove('dsh-ov-open');}\n"
        + "      }\n"
        + "    }\n"
        + "    if(!inMarket){\n"
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
        // overlay() 已上移到 mount() 作用域并改名 settingsOverlay()（指纹判定）
        + "      function setL2(){b.classList.remove('dsh-s-l3');b.classList.add('dsh-s-l2');}\n"
        + "      function setL3(){b.classList.remove('dsh-s-l2');b.classList.add('dsh-s-l3');}\n"

        // 单一 sync：面板开->按当前层级显示；面板关->清状态类
        // 给导航**容器**打标记：CSS 靠它限定作用域，避免 [class*="_nav"]
        // 过度匹配到 _navIcon/_navLabel（那会把图标和文字也变成 column 布局）。
        // 定位方式：_navList 的父元素就是容器（结构 nav > navTitle + navList）。
        // tagNav() 已并入 mount() 作用域的 tagHosts()：一次遍历同时给浮层与导航容器打标
        // ===== 插件市场入口卡片 =====
        // 插在导航容器之后（设置二级页顶部）。**挂在 body 上**，避免被 React 清掉（r30 教训）。
        // ===== 插件市场入口卡片 =====
        // ⚠️ 挂到 .dsh-s-nav 的**内部第一个子节点**（原来挂 _panel）。
        //   真机实测：_panel 上是行方向 flex，卡片作为裸子项 → 宽度取 max-content
        //   撑出屏幕、高度被 stretch 拉满整屏、导航被挤到屏幕外。
        //   .dsh-s-nav 的类名由我们的 JS 打上，dsh 覆盖不了它的几何。
        + "      function mkMarketCard(){\n"
        + "        if(document.getElementById('dsh-market-card')){return;}\n"
        + "        var nav=document.querySelector('.dsh-s-nav');\n"
        + "        if(!nav){return;}\n"
        + "        var c=document.createElement('div');c.id='dsh-market-card';\n"
        + "        c.innerHTML='<div class=\"dsh-mk-head\">'\n"
        + "          +'<span class=\"dsh-mk-ico\">'\n"
        + "          +'<svg width=\"20\" height=\"20\" viewBox=\"0 0 24 24\" fill=\"none\"'\n"
        + "          +' stroke=\"currentColor\" stroke-width=\"1.9\" stroke-linecap=\"round\"'\n"
        + "          +' stroke-linejoin=\"round\"><path d=\"M4 7h16M4 12h16M4 17h10\"/></svg></span>'\n"
        + "          +'<div class=\"dsh-mk-htxt\">'\n"
        + "          +'<div class=\"dsh-mk-title\">插件市场</div>'\n"
        + "          +'<div class=\"dsh-mk-sub\">社区插件 · 一键装到本机</div></div>'\n"
        + "          +'<span class=\"dsh-mk-chip\" id=\"dsh-mk-chip\">检测中</span></div>'\n"
        + "          +'<div class=\"dsh-mk-actions\">'\n"
        + "          +'<button id=\"dsh-market-btn\" type=\"button\">检测中…</button>'\n"
        + "          +'<button id=\"dsh-pnpm-btn\" type=\"button\" hidden>安装 pnpm</button></div>'\n"
        + "          +'<div class=\"dsh-mk-note\" id=\"dsh-mk-note\">安装插件需要 pnpm 包管理器</div>';\n"
        + "        c.querySelector('#dsh-market-btn').addEventListener('click',onMarket);\n"
        + "        c.querySelector('#dsh-pnpm-btn').addEventListener('click',onPnpm);\n"
        + "        nav.insertBefore(c, nav.firstChild);\n"
        + "        refreshMarket();\n"
        + "      }\n"
        // ===== 几何自愈（兜底）=====
        // 上面每条 CSS 都带 !important，但真机上仍出现过 _panel 的 flex 方向没被覆盖。
        // 所以这里用**实测几何**兜底：卡片只要越出视口、或被拉伸到异常高度，
        // 立刻用内联样式把它钉成「视口宽-32px、高度自适应、不参与 flex 伸缩」。
        // 宽高恢复正常后自动撤销内联样式（不残留，便于日后排查）。
        // dshmarket 装好后，dsh 会**自己**在设置导航里注册一个「插件市场」项。
        // 它和我们的注入卡片同名同目标 -> 二级页出现两个「插件市场」（真机②）。
        //   这里只做**视觉隐藏**（保留在 DOM 里），因为 openMarket() 还要靠它跳转：
        //   市场路由是插件注册的客户端路由，必须由 dsh 自己的路由处理。
        + "      function marketCell(){\n"
        + "        var cells=document.querySelectorAll('.dsh-s-nav [class*=\"_navCell\"]');\n"
        + "        for(var i=0;i<cells.length;i++){\n"
        + "          var t=(cells[i].textContent||'').replace(/\\s+/g,'');\n"
        + "          if(t==='插件市场'||t==='PluginMarket'){return cells[i];}\n"
        + "        }\n"
        + "        return null;\n"
        + "      }\n"
        // ===== P3-1 浮层焦点管理 =====
        // 打开设置浮层时把焦点移进去，Tab 在浮层内循环（焦点陷阱），
        // 关闭时把焦点还给汉堡按钮 —— 外接键盘 / TalkBack 用户否则会「跳到看不见的地方」。
        + "      var FOCUSABLE='a[href],button:not([disabled]),input:not([disabled]),select:not([disabled]),textarea:not([disabled]),[tabindex]';\n"
        + "      function focusables(ov){\n"
        + "        var all=(ov||document).querySelectorAll(FOCUSABLE);\n"
        + "        var out=[];\n"
        + "        for(var i=0;i<all.length;i++){\n"
        + "          var e=all[i],r=e.getBoundingClientRect();\n"
        // tabindex=-1 的元素（如 dsh 自己管理的浮层）要排除：FOCUSABLE 选择器
        // 里不写 \"-1\" 是因为 Java 字符串转义会吞掉双引号，改在运行时判断。
        + "          if(e.getAttribute('tabindex')==='-1'){continue;}\n"
        + "          if(r.width>0&&r.height>0){out.push(e);}\n"
        + "        }\n"
        + "        return out;\n"
        + "      }\n"
        + "      var lastFocus=null;\n"
        + "      var panelOpen=false;\n"
        + "      function trapFocus(e){\n"
        + "        if(e.key!=='Tab'){return;}\n"
        // ⚠️ r48：ov 可能为 null（面板已卸载但 keydown 监听还在）。
        //   旧代码直接 ov.contains(ae) 会抛 TypeError，keydown 监听器一旦抛错，
        //   同一次事件的后续处理全部中断 —— 返回键/Enter 导航时可能就断在这里。
        + "        var ov=document.querySelector('[class*=\"_panel\"]');\n"
        + "        if(!ov){return;}\n"
        + "        var f=focusables(ov);\n"
        + "        if(!f.length){return;}\n"
        + "        var first=f[0],last=f[f.length-1];\n"
        + "        var ae=document.activeElement;\n"
        + "        if(e.shiftKey&&(ae===first||!ov.contains(ae))){\n"
        + "          e.preventDefault();last.focus();\n"
        + "        }else if(!e.shiftKey&&(ae===last||!ov.contains(ae))){\n"
        + "          e.preventDefault();first.focus();\n"
        + "        }\n"
        + "      }\n"
        + "      function enterFocus(ov){\n"
        + "        if(!ov){return;}\n"
        + "        var f=focusables(ov);\n"
        + "        if(f.length){try{f[0].focus();}catch(e){}}\n"
        + "      }\n"
        + "      function leaveFocus(){\n"
        + "        var back=lastFocus||document.getElementById('dsh-mbtn');\n"
        + "        lastFocus=null;\n"
        + "        if(back&&back.focus){try{back.focus();}catch(e){}}\n"
        + "      }\n"
        // ===== P3-2 无障碍播报区 =====
        // 安装/更新结果除alert() 外再播报一次，屏幕阅读器用户能听到。
        + "      function announce(txt){\n"
        + "        var box=document.getElementById('dsh-live');\n"
        + "        if(!box){return;}\n"
        + "        box.textContent='';\n"
        + "        setTimeout(function(){box.textContent=txt;},30);\n"
        + "      }\n"
        + "      function dedupeMarket(){\n"
        + "        var c=marketCell();\n"
        + "        if(!c){return;}\n"
        + "        c.setAttribute('data-dsh-dup','1');\n"
        + "        c.style.setProperty('display','none','important');\n"
        + "      }\n"
        // ⚠️ r48 重写（r43 的方案是错的）：
        //   r43 以为 /dsh-market 是「插件注册的客户端路由」，于是保留了一个
        //   `location.assign('/dsh-market')` 的整页跳转兜底。实测真机证明：
        //   dshmarket 其实是**设置面板里的一个 panel**（截图里它带✘ 和「打开配置文件」，
        //   就是设置浮层内的三级页），服务端**根本没有** /dsh-market 这条路由。
        //   一旦走到这个兜底 -> GET /dsh-market -> 404 -> WebView 整页白屏，
        //   而且这条 404 还进了浏览器历史，用户按返回键会再回到这个白屏页（真机图2）。
        //   现在**彻底不做整页跳转**：只点 dsh 注册的那个导航项；点不到就如实提示。
        + "      function openMarket(btn){\n"
        + "        var cell=marketCell();\n"
        + "        if(cell){ try{ cell.click(); return 'nav'; }catch(e){} }\n"
        + "        var note=document.getElementById('dsh-mk-note');\n"
        + "        if(note){ note.textContent='未能定位市场入口，请重新打开设置页'; }\n"
        + "        if(btn){ btn.textContent='打开失败'; }\n"
        + "        return 'fail';\n"
        + "      }\n"
        + "      function guardCard(){\n"
        + "        var c=document.getElementById('dsh-market-card');\n"
        + "        if(!c){return;}\n"
        + "        var vw=document.documentElement.clientWidth||window.innerWidth||360;\n"
        + "        var w=Math.max(220,Math.round(vw)-32);\n"
        + "        var r=c.getBoundingClientRect();\n"
        + "        var bad=(r.width>vw+1)||(r.right>vw+1)||(r.left<-1)||(r.height>vw*1.6);\n"
        + "        if(bad){\n"
        + "          c.style.setProperty('flex','0 0 auto','important');\n"
        + "          c.style.setProperty('align-self','flex-start','important');\n"
        + "          c.style.setProperty('width',w+'px','important');\n"
        + "          c.style.setProperty('max-width',w+'px','important');\n"
        + "          c.style.setProperty('min-width','0','important');\n"
        + "          c.style.setProperty('height','auto','important');\n"
        + "          c.style.setProperty('box-sizing','border-box','important');\n"
        + "          c.style.setProperty('overflow','hidden','important');\n"
        + "          c.style.setProperty('margin-left','16px','important');\n"
        + "          c.style.setProperty('margin-right','16px','important');\n"
        + "          c.setAttribute('data-dsh-guarded','1');\n"
        + "        }else if(c.getAttribute('data-dsh-guarded')==='1'){\n"
        + "          var ks=['flex','align-self','width','max-width','min-width','height',\n"
        + "                  'box-sizing','overflow','margin-left','margin-right'];\n"
        + "          for(var i=0;i<ks.length;i++){c.style.removeProperty(ks[i]);}\n"
        + "          c.removeAttribute('data-dsh-guarded');\n"
        + "        }\n"
        + "      }\n"
        + "      function refreshMarket(){\n"
        + "        var btn=document.getElementById('dsh-market-btn');\n"
        + "        if(!btn){return;}\n"
        + "        var chip=document.getElementById('dsh-mk-chip');\n"
        + "        var v='';\n"
        + "        if(window.dshNative){try{ v=window.dshNative.marketInstalled(); }catch(e){}}\n"
        // 已安装 -> 按钮变成"打开市场"（**可点**）。
        // dshmarket 是**独立路由页** /dsh-market（lib/routes.js 实测），
        // 并不注册进设置面板，所以装完必须自己给出入口，否则"装了却用不了"。
        + "        if(v){ btn.textContent='打开市场'; btn.removeAttribute('disabled');\n"
        + "          btn.setAttribute('data-open','1');\n"
        + "          if(chip){ chip.textContent='已安装 v'+v; chip.setAttribute('data-on','1'); } }\n"
        + "        else { btn.textContent='一键安装'; btn.removeAttribute('disabled');\n"
        + "          btn.removeAttribute('data-open');\n"
        + "          if(chip){ chip.textContent='未安装'; chip.removeAttribute('data-on'); } }\n"
        + "        refreshPnpm();\n"
        + "      }\n"
        + "      var pnpmBusy=false;\n"
        + "      function refreshPnpm(){\n"
        + "        var pb=document.getElementById('dsh-pnpm-btn');\n"
        + "        var note=document.getElementById('dsh-mk-note');\n"
        + "        if(!pb){return;}\n"
        + "        var ready='';\n"
        + "        if(window.dshNative){try{ ready=window.dshNative.pnpmReady(); }catch(e){}}\n"
        // pnpm 就绪时**收起按钮**，只留一行说明 —— 一个禁用的灰按钮只是噪音
        + "        if(ready){ pb.hidden=true;\n"
        + "          if(note){ note.textContent='依赖已就绪，可直接安装插件'; } }\n"
        + "        else { pb.hidden=false; pb.textContent='安装 pnpm'; pb.removeAttribute('disabled');\n"
        + "          if(note){ note.textContent='安装插件需要 pnpm 包管理器'; } }\n"
        + "      }\n"
        + "      function onPnpm(e){\n"
        + "        e.preventDefault();e.stopPropagation();\n"
        + "        if(pnpmBusy){return;}\n"
        + "        pnpmBusy=true;\n"
        + "        var pb=document.getElementById('dsh-pnpm-btn');\n"
        + "        var note=document.getElementById('dsh-mk-note');\n"
        + "        pb.setAttribute('disabled','disabled');pb.textContent='安装中…';\n"
        + "        if(note){ note.textContent='正在下载并安装 pnpm…'; }\n"
        + "        window.__dshPnpmDone=function(id,res){\n"
        + "          pnpmBusy=false;\n"
        + "          if(res&&res.ok){ pb.hidden=true; pb.removeAttribute('disabled');\n"
        + "            if(note){ note.textContent='依赖已就绪，可直接安装插件'; }\n"
        + "            announce('pnpm 安装完成，可直接安装插件'); }\n"
        + "          else { pb.removeAttribute('disabled'); pb.textContent='重试';\n"
        + "            if(note){ note.textContent='pnpm 安装失败'; }\n"
        + "            alert('pnpm 安装失败：'+((res&&res.message)||'未知错误'));\n"
        + "            announce('pnpm 安装失败'); }\n"
        + "        };\n"
        + "        try{ window.dshNative.installPnpm('pnpm'); }\n"
        + "        catch(err){ pnpmBusy=false; pb.removeAttribute('disabled'); pb.textContent='重试'; }\n"
        + "      }\n"
        + "      var installing=false;\n"
        + "      function onMarket(e){\n"
        + "        e.preventDefault();e.stopPropagation();\n"
        // ⚠️ r39 的真 bug：旧代码把 `var btn=...` 写在下面却在这里先用 btn.getAttribute()。
        //   var 会提升但值是 undefined -> 每次点击都在这里抛 TypeError，
        //   "打开市场"永远点不动。把声明提到函数最前面。
        + "        var btn=document.getElementById('dsh-market-btn');\n"
        + "        if(!btn){return;}\n"
        + "        if(btn.getAttribute('data-open')==='1'){ openMarket(btn); return; }\n"
        + "        if(installing){return;}\n"
        + "        installing=true;\n"
        + "        var note=document.getElementById('dsh-mk-note');\n"
        + "        var chip=document.getElementById('dsh-mk-chip');\n"
        + "        btn.setAttribute('disabled','disabled');btn.textContent='安装中…';\n"
        + "        if(note){ note.textContent='正在下载并安装插件…'; }\n"
        + "        announce('正在下载并安装插件');\n"
        + "        window.__dshMarketDone=function(id,res){\n"
        + "          installing=false;\n"
        + "          if(res&&res.ok){ btn.removeAttribute('disabled');\n"
        + "            btn.textContent='打开市场'; btn.setAttribute('data-open','1');\n"
        + "            if(chip){ chip.textContent='已安装 v'+(res.version||'');\n"
        + "              chip.setAttribute('data-on','1'); }\n"
        + "            if(note){ note.textContent='安装完成，点「打开市场」进入'; }\n"
        + "            announce('插件市场安装完成'); }\n"
        + "          else { btn.removeAttribute('disabled'); btn.textContent='重试';\n"
        + "            if(note){ note.textContent='安装失败'; }\n"
        + "            alert('安装失败：'+((res&&res.message)||'未知错误'));\n"
        + "            announce('插件安装失败'); }\n"
        + "        };\n"
        + "        try{ window.dshNative.installMarket('mkt'); }\n"
        + "        catch(err){ installing=false; btn.removeAttribute('disabled'); btn.textContent='重试'; }\n"
        + "      }\n"
        + "      function sync(){\n"
        + "        var ov=settingsOverlay();\n"
        + "        if(!ov){\n"
        // 面板已关闭：清状态类**并把注入卡片摘掉**。
        // 否则卡片会变成孤儿节点留在 body 上，而 dsh 的 _content 又因状态类残留
        // 被display:none 永久隐藏 -> 整页只剩卡片那块空白（真机反馈"设置返回时
        // 会出现图三的情况"：一大片空白 + 只有插件市场卡片）。
        + "          b.classList.remove('dsh-s-l2');b.classList.remove('dsh-s-l3');\n"
        + "          syncOvFlag();\n"
        // ⚠️ r47：leaveFocus 只能在「开 -> 关」那一跳调用。首页永远没有设置面板，
        //   若每次 sync 都调，它内部的 focus() 会反复抢焦点：
        //   点汉堡 -> mousedown 焦点落按钮 -> DOM 变化 -> sync -> 焦点被抢走
        //   -> mouseup 落空 -> click 不派发 -> **抽屉打不开**。
        + "          if(panelOpen){panelOpen=false;leaveFocus();}\n"
        + "          var mc=document.getElementById('dsh-market-card');\n"
        + "          if(mc&&mc.parentNode){mc.parentNode.removeChild(mc);}\n"
        + "          syncBack();\n"
        + "          return;\n"
        + "        }\n"
        // dsh-ov-open：CSS 靠它把汉堡显式藏起来（浮层期间不需要抽屉入口；
        // 汉堡 z-index 61/65 低于浮层 1000，靠层叠"碰巧"被盖住不可靠）
        + "        syncOvFlag();\n"
        + "        tagHosts();\n"
        + "        mkMarketCard();\n"
        + "        dedupeMarket();\n"
        + "        detectDark();\n"
        // 首次打开才移入焦点（sync 每次 DOM 变化都跑，重复 focus 会打断用户）
        + "        if(!panelOpen){panelOpen=true;\n"
        + "          lastFocus=document.activeElement;\n"
        + "          enterFocus(document.querySelector('[class*=\"_panel\"]'));\n"
        + "        }\n"
        + "        if(!b.classList.contains('dsh-s-l2')&&!b.classList.contains('dsh-s-l3')){setL2();}\n"
        + "        guardCard();\n"
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
        + "        var ov=settingsOverlay();\n"
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
        + "        trapFocus(e);\n"
        + "      },true);\n"
        // 面板是动态挂载/卸载的，DOM 变化时同步
        // 去抖：sync() 自身会改 DOM（插/拔返回按钮），直接observe 会自激循环
        + "      var pend=null;\n"
        + "      function sched(){\n"
        + "        if(pend){return;}\n"
        + "        pend=setTimeout(function(){pend=null;sync();},60);\n"
        + "      }\n"
        + "      if(window.MutationObserver){new MutationObserver(sched).observe(b,{childList:true,subtree:true});}\n"
        // 旋屏 / 折叠屏展开后重新量一次卡片几何
        + "      window.addEventListener('resize',function(){sched();});\n"
        + "      window.addEventListener('orientationchange',function(){sched();});\n"
        + "      sync();\n"
        + "    })();\n"
        // 闭合 if(!inMarket){ —— 设置页专属逻辑到此结束
        // （市场页不需要二级/三级分级与插件市场卡片，但**仍需要下面的汉堡**）
        + "    }\n"
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
        // 看门狗：指纹判定 + 打标每 500ms 重算一次（面板是 React 动态挂载/卸载的，
        // MutationObserver 会漏掉"属性未变但节点被替换"的情形）。
        + "    setInterval(function(){tagHosts();syncOvFlag();},500);\n"
        + "    setInterval(detectDark,500);\n"
        + "    detectDark();\n"
        // ===== 返回键自愈 =====
        // MainActivity 的返回键走 webView.goBack()（浏览器历史）。dsh 是 SPA，
        // 如果历史里混入过「服务端不存在的路径」（如 r43 那次整页跳 /dsh-market -> 404），
        // 返回时 WebView 会重新加载那个 404 -> 白屏，只剩我们注入的汉堡。
        // 这里记下真正的应用入口，返回后若发现当前文档既不是入口也没有 dsh 主区域，
        // 就用 replace 回入口 —— replace 不会新增历史项，不会再死循环。
        + "    window.__dshHomeUrl=location.href;\n"
        + "    window.addEventListener('popstate',function(){\n"
        + "      setTimeout(function(){\n"
        + "        var ov=document.querySelector('[class*=\"_overlay\"]');\n"
        + "        var center=document.querySelector('div[class*=\"centerCol\"]');\n"
        + "        var home=document.getElementById('dsh-market-shell');\n"
        + "        var dead=(!ov&&(!center||center.children.length===0)&&!home);\n"
        + "        if(dead&&window.__dshHomeUrl&&location.href!==window.__dshHomeUrl){\n"
        + "          try{ location.replace(window.__dshHomeUrl); }catch(e){}\n"
        + "        }\n"
        + "      },260);\n"
        + "    });\n"
        // 打标**不防抖**：只改 class，不增删节点，所以不会被 childList 观察者捕获、
        // 不会像 sync() 那样自激。这样设置面板一插进 DOM 就带上 .dsh-s-ov，
        // 第一帧就是最终形态（否则会先以 dsh 原生 800px 宽度闪一下）。
        + "    if(window.MutationObserver){\n"
        + "      new MutationObserver(function(){tagHosts();syncOvFlag();})\n"
        + "        .observe(document.body,{childList:true,subtree:true});\n"
        + "    }\n"
        + "    bar.querySelector('#dsh-mbtn').addEventListener('click',function(e){\n"
        + "      e.preventDefault();e.stopPropagation();\n"
        + "      var b=document.body;\n"
        + "      if(b.classList.contains('dsh-drawer-open')){close();return;}\n"
        + "      try{expandSidebarOnce();}catch(err){}\n"
        + "      b.classList.add('dsh-drawer-open');\n"
        + "      var n=0;\n"
        + "      var iv=setInterval(function(){\n"
        + "        try{expandSidebarOnce();}catch(err){}\n"
        + "        if(++n>8){clearInterval(iv);}\n"
        + "      },140);\n"
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
        // ⚠️ 这个 `}` 是 mount() 的收尾，**必须在 if(!inMarket) 块之外**。
        //   若被 `if(!inMarket){` 抢占，后面所有代码都会落进条件块，
        //   末尾再补一个 } 就成了多余 -> "Unexpected token ')'"。
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