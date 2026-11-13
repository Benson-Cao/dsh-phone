// 三引擎切换 UI（独立注入，不改动 official-bridge.js 一个字节）。
//
// r228：按设计稿改成「分组卡片」语言 —— 圆角 14px 的卡片、卡片间 10px 间距、
// 近黑底、行内图标（显示器 / 云）、分段控件选类型。整页仍然是整页：
// 点窄栏图标 → 铺满视口，左侧返回箭头，绝不跳转到独立 URL。
//
// 结构：两页。
//   · 列表页「引擎」：提示 + 引擎卡片 + 「+ 添加端点」描边卡 + 脚注
//   · 添加页「添加端点」：名称/地址卡片 + 类型分段 + 保存（列表只在列表页）
//
// 样式纪律：不写死主题色，一律 --dsw-alias-* token，缺失才用深色兜底；
// 不重绘宿主组件，只在窄栏底部容器 #dsh-rail-foot 里加一个与「设置」并列的按钮。
//
// 返回键：#dsh-eng-root 带 role="dialog"，official-bridge 的 syncBackState()
// 会因此上报 setBackHandled(true)，原生返回键改走 __dshBack() → 派发 Escape，
// 这里监听 Escape 关闭（添加页先退回列表），与 App 内其他页面一致。
//
// 数据经 window.dshNative.engine* 读写（App 侧 EngineEndpoints）。
(function () {
  if (window.__dshEngineSwitcher) return;
  window.__dshEngineSwitcher = true;

  var N = window.dshNative;
  if (!N || typeof N.engineList !== 'function') return;

  var ID = 'dsh-eng-root';
  var RAIL_BTN_ID = 'dsh-eng-rail-btn';
  if (document.getElementById(ID)) return;

  function parse(raw) {
    try { return typeof raw === 'string' ? JSON.parse(raw) : (raw || {}); }
    catch (e) { return { ok: false, error: String(e) }; }
  }
  function esc(s) {
    return String(s == null ? '' : s).replace(/[&<>"']/g, function (c) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
    });
  }

  function svg(inner, size) {
    return '<svg width="' + (size || 20) + '" height="' + (size || 20) + '" viewBox="0 0 24 24" '
      + 'fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" '
      + 'stroke-linejoin="round" aria-hidden="true">' + inner + '</svg>';
  }

  var ENGINE_ICON = svg('<rect x="2" y="3" width="20" height="7" rx="2"/>'
    + '<rect x="2" y="14" width="20" height="7" rx="2"/><path d="M6 6.5h.01M6 17.5h.01"/>', 18);
  var BACK_ICON = svg('<path d="M15 5l-7 7 7 7"/>', 22);
  var CHECK_ICON = svg('<path d="M20 6L9 17l-5-5"/>', 18);
  var DOT_ICON = svg('<circle cx="12" cy="12" r="7"/>', 18);
  var DESKTOP_ICON = svg('<rect x="3" y="4" width="18" height="12" rx="2"/><path d="M8 20h8M12 16v4"/>', 22);
  var CLOUD_ICON = svg('<path d="M17.5 19a4.5 4.5 0 0 0 .5-8.97A6 6 0 0 0 6.2 9.2 3.9 3.9 0 0 0 7 19h10.5Z"/>', 22);
  var LOCAL_ICON = svg('<rect x="4" y="3" width="16" height="12" rx="2"/><path d="M2 19h20"/>', 22);

  /* 设计稿取值 → token 兜底。
     卡面 #17181c / 页底 #0b0b0d / 分隔 rgba(255,255,255,.06)
     选中段 #1b2b3d + 描边 #2f6fb0 / 保存 #1d3550 / 链接 #4d8fd6 */
  var css = ''
    + '#' + ID + '{position:fixed!important;top:0!important;right:0!important;bottom:0!important;left:0!important;'
    + 'width:auto!important;height:auto!important;margin:0!important;padding:0!important;'
    + 'border:0!important;border-radius:0!important;transform:none!important;'
    + 'z-index:2147483000!important;display:none;'
    + 'background:var(--dsw-alias-bg-base,#0b0b0d);color:var(--dsw-alias-label-primary,#eceef1);'
    + 'font:15px/1.45 -apple-system,BlinkMacSystemFont,system-ui,sans-serif;'
    + 'overscroll-behavior:contain;-webkit-tap-highlight-color:transparent}'
    + '#' + ID + '.on{display:block}'
    + '#' + ID + ' .dsh-eng-page{position:absolute!important;top:0!important;left:0!important;'
    + 'right:0!important;bottom:0!important;display:flex!important;flex-direction:column;'
    + 'padding-top:env(safe-area-inset-top,0px);padding-bottom:env(safe-area-inset-bottom,0px)}'
    + '#' + ID + ' .dsh-eng-page[hidden]{display:none!important}'

    // 头部：只留返回箭头 + 标题，跟设计稿一样不加分隔线。
    + '#' + ID + ' .dsh-eng-head{flex:none;display:flex;align-items:center;gap:4px;min-height:54px;padding:0 10px}'
    + '#' + ID + ' .dsh-eng-back{display:flex;align-items:center;justify-content:center;width:40px;height:40px;'
    + 'padding:0;border:0;border-radius:10px;background:none;'
    + 'color:var(--dsw-alias-label-primary,#eceef1);cursor:pointer}'
    + '#' + ID + ' .dsh-eng-back:active{background:var(--dsw-alias-interactive-bg-hover,rgba(255,255,255,.07))}'
    + '#' + ID + ' .dsh-eng-title{margin:0;font-size:19px;font-weight:600;letter-spacing:.1px}'

    // 内容：左右 16px，卡片之间 10px。
    + '#' + ID + ' .dsh-eng-body{flex:1;overflow-y:auto;-webkit-overflow-scrolling:touch;padding:4px 16px 28px}'
    + '#' + ID + ' .dsh-eng-hint{margin:2px 0 14px;font-size:13px;line-height:1.6;'
    + 'color:var(--dsw-alias-label-tertiary,#8a8f98)}'

    // 卡片：圆角 14、无阴影，靠底色和极浅描边分层。
    + '#' + ID + ' .dsh-eng-card{box-sizing:border-box;width:100%;display:block;text-align:left;'
    + 'background:var(--dsw-alias-bg-layer-1,#17181c);'
    + 'border:1px solid var(--dsw-alias-border-l2,rgba(255,255,255,.06));'
    + 'border-radius:14px;padding:13px 16px;margin:0 0 10px;color:inherit;'
    + 'font:inherit;cursor:pointer}'
    + '#' + ID + ' .dsh-eng-card:last-of-type{margin-bottom:0}'
    + '#' + ID + ' .dsh-eng-card:active{background:var(--dsw-alias-interactive-bg-hover,rgba(255,255,255,.05))}'

    // 引擎行：图标 + 名称/地址 + 删除。
    + '#' + ID + ' .dsh-eng-rowCard{display:flex;align-items:center;gap:13px}'
    + '#' + ID + ' .dsh-eng-icon{flex:none;display:flex;align-items:center;justify-content:center;'
    + 'width:26px;color:var(--dsw-alias-label-secondary,#b9bec7)}'
    + '#' + ID + ' .dsh-eng-rowCard.cur .dsh-eng-icon{color:var(--dsw-alias-state-business-primary,#4d8fd6)}'
    + '#' + ID + ' .dsh-eng-main{flex:1;min-width:0}'
    + '#' + ID + ' .dsh-eng-label{display:block;font-size:16px;font-weight:500;'
    + 'overflow:hidden;text-overflow:ellipsis;white-space:nowrap}'
    + '#' + ID + ' .dsh-eng-sub{display:block;margin-top:2px;font-size:12.5px;'
    + 'color:var(--dsw-alias-label-tertiary,#8a8f98);word-break:break-all}'
    + '#' + ID + ' .dsh-eng-del{flex:none;border:0;background:none;padding:8px 2px;font:inherit;'
    + 'font-size:13.5px;color:var(--dsw-alias-state-error-primary,#d4736f);cursor:pointer}'
    + '#' + ID + ' .dsh-eng-badge{flex:none;font-size:11px;padding:2px 7px;border-radius:6px;'
    + 'color:var(--dsw-alias-state-business-primary,#4d8fd6);'
    + 'background:var(--dsw-alias-interactive-bg-hover-accent,rgba(77,143,214,.14))}'

    // 「+ 添加端点」：描边卡，蓝字。
    + '#' + ID + ' .dsh-eng-addcard{display:flex;align-items:center;gap:8px;width:100%;box-sizing:border-box;'
    + 'margin:10px 0 0;padding:14px 16px;border-radius:14px;font:inherit;font-size:15.5px;cursor:pointer;'
    + 'border:1px solid var(--dsw-alias-state-business-primary,#2f6fb0);'
    + 'background:var(--dsw-alias-interactive-bg-hover-accent,rgba(47,111,176,.10));'
    + 'color:var(--dsw-alias-state-business-primary,#6ea8e0);text-align:left}'
    + '#' + ID + ' .dsh-eng-addcard:active{background:rgba(47,111,176,.2)}'

    // 表单卡：标签在上，输入框透明无边框。
    + '#' + ID + ' .dsh-eng-field{display:block;cursor:default}'
    + '#' + ID + ' .dsh-eng-field > span{display:block;font-size:12.5px;'
    + 'color:var(--dsw-alias-label-tertiary,#8a8f98)}'
    + '#' + ID + ' .dsh-eng-field input{display:block;width:100%;box-sizing:border-box;margin-top:6px;'
    + 'padding:0;border:0;background:none;font:inherit;font-size:15.5px;'
    + 'color:var(--dsw-alias-label-primary,#eceef1);outline:none}'
    + '#' + ID + ' .dsh-eng-field input::placeholder{color:var(--dsw-alias-label-tertiary,#5d626b)}'

    // 类型：标签在卡片外，分段按钮等宽。
    + '#' + ID + ' .dsh-eng-sectionLabel{margin:18px 2px 10px;font-size:13px;'
    + 'color:var(--dsw-alias-label-tertiary,#8a8f98)}'
    + '#' + ID + ' .dsh-eng-seg{display:flex;gap:10px}'
    + '#' + ID + ' .dsh-eng-seg button{flex:1;height:46px;border-radius:12px;font:inherit;font-size:15px;'
    + 'cursor:pointer;border:1px solid transparent;'
    + 'background:var(--dsw-alias-bg-layer-1,#17181c);'
    + 'color:var(--dsw-alias-label-secondary,#c6cad1)}'
    + '#' + ID + ' .dsh-eng-seg button[aria-pressed="true"]{'
    + 'border-color:var(--dsw-alias-state-business-primary,#2f6fb0);'
    + 'background:var(--dsw-alias-interactive-bg-hover-accent,#1b2b3d);'
    + 'color:var(--dsw-alias-label-primary,#cfe3f7)}'

    // 保存：整宽主按钮。
    + '#' + ID + ' .dsh-eng-save{width:100%;box-sizing:border-box;height:46px;margin:18px 0 0;'
    + 'border:0;border-radius:12px;font:inherit;font-size:16px;font-weight:500;cursor:pointer;'
    + 'background:var(--dsw-alias-button-primary-fill,#1d3550);'
    + 'color:var(--dsw-alias-label-primary-inverted,#eaf2fb)}'
    + '#' + ID + ' .dsh-eng-save:active{filter:brightness(1.12)}'

    + '#' + ID + ' .dsh-eng-note{margin:18px 2px 0;font-size:12px;line-height:1.75;'
    + 'color:var(--dsw-alias-label-tertiary,#6d727b)}'

    // 轻提示：贴底、圆角、用完即走。
    + '#' + ID + ' .dsh-eng-msg{position:absolute;left:16px;right:16px;'
    + 'bottom:calc(18px + env(safe-area-inset-bottom,0px));padding:11px 15px;border-radius:12px;'
    + 'font-size:13.5px;opacity:0;transform:translateY(6px);pointer-events:none;'
    + 'transition:opacity .16s ease,transform .16s ease;'
    + 'background:var(--dsw-alias-toast-bg,#1c1e23);color:var(--dsw-alias-toast-label,#eceef1);'
    + 'box-shadow:0 8px 24px rgba(0,0,0,.45)}'
    + '#' + ID + ' .dsh-eng-msg.on{opacity:1;transform:translateY(0)}'
    + '#' + ID + ' .dsh-eng-msg.bad{color:var(--dsw-alias-state-error-primary,#d4736f)}'
    + '@media (prefers-reduced-motion:reduce){#' + ID + ' .dsh-eng-msg{transition:none}}'

    // 窄栏按钮：沿用 #dsh-rail-foot > button 的尺寸规则，只补选中态。
    + '#' + RAIL_BTN_ID + '[data-on="1"]{background:rgba(77,143,214,.18) !important}'

    // 宽屏 / 分屏没有窄栏时的同尺寸图标兜底（8s 后仍找不到才出现）。
    + '#' + ID + ' .dsh-eng-btn{display:none;position:fixed;left:12px;bottom:14px;'
    + 'width:36px;height:36px;align-items:center;justify-content:center;padding:0;border:0;'
    + 'border-radius:10px;background:var(--dsw-alias-bg-layer-2,rgba(255,255,255,.08));'
    + 'color:var(--dsw-alias-label-secondary,#b9bec7);cursor:pointer;z-index:2147483001}'
    + '#' + ID + '.fallback .dsh-eng-btn{display:flex}';

  var style = document.createElement('style');
  style.id = 'dsh-eng-css';
  style.textContent = css;
  (document.head || document.documentElement).appendChild(style);

  var root = document.createElement('div');
  root.id = ID;
  root.setAttribute('role', 'dialog');
  // 不设 aria-modal：某些宿主的模态样式会连带改布局，这里只需要 role 拿返回键
  root.setAttribute('aria-label', '引擎');
  root.innerHTML =
    // ---------- 列表页 ----------
      '<div class="dsh-eng-page" data-page="list">'
    + '  <div class="dsh-eng-head">'
    + '    <button class="dsh-eng-back" type="button" aria-label="返回" data-act="close">' + BACK_ICON + '</button>'
    + '    <h1 class="dsh-eng-title">引擎</h1>'
    + '  </div>'
    + '  <div class="dsh-eng-body">'
    + '    <p class="dsh-eng-hint">选中哪个，App 就接入哪个引擎；只换执行者，不迁移会话。</p>'
    + '    <div class="dsh-eng-list"></div>'
    + '    <button class="dsh-eng-addcard" type="button" data-act="to-add">＋ 添加端点</button>'
    + '    <p class="dsh-eng-note">跨引擎历史依赖 NAS 同步。入口在窄栏侧边栏、设置按钮上方。</p>'
    + '  </div>'
    + '</div>'
    // ---------- 添加页 ----------
    + '<div class="dsh-eng-page" data-page="add" hidden>'
    + '  <div class="dsh-eng-head">'
    + '    <button class="dsh-eng-back" type="button" aria-label="返回" data-act="to-list">' + BACK_ICON + '</button>'
    + '    <h1 class="dsh-eng-title">添加端点</h1>'
    + '  </div>'
    + '  <div class="dsh-eng-body">'
    + '    <label class="dsh-eng-card dsh-eng-field"><span>名称</span>'
    + '      <input class="dsh-eng-name" type="text" placeholder="书房 5060Ti / 腾讯云 dsh" autocomplete="off" /></label>'
    + '    <label class="dsh-eng-card dsh-eng-field"><span>地址</span>'
    + '      <input class="dsh-eng-url" type="url" inputmode="url" placeholder="http://192.168.1.10:3080" autocomplete="off" /></label>'
    + '    <div class="dsh-eng-sectionLabel">类型</div>'
    + '    <div class="dsh-eng-seg">'
    + '      <button type="button" data-kind="pc" aria-pressed="true">电脑</button>'
    + '      <button type="button" data-kind="server" aria-pressed="false">服务器</button>'
    + '    </div>'
    + '    <button class="dsh-eng-save" type="button" data-act="save">保存</button>'
    + '  </div>'
    + '</div>'
    + '<div class="dsh-eng-msg" role="status" aria-live="polite"></div>'
    + '<button class="dsh-eng-btn" type="button" aria-label="引擎" title="切换 DSH 引擎">' + ENGINE_ICON + '</button>';
  (document.body || document.documentElement).appendChild(root);

  var listEl = root.querySelector('.dsh-eng-list');
  var msgEl = root.querySelector('.dsh-eng-msg');
  var nameEl = root.querySelector('.dsh-eng-name');
  var urlEl = root.querySelector('.dsh-eng-url');
  var kind = 'pc';
  var data = parse(N.engineList());
  var msgTimer = 0;

  function flash(text, bad) {
    msgEl.textContent = text || '';
    msgEl.className = 'dsh-eng-msg' + (text ? ' on' : '') + (bad ? ' bad' : '');
    if (msgTimer) clearTimeout(msgTimer);
    if (text) {
      msgTimer = setTimeout(function () { msgEl.className = 'dsh-eng-msg'; }, 2400);
    }
  }

  function iconFor(e) {
    if (e.local) return LOCAL_ICON;
    return e.kind === 'server' ? CLOUD_ICON : DESKTOP_ICON;
  }

  function rowHtml(e) {
    var cur = e.id === data.currentId;
    var kindText = e.local ? '本机' : (e.kind === 'server' ? '服务器' : '电脑');
    return '<div class="dsh-eng-card dsh-eng-rowCard' + (cur ? ' cur' : '') + '" data-id="' + esc(e.id) + '" role="button" tabindex="0">'
      + '<span class="dsh-eng-icon">' + iconFor(e) + '</span>'
      + '<span class="dsh-eng-main">'
      + '<span class="dsh-eng-label">' + esc(e.label) + '</span>'
      + '<span class="dsh-eng-sub">' + kindText + ' · ' + esc(e.url) + '</span>'
      + '</span>'
      + (cur
        ? '<span class="dsh-eng-badge">当前</span>'
        : (e.local ? '' : '<button class="dsh-eng-del" type="button" data-del="' + esc(e.id) + '">删除</button>'))
      + '</div>';
  }

  function render() {
    data = parse(N.engineList());
    var html;
    if (!data.ok) {
      html = '<div class="dsh-eng-card"><span class="dsh-eng-label">读取配置失败</span></div>';
    } else {
      var eps = data.endpoints || [];
      html = '';
      for (var i = 0; i < eps.length; i++) html += rowHtml(eps[i]);
      if (!eps.length) html = '<div class="dsh-eng-card"><span class="dsh-eng-label">还没有端点</span></div>';
    }
    listEl.innerHTML = html;
  }

  // ===== 开关 =====
  var scrollLocked = null;

  function lockScroll(on) {
    var el = document.documentElement;
    if (on) {
      if (scrollLocked) return;
      scrollLocked = { html: el.style.overflow, body: document.body.style.overflow };
      el.style.overflow = 'hidden';
      document.body.style.overflow = 'hidden';
    } else if (scrollLocked) {
      el.style.overflow = scrollLocked.html;
      document.body.style.overflow = scrollLocked.body;
      scrollLocked = null;
    }
  }

  function showPage(name) {
    var pages = root.querySelectorAll('.dsh-eng-page');
    for (var i = 0; i < pages.length; i++) {
      pages[i].hidden = pages[i].getAttribute('data-page') !== name;
    }
    var body = root.querySelector('.dsh-eng-page[data-page="' + name + '"] .dsh-eng-body');
    if (body) body.scrollTop = 0;
  }

  function setKind(next) {
    kind = next;
    var segs = root.querySelectorAll('[data-kind]');
    for (var i = 0; i < segs.length; i++) {
      segs[i].setAttribute('aria-pressed', segs[i].getAttribute('data-kind') === kind ? 'true' : 'false');
    }
  }

  function setRailState(on) {
    var rb = document.getElementById(RAIL_BTN_ID);
    if (rb) rb.setAttribute('data-on', on ? '1' : '0');
  }

  function open() {
    root.classList.add('on');
    lockScroll(true);
    showPage('list');
    render();
    setRailState(true);
  }

  function close() {
    root.classList.remove('on');
    lockScroll(false);
    flash('');
    setRailState(false);
  }

  function toggle() {
    if (root.classList.contains('on')) close(); else open();
  }

  // ===== 侧边栏入口 =====
  // #dsh-rail-foot 由 official-bridge.js 在窄栏态挂载到 body；React 重渲染
  // 或 rail 状态切换可能重建它，所以照抄官方脚本的复查套路。
  function mountRailEntry() {
    var foot = document.getElementById('dsh-rail-foot');
    if (!foot || !foot.isConnected) return false;
    var existing = document.getElementById(RAIL_BTN_ID);
    if (existing && existing.isConnected) return true;
    var btn = document.createElement('button');
    btn.id = RAIL_BTN_ID;
    btn.type = 'button';
    btn.setAttribute('aria-label', '引擎');
    btn.setAttribute('title', '切换 DSH 引擎');
    btn.innerHTML = ENGINE_ICON;
    var gear = document.getElementById('dsh-rail-settings');
    if (gear && gear.parentNode === foot) foot.insertBefore(btn, gear);
    else foot.appendChild(btn);
    btn.addEventListener('click', function (ev) { ev.stopPropagation(); toggle(); });
    return true;
  }

  var railMounted = mountRailEntry();
  setInterval(function () {
    if (document.hidden) return;
    try { railMounted = mountRailEntry() || railMounted; } catch (e) { }
  }, 600);

  setTimeout(function () {
    if (!railMounted) root.classList.add('fallback');
  }, 8000);

  root.querySelector('.dsh-eng-btn').addEventListener('click', function (ev) {
    ev.stopPropagation();
    toggle();
  });

  // ===== 事件 =====
  function save() {
    var url = urlEl.value.trim();
    if (!url) { flash('请填写地址', true); return; }
    var r = parse(N.engineAdd(JSON.stringify({
      label: nameEl.value.trim(), url: url, kind: kind,
    })));
    if (!r.ok) { flash(r.error || '添加失败：URL 无效或已存在', true); return; }
    render();
    flash('已添加');
    showPage('list');
  }

  root.addEventListener('click', function (ev) {
    var t = ev.target;
    var act = t.closest ? t.closest('[data-act]') : null;
    if (act) {
      var a = act.getAttribute('data-act');
      if (a === 'close') close();
      else if (a === 'to-add') {
        showPage('add');
        nameEl.value = '';
        urlEl.value = '';
        setKind('pc');
      } else if (a === 'to-list') showPage('list');
      else if (a === 'save') save();
      return;
    }

    var kindBtn = t.closest ? t.closest('[data-kind]') : null;
    if (kindBtn) { setKind(kindBtn.getAttribute('data-kind')); return; }

    var del = t.closest ? t.closest('[data-del]') : null;
    if (del) {
      ev.stopPropagation();
      var r = parse(N.engineRemove(del.getAttribute('data-del')));
      flash(r.ok ? '已删除' : (r.error || '至少保留一个端点'), !r.ok);
      render();
      return;
    }

    var row = t.closest ? t.closest('.dsh-eng-rowCard[data-id]') : null;
    if (!row) return;
    var id = row.getAttribute('data-id');
    if (id === data.currentId) { flash('已经是当前引擎'); return; }
    var r2 = parse(N.engineSwitch(id));
    if (!r2.ok) { flash(r2.error || '切换失败', true); return; }
    // App 侧会自己 loadUrl 到新端点，这里不再 reload，避免双重刷新。
    flash('已切换，正在接入目标引擎');
  });

  urlEl.addEventListener('keydown', function (ev) {
    if (ev.key === 'Enter') { ev.preventDefault(); save(); }
  });

  // Escape 由官方外壳在原生返回键时派发（见文件头注释）。
  document.addEventListener('keydown', function (ev) {
    if (ev.key !== 'Escape') return;
    if (!root.classList.contains('on')) return;
    var addPage = root.querySelector('[data-page="add"]');
    if (addPage && !addPage.hidden) { showPage('list'); return; }
    close();
  }, false);

  render();
  console.log('[dsh] engine switcher ready (card design)');
})();
