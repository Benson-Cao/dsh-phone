#!/usr/bin/env node
/**
 * cdp-eval.js —— 用 CDP 直接读/跑设备 WebView 里的 JS（免 logcat）。
 *
 * ## 为什么需要它
 *
 * 真机是 vivo，**第三方应用的 logcat 会被系统吞掉**（实测 persist.log.tag=VERBOSE、
 * 进程 alive，`logcat --pid` 仍 0 行）。于是 mobile.js 里任何 console.error /
 * 未捕获异常都看不见 —— r106 的 SAF shim 问题就是靠"设备日志里什么都没有"才绕了远路。
 * r107 在 MainActivity.onCreate 里开了 `setWebContentsDebuggingEnabled(true)`，
 * 配合本脚本就能把页面里的 console、异常、DOM、localStorage 全部捞出来。
 *
 * ## 为什么不用 puppeteer / ws 包
 *
 * 本机没装 npm 依赖、也不该为了看一眼 DOM 去装。Node ≥22 **自带全局 WebSocket**，
 * CDP 的传输层就是一条 WebSocket —— 手写 30 行足够，且零依赖、离线可用。
 *
 * ## 用法
 *
 *   ① 先建通道（另开一个终端，或用 remote-debug.sh 自动做）：
 *        adb forward tcp:9222 localabstract:webview_devtools_remote_<pid>
 *
 *   ② 看清楚有哪些页面（选 index）：
 *        node scripts/cdp-eval.js --list
 *
 *   ③ 求值（结果按 JSON 打印）：
 *        node scripts/cdp-eval.js "document.title"
 *        node scripts/cdp-eval.js --page 0 "location.href"
 *        node scripts/cdp-eval.js --file check.js          # 跑一个本地 js 文件
 *
 *   ④ 盯 console / 未捕获异常 N 秒（真机排查的主用途）：
 *        node scripts/cdp-eval.js --console 15
 *
 * 退出码：0 成功（含"表达式本身抛错"，那属于业务结果）；2 连不上/没有目标。
 */

'use strict';

const HTTP_BASE = process.env.CDP_BASE || 'http://127.0.0.1:9222';

function usage(code) {
  console.log(`用法:
  node cdp-eval.js --list                   列出可调试页面
  node cdp-eval.js "<js表达式>"              在首个页面求值
  node cdp-eval.js --page 1 "<js表达式>"     指定页面序号
  node cdp-eval.js --file a.js              从文件读表达式
  node cdp-eval.js --console 15             收集 console/异常 15 秒
选项:
  --base <url>   CDP HTTP 入口，默认 ${HTTP_BASE}（走 adb forward 时不用改）
  --json         只打印机器可读结果
`);
  process.exit(code);
}

function parseArgs(argv) {
  const a = { page: 0, consoleSec: 0, json: false, list: false, expr: null, file: null };
  for (let i = 0; i < argv.length; i++) {
    const v = argv[i];
    if (v === '--help' || v === '-h') usage(0);
    else if (v === '--list') a.list = true;
    else if (v === '--json') a.json = true;
    else if (v === '--page') a.page = parseInt(argv[++i], 10) || 0;
    else if (v === '--base') a.base = argv[++i];
    else if (v === '--file') a.file = argv[++i];
    else if (v === '--console') a.consoleSec = parseInt(argv[++i], 10) || 10;
    else if (v.startsWith('--')) { console.error('未知选项: ' + v); usage(1); }
    else a.expr = v;
  }
  return a;
}

async function fetchJson(url) {
  // 本地回环：绝不能走系统代理（沙箱里 http_proxy 是全局的，会把 127.0.0.1 劫持成 502）
  const res = await fetch(url, { proxy: undefined, redirect: 'follow' });
  if (!res.ok) throw new Error('HTTP ' + res.status + ' ' + url);
  return res.json();
}

async function listTargets(base) {
  const [ver, list] = await Promise.all([
    fetchJson(base + '/json/version').catch(() => ({})),
    fetchJson(base + '/json/list'),
  ]);
  return { version: ver, pages: (list || []).filter((t) => t.type === 'page' || t.type === 'webview') };
}

function pickPage(pages, idx) {
  if (!pages.length) return null;
  return pages[Math.min(idx, pages.length - 1)];
}

/** 一条最小 CDP 客户端：连上、按需发命令、按 id 收结果。 */
function connect(wsUrl) {
  return new Promise((resolve, reject) => {
    const ws = new WebSocket(wsUrl);
    let id = 0;
    const pending = new Map();
    const listeners = [];
    const openTimer = setTimeout(() => reject(new Error('连接 CDP 超时：' + wsUrl)), 8000);

    ws.addEventListener('open', () => {
      clearTimeout(openTimer);
      resolve({
        send(method, params) {
          const mid = ++id;
          return new Promise((res, rej) => {
            pending.set(mid, { res, rej });
            ws.send(JSON.stringify({ id: mid, method, params: params || {} }));
            setTimeout(() => {
              if (pending.has(mid)) {
                pending.delete(mid);
                rej(new Error('CDP 命令超时: ' + method));
              }
            }, 20000);
          });
        },
        on(fn) { listeners.push(fn); },
        close() { try { ws.close(); } catch (_) {} },
      });
    });

    ws.addEventListener('message', (ev) => {
      let msg;
      try { msg = JSON.parse(typeof ev.data === 'string' ? ev.data : String(ev.data)); }
      catch (_) { return; }
      if (msg.id && pending.has(msg.id)) {
        const p = pending.get(msg.id);
        pending.delete(msg.id);
        if (msg.error) p.rej(new Error(msg.error.message || JSON.stringify(msg.error)));
        else p.res(msg.result);
        return;
      }
      for (const fn of listeners) fn(msg);
    });

    ws.addEventListener('error', () => {
      clearTimeout(openTimer);
      reject(new Error('WebSocket 出错（端口转发没建？见文件头 ①）'));
    });
  });
}

async function evalIn(conn, expr) {
  await conn.send('Runtime.enable');
  const r = await conn.send('Runtime.evaluate', {
    expression: expr,
    returnByValue: true,
    awaitPromise: true,
    userGesture: true,
  });
  if (r.exceptionDetails) {
    const d = r.exceptionDetails;
    return { ok: false, error: (d.exception && (d.exception.description || d.exception.value)) || d.text };
  }
  return { ok: true, value: r.result && r.result.value !== undefined ? r.result.value : r.result };
}

async function watchConsole(conn, seconds, asJson) {
  await conn.send('Runtime.enable');
  await conn.send('Log.enable').catch(() => {});
  const seen = [];
  conn.on((msg) => {
    const m = msg.method;
    if (m === 'Runtime.consoleAPICalled') {
      const text = (msg.params.args || [])
        .map((a) => (a.value !== undefined ? String(a.value)
          : a.description || a.unserializableValue || ('<' + a.type + '>')))
        .join(' ');
      const line = { kind: 'console', level: msg.params.type, text };
      seen.push(line);
      if (!asJson) console.log(`[${line.level}] ${text}`);
    } else if (m === 'Runtime.exceptionThrown') {
      const d = msg.params.exceptionDetails || {};
      const text = (d.exception && (d.exception.description || d.exception.value)) || d.text || '';
      const line = { kind: 'exception', text: String(text).split('\n')[0], detail: String(text) };
      seen.push(line);
      if (!asJson) console.log('[exception] ' + line.text);
    } else if (m === 'Log.entryAdded') {
      const e = msg.params.entry || {};
      if (e.level === 'error' || e.level === 'warning') {
        const line = { kind: 'log', level: e.level, text: e.text };
        seen.push(line);
        if (!asJson) console.log(`[${e.level}] ${e.text}`);
      }
    }
  });
  // 轮询期间保持输入响应不能做，这里直接等够时长即可
  await new Promise((res) => setTimeout(res, seconds * 1000));
  if (asJson) console.log(JSON.stringify({ collected: seen }, null, 2));
  if (!seen.length && !asJson) console.log('(这段时间内没有 console / 未捕获异常)');
}

(async () => {
  const a = parseArgs(process.argv.slice(2));
  const base = a.base || HTTP_BASE;

  if (a.list || (!a.expr && !a.file && !a.consoleSec)) {
    let t;
    try { t = await listTargets(base); } catch (e) {
      console.error('✗ 读不到 CDP 目标：' + e.message);
      console.error('  ① 确认端口转发：adb forward tcp:9222 localabstract:webview_devtools_remote_<pid>');
      console.error('  ② 确认设备侧有页面：adb shell cat /proc/net/unix | grep webview_devtools_remote');
      process.exit(2);
    }
    if (t.version && t.version.Browser) console.log('Browser: ' + t.version.Browser);
    if (!t.pages.length) { console.error('✗ 一个可调试页面都没有（WebView 还没建？或 APK 没开调试开关）'); process.exit(2); }
    t.pages.forEach((p, i) => {
      console.log(`--page ${i}  [${p.type}] ${p.title || '(无标题)'}`);
      console.log(`           ${p.url || ''}`);
      console.log(`           ws: ${p.webSocketDebuggerUrl}`);
    });
    if (!a.list) process.exit(0);
    process.exit(0);
  }

  let pages;
  try { pages = (await listTargets(base)).pages; } catch (e) {
    console.error('✗ 读不到 CDP 目标：' + e.message); process.exit(2);
  }
  const page = pickPage(pages, a.page);
  if (!page) { console.error('✗ 没有可调试页面'); process.exit(2); }

  const conn = await connect(page.webSocketDebuggerUrl);
  try {
    if (a.consoleSec) {
      console.log(`== 监视 console ${a.consoleSec}s @ ${page.url} ==`);
      await watchConsole(conn, a.consoleSec, a.json);
    } else {
      let expr = a.expr;
      if (a.file) expr = require('fs').readFileSync(a.file, 'utf8');
      const r = await evalIn(conn, expr);
      if (a.json) console.log(JSON.stringify(r, null, 2));
      else if (r.ok) {
        const v = r.value;
        console.log(typeof v === 'string' ? v : JSON.stringify(v, null, 2));
      } else { console.error('页面里抛错了:\n' + r.error); process.exitCode = 1; }
    }
  } finally {
    conn.close();
  }
})().catch((e) => { console.error('✗ ' + (e && e.message)); process.exit(2); });
