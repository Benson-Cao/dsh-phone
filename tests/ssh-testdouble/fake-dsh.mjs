#!/usr/bin/env node
/**
 * fake-dsh.mjs —— 隧道**对端**的最小 HTTP 服务（测试替身，不进 APK）。
 *
 * SSH 档的转发目标是「电脑上的 127.0.0.1:3080」。真机上验隧道时不需要真的起 dsh 引擎：
 * 只要 3080 上有东西能应答，手机 WebView 里出现这页就证明
 * 「SSH 通 + direct-tcpip 通 + 端口转发通 + WebView 指向正确」全部成立。
 *
 * 页面故意把来源信息写在最显眼处，方便一眼确认不是本机引擎在应答：
 *   - 大字 FAKE 3080 · PC SIDE
 *   - 请求头 Host、来源 IP、UA 全部打出来
 *
 * 用法：node fake-dsh.mjs   （PORT 默认 3080，绑 127.0.0.1 —— 只让隧道能到，局域网直连不到）
 */
import http from 'node:http';

const PORT = Number(process.env.PORT || 3080);
const TAG = process.env.TAG || 'PC SIDE';
// TOKEN=xxx 时要求 ?token=xxx —— 用来验「隧道通了但电脑侧 dsh 拒绝访问（401）」这条分支。
// 真 dsh 的行为就是这样：实测 `GET http://127.0.0.1:61167/` 返回 401，回环也不放过。
const TOKEN = process.env.TOKEN || '';

const server = http.createServer((req, res) => {
  const ip = (req.socket.remoteAddress || '').replace(/^::ffff:/, '');
  const url = new URL(req.url, 'http://127.0.0.1');
  if (TOKEN && url.searchParams.get('token') !== TOKEN) {
    console.log(`[${new Date().toISOString().slice(11, 19)}] 401 ${req.url} ← ${ip} `
      + `（令牌 ${url.searchParams.get('token') ? '不匹配' : '缺失'}）`);
    res.writeHead(401, { 'content-type': 'text/plain; charset=utf-8', 'cache-control': 'no-store' });
    return res.end(TAG === 'PC SIDE' ? 'Unauthorized' : '401');
  }
  console.log(`[${new Date().toISOString().slice(11, 19)}] ${req.method} ${req.url} ← ${ip} `
    + `host=${req.headers.host || '-'} ua=${(req.headers['user-agent'] || '-').slice(0, 60)}`);
  res.writeHead(200, { 'content-type': 'text/html; charset=utf-8', 'cache-control': 'no-store' });
  res.end(`<!doctype html><meta name="viewport" content="width=device-width,initial-scale=1">
<style>
 body{margin:0;min-height:100vh;display:flex;flex-direction:column;align-items:center;justify-content:center;
      background:#0d1117;color:#e6edf3;font:15px/1.7 system-ui,-apple-system,"Segoe UI",sans-serif;padding:24px}
 h1{margin:0 0 4px;font-size:30px;letter-spacing:.5px}
 .tag{color:#7dd3fc;font:600 13px/1 ui-monospace,monospace;letter-spacing:2px;margin-bottom:22px}
 dl{display:grid;grid-template-columns:auto 1fr;gap:6px 14px;font:12px/1.6 ui-monospace,monospace;
    background:#161b22;border:1px solid #30363d;border-radius:10px;padding:14px 16px;max-width:92vw}
 dt{color:#8b949e} dd{margin:0;word-break:break-all}
</style>
<h1>FAKE 3080</h1>
<div class="tag">${TAG}</div>
<dl>
  <dt>path</dt><dd>${req.url}</dd>
  <dt>host</dt><dd>${req.headers.host || '-'}</dd>
  <dt>from</dt><dd>${ip}</dd>
  <dt>time</dt><dd>${new Date().toISOString()}</dd>
</dl>`);
});

server.listen(PORT, '127.0.0.1', () => {
  console.log(`fake-dsh 就绪：http://127.0.0.1:${PORT}（只绑回环 —— 必须经隧道才到得了）`
    + (TOKEN ? `；要求 ?token=${TOKEN}` : '；不校验令牌'));
});
