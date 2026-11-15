#!/usr/bin/env node
/**
 * dsh-web-daemon —— 给手机 SSH 档一个固定、免维护令牌的入口。
 *
 * 问题：dsh web 的令牌是每次启动随机生成的 randomBytes；手机档案里的
 * 「访问令牌」却是静态字段。于是每次 dsh web 重启（含后台进程被回收），
 * 手机里那串立刻失效，必须重新取令牌、再去手机上改一次。
 *
 * 做法（全在 PC 侧，不碰手机档案）：
 *   - 上游 dsh web 起在 127.0.0.1:<upstream>（默认 3082），从 stdout 捕获真令牌；
 *   - 代理监听 127.0.0.1:<listen>（默认 3080，就是手机档案的 targetPort）；
 *   - 手机首载一定带 ?token=<档案里的旧值>，代理把它换成当前真令牌；
 *     已带 dsh-auth cookie 的请求一律原样透传 —— 否则上游每次都回 303，会死循环；
 *   - **原始 Host 头必须透传**：上游按请求 Host 签 cookie 的 authority，
 *     手机那侧是 127.0.0.1:3081，签成上游端口会导致后续 401；
 *   - WebSocket / SSE 的 upgrade 一并转发；上游退出就重启并刷新令牌。
 *
 * 安全口径：代理只绑回环；能到这个端口的连接必须已经过 pc-sshd 的公钥认证
 * （它只放行回环转发目标）。真令牌只留在本进程与本机日志里，不下发给手机。
 *
 * 用法：
 *   node dsh-web-daemon.mjs --detach      # 脱离当前会话常驻
 *   node dsh-web-daemon.mjs              # 前台跑，便于看日志
 *   node dsh-web-daemon.mjs --print-config
 */
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import http from 'node:http'
import net from 'node:net'
import { spawn } from 'node:child_process'
import { fileURLToPath } from 'node:url'
import { rewritePath } from './web-proxy-rules.mjs'

const HERE = path.dirname(fileURLToPath(import.meta.url))
const SELF = fileURLToPath(import.meta.url)
const HOME = os.homedir()
const STATE_DIR = path.join(HOME, '.dsh', 'pc-sshd')
const LOG_FILE = path.join(STATE_DIR, 'dsh-web-daemon.log')
const TOKEN_FILE = path.join(STATE_DIR, 'dsh-web-token.txt')

const argv = process.argv.slice(2)
function arg(name, fallback) {
  const i = argv.indexOf(name)
  return i >= 0 && argv[i + 1] ? argv[i + 1] : fallback
}
const LISTEN = Number(arg('--listen', process.env.DSH_WEB_LISTEN || 3080))
const UPSTREAM = Number(arg('--upstream', process.env.DSH_WEB_UPSTREAM || 3082))
const EXE_CANDIDATES = [
  arg('--exe', ''),
  'D:/Program AI/Deepseek Harness/DeepSeek Harness.exe',
  'C:/Program Files/DeepSeek Harness/DeepSeek Harness.exe'
].filter(Boolean)
const CLI_CANDIDATES = [
  arg('--cli', ''),
  'D:/Program AI/Deepseek Harness/resources/app.asar/dsh/node_modules/@deepseek-ai/dsh-desktop-host/lib/cli.js',
  'C:/Program Files/DeepSeek Harness/resources/app.asar/dsh/node_modules/@deepseek-ai/dsh-desktop-host/lib/cli.js'
].filter(Boolean)

function ts() { return new Date().toISOString().replace('T', ' ').slice(0, 19) }
function log(...a) {
  const line = `[${ts()}] ${a.join(' ')}`
  console.log(line)
  try {
    fs.mkdirSync(STATE_DIR, { recursive: true })
    fs.appendFileSync(LOG_FILE, line + '\n')
  } catch { /* 日志写不了不影响服务 */ }
}

// ---- --detach：把自己再起一份，脱离调用方会话 ----
if (argv.includes('--detach')) {
  fs.mkdirSync(STATE_DIR, { recursive: true })
  const rest = argv.filter((a) => a !== '--detach')
  // 子进程的 log() 自己会 append 到 LOG_FILE；再把 stdout 指到同一文件会写两遍。
  const child = spawn(process.execPath, [SELF, ...rest], {
    cwd: HERE, detached: true, stdio: 'ignore', env: process.env
  })
  child.unref()
  console.log(`dsh-web-daemon 已后台启动 pid=${child.pid}，日志 ${LOG_FILE}`)
  process.exit(0)
}

// app.asar 是**文件**，普通 fs 没法把 app.asar/xxx 当目录路径 stat。
// Electron 以 ELECTRON_RUN_AS_NODE=1 启动时才挂载那套虚拟文件系统，所以这里
// 只要 asar 包体存在就认为路径可用，别用 existsSync 直接判内部文件。
function resolveCli(p) {
  if (fs.existsSync(p)) return p
  const i = p.indexOf('app.asar/')
  if (i >= 0 && fs.existsSync(p.slice(0, i + 'app.asar'.length))) return p
  return null
}
const EXE = EXE_CANDIDATES.find((p) => fs.existsSync(p))
const CLI = CLI_CANDIDATES.map(resolveCli).find(Boolean)
if (!EXE || !CLI) {
  console.error('找不到 DeepSeek Harness.exe 或 asar 内的 cli.js，用 --exe / --cli 指定')
  process.exit(2)
}
if (argv.includes('--print-config')) {
  console.log(JSON.stringify({ LISTEN, UPSTREAM, EXE, CLI, LOG_FILE, TOKEN_FILE }, null, 2))
  process.exit(0)
}

let token = ''
let child = null
let restarting = false

function startUpstream() {
  const childEnv = { ...process.env, ELECTRON_RUN_AS_NODE: '1' }
  delete childEnv.NODE_OPTIONS
  const args = [
    '--expose-internals', CLI, 'web', '--no-open',
    '--host', '127.0.0.1', '--port', String(UPSTREAM),
    '--trusted-host', '127.0.0.1:3081', '--trusted-host', '127.0.0.1:3080'
  ]
  log(`启动上游 dsh web：127.0.0.1:${UPSTREAM}`)
  child = spawn(EXE, args, { cwd: HERE, env: childEnv, stdio: ['ignore', 'pipe', 'pipe'] })
  let buf = ''
  child.stdout.on('data', (d) => {
    buf += d.toString()
    const lines = buf.split(/\r?\n/)
    buf = lines.pop() || ''
    for (const line of lines) {
      const m = line.match(/dsh web:\s*http:\/\/\S+\/\?token=([^\s]+)/)
      if (m && m[1] !== token) {
        token = m[1]
        fs.mkdirSync(STATE_DIR, { recursive: true })
        fs.writeFileSync(TOKEN_FILE, token)
        log(`捕获新令牌（${token.length} 字符），代理就绪`)
      }
    }
  })
  child.stderr.on('data', (d) => {
    const s = d.toString().trim()
    if (s && !s.includes('skipping profile bundle')) log(`[上游] ${s.slice(0, 200)}`)
  })
  child.on('exit', (code) => {
    log(`上游退出 code=${code}`)
    token = ''
    if (!restarting) {
      restarting = true
      setTimeout(() => { restarting = false; startUpstream() }, 1500)
    }
  })
  child.on('error', (e) => log(`上游启动失败 ${e.message}`))
}

const proxy = http.createServer((req, res) => {
  if (!token) {
    res.writeHead(503, { 'content-type': 'text/plain; charset=utf-8' })
    return res.end('dsh web 还没就绪，稍等两秒再刷新')
  }
  const forwardPath = rewritePath(req.url, req.headers.cookie, token)
  const headers = { ...req.headers }   // 保留原始 Host（cookie authority 依赖它）
  const up = http.request({
    host: '127.0.0.1', port: UPSTREAM, path: forwardPath,
    method: req.method, headers
  }, (ur) => {
    res.writeHead(ur.statusCode, ur.headers)
    ur.pipe(res)
  })
  up.on('error', (e) => {
    if (!res.headersSent) res.writeHead(502, { 'content-type': 'text/plain; charset=utf-8' })
    res.end(`上游不可达：${e.message}`)
  })
  req.pipe(up)
})

// WebSocket / SSE：把原始握头发给上游，之后双向裸转发
proxy.on('upgrade', (req, socket, head) => {
  const up = net.connect(UPSTREAM, '127.0.0.1', () => {
    let raw = `${req.method} ${req.url} HTTP/1.1\r\n`
    for (let i = 0; i < req.rawHeaders.length; i += 2) {
      raw += `${req.rawHeaders[i]}: ${req.rawHeaders[i + 1]}\r\n`
    }
    raw += '\r\n'
    up.write(raw)
    if (head && head.length) up.write(head)
    socket.pipe(up)
    up.pipe(socket)
  })
  up.on('error', () => socket.destroy())
  socket.on('error', () => up.destroy())
})

proxy.on('error', (e) => {
  log(`代理错误 ${e.message}`)
  // 登录自启与会话内实例可能同时存在。端口被占时不要留一个半死不活的进程，
  // 也不要去抢端口 —— 明确退出，日志里写清原因。
  if (e.code === 'EADDRINUSE') {
    log(`端口 ${LISTEN} 已被占用：另一个守护实例在跑，本实例退出`)
  }
  try { if (child) child.kill() } catch { }
  process.exit(1)
})
proxy.listen(LISTEN, '127.0.0.1', () => {
  log(`=== dsh-web-daemon 启动 === 代理 127.0.0.1:${LISTEN} → 上游 127.0.0.1:${UPSTREAM}`)
  startUpstream()
})

function shutdown() {
  log('收到退出信号，关闭')
  try { if (child) child.kill() } catch { }
  try { proxy.close() } catch { }
  process.exit(0)
}
process.on('SIGINT', shutdown)
process.on('SIGTERM', shutdown)
