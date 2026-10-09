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
    var mark=document.createElement('div');mark.id=SID;
    document.body.appendChild(mark);
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
    var inMarket=DSH_MKT_RE.test(location.pathname);
    function applyDshScope(){
      inMarket=DSH_MKT_RE.test(location.pathname);
      st.textContent=inMarket?__DSH_MARKET_CSS__:__DSH_CSS__;
      var ms=document.getElementById(msId);
      if(inMarket){
        if(!ms){ms=document.createElement('style');ms.id=msId;ms.textContent=__DSH_MARKET_CHROME_CSS__;          (document.head||document.documentElement).appendChild(ms);}
      }else if(ms&&ms.parentNode){ms.parentNode.removeChild(ms);}
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
    function ovVisible(o){
      var cs=window.getComputedStyle(o);
      if(!cs||cs.display==='none'||cs.visibility==='hidden'){return false;}
      if(parseFloat(cs.opacity||'1')===0){return false;}
      var r=null;
      try{ r=o.getBoundingClientRect(); }catch(e){ r=null; }
      if(!r||r.width<=1||r.height<=1){return false;}
      var vw=window.innerWidth||0,vh=window.innerHeight||0;
      if(vw&&vh&&(r.left>vw-2||r.top>vh-2||r.right<2||r.bottom<2)){return false;}
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
      // U18（r66）· 重载兜底必须**整个页面只允许一次**。
      //   r65 那版把"试过没有"记在 ovCloseTried 上，而它只在 `!ov` 时才复位 ——
      //   但 closeSettingsPanel() 只会在**有浮层时**被调用，于是第一次成功关闭之后
      //   ovCloseTried 永远停在 true ⇒ **第二次**关设置面板直接 location.reload()。
      //   用户看到的就是「侧滑返回后回到首页，汉堡没了 / 界面被整页刷掉」。
      var ovReloadedOnce=false;
      // 关闭动作里我们自己那套布局状态的复位。
      //   l2/l3 决定「导航列表 / 内容」谁显示，ov-open 决定汉堡与遮罩显不显示。
      // 抽成函数是因为有三处退出路径都要复位（点 × / 点遮罩 / 侧滑与返回键）。
      function clearOvState(){
        var bd=document.body;
        if(bd){bd.classList.remove('dsh-s-l2');bd.classList.remove('dsh-s-l3');bd.classList.remove('dsh-ov-open');}
        // 顶栏若被 React 整体重渲染冲掉，这里立刻补回来（见 ensureTopBar 的注释）。
        ensureTopBar();
      }
      function closeSettingsPanel(){
        ovCloseRequested=true;
        var ov=settingsOverlay();
        if(!ov){
          ovCloseTried=false;
          clearOvState();
          return;
        }
        var c=visibleClose(ov);
        if(!c){ c=ov.querySelector('[class*="_close"],button[aria-label*="关闭"],button[aria-label*="Close"]'); }
        if(c){ try{ c.click(); }catch(e){} }
        // 兜底：无论有没有点到 dsh 的出口，都把我们自己的布局状态复位，
        // 至少不能让用户卡在一个"既没有出口、返回键也没反应"的界面里。
        clearOvState();
        // 点 × 之后同步就已经看不见浮层了 → 这就是正常路径，绝不重载页面。
        if(!settingsOverlay()){ ovCloseTried=false; return; }
        if(ovCloseTried){
          // 重试过一次、浮层**仍然可见** ⇒ dsh 这个版本的出口不响应程序化点击。
          // 宁可重载一次本地页面（代价约 1s），也不能给用户一个死界面；
          // 但整页只允许一次，否则就变成"每次返回都重载"（那正是 r65 的事故）。
          if(!ovReloadedOnce){ ovReloadedOnce=true; try{ location.reload(); }catch(e){} }
          return;
        }
        ovCloseTried=true;
        try{
          setTimeout(function(){
            if(settingsOverlay()){closeSettingsPanel();}
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
      function syncBackHandled(){
        var bd=document.body;
        var v=!!(bd&&(bd.classList.contains('dsh-ov-open')||bd.classList.contains('dsh-drawer-open')));
        if(v===_backHandled){return;}
        _backHandled=v;
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
      function pushPanelHistory(){
        try{
          if(history.state&&history.state[PANEL_MARK])return;   // 已压过，别重复压
          history.pushState({dshPanel:1},'');
        }catch(e){}
      }
      function popPanelHistory(){
        try{
          if(history.state&&history.state[PANEL_MARK]){history.back();}
        }catch(e){}
      }
      // U17（r65）· P0 修复：这里原来写的是 `b.classList...`，而 `b` 只在
      //   mount() 里那个设置面板 IIFE 内定义（var b=document.body）。
      //   本处理器在 **mount 作用域**，读一个未声明变量必然抛 ReferenceError；
      //   事件监听器一抛异常就当场中断，后面几行 remove 永远执行不到
      //   → 面板关不掉 → 用户看到的就是「侧滑返回没反应」；而历史项已被消费，
      //   再滑一次就退出 App。**这才是「侧滑返回不能用」的真正根因**，
      //   跟 MainActivity 有没有 onBackPressed 无关。
      //   修法：处理器内部自取 body，并整体包 try/catch（监听器绝不能抛）。
      window.addEventListener('popstate',function(){
        try{
          var bd=document.body;
          if(!bd){return;}
          // 只处理「状态里没有我们的标记」的情况 = 用户侧滑/返回键退回来了
          var st=null;
          try{ st=history.state; }catch(e){}
          if(st&&st[PANEL_MARK]){return;}
          if(bd.classList.contains('dsh-ov-open')){
            // 真的关，而不是"删掉我们的 class 假装关了"
            closeSettingsPanel();
          }
          // 抽屉是我们的 CSS 画出来的（transform），删 class 就是真关闭
          bd.classList.remove('dsh-drawer-open');
        }catch(e){}
        syncBackHandled();
      });
      function syncOvFlag(){
        var b=document.body;
        if(!b){return;}
        // U18（r66）：顺手保证顶栏在树上。dsh 的 React 树整体替换 body 子节点时，
        //   我们的 #dsh-mtop/#dsh-scrim 会一起消失；而注入脚本每页只执行一次
        //   （mount() 有 SID 幂等守卫），没人再补 → 汉堡永久消失。
        //   这里幂等补回，配合 MutationObserver + 500ms 轮询构成自愈。
        ensureTopBar();
        var on=!!settingsOverlay();
        if(on!==b.classList.contains('dsh-ov-open')){
          if(on){
            b.classList.add('dsh-ov-open');
          }else{
            b.classList.remove('dsh-ov-open');
          }
        }
        syncBackMark();
      }
      // 覆盖层（设置面板 / 抽屉）与"压一条历史"必须严格同进同出：
      //   只要有一个覆盖层开着，历史里就该有我们压的那一条，侧滑才有东西可退；
      //   都关了，就该把它消费掉，免得后面多退一次。
      function syncBackMark(){
        var bd=document.body;
        if(!bd){return;}
        var need=!!settingsOverlay()||bd.classList.contains('dsh-drawer-open');
        if(need){pushPanelHistory();}else{popPanelHistory();}
        syncBackHandled();
      }
    if(!inMarket){
    (function(){
      if(!mq.matches){return;}
      var b=document.body;
      function setL2(){b.classList.remove('dsh-s-l3');b.classList.add('dsh-s-l2');}
      function setL3(){b.classList.remove('dsh-s-l2');b.classList.add('dsh-s-l3');}
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
        return c;
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
        cell.appendChild(bd);
      }
      function marketEntry(){
        var n=marketCell();
        if(n){
          removeMineCell();      // dsh 自己画了原生项 → 我们的注入项退出
          n.setAttribute('data-dsh-mkt','1');
          decorateEntry(n);
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
        var onFirstPage=!b.classList.contains('dsh-s-l3');
        if(!ov||!onFirstPage){
          if(bar&&bar.parentNode){bar.parentNode.removeChild(bar);}
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
          closeSettings();
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
      function sync(){
        var ov=settingsOverlay();
        if(!ov){
          ovCloseRequested=false;   // 浮层真没了 → 清掉"用户要求关闭"的标记
          b.classList.remove('dsh-s-l2');b.classList.remove('dsh-s-l3');
          syncOvFlag();
          if(panelOpen){panelOpen=false;leaveFocus();}
          syncBack();
          return;
        }
        syncOvFlag();
        tagHosts();
        marketEntry();
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
        if(!b.classList.contains('dsh-s-l2')&&!b.classList.contains('dsh-s-l3')&&!ovCloseRequested){setL2();}
        syncBack();
      }
      function syncBack(){
      }
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
        try{
          if(b){
            if(b.classList.contains('dsh-drawer-open')){close();syncBackMark();handled=true;}
            else if(settingsOverlay()){
              if(b.classList.contains('dsh-s-l3')){setL2();syncBack();}
              else{closeSettings();}
              handled=true;
            }
          }
        }catch(e){}
        if(!handled){
          // 自愈：走到这里说明原生侧的上报已经过期（例如浮层被 dsh 自己关掉、
          // 而我们还没跑下一轮 sync）。若不立刻纠正，原生会继续以为"该由 JS 处理"，
          // 表现就是**返回键彻底没反应** —— 比退出 App 更难排查。
          _backHandled=false;
          try{
            if(window.dshNative&&window.dshNative.setBackHandled){window.dshNative.setBackHandled(false);}
          }catch(e){}
        }
        return handled;
      }
      try{ window.__dshBack=backOneLevel; }catch(e){}
      document.addEventListener('click',function(e){
        var t=e.target;
        if(!t||!t.closest){sync();return;}
        var closer=t.closest('[class*="_close"],button[aria-label*="关闭"],button[aria-label*="Close"]');
        if(closer){
          if(b.classList.contains('dsh-s-l3')){
            e.preventDefault();e.stopPropagation();setL2();syncBack();
          }else{
            b.classList.remove('dsh-s-l2');b.classList.remove('dsh-s-l3');
          }
          return;
        }
        var ov=settingsOverlay();
        var onMask=ov&&(t===ov||(t.className&&String(t.className).indexOf('_mask')>=0));
        if(onMask){b.classList.remove('dsh-s-l2');b.classList.remove('dsh-s-l3');syncBack();return;}
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
        if(cell&&b.classList.contains('dsh-s-l2')){setL3();syncBack();}
        else{sync();}
      },true);
      document.addEventListener('keydown',function(e){
        if(e.key==='Escape'&&b.classList.contains('dsh-s-l3')){
          e.preventDefault();e.stopPropagation();setL2();syncBack();
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
    })();
    }
    // ===== 顶栏（汉堡 + 遮罩）· 幂等自愈（r66 · U18）=====
    // 背景：r41 修过一轮「汉堡按钮消失」，r66 用户又报了同类现象 —— 从设置页侧滑返回后
    //   回到首页，左上角没有汉堡。两个并存的原因：
    //     ① dsh-ov-open 被错误地留在 body 上（→ 见 ovVisible/settingsOverlay 的注释）；
    //     ② 顶栏是 document.body 的直接子节点，dsh 的 React 树一旦整体替换 body 的
    //        子节点，它就被冲掉了，而注入脚本每页只跑一次、不会补。
    //   所以：创建 + 事件绑定全部收进这个幂等函数，交给 MutationObserver 与 500ms
    //   轮询兜底调用 —— 无论谁把顶栏弄丢，下一轮就会被原样补回，且不会重复绑定。
    var MENU_SVG='<svg width="22" height="22" viewBox="0 0 24 24" fill="none"'
      +' stroke="currentColor" stroke-width="2" stroke-linecap="round">'
      +'<path d="M3 6h18M3 12h18M3 18h18"/></svg>';
    function onMenuClick(e){
      e.preventDefault();e.stopPropagation();
      var b=document.body;
      if(!b){return;}
      if(b.classList.contains('dsh-drawer-open')){close();syncBackMark();return;}
      try{expandSidebarOnce();}catch(err){}
      b.classList.add('dsh-drawer-open');
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
    function ensureTopBar(){
      var b=document.body;
      if(!b){return null;}
      var bar=document.getElementById('dsh-mtop');
      if(!bar){
        bar=document.createElement('div');
        bar.id='dsh-mtop';
        bar.innerHTML='<button id="dsh-mbtn" type="button" aria-label="菜单">'+MENU_SVG+'</button>';
        b.appendChild(bar);
      }else if(bar.parentNode!==b){
        b.appendChild(bar);   // 被挪走/被冲掉后重新挂回 body
      }
      // __dshBound 是我们自己的幂等标记：节点是我们造的，只在创建时绑一次，
      // 重复调用 ensureTopBar() 不会叠加监听器。
      var btn=bar.querySelector('#dsh-mbtn')||document.getElementById('dsh-mbtn');
      if(!btn){
        // 顶栏在、但里面的按钮被 React 清掉了 —— 重建内容（同样只在需要时做）
        bar.innerHTML='<button id="dsh-mbtn" type="button" aria-label="菜单">'+MENU_SVG+'</button>';
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
    setInterval(function(){
      if(document.hidden)return;
      tagHosts();syncOvFlag();detectDark();
      // 市场页：dshmarket 是独立 bundle，可能在我们注入之后才渲染完 —— 反复尝试
      // 整理它的头部（全部幂等），渲染晚也不会漏。
      if(inMarket){try{marketPageBar();}catch(e){}}
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
    function installPnpmNow(after){
      if(pnpmBusy){return;}
      pnpmBusy=true;
      announce('正在下载并安装 pnpm');
      window.__dshPnpmDone=function(id,res){
        pnpmBusy=false;
        if(res&&res.ok){ announce('pnpm 安装完成'); if(after){after(res);} }
        else { alert('pnpm 安装失败：'+((res&&res.message)||'未知错误')); announce('pnpm 安装失败'); }
      };
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
    // 给市场页头部打标记（只加属性，不动节点）。
    function marketDecorate(root){
      if(!root){return;}
      // ② 标题 + 版本号 + 标题行（标题行强制横排，见 market.css）
      var title=mktFind(root,/^插件市场/);
      if(title&&mktTextLen(title)>24){title=null;}   // 太长 = 命中了卡片正文，不要
      if(title){
        title.setAttribute('data-dsh-mkt-title','1');
        var row=title.parentNode;
        if(row&&row!==root&&row.setAttribute){row.setAttribute('data-dsh-mkt-row','1');}
        // 标题左边的方块图标（一般是无文字的 svg/img/div），只打标不搬动
        var logo=title.previousElementSibling||(row&&row.previousElementSibling);
        if(logo&&!(String(logo.textContent||'').trim())&&!logo.getAttribute('data-dsh-mkt-title')){
          logo.setAttribute('data-dsh-mkt-logo','1');
        }
      }
      var ver=mktFind(root,/^dsh-market/i);
      if(ver&&mktTextLen(ver)>40){ver=null;}
      if(ver){ver.setAttribute('data-dsh-mkt-ver','1');}
      // ④ 社区说明：它常与「导出日志」同容器（裸文本 + 两个按钮），所以只能
      //    "隐藏文字"不能整块隐藏 —— CSS 侧用 font-size:0 藏裸文本、再把直接子元素
      //    恢复字号。锚定"发现社区/社区为"开头并限制长度，避免命中卡片正文。
      var desc=mktFind(root,/^发现社区|^社区为/);
      if(desc&&(mktTextLen(desc)>120||String(desc.textContent||'').indexOf('插件市场')>=0)){desc=null;}
      if(desc){desc.setAttribute('data-dsh-mkt-desc','1');}
      var sug=mktFind(root,/申请收录/);
      if(sug){sug.setAttribute('data-dsh-mkt-hide','1');}
      // 设计稿操作区只有两个按钮 → dsh 的「重启前都不再提醒」不显示
      // （它只是"别再提醒我更新"的开关，隐藏不影响任何数据/功能，随时可一行恢复）
      var rem=mktFind(root,/不再提醒/);
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
        //   ③ 市场页头部整理 —— 只在市场页跑。
        var run=function(){
          moRaf=false;tagHosts();syncOvFlag();
          if(inMarket){try{marketPageBar();}catch(e){}}
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
