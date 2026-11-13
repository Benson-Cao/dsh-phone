/**
 * 官方 WebView 外壳（v39）。
 *
 * 用户最终裁定：「去掉我们自己写的 UI 布局样式，只保留官方的」。
 * 官方 0.2.2 自带移动端 frame（pI_x6G_* / data-mobile-nav="frame"）与响应式
 * 布局，此前 r173~r225 叠的所有布局补丁（短视口 ensureCss、图标窄栏 rail、
 * 抽屉位移 syncLayout/syncDrawerShift、隐藏内部目录、鲸鱼图标、更多卡片宽度…）
 * 与官方 frame 互相打架，实测把首页压成白屏。v37 一次性全部删除。
 *
 * ⚠️ v38 保留唯一一条**官方 CSS 内部矛盾**的和解规则（不是自绘 UI）：
 *   官方布局 CSS（dsh-client-ui-layout）声明 .pI_x6G_centerCol { grid-area: 1/2 }
 *   （中间列在第 2 格，第 1 格留给 sidebarCol，收起时官方 JS 把第 1 列压成 0px）；
 *   而官方 dsh-web-mobile 插件（rev 04e0ff15289b）的移动档媒体查询
 *   (max-width:1023px)+(pointer:coarse) 把 frame 强改为
 *   `grid-template-columns: minmax(0,1fr) 0 0 !important` —— 这是按旧版布局
 *   （中间列在第 1 格）写的。两者组合后 1fr 全宽的第 1 格被 sidebarCol 占据，
 *   centerCol 落在 0px 的第 2 格 → 首页整页白屏（真机 Chrome/151 WebView 实测，
 *   CDP 取证：centerCol 0x740@x=360/764，页面只剩 frame+overlay+鲸鱼宠物）。
 *   和解：同一媒体查询下把 centerCol 拉回第 1 格 —— 这正是插件期望的
 *   「覆盖式抽屉 + 全宽内容」布局；官方 grid-area 语义不破坏（sidebarCol
 *   在抽屉态仍是绝对定位覆盖层）。实拍验证 gridColumnStart=1 后内容立即
 *   正常渲染（360x740 全宽，文案/输入框齐全）。
 * ⚠️ v39：和解规则改为**按需注入** —— 只有页面上真的存在 dsh-web-mobile
 *   插件的 CSS（关键帧名 dsh-web-mobile-* 作指纹）才注入；插件停用/卸载后
 *   自动摘除，回到 100% 纯官方布局。
 *
 * 除这一条外，本脚本只剩三件与样式无关的事：
 *
 *   ① 返回键状态上报：页面上存在**可见的** role=dialog 时上报
 *      setBackHandled(true)，让原生返回走 JS（关对话框）；否则原生按
 *      history.back() 处理，与浏览器行为一致。
 *   ② 主题跟随：持续采样页面真实深浅色，变化才过桥（系统栏颜色跟着变）。
 *   ③ 系统深色兜底（r214）：WebView 里 prefers-color-scheme 不可靠，
 *      页面该深却没深时补官方自己的 `body[data-ds-dark-theme]` 标记，
 *      用的是 dsh 主题包现成的 CSS 规则，不引入任何颜色字面量。
 *
 * ⚠️ 勿再加回任何布局/CSS 补丁 —— 需要改布局时优先怀疑官方 frame 本身
 *   是否正常加载，而不是叠补丁。
 */
(function () {
  if (window.__dshOfficialBridge) { return; }
  window.__dshOfficialBridge = 1;

  /** 当前是否有用户可见的对话框（几何 + computed style 双证据，避免误判）。 */
  function visibleDialog() {
    var list = document.querySelectorAll('[role="dialog"]');
    for (var i = 0; i < list.length; i++) {
      var el = list[i];
      try {
        var r = el.getBoundingClientRect();
        if (r.width < 40 || r.height < 40) { continue; }
        var st = window.getComputedStyle(el);
        if (st.visibility === 'hidden' || st.display === 'none') { continue; }
        if (parseFloat(st.opacity || '1') < 0.05) { continue; }
        return el;
      } catch (e) { /* 过渡窗口量不出来：按不可见处理，下一轮再试 */ }
    }
    return null;
  }

  var lastReported = null;
  function syncBackState() {
    var now = !!visibleDialog();
    if (now === lastReported) { return; }
    lastReported = now;
    try {
      if (window.dshNative && window.dshNative.setBackHandled) {
        window.dshNative.setBackHandled(now);
      }
    } catch (e) { }
  }

  // 原生返回键在 jsHandled=true 时调用它。
  window.__dshBack = function () {
    if (visibleDialog()) {
      try { document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true })); } catch (e) { }
      return;
    }
    try { window.history.back(); } catch (e) { }
  };

  /* ---- 主题跟随 ---------------------------------------------------------
   * dsh 的深浅色存在 ui-theme.preference 里。用户改完主题返回时 WebView
   * 不会 reload，系统栏会停在旧颜色。这里持续采样页面的真实深浅色，
   * **变化才过桥**（JS 侧去重，不会打爆桥）。
   *
   * 判据优先级：权威属性 > body 背景亮度。
   *   ① body/html 的 data-ds-dark-theme 是 dsh 官方深色标记（权威信号）；
   *   ② 没有标记时按背景亮度算 —— 比 prefers-color-scheme 准，因为
   *      「dsh 选浅色 + 系统深色」时 matchMedia 会返回 dark，但页面其实是浅的。
   */
  var lastDark = null;

  function themeIsDark() {
    var h = document.documentElement, b = document.body;
    if (!h || !b) { return null; }
    if (b.hasAttribute('data-ds-dark-theme') || h.hasAttribute('data-ds-dark-theme')
        || h.classList.contains('dark') || b.classList.contains('dark')) { return true; }
    if (b.hasAttribute('data-ds-light-theme') || h.hasAttribute('data-ds-light-theme')) { return false; }
    try {
      var bg = window.getComputedStyle(b).backgroundColor || '';
      var m = /rgba?\((\d+)[,\s]+(\d+)[,\s]+(\d+)/.exec(bg);
      if (!m) { return null; }
      var lum = 0.299 * parseInt(m[1], 10) + 0.587 * parseInt(m[2], 10) + 0.114 * parseInt(m[3], 10);
      return lum < 128;
    } catch (e) { return null; }
  }

  function syncTheme() {
    var d = themeIsDark();
    if (d === null || d === lastDark) { return; }
    lastDark = d;
    try {
      if (window.dshNative && window.dshNative.applyDshTheme) {
        window.dshNative.applyDshTheme(d);
      }
    } catch (e) { /* 桥未就绪：下一轮再试 */ }
  }

  /* ---- 系统深色兜底 ------------------------------------------------------
   * 真机取证：dsh 主 WebView 里 matchMedia('(prefers-color-scheme: dark)')
   * 可能返回 false（媒体查询在 WebView 里不可靠），dsh 的
   * ui-theme.preference=system 会被解析成浅色 → 首页整页白。
   *
   * 原生知道自己所在的系统配色、也读得到 dsh 的主题偏好
   * （DshBridge.dshShouldBeDark，只读内存缓存、同步无 IO）。这里每 500ms
   * 比对一次：页面该深却没深，就补上官方自己的深色标记
   * `body[data-ds-dark-theme]`；该浅却还深，就摘掉。用的是 dsh 主题包里
   * **现成的** CSS 规则，不引入任何颜色字面量。
   *
   * ⚠️ 幂等：属性一致就立刻 return，不产生 DOM 写入，不会和主题运行时的
   *   publish() 打架。
   */
  function enforceNativeTheme() {
    var want;
    try {
      if (!window.dshNative || !window.dshNative.dshShouldBeDark) { return; }
      want = !!window.dshNative.dshShouldBeDark();
    } catch (e) { return; }
    var h = document.documentElement, b = document.body;
    if (!h || !b) { return; }
    var onB = b.hasAttribute('data-ds-dark-theme');
    var onH = h.hasAttribute('data-ds-dark-theme');
    if (want) {
      if (!onB) { b.setAttribute('data-ds-dark-theme', ''); }
    } else if (onB || onH) {
      b.removeAttribute('data-ds-dark-theme');
      h.removeAttribute('data-ds-dark-theme');
    }
  }

  /* ---- 官方 CSS 内部矛盾的和解（v38，唯一一条样式规则）------------------
   * 见文件头注释。只动 grid 落位，不画任何图形/颜色/间距。
   * 选择器用 [class*="_centerCol"] 片段匹配，防官方哈希类漂移；
   * 挂在 html 前缀提高优先级，配合 !important 压过插件同源的 media 规则。
   */
  function pluginMobileCssPresent() {
    for (var i = 0; i < document.styleSheets.length; i++) {
      var rules;
      try { rules = document.styleSheets[i].cssRules; } catch (e) { continue; }
      if (!rules) { continue; }
      for (var k = 0; k < rules.length; k++) {
        var t = rules[k].cssText || '';
        if (t.indexOf('dsh-web-mobile') >= 0) { return true; }
      }
    }
    return false;
  }

  function ensureFrameCompatCss() {
    var want = pluginMobileCssPresent();
    var existing = document.getElementById('dsh-frame-compat-css');
    if (!want) {
      if (existing) { existing.parentNode.removeChild(existing); }
      return;
    }
    if (existing) { return; }
    var head = document.head || document.documentElement;
    if (!head) { return; }
    var st = document.createElement('style');
    st.id = 'dsh-frame-compat-css';
    st.textContent = [
      '@media (max-width: 1023px) and (pointer: coarse) {',
      '  html [data-mobile-nav="frame"] > [class*="_centerCol"] {',
      '    grid-column-start: 1 !important;',
      '    grid-row-start: 1 !important;',
      '  }',
      '}'
    ].join('\n');
    head.appendChild(st);
  }

  setInterval(function () { enforceNativeTheme(); syncBackState(); syncTheme(); ensureFrameCompatCss(); }, 500);
  ensureFrameCompatCss();
  syncBackState();
  enforceNativeTheme();   // 注入即纠正，不等 500ms 心跳（否则先白一屏）
  setTimeout(function () { enforceNativeTheme(); syncTheme(); syncBackState(); }, 400);
})();
