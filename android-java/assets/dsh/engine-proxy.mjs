// 三引擎切换层：App 内本地反代。
// WebView 始终访问 http://127.0.0.1:3082/，本进程把 HTTP / SSE / WebSocket
// 转发到 engine-upstream.json 指定的上游（电脑或服务器上的 dsh web）。
// 上游配置文件由 Java 侧写入，用 fs.watch 热读，切换引擎无需重启本进程。
//
// 只监听 127.0.0.1，不对局域网开放。上游 TLS 允许自签证书（手机侧受控设备，
// Trust on first use 由用户在 App 里手动登记端点来达成）。

import http from 'node:http';
import https from 'node:https';
import net from 'node:net';
import tls from 'node:tls';
import fs from 'node:fs';

const argv = process.argv.slice(2);
function opt(name, fallback) {
  const i = argv.indexOf('--' + name);
  return i >= 0 && i + 1 < argv.length ? argv[i + 1] : fallback;
}

const PORT = Number(opt('port', '3082'));
const CONFIG = opt('config', null);
const LOCAL_ORIGIN = `http://127.0.0.1:${PORT}`;

let upstream = new URL('http://127.0.0.1:3080');

function reload() {
  if (!CONFIG) return;
  try {
    const j = JSON.parse(fs.readFileSync(CONFIG, 'utf8'));
    if (j.upstream) {
      const next = new URL(j.upstream);
      if (next.origin !== upstream.origin) {
        console.log(`[engine-proxy] upstream -> ${next.origin}`);
      }
      upstream = next;
    }
  } catch (e) {
    console.warn('[engine-proxy] config read failed: ' + e.message);
  }
}
reload();
if (CONFIG) {
  try { fs.watch(CONFIG, { persistent: false }, () => setTimeout(reload, 80)); }
  catch (e) { console.warn('[engine-proxy] fs.watch unavailable: ' + e.message); }
}

function targetPort(u) {
  return Number(u.port) || (u.protocol === 'https:' ? 443 : 80);
}

// DSH 会校验 Host / Origin；转发时必须换成本代理所冒充的上游 origin，
// 否则上游把请求判成跨站并拒绝（403）或拒绝 WebSocket 升级。
function requestHeaders(headers, u) {
  const h = Object.assign({}, headers);
  h.host = u.host;
  if (h.origin) h.origin = u.origin;
  if (h.referer) {
    try {
      const r = new URL(h.referer);
      h.referer = u.origin + r.pathname + r.search;
    } catch (e) { /* 保留原值 */ }
  }
  return h;
}

function responseHeaders(headers, u) {
  const h = Object.assign({}, headers);
  // 上游下发的 Cookie 要能落到 127.0.0.1，去掉 Domain / Secure 约束
  if (h['set-cookie']) {
    h['set-cookie'] = h['set-cookie'].map(c =>
      c.replace(/;\s*Domain=[^;]+/ig, '').replace(/;\s*Secure\b/ig, ''));
  }
  // 相对本机 origin 重写重定向，避免浏览器被带到远程 origin（A-02 会拦）
  if (h.location && h.location.startsWith(u.origin)) {
    h.location = LOCAL_ORIGIN + h.location.slice(u.origin.length);
  }
  delete h['content-security-policy'];   // 上游 CSP 可能禁止 127.0.0.1 连接
  return h;
}

const server = http.createServer((req, res) => {
  const u = upstream;
  const mod = u.protocol === 'https:' ? https : http;
  const options = {
    protocol: u.protocol,
    hostname: u.hostname,
    port: targetPort(u),
    method: req.method,
    path: req.url,
    headers: requestHeaders(req.headers, u),
    rejectUnauthorized: false,
  };
  let proxied;
  try {
    proxied = mod.request(options, r => {
      try {
        res.writeHead(r.statusCode || 502, responseHeaders(r.headers, u));
      } catch (e) {
        if (!res.headersSent) res.writeHead(502, { 'content-type': 'text/plain' });
        res.end('engine-proxy header error: ' + e.message);
        r.destroy();
        return;
      }
      r.pipe(res);          // SSE / 流式响应按 chunk 原样透传，不缓冲
    });
  } catch (e) {
    if (!res.headersSent) res.writeHead(502, { 'content-type': 'text/plain' });
    res.end('engine-proxy: ' + e.message);
    return;
  }
  proxied.on('error', e => {
    if (!res.headersSent) res.writeHead(502, { 'content-type': 'text/plain' });
    res.end('engine-proxy upstream: ' + e.message);
  });
  req.pipe(proxied);
});

// WebSocket：手写升级请求，之后两边原始 TCP 双向对拷（帧不再解析）。
server.on('upgrade', (req, socket, head) => {
  const u = upstream;
  const tlsUpstream = u.protocol === 'https:';
  const connect = tlsUpstream ? tls : net;
  const options = tlsUpstream
    ? { host: u.hostname, port: targetPort(u), servername: u.hostname, rejectUnauthorized: false }
    : { host: u.hostname, port: targetPort(u) };

  let target;
  try { target = connect.connect(options); }
  catch (e) { socket.destroy(); return; }

  // TLSSocket 触发 secureConnect，裸 TCP Socket 才触发 connect
  target.on(tlsUpstream ? 'secureConnect' : 'connect', () => {
    const h = requestHeaders(req.headers, u);
    let raw = `${req.method} ${req.url} HTTP/1.1\r\n`;
    for (const [k, v] of Object.entries(h)) {
      if (v === undefined) continue;
      raw += `${k}: ${Array.isArray(v) ? v.join(', ') : v}\r\n`;
    }
    raw += '\r\n';
    target.write(raw);
    if (head && head.length) target.write(head);
    target.pipe(socket);
    socket.pipe(target);
  });
  target.on('error', () => socket.destroy());
  socket.on('error', () => target.destroy());
});

server.on('clientError', (e, socket) => {
  try { socket.end('HTTP/1.1 400 Bad Request\r\n\r\n'); } catch (ignored) { }
});

server.listen(PORT, '127.0.0.1', () => {
  console.log(`[engine-proxy] ${LOCAL_ORIGIN} -> ${upstream.origin}`);
});
