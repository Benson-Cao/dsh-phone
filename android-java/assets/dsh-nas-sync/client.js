window.__ModuleLoader__.load({
  id: 'dsh-nas-sync',
  factory(require) {
    const React = require('react');
    const h = React.createElement;
    const { useState, useEffect, useCallback, useRef } = React;

    const NS = 'nas.sync';
    // 注意：旧 @local/dsh-plugin-nas 也注册 key/id 'nas-sync'，为避免 slot 冲突改用 '-tools' 后缀
    const PANEL_ID = 'nas-sync-tools';

    // ---- 同步内容分类（与服务端 SYNC_CATEGORIES 一一对应，顺序即展示顺序）----
    // 服务端是权威：这里只是为了渲染勾选框；对不上的键服务端会忽略并告警。
    const SCOPE_ORDER = ['sessions', 'workspaces', 'workspaceFiles', 'customFolders', 'sessionTitles', 'attachments', 'skills', 'profiles', 'settings', 'pluginPatch'];
    const SCOPE_DEFAULT = {
      sessions: true, workspaces: true, workspaceFiles: true, customFolders: true, sessionTitles: true, attachments: true, skills: true,
      profiles: false, settings: false, pluginPatch: false,
    };

    /**
     * 设备 → emoji。只做平台级区分：目的是一眼看出「这个文件夹主要是谁在用」，
     * 不需要精确到具体机器，所以同平台多台设备共用一个图标。
     */
    const DEVICE_EMOJI = { win32: '💻', android: '📱', darwin: '🖥️', linux: '🐧' };
    const deviceEmoji = (d) => DEVICE_EMOJI[String(d || '').split(':')[0]] || '💾';

    /**
     * 工作区名归一化：把「侧栏显示的名字」和「设备声明里的目录名」对上。
     * 与服务端 engine.normalizeWorkspaceName 必须保持一致。
     */
    const normalizeWsName = (raw) => {
      const s = String(raw || '')
        .replace(/[（(][^）)]*[）)]/g, '')
        .replace(/[_\s-]+/g, '')
        .toLowerCase();
      return (s === '默认工作区' || s === 'defaultworkspace' || s === 'default')
        ? 'defaultworkspace' : s;
    };

    /**
     * 侧栏某个工作区该显示什么。返回 { emojis, devices } 或 null。
     * 本机不显示；key（data-row-key 后缀）和 label（显示名）都试一遍，
     * 这样无论宿主用 id 还是 path 当 key 都能匹配上。
     */
    const sidebarBadgeFor = (devices, key, label) => {
      if (!devices || !devices.byWorkspace) return null;
      for (const raw of [key, label]) {
        const k = normalizeWsName(raw);
        if (!k) continue;
        const list = devices.byWorkspace[k] || [];
        const others = list.filter((x) => x.device && x.device !== devices.self);
        if (others.length === 0) continue;
        const emojis = [...new Set(others.map((x) => deviceEmoji(x.device)))].join('');
        return { emojis, devices: others.map((x) => x.device) };
      }
      return null;
    };

    /** 分类 key → 远端清单里的前缀（必须与 SYNC_CATEGORIES 的 localDir 对应）。 */
    const CATEGORY_PREFIX = {
      sessions: 'sessions',
      workspaces: 'storages',
      workspaceFiles: 'workspaces',
      customFolders: 'custom-folders',
      sessionTitles: 'session-titles.json',
      attachments: 'attachments',
      skills: 'skills',
      profiles: 'profiles',
      settings: 'settings.yaml',
      pluginPatch: 'cordis.patch.yml',
    };
    const SCOPE_SENSITIVE = { profiles: true, settings: true, pluginPatch: true };

    const zh = {
      panelLabel: 'NAS 同步',
      title: 'NAS 同步',
      subtitle: '把 DSH 的会话、附件、Skills 加密同步到你的 NAS（WebDAV）。',
      needRefresh: '页面没有拿到请求令牌，请退出本页重新进入。',
      refresh: '刷新',
      statusSection: '同步状态',
      pluginSection: '插件同步管理',
      // 「手机 / 电脑」两侧并排看：两端的插件集本来就不该一样（竖屏适配 vs 桌面布局），
      // 只报差集的话用户没有参照物。标签按设备类别渲染，不写死成手机。
      sideMobile: '手机',
      sideDesktop: '电脑',
      sideCli: '命令行',
      sideUnknown: '设备',
      pluginNoReport: '还没上报插件清单',
      pluginPeerHint: '对端同步一次后显示',
      pluginOnlyHere: '对端没有这个插件',
      pluginDiffTitle: '对端有、本机没有',
      pluginHint: '插件的包体不走同步（只同步装配清单），这里列出「别的设备装了、这台机器没装」的插件。平台专属插件（如手机竖屏适配）按「各自最优」跳过，不算缺失。安装会调用 dsh CLI，装完需重启 DSH。',
      pluginCheck: '对账',
      pluginChecking: '对账中…',
      pluginTarget: '装到',
      pluginInstall: '安装',
      pluginInstallAll: '全部安装',
      pluginAllDone: '所有插件已对齐，没有缺失。',
      pluginInstalled: '本机已装',
      pluginNotRun: '还没对账，点上面的「⟳ 对账」开始（会读取远端装配清单并比对）。',
      pluginMissing: '未安装',
      pluginOutdated: '版本不符',
      pluginNoCli: '这台设备没有可用的 dsh CLI，只能复制命令手动执行。',
      pluginNotInstallable: '指向对端本地路径，本机装不了（需在对端重新 link）',
      // 「各自最优」：平台专属插件故意不装，这不是缺失，要说明白，否则用户以为坏了。
      pluginPlatform: '平台专属，本机不装（各自最优）',
      pluginInstalling: '安装中…',
      pluginDone: '安装完成，重启 DSH 后生效。',
      pluginFailed: '安装失败：',
      lastSync: '上次成功同步',
      never: '从未',
      pending: '待同步',
      conflicts: '冲突',
      running: '进行中',
      idle: '空闲',
      phase: '阶段',
      phaseInterrupted: '上次中断于',
      catchUp: '回到前台，补跑同步',
      syncNow: '立即同步',
      syncing: '同步中…',
      stop: '停止',
      testConnection: '测试连接',
      testing: '测试中…',
      connected: 'NAS 连接正常',
      notConnected: '连接失败',
      syncDone: '完成：上传 {pushed}，下载 {pulled}，失败 {errors}，冲突 {conflicts}',
      syncAbortedNote: '（已按要求的方向处理，其余项保留）',
      configSection: '配置',
      cfgType: '方式',
      cfgUrl: 'WebDAV 地址',
      cfgRemote: '远程目录（共享文件夹）',
      cfgRemoteHint: 'NAS 端的共享文件夹名或子路径，如 /dsh-sync 或 /home/DSH备份；留空使用根目录。fnOS/群晖需对该共享文件夹开启 WebDAV 访问。',
      cfgUser: '用户名',
      cfgPass: '密码',
      cfgMount: '挂载点（mount 方式）',
      cfgPassphrase: '加密口令',
      cfgPassphraseHint: '用于加密上传内容，两端必须一致；留空表示维持原值。',
      cfgAuto: '自动同步',
      cfgAutoOff: '关闭',
      cfgPush: '只上传',
      cfgPull: '只下载',
      cfgMirror: '双向',
      cfgInterval: '同步间隔',
      cfgIntervalCustom: '自定义',
      cfgIntervalUnit: '分钟',
      cfgScope: '同步内容',
      cfgScopeHint: '只同步勾选的内容。取消勾选后，已上传到 NAS 的旧文件不会被删除，只是不再参与同步。',
      scopeSensitive: '敏感',
      scopeAll: '全选',
      scopeNone: '全不选',
      skillsLoading: '正在读取技能…',
      skillsEmpty: '没有找到技能（skills/ 目录为空）',
      skillsHint: '只同步勾选的技能。全部勾上时按「全部技能」处理，以后新增的技能会自动包含。',
      skillsAllOn: '（当前 = 全部技能）',
      scopeSessions: '会话记录',
      scopeWorkspaces: '工作区清单',
      scopeWorkspaceFiles: '工作区文件',
      scopeCustomFolders: '自定义文件夹',
      cfgCustomFolders: '自定义文件夹',
      cfgCustomFoldersHint: '名字是两台设备之间的对应关系（同名 = 同一份数据）；路径只在本机生效，两台电脑可以不一样。',
      cfgCustomFolderAdd: '+ 添加文件夹',
      cfgCustomFolderName: '名字',
      cfgCustomFolderPath: '本机路径',
      cfgCustomFolderRemove: '移除',
      scopeSessionTitles: '会话标题',
      scopeAttachments: '附件',
      scopeSkills: '技能',
      scopeProfiles: 'Profile 与插件',
      scopeSettings: '主设置（settings.yaml）',
      scopePluginPatch: '插件补丁（cordis.patch.yml）',
      save: '保存配置',
      saving: '保存中…',
      saved: '已保存',
      pendingSection: '待同步文件',
      empty: '没有待同步的内容。',
      conflictSection: '冲突',
      noConflicts: '没有冲突。',
      keepLocal: '用本地',
      keepRemote: '用远端',
      skipItem: '跳过',
      tryMerge: '尝试合并',
      merged: '已合并写回本地',
      resolved: '已处理',
      error: '错误',
      unknownError: '未知错误',
      passwordKeep: '已设置（留空维持）',
      loadFail: '加载失败',
      // r172：配置首帧为空时的占位文案（见 PanelView 里的 !cfg 守卫）
      loadingCfg: '正在读取 NAS 配置…',
      // ---- 0.5.9：回收站与本机屏蔽（面板入口，之前只有工具 / HTTP 端点）----
      recoverySection: '回收站与本机屏蔽',
      recoveryHint: '被对端删除、或本机屏蔽的会话不会硬删，而是移到本机回收目录。这里可以恢复、撤销屏蔽，或按会话 ID 新增屏蔽（只影响本机，远端原文保留）。',
      recoverySafetyBlocked: '⚠ 屏蔽清单异常，本轮已禁用全部删除传播与回收动作：',
      recoveryTrustFailed: '⚠ 有设备清单未能读取，本轮未做删除传播：',
      recoveryRemovedTitle: '回收记录',
      recoveryEmpty: '回收目录是空的。',
      recoveryRestorable: '可恢复',
      recoveryLegacy: '仅手工（无元数据）',
      recoveryRestore: '恢复',
      recoveryConfirmRestore: '再点一次确认',
      recoveryRestoring: '恢复中…',
      recoveryRestored: '已恢复并重新登记侧栏',
      recoveryIgnoredTitle: '本机屏蔽',
      recoveryIgnoredEmpty: '没有屏蔽的会话。',
      recoveryUnignore: '撤销',
      recoveryIgnorePlaceholder: '会话 ID（session-…）',
      recoveryIgnoreAdd: '添加屏蔽',
      recoveryIgnoreAdded: '已加入本机屏蔽',
      recoveryIgnoreRemoved: '已撤销屏蔽',
      recoveryLoadFail: '回收信息读取失败',
      recoveryNeedId: '请先填写会话 ID',
    };

    const en = {
      panelLabel: 'NAS Sync',
      title: 'NAS Sync',
      subtitle: 'Encrypt and sync DSH sessions, attachments and skills to your NAS (WebDAV).',
      needRefresh: 'Page token missing — please leave and reopen this panel.',
      refresh: 'Refresh',
      statusSection: 'Status',
      pluginSection: 'Plugin sync',
      sideMobile: 'Phone',
      sideDesktop: 'Desktop',
      sideCli: 'CLI',
      sideUnknown: 'Device',
      pluginNoReport: 'No plugin list reported yet',
      pluginPeerHint: 'appears after the other device syncs once',
      pluginOnlyHere: 'missing on the other device',
      pluginDiffTitle: 'On the other device, not here',
      pluginHint: 'Plugin packages never sync (only install manifests do). This lists plugins other devices have but this machine does not. Platform-specific plugins (e.g. mobile layout adapters) are skipped as each device keeps its own best set. Installing calls the dsh CLI; restart DSH afterwards.',
      pluginCheck: 'Check',
      pluginChecking: 'Checking…',
      pluginTarget: 'Install into',
      pluginInstall: 'Install',
      pluginInstallAll: 'Install all',
      pluginAllDone: 'All plugins are present.',
      pluginInstalled: 'Installed here',
      pluginNotRun: 'Not audited yet — press “⟳ Check” above.',
      pluginMissing: 'not installed',
      pluginOutdated: 'version differs',
      pluginNoCli: 'No usable dsh CLI on this device — copy the command and run it manually.',
      pluginNotInstallable: 'points at the other device\'s local path; cannot install here',
      pluginPlatform: 'platform-specific — skipped on this device (each keeps its own best set)',
      pluginInstalling: 'Installing…',
      pluginDone: 'Installed. Restart DSH to load it.',
      pluginFailed: 'Install failed: ',
      lastSync: 'Last successful sync',
      never: 'never',
      pending: 'Pending',
      conflicts: 'Conflicts',
      running: 'Running',
      idle: 'Idle',
      phase: 'Phase',
      phaseInterrupted: 'interrupted at',
      catchUp: 'Back in foreground, catching up',
      syncNow: 'Sync now',
      syncing: 'Syncing…',
      stop: 'Stop',
      testConnection: 'Test connection',
      testing: 'Testing…',
      connected: 'NAS connected',
      notConnected: 'Connection failed',
      syncDone: 'Done: pushed {pushed}, pulled {pulled}, {errors} errors, {conflicts} conflicts',
      syncAbortedNote: '(processed in the chosen direction; the rest kept)',
      configSection: 'Configuration',
      cfgType: 'Transport',
      cfgUrl: 'WebDAV URL',
      cfgRemote: 'Remote folder (share)',
      cfgRemoteHint: 'Share name or subpath on your NAS, e.g. /dsh-sync. Leave empty for root. The share must allow WebDAV access on fnOS/Synology.',
      cfgUser: 'Username',
      cfgPass: 'Password',
      cfgMount: 'Mount root (mount mode)',
      cfgPassphrase: 'Encryption passphrase',
      cfgPassphraseHint: 'Encrypts uploads; must match on both devices. Leave empty to keep current.',
      cfgAuto: 'Auto sync',
      cfgAutoOff: 'Off',
      cfgPush: 'Push only',
      cfgPull: 'Pull only',
      cfgMirror: 'Mirror',
      cfgInterval: 'Sync interval',
      cfgIntervalCustom: 'Custom',
      cfgIntervalUnit: 'min',
      cfgScope: 'What to sync',
      cfgScopeHint: 'Only checked categories sync. Unchecking never deletes what is already on the NAS — it just stops being synced.',
      scopeSensitive: 'sensitive',
      scopeAll: 'All',
      scopeNone: 'None',
      skillsLoading: 'Loading skills…',
      skillsEmpty: 'No skills found (skills/ is empty)',
      skillsHint: 'Only checked skills sync. Checking all is treated as "all skills", so skills added later are included automatically.',
      skillsAllOn: '(currently = all skills)',
      scopeSessions: 'Sessions',
      scopeWorkspaces: 'Workspace list',
      scopeWorkspaceFiles: 'Workspace files',
      scopeCustomFolders: 'Custom folders',
      cfgCustomFolders: 'Custom folders',
      cfgCustomFoldersHint: 'Name links the two devices (same name = same data); the path is local only and may differ per machine.',
      cfgCustomFolderAdd: '+ Add folder',
      cfgCustomFolderName: 'Name',
      cfgCustomFolderPath: 'Local path',
      cfgCustomFolderRemove: 'Remove',
      scopeSessionTitles: 'Session titles',
      scopeAttachments: 'Attachments',
      scopeSkills: 'Skills',
      scopeProfiles: 'Profiles & plugins',
      scopeSettings: 'Main settings (settings.yaml)',
      scopePluginPatch: 'Plugin patch (cordis.patch.yml)',
      save: 'Save config',
      saving: 'Saving…',
      saved: 'Saved',
      pendingSection: 'Pending files',
      empty: 'Nothing to sync.',
      conflictSection: 'Conflicts',
      noConflicts: 'No conflicts.',
      keepLocal: 'Keep local',
      keepRemote: 'Keep remote',
      skipItem: 'Skip',
      tryMerge: 'Try merge',
      merged: 'Merged and written locally',
      resolved: 'Resolved',
      error: 'Error',
      unknownError: 'Unknown error',
      passwordKeep: 'Set (leave empty to keep)',
      loadFail: 'Load failed',
      loadingCfg: 'Loading NAS config…',
      // 0.5.9: recycling bin / local ignore list (previously tools + HTTP only)
      recoverySection: 'Removed & local ignores',
      recoveryHint: 'Sessions deleted on the other device or ignored here are moved to a local recycling folder instead of being deleted. Restore them, revoke an ignore, or add one by session ID (this device only; the remote copy is kept).',
      recoverySafetyBlocked: '⚠ Ignore list is unhealthy — all deletion propagation and recycling were disabled this round:',
      recoveryTrustFailed: '⚠ Some device manifests could not be read — deletion propagation was skipped this round:',
      recoveryRemovedTitle: 'Recycled sessions',
      recoveryEmpty: 'The recycling folder is empty.',
      recoveryRestorable: 'restorable',
      recoveryLegacy: 'manual only (no metadata)',
      recoveryRestore: 'Restore',
      recoveryConfirmRestore: 'Click again to confirm',
      recoveryRestoring: 'Restoring…',
      recoveryRestored: 'Restored and re-registered in the sidebar',
      recoveryIgnoredTitle: 'Locally ignored',
      recoveryIgnoredEmpty: 'No ignored sessions.',
      recoveryUnignore: 'Revoke',
      recoveryIgnorePlaceholder: 'Session ID (session-…)',
      recoveryIgnoreAdd: 'Add ignore',
      recoveryIgnoreAdded: 'Added to local ignores',
      recoveryIgnoreRemoved: 'Ignore revoked',
      recoveryLoadFail: 'Could not load recycling info',
      recoveryNeedId: 'Enter a session ID first',
    };

    function makeT(dict) {
      return (key, params) => {
        let s = dict[key] || key;
        if (params) {
          for (const [k, v] of Object.entries(params)) s = s.split('{' + k + '}').join(String(v));
        }
        return s;
      };
    }

    function api() {
      const token = window.__DSH_NAS_SYNC_TOKEN__;
      const prefix = window.__DSH_NAS_SYNC_PREFIX__ || '/nas-sync-api';
      return {
        ready: typeof token === 'string' && token.length > 0,
        async call(path, options) {
          const o = options || {};
          const res = await fetch(prefix + path, {
            method: o.method || 'GET',
            headers: { 'x-nas-sync-token': token, 'content-type': 'application/json' },
            body: o.body !== undefined ? JSON.stringify(o.body) : undefined,
          });
          const data = await res.json().catch(() => null);
          if (!data) throw new Error('HTTP ' + res.status);
          if (!data.ok) throw new Error((data.error && data.error.message) || 'error');
          return data.value;
        },
      };
    }


    // ---- 宿主左侧栏工作区来源标记（模块级自启动，不依赖面板是否打开）----
    let sidebarDevices = null;
    let sidebarStarted = false;

    /** 幂等注入：目标 emoji 没变就不碰 DOM，避免 observer 自我触发死循环。 */
    function applySidebarBadges() {
      if (typeof document === 'undefined' || !document.querySelectorAll) return;
      const rows = document.querySelectorAll('[data-row-key^="workspace:"]');
      for (const row of rows) {
        let titleEl = null;
        let label = '';
        // 只看「直接文本节点」，不能用 children.length === 0 过滤：badge 注入后
        // title 就有了子 span，用那个条件会导致后续 apply 认不出 title，标记再也无法更新/清除。
        for (const sp of row.querySelectorAll('span')) {
          if (sp.hasAttribute('data-nas-sync-device')) continue;
          let txt = '';
          for (const n of sp.childNodes) if (n.nodeType === 3) txt += n.textContent;
          if (!txt.trim()) continue;
          titleEl = sp;
          label = txt.trim();
          break;
        }
        if (!titleEl) continue;

        const key = (row.getAttribute('data-row-key') || '').slice('workspace:'.length);
        const want = sidebarBadgeFor(sidebarDevices, key, label);
        const existing = titleEl.querySelector(':scope > [data-nas-sync-device]');
        if (existing && existing.getAttribute('data-badge') === (want ? want.emojis : '')) continue;
        if (existing) existing.remove();
        if (!want) continue;

        const el = document.createElement('span');
        el.setAttribute('data-nas-sync-device', '1');
        el.setAttribute('data-badge', want.emojis);
        el.textContent = ' ' + want.emojis;
        el.title = want.devices.join('\n');
        el.style.cssText = 'font-size:12px;line-height:1;opacity:.85;margin-left:2px;flex:0 0 auto;cursor:default;';
        titleEl.appendChild(el);
      }
    }

    /** 面板刷新拿到 devices 后调用，立即重标；全局定时拉取也会调它。 */
    function setSidebarDevices(d) {
      sidebarDevices = d;
      applySidebarBadges();
    }

    function startSidebarBadges() {
      if (sidebarStarted) return;
      sidebarStarted = true;
      // 面板没打开时也要能更新：每 20 秒拉一次 /devices（token 由 boot payload 注入）
      const pull = () => {
        try {
          const a = api();
          if (!a.ready) return;
          a.call('/devices').then(setSidebarDevices).catch(() => {});
        } catch (_) { /* token 还没就绪，下一轮再试 */ }
      };
      pull();
      setInterval(pull, 20000);

      let debounce = null;
      try {
        const obs = new MutationObserver(() => {
          if (debounce) clearTimeout(debounce);
          debounce = setTimeout(applySidebarBadges, 180);
        });
        obs.observe(document.body, { childList: true, subtree: true });
      } catch (_) { /* 宿主不支持 observer 时仍保留定时刷新 */ }
      applySidebarBadges();
    }

    // DOM 可能还没就绪（模块加载早于 body 创建），延到 DOMContentLoaded
    if (typeof document !== 'undefined' && document.body) startSidebarBadges();
    else if (typeof window !== 'undefined') window.addEventListener('DOMContentLoaded', startSidebarBadges, { once: true });

    const S = {
      // 宽度对齐原生设置页：宿主自己的页面样式是
      //   .page { flex-direction: column; align-items: center }
      //   .page > * { width: 100%; max-width: 960px }
      // 即「内容列最宽 960px + 居中」。我们先前既没有上限（铺满到侧栏边）、
      // 又去量父宽强撑，方向正好反了 —— 0.3.7/0.3.8 两版都栽在这。
      // 左右留白：手机上整页贴边会显得"糊在墙上"，卡片也没有呼吸感。
      // 用 padding 而不是 max-width —— 窄屏时 max-width 不生效（视口本来就窄），
      // 只有 padding 能保证两端都有空隙；box-sizing 已包含在 root 上。
      root: { padding: '0 14px', width: '100%', maxWidth: '960px', margin: '0 auto', alignSelf: 'center', flex: '0 1 auto', minWidth: 0, color: 'var(--dsw-alias-label-primary)', fontSize: '13px', lineHeight: 1.5, display: 'flex', flexDirection: 'column', gap: '16px', height: '100%', overflow: 'auto', boxSizing: 'border-box' },
      card: { background: 'var(--dsw-alias-bg-layer-1)', border: '1px solid var(--dsw-alias-border-l1)', borderRadius: '12px', padding: '16px', display: 'flex', flexDirection: 'column', gap: '10px' },
      title: { margin: 0, fontSize: '16px', fontWeight: 600 },
      subtitle: { margin: 0, color: 'var(--dsw-alias-label-secondary)', fontSize: '12px' },
      section: { margin: 0, fontSize: '13px', fontWeight: 600 },
      row: { display: 'flex', alignItems: 'center', gap: '8px', flexWrap: 'wrap' },
      stat: { display: 'flex', gap: '18px', flexWrap: 'wrap' },
      statItem: { display: 'flex', flexDirection: 'column', gap: '2px' },
      statNum: { fontSize: '18px', fontWeight: 600 },
      statLabel: { fontSize: '11px', color: 'var(--dsw-alias-label-secondary)' },
      btn: { height: '28px', padding: '0 12px', borderRadius: '6px', border: '1px solid var(--dsw-alias-border-l2)', background: 'var(--dsw-alias-bg-layer-2)', color: 'var(--dsw-alias-label-primary)', cursor: 'pointer', fontSize: '12px' },
      btnPrimary: { background: 'var(--dsw-alias-brand-primary)', color: 'var(--dsw-alias-bg-base)', borderColor: 'var(--dsw-alias-brand-primary)' },
      btnDanger: { color: 'var(--dsw-alias-state-error-primary)', borderColor: 'var(--dsw-alias-state-error-primary)', background: 'transparent' },
      btnDisabled: { opacity: 0.5, cursor: 'default' },
      input: { height: '28px', padding: '0 8px', borderRadius: '6px', border: '1px solid var(--dsw-alias-border-l2)', background: 'var(--dsw-alias-bg-layer-2)', color: 'var(--dsw-alias-label-primary)', fontSize: '12px', boxSizing: 'border-box', width: '100%' },
      field: { display: 'flex', flexDirection: 'column', gap: '4px', minWidth: '220px', flex: '1 1 220px' },
      label: { fontSize: '11px', color: 'var(--dsw-alias-label-secondary)' },
      inputNarrow: { height: '28px', padding: '0 8px', borderRadius: '6px', border: '1px solid var(--dsw-alias-border-l2)', background: 'var(--dsw-alias-bg-layer-2)', color: 'var(--dsw-alias-label-primary)', fontSize: '12px', boxSizing: 'border-box', width: '96px' },
      intervalCustomRow: { display: 'flex', alignItems: 'center', gap: '8px', marginTop: '6px' },
      cfBlock: { marginTop: '8px' },
      cfRow: { display: 'flex', alignItems: 'center', gap: '6px', marginBottom: '6px' },
      cfName: { height: '28px', padding: '0 8px', borderRadius: '6px', border: '1px solid var(--dsw-alias-border-l2)', background: 'var(--dsw-alias-bg-layer-2)', color: 'var(--dsw-alias-label-primary)', fontSize: '12px', boxSizing: 'border-box', width: '132px', flex: '0 0 auto' },
      cfPath: { height: '28px', padding: '0 8px', borderRadius: '6px', border: '1px solid var(--dsw-alias-border-l2)', background: 'var(--dsw-alias-bg-layer-2)', color: 'var(--dsw-alias-label-primary)', fontSize: '12px', boxSizing: 'border-box', flex: '1 1 auto', minWidth: '0' },
      deviceBadges: { display: 'inline-flex', alignItems: 'center', gap: '2px', marginLeft: '4px', fontSize: '12px', lineHeight: '1', flex: '0 0 auto', cursor: 'default' },
      deviceBadge: { opacity: '0.85' },
      cfDel: { flex: '0 0 auto', width: '28px', height: '28px', borderRadius: '6px', border: '1px solid var(--dsw-alias-border-l2)', background: 'none', color: 'var(--dsw-alias-label-secondary)', cursor: 'pointer', font: 'inherit', fontSize: '14px', lineHeight: '1' },
      hint: { fontSize: '11px', color: 'var(--dsw-alias-label-secondary)' },
      fileRow: { display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: '8px', padding: '6px 4px', borderBottom: '1px solid var(--dsw-alias-border-l1)', minHeight: '32px' },
      fileName: { fontFamily: 'monospace', fontSize: '12px', wordBreak: 'break-all', flex: 1 },
      badge: { fontSize: '11px', padding: '2px 8px', borderRadius: '999px', border: '1px solid var(--dsw-alias-border-l2)', color: 'var(--dsw-alias-label-secondary)' },
      // 「插件同步管理」的两侧并排视图。手机上卡片内容宽约 330px，两列各 ~155px，
      // 够放「包名 版本」这种 20 字符出头的标签（放不下会自动换行到下一行内）。
      pluginGrid: { display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '10px' },
      pluginCol: { display: 'flex', flexDirection: 'column', gap: '6px', minWidth: 0, padding: '10px', borderRadius: '10px', border: '1px solid var(--dsw-alias-border-l1)', background: 'var(--dsw-alias-bg-layer-2)' },
      pluginColHead: { display: 'flex', alignItems: 'baseline', justifyContent: 'space-between', gap: '6px', minWidth: 0 },
      pluginColTitle: { fontSize: '12px', fontWeight: 600 },
      badgeWrap: { display: 'flex', flexWrap: 'wrap', gap: '4px' },
      // 包名普遍很长（dsh-guizang-design），一列只有 ~155px，挤成一行必然折行成两截。
      // 改成两行胶囊：包名一行、版本一行，长名整体不换行。
      badgeStack: { display: 'inline-flex', flexDirection: 'column', alignItems: 'flex-start', gap: '1px', maxWidth: '100%', whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' },
      badgeVer: { fontSize: '10px', opacity: 0.75, maxWidth: '100%', overflow: 'hidden', textOverflow: 'ellipsis' },
      // 只在单侧出现的插件：描边换成品牌色。比另起一行说明省地方，也不至于像报错。
      badgeAlone: { borderColor: 'var(--dsw-alias-brand-primary)', color: 'var(--dsw-alias-brand-primary)' },
      scopeList: { display: 'flex', flexDirection: 'column', gap: '6px', marginTop: '2px' },
      scopeRow: { display: 'flex', alignItems: 'center', gap: '8px', fontSize: '12px', cursor: 'pointer' },
      scopeHead: { display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: '8px' },
      scopeBtns: { display: 'flex', gap: '6px' },
      scopeBtn: { fontSize: '11px', lineHeight: '16px', padding: '1px 8px', borderRadius: '999px', border: '1px solid var(--dsw-alias-border-l2)', background: 'none', color: 'var(--dsw-alias-label-secondary)', cursor: 'pointer', font: 'inherit' },
      skillGroup: { display: 'flex', flexDirection: 'column', gap: '4px' },
      skillList: { display: 'flex', flexDirection: 'column', gap: '4px', marginLeft: '22px', paddingLeft: '10px', borderLeft: '2px solid var(--dsw-alias-border-l1)' },
      skillRow: { display: 'flex', alignItems: 'center', gap: '6px', fontSize: '11px', cursor: 'pointer' },
      skillMeta: { color: 'var(--dsw-alias-label-secondary)', fontSize: '11px' },
      // r174：自动同步的四选一平铺按钮（替代原生 select）
      segRow: { display: 'flex', gap: '6px', flexWrap: 'wrap' },
      seg: { height: '28px', padding: '0 12px', borderRadius: '6px', border: '1px solid var(--dsw-alias-border-l2)', background: 'var(--dsw-alias-bg-layer-2)', color: 'var(--dsw-alias-label-primary)', cursor: 'pointer', fontSize: '12px', font: 'inherit', whiteSpace: 'nowrap' },
      segOn: { background: 'var(--dsw-alias-brand-primary)', color: 'var(--dsw-alias-bg-base)', borderColor: 'var(--dsw-alias-brand-primary)' },
      // r174：「包含敏感文件」——框在左、字在右，水平一行。
      // 之前用的是 scopeRow 之外的自造 label，checkbox 被 flex 拉伸跑到右边甚至居中。
      checkRow: { display: 'flex', flexDirection: 'row', alignItems: 'center', gap: '8px', fontSize: '12px', cursor: 'pointer', minWidth: '220px', flex: '1 1 220px' },
      // 宿主（DSH Web）里有针对裸 input 的全局样式，复选框会被撑成整行宽、看着像「跑到右边」。
      // 这里把尺寸和外观钉死，不再继承宿主。
      checkBox: { margin: 0, padding: 0, flex: '0 0 auto', width: '16px', height: '16px', minWidth: '16px', accentColor: 'var(--dsw-alias-brand-primary)', cursor: 'pointer' },
      badgeOk: { color: 'var(--dsw-alias-state-success-primary)', borderColor: 'var(--dsw-alias-state-success-primary)' },
      badgeErr: { color: 'var(--dsw-alias-state-error-primary)', borderColor: 'var(--dsw-alias-state-error-primary)' },
      msgErr: { color: 'var(--dsw-alias-state-error-primary)', fontSize: '12px' },
      msgOk: { color: 'var(--dsw-alias-state-success-primary)', fontSize: '12px' },
      // ---- 0.5.9：回收站 / 本机屏蔽 ----
      recoveryRow: { display: 'flex', alignItems: 'flex-start', justifyContent: 'space-between', gap: '8px', flexWrap: 'wrap', padding: '7px 4px', borderBottom: '1px solid var(--dsw-alias-border-l1)' },
      recoveryInfo: { display: 'flex', flexDirection: 'column', gap: '2px', minWidth: 0, flex: '1 1 180px' },
      recoveryMeta: { fontSize: '11px', color: 'var(--dsw-alias-label-secondary)', wordBreak: 'break-all' },
      recoveryInput: { height: '28px', padding: '0 8px', borderRadius: '6px', border: '1px solid var(--dsw-alias-border-l2)', background: 'var(--dsw-alias-bg-layer-2)', color: 'var(--dsw-alias-label-primary)', fontSize: '12px', boxSizing: 'border-box', flex: '1 1 auto', minWidth: 0 },
      recoveryAddRow: { display: 'flex', gap: '6px', alignItems: 'center', marginTop: '8px', flexWrap: 'wrap' },
      recoveryWarn: { fontSize: '12px', color: 'var(--dsw-alias-state-error-primary)', background: 'var(--dsw-alias-bg-layer-2)', border: '1px solid var(--dsw-alias-state-error-primary)', borderRadius: '8px', padding: '8px 10px', whiteSpace: 'pre-wrap', wordBreak: 'break-all' },
      recoverySubHead: { display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: '8px', marginTop: '4px' },
    };

    function formatBytes(n) {
      if (!Number.isFinite(n)) return '';
      if (n < 1024) return n + ' B';
      if (n < 1024 * 1024) return (n / 1024).toFixed(1) + ' KB';
      return (n / 1024 / 1024).toFixed(1) + ' MB';
    }

    function fmtTime(ms) {
      if (!ms) return null;
      try { return new Date(ms).toLocaleString(); } catch { return String(ms); }
    }

    function PanelView({ t }) {
      const client = useRef(null);
      if (!client.current) client.current = api();
      const c = client.current;

      const rootRef = useRef(null);
      // 宽度完全交给 root 的样式（max-width:960 + margin:auto，与原生页同一套）。
      // 不再在挂载时去改祖先：0.3.6~0.3.8 反复证明「猜哪一层在限宽」只会把布局搞坏。

      const [snap, setSnap] = useState(null);
      const [cfg, setCfg] = useState(null);
      const [pending, setPending] = useState(null);
      const [conflicts, setConflicts] = useState(null);
      const [busy, setBusy] = useState('');
      const [msg, setMsg] = useState(null); // {kind:'ok'|'err', text}
      // ---- 插件对账（0.4.0）----
      // audit: /plugins 的结果 { gaps, canInstall, remoteProfiles, localProfiles }
      // auditBusy: '' | 'check' | 'install'
      // installJob: 安装任务快照（轮询 /plugins/install?id=）
      const [audit, setAudit] = useState(null);
      const [auditBusy, setAuditBusy] = useState('');
      const [installJob, setInstallJob] = useState(null);
      const [passDraft, setPassDraft] = useState('');
      const [passSet, setPassSet] = useState(false);
      // 设备上真实存在的技能名单（含体积），用于渲染「技能」下的子勾选框。
      // null = 还没拉到；[] = 拉到了但一个技能都没有。两者要区分开，否则会把
      // 「正在加载」误显示成「你没有装任何技能」。
      const [skills, setSkills] = useState(null);
      // 「自定义间隔」是纯视图状态：当前值不在预设档位里时，自动展开输入框。
      // 用独立 state 而不是把 intervalMin 置成 0/-1 之类的哨兵值 —— 哨兵会被
      // 一并 POST 到后端，触发 schema 的 min(1) 校验失败。
      const [customInterval, setCustomInterval] = useState(false);
      // 来源设备：{ self, byPrefix: { <前缀>: [{device, files}] } }
      // 数据来自远端清单记录的「最后上传者」，所以首次同步前是 null —— 此时不显示任何标记，
      // 而不是显示一个"未知设备"的占位，免得用户以为同步坏了。
      const [devices, setDevices] = useState(null);
      // ---- 回收站 / 本机屏蔽（0.5.9）----
      // null = 还没读到（或旧版 host 没这两个端点），要和「读到了但为空」分开。
      const [removed, setRemoved] = useState(null);
      const [ignored, setIgnored] = useState(null);
      // /status 里的 safety：解释「本轮为什么没做删除传播」。0.5.8 没有这个字段。
      const [safety, setSafety] = useState(null);
      const [ignoreDraft, setIgnoreDraft] = useState('');
      // 恢复会移动文件并撤销屏蔽，第一次点击只把按钮切成「再点一次确认」。
      const [restoreArm, setRestoreArm] = useState(null);

      const refreshAll = useCallback(async () => {
        if (!c.ready) return;
        try {
          const st = await c.call('/status');
          setSnap(st.snapshot);
          setCfg(st.config);
          setPassSet(Boolean(st.config.passphraseSet));
          // 0.5.9：把「本轮为什么没做删除传播」带到面板上，旧版 host 没有这个字段。
          setSafety(st.safety || null);
          const pd = await c.call('/pending?page=1&pageSize=100');
          setPending(pd);
          const cf = await c.call('/conflicts');
          setConflicts(cf);
          // 来源设备统计依赖远端清单，网络慢/接口不存在时不该拖住整个面板渲染。
          // 单独 try：旧版 host 没有 /devices 端点，这里不能让整个 refreshAll 失败。
          try {
            const dv = await c.call('/devices');
            setDevices(dv);
            setSidebarDevices(dv);
          } catch (e) {
            setDevices(null);
            setSidebarDevices(null);
          }
          // 技能名单单独拉：它只依赖磁盘上的 skills/ 目录，跟同步状态无关。
          // 失败不阻断整个刷新 —— 面板其它部分仍然可用，只是技能子列表空着。
          try {
            const sk = await c.call('/skills');
            setSkills(Array.isArray(sk && sk.skills) ? sk.skills : []);
          } catch (_) {
            setSkills([]);
          }
          // 回收站 / 本机屏蔽（0.5.9）。旧版 host 没有这两个端点，
          // 所以单独 try 且失败只留 null —— 面板其它部分必须照常可用。
          try {
            const [rm, ig] = await Promise.all([c.call('/removed'), c.call('/ignored')]);
            setRemoved(rm && Array.isArray(rm.items) ? rm : null);
            setIgnored(ig && Array.isArray(ig.ids) ? ig : null);
          } catch (_) {
            setRemoved(null);
            setIgnored(null);
          }
        } catch (e) {
          setMsg({ kind: 'err', text: t('loadFail') + '：' + e.message });
        }
      }, [c, t]);

      useEffect(() => { refreshAll(); }, [refreshAll]);

      // ---- 插件对账 ----
      // 对账口径：拿「远端各 profile 的依赖」去比「本机**选定** profile 的实际安装」。
      // 不按 profile 同名比：手机跑 web、电脑跑 desktop，同名比永远比不出东西。
      const runAudit = useCallback(async (target) => {
        if (!c.ready) return;
        setAuditBusy('check');
        try {
          const q = target ? '?target=' + encodeURIComponent(target) : '';
          const v = await c.call('/plugins' + q);
          setAudit(v);
        } catch (e) {
          setMsg({ kind: 'err', text: e.message });
        } finally {
          setAuditBusy('');
        }
      }, [c, t]);

      // 进面板自动对一次账。为什么：卡片初始态只有一个「—」，用户不知道要先点按钮，
      // 于是「插件对账里什么都看不到」。自动跑一次，至少把已装清单先摆出来。
      const auditAuto = useRef(false);
      useEffect(() => {
        if (!c.ready || auditAuto.current) return;
        auditAuto.current = true;
        runAudit(null);
      }, [c, runAudit]);

      /** 安装一组插件；轮询到任务结束后再刷新对账结果。 */
      const installItems = useCallback(async (profile, items) => {
        if (!items.length) return;
        setAuditBusy('install');
        setMsg(null);
        try {
          let job = await c.call('/plugins/install', { method: 'POST', body: { profile, items } });
          setInstallJob(job);
          // pnpm 装包可能要几十秒，轮询而不阻塞界面；上限 5 分钟，超时也要把控制权还给用户。
          const deadline = Date.now() + 5 * 60 * 1000;
          while (job && job.state === 'running' && Date.now() < deadline) {
            await new Promise((r) => setTimeout(r, 1500));
            job = await c.call('/plugins/install?id=' + encodeURIComponent(job.id));
            setInstallJob(job);
          }
          if (job && job.state === 'done') {
            const extra = job.registered && job.registered.length ? '（已登记：' + job.registered.join(', ') + '）' : '';
            setMsg({ kind: 'ok', text: t('pluginDone') + extra });
            await runAudit(audit && audit.target);
          } else {
            const tail = (job && job.log ? String(job.log) : '').slice(-400);
            setMsg({ kind: 'err', text: t('pluginFailed') + (tail || 'timeout') });
          }
        } catch (e) {
          setMsg({ kind: 'err', text: e.message });
        } finally {
          setAuditBusy('');
        }
      }, [c, t, runAudit, audit]);

      /** 全部安装：装进对账选定的那个 profile。 */
      const installAll = useCallback(async () => {
        const gaps = (audit && audit.gaps) || [];
        const items = gaps.filter((g) => g.installable && g.target).map((g) => ({ name: g.name, target: g.target }));
        if (!items.length || !audit) return;
        await installItems(audit.target, items);
      }, [audit, installItems]);

      useEffect(() => {
        const timer = setInterval(() => {
          if (!c.ready) return;
          c.call('/status').then((st) => { setSnap(st.snapshot); setCfg((prev) => prev || st.config); }).catch(() => {});
        }, 5000);
        return () => clearInterval(timer);
      }, [c]);

      /**
       * 回前台补跑（0.5.1）。
       *
       * 手机上把 App 切到后台，Android 会冻结进程/WebView 定时器，宿主那套
       * setInterval 自动同步就整段被跳过 —— 用户实测「切后台快一小时，一条都没同步」，
       * 而且回到前台后还要再等满一个周期才会动，体感就是「它就是不同步」。
       *
       * 后台跑不了是系统限制，插件这边改不动；但回到前台的这一瞬间可以补：
       * 只要距上次成功同步已经超过设定的间隔、当前也没有任务在跑，就立刻补一轮。
       * 挂载即判定一次 —— 面板本来就是「回到前台」之后才打开的。
       */
      useEffect(() => {
        let lastAttempt = 0;
        const catchUp = () => {
          if (typeof document !== 'undefined' && document.visibilityState === 'hidden') return;
          if (!c.ready) return;
          const now = Date.now();
          if (now - lastAttempt < 60_000) return; // 一分钟内不重复补跑
          c.call('/status').then((st) => {
            const s = (st && st.snapshot) || {};
            const auto = (st && st.config && st.config.autoSync) || null;
            if (!auto || !auto.enabled) return;
            if (s.running) return;
            const intervalMs = (Number(auto.intervalMin) || 30) * 60_000;
            if (now - (s.lastSyncTime || 0) < intervalMs) return;
            lastAttempt = now;
            setMsg({ kind: 'ok', text: t('catchUp') + '…' });
            return c.call('/sync', { method: 'POST', body: { mode: auto.mode || 'mirror' } })
              .then((r) => { setMsg({ kind: 'ok', text: t('syncDone', r) }); refreshAll(); });
          }).catch(() => {});
        };
        document.addEventListener('visibilitychange', catchUp);
        window.addEventListener('focus', catchUp);
        catchUp();
        return () => {
          document.removeEventListener('visibilitychange', catchUp);
          window.removeEventListener('focus', catchUp);
        };
      }, [c, refreshAll]);

      if (!c.ready) {
        return h('div', { ref: rootRef, style: S.root }, h('div', { style: S.card, role: 'alert' }, t('needRefresh')));
      }

      // r172：配置还没到手时必须**停下**。下面整片配置区（以及紧随其后的
      // `cfg.syncScope` / `cfg.skillFilter`）直接读 cfg，而 cfg 的初值是 null ——
      // 少这道门，首帧渲染就抛 TypeError: Cannot read properties of null，
      // React 18 会把整棵树卸载 ⇒ 用户看到的就是「点 NAS 同步后整页白屏」。
      // 为什么 r170 才出现：这一版新加的「按分类勾选」在 render 函数体里
      // 先算了 syncScope（不是 JSX 里的条件分支），所以第一次渲染必崩。
      if (!cfg) {
        return h('div', { ref: rootRef, style: S.root }, h('div', { style: S.card }, t('loadingCfg')));
      }

      const running = Boolean(snap && snap.running);
      const phaseText = snap ? snap.phase : '…';

      async function doSync(mode) {
        setBusy('sync');
        setMsg(null);
        try {
          const r = await c.call('/sync', { method: 'POST', body: { mode } });
          setMsg({ kind: 'ok', text: t('syncDone', r) });
        } catch (e) {
          setMsg({ kind: 'err', text: e.message });
        }
        setBusy('');
        refreshAll();
      }
      async function doStop() {
        try { await c.call('/stop', { method: 'POST' }); } catch (e) { setMsg({ kind: 'err', text: e.message }); }
        refreshAll();
      }
      async function doTest() {
        setBusy('test');
        setMsg(null);
        try {
          const r = await c.call('/test', { method: 'POST' });
          if (r.connected) {
            setMsg({ kind: 'ok', text: t('connected') });
          } else {
            // 失败：显示完整失败 log（目标地址、底层错误）
            setMsg({ kind: 'err', text: t('notConnected') + '\n' + (r.message || t('unknownError')) });
          }
        } catch (e) {
          setMsg({ kind: 'err', text: t('notConnected') + '\n' + e.message });
        }
        setBusy('');
      }
      async function saveConfig() {
        setBusy('save');
        setMsg(null);
        try {
          const webdav = { url: cfg.webdav.url, remotePath: cfg.webdav.remotePath || '', username: cfg.webdav.username };
          if (cfg.webdav._pass) webdav.password = cfg.webdav._pass;
          const patch = {
            type: cfg.type,
            webdav,
            mountRoot: cfg.mountRoot,
            autoSync: cfg.autoSync,
            // includeSensitive 不再由面板提交：那个复选框已按用户要求移除。
            // 不提交 = 保持后端原值（默认 false），工具侧仍可显式设置，
            // 避免「界面上看不见但偷偷被翻成 true」这种情况。
            // 分类勾选：整对象提交（服务端做二级合并 + 归一化，缺键会回落默认值）
            syncScope: cfg.syncScope || SCOPE_DEFAULT,
            // 技能白名单：[] = 全部技能（含以后新增的）
            skillFilter: Array.isArray(cfg.skillFilter) ? cfg.skillFilter : [],
            // 自定义文件夹映射表：整表替换（服务端归一化，非法 name/path 会被丢掉）
            customFolders: Array.isArray(cfg.customFolders) ? cfg.customFolders : [],
          };
          if (passDraft) patch.passphrase = passDraft;
          await c.call('/config', { method: 'POST', body: { config: patch } });
          setMsg({ kind: 'ok', text: t('saved') });
          setPassDraft('');
        } catch (e) {
          setMsg({ kind: 'err', text: e.message });
        }
        setBusy('');
        refreshAll();
      }
      async function resolve(file, resolution) {
        setBusy('resolve:' + file);
        setMsg(null);
        try {
          await c.call('/resolve', { method: 'POST', body: { file, resolution } });
          setMsg({ kind: 'ok', text: t('resolved') + '：' + file });
        } catch (e) {
          setMsg({ kind: 'err', text: e.message });
        }
        setBusy('');
        refreshAll();
      }
      async function tryMerge(file) {
        setBusy('merge:' + file);
        setMsg(null);
        try {
          const r = await c.call('/merge', { method: 'POST', body: { file, apply: true } });
          if (r.success && !r.hasConflicts) setMsg({ kind: 'ok', text: t('merged') + '：' + file });
          else if (r.success) setMsg({ kind: 'err', text: '合并完成但仍有冲突块，请手动处理：' + file });
          else setMsg({ kind: 'err', text: r.reason || t('unknownError') });
        } catch (e) {
          setMsg({ kind: 'err', text: e.message });
        }
        setBusy('');
        refreshAll();
      }

      // ---- 回收站 / 本机屏蔽（0.5.9）----
      // 端点都在 bridge.ts；失败抛到 msg，成功后 refreshAll 重新拉两份列表。
      async function restoreRemoved(key) {
        // 两步确认：恢复会搬动会话目录、重新登记侧栏并撤销本机屏蔽，误点代价不低。
        if (restoreArm !== key) { setRestoreArm(key); return; }
        setRestoreArm(null);
        setBusy('restore:' + key);
        setMsg(null);
        try {
          const r = await c.call('/removed/restore', { method: 'POST', body: { key } });
          setMsg({ kind: 'ok', text: t('recoveryRestored') + '：' + (r.rel || key) });
        } catch (e) {
          setMsg({ kind: 'err', text: e.message });
        }
        setBusy('');
        refreshAll();
      }
      async function addIgnore() {
        const sid = ignoreDraft.trim();
        if (!sid) { setMsg({ kind: 'err', text: t('recoveryNeedId') }); return; }
        setBusy('ignore:add');
        setMsg(null);
        try {
          await c.call('/ignored', { method: 'POST', body: { action: 'add', sessionId: sid } });
          setIgnoreDraft('');
          setMsg({ kind: 'ok', text: t('recoveryIgnoreAdded') + '：' + sid });
        } catch (e) {
          setMsg({ kind: 'err', text: e.message });
        }
        setBusy('');
        refreshAll();
      }
      async function removeIgnore(sid) {
        setBusy('ignore:' + sid);
        setMsg(null);
        try {
          await c.call('/ignored', { method: 'POST', body: { action: 'remove', sessionId: sid } });
          setMsg({ kind: 'ok', text: t('recoveryIgnoreRemoved') + '：' + sid });
        } catch (e) {
          setMsg({ kind: 'err', text: e.message });
        }
        setBusy('');
        refreshAll();
      }

      const setCfgField = (path, value) => {
        setCfg((prev) => {
          const next = JSON.parse(JSON.stringify(prev));
          const keys = path.split('.');
          let o = next;
          for (let i = 0; i < keys.length - 1; i++) {
            // 中间层可能不存在（旧 store 里没有 syncScope），必须补一个空对象再往下走
            if (o[keys[i]] == null || typeof o[keys[i]] !== 'object') o[keys[i]] = {};
            o = o[keys[i]];
          }
          o[keys[keys.length - 1]] = value;
          return next;
        });
      };

      /**
       * 某个前缀的来源设备标记。
       *
       * **本机不显示**：自己传上去的文件标个自己的图标没有信息量，只会让界面变吵。
       * 没有统计（首次同步前）也返回 null —— 空着比显示"未知"更好。
       */
      const deviceBadges = (prefix) => {
        if (!prefix || !devices || !devices.byPrefix) return null;
        const list = devices.byPrefix[prefix] || [];
        const others = list.filter((x) => x.device && x.device !== devices.self);
        if (others.length === 0) return null;
        return h('span', {
          style: S.deviceBadges,
          title: others.map((x) => x.device + ' · ' + x.files + ' 个文件').join('\n'),
        }, others.map((x) => h('span', { key: x.device, style: S.deviceBadge }, deviceEmoji(x.device))));
      };

      // 一键全选/全不选：7 个复选框逐个点太烦。整对象替换（单段路径，setCfgField 直接赋值）。
      const setAllScope = (v) => {
        const all = {};
        for (const k of SCOPE_ORDER) all[k] = v;
        setCfgField('syncScope', all);
      };

      // ---- 自定义文件夹映射表（「自定义文件夹」分类下的第二层）----
      // 每行 = { name, path }。name 决定远端 rel 前缀，path 只在本机生效，
      // 所以两台机器同名不同路径也能对上（见 portable.ts 的 customFolderLocalOf）。
      const customFoldersNow = () => (Array.isArray(cfg.customFolders) ? cfg.customFolders : []);
      const updateCustomFolder = (index, key, value) => {
        const list = customFoldersNow().map((f, i) => (i === index ? Object.assign({}, f, { [key]: value }) : f));
        setCfgField('customFolders', list);
      };
      const addCustomFolder = () => {
        setCfgField('customFolders', customFoldersNow().concat([{ name: '', path: '' }]));
      };
      const removeCustomFolder = (index) => {
        setCfgField('customFolders', customFoldersNow().filter((_, i) => i !== index));
      };

      // ---- 技能白名单（「技能」分类下的第二层）----
      // 空白名单 = 全部技能，所以「当前选中的集合」要把这种情况展开成全部名字。
      const scopeNow = Object.assign({}, SCOPE_DEFAULT, cfg.syncScope || {});
      const skillNames = Array.isArray(skills) ? skills.map((s) => s.name) : [];
      const skillSel = (cfg.skillFilter && cfg.skillFilter.length) ? cfg.skillFilter : skillNames;
      const toggleSkill = (name, on) => {
        const next = on
          ? skillNames.filter((n) => n === name || skillSel.includes(n))
          : skillSel.filter((n) => n !== name);
        // 全勾时收敛成 []（= 全部技能），这样以后新装的技能会自动被同步，
        // 而不是把「当时那 7 个」冻成一份死名单。
        setCfgField('skillFilter', next.length === skillNames.length ? [] : next);
      };

      // ---- 自动同步模式（r174：原生 select → 平铺四按钮）----
      // 为什么不再用 <select>：这个面板是挂在宿主 App 的浮层里渲染的，WebView 的原生
      // 下拉弹层会被浮层/上层窗口吞掉，用户点开只看得见当前项、选不了别的（实拍截图）。
      // 平铺按钮把四个选项一次性摊在页面上，不依赖任何系统弹层。
      // 注意：UI 上是「四选一」，但存储仍是 enabled + mode 两个字段，
      // 所以「关闭」要落成 enabled:false，而不是往下塞一个 mode:'off'（服务端不认识）。
      const autoMode = (cfg.autoSync && cfg.autoSync.enabled)
        ? (cfg.autoSync.mode === 'pull' ? 'pull' : cfg.autoSync.mode === 'mirror' ? 'mirror' : 'push')
        : 'off';
      const setAutoMode = (v) => {
        const intervalMin = (cfg.autoSync && cfg.autoSync.intervalMin) || 30;
        setCfgField('autoSync', v === 'off'
          ? { enabled: false, mode: 'push', intervalMin }
          : { enabled: true, mode: v, intervalMin });
      };

      return h('div', { ref: rootRef, style: S.root },
        // 头部
        h('div', null,
          h('h2', { style: S.title }, t('title')),
          h('p', { style: S.subtitle }, t('subtitle')),
        ),

        // 状态卡
        h('div', { style: S.card },
          h('div', { style: S.row, style: Object.assign({}, S.row, { justifyContent: 'space-between' }) },
            h('h3', { style: S.section }, t('statusSection')),
            h('button', { style: S.btn, onClick: () => refreshAll() }, '↻ ' + t('refresh')),
          ),
          snap
            ? h('div', { style: S.stat },
                h('div', { style: S.statItem },
                  h('span', { style: S.statNum }, String(snap.pendingCount)),
                  h('span', { style: S.statLabel }, t('pending')),
                ),
                h('div', { style: S.statItem },
                  h('span', { style: Object.assign({}, S.statNum, snap.conflictCount ? { color: 'var(--dsw-alias-state-error-primary)' } : {}) }, String(snap.conflictCount)),
                  h('span', { style: S.statLabel }, t('conflicts')),
                ),
                h('div', { style: S.statItem },
                  h('span', { style: S.statNum }, running ? '…' : (snap.lastSyncTime ? '✓' : '—')),
                  h('span', { style: S.statLabel }, t('lastSync') + '：' + (snap.lastSyncTime ? fmtTime(snap.lastSyncTime) : t('never'))),
                ),
                h('div', { style: S.statItem },
                  h('span', { style: S.statNum }, running ? t('running') : t('idle')),
                  // 只有真在跑时才把 done/total 当实时进度。非运行时那个数字是上一条
                  // 命留下的（App 切后台被系统冻结，finally 没机会执行），直接显示会
                  // 变成一个永远不动的 94/145，比不显示更容易误导。
                  h('span', { style: S.statLabel }, t('phase') + '：' + phaseText + (() => {
                    const p = snap.progress;
                    if (!p || !p.total) return '';
                    if (running) return ' (' + p.done + '/' + p.total + ')';
                    return p.done > 0 ? ' (' + t('phaseInterrupted') + ' ' + p.done + '/' + p.total + ')' : '';
                  })()),
                ),
              )
            : h('div', null, '…'),
          h('div', { style: S.row },
            h('button', { style: Object.assign({}, S.btn, S.btnPrimary, busy === 'sync' ? S.btnDisabled : {}), disabled: busy === 'sync', onClick: () => doSync('mirror') }, busy === 'sync' ? t('syncing') : t('syncNow')),
            running ? h('button', { style: Object.assign({}, S.btn, S.btnDanger), onClick: () => doStop() }, t('stop')) : null,
            h('button', { style: Object.assign({}, S.btn, busy === 'test' ? S.btnDisabled : {}), disabled: busy === 'test', onClick: () => doTest() }, busy === 'test' ? t('testing') : t('testConnection')),
          ),
          // 0.6.0 / a11y：成功用 status（不打断读屏），失败才用 alert；
          // 两者都进 live region，异步同步结果才能被读屏软件播报出来。
          msg ? h('div', {
            role: msg.kind === 'err' ? 'alert' : 'status',
            'aria-live': msg.kind === 'err' ? 'assertive' : 'polite',
            'data-nas-msg': msg.kind,
            style: Object.assign({ whiteSpace: 'pre-wrap', wordBreak: 'break-all' }, msg.kind === 'ok' ? S.msgOk : S.msgErr),
          }, msg.text) : null,
        ),

        // 插件对账卡（0.4.0）：插件的包体不走同步，只同步装配清单，
        // 所以「别的设备装了、这台机器没有」是常态，这里给出差集与一键安装。
        h('div', { style: S.card },
          h('div', { style: S.row },
            h('h3', { style: S.section }, t('pluginSection')),
            h('button', {
              style: Object.assign({}, S.btn, auditBusy === 'check' ? S.btnDisabled : {}),
              disabled: Boolean(auditBusy),
              onClick: () => runAudit(audit && audit.target),
            }, auditBusy === 'check' ? t('pluginChecking') : '⟳ ' + t('pluginCheck')),
          ),
          // r214：两侧并排看（手机 / 电脑）。本机侧读磁盘真值，对端侧读它同步时
          // 上报的插件快照（`dsh/devices/<device>.json` 的 plugins 字段）。
          // 只报差集时用户没有参照物 —— 两端的插件集本来就不该一样。
          (() => {
            if (!audit) return null;
            const labelOf = (kind) => t(
              kind === 'mobile' ? 'sideMobile'
                : kind === 'desktop' ? 'sideDesktop'
                  : kind === 'cli' ? 'sideCli' : 'sideUnknown',
            );
            const sides = Array.isArray(audit.sides) ? audit.sides : [];
            const self = sides.find((s) => s.source === 'self')
              || {
                id: audit.selfDevice || '',
                kind: 'mobile',
                profiles: audit.target ? [audit.target] : [],
                plugins: audit.installedPlugins || [],
              };
            const peers = sides.filter((s) => s !== self);
            // 对端的版本号可能是一整条 git URL（`git+https://github.com/...`）或
            // `link:/data/user/0/...`，直接铺出来会把一列撑变形。这里只保留「来源类型」：
            // 真正的完整 spec 在下方差集列表里，那里是整行、能换行。
            const fmtVer = (v) => {
              const s = String(v || '');
              if (s.startsWith('git+') || s.startsWith('github:') || s.startsWith('git@')) return 'git';
              if (s.startsWith('link:')) return 'link';
              if (s.startsWith('file:')) return 'file';
              if (s.startsWith('workspace:')) return 'workspace';
              return s;
            };
            // 同一列里同一个包名只显示一次：对端可能同时跑 desktop 与 acp 两个档
            const dedupe = (list) => {
              const m = new Map();
              for (const p of list || []) if (!m.has(p.name)) m.set(p.name, p);
              return Array.from(m.values());
            };
            const selfList = dedupe(self.plugins);
            const peerList = [];
            for (const p of peers) for (const it of p.plugins || []) peerList.push(it);
            const peerUnique = dedupe(peerList);
            const selfNames = new Set(selfList.map((p) => p.name));
            const peerNames = new Set(peerUnique.map((p) => p.name));
            const col = (title, sub, list, otherNames, key) => h('div', { style: S.pluginCol },
              h('div', { style: S.pluginColHead },
                h('span', { style: S.pluginColTitle }, title),
                h('span', { style: S.hint }, sub),
              ),
              list.length
                ? h('div', { style: S.badgeWrap }, list.map((p) => h('span', {
                    key: key + '|' + p.name,
                    style: Object.assign({}, S.badge, S.badgeStack, otherNames.has(p.name) ? null : S.badgeAlone),
                    title: otherNames.has(p.name) ? p.name : p.name + ' — ' + t('pluginOnlyHere'),
                  },
                  h('span', null, p.name),
                  p.version ? h('span', { style: S.badgeVer }, fmtVer(p.version)) : null)))
                : h('span', { style: S.hint }, t('pluginNoReport')),
            );
            const subOf = (side, n) => {
              const pf = (side && side.profiles) || [];
              return (pf.length ? pf.join('/') + ' · ' : '') + n;
            };
            const peerTitle = peers.length
              ? peers.map((s) => labelOf(s.kind)).filter((v, i, a) => a.indexOf(v) === i).join(' / ')
              : t('sideDesktop');
            return h('div', { style: S.pluginGrid },
              col(labelOf(self.kind), subOf(self, selfList.length), selfList, peerNames, 'self'),
              // 对端不止一台时（电脑 + 命令行）合成一列：手机上放不下三列，
              // 而且用户关心的是「另一端装了什么」，不是每台的细节。
              peers.length
                ? col(peerTitle, subOf(peers.length === 1 ? peers[0] : null, peerUnique.length), peerUnique, selfNames, 'peer')
                : h('div', { style: S.pluginCol },
                    h('div', { style: S.pluginColHead }, h('span', { style: S.pluginColTitle }, t('sideDesktop'))),
                    h('span', { style: S.hint }, t('pluginPeerHint'))),
            );
          })(),
          audit
            ? (audit.gaps && audit.gaps.length
              ? h('div', { style: S.scopeList },
                  h('span', { style: S.hint }, t('pluginDiffTitle') + '（' + audit.gaps.length + '）'),
                  audit.gaps.map((g) => h('div', { key: g.profile + '|' + g.name, style: S.fileRow },
                    h('div', { style: { display: 'flex', flexDirection: 'column', gap: '2px', minWidth: 0 } },
                      h('span', { style: S.fileName }, g.name + '@' + g.spec),
                      h('span', { style: S.hint },
                        '[' + g.profile + '] ' + (g.status === 'missing' ? t('pluginMissing') : t('pluginOutdated')) +
                        (g.installable ? '' : ' · ' + (g.reason === 'platform' ? t('pluginPlatform') : t('pluginNotInstallable')))),
                    ),
                    g.installable
                      ? h('button', {
                          style: Object.assign({}, S.btn, auditBusy === 'install' ? S.btnDisabled : {}),
                          disabled: Boolean(auditBusy),
                          onClick: () => installItems(audit.target, [{ name: g.name, target: g.target }]),
                        }, t('pluginInstall'))
                      : h('span', { style: S.badge }, '—'),
                  )),
                  h('div', { style: Object.assign({}, S.row, { marginTop: '8px' }) },
                    h('button', {
                      style: Object.assign({}, S.btn, S.btnPrimary, auditBusy ? S.btnDisabled : {}),
                      disabled: Boolean(auditBusy),
                      onClick: () => installAll(),
                    }, auditBusy === 'install' ? t('pluginInstalling') : t('pluginInstallAll') + ' → ' + audit.target),
                    audit.canInstall ? null : h('span', { style: S.hint }, t('pluginNoCli')),
                  ),
                )
              : h('p', { style: S.subtitle }, t('pluginAllDone')))
            : h('p', { style: S.hint }, t('pluginNotRun')),
          installJob && installJob.state === 'running'
            ? h('pre', {
                style: Object.assign({}, S.hint, { whiteSpace: 'pre-wrap', maxHeight: '160px', overflow: 'auto' }),
              }, String(installJob.log || '').slice(-800))
            : null,
        ),

        // 配置卡
        cfg ? h('div', { style: S.card },
          h('h3', { style: S.section }, t('configSection')),
          h('div', { style: S.row },
            h('div', { style: S.field },
              h('label', { style: S.label }, t('cfgType')),
              // r174：与「自动同步」同因 —— 原生 select 的弹层在这块浮层里打不开，
              // 只剩两个选项，索性一起摊成按钮，面板里不再有任何 <select>。
              h('div', { style: S.segRow },
                [['webdav', 'WebDAV'], ['mount', 'mount']].map(([v, label]) => h('button', {
                  key: 'type-' + v,
                  type: 'button',
                  'data-sync-type': v,
                  style: Object.assign({}, S.seg, cfg.type === v ? S.segOn : null),
                  onClick: () => setCfgField('type', v),
                }, label)),
              ),
            ),
            // 0.6.0 / a11y：这里的 <label> 只是视觉文字，没有 htmlFor/id 关联。
            // 给每个输入框补 aria-label，读屏才能报出「这是什么框」。
            cfg.type === 'webdav'
              ? h('div', { style: S.field },
                  h('label', { style: S.label }, t('cfgUrl')),
                  h('input', { style: S.input, 'aria-label': t('cfgUrl'), value: cfg.webdav.url || '', placeholder: 'https://nas.local:5006/dav', onChange: (e) => setCfgField('webdav.url', e.target.value) }),
                )
              : h('div', { style: S.field },
                  h('label', { style: S.label }, t('cfgMount')),
                  h('input', { style: S.input, 'aria-label': t('cfgMount'), value: cfg.mountRoot || '', onChange: (e) => setCfgField('mountRoot', e.target.value) }),
                ),
          ),
          cfg.type === 'webdav'
            ? h('div', { style: S.row },
                h('div', { style: S.field },
                  h('label', { style: S.label }, t('cfgRemote')),
                  h('input', { style: S.input, 'aria-label': t('cfgRemote'), value: cfg.webdav.remotePath || '', placeholder: '/dsh-sync', onChange: (e) => setCfgField('webdav.remotePath', e.target.value) }),
                  h('span', { style: S.hint }, t('cfgRemoteHint')),
                ),
              )
            : null,
          cfg.type === 'webdav'
            ? h('div', { style: S.row },
                h('div', { style: S.field },
                  h('label', { style: S.label }, t('cfgUser')),
                  h('input', { style: S.input, 'aria-label': t('cfgUser'), value: cfg.webdav.username || '', onChange: (e) => setCfgField('webdav.username', e.target.value) }),
                ),
                h('div', { style: S.field },
                  h('label', { style: S.label }, t('cfgPass') + (cfg.webdav.passwordSet ? '（' + t('passwordKeep') + '）' : '')),
                  // ⚠️ 必须是非受控输入（defaultValue，不能写 value: ''）：
                  //   钉死 value 的受控框每敲一个字符就被 React 重置成空，
                  //   结果只剩**最后一个字符**进了 _pass —— 用户存进去的密码是残缺的，
                  //   表现就是「密码明明没输错却 401」。
                  h('input', { style: S.input, type: 'password', 'aria-label': t('cfgPass'), defaultValue: '', onChange: (e) => setCfgField('webdav._pass', e.target.value) }),
                ),
              )
            : null,
          h('div', { style: S.row },
            h('div', { style: S.field },
              h('label', { style: S.label }, t('cfgPassphrase') + (passSet ? '（' + t('passwordKeep') + '）' : '')),
              h('input', { style: S.input, type: 'password', 'aria-label': t('cfgPassphrase'), value: passDraft, onChange: (e) => setPassDraft(e.target.value), placeholder: passSet ? '' : 'required' }),
              h('span', { style: S.hint }, t('cfgPassphraseHint')),
            ),
          ),

          // ---- 同步内容：分类勾选（r3）----
          // 服务端 `syncScope` 决定扫描哪些子树；这里只负责勾选与提交。
          // 未勾选的分类既不上传也不下载，且**不会**删除 NAS 上已有的旧文件。
          h('div', { style: S.field, flex: '1 1 100%' },
            h('div', { style: S.scopeHead },
              h('label', { style: S.label }, t('cfgScope')),
              h('div', { style: S.scopeBtns },
                h('button', { type: 'button', style: S.scopeBtn, onClick: () => setAllScope(true) }, t('scopeAll')),
                h('button', { type: 'button', style: S.scopeBtn, onClick: () => setAllScope(false) }, t('scopeNone')),
              ),
            ),
            h('div', { style: S.scopeList },
              SCOPE_ORDER.map((k) => {
                // 用 `=== true` 而不是 `!== false`：后者会把「缺失的键」当成勾选。
                // 一旦服务端返回的 syncScope 不完整（旧配置 / 手工改过 config.json），
                // profiles / settings / pluginPatch 这三个敏感分类就会被默认打开 ——
                // 这与 DEFAULT 里「敏感默认关」的设计正好相反。缺键一律回落常量。
                const scope = Object.assign({}, SCOPE_DEFAULT, cfg.syncScope || {});
                const on = scope[k] === true;
                const keyLabel = 'scope' + k.charAt(0).toUpperCase() + k.slice(1);
                const row = h('label', { key: k, style: S.scopeRow },
                  h('input', { type: 'checkbox', 'data-scope': k, checked: on, onChange: (e) => setCfgField('syncScope.' + k, e.target.checked) }),
                  h('span', null, t(keyLabel)),
                  SCOPE_SENSITIVE[k] ? h('span', { style: S.badge }, t('scopeSensitive')) : null,
                  // 该分类的数据主要来自哪台设备（本机不显示）
                  deviceBadges(CATEGORY_PREFIX[k]),
                );
                // 「技能」是唯一有第二层的分类：勾上它才展开技能名单，
                // 没勾时藏起来，免得用户以为关掉的技能仍在同步。
                if (k !== 'skills') return row;
                let sub = null;
                if (on) {
                  if (skills === null) {
                    sub = h('div', { style: S.skillMeta }, t('skillsLoading'));
                  } else if (skillNames.length === 0) {
                    sub = h('div', { style: S.skillMeta }, t('skillsEmpty'));
                  } else {
                    sub = h('div', { style: S.skillList },
                      skillNames.map((name) => {
                        const info = skills.find((s) => s.name === name) || {};
                        return h('label', { key: name, style: S.skillRow },
                          h('input', {
                            type: 'checkbox',
                            'data-skill': name,
                            checked: skillSel.includes(name),
                            onChange: (e) => toggleSkill(name, e.target.checked),
                          }),
                          h('span', null, name),
                          h('span', { style: S.skillMeta },
                            (info.files || 0) + ' 个 · ' + formatBytes(info.bytes || 0)),
                        );
                      }),
                      h('span', { style: S.skillMeta },
                        ((cfg.skillFilter && cfg.skillFilter.length) ? '' : t('skillsAllOn')) + ' ' + t('skillsHint')),
                    );
                  }
                }
                return h('div', { key: k + '-wrap', style: S.skillGroup }, row, sub);
              }),
            ),
            h('span', { style: S.hint }, t('cfgScopeHint')),
            // 自定义文件夹编辑器：只在「自定义文件夹」这一类被勾上时展开，
            // 跟「技能」下的明细一致 —— 关掉的分类不该还占着屏幕。
            (Object.assign({}, SCOPE_DEFAULT, cfg.syncScope || {}).customFolders === true)
              ? h('div', { style: S.cfBlock },
                  h('label', { style: S.label }, t('cfgCustomFolders')),
                  h('div', { style: S.hint, marginBottom: '6px' }, t('cfgCustomFoldersHint')),
                  customFoldersNow().map((f, i) =>
                    h('div', { key: 'cf-' + i, style: S.cfRow },
                      h('input', {
                        style: S.cfName,
                        type: 'text',
                        'aria-label': t('cfgCustomFolderName'),
                        placeholder: t('cfgCustomFolderName'),
                        value: f.name || '',
                        onChange: (e) => updateCustomFolder(i, 'name', e.target.value),
                      }),
                      h('input', {
                        style: S.cfPath,
                        type: 'text',
                        'aria-label': t('cfgCustomFolderPath'),
                        placeholder: t('cfgCustomFolderPath'),
                        value: f.path || '',
                        onChange: (e) => updateCustomFolder(i, 'path', e.target.value),
                      }),
                      // 每个自定义文件夹单独标来源：同名文件夹在两端各自落地，
                      // 但数据是谁传上去的仍要看清单里的 device
                      deviceBadges('custom-folders/' + (f.name || '')),
                      h('button', {
                        type: 'button',
                        style: S.cfDel,
                        title: t('cfgCustomFolderRemove'),
                        onClick: () => removeCustomFolder(i),
                      }, '×'),
                    ),
                  ),
                  h('button', { type: 'button', style: S.scopeBtn, onClick: addCustomFolder }, t('cfgCustomFolderAdd')),
                )
              : null,
          ),
          h('div', { style: S.row },
            h('div', { style: S.field },
              h('label', { style: S.label }, t('cfgAuto')),
              // r174：原来是原生 <select>，用户在手机上点开只能看到「只上传」、换不了别的
              // （WebView 里 select 的原生弹层在本面板里被盖住/吞掉）。
              // 改成平铺按钮：一次看到四个选项、点谁是谁，不依赖任何系统弹层。
              h('div', { style: S.segRow },
                [['off', t('cfgAutoOff')], ['push', t('cfgPush')], ['pull', t('cfgPull')], ['mirror', t('cfgMirror')]]
                  .map(([v, label]) => h('button', {
                    key: 'auto-' + v,
                    type: 'button',
                    'data-auto-mode': v,
                    style: Object.assign({}, S.seg, autoMode === v ? S.segOn : null),
                    onClick: () => setAutoMode(v),
                  }, label)),
              ),
            ),
            cfg.autoSync && cfg.autoSync.enabled
              ? (() => {
                  // 间隔也做成平铺按钮：跟上面的「关闭/只上传/只下载/双向」保持同一种交互，
                  // 手机上不用弹系统数字键盘就能改。最后一档「自定义」才展开输入框。
                  const INTERVAL_PRESETS = [5, 15, 30, 60, 120];
                  const cur = Number(cfg.autoSync.intervalMin) || 30;
                  const showCustom = customInterval || INTERVAL_PRESETS.indexOf(cur) === -1;
                  return h('div', { style: S.field },
                    h('label', { style: S.label }, t('cfgInterval')),
                    h('div', { style: S.segRow },
                      INTERVAL_PRESETS.map((v) => h('button', {
                        key: 'iv-' + v,
                        type: 'button',
                        'data-interval': v,
                        style: Object.assign({}, S.seg, (!showCustom && cur === v) ? S.segOn : null),
                        onClick: () => { setCustomInterval(false); setCfgField('autoSync.intervalMin', v); },
                      }, v + ' ' + t('cfgIntervalUnit'))),
                      h('button', {
                        key: 'iv-custom',
                        type: 'button',
                        'data-interval': 'custom',
                        style: Object.assign({}, S.seg, showCustom ? S.segOn : null),
                        onClick: () => setCustomInterval(true),
                      }, t('cfgIntervalCustom')),
                    ),
                    showCustom
                      ? h('div', { style: S.intervalCustomRow },
                          h('input', {
                            style: S.inputNarrow,
                            type: 'number', min: 1, max: 1440,
                            value: cur,
                            onChange: (e) => setCfgField('autoSync.intervalMin', Number(e.target.value) || 30),
                          }),
                          h('span', { style: S.hint }, t('cfgIntervalUnit') + '（1–1440）'),
                        )
                      : null,
                  );
                })()
              : null,
          ),
          h('div', { style: S.row },
            h('button', { style: Object.assign({}, S.btn, S.btnPrimary, busy === 'save' ? S.btnDisabled : {}), disabled: busy === 'save', onClick: () => saveConfig() }, busy === 'save' ? t('saving') : t('save')),
          ),
        ) : null,

        // 待同步
        pending ? h('div', { style: S.card },
          h('h3', { style: S.section }, t('pendingSection') + '（' + pending.total + '）'),
          pending.items.length === 0
            ? h('div', { style: S.hint }, t('empty'))
            : h('div', null, pending.items.map((f) =>
                h('div', { key: f.rel, style: S.fileRow },
                  h('span', { style: S.fileName }, f.rel),
                  h('span', { style: S.badge }, (f.action === 'upload' ? '↑ ' : '↓ ') + formatBytes(f.size)),
                ),
              )),
        ) : null,

        // 冲突
        conflicts ? h('div', { style: S.card },
          h('h3', { style: S.section }, t('conflictSection') + '（' + conflicts.items.length + '）'),
          conflicts.items.length === 0
            ? h('div', { style: S.hint }, t('noConflicts'))
            : h('div', null, conflicts.items.map((cf) =>
                h('div', { key: cf.rel, style: Object.assign({}, S.fileRow, { flexWrap: 'wrap' }) },
                  h('span', { style: S.fileName }, cf.rel),
                  h('span', { style: S.row },
                    cf.mergeable ? h('button', { style: S.btn, disabled: busy === 'merge:' + cf.rel, onClick: () => tryMerge(cf.rel) }, t('tryMerge')) : null,
                    h('button', { style: S.btn, disabled: busy === 'resolve:' + cf.rel, onClick: () => resolve(cf.rel, 'local') }, t('keepLocal')),
                    h('button', { style: S.btn, disabled: busy === 'resolve:' + cf.rel, onClick: () => resolve(cf.rel, 'remote') }, t('keepRemote')),
                    h('button', { style: S.btn, disabled: busy === 'resolve:' + cf.rel, onClick: () => resolve(cf.rel, 'skip') }, t('skipItem')),
                  ),
                ),
              )),
        ) : null,

        // 回收站与本机屏蔽（0.5.9）：把 /removed、/ignored、/removed/restore
        // 做成真正点得到的入口，并把 /status 的 safety 解释出来。
        (() => {
          // 0.5.8 及更早没有这三个能力，整张卡片直接隐藏，
          // 免得旧 host 上出现一个永远读不出内容的空壳。
          if (!removed && !ignored && !safety) return null;
          const rmItems = (removed && removed.items) || [];
          const igIds = (ignored && ignored.ids) || [];
          const trustFailed = (safety && Array.isArray(safety.manifestTrustFailed))
            ? safety.manifestTrustFailed : [];
          return h('div', { style: S.card },
            h('div', { style: S.recoverySubHead },
              h('h3', { style: S.section }, t('recoverySection')),
              h('button', { style: S.btn, onClick: () => refreshAll() }, '↻ ' + t('refresh')),
            ),
            h('span', { style: S.hint }, t('recoveryHint')),
            // 0.5.9 fail-closed：屏蔽清单坏了 / 清单没读全时，明确告诉用户
            // 本轮为什么不搬文件，而不是让「同步成功」看起来一切正常。
            (safety && safety.destructiveBlocked && safety.ignoredFileError)
              ? h('div', { style: S.recoveryWarn, role: 'alert' }, t('recoverySafetyBlocked') + '\n' + safety.ignoredFileError)
              : null,
            trustFailed.length
              ? h('div', { style: S.recoveryWarn, role: 'alert' }, t('recoveryTrustFailed') + '\n' + trustFailed.join('\n'))
              : null,
            (ignored && ignored.error)
              ? h('div', { style: S.recoveryWarn, role: 'alert' }, t('recoverySafetyBlocked') + '\n' + ignored.error)
              : null,

            h('div', { style: S.recoverySubHead },
              h('span', { style: S.section }, t('recoveryRemovedTitle') + '（' + (removed ? rmItems.length : '…') + '）'),
            ),
            removed
              ? (rmItems.length === 0
                  ? h('span', { style: S.hint }, t('recoveryEmpty'))
                  : h('div', null, rmItems.map((e) =>
                      h('div', { key: e.id, style: S.recoveryRow },
                        h('div', { style: S.recoveryInfo },
                          h('span', { style: S.fileName }, e.rel || e.id),
                          h('span', { style: S.recoveryMeta },
                            [e.sessionId ? 'ID ' + e.sessionId : null,
                              e.retiredAt ? String(e.retiredAt) : null,
                              e.reason || null].filter(Boolean).join('　')),
                        ),
                        h('div', { style: S.row },
                          h('span', {
                            style: Object.assign({}, S.badge, e.restorable ? S.badgeOk : null),
                          }, e.restorable ? t('recoveryRestorable') : t('recoveryLegacy')),
                          e.restorable
                            ? h('button', {
                                // 0.6.0 / a11y：两步确认的状态必须让读屏也知道，
                                // 不能只靠按钮文字变化（视觉用户看得见，读屏用户未必）。
                                type: 'button',
                                'aria-label': (restoreArm === e.id ? t('recoveryConfirmRestore') : t('recoveryRestore'))
                                  + '：' + (e.rel || e.id),
                                'aria-pressed': restoreArm === e.id,
                                style: Object.assign({}, S.btn, busy === 'restore:' + e.id ? S.btnDisabled : {}),
                                disabled: Boolean(busy),
                                onClick: () => restoreRemoved(e.id),
                              }, restoreArm === e.id ? t('recoveryConfirmRestore')
                                : busy === 'restore:' + e.id ? t('recoveryRestoring') : t('recoveryRestore'))
                            : null,
                        ),
                      ),
                    )))
              : h('span', { style: S.hint }, t('recoveryLoadFail')),

            h('div', { style: S.recoverySubHead },
              h('span', { style: S.section }, t('recoveryIgnoredTitle') + '（' + (ignored ? igIds.length : '…') + '）'),
            ),
            ignored
              ? (igIds.length === 0
                  ? h('span', { style: S.hint }, t('recoveryIgnoredEmpty'))
                  : h('div', null, igIds.map((sid) =>
                      h('div', { key: sid, style: S.recoveryRow },
                        h('span', { style: S.fileName }, sid),
                        h('button', {
                          style: Object.assign({}, S.btn, busy === 'ignore:' + sid ? S.btnDisabled : {}),
                          disabled: Boolean(busy),
                          onClick: () => removeIgnore(sid),
                        }, t('recoveryUnignore')),
                      ),
                    )))
              : h('span', { style: S.hint }, t('recoveryLoadFail')),
            h('div', { style: S.recoveryAddRow },
              h('input', {
                style: S.recoveryInput,
                type: 'text',
                'aria-label': t('recoveryIgnorePlaceholder'),
                'data-recovery-sid': '1',
                placeholder: t('recoveryIgnorePlaceholder'),
                value: ignoreDraft,
                onChange: (e) => setIgnoreDraft(e.target.value),
              }),
              h('button', {
                style: Object.assign({}, S.btn, busy === 'ignore:add' ? S.btnDisabled : {}),
                disabled: busy === 'ignore:add',
                onClick: () => addIgnore(),
              }, t('recoveryIgnoreAdd')),
            ),
          );
        })(),
      );
    }

    function PanelIcon(props) {
      const size = (props && props.size) || 20;
      return h('svg', {
        width: size, height: size, viewBox: '0 0 24 24',
        fill: 'none', stroke: 'currentColor', strokeWidth: 1.8,
        strokeLinecap: 'round', strokeLinejoin: 'round', 'aria-hidden': true,
      },
        h('path', { d: 'M4 8h12l-3-3' }),
        h('path', { d: 'M20 16H8l3 3' }),
        h('path', { d: 'M16 5v3', opacity: 0 }),
      );
    }

    function apply(ctx) {
      ctx.effect(() => ctx.locale.register(NS, { zh, en }), 'dsh-nas-sync: dictionaries');
      const t = ctx.locale.bind(NS);
      function NasPanel() { return h(PanelView, { t }); }
      function NasPanelIcon(props) { return h(PanelIcon, props); }
      ctx.slots.inject('main', () =>
        ctx.slots.register({ name: 'main', key: PANEL_ID, locale: NS }, NasPanel),
      );
      ctx.slots.inject('sidebar.panellist', () =>
        ctx.slots.register(
          { name: 'sidebar.panellist', id: PANEL_ID, order: 21, locale: NS, label: () => t('panelLabel') },
          NasPanelIcon,
        ),
      );
    }

    return { inject: ['slots', 'locale'], apply };
  },
});
