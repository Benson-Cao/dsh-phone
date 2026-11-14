// 侧栏「连接」入口 —— 把三块原生页接进官方 UI 的窄栏。
//
// 为什么需要它：配对页 / 离线页 / 切换器都做完了，但默认档案是 local，
// 而这三页的既有入口只在「远端档案启动」或「离线页里」才出现 ——
// 也就是说默认状态下用户**根本到不了**。一个功能没有入口等于没有。
//
// 边界（D-005 / BR-8）：这里只加**一个图标按钮**，不写任何页面、不重绘宿主组件、
// 不注入 CSS 去改布局。点击只是调用壳的桥去开原生 Activity，界面全在原生侧。
// 与 engine-switcher.js 同一套做法（那是既有先例，用户已接受）。
//
// 路由：
//   已配对且有令牌 → 切换器（列表里也能「重新配对」）
//   其余情况     → 配对页（首次配对、令牌被清、被收回后重配）
// 不自己判断「该连哪台」——那是切换器/离线页的职责，这里只把人送到对的页面。
(function () {
  if (window.__dshConnEntry) return;
  window.__dshConnEntry = true;

  var S = window.dshSaf;
  // relay 档是远端 origin，壳不注入任何桥；没有 dshSaf 就不要留下一个点不动的按钮
  if (!S || typeof S.openPairPage !== 'function') return;

  var BTN_ID = 'dsh-conn-rail-btn';
  var FAB_ID = 'dsh-conn-fab';

  // 链条图标：与「设置」「引擎」同一套线性风格，24x24 viewBox
  var ICON =
    '<svg viewBox="0 0 24 24" width="22" height="22" fill="none" aria-hidden="true">'
    + '<path d="M9.5 14.5a3.5 3.5 0 0 1 0-4.95l2.3-2.3a3.5 3.5 0 1 1 4.95 4.95l-1.2 1.2"'
    + ' stroke="currentColor" stroke-width="1.6" stroke-linecap="round"/>'
    + '<path d="M14.5 9.5a3.5 3.5 0 0 1 0 4.95l-2.3 2.3a3.5 3.5 0 1 1-4.95-4.95l1.2-1.2"'
    + ' stroke="currentColor" stroke-width="1.6" stroke-linecap="round"/>'
    + '</svg>';

  function parse(raw) {
    try { return typeof raw === 'string' ? JSON.parse(raw) : (raw || {}); }
    catch (e) { return {}; }
  }

  /** 已配对且令牌在 → 切换器；否则 → 配对页。 */
  function route() {
    var st = {};
    try { st = parse(S.pairStatus ? S.pairStatus() : {}); } catch (e) { st = {}; }
    if (st.paired && st.hasToken && typeof S.openSwitcherPage === 'function') {
      return S.openSwitcherPage();
    }
    return S.openPairPage();
  }

  function mount() {
    // 首选：官方窄栏底部（与「设置」「引擎」并列）。但 #dsh-rail-foot 只在**侧栏展开成
    // 图标态**时才由 official-bridge.js 创建 —— 真机实测 360px 窄屏下它并不存在，
    // 于是按「等侧栏出现」写的入口（包括既有的 engine-switcher）实际上点不到。
    // 所以必须有兜底：侧栏在就放侧栏，不在就放一个固定浮动入口。
    if (mountRail()) return true;
    return mountFab();
  }

  function mountRail() {
    var foot = document.getElementById('dsh-rail-foot');
    if (!foot || !foot.isConnected) return false;
    var existing = document.getElementById(BTN_ID);
    if (existing && existing.isConnected) return true;
    var btn = makeButton(BTN_ID);
    // 与「设置」并列：优先插在设置前，找不到就插在引擎按钮前，再不行就追加
    var gear = document.getElementById('dsh-rail-settings');
    var engine = document.getElementById('dsh-eng-rail-btn');
    if (gear && gear.parentNode === foot) foot.insertBefore(btn, gear);
    else if (engine && engine.parentNode === foot) foot.insertBefore(btn, engine);
    else foot.appendChild(btn);
    return true;
  }

  /**
   * 兜底：固定浮动入口。
   *
   * 边界说明：这不是「写皮肤」，只在窄栏且没有官方侧栏容器时出现一个 44px 圆钮，
   * 点它打开的是**原生页面**。不用它的话，三块原生页在默认 local 档下没有任何入口。
   */
  function mountFab() {
    if (document.getElementById(FAB_ID)) return true;
    var btn = makeButton(FAB_ID);
    btn.style.cssText = [
      'position:fixed',
      'right:14px',
      'bottom:96px',
      'width:44px',
      'height:44px',
      'z-index:2147483000',
      'display:flex',
      'align-items:center',
      'justify-content:center',
      'border-radius:22px',
      'border:1px solid rgba(127,127,127,.45)',
      'background:rgba(127,127,127,.22)',
      'color:inherit',
      'backdrop-filter:none',
      'padding:0'
    ].join(';');
    document.body.appendChild(btn);
    return true;
  }

  function makeButton(id) {
    var btn = document.createElement('button');
    btn.id = id;
    btn.type = 'button';
    btn.setAttribute('aria-label', '连接电脑');
    btn.setAttribute('title', '连接电脑 / 切换主机');
    btn.innerHTML = ICON;
    btn.addEventListener('click', function (ev) {
      ev.stopPropagation();
      try { route(); } catch (e) { }
    });
    return btn;
  }

  // #dsh-rail-foot 由 official-bridge.js 在窄栏态挂载；React 重渲染会重建它，
  // 所以照官方脚本的复查节奏（600ms）重挂，隐藏标签页时不跑。
  var mounted = mount();
  setInterval(function () {
    if (document.hidden) return;
    try { mounted = mount() || mounted; } catch (e) { }
  }, 600);
})();
