// 顶部「主机胶囊」—— 设计稿 p1 的这一件（只做胶囊，不做底部导航）。
//
// 为什么要它：人在远程档案里时，屏幕上唯一能说明「我现在连的是哪台机器」的地方，
// 是侧栏里那两枚图标按钮 —— 而按钮上只有图标，既没有主机名也没有 activePath。
// 多台电脑同名/相近时，用户只能靠猜。
//
// 显示规则（用户拍板，r143）：
//   · **只在首页显示**。判定不用 URL（官方 SPA 切视图不改地址，r142 的 URL 判定
//     在会话页照样显示），改用 DOM 标记：首页有固定欢迎语「探索未至之境」。
//   · 抽屉/全屏覆盖层打开时隐藏 —— 胶囊压在抽屉标题上既丑又点不到正确目标。
//     判定：胶囊正下方（把它自己临时 pointer-events:none 后取 elementFromPoint）
//     的祖先链里存在「定位元素且覆盖超过半屏」→ 认为有覆盖层。
//   · 样式无填充（透明底 + ink 描边与文字）。
//     r148：高度改为**与左侧按钮可见方框等高（14px）**，不再照官方 iconButton
//     的整体 box（28×28）做 —— 那个 box 含透明内边距，胶囊跟着它会比左边方框
//     高出一倍（真机原始帧实测：胶囊描边 97 物理 px，左方框 49 物理 px）。
//     top 取 18px 让胶囊中心与左方框中心重合（内容区坐标，两者中心均 ~25px）。
//
// 边界（与原 connection-entry.js 同一套纪律）：
//   · 只画**一个胶囊**，不改官方任何布局/CSS，不重绘宿主组件；
//   · 点击只调壳的桥去开原生页，页面全在原生侧；
//   · 桥不可用（relay 档是远端 origin，壳根本不注入）时**什么都不做**。
(function () {
  if (window.__dshHostPill) return;
  window.__dshHostPill = true;

  var S = window.dshSaf;
  if (!S || typeof S.shellStatus !== 'function') return;

  var ID = 'dsh-host-pill';
  // 300px 以下不再画：那种宽度下任何固定元素都会压住官方内容
  var MIN_W = 300;

  var state = { label: '', kind: '', paired: false };
  // 过渡窗口内会 60ms 重判一次，内容没变就不要重写 innerHTML（否则会闪、也会打断点击）。
  var painted = '';

  function parse(raw) {
    try { return typeof raw === 'string' ? JSON.parse(raw) : (raw || {}); }
    catch (e) { return {}; }
  }

  function esc(s) {
    return String(s == null ? '' : s)
      .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;');
  }

// ⚠️ box-sizing:border-box 必须有：默认 content-box 会把 1px 边框加到 height 之外，
// 「height:30」实际渲染 32 —— r142 真机截图里胶囊比官方 28px 图标高一截就是这个。
// r148：h=14、top=18 都是相对 WebView 内容区的 CSS px（真机 density 3.5）。
// 绿点用 #22C55E：深/浅两种底上都清晰，且与应用内「在线/可用」语义一致；
// 两种主题共用同一条规则，不再在深色 media 里覆盖圆点颜色。
  var CSS = ''
    + '#dsh-host-pill{position:fixed;z-index:2147483000;'
    + 'box-sizing:border-box!important;height:14px!important;min-height:0!important;'
    + 'top:calc(18px + env(safe-area-inset-top,0px));'
    + 'left:calc(42px + env(safe-area-inset-left,0px));'
    + 'display:inline-flex;align-items:center;gap:5px;'
    + 'max-width:180px;padding:0 8px 0 6px;border-radius:999px;'
    + 'background:transparent;border:1px solid #18181B;color:#18181B;'
    + 'font:700 9px/1 system-ui,-apple-system,"Segoe UI",sans-serif;'
    + '-webkit-tap-highlight-color:transparent;cursor:pointer}'
    + '#dsh-host-pill .hp-dot{width:4px;height:4px;border-radius:50%;'
    + 'background:#22C55E;flex:0 0 4px}'
    + '#dsh-host-pill .hp-tag{font:700 8px/1 ui-monospace,Menlo,monospace;'
    + 'opacity:.7;letter-spacing:.3px;padding-right:5px;'
    + 'border-right:1px solid rgba(24,24,27,.34)}'
    + '#dsh-host-pill .hp-name{min-width:0;overflow:hidden;text-overflow:ellipsis;'
    + 'white-space:nowrap}'
    + '#dsh-host-pill .hp-chev{flex:0 0 7px;opacity:.82;font-size:9px}'
    + '#dsh-host-pill:active{background:#F4F4F5}'
    + '@media (prefers-color-scheme:dark){'
    + '#dsh-host-pill{border-color:#F4F4F5;color:#F4F4F5}'
    + '#dsh-host-pill .hp-tag{border-right-color:rgba(244,244,245,.34)}'
    + '#dsh-host-pill:active{background:#232327}}';

  function style() {
    if (document.getElementById('dsh-host-pill-css')) return;
    var st = document.createElement('style');
    st.id = 'dsh-host-pill-css';
    st.textContent = CSS;
    (document.head || document.documentElement).appendChild(st);
  }

  function el() { return document.getElementById(ID); }

  /** 官方 SPA 切视图不改地址，首页判定只能靠 DOM：欢迎语是首页固定文案。 */
  function onHome() {
    try {
      return (document.body.innerText || '').indexOf('探索未至之境') >= 0;
    } catch (e) { return false; }
  }

  /**
   * 胶囊正下方是否被全屏覆盖层（抽屉/弹层）压着。
   * 把胶囊临时 pointer-events:none，取 elementFromPoint，沿祖先链找
   * 「定位元素（fixed/absolute/sticky）且覆盖超过半屏高」—— 官方抽屉就是这种形态。
   */
  function coveredByOverlay() {
    var node = el();
    if (!node) return false;
    var r = node.getBoundingClientRect();
    // r147：节点被我们隐藏（display:none）时 getBoundingClientRect() 全为 0，
    // 采样点会落到 (10,0) 这个毫无意义的位置，抽屉已收起也可能被判成仍被压住。
    // 隐藏态改用 CSS 里的固定几何（left:42px / top:10px / height:28px）。
    var cx, cy;
    if (r.width > 0 && r.height > 0) { cx = r.left + 10; cy = r.top + r.height / 2; }
    else { cx = 52; cy = 25; }  // r148：隐藏态按 h=14 / top=18 的几何取中心
    var saved = node.style.pointerEvents;
    node.style.pointerEvents = 'none';
    var under = null;
    try { under = document.elementFromPoint(cx, cy); }
    finally { node.style.pointerEvents = saved; }
    if (!under || node === under || node.contains(under)) return false;
    var n = under;
    while (n && n !== document.body) {
      var cs, box;
      try { cs = getComputedStyle(n); } catch (e) { return false; }
      if (cs.position === 'fixed' || cs.position === 'absolute' || cs.position === 'sticky') {
        box = n.getBoundingClientRect();
        // r147：抽屉收起是靠 transform 平移出屏幕 / opacity 淡出。仅看「高度超过半屏」
        // 会把一个已经移走或全透明的覆盖层继续当成遮挡，胶囊就一直不出现。
        // 现在要求盒子真的盖住采样点，并且是可见的。
        var op = parseFloat(cs.opacity);
        if (isNaN(op)) op = 1;
        if (cs.visibility !== 'hidden' && op > 0.05
            && box.height > window.innerHeight * 0.5
            && box.left <= cx && box.right >= cx
            && box.top <= cy && box.bottom >= cy) return true;
      }
      n = n.parentElement;
    }
    return false;
  }

  function render() {
    var node = el();
    if (!node) return;
    var show = state.label && onHome() && !coveredByOverlay();
    if (!show) { node.style.display = 'none'; return; }
    node.style.display = 'inline-flex';
    var tag = state.kind ? '<span class="hp-tag">' + esc(state.kind) + '</span>' : '';
    var html = '<span class="hp-dot"></span>' + tag
      + '<span class="hp-name">' + esc(state.label) + '</span>'
      + '<span class="hp-chev">›</span>';
    if (painted !== html) { node.innerHTML = html; painted = html; }
    node.title = '当前连接：' + state.label + '（点击进入连接页面）';
  }

  function tap() {
    try {
      if (state.paired && typeof S.openSwitcherPage === 'function') S.openSwitcherPage();
      else S.openPairPage();
    } catch (e) { }
  }

  function mount() {
    if (el()) return;
    if (window.innerWidth < MIN_W) return;
    style();
    var node = document.createElement('div');
    node.id = ID;
    node.setAttribute('role', 'button');
    node.addEventListener('click', tap);
    // 挂在 body 上而不是官方 appbar 里：官方是 SPA，appbar 会随路由整块重建，
    // 挂进去的元素下一次渲染就没了；固定定位则不受重建影响。
    (document.body || document.documentElement).appendChild(node);
    painted = '';   // 新节点是空的，缓存必须作废，否则会画出一颗没有文字的胶囊
    render();
  }

  function refresh() {
    try {
      state = parse(S.shellStatus());
      if (!state || !state.label) state = { label: '', kind: '', paired: false };
    } catch (e) {
      state = { label: '', kind: '', paired: false };
    }
    // SPA 重建 body 时我们的节点会丢，这里顺手补回来（比 MutationObserver 便宜）
    if (!el()) mount();
    render();
  }

  mount();
  refresh();
  setInterval(refresh, 8000);

  // ---- r147：抽屉收起后胶囊「要等一会才出现」的修复 ----
  // 根因：以前只观察 childList，而官方抽屉开合改的是 class / style（属性变化），
  // 且带 CSS 过渡动画 —— 动画期间 render() 仍判 covered，动画结束又没有新的 DOM
  // 变化触发重判，于是只能等下一次 8 秒的 setInterval(refresh) 才画出来。
  // 三处补齐：① 属性变化也观察；② 过渡窗口内连续重判；③ 过渡事件直接重判。
  var moTimer = null, attrTimer = null, settleTimer = null;

  /** 过渡窗口：60ms 一判，最多 1.2s；抽屉一让开就立刻把胶囊画回来。 */
  function settle() {
    if (settleTimer) return;
    var until = Date.now() + 1200;
    settleTimer = setInterval(function () {
      render();
      if (Date.now() >= until) { clearInterval(settleTimer); settleTimer = null; }
    }, 60);
  }

  try {
    new MutationObserver(function (records) {
      var attr = false;
      for (var i = 0; i < records.length; i++) {
        if (records[i].type === 'attributes') { attr = true; break; }
      }
      if (attr) {
        // 抽屉开合这类属性变化要快：60ms 节流即可，聊天流式输出的 class 抖动也压得住。
        if (attrTimer) return;
        attrTimer = setTimeout(function () {
          attrTimer = null; render(); settle();
        }, 60);
        return;
      }
      // 子节点变化仍走 400ms 节流（流式输出 mutation 很密）。
      if (moTimer) return;
      moTimer = setTimeout(function () { moTimer = null; render(); settle(); }, 400);
    }).observe(document.body, {
      childList: true, subtree: true,
      attributes: true, attributeFilter: ['class', 'style']
    });
  } catch (e) { }

  // 过渡/动画结束是「抽屉彻底让开」的准确时刻，不依赖后面还有没有 DOM 变化。
  ['transitionend', 'animationend'].forEach(function (evt) {
    document.addEventListener(evt, function () { render(); settle(); }, true);
  });

  document.addEventListener('visibilitychange', function () {
    if (!document.hidden) refresh();
  });
  window.addEventListener('resize', function () {
    if (window.innerWidth < MIN_W) {
      var n = el();
      if (n) n.remove();
    } else mount();
  });
})();
