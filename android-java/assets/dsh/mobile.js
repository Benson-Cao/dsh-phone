(function(){
  if(document.documentElement&&document.documentElement.getAttribute('data-dsh-error')){return;}
  var SID='dsh-m-shell';
  if(document.getElementById(SID)){return;}
  function detectDark(){
    var b=document.body;
    if(!b){return;}
    if(b.hasAttribute('data-ds-dark-theme')){
      document.documentElement.classList.remove('dsh-dark');
      return;
    }
    var c=window.getComputedStyle(b).backgroundColor||'';
    var m=c.match(/rgba?\((\d+),\s*(\d+),\s*(\d+)(?:,\s*([\d.]+))?/);
    if(!m){return;}
    var a=m[4]===undefined?1:parseFloat(m[4]);
    if(a<0.5){document.documentElement.classList.remove('dsh-dark');return;}
    var lum=(0.2126*+m[1]+0.7152*+m[2]+0.0722*+m[3])/255;
    document.documentElement.classList.toggle('dsh-dark',lum<0.5);
  }
  var mq=window.matchMedia('(max-width:1023px)');
  function close(){document.body.classList.remove('dsh-drawer-open');}
  function mount(){
    if(document.getElementById(SID)){return;}
    // ===== r68 · 汉堡图标常量必须声明在 mount() 的**最顶端** =====
    //   真机故障：落地页左上角出现一个白底圆角胶囊、里面是文本 "undefined"，
    //   而汉堡图标不见了。胶囊的白底 / 13px 圆角 / 轻阴影与 #dsh-mbtn 的 CSS 完全
    //   吻合 —— 它就是汉堡按钮本身，只是 innerHTML 被拼成了字符串 "undefined"。
    //   根因（可复现、非猜测）：`var` 会提升，**但赋值不会**。
    //     MENU_SVG 原先声明在 mount 主体靠后的位置（r66 引入 ensureTopBar() 时），
    //     而 ensureTopBar() 最早是被设置面板那个 IIFE 里的 sync() → syncOvFlag()
    //     调起来的 —— 那个 IIFE 在源码里**排在 MENU_SVG 赋值之前**就执行完了。
    //     于是这一刻 MENU_SVG 是 undefined，
    //       bar.innerHTML='<button …>'+MENU_SVG+'</button>'
    //     里的字符串拼接把 undefined 拼成了文本 "undefined"。
    //     更糟的是**它不会自愈**：按钮一旦建好，后面 ensureTopBar() 看到
    //     `#dsh-mbtn` 存在就不会重建，这个 "undefined" 会一直留在屏幕上。
    //   为什么静态查不出来：构建产物里根本没有 "undefined" 这个字符串，
    //     它是运行时拼出来的 —— 只能靠把脚本真跑起来扫 DOM 才能发现。
    //   修法两条，一起上：
    //     ① 常量提到 mount() 最顶端（在任何可能用到它的 IIFE 之前）——治本；
    //     ② ensureTopBar() 增加**内容**校验（不只是"节点在不在"）——治标，
    //       顺带覆盖 React 把按钮内容清空、只留壳子的情况。
    var MENU_SVG='<svg width="22" height="22" viewBox="0 0 24 24" fill="none"'
      +' stroke="currentColor" stroke-width="2" stroke-linecap="round">'
      +'<path d="M3 6h18M3 12h18M3 18h18"/></svg>';
    // r93 · 抽屉打开时汉堡换成「收起侧栏」面板图标（参考稿《设置页（参考）.jpg》：
    //   logo 行右端是一颗 ▭ 形态的收起按钮，不是 ☰）。与 MENU_SVG 同样必须在
    //   mount() 最顶端赋值 —— r68 的 "undefined" 胶囊教训：任何被 ensureTopBar()
    //   或更早的 IIFE 用到的常量，声明可以提升、赋值不会。
    var PANEL_SVG='<svg width="20" height="20" viewBox="0 0 24 24" fill="none"'
      +' stroke="currentColor" stroke-width="2" stroke-linejoin="round">'
      +'<rect x="3" y="4" width="18" height="16" rx="3"/><path d="M15 4v16"/></svg>';
    // r93 · 抽屉「插件」行图标（拼图块，与设置页插件项语义一致；纯装饰，颜色走 currentColor）
    var PLUGIN_SVG='<svg width="18" height="18" viewBox="0 0 24 24" fill="none"'
      +' stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">'
      +'<path d="M9 3h6v3.2a1.8 1.8 0 1 0 3.6 0V6H21v6h-2.8a1.8 1.8 0 1 0 0 3.6H21V21h-6v-2.8'
      +' a1.8 1.8 0 1 0-3.6 0V21H3v-6h2.8a1.8 1.8 0 1 0 0-3.6H3V3h6z"/></svg>';
    var mark=document.createElement('div');mark.id=SID;
    document.body.appendChild(mark);
    // r74 [diag]：注入脚本统一日志出口（console -> DshChromeClient.onConsoleMessage -> logcat）
    function dlog(){try{console.log.apply(console,arguments);}catch(e){}}
    function derr(){try{console.error.apply(console,arguments);}catch(e){}}
    var live=document.createElement('div');
    live.id='dsh-live';
    live.setAttribute('role','status');
    live.setAttribute('aria-live','polite');
    live.setAttribute('aria-atomic','true');
    live.style.cssText='position:absolute;width:1px;height:1px;margin:-1px;padding:0;overflow:hidden;clip:rect(0 0 0 0);white-space:nowrap;border:0';
    document.body.appendChild(live);
    var st=document.createElement('style');st.id=SID+'-css';
    var DSH_MKT_RE=/[\/]dsh-market(\/|$|[?#])/;
    var msId=SID+'-mkt';
    // ===== r71 · 市场页样式改成**常驻注入**（真机图1 的总根因）=====
    //   旧逻辑（r50~r70）：按 location.pathname 匹配 /dsh-market 决定 st 里装
    //   mobile.css 还是 market.css，market.css 不匹配时**整份不注入**。
    //   但 dshmarket 1.66.x 把市场面板注册成 **settings.section**
    //   （src/client/index.ts:157-172 `ctx.slots.register({name:'settings.section',id:'market',order:40})`），
    //   渲染在设置浮层 `[role="dialog"]` 内（src/client/Market.module.css:322-325 的
    //   `[role="dialog"]:has([data-dsh-market-root])` 就是证据）——**客户端根本没有
    //   `/dsh-market` 这个路由**（`/dsh-market/*` 是宿主的 HTTP 数据端点）。
    //   于是真机上 pathname 永远不匹配 ⇒
    //     ① market.css / market-chrome.css 一条都不注入；
    //     ② marketPageBar() 的两处调用点都被 if(inMarket) 挡掉 ⇒ 一个 data-dsh-mkt-*
    //        都没打上、#dsh-mkt-bar 从未注入。
    //   表现正是真机图1：标题被挤成竖排（没有 nowrap）、社区说明/申请收录/重启提醒
    //   全在、× 没变 ←、两个按钮不同排。r69 之前所有"市场页"验证都跑在
    //   repro/server.js 合成的 /dsh-market 上 —— 桩是绿的，真机是死的。
    //   修法：mobile.css 永远在 st；market.css + market-chrome.css 放进**另一个常驻
    //   <style>**。两者全部以 [data-dsh-market-root] 为作用域，而该属性由插件自己写在
    //   市场根节点上（MarketSection.tsx:5500 / client.js:11571），不渲染市场时天然不命中，
    //   所以常驻注入没有任何副作用（也顺带修掉 pushState 不触发 popstate 的老问题）。
    function hasMarketRoot(){
      try{ return !!document.querySelector('[data-dsh-market-root]'); }catch(e){ return false; }
    }
    // ⚠️ inMarket 仍**只按 pathname** 判定：:370 那个大 IIFE 用 `if(!inMarket)` 决定要不要
    //    做设置页/导航的准备，不能因为"设置浮层里正开着市场"就跳过。
    //    "市场页样式/操作条要不要上"改由两个自守卫的入口决定：
    //    css 常驻注入（作用域 [data-dsh-market-root]，不渲染就不命中）+ marketPageBar()
    //    开头 `if(!root) return`。
    var inMarket=DSH_MKT_RE.test(location.pathname);
    function applyDshScope(){
      inMarket=DSH_MKT_RE.test(location.pathname);
      st.textContent=__DSH_CSS__;
      if(!document.getElementById(msId)){
        var ms=document.createElement('style');ms.id=msId;
        ms.textContent=__DSH_MARKET_CHROME_CSS__+'\n'+__DSH_MARKET_CSS__;
        (document.head||document.documentElement).appendChild(ms);
      }
    }
    applyDshScope();
    (document.head||document.documentElement).appendChild(st);
    window.addEventListener('popstate',applyDshScope);
    window.addEventListener('hashchange',applyDshScope);
    // U18（r66）·「侧滑关闭设置后汉堡消失」的根因之一就在这里。
    //   settingsOverlay() 原来只读 getComputedStyle(o) —— 而那只看**元素自身**的样式，
    //   不反映祖先。dsh 关闭面板后浮层节点可能仍留在树上（被祖先藏起来，或尺寸被压成 0），
    //   此时它自身 display 依旧不是 none ⇒ 被判成"面板还开着" ⇒ syncOvFlag() 把
    //   body.dsh-ov-open **又加回来** ⇒ mobile.css 的
    //   `body.dsh-ov-open #dsh-mtop{display:none !important}` 把汉堡藏死。
    //   ⚠️ 同一类坑 r65 已经在 visibleClose()/reallyVisible() 上踩过一次
    //   （见那两处的注释），这里是同一根因的第二个现场。
    //   修法：几何判据 —— rect 为 0 / 整块在视口外，用户就是看不见，不算打开。
    //   ⚠️ r69：**量不出来的时候按"看得见"处理**。过渡动画中 getBoundingClientRect()
    //   可能抛异常或返回全 0；此时若一律判"不可见"，会连带把设置 L2 的返回条删掉
    //   （真机图1：顶部没有「← 设置」），而页面一旦静止 sync() 不再跑 ⇒ 永久缺失。
    //   判"不可见"必须要有**确定的证据**（样式明确 none/hidden，或几何明确为 0），
    //   拿不到证据就保守地当成"看得见"。
    function ovVisible(o){
      var cs=null;
      try{ cs=window.getComputedStyle(o); }catch(e){ cs=null; }
      if(!cs){return true;}                                    // 量不出样式 → 保守当可见
      if(cs.display==='none'||cs.visibility==='hidden'){return false;}
      if(parseFloat(cs.opacity||'1')===0){return false;}
      var r=null;
      try{ r=o.getBoundingClientRect(); }catch(e){ r=null; }
      if(!r){return true;}                                    // 量不出几何 → 保守当可见
      if(r.width<=1||r.height<=1){return false;}
      var vw=window.innerWidth||0,vh=window.innerHeight||0;
      if(vw&&vh&&(r.left>vw-2||r.top>vh-2||r.right<2||r.bottom<2)){return false;}
      // r71（侧滑返回后汉堡消失的残留根因）：再沿**祖先链**查一遍。
      //   为什么必须有：display 不继承，opacity 也**不**继承 —— dsh 用一个外层容器
      //   淡出（opacity:0）关设置面板时，浮层节点自身的 computed display 仍是 flex、
      //   opacity 仍是 1，而 rect 也**不是 0**（opacity:0 不改变布局）⇒ 上面几道
      //   自身判据全部漏过，syncOvFlag() 会把 body.dsh-ov-open 加回来，
      //   mobile.css 的 `body.dsh-ov-open #dsh-mtop{display:none!important}` 再把汉堡藏死。
      //   ⚠️ 这正是 r65/r66/r69 已经踩过三次的同一坑（「祖先隐藏 vs 自身样式」）：
      //   判"看不见"要么有自身样式证据、要么有几何证据，现在补上第三种：祖先样式证据。
      var pn=o.parentNode,hop=0;
      while(pn&&pn.nodeType===1&&hop<12){
        hop++;
        var pcs=null;
        try{ pcs=window.getComputedStyle(pn); }catch(e){ pcs=null; }
        if(pcs){
          if(pcs.display==='none'||pcs.visibility==='hidden'){return false;}
          if(parseFloat(pcs.opacity||'1')===0){return false;}
        }
        pn=pn.parentNode;
      }
      return true;
    }
    function settingsOverlay(){
      var ovs=document.querySelectorAll('[class*="_overlay"]');
      for(var i=0;i<ovs.length;i++){
        var o=ovs[i];
        if(!o.querySelector('[class*="_panel"]')){continue;}
        if(!o.querySelector('[class*="_navList"]')){continue;}
        if(ovVisible(o)){return o;}
      }
      return null;
    }
    function tagHosts(){
      var ov=settingsOverlay();
      if(!ov){return null;}
      if(!ov.classList.contains('dsh-s-ov')){ov.classList.add('dsh-s-ov');}
      var nl=ov.querySelector('[class*="_navList"]');
      if(nl&&nl.parentElement&&!nl.parentElement.classList.contains('dsh-s-nav')){
        nl.parentElement.classList.add('dsh-s-nav');
      }
      return ov;
    }
    // ===== 真正关闭设置面板 / 原生返回状态上报（r65）=====
      // 这一簇**必须待在 mount 主体**：popstate（侧滑 / 返回键）处理器就在 mount
      // 作用域，够不到设置面板那个 IIFE 里的函数 —— r65 之前它只能删几个 body
      // class 当作"关闭"，而 dsh 的浮层是 React 组件，`dsh-ov-open` 只是我们的
      // 布局开关，删掉它并不等于把浮层关掉。
      // 判断元素是否「用户真的看得见」。
      // ⚠️ 不能只看 getComputedStyle：它只反映元素**自身**的样式，不反映祖先。
      // 设置首页的 _close 就是这种：自身 display:block，但它所在的 header 在那个层级
      // 整体不显示 —— 只看 computed style 会误判成「可见」，于是返回条永远不注入。
      // getBoundingClientRect 返回 0 才是真的看不见（这条对祖先 display:none 也成立）。
      function reallyVisible(el){
        var cs=window.getComputedStyle(el);
        if(cs.display==='none'||cs.visibility==='hidden'){return false;}
        if(parseFloat(cs.opacity||'1')===0){return false;}
        var r=el.getBoundingClientRect();
        return !!(r&&r.width>0&&r.height>0);
      }
      // 找「真正可见」的关闭按钮
      function visibleClose(ov){
        var list=ov.querySelectorAll('[class*="_close"],button[aria-label*="关闭"],button[aria-label*="Close"]');
        for(var i=0;i<list.length;i++){ if(reallyVisible(list[i])){return list[i];} }
        return null;
      }
      // U15（r65）：真正关闭设置浮层。
      //   为什么不自己删 class 了事：dsh 的浮层归 React 管，我们的 class 只管布局。
      //   必须点到 dsh 自己的出口（× 或遮罩）才会真的卸载。
      //   ⚠️ 第二段"放宽到存在即可"是关键：设置首页（l2）下 dsh 的 × 就在
      //   mobile.css:84 被我们 display:none 掉的 _content 里，几何可见性为 0，
      //   visibleClose() 永远找不到它 —— 于是「返回条点了没反应」。
      //   display:none 只影响布局，不影响事件派发，程序化 .click() 依然会
      //   冒泡到 React 的根监听器，所以这里可以放心放宽。
      //   ⚠️ 最后那道"重试 → 兜底重载"是**护栏**：万一 dsh 某个版本的 × 不再响应
      //   程序化点击，用户就会**彻底关不掉设置面板**（连返回键也会被我们自己的
      //   状态机吃掉）。宁可重载一次本地页面（代价约 1s），也不能给用户一个死界面。
var ovCloseTried=false;
    // 用户已经明确要求关闭浮层（点了返回条 / 侧滑返回），但 dsh 的浮层还在（
    // React 尚未卸载）。此时**不能**再把它顶回 l2 —— 那会把 _content 重新
    // display:none 掉，连 dsh 自己的 × 都被藏起来，等于把用户锁死。
    var ovCloseRequested=false;
    // ===== r69 · 图3「三级页面跑到二级页面显示」=====
    //   真机现象：导航列表（通用设置/模型/插件/Agent 预设/插件市场）与下层内容
    //   （打开配置文件/权限/语言/外观）**同时显示**。
    //   根因（可在合成环境复现）：mobile.css 的层级显示完全靠 body 上两个互斥 class
    //   —— `dsh-s-l2` 隐藏 _content、`dsh-s-l3` 隐藏 _navList，两个都没有 = 两边都显示。
    //   而 closeSettingsPanel() 会先置 ovCloseRequested=true 再清掉这两个 class；
    //   sync() 恢复 L2 的那行又带 `&&!ovCloseRequested` 排除条件，ovCloseRequested
    //   只在"浮层真的没了"时复位。于是只要发生过一次「请求关闭但 dsh 没卸载浮层」，
    //   ovCloseRequested 永久为 true ⇒ **L2 永远不再恢复** ⇒ 图3，且再也回不去。
    //   修法：给"请求关闭"加**宽限期**。超过宽限期浮层还在 ⇒ 判定这次关闭失败，
    //   撤销请求并把 L2 恢复回去（不变量：浮层可见 ⇒ body 必为 l2 或 l3 之一）；
    //   连续失败两次才走一次整页重载（已有 ovReloadedOnce 兜底，不会反复重载）。
    var ovCloseAt=0;
    var OV_CLOSE_GRACE=800;
    var ovCloseFails=0;
    // r69：关闭请求"过期并放弃"的标记。浏览器实测（repro/debug4.js 时间线）：
    //   点返回 → 0~1080ms body 只有 dsh-ov-open（**图3 的混合态**）→ 1189ms 自愈回 L2
    //   → 1299ms 重试链又调 clearOvState() 把 L2 抹掉 ⇒ 界面来回闪，最后还兜底重载。
    //   根因：自愈与"反复强制关闭"在互相打架。放弃之后就**不再重复尝试**，
    //   改为：自愈保持 L2（界面连贯）+ 400ms 后干净地重载一次（把用户带出去）。
    var ovAbandoned=false;
    // r80：记录最近一次"放弃关闭"的时间戳 —— abandonment 不再是终身流放：
    //   冷却 2.5s 后新来的关闭请求重新武装重试。否则 ovReloadedOnce 用掉后
    //   ovAbandoned 永久为 true ⇒ closeSettings 永久空操作 ⇒ L2 返回键彻底
    //   没反应（r79 真机日志：连按 6 次返回 bodyClass 纹丝不动）。重试是安全的：
    //   clearOvState() 强制收抽屉后面板视觉上必关，sync() 也会在浮层消失时复位全部标志。
    var ovAbandonAt=0;
      // r87 · 程序化点击在途标记：dshTap 派发的 click 会冒泡进捕获层，
      //   捕获层的三级 ← 拦截必须跳过"自己人"（否则 closeSettingsPanel 的 ×
      //   程序化点击会被拦成 setL2，面板永远真关不掉）。
      var _dshTapAt=0;
      // r84 · 程序化点击 dsh 控件的可靠方式：完整 pointer+mouse 事件序列（带坐标）。
      //   为什么：CDP 直连真机实测 —— React 对裸 .click() 时灵时不灵（设置入口
      //   triggerRow 完全忽略合成 click，必须真实输入事件才响应；r83 真机日志里
      //   × 关闭键也出现过"点了没关"的僵尸面板）。带坐标的完整序列与真实手指等价。
      //   ⚠️ 末尾的 click 用 dispatchEvent 派发；只有派发失败（旧桩环境没有
      //   MouseEvent/dispatchEvent）才退回 el.click()，绝不能两者都发 —— 那会把
      //   onClick 触发两次，手风琴 toggle 两次等于没按。
      function dshTap(el){
        if(!el){return false;}
        var x=0,y=0;
        try{
          var r=el.getBoundingClientRect();
          if(r){ x=r.left+(r.width||0)/2; y=r.top+(r.height||0)/2; }
        }catch(eR){}
        var opts={bubbles:true,cancelable:true,composed:true,view:window,
                  clientX:x,clientY:y,screenX:x,screenY:y};
        try{ _dshTapAt=(new Date()).getTime(); }catch(eT0){}   // r87：打点在途标记
        var ok=false;
        try{ el.dispatchEvent(new PointerEvent('pointerdown',opts)); }catch(e1){}
        try{ el.dispatchEvent(new MouseEvent('mousedown',opts)); }catch(e2){}
        try{ el.dispatchEvent(new PointerEvent('pointerup',opts)); }catch(e3){}
        try{ el.dispatchEvent(new MouseEvent('mouseup',opts)); }catch(e4){}
        try{ el.dispatchEvent(new MouseEvent('click',opts)); ok=true; }catch(e5){}
        if(!ok){ try{ el.click(); }catch(e6){} }
        // r85 [diag]：两种调用路径（CDP 直调 vs evaluateJavascript 按键）的差异
        //   就在这里被钉死 —— 派发方式 + 坐标一目了然。
        try{ dlog('[dsh] dshTap: dispatched='+ok+' x='+Math.round(x)+' y='+Math.round(y)
          +' tag='+el.tagName+' cls='+String(el.className).slice(0,30)); }catch(eD){}
        return true;
      }
      // r84 · 收起内容区展开的卡片（插件页的手风琴 = 用户的"三级页面"）。
      //   真机 DOM 取证（CDP）：插件项展开 = li.YyYd_a_card.YyYd_a_cardOpen
      //   （高 68→351，露出配置表单 + 放弃修改/保存），头部按钮 YyYd_a_header
      //   是 toggle —— 点击即收起（真机实测验证）。
      //   这就是"三级 → 二级"的正确返回动作；旧代码直接 setL2 会跳到设置列表
      //   （跳过插件列表页）= 用户报的"三级页面按返回直接回上上级"。
      //   ⚠️ 只认 cardOpen（展开态）；cardActive 是预设"选中态"（非展开，
      //   点击 cardMain 也不收起，真机实测）不算一级深度。
      function collapseExpandedCards(){
        try{
          var ov=document.querySelector('.dsh-s-ov');
          if(!ov){
            try{ dlog('[dsh] collapse: no .dsh-s-ov'); }catch(eD0){}
            return false;
          }
          var cards=ov.querySelectorAll('[class*="_content"] [class*="cardOpen"]');
          if(!cards||!cards.length){
            // r85 [diag]：按键路径曾在这一步返回 false 而 CDP 直调命中 1 ——
            //   把查询各环节的数据全部打出来，一次日志定位。
            try{
              var dC=ov.querySelectorAll('[class*="cardOpen"]').length;
              var dCt=document.querySelectorAll('[class*="_content"]').length;
              var dO=ov.querySelectorAll('[class*="_content"] [class*="_card"]').length;
              dlog('[dsh] collapse: cards=0 [ov.cardOpen='+dC
                +' doc._content='+dCt+' ov.content.card='+dO
                +' body='+document.body.className+']');
            }catch(eD1){}
            return false;
          }
          var done=0;
          for(var i=0;i<cards.length;i++){
            var card=cards[i];
            var btn=null;
            for(var j=0;j<card.children.length;j++){
              if(card.children[j]&&String(card.children[j].tagName||'').toUpperCase()==='BUTTON'){
                btn=card.children[j];break;
              }
            }
            if(!btn){ try{ btn=card.querySelector('button'); }catch(eB){ btn=null; } }
            if(btn&&dshTap(btn)){done++;}
          }
          if(done>0){ dlog('[dsh] collapseExpandedCards: toggled '+done+' open card(s)'); }
          else{
            try{ dlog('[dsh] collapse: cards='+cards.length+' but done=0 (btn not found?)'); }catch(eD2){}
          }
          return done>0;
        }catch(e){
          try{ dlog('[dsh] collapse threw: '+(e&&e.message||e)); }catch(eD3){}
          return false;
        }
      }
      // U18（r66）· 重载兜底必须**整个页面只允许一次**。
      //   r65 那版把"试过没有"记在 ovCloseTried 上，而它只在 `!ov` 时才复位 ——
      //   但 closeSettingsPanel() 只会在**有浮层时**被调用，于是第一次成功关闭之后
      //   ovCloseTried 永远停在 true ⇒ **第二次**关设置面板直接 location.reload()。
      //   用户看到的就是「侧滑返回后回到首页，汉堡没了 / 界面被整页刷掉」。
      var ovReloadedOnce=false;
      // 关闭动作里我们自己那套布局状态的复位。
      //   l2/l3 决定「导航列表 / 内容」谁显示，ov-open 决定汉堡与遮罩显不显示。
      // 抽成函数是因为有三处退出路径都要复位（点 × / 点遮罩 / 侧滑与返回键）。
      // r87：keepDrawer 参数 —— "退出设置面板但停在设置页（抽屉保持展开）"的
      //   新语义：二级页面按返回/点返回条 → 关面板保抽屉；只有"彻底退出"
      //   （popstate 等全退场景）才连带收抽屉（r79 旧语义）。
      function clearOvState(keepDrawer){
        var bd=document.body;
        _lvl=0;   // r88：面板退出（无论哪种语义）层级记忆一并归零
        if(bd){bd.classList.remove('dsh-s-l2');bd.classList.remove('dsh-s-l3');bd.classList.remove('dsh-ov-open');
          // r87：keepDrawer=true（二级返回的"回设置页"语义）时**保住抽屉**；
          //   默认 false = r79 的彻底退出语义（连带收抽屉）。
          if(!keepDrawer){bd.classList.remove('dsh-drawer-open');}}
        // r79（恢复 r77 的正确半边）：**彻底退出**时连带收抽屉 ——
        //   面板住在 sidebarCol 里（默认 translate3d(-102%)），只删面板不删抽屉，
        //   展开的侧栏会留在屏幕上；r78 曾把这里回退，这一轮已重新验证必要性。
        ensureTopBar();
      }
      // r87 · **退出设置面板但停在设置页（抽屉保持展开）**。
      //   用户语义（真机日志 21:39:55 实锤）：二级页面（设置列表）按返回/点
      //   "← 设置"返回条 → 应回到**设置页（抽屉）**，而不是直接回首页。
      //   旧 closeSettings()（= closeSettingsPanel()）连抽屉一起收 → 首页。
      function closeSettingsToDrawer(){
        closeSettingsPanel(true);
        // 面板关闭后把抽屉展开补上（即便此前抽屉类丢失的幽灵态，也把用户
        // 落在设置页，而不是裸首页）。
        try{
          var bd=document.body;
          if(bd&&!bd.classList.contains('dsh-drawer-open')){bd.classList.add('dsh-drawer-open');}
        }catch(e){}
      }
      function closeSettingsPanel(keepDrawer){
        if(ovAbandoned){
          // r80：冷却重武装 —— 2.5s 内不重复折腾，超过则允许再试一次完整关闭。
          var _an=0;
          try{ _an=(new Date()).getTime(); }catch(e){}
          if(_an&&ovAbandonAt&&_an-ovAbandonAt<2500){return;}
          ovAbandoned=false;
        }
        // ⚠️ r69 计时起点只在**新的**关闭请求上打（浏览器取证：repro/debug4.js 时间线）。
        //   原来每次调用都刷新 ovCloseAt，而重试链每 350~510ms 就调一次 ⇒
        //   `now-ovCloseAt` 永远是零头 ⇒ 宽限期永远不过 ⇒ 永远不放弃、永远重试，
        //   ovCloseRequested 也永远为真 ⇒ L2 永远恢复不了（就是图3）。
        //   判据用 ovCloseTried：它为真说明本次是**重试**，不重新起表。
        if(!ovCloseTried){ ovCloseAt=(new Date()).getTime(); }
        ovCloseRequested=true;
        var ov=settingsOverlay();
        if(!ov){
          ovCloseTried=false;
          clearOvState(keepDrawer);
          return;
        }
        // r87：关闭目标选择 —— ①可见 × 直接点；②× 不可见（L2 下它藏在
        //   display:none 的 _content 里）→ 改点**遮罩**（dsh React 自己的
        //   关闭路径，面板会真正卸载）；③连遮罩都没有才用兜底 ×（仍验几何）。
        //   r86 的 0×0 隐藏 × 绝不点依旧成立。
        var c=visibleClose(ov);
        if(c){ dshTap(c); }
        else{
          var mask=null;
          try{ mask=ov.querySelector('[class*="_mask"]'); }catch(eM){ mask=null; }
          if(mask){
            dshTap(mask);
            try{ dlog('[dsh] closeSettingsPanel: × 不可见，改点遮罩'); }catch(eD){}
          }else{
            var c2=ov.querySelector('[class*="_close"],button[aria-label*="关闭"],button[aria-label*="Close"]');
            if(c2){
              var _cr=null;
              try{ _cr=c2.getBoundingClientRect(); }catch(eR){ _cr=null; }
              if(_cr&&_cr.width>1&&_cr.height>1){ dshTap(c2); }
              else{ try{ dlog('[dsh] closeSettingsPanel: × 不可见(0x0)，跳过程序化点击'); }catch(eV){} }
            }
          }
        }
        // 兜底：无论有没有点到 dsh 的出口，都把我们自己的布局状态复位，
        // 至少不能让用户卡在一个"既没有出口、返回键也没反应"的界面里。
        clearOvState(keepDrawer);
        // 点 × 之后同步就已经看不见浮层了 → 这就是正常路径，绝不重载页面。
        if(!settingsOverlay()){ ovCloseTried=false; return; }
        ovCloseFails++;   // r69：这次"请求关闭"没成功（dsh 的 × 没响应），记一笔
        if(ovCloseTried){
          // 重试过一次、浮层**仍然可见** ⇒ dsh 这个版本的出口不响应程序化点击。
          // 宁可重载一次本地页面（代价约 1s），也不能给用户一个死界面；
          // 但整页只允许一次，否则就变成"每次返回都重载"（那正是 r65 的事故）。
          // ⚠️ r69：重载必须**排在自愈之后**。原来这里是"第二次失败就立刻 reload"，
          //   时间线只有 350+350=700ms，比新加的 800ms 宽限期还短 ⇒ sync() 的
          //   "撤销关闭请求 + 恢复 L2"根本没机会跑，用户看到的是"点返回 → 闪一下回到
          //   主页"。现在没到宽限期就再排一次重试，把机会让给自愈；
          //   宽限期一过仍然关不掉，才重载（且只重载一次）。
          var waited=(new Date()).getTime()-ovCloseAt;
          if(waited>OV_CLOSE_GRACE){   // r69：宽限期已过
            // 宽限期已过 ⇒ 认定 dsh 这次关不掉：放弃反复尝试，交给一次性兜底重载。
            //   先留 400ms 让 sync() 把 L2 恢复好（万一重载被系统拦掉，界面也是连贯的）。
            ovAbandoned=true;
            ovAbandonAt=(new Date()).getTime();   // r80：记下放弃时间，供冷却重武装判定
            ovCloseRequested=false;
            try{
              setTimeout(function(){
                if(!ovReloadedOnce){ ovReloadedOnce=true; try{ location.reload(); }catch(e){} }
              },400);
            }catch(e){}
            return;
          }
          // ⚠️ 这里**不能**把 ovCloseTried 清回 false：那会让下一次调用看起来像
          //   "第一次尝试"，于是走不到上面那个 waited>GRACE 的放弃分支，
          //   重试链变成永不停的自旋（浏览器实测：fails 一路涨到 4、计时器反复刷新）。
          //   保持 true，让下一次调用重新进来算 waited。
          try{
            setTimeout(function(){
              if(settingsOverlay()){closeSettingsPanel(keepDrawer);}
              else{ovCloseTried=false;}
            },Math.max(150,OV_CLOSE_GRACE-waited+60));
          }catch(e){}
          return;
        }
        ovCloseTried=true;
        try{
          setTimeout(function(){
            if(settingsOverlay()){closeSettingsPanel(keepDrawer);}
            else{ovCloseTried=false;}   // 关成功了 → 复位，别把状态留给下一次
          },350);
        }catch(e){}
      }
      // U16（r65）：把"现在原生返回该由 JS 处理"同步给原生侧（DshBridge.setBackHandled）。
      //   为什么需要：MainActivity 只能同步地问 WebView「能不能回退」，而设置浮层
      //   与抽屉是**覆盖层**，它们的存在与否只有 JS 知道。没有这个上报，原生只能
      //   靠 canGoBack() 猜，猜错就是「返回键直接退出 App」。
      //   只在值变化时过桥，避免每次 sync 都跨线程调用。
      var _backHandled=null;
      // r73：backOneLevel 的去重时间戳（见该函数内的去重闸注释）。
      var _backLastAt=0;
      function syncBackHandled(){
        var bd=document.body;
      var v=!!(bd&&(bd.classList.contains('dsh-ov-open')||bd.classList.contains('dsh-drawer-open')));
      if(v===_backHandled){return;}
      _backHandled=v;
      dlog('[dsh] syncBackHandled -> '+v);
      try{
        if(window.dshNative&&window.dshNative.setBackHandled){window.dshNative.setBackHandled(v);}
      }catch(e){}
      }
    // ===== 侧滑返回支持（r64）=====
      // 根因：设置面板是**覆盖层**，打开它**不改变 URL**，因此根本不产生 WebView 历史项。
      // 侧滑手势没有东西可退 → 直接落到 Activity 默认行为 → 退出 App
      //（用户反馈「侧滑返回还是不能用」；r63 只把 clearHistory 改成只清一次，
      //  但那只解决了「历史被清空」，解决不了「压根没历史」）。
      // 解法：打开面板时手动压一条历史，侧滑（popstate）时关闭面板；
      // 我们主动关闭面板时走 history.back() 消费掉它，避免多退一次。
      var PANEL_MARK='dshPanel';
      // r73 · 自家清理 back 的在途标记（浏览器 E2E 实测的竞态，真机同样可能发生）：
      //   syncBackMark() 关面板后调 history.back() 消费压入的历史，该 popstate **异步**
      //   到达；若这几十~几百 ms 里面板被重新打开（pushState 新条目），迟到的 popstate
      //   读 history.state 已是 null，会被误判成"用户返回"→ 把刚打开的新面板关掉。
      //   现在自家清理 back 前打时间戳，popstate 认领后就地消化（1 秒时效兜底：
      //   同文档 popstate 正常几 ms 内到达，超时的按用户返回处理）。用户真正的
      //   返回（系统手势/返回键）不设这个标记，行为不变；即便历史条目被错位消费，
      //   syncBackMark 的 push-on-need 也会在下一轮把缺的条目补回来。
      var popInFlightAt=0;
      // r75 · 防退两级白屏：popPanelHistory() 会被 syncBackMark() 反复调用
      //   （closeSettings 后 MutationObserver 60ms + 500ms 轮询都会再来一轮），
      //   而 history.back() 是**异步**的 —— 第一次发出后 history.state 仍是
      //   PANEL_MARK，第二次调用看到"有标记"就又发一次 back。WebView 连续退
      //   两级：dsh 面板 → dsh(popstate) → **about:blank（白屏）**；白屏页上
      //   再按返回 jsHandled=false 且 canGoBack=false → 直接退出 App。
      //   真机证据（r74 logcat）：14:11:51.146 popstate 认领后仅 17ms，
      //   14:11:51.163 就出现 path=blank 的新文档 —— 正是第二次 back 到达。
      //   修法：发出 back 前打时间戳，1.2s 内不再发第二次（覆盖 popstate 认领
      //   时延，远小于人手连退间隔；用户真实返回不设此标记，行为不变）。
      var popReqAt=0;
      function pushPanelHistory(){
        try{
          if(history.state&&history.state[PANEL_MARK])return;   // 已压过，别重复压
          history.pushState({dshPanel:1},'');
        }catch(e){}
      }
      function popPanelHistory(){
        try{
          if(history.state&&history.state[PANEL_MARK]){
            var _now=0;
            try{ _now=(new Date()).getTime(); }catch(e){ _now=0; }
            if(_now&&popReqAt&&_now-popReqAt<1200){return;}   // r75：在途锁，防重复 back
            popReqAt=_now;
            popInFlightAt=_now;
            history.back();
          }
        }catch(e){}
      }
      // r86 · 自家 back 的统一在途窗口：发出 history.back() 后的 popstate
      //   一律视为"自家清理的回响"，不得触发"关面板"逻辑。
      //   根因（r85 用户真机日志 21:32:42~43 钉死）：
      //     我们补压的 PANEL_MARK 条目被 popPanelHistory 弹掉后，迟到的 popstate
      //     读 state 已无标记 → 被误判成"用户侧滑/按键退回" → closeSettingsPanel
      //     → dshTap 点中 0×0 的隐藏 × → 面板+抽屉连坐全关 → 直接回首页。
      //     这正是"二级页面按返回直接回首页"的完整链条。
      //   修法：在途窗口从 popInFlightAt 单独计时改为与 popReqAt 对齐（取较大者），
      //   窗口内一律消化；窗口外且面板开着且**不在关闭流程**的 popstate 才真关。
      function selfBackInFlight(now){
        var t=popInFlightAt>popReqAt?popInFlightAt:popReqAt;
        return !!(t&&now&&now-t<1200);
      }
      // U17（r65）· P0 修复：这里原来写的是 `b.classList...`，而 `b` 只在
      //   mount() 里那个设置面板 IIFE 内定义（var b=document.body）。
      //   本处理器在 **mount 作用域**，读一个未声明变量必然抛 ReferenceError；
      //   事件监听器一抛异常就当场中断，后面几行 remove 永远执行不到
      //   → 面板关不掉 → 用户看到的就是「侧滑返回没反应」；而历史项已被消费，
      //   再滑一次就退出 App。**这才是「侧滑返回不能用」的真正根因**，
      //   跟 MainActivity 有没有 onBackPressed 无关。
      //   修法：处理器内部自取 body，并整体包 try/catch（监听器绝不能抛）。
window.addEventListener('popstate',function(ev){
        try{
          var bd=document.body;
          if(!bd){return;}
          dlog('[dsh] popstate: inflight='+(popInFlightAt?'yes':'no')
            +' ovOpen='+bd.classList.contains('dsh-ov-open')
            +' drawer='+bd.classList.contains('dsh-drawer-open'));
          // r73：自家清理 back 的 popstate 先到先认领 —— 跳过关闭逻辑，
          //   否则会会把"关闭后立刻重开"的新面板误杀（见 popInFlightAt 处注释）。
          var _pn=0;
          try{ _pn=(new Date()).getTime(); }catch(eP){ _pn=0; }
          if(popInFlightAt&&_pn&&_pn-popInFlightAt<1000){ popInFlightAt=0; return; }
          // 只处理「状态里没有我们的标记」的情况 = 用户侧滑/返回键退回来了
          var st=null;
          try{ st=history.state; }catch(e){}
          if(st&&st[PANEL_MARK]){return;}
          // r86：自家 back 的在途窗口内（含补压条目被弹后的回响）一律消化。
          //   另外只在"面板真的开着且用户不在主动关闭流程"时才关面板 ——
          //   dsh 路由产生的多余历史弹跳不再误杀整个设置面板。
          if(selfBackInFlight(_pn)){ return; }
          if(bd.classList.contains('dsh-ov-open')&&!ovCloseRequested){
            // 真的关，而不是"删掉我们的 class 假装关了"
            closeSettingsPanel();
          }
          // 抽屉是我们的 CSS 画出来的（transform），删 class 就是真关闭
          bd.classList.remove('dsh-drawer-open');
        }catch(e){}
        syncBackHandled();
      });
      function syncOvFlag(forceOpen){
        var b=document.body;
        if(!b){return;}
        // U18（r66）：顺手保证顶栏在树上。dsh 的 React 树整体替换 body 子节点时，
        //   我们的 #dsh-mtop/#dsh-scrim 会一起消失；而注入脚本每页只执行一次
        //   （mount() 有 SID 幂等守卫），没人再补 → 汉堡永久消失。
        //   这里幂等补回，配合 MutationObserver + 500ms 轮询构成自愈。
        ensureTopBar();
        var on=!!settingsOverlay();
        // r82：几何判据抖动时，若面板**还在树上且用户没在关闭**（sync() 的 !ov
        //   分支传 forceOpen），保持 ov-open —— 否则 ov-open 跟着几何一抖就掉，
        //   汉堡被 CSS 复活、jsBackHandled 误报 false，就是 r81 日志里"幽灵开合"
        //   的另一个来源。真关闭（卸载/残留）仍按几何判据收敛。
        if(!on&&forceOpen){on=true;}
        if(on!==b.classList.contains('dsh-ov-open')){
          if(on){
            b.classList.add('dsh-ov-open');
          }else{
            b.classList.remove('dsh-ov-open');
          }
          dlog('[dsh] syncOvFlag toggle ov-open -> '+on+' bodyClass='+b.className);
        }
        syncBackMark();
      }
      // 覆盖层（设置面板 / 抽屉）与"压一条历史"必须严格同进同出：
      //   只要有一个覆盖层开着，历史里就该有我们压的那一条，侧滑才有东西可退；
      //   都关了，就该把它消费掉，免得后面多退一次。
      function syncBackMark(){
        var bd=document.body;
        if(!bd){return;}
        // r82/r83：历史条目必须与**结构事实**同进同出（面板在不在 DOM），不能跟着
        //   几何判据抖动 —— r81 真机日志的"幽灵开合"（覆盖层无声消失又自己弹回，
        //   伴随 popstate inflight=yes、历史被消费）就是 need 在几何抖动时翻 false
        //   → popPanelHistory() 提前消费了历史。
        //   r83 修正：判据用 `_panel` 而不是 `_navList` —— r82 真机证明详情页下
        //   navList 会被 React 卸载，若用 navList 判"在不在"，详情页会误判"面板没了"
        //   → 历史照弹、ghost 关面板。面板容器 _panel 在详情页一定在树上。
        var mounted=!!document.querySelector('[class*="_overlay"] [class*="_panel"]');
        var need=mounted||bd.classList.contains('dsh-drawer-open');
        if(need){pushPanelHistory();}else{popPanelHistory();}
        syncBackHandled();
      }
    if(!inMarket){
    (function(){
      try{
      if(!mq.matches){return;}
      var b=document.body;
      dlog('[dsh] settings IIFE start: mq='+mq.matches+' path='+location.pathname+' inMarket='+inMarket);
      // r88 · **层级记忆**：l2/l3 类会被 React 重挂面板的过渡窗口清掉（r87 真机
      //   日志 22:03:48→50 实锤：点插件后 1.5s 内 l3 被清），而类一丢：← 拦截火不了、
      //   自愈只能靠 navList 可见性猜（类丢了 navList 就亮 → 错猜成 l2 → 用户
      //   被翻回设置列表）。JS 变量 _lvl 活在闭包里，React 怎么重挂都冲不掉。
      var _lvl=0;
      function setL2(){_lvl=2;b.classList.remove('dsh-s-l3');b.classList.add('dsh-s-l2');}
      function setL3(){_lvl=3;b.classList.remove('dsh-s-l2');b.classList.add('dsh-s-l3');}
      // r58 · 按设计稿：插件市场入口是**纯标题列表项**（与模型/插件/Agent 预设同格式），
      // r39 那个大卡片（图标 + 副标题 + 两个按钮）到此为止。
      // ⚠️ marketState() / announce() / installPnpmNow() / installMarketNow() / marketPageBar()
      //   已上移到 **mount 主体**（见 IIFE 闭合之后）。原因见该处的说明：
      //   它们同时服务于设置页与市场页，而这个 IIFE 在两种情况下都可能不执行
      //   —— 市场页（inMarket=true）整个跳过，宽屏（mq.matches=false）也在开头 return。
      // r58 就是把 marketPageBar() 定义在这里、却从 mount 主体调用，导致 ReferenceError
      // 把 mount() 拦腰截断，汉堡抽屉、rescue 自愈、Escape 关闭等 6 项功能静默失效。
      // 检测安装状态：market=dshmarket 版本（空=未装），pnpm=是否就绪。
      // 在**原生** navCell 右侧挂/移除状态徽标。
      // ⚠️ 为什么不直接把安装按钮删干净：市场页（浏览/搜索/已安装列表）本身
      //   要 dshmarket **已安装**才能打开。若设置页只剩纯列表项，未装用户会陷入
      //   「点进去什么都没有、也装不了」的死锁。所以未安装时保留一个最小入口：
      //   同一个列表项 + 右侧小徽标，点它弹确认框后安装；装完徽标自动移除，
      //   入口才真正退化成设计稿要求的纯列表项。
      // ===== U15（r65）· 插件市场入口 —— 这一节的根因有硬证据，不是猜 =====
      // 用户反馈「插件市场坏了」+ 截图：设置首页只有 通用设置/模型/插件/Agent 预设 四项。
      // 取证（可复现）：
      //   dsh-bundle 里 @deepseek-ai/dsh-web-frontend/dist 全量 grep「插件市场」= **0 处**；
      //   设置导航是由 dsh-client-ui-settings-* 这批客户端模块拼出来的，而包里只有
      //   settings-general / settings-models / settings-plugins / agent-preset，
      //   **没有 settings-market**。
      //   ⇒ 「插件市场」这一项是 dshmarket 这个独立 bundle 装上之后才注册进来的。
      // 于是 r58 那套「找到 dsh 原生 navCell、在它右侧挂个徽标」在**未安装**的机器上
      // 必然找不到节点 → 入口和徽标一起消失。用户看到的就是「市场入口没了」。
      // 修法：找不到原生项时**由我们注入一个同格式的列表项**（图标 + 标题 + 右侧徽标）。
      //   一旦 dshmarket 装好、dsh 渲染出原生项，我们的注入项自动退场。
      var MKT_MINE='dsh-mine-mkt';
      function mineCell(){
        var c=document.getElementById(MKT_MINE);
        if(!c||!c.parentNode){return null;}
        return c;
      }
      function removeMineCell(){
        var c=mineCell();
        if(c&&c.parentNode){c.parentNode.removeChild(c);}
      }
      function ensureMineCell(){
        var c=mineCell();
        if(c){return c;}                       // 幂等：已有就不再插
        var list=document.querySelector('.dsh-s-nav [class*="_navList"]');
        if(!list){return null;}
        // 类名刻意用 dshmine_ 前缀，并且**内含 _navCell / _navIcon / _navLabel** 子串：
        //   mobile.css 的适配规则全是按 [class*="_navCell"] 这种**子串**写的，
        //   于是注入项天然与 dsh 原生项同款外观（54px 行高 / 14px 圆角 / 15.5px 字重），
        //   不必再写一套样式，也不会因为挑中 dsh 的类名而误伤它（r38 铁律）。
        c=document.createElement('button');
        c.type='button';
        c.id=MKT_MINE;
        c.className='dshmine_navCell';
        c.setAttribute('data-dsh-mine','1');
        c.innerHTML='<svg class="dshmine_navIcon" width="16" height="16" viewBox="0 0 16 16"'
          +' fill="none" stroke="currentColor" stroke-width="1.4" stroke-linecap="round"'
          +' stroke-linejoin="round" aria-hidden="true">'
          +'<path d="M3 3h4v4H3zM9 3h4v4H9zM3 9h4v4H3zM9 9h4v4H9z"/></svg>'
          +'<span class="dshmine_navLabel">插件市场</span>';
        list.appendChild(c);
        ensureArrow(c);          // r67：右侧 chevron（设计稿图 2）
        return c;
      }
      // ===== r67 · 列表项右侧 chevron（设计稿图 2）=====
      // 用户拍板：设计稿 Screen 2 里四个列表项（插件市场 / 模型 / 插件 / Agent 预设）
      // **每一项**右侧都有一个灰色 chevron。r66 我按"真机原生项没有箭头"取了不加的
      // 方案，现在改回贴设计稿 —— 而且**四项都要有**：只给市场项加、其余没有，
      // 列表里会出现"为什么只有它有"，比全都没有更不像设计稿。
      // 三条铁律（沿用 r38 / r66）：
      //   ① 类名带 dshmine_ 前缀、标记用 data-dsh-*，绝不碰 dsh 的类名；
      //   ② append-only —— dsh 的节点由 React 管，**绝不移动/删除 dsh 的节点**
      //      （React 卸载时对不在其列表里的节点 removeChild → NotFoundError 整页崩）；
      //   ③ 幂等标记 data-dsh-arrow 挂在**我们自己的节点**上，而不是挂在 cell 上 ——
      //      React 重渲染把节点冲掉时标记随之消失，下一轮 sync()/MutationObserver
      //      就能补回来；挂在 cell 上会退化成"标记还在、箭头没了"的死状态。
      var ARROW_SVG='<svg class="dshmine_navArrow" width="16" height="16" viewBox="0 0 24 24"'
        +' fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"'
        +' stroke-linejoin="round" aria-hidden="true"><path d="M9 18l6-6-6-6"/></svg>';
      function ensureArrow(cell){
        if(!cell||!cell.querySelector){return null;}
        var old=cell.querySelector('[data-dsh-arrow]');
        if(old){return old;}                     // 幂等：已有就不再插
        var wrap=document.createElement('span');
        wrap.className='dshmine_navArrowWrap';
        wrap.setAttribute('data-dsh-arrow','1');  // 标记随节点存亡 → 被冲掉可自愈
        wrap.setAttribute('aria-hidden','true');  // 纯装饰，不进无障碍树
        wrap.innerHTML=ARROW_SVG;
        // 顺序（设计稿，左→右）：图标 → 标题 → 状态徽标 → chevron。
        //   徽标由 decorateEntry() 追加、可能晚于箭头插入，那时它会落到箭头右边；
        //   所以这里主动插到徽标**之后**，decorateEntry() 那边也对称地插在箭头之前。
        var flag=cell.querySelector('.dsh-mkt-flag');
        if(flag&&flag.parentNode===cell){ cell.insertBefore(wrap,flag.nextSibling); }
        else{ cell.appendChild(wrap); }
        return wrap;
      }
      function decorateNavArrows(){
        var cells=document.querySelectorAll('.dsh-s-nav [class*="_navCell"]');
        for(var i=0;i<cells.length;i++){
          // 单项失败不许影响其它项、更不许影响点击（整体 try/catch）
          try{ ensureArrow(cells[i]); }catch(e){}
        }
      }
      // 在列表项右侧挂/移除状态徽标（原生项与注入项共用）
      function decorateEntry(cell){
        var st=marketState();
        var old=cell.querySelector('.dsh-mkt-flag');
        var txt=st.market?(st.pnpm?'':'装 pnpm'):'未安装';
        if(!txt){
          if(old&&old.parentNode){old.parentNode.removeChild(old);}
          cell.removeAttribute('data-dsh-mkt-need');
          return;
        }
        cell.setAttribute('data-dsh-mkt-need',txt==='未安装'?'market':'pnpm');
        if(old){ old.textContent=txt; return; }
        var bd=document.createElement('span');
        bd.className='dsh-mkt-flag';
        bd.textContent=txt;
        // r67：徽标必须排在 chevron **左边**（设计稿：标题 → 徽标 → 箭头）
        var ar=cell.querySelector('[data-dsh-arrow]');
        if(ar&&ar.parentNode===cell){ cell.insertBefore(bd,ar); }
        else{ cell.appendChild(bd); }
      }
      function marketEntry(){
        var n=marketCell();
        if(n){
          removeMineCell();      // dsh 自己画了原生项 → 我们的注入项退出
          n.setAttribute('data-dsh-mkt','1');
          decorateEntry(n);
          ensureArrow(n);        // r67：原生市场项也要有 chevron（append-only）
          return;
        }
        // dsh 没画（= dshmarket 未安装）→ 我们补一个
        var m=ensureMineCell();
        if(!m){return;}
        m.setAttribute('data-dsh-mkt','1');
        decorateEntry(m);
      }
      function marketCell(){
        var cells=document.querySelectorAll('.dsh-s-nav [class*="_navCell"]');
        var i,t,al;
        // U7：分层匹配。原来只认 textContent **精确等于** '插件市场'/'PluginMarket'，
        //   dsh 改一次文案（多个空格、换成「插件 Market」、加个图标字符）就静默失效，
        //   症状是「点打开没反应」—— 用户完全无从判断是按钮坏了还是入口变了。
        //   现在按稳定性从高到低依次尝试，任一层命中即可：
        //   ① dsh 暴露的稳定 data 钩子 / testid；
        //   ② aria-label 或 title（中英模糊，不要求全等）；
        //   ③ 文本全等（保持原行为不变，作为快速路径）；
        //   ④ 文本模糊（最后兜底）。
        // U15：**必须跳过我们自己注入的那一项**。否则第 ④ 层（文本模糊）会命中
        //   注入项（它的 textContent 是「插件市场未安装」），marketCell() 便"找到了
        //   原生项"，removeMineCell() 与 ensureMineCell() 互相打架，入口反复闪。
        for(i=0;i<cells.length;i++){
          if(cells[i].getAttribute('data-dsh-mine')){continue;}
          if(cells[i].getAttribute('data-dsh-market')!=null
             ||cells[i].getAttribute('data-plugin-market')!=null
             ||cells[i].getAttribute('data-testid')==='plugin-market'){return cells[i];}
        }
        for(i=0;i<cells.length;i++){
          if(cells[i].getAttribute('data-dsh-mine')){continue;}
          al=((cells[i].getAttribute('aria-label')||'')+' '+(cells[i].getAttribute('title')||'')).toLowerCase();
          if(al.indexOf('插件')>=0||al.indexOf('市场')>=0
             ||al.indexOf('plugin')>=0||al.indexOf('market')>=0){return cells[i];}
        }
        for(i=0;i<cells.length;i++){
          if(cells[i].getAttribute('data-dsh-mine')){continue;}
          t=(cells[i].textContent||'').replace(/\s+/g,'');
          if(t==='插件市场'||t==='PluginMarket'){return cells[i];}
        }
        for(i=0;i<cells.length;i++){
          if(cells[i].getAttribute('data-dsh-mine')){continue;}
          t=(cells[i].textContent||'').replace(/\s+/g,'');
          if(t.indexOf('插件市场')>=0||t.toLowerCase().indexOf('pluginmarket')>=0){return cells[i];}
        }
        return null;
      }
      var FOCUSABLE='a[href],button:not([disabled]),input:not([disabled]),select:not([disabled]),textarea:not([disabled]),[tabindex]';
      // U9：Tab 陷阱每次都要 querySelectorAll + 逐个 getBoundingClientRect，
      //   浮层里元素多时开销明显（每次 Tab 一轮布局测量）。加 500ms 缓存：
      //   Tab 是连发的，人手跟不上 500ms 内的浮层结构变化，命中过期缓存也无感。
      var _fcOv=null,_fcList=null,_fcAt=0;
      function focusables(ov){
        var now=(new Date()).getTime();
        if(_fcList&&_fcOv===ov&&(now-_fcAt)<500){return _fcList;}
        var all=(ov||document).querySelectorAll(FOCUSABLE);
        var out=[];
        for(var i=0;i<all.length;i++){
          var e=all[i],r=e.getBoundingClientRect();
          if(e.getAttribute('tabindex')==='-1'){continue;}
          if(r.width>0&&r.height>0){out.push(e);}
        }
        _fcOv=ov;_fcList=out;_fcAt=now;
        return out;
      }
      var lastFocus=null;
      var panelOpen=false;
      function trapFocus(e){
        if(e.key!=='Tab'){return;}
        // U9：原来用 [class*="_panel"] 这个**宽泛**选择器取浮层 —— 页面上任何
        //   可见的 _panel（dsh 自己别的面板也算）都会被误认成设置浮层，
        //   于是 Tab 被困在错误的容器里，焦点看起来"丢了"。
        //   改用我们自己打的指纹 .dsh-s-ov（由 tagHosts 维护），只认设置浮层。
        var ov=document.querySelector('.dsh-s-ov');
        if(!ov){return;}
        var f=focusables(ov);
        if(!f.length){return;}
        var first=f[0],last=f[f.length-1];
        var ae=document.activeElement;
        if(e.shiftKey&&(ae===first||!ov.contains(ae))){
          e.preventDefault();last.focus();
        }else if(!e.shiftKey&&(ae===last||!ov.contains(ae))){
          e.preventDefault();first.focus();
        }
      }
      function enterFocus(ov){
        if(!ov){return;}
        var f=focusables(ov);
        if(f.length){try{f[0].focus();}catch(e){}}
      }
      function leaveFocus(){
        var back=lastFocus||document.getElementById('dsh-mbtn');
        lastFocus=null;
        if(back&&back.focus){try{back.focus();}catch(e){}}
      }
      // announce() 已上移到 mount 主体 —— 市场页的安装动作也要播报无障碍文本
      // r58 移除：dedupeMarket / openMarket / guardCard
      // 这三个函数都是为 r39 那个注入大卡片服务的：
      //   dedupeMarket —— 把原生「插件市场」navCell 藏起来（display:none），
      //                   好让大卡片取而代之。列表项化后**必须不再藏**，
      //                   否则设计稿要求的那个列表项根本不存在。
      //   openMarket   —— 大卡片按钮点了去 cell.click()，本质是「用 JS 代替
      //                   用户点击原生项」。现在用户直接点原生项，dsh 自己处理，
      //                   这层绕路整体删除。
      //   guardCard    —— 大卡片塞进 dsh 的 flex 容器后被挤变形时的几何自愈。
      //                   列表项是 dsh 自己的元素，天然被正确布局，**用不上**。
      // r58 移除：refreshMarket / refreshPnpm
      // 原先负责给大卡片的按钮与 chip 刷状态（「一键安装」→「打开市场」），
      // 并写 #dsh-mk-note 文案。现在状态统一由 marketState() 读取、
      // marketEntry() 把结果显示成列表项右侧的小徽标，不再需要 note 文案位。
      // ===== r58 · 安装动作（设置页列表项 与 市场页操作条 共用）=====
      // installPnpmNow() / installMarketNow() 已上移到 mount 主体（设置页与市场页共用，
      // 而本IIFE 在市场页/宽屏下不执行）。注意它们**不再直接调�� marketEntry()** ——
      // marketEntry 属于设置页、留在这个 IIFE 里，改由调用方通过 after 回调触发刷新。
      // ===== 「返回上一级」按钮（设计稿图 2）=====
      // 设置面板整体挂在侧栏抽屉里（body 恒为 dsh-s-l2），所以「返回上一级」= 退出设置
      // 回到聊天页。**自适应**：如果 dsh 自己已经在面板里画了关闭按钮（×，插件页/
      // Agent 预设页就是这样），就不再重复加我们的返回条；只有像设置首页那样
      // 没有关闭按钮的界面才补一个。
      // 判断元素是否「用户真的看得见」的 reallyVisible()、找可见关闭按钮的
      // visibleClose()、以及真正关闭浮层的 closeSettingsPanel() 已上移到
      // **mount 主体**（见上方）—— popstate 处理器也要用它们，而它在 mount 作用域。
      // 这里只留一个薄封装，保持 IIFE 内部调用点的可读性。
      function closeSettings(){ closeSettingsPanel(); }
      // 「返回上一级」条 —— **只出现在设置第一页**（用户明确要求）。
      //   第一页 = 导航列表页（通用设置/模型/插件/Agent 预设），此时 body 是 dsh-s-l2；
      //   详情页（插件、Agent 预设…）是 dsh-s-l3，**dsh 自己已经画了关闭按钮**，
      //   再塞一个就是重复出口。
      // 判据用的是**我们自己的状态 class**，不是去猜 DOM 里有没有 _close ——
      //   r60~r62 连续三次败在那个猜测上（存在性 / computedStyle / 几何都试过）。
      function ensureBackBar(){
        var ov=settingsOverlay();
        var bar=document.getElementById('dsh-back');
        // r88：加 _lvl!==3 —— 类丢失的过渡窗口里也不在详情页头上插返回条
        //   （用户在插件页时多一根"← 设置"只会添乱）。
        var onFirstPage=!b.classList.contains('dsh-s-l3')&&_lvl!==3;
        if(!onFirstPage){
          // L3：dsh 自己有 ×，刻意不给返回条（不是"判不准"，是设计决定）。
          if(bar&&bar.parentNode){bar.parentNode.removeChild(bar);}
          return;
        }
        if(!ov){
          // ===== r69 · 图1「设置 L2 顶部没有 ← 设置」=====
          //   真机现象：L2 第一页四个列表项和底部提示条都在，唯独返回条没了，
          //   而且**再也不会自己回来**（页面静止后 sync() 只在 DOM 变化/resize 时跑）。
          //   触发条件（取证结论）：settingsOverlay() 依赖 ovVisible() 的几何判据；
          //   面板做过渡动画时 rect 会短暂为 0 / 或祖先层级切换瞬间取不到 rect，
          //   这一轮就被判成"浮层不可见" ⇒ 这里把 bar 删掉 ⇒ 之后无人补回。
          //   修法：**只在 DOM 里真的已经没有浮层时**才删（结构性缺失是确定的证据），
          //   几何判据不确定时一律保留 —— 宁可多留一根返回条，也不能把用户唯一的
          //   出口弄丢。补回由 500ms 轮询调 sync() 兜底（见 mount 主体）。
          var gone=!document.querySelector('[class*="_overlay"] [class*="_navList"]');
          if(gone&&bar&&bar.parentNode){bar.parentNode.removeChild(bar);}
          return;
        }
        if(bar)return;
        bar=document.createElement('div');
        bar.id='dsh-back';
        bar.innerHTML='<button id="dsh-back-btn" type="button" aria-label="返回设置">'
          +'<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor"'
          +' stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round">'
          +'<path d="M19 12H5M12 19l-7-7 7-7"/></svg>'
          +'<span class="dsh-back-t">设置</span></button>';
        // U16（r65）· 返回条必须插进**真的可见**的容器 —— 这是「设置首页没有
        //   返回按钮」的真因，而且解释了为什么 r60~r64 连改四轮判断条件都没用：
        //   条件从来不是问题。dsh 的面板结构是
        //     panel > nav(.) + content > header(含 ×) + options
        //   而 mobile.css:84 在 l2（设置首页）把整个 _content display:none 掉
        //   —— 返回条插进 _content 里的 _header，等于插进一个不显示的容器：
        //   DOM 里有（所以冒烟测试一直是绿的）、屏幕上看不见。
        //   .dsh-s-nav 是我们自己打的指纹（tagHosts 维护），l2 下必然可见，改插它。
        var host=document.querySelector('.dsh-s-nav')||ov.querySelector('[class*="_header"]')||ov;
        host.insertBefore(bar, host.firstChild);
        bar.querySelector('#dsh-back-btn').addEventListener('click',function(e){
          e.preventDefault();e.stopPropagation();
          // r87：返回条语义 = 回设置页（关面板、抽屉保持），不再直接回首页。
          closeSettingsToDrawer();
        });
      }
      // 列表项点击：缺什么就先装什么。已装齐则返回 false，交给 dsh 原生逻辑
      // （它自己会跳到市场页），我们不干预。
      // confirm 在某些 WebView 上不可用，此时退化为直接装 —— 宁可多装一次，
      // 也不能因为弹不出框就把功能卡死。
      function onEntryTap(e){
        var cell=e.currentTarget;
        var need=cell.getAttribute('data-dsh-mkt-need');
        var mine=cell.getAttribute('data-dsh-mine')==='1';
        if(!need){
          if(!mine){return false;}   // dsh 原生项、依赖已齐 → 交给 dsh 自己的路由
          // 我们自己注入的项：依赖已齐却仍没有原生项 = 刚装完还没重启 dsh。
          // 这里绝不能返回 false —— 那会落到下面 `t.closest('_navCell')` 分支把
          // 界面顶到 l3（隐藏导航列表），变成"点了一下界面就没了"。
          try{ window.alert('插件市场已安装。重启应用后，这里会变成 dsh 原生的市场入口。'); }catch(err){}
          announce('插件市场已安装，重启应用后生效');
          return true;
        }
        e.preventDefault();e.stopPropagation();
        var msg=(need==='market')
          ? '插件市场尚未安装。\n\n安装后即可浏览并安装插件。\n\n现在下载安装？'
          : '安装插件需要 pnpm 包管理器。\n\n现在下载安装？';
        var ok=true;
        try{ ok=window.confirm(msg); }catch(err){ ok=true; }
        if(!ok){return true;}
        // 安装完成后刷新列表项徽标 —— marketEntry() 留在这个 IIFE 里（只服务设置页），
        // 所以通过 after 回调把刷新动作传给出售给 install* 的外部函数。
        if(need==='market'){ installMarketNow(function(){marketEntry();}); }
        else{ installPnpmNow(function(){marketEntry();}); }
        return true;
      }
      // marketPageBar() 已上移到 mount 主体（只在市场页有意义，且必须能被 mount 直接调用）。
      // U13：把返回键的预期固化到界面上（文案在 r60 已按新行为更新）。
      // 背景：r49 那版 MainActivity.onResume 每次回前台都 clearHistory()，因为 r43
      //   整页跳 /dsh-market 在历史里留了 404 文档，侧滑回去就是一整页白屏。
      //   代价是返回键/侧滑一律退 App —— 用户看不到代码注释，只会觉得「返回键失灵」。
      // r60 起 clearHistory() 改为**每个进程只清一次**，侧滑/返回键可以逐级回退了，
      //   所以文案也从「会直接退出应用」改成「可回到上一级（第一级时才退出）」。
      // 只插一次（幂等），挂在**我们自己的** .dsh-s-nav 里，不碰 dsh 类名（r38 铁律）。
      var backHintShown=false;
      function backHint(){
        if(backHintShown){return;}
        var nav=document.querySelector('.dsh-s-nav');
        if(!nav){return;}
        backHintShown=true;
        var t=document.createElement('div');
        t.className='dsh-s-tip';t.id='dsh-s-tip';
        t.textContent='提示：侧滑或按返回键可回到上一级页面（已在第一级时会退出应用）。';
        nav.appendChild(t);
      }
// r83 · **列表可见性判据**（l2/l3 的权威来源）。
      //   r82 真机日志（r82-back.log）证明：详情页(L3)下 dsh 的 React **会把
      //   navList 卸载掉**（r75 记录的"结构 DOM 缺席"）——所以"navList 在不在
      //   DOM"不能单独当证据；但"navList 是否**可见**"可以：
      //     · navList 可见（在 DOM 且没被隐藏）→ 列表页（L2）
      //     · navList 不可见（被卸载 / 被 l3 的 CSS display:none 藏掉）→ 详情页（L3）
      //   这是与"类存不存在"无关的硬事实 —— 类丢了也能当场重建正确的层级。
      function settingsListShown(){
        try{
          var ov=document.querySelector('.dsh-s-ov');
          if(!ov){return false;}
          var nl=ov.querySelector('[class*="_navList"]');
          if(!nl){return false;}                 // navList 被 React 卸载 → 详情页
          var cs=window.getComputedStyle(nl);
          if(cs&&(cs.display==='none'||cs.visibility==='hidden'||cs.visibility==='collapse')){return false;}
          try{var r=nl.getBoundingClientRect();
            if(r&&(r.width<=0||r.height<=0)){return false;}}catch(eR){}
          return true;
        }catch(e){return false;}
}
      // r83 · healLevel：浮层可见（或结构在树上）⇒ body 必为 l2 或 l3 之一，
      //   且与列表可见性一致。r82 之前只补 l2 —— 详情页的 l3 一旦被清就永久丢失
      //   （l3 只能靠点 navCell 设置），返回时必然误判。r83 用 settingsListShown()
      //   补回正确的层级：navList 可见 → l2；navList 不可见（卸载/隐藏）→ **l3**。
      //   ⚠️ 必须在 **两条 sync 路径都跑**：详情页下 navList 被 React 卸载后
      //   settingsOverlay()（依赖 navList 判存在）会返回 null，若只放在 ov 路径，
      //   详情页的类丢了就永远没机会恢复（r82 真机日志实锤）。
      function healLevel(){
        try{
          if(!b.classList.contains('dsh-s-l2')&&!b.classList.contains('dsh-s-l3')){
            var overdue=ovCloseRequested&&((new Date()).getTime()-ovCloseAt)>OV_CLOSE_GRACE;
            if(!ovCloseRequested||overdue){
              // r88：**层级记忆优先** —— 类丢失多发生在 React 重挂面板的过渡窗口，
              //   此刻 navList 会因为 l3 类不在而"亮出来"，按可见性猜必猜成 l2
              //   （用户被翻回设置列表）。_lvl 活在闭包里不受重挂影响，先按它恢复；
              //   _lvl=0（刚退出过面板/首次打开）才退回 DOM 启发式。
              if(_lvl===3){setL3();}
              else if(_lvl===2){setL2();}
              else if(settingsListShown()){setL2();}else{setL3();}
              if(overdue){ovCloseRequested=false;}
            }else{
              // r81 [diag]：浮层可见但 l2/l3 全缺，且处于"请求关闭"宽限期内 →
              // 本轮不恢复层级。若此日志在面板长时间打开时持续出现（800ms 宽限期
              // 早该过期），说明 ovCloseRequested 被卡死。
              try{
                dlog('[dsh] sync: L2/L3 restore suppressed by ovCloseRequested'
                  +' ovCloseAge='+((new Date()).getTime()-ovCloseAt)
                  +' body='+b.className);
              }catch(eD){}
            }
          }
        }catch(eH){}
      }
      function sync(){
        var ov=settingsOverlay();
        if(!ov){
          // r69：先记下"这一轮是不是**关闭流程**走出来的"。两种"看不见"要分开：
          //   ① 没人要求关闭，只是几何判据在过渡动画里看不准 → **保留层级**；
          //   ② 关闭流程已经走完、dsh 留了个不可见的残影 → 按 r66 的结论处理，
          //      **不复活面板**（否则用户在聊天页被莫名其妙弹回设置界面）。
          var wasClosing=ovCloseRequested;
          ovCloseRequested=false;   // 浮层判定不可见 → 清掉"用户要求关闭"的标记
          ovCloseFails=0;           // r69：关闭成功过，失败计数归零
          ovAbandoned=false;        // r69：真关掉了 ⇒ 下次关闭请求重新从零开始
          // r82/r83：以**结构证据**决定清不清层级（r82 真机日志修正）：
          //   r82 用 navList 判 mounted 是错的 —— 详情页下 navList 被 React 卸载，
          //   mounted 恒为 false，l3 照样被清。r83 改用 **面板容器 _panel**
          //   （详情页里 _panel 一定在），并且无论清没清，下一轮都会按列表
          //   可见性把正确的层级补回来（见 healLevel）。
          var mounted=!!document.querySelector('[class*="_overlay"] [class*="_panel"]');
          if(!mounted||wasClosing){
            b.classList.remove('dsh-s-l2');b.classList.remove('dsh-s-l3');
          }
          // r82：面板还在树上且没在关 → ov-open 也保持（forceOpen），
          // 避免几何抖动把"开着"翻成"关了"（幽灵开合的另一半）。
          syncOvFlag(mounted&&!wasClosing);
          // r83：详情页 navList 被卸载 → settingsOverlay 恒 null → 只有在这里
          // 才能触达 healLevel（面板还在树上、没在关闭时）。
          if(mounted&&!wasClosing){healLevel();}
          if(panelOpen){panelOpen=false;leaveFocus();}
          return;
        }
        syncOvFlag();
        tagHosts();
        marketEntry();
        // r67：四个列表项（插件市场 / 模型 / 插件 / Agent 预设）的右侧 chevron。
        //   必须放在 marketEntry() **之后**：徽标先落位，箭头才能排在它右边。
        //   dsh 是 React 管理的，重渲染可能把追加的节点冲掉 —— 靠这里每轮
        //   sync()（MutationObserver + 500ms 轮询都会走到）幂等补回，与 r66
        //   顶栏 ensureTopBar() 的自愈是同一套手法。
        decorateNavArrows();
        ensureBackBar();
        backHint();
        detectDark();
        if(!panelOpen){panelOpen=true;
          lastFocus=document.activeElement;
          // U9：与 trapFocus 一致，改用我们自己的指纹 .dsh-s-ov。
          // 上面 tagHosts() 已确保指纹已打上；宽泛的 [class*="_panel"]
          // 可能命中 dsh 别的面板，焦点会进错容器。
          enterFocus(document.querySelector('.dsh-s-ov'));
        }
        // r83：healLevel 抽成函数（见上方定义），ov 路径与 !ov 路径共用
        healLevel();
      }
      // r72（P3-1）：原这里的空 syncBack 函数与它的 7 处调用已全部删除 ——
      //   它是历史上的占位（真正干活的是 mount 作用域的 syncBackMark，
      //   经 syncOvFlag 调用），留着只会误导读者以为有副作用。
      // U16（r65）：原生返回（返回键 / 侧滑手势）的**唯一入口**。
      //   MainActivity.handleBack() 会先问 `dshNative.setBackHandled()` 上报的状态，
      //   需要 JS 处理时就 evaluateJavascript 调这里，而不是盲目 history.back()。
      //   返回 true = 这一级已经被消化掉（原生不该再退出 App）。
      //   状态机（与 Escape / 返回条的语义保持一条线）：
      //     抽屉开着      → 关抽屉
      //     L3（详情页）  → 回 L2（列表页）
      //     L2（列表页）  → 退出设置（= 回聊天页）
      function backOneLevel(){
        var handled=false;
        var _now=0;
        try{
          // r73：去重闸 —— 侧滑手势触发后，系统返回手势/返回键（极小概率）紧跟着
          //   也到达时只消化一次，防止一次滑动退两级。人手不可能在 220ms 内合法地
          //   连退两级（一次完整滑动手程就要 200ms+），真实连滑/连按不受影响。
          //   ⚠️ 桩里 Date 是可控虚拟时钟（win.advance），所以本闸在冒烟测试里可断言。
          try{ _now=(new Date()).getTime(); }catch(e0){ _now=0; }
          if(_now&&_backLastAt&&_now-_backLastAt<220){return true;}
          // r81：按下瞬间的**前置状态**快照（分支执行后会改写 body class，
          //   诊断日志必须用按下前的值才能还原决策现场）。
          var pre=null;
          try{
            if(b){
              pre={drawer:b.classList.contains('dsh-drawer-open'),
                   l2:b.classList.contains('dsh-s-l2'),
                   l3:b.classList.contains('dsh-s-l3'),
                   cls:b.className};
            }
          }catch(eP){}
          if(b){
            // r79：**优先级对调 —— 设置面板优先，抽屉其后**。
            //   r78 及之前抽屉分支排第一，而设置面板就住在抽屉（sidebarCol）里：
            //   开设置时 dsh-drawer-open 留在 body 上，按返回先命中抽屉分支 →
            //   只删 drawer class → 侧栏滑出屏幕 → 住在里面的整张设置面板
            //   跟着消失（r78 真机日志：backOneLevel l3=true 后 200ms
            //   ov-open 翻 false，截图直接落到聊天页 = "返回上上级"）。
            //   正确序：面板可见时它就是最顶层，返回先退面板层级；
            //   抽屉分支只服务"纯抽屉"（无面板）状态。
            // r83：闸门从 `settingsOverlay()`（几何判据）放宽为
            //   `几何判据 || 面板结构存在` —— r82 真机日志显示返回瞬间
            //   ov=0 但用户明明在详情页（面板在树上）：几何抖动一次就让它
            //   掉进抽屉分支关抽屉 → 聊天页（"返回上上级"再现）。只要
            //   .dsh-s-ov（我们打的指纹）还在树上，就按设置面板处理。
            var ovGate=settingsOverlay();
            if(!ovGate){
              try{ ovGate=document.querySelector('.dsh-s-ov'); }catch(eG){ ovGate=null; }
            }
            if(ovGate){
              // r88：l3 判定 = 类 || 层级记忆（类会被 React 重挂的过渡窗口清掉，
              //   记忆不会；详见 _lvl 声明处注释）。
              if(b.classList.contains('dsh-s-l3')||(_lvl===3&&!b.classList.contains('dsh-s-l2'))){
                // r84 · l3 分两档（真机 CDP 取证修正）：
                //   ① 内容区有**展开的卡片**（插件页手风琴：li.YyYd_a_cardOpen，
                //      展开 68→351px 露出配置表单）= 用户的"三级页面" →
                //      **收起卡片**（点击其头部 toggle，真机实测有效），停在 l3；
                //      旧代码直接 setL2 会跳到设置列表，跳过插件列表页 ——
                //      这正是"三级页面按返回直接回上上级"的根因。
                //   ② 无展开卡片（普通详情/表单页 = 二级）→ 纯 CSS 翻转回
                //      设置列表（r78 语义不变）。
                // r85 [diag]：collapse 返回值进日志 —— 按键路径 vs CDP 直调的
                //   差异（r84 真机验证暴露）一眼可见。
                var coll=collapseExpandedCards();
                try{ dlog('[dsh] backOneLevel l3: collapse='+coll); }catch(eC){}
                if(!coll){
                  setL2();
                }
              }
              else if(b.classList.contains('dsh-s-l2')){
                // r87：列表页（二级页面）按返回 → **退出设置面板、抽屉保持展开**
                //   （= 回到设置页），不再直接回首页。
                closeSettingsToDrawer();
              }
else{
                // r81 · **状态丢失兜底**（r80 真机日志钉死根因）：
                //   backOneLevel: handled=true ov=1 l3=false bodyClass=(空)
                //   —— 面板明明可见（ov=1），但 l2/l3 一个都不在 body 上。
                //   此时用户在二级详情页按返回，被当成"列表页"执行了 closeSettings()，
                //   整张面板被关掉，直接落到聊天页 = "返回上上级"。
                // r83 · 缺类时：
                //   · ovCloseRequested 为真（用户**主动要求过关闭**，如 r73 去重闸
                //     场景里关过又重开）→ 按列表页语义退到设置页；
                //   · 否则一律 **setL2 回列表**（详情页返回必到列表页，绝不跳聊天；
                //     就算真在列表页也只是多按一次才退出到设置页）。
                //   ⚠️ 不能反过来：缺类一律关闭就是 r80 的"上上级"。
                if(ovCloseRequested){closeSettingsToDrawer();}
                else{setL2();}
              }
              handled=true;
            }
            else if(b.classList.contains('dsh-drawer-open')){close();syncBackMark();handled=true;}
          }
        }catch(e){}
        if(handled){
          try{ _backLastAt=_now; }catch(e2){}
          // r73：消化完立刻把最新的覆盖层状态回写给原生。为什么必须：
          //   closeSettingsPanel() 只清 body class，不触发 syncBackHandled() ——
          //   原来要等 MutationObserver→sync()（~60ms 后）才纠正上报，这个窗口里
          //   原生还以为"该由 JS 处理"，紧接着的第二次返回会白进来一趟。
          //   syncBackHandled() 内部有去重，状态没变时不过桥。
          try{ syncBackHandled(); }catch(e3){}
        }
        if(!handled){
          // 自愈：走到这里说明原生侧的上报已经过期（例如浮层被 dsh 自己关掉、
          // 而我们还没跑下一轮 sync）。若不立刻纠正，原生侧会继续以为"已恢复"，
          // 表现就是**返回键彻底没反应** —— 比退出 App 更难排查。
          _backHandled=false;
          try{
            if(window.dshNative&&window.dshNative.setBackHandled){window.dshNative.setBackHandled(false);}
          }catch(e){}
        }
        try{ dlog('[dsh] backOneLevel: handled='+handled
          +' pre[drawer='+(pre?pre.drawer:'n/a')+' l2='+(pre?pre.l2:'n/a')+' l3='+(pre?pre.l3:'n/a')+' cls='+(pre?pre.cls:'(none)')+']'
          +' post[drawer='+(b?b.classList.contains('dsh-drawer-open'):'n/a')
          +' ov='+(settingsOverlay()?'1':'0')
          +' l2='+(b?b.classList.contains('dsh-s-l2'):'n/a')
          +' l3='+(b?b.classList.contains('dsh-s-l3'):'n/a')
          +' navList='+(document.querySelector('[class*="_overlay"] [class*="_navList"]')?'1':'0')
          +' cls='+(b?b.className:'(none)')+']'); }catch(e){}
        return handled;
      }
      try{ window.__dshBack=backOneLevel; }catch(e){}
      // r69：把设置页的 sync() 也挂出来，供 mount 主体的 500ms 轮询调用。
      //   为什么需要：sync() 原来只挂在 MutationObserver 与事件上 —— 页面一旦静止
      //   （动画结束、用户不动）就没人再跑，而"返回条被误删"这类损伤只在静止后
      //   才暴露，于是永远补不回来（真机图1）。轮询里已有 document.hidden 保护，
      //   且 sync() 全程幂等，代价可以忽略。
      try{ window.__dshSettingsSync=sync; }catch(e){}
      document.addEventListener('click',function(e){
        var t=e.target;
        if(!t||!t.closest){sync();return;}
        var closer=t.closest('[class*="_close"],button[aria-label*="关闭"],button[aria-label*="Close"]');
        if(closer){
          // r72（P1-1）：只拦「设置面板壳层自己的」关闭键 —— 旧逻辑对全文档任何
          //   _close 生效，L3 下市场崩溃浮层/插件详情等**嵌套弹窗**自己的 × 也被吞掉
          //   （捕获层 stopPropagation 后点击根本到不了弹窗自己的监听器，
          //   浏览器实测 subDialogCloseFired=0、弹窗永远关不掉）。
          //   判据：关闭键的最近 dialog 祖先必须是设置浮层本身；
          //   嵌在 [role=dialog]/_overlay/_dialog/_modal/market-crashed 里的关闭键
          //   一律放行，交给弹窗自己关闭。
          var ovC=settingsOverlay();
          var shell=!!(ovC&&ovC.contains(closer)
            &&ovC===closer.closest('[role="dialog"],[class*="_overlay"],[class*="_dialog"],[class*="_modal"],[data-dsh-market-crashed]'));
          if(shell){
            // r87：三级页面点壳层 ←（arrowizeClose 把 × 画成了返回箭头）=
            //   **返回二级页面（设置列表）**，不再放行给 React 整关面板。
            //   用户实测（r86 真机日志 21:40:27）：放行 → dsh 卸载整个面板 →
            //   落在设置页，跳过了二级 —— 与硬件返回键语义（三级→二级）不一致。
            //   拦截只限 body 是 dsh-s-l3 时；捕获层 stopPropagation 掐断
            //   React 的根监听，嵌套弹窗不受影响（shell 判据照旧，r72 验证过）。
            //   与硬件键一致：先收起展开的手风琴卡片，没有才翻回列表。
            //   ⚠️ 只拦**真实用户点击**：dshTap 程序化派的 × 点击（closeSettingsPanel
            //   的关闭路径）带在途标记，跳过拦截让 React 真关面板。
            // r88：拦截条件从"body 有 l3 类"改为"**← 按钮看得见**"——
            //   r87 真机日志实锤（22:03:50 l3 被过渡窗口清掉 → 22:04:06 点 ←
            //   没拦住 → React 整关面板 → 跳过二级直接到设置页）。而 l2（设置列表）
            //   下 × 藏在 display:none 的 _content 里**不可见**——所以**看得见的 ←
            //   一定意味着用户在详情页**，与类是否丢过无关。
            var _tn=0; try{ _tn=(new Date()).getTime(); }catch(eN){ _tn=0; }
            var _br=null; try{ _br=closer.getBoundingClientRect(); }catch(eG){ _br=null; }
            var _vis=!!(_br&&_br.width>1&&_br.height>1);
            var _prog=!!(_tn&&_dshTapAt&&_tn-_dshTapAt<80);
            if(_vis&&!_prog&&(b.classList.contains('dsh-s-l3')||_lvl===3)){
              e.preventDefault();e.stopPropagation();
              var coll2=collapseExpandedCards();
              try{ dlog('[dsh] shell← on l3: collapse='+coll2
                +' (btn '+Math.round(_br.width)+'x'+Math.round(_br.height)+' lvl='+_lvl+')'); }catch(eS){}
              if(!coll2){ setL2(); }
              return;
            }
            // r78：其余场景壳层 × 原样放行，连 class 也不动 ——
            //   React 负责真关，sync() 的不变量自愈负责把 class 校正回来。
            return;
          }
          // 非 shell 关闭键：不拦，继续走下面的通用逻辑（弹窗自己处理点击）
        }
        var ov=settingsOverlay();
        var onMask=ov&&(t===ov||(t.className&&String(t.className).indexOf('_mask')>=0));
        if(onMask){_lvl=0;b.classList.remove('dsh-s-l2');b.classList.remove('dsh-s-l3');return;}
        // r60 曾在这里加过 `if(t.closest('[role="tab"]')){ setL3(); }`，**r61 已移除**。
        //   当时的判断是「tab 的内容在 [class*="_content"] 里，被 l2 的 display:none 藏住了」。
        //   真因查清了：dsh 的 tab 面板 class 是 CSS-module 哈希名（不含 _content），
        //   它靠 hidden 属性切换，是 `.panel` 自带的 display **盖过了** UA 的
        //   `[hidden]{display:none}` —— 已在 mobile.css 用
        //   `[role="tabpanel"][hidden]{display:none !important}` 兜底。
        //   强行 setL3() 解决不了问题，只会污染 l2/l3 状态机（把普通面板也顶到 L3）。
        // r58：插件市场列表项缺依赖时先补依赖（确认框 → 安装）；
        // 已装齐时 onEntryTap 返回 false，不拦截 —— 由 dsh 原生项自己跳市场页。
        var ent=t.closest('[data-dsh-mkt]');
        if(ent&&onEntryTap({currentTarget:ent,
                            preventDefault:function(){e.preventDefault();},
                            stopPropagation:function(){e.stopPropagation();}})){
          return;
        }
        var cell=t.closest('[class*="_navCell"]');
        if(cell&&b.classList.contains('dsh-s-l2')){setL3();}
        else{sync();}
      },true);
      document.addEventListener('keydown',function(e){
        if(e.key==='Escape'&&b.classList.contains('dsh-s-l3')){
          e.preventDefault();e.stopPropagation();setL2();
        }
        trapFocus(e);
      },true);
      var pend=null;
      function sched(){
        if(pend){return;}
        pend=setTimeout(function(){pend=null;sync();},60);
      }
      if(window.MutationObserver){new MutationObserver(sched).observe(b,{childList:true,subtree:true});}
      window.addEventListener('resize',function(){sched();});
      window.addEventListener('orientationchange',function(){sched();});
      sync();
      dlog('[dsh] settings IIFE done: __dshBack='+(typeof window.__dshBack));
      }catch(e){
        derr('[dsh] settings IIFE threw:',e);
        try{
          var _bd=document.body;
          derr('[dsh] state@throw: bodyClass='+(_bd?_bd.className:'(none)')
            +' overlay='+(settingsOverlay()?'yes':'no')
            +' drawer='+(_bd?_bd.classList.contains('dsh-drawer-open'):'n/a')
            +' ovOpen='+(_bd?_bd.classList.contains('dsh-ov-open'):'n/a'));
        }catch(e2){}
      }
    })();
    }
    // ===== r73 · 安卓侧滑返回上一级（WebView 页面内手势）=====
    // 背景：r64~r71 已把「系统返回手势/返回键 → MainActivity.handleBack() →
    //   __dshBack()」这条链修通，但它依赖系统把边缘手势翻译成 KEYCODE_BACK：
    //   · 三键导航的手机**根本没有**边缘手势 —— 用户在屏幕上侧滑，WebView 里
    //     什么都不会发生（这正是"侧滑返回没反应"在部分真机上的成因）；
    //   · 手势导航机型系统只认左右边缘 ~24px 的起手区，起手稍微靠里就没人管。
    //   这里把「侧滑返回」做成 WebView 自己的手势：从屏幕左/右边缘横向滑动并松手
    //   ⇒ 走与返回键**完全相同**的 backOneLevel() 状态机
    //   （抽屉开着→关抽屉 / L3→L2 / L2→退出设置），行为与返回键严格一致。
    // 设计原则（延续 r38/r65/r69 的铁律）：
    //   ① 不碰 dsh 的节点：指示器 #dsh-swipe-ind 是我们自己的节点，监听挂 document；
    //   ② 只在「有上一级可退」时接管：抽屉或设置面板开着（body 两个状态 class）。
    //      聊天页上绝不抢触摸 —— 那里没有上一级，退出 App 是系统返回的职责；
    //   ③ 绝不影响滚动与点击：手势只在横向位移明显占优时才接管（tap 完全不受影响，
    //      竖滑到 16px+ 就立刻归还）；起手点在横向滚动容器里（市场 tab 条 / 代码块）
    //      或输入框里一律放行 —— 那些地方横滑有自己的语义；
    //   ④ 与系统返回手势共存：手势导航机型上系统认领的触摸会给我们发 touchcancel，
    //      收到即放弃，返回交给系统走 handleBack()（同一条状态机），不会双重返回；
    //      backOneLevel() 里另有 220ms 去重闸兜底（见该函数注释）。
    var SWIPE_EDGE=42;        // 起手判定区：左右边缘各 42px（三键导航全靠它；比系统 ~24px 稍宽）
    var SWIPE_CLAIM=12;       // 横向位移 ≥12px 且大于纵向 → 接管手势
    // r78：96px 太苛刻 —— r76/r77 两轮真机日志里 swipe fire 一次都没出现过
    //   （快甩通常 50~70px 就松手）。降到 52px（约屏宽 1/8）；误触由
    //   "横移明显大于纵移 + 边缘起手 + 壁垒区放行"三道闸挡住。
    // r82：r81 真机日志 5 次 touchstart（x=26~42）0 次触发 —— 用户"轻扫"习惯
    //   往往只有 30~45px。降到 40px；三道闸照旧，且加了 claimed/touchend 的
    //   位移埋点，下轮日志直接看到用户到底滑了多少、卡在哪一档。
    var SWIPE_ARM=40;         // 松手时累计拖动 ≥40px → 触发返回
    var SWIPE_IND_MAX=72;     // 指示器最大跟随距离（px）
    var swT=null;             // 进行中的手势 {side:'l'|'r', x0, y0, claimed, armed}
    function swipeTargetAvailable(){
      var bd=document.body;
      if(!bd){return false;}
      return bd.classList.contains('dsh-drawer-open')||bd.classList.contains('dsh-ov-open');
    }
    // 起手点是否落在「横滑另有语义」的地方：横向滚动容器 / 文本输入。
    // ⚠️ 只读，绝不修改任何节点；祖先链限 12 层，与 ovVisible() 同一防爆炸手法。
    function swipeInReserved(t){
      var n=t,hop=0;
      while(n&&n.nodeType===1&&hop<12){
        hop++;
        var tg=String(n.tagName||'').toUpperCase();
        if(tg==='INPUT'||tg==='TEXTAREA'||tg==='SELECT'){return true;}
        if(n.isContentEditable){return true;}
        var ox=null;
        try{ ox=window.getComputedStyle(n).overflowX; }catch(e){ ox=null; }
        if((ox==='auto'||ox==='scroll')&&n.scrollWidth>n.clientWidth+1){return true;}
        n=n.parentNode;
      }
      return false;
    }
    // 指示器：懒创建 + 幂等补挂（React 冲掉 body 子节点后，下一次滑动自动重建 ——
    // 与 ensureTopBar() 的自愈是同一套手法）。
    function swipeIndicator(){
      var ind=null;
      try{ ind=document.getElementById('dsh-swipe-ind'); }catch(e){ ind=null; }
      if(!ind){
        try{
          ind=document.createElement('div');
          ind.id='dsh-swipe-ind';
          ind.setAttribute('aria-hidden','true');
          ind.innerHTML='<svg viewBox="0 0 24 24" width="18" height="18" fill="none"'
            +' stroke="currentColor" stroke-width="2.4" stroke-linecap="round"'
            +' stroke-linejoin="round"><path d="M19 12H5M12 19l-7-7 7-7"/></svg>';
          document.body.appendChild(ind);
        }catch(e2){ return null; }
      }else if(ind.parentNode!==document.body){
        try{ document.body.appendChild(ind); }catch(e3){}
      }
      return ind;
    }
    function swipeIndicatorShow(x,y){
      var ind=swipeIndicator();
      if(!ind){return;}
      try{
        ind.style.transform='translate('+x+'px,'+y+'px) translate(-50%,-50%)';
        ind.style.display='flex';
      }catch(e){}
    }
    function swipeIndicatorArm(on){
      var ind=null;
      try{ ind=document.getElementById('dsh-swipe-ind'); }catch(e){ ind=null; }
      if(ind){ try{ ind.classList[on?'add':'remove']('dsh-swipe-armed'); }catch(e2){} }
    }
    function swipeIndicatorHide(){
      var ind=null;
      try{ ind=document.getElementById('dsh-swipe-ind'); }catch(e){ ind=null; }
      if(ind){ try{ ind.style.display='none'; ind.classList.remove('dsh-swipe-armed'); }catch(e){} }
    }
    function swipeFire(){
      dlog('[dsh] swipe fire: __dshBack='+(typeof window.__dshBack));
      swipeIndicatorHide();
      // 到达触发阈值的轻震动反馈（manifest 已有 VIBRATE 权限；不支持则静默跳过）
      try{ if(navigator.vibrate){navigator.vibrate(10);} }catch(e){}
      try{
        if(typeof window.__dshBack==='function'){ window.__dshBack(); }
      }catch(e2){}
    }
    function swipeReset(){ swT=null; swipeIndicatorHide(); }
    // ===== r89 · ← 按钮手势层接管（"三级页面点 ← 仍回设置页"的最终修法）=====
    //   r88 真机日志钉死：点击拦截此路不通 —— 用户点壳层 ← 时 click 事件
    //   被 React 在更内层消化（touchstart 留痕 x=328、click 无记录），我们的
    //   document 捕获层从未看到。修法换到**手势层**：touchstart/touchend
    //   这两个事件我们**收到了**（侧滑日志即证）——
    //   ① touchstart 落点在「可见的壳层关闭按钮（←）」矩形内 → 记 tapBack；
    //   ② touchend 位移 <12px（是 tap 不是滑）→ 直接执行 backOneLevel()
    //   （与硬件返回键同一条状态机：先收手风琴卡片、否则回二级列表），
    //   并 preventDefault 掐断 React 的后续 click 派发。
    //   ⚠️ 只认「按钮可见」（r88 的可见性判定）：l2 列表页 × 藏在
    //   display:none 的 _content 里，rect 为 0×0，天然不命中 ——
    //   l2 的"← 设置"返回条是我们自己的按钮（dshTapAt 在途标记之外的
    //   真实点击），不受影响。
    var tapBack=null;
    function shellCloseRect(){
      try{
        var ov=document.querySelector('.dsh-s-ov');
        if(!ov){return null;}
        var c=ov.querySelector('[class*="_content"] [class*="_header"] [class*="_close"]');
        if(!c){return null;}
        var r=c.getBoundingClientRect();
        if(!r||r.width<2||r.height<2){return null;}   // 不可见（l2 或被藏）不接管
        // 放宽 12px 热区：圆按钮小（34×34），手指中心常落在边缘外
        return {x:r.left-12,y:r.top-12,w:r.width+24,h:r.height+24,node:c};
      }catch(e){return null;}
    }
    document.addEventListener('touchstart',function(e){
      try{
        tapBack=null;
        if(!mq.matches){return;}
        if(!e||!e.touches||e.touches.length!==1){return;}
        var bd=document.body;
        if(!bd||!(bd.classList.contains('dsh-ov-open')||bd.classList.contains('dsh-s-l3'))){return;}
        var t=e.touches[0];
        if(!t){return;}
        var hr=shellCloseRect();
        if(!hr){return;}
        if(t.clientX>=hr.x&&t.clientX<=hr.x+hr.w&&t.clientY>=hr.y&&t.clientY<=hr.y+hr.h){
          tapBack={x:t.clientX,y:t.clientY};
          try{ dlog('[dsh] tapBack armed: x='+Math.round(t.clientX)+' y='+Math.round(t.clientY)); }catch(eD){}
        }
      }catch(e2){ tapBack=null; }
    },{passive:true,capture:true});
    document.addEventListener('touchend',function(e){
      try{
        if(!tapBack){return;}
        var tb=tapBack; tapBack=null;
        var ct=null;
        try{ ct=e&&e.changedTouches&&e.changedTouches[0]; }catch(eC){ ct=null; }
        if(!ct){return;}
        var dx=ct.clientX-tb.x,dy=ct.clientY-tb.y;
        if(dx*dx+dy*dy>16*16){return;}   // 位移>16px = 在滑动，不是 tap → 不接管
        if(e&&e.cancelable){e.preventDefault();}   // 掐断 React 的 click 派发
        try{ dlog('[dsh] tapBack fire: 走 backOneLevel（绕过 React）'); }catch(eD2){}
        try{
          if(typeof window.__dshBack==='function'){ window.__dshBack(); }
        }catch(e3){}
      }catch(e2){ tapBack=null; }
    },{passive:false,capture:true});
    try{
      document.addEventListener('touchstart',function(e){
        try{
          swT=null;
          if(!mq.matches){return;}                       // 宽屏（桌面）不参与
          if(!e||!e.touches||e.touches.length!==1){return;}   // 多指不参与
          if(!swipeTargetAvailable()){return;}           // 聊天页没有上一级 → 不抢触摸
          // 键盘开着（焦点在输入框）时，系统返回的第一下是收键盘 —— 语义保持一致
          var ae=null;
          try{ ae=document.activeElement; }catch(eA){ ae=null; }
          if(ae&&(String(ae.tagName||'').toUpperCase()==='INPUT'
            ||String(ae.tagName||'').toUpperCase()==='TEXTAREA'
            ||String(ae.tagName||'').toUpperCase()==='SELECT'
            ||ae.isContentEditable)){return;}
          var t=e.touches[0];
          if(!t){return;}
          var vw=window.innerWidth||0;
          if(t.clientX>=SWIPE_EDGE&&t.clientX<vw-SWIPE_EDGE){return;}   // 不在边缘区
          if(swipeInReserved(e.target)){return;}         // 横滑另有语义的地方 → 放行
          swT={side:(t.clientX<vw/2)?'l':'r',x0:t.clientX,y0:t.clientY,
               claimed:false,armed:false};
          dlog('[dsh] swipe touchstart: side='+swT.side+' x='+Math.round(t.clientX)
            +' avail='+(swipeTargetAvailable()?'1':'0'));
        }catch(e2){ swT=null; }
      },{passive:true});
      document.addEventListener('touchmove',function(e){
        try{
          if(!swT){return;}
          if(!e||!e.touches||e.touches.length!==1){swipeReset();return;}
          var t=e.touches[0];
          if(!t){swipeReset();return;}
          var dx=t.clientX-swT.x0,dy=t.clientY-swT.y0;
          var adx=dx<0?-dx:dx,ady=dy<0?-dy:dy;
          // 反向（往回推向屏幕边缘）＝取消本次手势方向上的进度
          if(swT.side==='l'&&dx<0){adx=0;}
          if(swT.side==='r'&&dx>0){adx=0;}
          if(!swT.claimed){
            if(adx>=SWIPE_CLAIM&&adx>ady){
              swT.claimed=true;
              // r82：claimed 时记录位移，真机日志可核对用户滑动距离（排查"滑了没触发"）
              dlog('[dsh] swipe claimed: side='+swT.side+' dx='+Math.round(adx)
                +' dy='+Math.round(ady));
              swipeIndicatorShow(swT.x0,swT.y0);
            }else if(ady>adx+16&&ady>24){
              swipeReset();                              // 明显在竖向滚动 → 归还触摸
              return;
            }else{
              return;                                    // 还看不出方向，继续观察
            }
          }
          // 已接管：挡住页面随之滚动/横移，指示器跟随手指
          if(e.cancelable){e.preventDefault();}
          var fx=(swT.side==='l')
            ?swT.x0+Math.min(adx,SWIPE_IND_MAX)
            :swT.x0-Math.min(adx,SWIPE_IND_MAX);
          swipeIndicatorShow(fx,t.clientY);
          var armed=adx>=SWIPE_ARM;
          if(armed!==swT.armed){
            swT.armed=armed;
            swipeIndicatorArm(armed);
            if(armed){ try{ if(navigator.vibrate){navigator.vibrate(6);} }catch(eA){} }
          }
        }catch(e2){ swipeReset(); }
      },{passive:false});
      document.addEventListener('touchend',function(e){
        try{
          if(!swT){return;}
          var wasArmed=swT.armed;
          var eDx=0,eDy=0;
          try{ if(e&&e.changedTouches&&e.changedTouches.length){
            eDx=Math.round(e.changedTouches[0].clientX-swT.x0);
            eDy=Math.round(e.changedTouches[0].clientY-swT.y0);
          } }catch(eT){}
          swT=null;
          swipeIndicatorHide();
          if(wasArmed){ swipeFire(); }
          else{
            // r82：松手未达阈值也留痕 —— 下轮真机日志直接看到"滑了多远没触发"
            dlog('[dsh] swipe end: not-armed dx='+eDx+' dy='+eDy);
          }
        }catch(e2){ swipeReset(); }
      },{passive:true});
      document.addEventListener('touchcancel',function(){
        // 系统返回手势认领了这次触摸（手势导航机型）→ 立即放弃；
        // 返回由系统走 handleBack()（同一条 backOneLevel 状态机），不会双重返回。
        if(swT){ dlog('[dsh] swipe cancelled (system claimed?)'); }
        swipeReset();
      },{passive:true});
    }catch(eSwipe){}
    // ===== 顶栏（汉堡 + 遮罩）· 幂等自愈（r66 · U18）=====
    // 背景：r41 修过一轮「汉堡按钮消失」，r66 用户又报了同类现象 —— 从设置页侧滑返回后
    //   回到首页，左上角没有汉堡。两个并存的原因：
    //     ① dsh-ov-open 被错误地留在 body 上（→ 见 ovVisible/settingsOverlay 的注释）；
    //     ② 顶栏是 document.body 的直接子节点，dsh 的 React 树一旦整体替换 body 的
    //        子节点，它就被冲掉了，而注入脚本每页只跑一次、不会补。
    //   所以：创建 + 事件绑定全部收进这个幂等函数，交给 MutationObserver 与 500ms
    //   轮询兜底调用 —— 无论谁把顶栏弄丢，下一轮就会被原样补回，且不会重复绑定。
    //   ⚠️ MENU_SVG 已上移到 mount() 最顶端（r68 修复 "undefined" 胶囊，见那里注释）。
    function onMenuClick(e){
      e.preventDefault();e.stopPropagation();
      var b=document.body;
      if(!b){return;}
      if(b.classList.contains('dsh-drawer-open')){close();syncBackMark();return;}
      try{expandSidebarOnce();}catch(err){}
      b.classList.add('dsh-drawer-open');
      // r93：抽屉滑入的三拍里同步 ▭ 到 logo 行（500ms 轮询太慢，会看到位置跳变）
      try{syncMenuBtnToLogo();}catch(eS0){}
      setTimeout(function(){try{syncMenuBtnToLogo();}catch(eS1){}},150);
      setTimeout(function(){try{syncMenuBtnToLogo();}catch(eS2){}},380);
      // 抽屉也是覆盖层：同样要压一条历史，否则侧滑/返回键只能退出 App
      // （U17：r64 只给设置面板压了历史，抽屉漏了）
      syncBackMark();
      var n=0;
      var iv=setInterval(function(){
        try{expandSidebarOnce();}catch(err){}
        if(++n>8){clearInterval(iv);}
      },140);
    }
    function onScrimClick(){close();syncBackMark();}
    // 顶栏内容只有一处定义，避免三处手抄（r67 就是抄出了两份、其中一份拼错）。
    // r93：汉堡里放两颗图标 —— ☰（抽屉关）与 ▭（抽屉开，即参考稿的「收起侧栏」形态），
    //   显隐由 mobile.css 按 body.dsh-drawer-open 切换。两个 <svg> 都在 innerHTML 里，
    //   ensureTopBar() 的 '<svg' 内容校验不受影响。
    function topBarHtml(){
      return '<button id="dsh-mbtn" type="button" aria-label="菜单">'
        +'<span class="dsh-ic-menu">'+MENU_SVG+'</span>'
        +'<span class="dsh-ic-panel">'+PANEL_SVG+'</span>'
        +'</button>';
    }
    function ensureTopBar(){
      var b=document.body;
      if(!b){return null;}
      var bar=document.getElementById('dsh-mtop');
      if(!bar){
        bar=document.createElement('div');
        bar.id='dsh-mtop';
        bar.innerHTML=topBarHtml();
        b.appendChild(bar);
      }else if(bar.parentNode!==b){
        b.appendChild(bar);   // 被挪走/被冲掉后重新挂回 body
      }
      // __dshBound 是我们自己的幂等标记：节点是我们造的，只在创建时绑一次，
      // 重复调用 ensureTopBar() 不会叠加监听器。
      var btn=bar.querySelector('#dsh-mbtn')||document.getElementById('dsh-mbtn');
      // r68：光判断"按钮在不在"**不够** —— r67 真机上按钮在、内容却是文本
      //   "undefined"（常量还没赋值就被拼进 innerHTML），而"节点存在"恰好让这段
      //   重建逻辑永远不触发，故障就永久留在屏幕上。
      //   所以再校验**内容**：壳子里必须真的有 <svg，否则（被 React 清空 / 被换成
      //   别的文本 / 拼进了 undefined）一律原样重建。
      if(!btn||String(bar.innerHTML).indexOf('<svg')<0){
        // 顶栏在、但里面的按钮被 React 清掉（或内容不对）—— 重建（只在需要时做）
        bar.innerHTML=topBarHtml();
        btn=bar.querySelector('#dsh-mbtn');
      }
      if(btn&&!btn.__dshBound){btn.__dshBound=true;btn.addEventListener('click',onMenuClick);}
      var scrim=document.getElementById('dsh-scrim');
      if(!scrim){
        scrim=document.createElement('div');
        scrim.id='dsh-scrim';
        b.appendChild(scrim);
      }else if(scrim.parentNode!==b){
        b.appendChild(scrim);
      }
      if(scrim&&!scrim.__dshBound){scrim.__dshBound=true;scrim.addEventListener('click',onScrimClick);}
      return bar;
    }
    ensureTopBar();
    // ===== r93 · 抽屉「插件」行（参考稿《设置页（参考）.jpg》：新会话下方有 插件 项）=====
    //   现装 dsh 前端版本的侧栏没有插件入口，设置页里才有 —— 参考稿明确它该在抽屉里。
    //   实现与 ensureTopBar 同一套幂等自愈模式：React 冲掉就补，绝不重复插。
    //   点击语义 = 深链到设置页插件列表：先程序化点侧栏底部「设置」入口（与用户手点
    //   同一条原生路径），面板浮出后再程序化点导航里的「插件」cell。两跳之间轮询等
    //   DOM 就位；任何一跳失败都停在「设置列表页」—— 比注入死链诚实，且不产生新的
    //   状态机分支（L2/L3 归现有的 backOneLevel 管）。
    //   ⚠️ sbTap 是 dshTap（设置 IIFE 内）的山寨版：dshTap 是函数声明、出不了那个
    //   IIFE 作用域，而本行注入必须活在 mount 主体（500ms 轮询在市场页/宽屏也要跑）。
    //   r59 的教训反过来读：跨作用域共用的函数要么上移、要么复制，绝不能隔空引用。
    var pluginNavBusy=false;
    function sbTap(el){
      if(!el){return false;}
      var x=0,y=0;
      try{
        var r=el.getBoundingClientRect();
        if(r){ x=r.left+(r.width||0)/2; y=r.top+(r.height||0)/2; }
      }catch(eR){}
      var opts={bubbles:true,cancelable:true,composed:true,view:window,
                clientX:x,clientY:y,screenX:x,screenY:y};
      var ok=false;
      try{ el.dispatchEvent(new PointerEvent('pointerdown',opts)); }catch(e1){}
      try{ el.dispatchEvent(new MouseEvent('mousedown',opts)); }catch(e2){}
      try{ el.dispatchEvent(new PointerEvent('pointerup',opts)); }catch(e3){}
      try{ el.dispatchEvent(new MouseEvent('mouseup',opts)); }catch(e4){}
      try{ el.dispatchEvent(new MouseEvent('click',opts)); ok=true; }catch(e5){}
      if(!ok){ try{ el.click(); }catch(e6){} }
      try{ dlog('[dsh] sbTap: ok='+ok+' tag='+el.tagName+' txt='
        +String(el.textContent||'').trim().slice(0,12)); }catch(eD){}
      return true;
    }
    function findSidebarSettingsTrigger(sb){
      // 优先：文本恰为「设置」的按钮/行（真机底部行是 图标+文字，textContent 含图标
      // 字符/空白，所以用 includes + 长度上限，不匹配「通用设置」等长文案）。
      // querySelectorAll 按文档序返回 —— 外层容器会排在真正可点的 button 前面，
      // 所以「最后的命中」才是最内层；可点的优先（BUTTON/[role=button]）。
      var cands=sb.querySelectorAll('button, [role="button"], div, span');
      var el=null, elClickable=null;
      for(var i=0;i<cands.length;i++){
        var t=String(cands[i].textContent||'').replace(/\s+/g,'');
        if(t&&t.indexOf('设置')>=0&&t.length<=4){
          el=cands[i];
          var tag=cands[i].tagName;
          if(tag==='BUTTON'||(cands[i].getAttribute&&cands[i].getAttribute('role')==='button')){
            elClickable=cands[i];
          }
        }
      }
      return elClickable||el;
    }
    function findPluginCell(){
      var cells=document.querySelectorAll('.dsh-s-nav [class*="_navCell"]');
      for(var i=0;i<cells.length;i++){
        var t=String(cells[i].textContent||'').replace(/\s+/g,'');
        if(t==='插件'){return cells[i];}
      }
      return null;
    }
    function openSettingsToPlugins(){
      if(pluginNavBusy){return;}
      var sb=document.querySelector('div[class*="sidebarCol"]');
      if(!sb){return;}
      // 面板已开：直接点「插件」cell（L2 态由现有捕获层转 L3）
      var b=document.body;
      if(b&&(b.classList.contains('dsh-s-l2')||b.classList.contains('dsh-s-l3'))){
        var cell0=findPluginCell();
        if(cell0){sbTap(cell0);}
        return;
      }
      var trig=findSidebarSettingsTrigger(sb);
      if(!trig){ dlog('[dsh] plugin row: settings trigger not found'); return; }
      pluginNavBusy=true;
      sbTap(trig);
      var waited=0;
      var iv=setInterval(function(){
        waited+=140;
        var bb=document.body;
        var panel=bb&&bb.classList.contains('dsh-s-l2');
        if(panel){
          var cell=findPluginCell();
          if(cell){
            clearInterval(iv);
            pluginNavBusy=false;
            sbTap(cell);
            return;
          }
        }
        if(waited>=4200){
          clearInterval(iv);
          pluginNavBusy=false;
          dlog('[dsh] plugin row: deep-link timeout (stopped at settings list)');
        }
      },140);
    }
    function ensureDrawerPluginRow(){
      if(!mq.matches){return;}
      var sb=document.querySelector('div[class*="sidebarCol"]');
      if(!sb){return;}
      if(document.getElementById('dsh-sb-plugin')){
        // 在树上但可能被 React 挪走：挂回 sidebarCol 直系（幂等）
        return;
      }
      var ns=sb.querySelector('button[class*="_newSession"]:not([class*="Label"])');
      if(!ns){return;}   // 没有新会话按钮 = 侧栏还没渲染好，等下一轮
      var row=document.createElement('button');
      row.id='dsh-sb-plugin';
      row.type='button';
      row.setAttribute('aria-label','插件');
      row.innerHTML='<span class="dshsb-ic">'+PLUGIN_SVG+'</span>'
        +'<span class="dshsb-label">插件</span>';
      row.addEventListener('click',function(e){
        e.preventDefault();e.stopPropagation();
        openSettingsToPlugins();
      });
      try{ ns.insertAdjacentElement('afterend',row); }
      catch(eIns){ if(ns.parentNode){ns.parentNode.insertBefore(row,ns.nextSibling);} }
      dlog('[dsh] drawer plugin row injected');
    }
    try{ ensureDrawerPluginRow(); }catch(ePlug){ derr('[dsh] plugin row init:',ePlug); }
    // r93 · 抽屉开时把汉堡（▭ 收起形态）钉到 logo 行同一水平线（参考稿布局）。
    //   CSS 只能猜固定值，logo 行的实际 y 由 dsh 原生布局决定（真机与 fixture 差异大），
    //   所以量出来再写 style.top；抽屉关掉就清掉内联值，回落 CSS 默认（top:6px）。
    //   幂等：每轮只做一次几何读写，logo 行找不到就保持现状（绝不清成 0）。
    function syncMenuBtnToLogo(){
      var b=document.body;
      var mb=document.getElementById('dsh-mbtn');
      if(!b||!mb){return;}
      if(!b.classList.contains('dsh-drawer-open')){
        if(mb.style.top){mb.style.top='';}
        return;
      }
      var sb=document.querySelector('div[class*="sidebarCol"]');
      if(!sb){return;}
      var logo=sb.querySelector('[class*="logoRow"],[class*="_brand"]');
      if(!logo){return;}
      var r=logo.getBoundingClientRect();
      if(!r||r.height<=0){return;}   // 过渡态（透明度动画中）不动
      var top=Math.max(6,Math.round(r.top+r.height/2-22));
      if(String(mb.style.top)!==top+'px'){mb.style.top=top+'px';}
    }
    try{ syncMenuBtnToLogo(); }catch(eM2L){}
    dlog('[dsh] mount shell done: bridge='+(window.dshNative?'yes':'no')
      +' __dshBack='+(typeof window.__dshBack));
    setInterval(function(){
      if(document.hidden)return;
      tagHosts();syncOvFlag();detectDark();
      try{ ensureDrawerPluginRow(); }catch(ePlug2){}
      try{ syncMenuBtnToLogo(); }catch(eM2L2){}
      // r69：设置页的层级/返回条自愈 —— 页面静止时也要有人复查（见 __dshSettingsSync 处注释）
      if(window.__dshSettingsSync){try{window.__dshSettingsSync();}catch(e){}}
      // 市场页：dshmarket 是独立 bundle，可能在我们注入之后才渲染完 —— 反复尝试
      // 整理它的头部（全部幂等），渲染晚也不会漏。
      try{ arrowizeClose(); }catch(e){}
      try{ marketPageBar(); }catch(e){}
    },500);
    detectDark();
    // ============================================================
    // 跨页面共用：安装状态 / 无障碍播报 / 两个安装动作 / 市场页操作条
    //
    // ⚠️ 为什么这一簇必须待在 mount 主体、而不是设置面板那个 IIFE 里：
    //   那个 IIFE 在两种情况下**都不执行** —— 市场页（inMarket=true）整个跳过，
    //   宽屏（mq.matches=false）也在开头 return。r58 把 marketPageBar() 定义在
    //   IIFE 内却从 mount 主体调用，于是 ReferenceError 把 mount() 拦腰截断：
    //   汉堡抽屉、rescue 白屏自愈、Escape 关闭、scrim 关闭、侧栏展开轮询
    //   共 6 项功能静默失效，而按钮"看得见、点了没反应"，极难定位。
    //   function 声明会提升到 mount 作用域，所以设置面板 IIFE 内仍能访问这里的
    //   marketState / announce / install*，不必再传参。
    // ============================================================
    function marketState(){
      var v='',p='';
      if(window.dshNative){
        try{ v=window.dshNative.marketInstalled(); }catch(e){}
        try{ p=window.dshNative.pnpmReady(); }catch(e){}
      }
      return {market:v||'', pnpm:!!p};
    }
    function announce(txt){
      var box=document.getElementById('dsh-live');
      if(!box){return;}
      box.textContent='';
      setTimeout(function(){box.textContent=txt;},30);
    }
    var pnpmBusy=false, installing=false;
    // r72（P2-1）：安装 busy 兼容超时 —— 原生侧若永远不回调（Activity 已死等极端
    //   情况），busy 标志会永久卡死，之后所有安装点击都静默无响应。120s 自复位
    //   （正常安装 1~3MB 下载远小于此值）；回调先到时本定时器晚触发也无害。
    var INSTALL_BUSY_MS=120000;
    function installPnpmNow(after){
      if(pnpmBusy){return;}
      pnpmBusy=true;
      announce('正在下载并安装 pnpm');
      window.__dshPnpmDone=function(id,res){
        pnpmBusy=false;
        if(res&&res.ok){ announce('pnpm 安装完成'); if(after){after(res);} }
        else { alert('pnpm 安装失败：'+((res&&res.message)||'未知错误')); announce('pnpm 安装失败'); }
      };
      setTimeout(function(){ if(pnpmBusy){pnpmBusy=false;announce('pnpm 安装超时，可重试');} },INSTALL_BUSY_MS);
      try{ window.dshNative.installPnpm('pnpm'); }
      catch(err){ pnpmBusy=false; alert('pnpm 安装失败：'+((err&&err.message)||err)); }
    }
    function installMarketNow(after){
      if(installing){return;}
      installing=true;
      announce('正在下载并安装插件市场');
      window.__dshMarketDone=function(id,res){
        installing=false;
        if(res&&res.ok){ announce('插件市场安装完成'); if(after){after(res);} }
        else { alert('安装失败：'+((res&&res.message)||'未知错误')); announce('插件安装失败'); }
      };
      setTimeout(function(){ if(installing){installing=false;announce('插件市场安装超时，可重试');} },INSTALL_BUSY_MS);
      try{ window.dshNative.installMarket('mkt'); }
      catch(err){ installing=false; alert('安装失败：'+((err&&err.message)||err)); }
    }
    // ===== r66 · 市场页头部按设计稿图 3 整理 =====
    // 设计稿要求：
    //   ① 顶部一行 = ← 返回（左）+「打开配置文件」（右）；
    //   ② 标题块 = 深色圆角方块图标 + 「插件市场」+ 版本号，**横排、绝不竖排**；
    //   ③ 操作区 = 「导出日志」「更新插件市场」两个黑色胶囊按钮**同一排**；
    //   ④ 去掉社区说明与「申请收录插件」链接。
    // 三条硬约束（沿用 r38/r40/r58 的既定铁律）：
    //   ① **不搬、不删 dsh 的节点**。把 React 拥有的节点移走/删掉，等它卸载那棵子树时
    //      parentInstance.removeChild(child) 会找不到节点抛 NotFoundError，表现是
    //      「切页时报错 / 页面卡住」。我们只做两件事：给节点打 data-dsh-mkt-* 属性、
    //      以及注入**我们自己的**节点。显示/隐藏/重排全部交给 market.css。
    //      ⚠️ display:none 只影响布局、不影响事件派发 —— 程序化 .click() 照样会冒泡到
    //      React 的根监听器，所以「隐藏原生按钮 + 用自己的代理按钮去点它」是可用的
    //      （同 closeSettingsPanel() 点 dsh 那个 × 的手法）。
    //   ② 定位一律用**文本内容**（取最靠上、最内层的那一个），不猜 dshmarket 的哈希类名
    //      —— 它是独立 bundle，类名随版本变，文案不会变。
    //   ③ 幂等：每轮 sync 都可能重跑，重复调用无副作用。
    var MKT_LOG_SVG='<svg width="14" height="14" viewBox="0 0 24 24" fill="none"'
      +' stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"'
      +' aria-hidden="true"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/>'
      +'<path d="M7 10l5 5 5-5"/><path d="M12 15V3"/></svg>';
    function mktInBar(el){
      var n=el;
      while(n){ if(n.id==='dsh-mkt-bar'){return true;} n=n.parentNode; }
      return false;
    }
    // 在 root 里按文本找目标元素。
    //   优先级：**最深**（最内层，避免命中包着一大片的容器）→ 最靠上（避免命中
    //   插件卡片里的同名文字）→ 文本最短。
    //   为什么"最深"排第一：标题常见写法是 `<div>插件市场<span>v1.66</span></div>`，
    //   外层 div 也匹配 /^插件市场/；若按 top 优先就会选中外层，把版本号一起套上
    //   20px 标题字号。按深度优先才能稳定挑到那块纯标题。
    function mktFind(root,re){
      if(!root){return null;}
      var all=root.querySelectorAll('button,span,a,div,p,strong,b,h1,h2,h3,h4,i,label');
      var best=null,bestDepth=-1,bestTop=0,bestLen=0,i;
      for(i=0;i<all.length;i++){
        var el=all[i];
        if(mktInBar(el)){continue;}                       // 跳过我们自己的操作条
        if(el.getAttribute('data-dsh-mine')){continue;}   // 跳过我们自己注入的入口
        var t=(el.textContent||'').replace(/\s+/g,'');
        if(!t||!re.test(t)){continue;}
        var d=0,n=el;
        while(n&&n!==root){d++;n=n.parentNode;}
        var top=0;
        try{ var r=el.getBoundingClientRect(); top=r.top||0; }catch(e){ top=0; }
        if(d>bestDepth||(d===bestDepth&&(best===null||top<bestTop||(top===bestTop&&t.length<bestLen)))){
          best=el;bestDepth=d;bestTop=top;bestLen=t.length;
        }
      }
      return best;
    }
    function mktTextLen(el){
      return el?String(el.textContent||'').replace(/\s+/g,'').length:0;
    }
    // ===== r69 · 按**文本节点**定位（不猜标签名）=====
    //   为什么 r66/r67 那套在真机上没生效：mktFind() 只扫
    //   `button,span,a,div,p,strong,b,h1..h4,i,label` 这批标签，而且标题有
    //   "长度 > 24 就放弃" 的防误伤阈值。真机上的市场页头是
    //   「图标 + 插件市场 + dsh-market vX + 发现社区为… + 申请收录插件 ↗ + 不再提醒」
    //   **挤在同一行**：最深的「插件市场」元素要么不存在（文字是裸文本节点），
    //   要么匹配到的外层把整行文字都算进去、长度超阈值被丢掉 ——
    //   于是 `data-dsh-mkt-title` / `-desc` / `-hide` 一个都没打上，
    //   表现就是：标题竖排、文案还在、按钮顶出屏幕、底部出横向滚动条。
    //   改成直接遍历**文本节点**：不管它在什么标签里、甚至没有标签（裸文本），
    //   都能定位到它真正的宿主元素。这是对 r67 手法的补齐，不是换方向。
    function mktTextNode(scope,re,maxLen){
      if(!scope){return null;}
      // ⚠️ createTreeWalker 挂在 **Document / DocumentFragment** 上，**不在 Element 上**
      //   （Element 只有 matches/closest 那些）。写成 scope.createTreeWalker 会得到
      //   undefined —— 这正是第一版 r69 在真机页面上定位不到裸文本标题的原因，
      //   由 repro/debug5.js 在浏览器里实测确认（rootTW=undefined、docTW=function）。
      var w=null;
      try{ w=document.createTreeWalker(scope,4,null,false); }catch(e){ w=null; }
      if(!w){ try{ w=document.createTreeWalker(scope,4); }catch(e){ w=null; } }
      if(!w){ return null; }
      var node,best=null,bestLen=1e9;
      while((node=w.nextNode())){
        var t=String(node.nodeValue||'').replace(/\s+/g,'');
        if(!t||!re.test(t)){continue;}
        if(t.length>maxLen){continue;}
        if(t.length<bestLen){best=node;bestLen=t.length;}   // 取最短 = 最贴切那段文字
      }
      return best;
    }
    // 在整个文档里找（真机上「申请收录插件」「不再提醒」可能在 market root **之外**，
    //   例如挂在 dshmarket 自己的顶栏里 —— 只搜 root 会一个都找不到）。
    //   两条护栏：① 文本长度上限；② 必须位于页面上方（避免命中插件卡片正文）。
    function mktFindWide(re,maxLen,topLimit){
      var el=mktFind(document,re);
      if(el&&mktTextLen(el)<=maxLen){return el;}
      var node=mktTextNode(document,re,maxLen);
      var host=node?node.parentElement:null;
      if(!host){return null;}
      if(mktInBar(host)||host.getAttribute('data-dsh-mine')){return null;}
      try{ var r=host.getBoundingClientRect(); if(topLimit&&r.top>topLimit){return null;} }catch(e){}
      return host;
    }
    // 给市场页头部打标记（只加属性，不动节点）。
    function marketDecorate(root){
      if(!root){return;}
      // ② 标题 + 版本号 + 标题行（标题行强制横排，见 market.css）
      //    r69：标题多一路"按文本节点"的兜底 —— 真机上标题常常没有专属标签，
      //    或者外层把整行文字算进去导致长度超限被丢掉（详见 mktTextNode 的注释）。
      var title=mktFind(root,/^插件市场/);
      if(title&&mktTextLen(title)>24){title=null;}
      if(!title){
        var tn=mktTextNode(root,/^插件市场/,8);
        title=tn?tn.parentElement:null;
      }
      if(title&&title!==root&&title.setAttribute){
        // head = **直接包着「插件市场」那段文字**的元素。
        //   它可能是标题本身，也可能是标题行（真机上标题常常是没有专属标签的裸文本）。
        //   这两种情况的修法不同，所以分成两个标记，别混用：
        //     data-dsh-mkt-head → 「不许被压扁」（nowrap + 不收缩）
        //     data-dsh-mkt-title→ 真正的标题（20px 粗体），只有文字够短时才加
        title.setAttribute('data-dsh-mkt-head','1');
        if(mktTextLen(title)<=24){title.setAttribute('data-dsh-mkt-title','1');}
        var row=title.parentNode;
        if(row&&row!==root&&row.setAttribute){row.setAttribute('data-dsh-mkt-row','1');}
        else{ title.setAttribute('data-dsh-mkt-row','1'); }  // 标题是 root 的直接子元素
        // 标题左边的方块图标（一般是无文字的 svg/img/div），只打标不搬动
        var logo=title.previousElementSibling||(row&&row.previousElementSibling);
        if(logo&&!(String(logo.textContent||'').trim())&&!logo.getAttribute('data-dsh-mkt-title')){
          logo.setAttribute('data-dsh-mkt-logo','1');
        }
      }
      // ③ r71 · 真机 DOM 校准（子代理对 dshmarket 1.66.14 的 DOM 地图）：
      //    标题那一行 div.nUhMVa_titleRow（= 上面的 row）里其实塞了 7 个东西：
      //      svg MarketLogo / h2「插件市场」/ a.repoLink「dsh-market」/ span.version「v1.x.y」
      //      / Button「更新插件市场」/ Button「全部更新 (n)」/ Button「重启前都不再提醒」
      //    旧逻辑只拿 /^dsh-market/i 去认"版本号"，命中的其实是 **repoLink**，
      //    真正的 span.version 没人管 ⇒ 版本号跟着那一堆按钮被挤、被压在按钮底下。
      //    现在：repoLink → -repo，version → -ver，两个宿主按钮让位（-native-upd / -hide）。
      var repo=mktFind(root,/^dsh-market$/i);
      if(!repo){var rn=mktTextNode(root,/^dsh-market$/i,24);repo=rn?rn.parentElement:null;}
      if(repo&&repo!==title&&repo!==row&&mktTextLen(repo)<=24){repo.setAttribute('data-dsh-mkt-repo','1');}
      var ver=mktFind(root,/^v\d+\.\d+/);
      if(!ver){var vn=mktTextNode(root,/^v\d+\.\d+/,16);ver=vn?vn.parentElement:null;}
      if(ver&&ver!==repo&&mktTextLen(ver)<=24){ver.setAttribute('data-dsh-mkt-ver','1');}
      // 原生「更新插件市场」让位给注入操作条里的同名按钮（文案完全一致，见 #dsh-mkt-upd）；
      // 「全部更新 (n)」设计稿没有 → 一并收掉。两者都只是重复入口，数据/功能不受影响。
      var natUpd=mktFind(root,/^更新插件市场$/);
      if(natUpd){natUpd.setAttribute('data-dsh-mkt-native-upd','1');}
      var updAll=mktFind(root,/^全部更新/);
      if(updAll&&mktTextLen(updAll)<=16){updAll.setAttribute('data-dsh-mkt-hide','1');}
      // ④ 社区说明：它常与「导出日志」同容器（裸文本 + 两个按钮），所以只能
      //    "隐藏文字"不能整块隐藏 —— CSS 侧用 font-size:0 藏裸文本、再把直接子元素
      //    恢复字号。锚定"发现社区/社区为"开头并限制长度，避免命中卡片正文。
      //    r69：① 去掉"含『插件市场』就放弃"这条（真机上它就在标题那一行里，
      //    正是这条护栏把说明文字漏了下来）；② 加按文本节点的兜底；
      //    ③ 整页范围再搜一次（它可能在 market root 之外）。CSS 侧会把标题、
      //    版本号、按钮的字号显式还原，所以"整行 font-size:0"不会伤到别的元素。
      var desc=mktFind(root,/发现社区|社区为/);
      if(desc&&mktTextLen(desc)>160){desc=null;}
      if(!desc){
        var dn=mktTextNode(root,/发现社区|社区为/,60);
        desc=dn?dn.parentElement:null;
      }
      if(desc){desc.setAttribute('data-dsh-mkt-desc','1');
        // r71 · 设计稿图2 里没有"社区说明"这一行 ⇒ 它所在的 div.nUhMVa_sub 整块不显示
        //   （说明文字、「申请收录插件」、原生「导出日志」都在这个 div 里，设计稿三者都去掉；
        //   导出日志由注入的操作条顶替，文案一致）。
        var dpar=desc.parentNode;
        if(dpar&&dpar!==root&&dpar!==row&&dpar.setAttribute){dpar.setAttribute('data-dsh-mkt-sub','1');}
      }
      var sug=mktFindWide(/申请收录/,40,600);
      if(sug){sug.setAttribute('data-dsh-mkt-hide','1');}
      // 设计稿操作区只有两个按钮 → dsh 的「重启前都不再提醒」不显示
      // （它只是"别再提醒我更新"的开关，隐藏不影响任何数据/功能，随时可一行恢复）
      var rem=mktFindWide(/不再提醒/,40,600);
      if(rem){rem.setAttribute('data-dsh-mkt-hide','1');}
      // ③ 原生「导出日志」隐藏，由我们注入的代理按钮顶替 —— 这样两个按钮必然同排、
      //    同款（原生那个在 dshmarket 的说明行里，位置由它自己决定）。
      var natLog=mktFind(root,/^导出日志$/);
      if(!natLog){
        var loose=mktFind(root,/导出日志/);
        if(mktTextLen(loose)<=8){natLog=loose;}
      }
      if(natLog){natLog.setAttribute('data-dsh-mkt-native-log','1');}
      // ① 原生关闭按钮（×）→ 视觉上换成 ←（只换字形，点击行为还是 dsh 自己的）
      var close=root.querySelector('[class*="_close"],button[aria-label*="关闭"],button[aria-label*="Close"]');
      if(!close){
        // 顶部那一行的 × 可能在 root 之外（dshmarket 自己的顶栏），按几何位置收敛：
        // 只认位于页面最上方的候选，避免误伤浮窗里的关闭按钮。
        var cands=document.querySelectorAll('button[class*="_close"],button[aria-label*="关闭"],button[aria-label*="Close"]');
        for(var k=0;k<cands.length;k++){
          var rr=null;
          try{ rr=cands[k].getBoundingClientRect(); }catch(e){ rr=null; }
          if(rr&&rr.top<160){close=cands[k];break;}
        }
      }
      if(close){close.setAttribute('data-dsh-mkt-close','1');}
    }
    // 操作条放在**标题行之后**（设计稿：标题块 → 操作区 → tabs）。
    //   具体落在哪：从标题往上走到 root 的直接子节点，插在它后面；
    //   找不到标题就退回"root 的第一个子节点之前"（r58 的旧行为）。
    function placeMarketBar(root,bar,anchor){
      var node=anchor;
      while(node&&node.parentNode&&node.parentNode!==root){node=node.parentNode;}
      if(node&&node.parentNode===root){
        if(node.nextSibling){root.insertBefore(bar,node.nextSibling);}
        else{root.appendChild(bar);}
      }else if(root.firstChild){root.insertBefore(bar,root.firstChild);}
      else{root.appendChild(bar);}
    }
    // r71 · 全局「× → ←」（设计稿第 6 条 / 图一）：所有"返回上一级"的关闭按钮都换成左上箭头。
    //   做法是**直接改按钮内部字形**，不碰位置、不碰点击行为和 aria-label
    //   （大屏不注入这些 CSS 时它们本来就在最左）。
    //   ⚠️ 不能再用 CSS ::after 画箭头：mobile.css:141 已经把这个元素（L3 关闭键）的
    //   ::after 拿去当 44px 触控热区了，再画会被顶掉 —— 这正是真机图一里市场页
    //   画不出 ← 的原因之一。
    function arrowizeClose(){
      if(!mq.matches){return;}
      var nodes;
      try{ nodes=document.querySelectorAll('button[class*="_close"],button[aria-label*="关闭"],button[aria-label*="Close"],[data-dsh-mkt-close]'); }catch(e){ return; }
      for(var i=0;i<nodes.length;i++){
        var b=nodes[i];
        if(b.querySelector('[data-dsh-arrow-icon]')){b.setAttribute('data-dsh-arrow','1');continue;}
        b.setAttribute('data-dsh-arrow','1');
        // r72（P3-2）：清空字形前先把原文本存进 aria-label —— 否则没有 aria-label
        //   的纯文本关闭按钮会失去可访问名称（无障碍树里变成无名按钮）。
        if(!b.getAttribute('aria-label')){
          var lbl=String(b.textContent||'').replace(/\s+/g,' ').replace(/^\s+|\s+$/g,'');
          if(lbl){try{b.setAttribute('aria-label',lbl);}catch(e){}}
        }
        var kids=b.children||[],j;
        for(j=0;j<kids.length;j++){try{kids[j].style.setProperty('visibility','hidden','important');}catch(e){}}
        var cns=b.childNodes||[];
        for(j=0;j<cns.length;j++){
          if(cns[j].nodeType===3){cns[j].nodeValue='';}
        }
        var svg=document.createElementNS('http://www.w3.org/2000/svg','svg');
        svg.setAttribute('viewBox','0 0 24 24');svg.setAttribute('width','18');svg.setAttribute('height','18');
        svg.setAttribute('fill','none');svg.setAttribute('stroke','currentColor');
        svg.setAttribute('stroke-width','2.2');svg.setAttribute('stroke-linecap','round');
        svg.setAttribute('stroke-linejoin','round');svg.setAttribute('data-dsh-arrow-icon','1');
        var p=document.createElementNS('http://www.w3.org/2000/svg','path');
        p.setAttribute('d','M19 12H5M12 19l-7-7 7-7');
        svg.appendChild(p);b.appendChild(svg);
      }
    }
    function marketPageBar(){
      var root=document.querySelector('[data-dsh-market-root]');
      if(!root){return;}
      marketDecorate(root);
      var bar=document.getElementById('dsh-mkt-bar');
      if(!bar){
        bar=document.createElement('div');
        bar.id='dsh-mkt-bar';
        bar.innerHTML='<button id="dsh-mkt-log" type="button" aria-label="导出日志">'
            +MKT_LOG_SVG+'<span class="dsh-mkt-log-t">导出日志</span></button>'
          +'<button id="dsh-mkt-pnpm" type="button">安装 pnpm</button>'
          +'<button id="dsh-mkt-upd" type="button">更新插件市场</button>';
        bar.querySelector('#dsh-mkt-upd').addEventListener('click',function(e){
          e.preventDefault();e.stopPropagation();
          var v=marketState().market||'未知';
          if(window.confirm('将重新下载并安装插件市场（当前版本 '+v+'）。继续？')){
            installMarketNow();
          }
        });
        bar.querySelector('#dsh-mkt-pnpm').addEventListener('click',function(e){
          e.preventDefault();e.stopPropagation();
          installPnpmNow();
        });
        // 代理按钮：点了就去点 dsh 原生那个「导出日志」。原生按钮被我们
        // display:none 掉了，但程序化点击不受布局影响（见本段顶部注释）。
        bar.querySelector('#dsh-mkt-log').addEventListener('click',function(e){
          e.preventDefault();e.stopPropagation();
          var n=mktFind(root,/导出日志/);
          if(n){try{n.click();}catch(err){}}
        });
      }
      if(!root.contains(bar)){ placeMarketBar(root,bar,mktFind(root,/插件市场/)); }
      // 原生导出日志在 → 显示代理按钮；不在（版本变了）→ 藏起代理，别给死按钮
      var logBtn=document.getElementById('dsh-mkt-log');
      if(logBtn){
        if(mktFind(root,/导出日志/)){logBtn.removeAttribute('hidden');}
        else{logBtn.setAttribute('hidden','hidden');}
      }
      var pb=document.getElementById('dsh-mkt-pnpm');
      if(pb){
        if(!marketState().pnpm){pb.removeAttribute('hidden');}
        else{pb.setAttribute('hidden','hidden');}
      }
    }
    // r58：若当前就是市场页，注入操作条 + 整理头部。
    // 放在 mount 的收尾处，任何一次整页加载都会执行；内部幂等，重复调用无副作用。
    // MutationObserver 与 500ms 轮询也会再调一次（dshmarket 渲染可能更晚）。
    // ⚠️ try/catch 是刻意加的防线：这是**纯附加功能**，不该有能力把 mount 打断。
    // r58 那次事故正是这里抛了 ReferenceError，顺手打掉了后面 6 项关键功能。
    try{ marketPageBar(); }catch(e){ if(window.console)console.error('[dsh] marketPageBar', e); }
    function dshAlive(){
      if(document.documentElement&&document.documentElement.getAttribute('data-dsh-error')!=null){return true;}
      // ⚠️ #dsh-market-shell **必须保留**。独立审查建议删它，理由是「无产出方」——
      //   但那是**我们的代码**不产生它，不等于 dsh 不产生它：它很可能是 dsh 市场页
      //   自己的根节点 id。删掉的话，市场页会被 rescue() 判成「坏掉的文档」而弹回
      //   首页 —— 正是 U4 描述的导航劫持。**盲从这条会重新引入 U4**，故保留。
      return !!(document.querySelector('div[class*="frame"],div[class*="centerCol"],'
        + '[data-shell-overlay],#dsh-market-shell'));
    }
    var RESCUE_MAX=3,RESCUE_N=0;
    function docLooksAlive(){
      var b=document.body;
      return !!(b&&b.getElementsByTagName('*').length>50);
    }
    function rescue(){
      var entry=location.origin+'/';
      if(RESCUE_N>=RESCUE_MAX)return false;
      if(docLooksAlive())return false;
      if(!dshAlive()&&location.href!==entry){
        RESCUE_N++;
        try{ location.replace(entry); }catch(e){}
        return true;
      }
      return false;
    }
    rescue();
    setTimeout(rescue,600);
    setTimeout(rescue,1800);
    window.addEventListener('popstate',function(){ setTimeout(rescue,200); });
    var moRaf=false;
    if(window.MutationObserver){
      new MutationObserver(function(){
        if(moRaf)return;
        moRaf=true;
        // 每轮 DOM 变化都顺手做三件事（全部幂等）：
        //   ① tagHosts  —— 维护我们自己的指纹 .dsh-s-ov/.dsh-s-nav；
        //   ② syncOvFlag —— 同步 dsh-ov-open，并保证顶栏在树上（U18 自愈）；
        //   ③ 市场页头部整理 —— 幂等，自己会判断有没有 [data-dsh-market-root]
        var run=function(){
          moRaf=false;tagHosts();syncOvFlag();arrowizeClose();
          try{marketPageBar();}catch(e){}
        };
        if(window.requestAnimationFrame){window.requestAnimationFrame(run);}
        else{setTimeout(run,0);}
      }).observe(document.body,{childList:true,subtree:true});
    }
    // 汉堡 / 遮罩的 click 监听已在 ensureTopBar() 里绑定（幂等，重复调用不会叠加）。
    document.addEventListener('keydown',function(e){
      if(e.key!=='Escape')return;
      var bb=document.body;
      if(bb.classList.contains('dsh-s-l3'))return;
      if(bb.classList.contains('dsh-s-l2')){bb.classList.remove('dsh-s-l2');syncBackMark();return;}
      close();
      syncBackMark();
    });
    var tries=0;
    var poll=setInterval(function(){
      if(!mq.matches){clearInterval(poll);return;}
      expandSidebarOnce();
      if(++tries>20){clearInterval(poll);}
    },300);
    if(mq.addEventListener){mq.addEventListener('change',onMq);}
    else if(mq.addListener){mq.addListener(onMq);}
  }
  function expandSidebarOnce(){
    var f=document.querySelector('div[class*="frame"]');
    if(!f||!f.hasAttribute('data-sidebar-collapsed')){return;}
    var bs=document.querySelectorAll('div[class*="sidebarCol"] button[aria-label]');
    for(var i=0;i<bs.length;i++){
      var al=bs[i].getAttribute('aria-label')||'';
      if(/^(收起侧边栏|Collapse sidebar|打开侧边栏|Open sidebar)$/.test(al)){
        bs[i].click();
        return;
      }
    }
  }
  function onMq(e){if(!e.matches){close();}}
  if(document.body){mount();}
  else{document.addEventListener('DOMContentLoaded',mount);}
})();
