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
      function mkMarketCard(){
        if(document.getElementById('dsh-market-card')){return;}
        var nav=document.querySelector('.dsh-s-nav');
        if(!nav){return;}
        var c=document.createElement('div');c.id='dsh-market-card';
        c.innerHTML='<div class="dsh-mk-head">'
          +'<span class="dsh-mk-ico">'
          +'<svg width="20" height="20" viewBox="0 0 24 24" fill="none"'
          +' stroke="currentColor" stroke-width="1.9" stroke-linecap="round"'
          +' stroke-linejoin="round"><path d="M4 7h16M4 12h16M4 17h10"/></svg></span>'
          +'<div class="dsh-mk-htxt">'
          +'<div class="dsh-mk-title">插件市场</div>'
          +'<div class="dsh-mk-sub">社区插件 · 一键装到本机</div></div>'
          +'<span class="dsh-mk-chip" id="dsh-mk-chip">检测中</span></div>'
          +'<div class="dsh-mk-actions">'
          +'<button id="dsh-market-btn" type="button">检测中…</button>'
          +'<button id="dsh-pnpm-btn" type="button" hidden>安装 pnpm</button></div>'
          +'<div class="dsh-mk-note" id="dsh-mk-note">安装插件需要 pnpm 包管理器</div>';
        c.querySelector('#dsh-market-btn').addEventListener('click',onMarket);
        c.querySelector('#dsh-pnpm-btn').addEventListener('click',onPnpm);
        nav.insertBefore(c, nav.firstChild);
        refreshMarket();
      }
      function marketCell(){
        var cells=document.querySelectorAll('.dsh-s-nav [class*="_navCell"]');
        for(var i=0;i<cells.length;i++){
          var t=(cells[i].textContent||'').replace(/\s+/g,'');
          if(t==='插件市场'||t==='PluginMarket'){return cells[i];}
        }
        return null;
      }
      var FOCUSABLE='a[href],button:not([disabled]),input:not([disabled]),select:not([disabled]),textarea:not([disabled]),[tabindex]';
      function focusables(ov){
        var all=(ov||document).querySelectorAll(FOCUSABLE);
        var out=[];
        for(var i=0;i<all.length;i++){
          var e=all[i],r=e.getBoundingClientRect();
          if(e.getAttribute('tabindex')==='-1'){continue;}
          if(r.width>0&&r.height>0){out.push(e);}
        }
        return out;
      }
      var lastFocus=null;
      var panelOpen=false;
      function trapFocus(e){
        if(e.key!=='Tab'){return;}
        var ov=document.querySelector('[class*="_panel"]');
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
      function announce(txt){
        var box=document.getElementById('dsh-live');
        if(!box){return;}
        box.textContent='';
        setTimeout(function(){box.textContent=txt;},30);
      }
      function dedupeMarket(){
        var c=marketCell();
        if(!c){return;}
        c.setAttribute('data-dsh-dup','1');
        c.style.setProperty('display','none','important');
      }
      function openMarket(btn){
        var cell=marketCell();
        if(cell){ try{ cell.click(); return 'nav'; }catch(e){} }
        var note=document.getElementById('dsh-mk-note');
        if(note){ note.textContent='未能定位市场入口，请重新打开设置页'; }
        if(btn){ btn.textContent='打开失败'; }
        return 'fail';
      }
      function guardCard(){
        var c=document.getElementById('dsh-market-card');
        if(!c){return;}
        var vw=document.documentElement.clientWidth||window.innerWidth||360;
        var w=Math.max(220,Math.round(vw)-32);
        var r=c.getBoundingClientRect();
        var bad=(r.width>vw+1)||(r.right>vw+1)||(r.left<-1)||(r.height>vw*1.6);
        if(bad){
          c.style.setProperty('flex','0 0 auto','important');
          c.style.setProperty('align-self','flex-start','important');
          c.style.setProperty('width',w+'px','important');
          c.style.setProperty('max-width',w+'px','important');
          c.style.setProperty('min-width','0','important');
          c.style.setProperty('height','auto','important');
          c.style.setProperty('box-sizing','border-box','important');
          c.style.setProperty('overflow','hidden','important');
          c.style.setProperty('margin-left','16px','important');
          c.style.setProperty('margin-right','16px','important');
          c.setAttribute('data-dsh-guarded','1');
        }else if(c.getAttribute('data-dsh-guarded')==='1'){
          var ks=['flex','align-self','width','max-width','min-width','height',
                  'box-sizing','overflow','margin-left','margin-right'];
          for(var i=0;i<ks.length;i++){c.style.removeProperty(ks[i]);}
          c.removeAttribute('data-dsh-guarded');
        }
      }
      function refreshMarket(){
        var btn=document.getElementById('dsh-market-btn');
        if(!btn){return;}
        var chip=document.getElementById('dsh-mk-chip');
        var v='';
        if(window.dshNative){try{ v=window.dshNative.marketInstalled(); }catch(e){}}
        if(v){ btn.textContent='打开市场'; btn.removeAttribute('disabled');
          btn.setAttribute('data-open','1');
          if(chip){ chip.textContent='已安装 v'+v; chip.setAttribute('data-on','1'); } }
        else { btn.textContent='一键安装'; btn.removeAttribute('disabled');
          btn.removeAttribute('data-open');
          if(chip){ chip.textContent='未安装'; chip.removeAttribute('data-on'); } }
        refreshPnpm();
      }
      var pnpmBusy=false;
      function refreshPnpm(){
        var pb=document.getElementById('dsh-pnpm-btn');
        var note=document.getElementById('dsh-mk-note');
        if(!pb){return;}
        var ready='';
        if(window.dshNative){try{ ready=window.dshNative.pnpmReady(); }catch(e){}}
        if(ready){ pb.hidden=true;
          if(note){ note.textContent='依赖已就绪，可直接安装插件'; } }
        else { pb.hidden=false; pb.textContent='安装 pnpm'; pb.removeAttribute('disabled');
          if(note){ note.textContent='安装插件需要 pnpm 包管理器'; } }
      }
      function onPnpm(e){
        e.preventDefault();e.stopPropagation();
        if(pnpmBusy){return;}
        pnpmBusy=true;
        var pb=document.getElementById('dsh-pnpm-btn');
        var note=document.getElementById('dsh-mk-note');
        pb.setAttribute('disabled','disabled');pb.textContent='安装中…';
        if(note){ note.textContent='正在下载并安装 pnpm…'; }
        window.__dshPnpmDone=function(id,res){
          pnpmBusy=false;
          if(res&&res.ok){ pb.hidden=true; pb.removeAttribute('disabled');
            if(note){ note.textContent='依赖已就绪，可直接安装插件'; }
            announce('pnpm 安装完成，可直接安装插件'); }
          else { pb.removeAttribute('disabled'); pb.textContent='重试';
            if(note){ note.textContent='pnpm 安装失败'; }
            alert('pnpm 安装失败：'+((res&&res.message)||'未知错误'));
            announce('pnpm 安装失败'); }
        };
        try{ window.dshNative.installPnpm('pnpm'); }
        catch(err){ pnpmBusy=false; pb.removeAttribute('disabled'); pb.textContent='重试'; }
      }
      var installing=false;
      function onMarket(e){
        e.preventDefault();e.stopPropagation();
        var btn=document.getElementById('dsh-market-btn');
        if(!btn){return;}
        if(btn.getAttribute('data-open')==='1'){ openMarket(btn); return; }
        if(installing){return;}
        installing=true;
        var note=document.getElementById('dsh-mk-note');
        var chip=document.getElementById('dsh-mk-chip');
        btn.setAttribute('disabled','disabled');btn.textContent='安装中…';
        if(note){ note.textContent='正在下载并安装插件…'; }
        announce('正在下载并安装插件');
        window.__dshMarketDone=function(id,res){
          installing=false;
          if(res&&res.ok){ btn.removeAttribute('disabled');
            btn.textContent='打开市场'; btn.setAttribute('data-open','1');
            if(chip){ chip.textContent='已安装 v'+(res.version||'');
              chip.setAttribute('data-on','1'); }
            if(note){ note.textContent='安装完成，点「打开市场」进入'; }
            announce('插件市场安装完成'); }
          else { btn.removeAttribute('disabled'); btn.textContent='重试';
            if(note){ note.textContent='安装失败'; }
            alert('安装失败：'+((res&&res.message)||'未知错误'));
            announce('插件安装失败'); }
        };
        try{ window.dshNative.installMarket('mkt'); }
        catch(err){ installing=false; btn.removeAttribute('disabled'); btn.textContent='重试'; }
      }
      function sync(){
        var ov=settingsOverlay();
        if(!ov){
          b.classList.remove('dsh-s-l2');b.classList.remove('dsh-s-l3');
          syncOvFlag();
          if(panelOpen){panelOpen=false;leaveFocus();}
          var mc=document.getElementById('dsh-market-card');
          if(mc&&mc.parentNode){mc.parentNode.removeChild(mc);}
          syncBack();
          return;
        }
        syncOvFlag();
        tagHosts();
        mkMarketCard();
        dedupeMarket();
        detectDark();
        if(!panelOpen){panelOpen=true;
          lastFocus=document.activeElement;
          enterFocus(document.querySelector('[class*="_panel"]'));
        }
        if(!b.classList.contains('dsh-s-l2')&&!b.classList.contains('dsh-s-l3')){setL2();}
        guardCard();
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
    function dshAlive(){
      if(document.documentElement&&document.documentElement.getAttribute('data-dsh-error')!=null){return true;}
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
