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
    function settingsOverlay(){
      var ovs=document.querySelectorAll('[class*="_overlay"]');
      for(var i=0;i<ovs.length;i++){
        var o=ovs[i];
        if(!o.querySelector('[class*="_panel"]')){continue;}
        if(!o.querySelector('[class*="_navList"]')){continue;}
        var cs=window.getComputedStyle(o);
        if(cs.display!=='none'&&cs.visibility!=='hidden'){return o;}
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
    function syncOvFlag(){
      var b=document.body;
      if(!b){return;}
      var on=!!settingsOverlay();
      if(on!==b.classList.contains('dsh-ov-open')){
        if(on){b.classList.add('dsh-ov-open');}else{b.classList.remove('dsh-ov-open');}
      }
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
      function marketEntry(){
        var cell=marketCell();
        if(!cell){return;}
        cell.setAttribute('data-dsh-mkt','1');
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
        var b=document.createElement('span');
        b.className='dsh-mkt-flag';
        b.textContent=txt;
        cell.appendChild(b);
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
        for(i=0;i<cells.length;i++){
          if(cells[i].getAttribute('data-dsh-market')!=null
             ||cells[i].getAttribute('data-plugin-market')!=null
             ||cells[i].getAttribute('data-testid')==='plugin-market'){return cells[i];}
        }
        for(i=0;i<cells.length;i++){
          al=((cells[i].getAttribute('aria-label')||'')+' '+(cells[i].getAttribute('title')||'')).toLowerCase();
          if(al.indexOf('插件')>=0||al.indexOf('市场')>=0
             ||al.indexOf('plugin')>=0||al.indexOf('market')>=0){return cells[i];}
        }
        for(i=0;i<cells.length;i++){
          t=(cells[i].textContent||'').replace(/\s+/g,'');
          if(t==='插件市场'||t==='PluginMarket'){return cells[i];}
        }
        for(i=0;i<cells.length;i++){
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
      function closeSettings(){
        var ov=settingsOverlay();
        if(ov){
          var c=ov.querySelector('[class*="_close"],button[aria-label*="关闭"],button[aria-label*="Close"]');
          if(c){ try{ c.click(); return; }catch(e){} }
        }
        // 找不到 dsh 的关闭按钮就退而求其次：把状态复位，让面板自然收起
        b.classList.remove('dsh-s-l2');b.classList.remove('dsh-s-l3');
        b.classList.remove('dsh-ov-open');
      }
      function ensureBackBar(){
        var ov=settingsOverlay();
        var bar=document.getElementById('dsh-back');
        if(!ov){
          if(bar&&bar.parentNode){bar.parentNode.removeChild(bar);}
          return;
        }
        if(bar){return;}
        // 面板自带关闭按钮就别再加一条返回条，避免与 × 并排出现两个同样意思的出口
        if(ov.querySelector('[class*="_close"],button[aria-label*="关闭"],button[aria-label*="Close"]')){
          return;
        }
        bar=document.createElement('div');
        bar.id='dsh-back';
        bar.innerHTML='<button id="dsh-back-btn" type="button" aria-label="返回">'
          +'<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor"'
          +' stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round">'
          +'<path d="M19 12H5M12 19l-7-7 7-7"/></svg>'
          +'<span class="dsh-back-t">设置</span></button>';
        ov.insertBefore(bar, ov.firstChild);
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
        var need=e.currentTarget.getAttribute('data-dsh-mkt-need');
        if(!need){return false;}
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
        if(!b.classList.contains('dsh-s-l2')&&!b.classList.contains('dsh-s-l3')){setL2();}
        syncBack();
      }
      function syncBack(){
      }
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
        // dsh 的 tab 切换必须同时切到 l3。tab 元素是 role="tab"（取证：dsh-client-ui-*/
        // lib/client.js 里 `role:"tab"` + `aria-selected` + `aria-controls`）。
        // 原因：l2 状态下我们把 [class*="_content"] 隐藏了（见 mobile.css
        //   body.dsh-s-l2 .dsh-s-ov [class*="_panel"] [class*="_content"]{display:none}），
        // 而 tab 切换出来的内容正是在 _content 里 —— 于是「下划线切了、内容还是上一个
        // tab 的」。用户看到的现象就是：插件页点「插件列表」，「插件配置」的内容不消失。
        if(t.closest('[role="tab"]')){ setL3(); }
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
    var bar=document.createElement('div');bar.id='dsh-mtop';
    bar.innerHTML='<button id="dsh-mbtn" type="button" aria-label="菜单">'
      +'<svg width="22" height="22" viewBox="0 0 24 24" fill="none"'
      +' stroke="currentColor" stroke-width="2" stroke-linecap="round">'
      +'<path d="M3 6h18M3 12h18M3 18h18"/></svg></button>'
      +'';
    document.body.appendChild(bar);
    var scrim=document.createElement('div');scrim.id='dsh-scrim';
    document.body.appendChild(scrim);
    setInterval(function(){
      if(document.hidden)return;
      tagHosts();syncOvFlag();detectDark();
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
    // 设计稿图 3：市场页标题区下方那行操作里的「更新插件市场」。
    // 「导出日志」是 dshmarket 页面自带的，我们只注入更新 + pnpm 缺失时的安装。
    function marketPageBar(){
      var root=document.querySelector('[data-dsh-market-root]');
      if(!root){return;}
      var bar=document.getElementById('dsh-mkt-bar');
      if(!bar){
        bar=document.createElement('div');
        bar.id='dsh-mkt-bar';
        bar.innerHTML='<button id="dsh-mkt-pnpm" type="button">安装 pnpm</button>'
          +'<button id="dsh-mkt-upd" type="button">更新插件市场</button>';
        root.insertBefore(bar, root.firstChild);
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
      }
      var need=!marketState().pnpm;
      var pb=bar.querySelector('#dsh-mkt-pnpm');
      if(need){ pb.removeAttribute('hidden'); }else{ pb.setAttribute('hidden','hidden'); }
    }
    // r58：若当前就是市场页，注入「安装 pnpm / 更新插件市场」操作条。
    // 放在 mount 的收尾处，任何一次整页加载都会执行；内部幂等，重复调用无副作用。
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
        var run=function(){moRaf=false;tagHosts();syncOvFlag();};
        if(window.requestAnimationFrame){window.requestAnimationFrame(run);}
        else{setTimeout(run,0);}
      }).observe(document.body,{childList:true,subtree:true});
    }
    bar.querySelector('#dsh-mbtn').addEventListener('click',function(e){
      e.preventDefault();e.stopPropagation();
      var b=document.body;
      if(b.classList.contains('dsh-drawer-open')){close();return;}
      try{expandSidebarOnce();}catch(err){}
      b.classList.add('dsh-drawer-open');
      var n=0;
      var iv=setInterval(function(){
        try{expandSidebarOnce();}catch(err){}
        if(++n>8){clearInterval(iv);}
      },140);
    });
    scrim.addEventListener('click',close);
    document.addEventListener('keydown',function(e){
      if(e.key!=='Escape')return;
      var bb=document.body;
      if(bb.classList.contains('dsh-s-l3'))return;
      if(bb.classList.contains('dsh-s-l2')){bb.classList.remove('dsh-s-l2');return;}
      close();
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
