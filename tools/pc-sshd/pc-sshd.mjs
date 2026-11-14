#!/usr/bin/env node
/**
 * pc-sshd —— 电脑侧的 SSH 服务端，给手机端「SSH 主机」档案做隧道对端。
 *
 * 为什么不用 Windows 自带的 OpenSSH Server：
 *   它属于可选系统能力（`Add-WindowsCapability -Online -Name OpenSSH.Server~~~~0.0.1.0`），
 *   安装要管理员；且 Windows 的 sshd 必须由 SYSTEM 起服务，普通账户跑不起来。
 *   本机实测：22 端口无 sshd、能力状态需提权才能读、`Start-Process -Verb RunAs` 被安全策略拦。
 *
 * 因此这里提供一个**够用且更小**的 SSH 服务端：只做手机 SSH 档真正需要的那两件事 ——
 *   1) 公钥认证（只认 authorized_keys 里列出的公钥）
 *   2) 本地转发（direct-tcpip，且**只准连回环**）
 * 不提供 shell / exec / sftp / pty —— 没有的就攻击不到。这也让「拿这台机器当跳板」
 * 这件事从根上不成立：转发目标只能是 127.0.0.1 / ::1。
 *
 * 用法：
 *   npm install
 *   node pc-sshd.mjs                     # 用默认配置（端口 22）
 *   pc-sshd.mjs --port 2222 --print-config
 *   PCS_SHD_CONFIG=D:\path\pc-sshd.json node pc-sshd.mjs
 *
 * 首次运行会生成主机密钥并打印指纹；手机首次连接时会钉住这个指纹（TOFU）。
 */
import fs from 'node:fs'
import net from 'node:net'
import os from 'node:os'
import path from 'node:path'
import crypto from 'node:crypto'
import { fileURLToPath } from 'node:url'
import ssh2 from 'ssh2'

const { Server, utils } = ssh2
const HERE = path.dirname(fileURLToPath(import.meta.url))
const HOME = os.homedir()
const LOOPBACK = new Set(['127.0.0.1', '::1', 'localhost'])

// ---- 配置 ----

function readJson(p) {
  try {
    return JSON.parse(fs.readFileSync(p, 'utf8'))
  } catch {
    return null
  }
}

function argValue(name, fallback) {
  const i = process.argv.indexOf(name)
  return i >= 0 && process.argv[i + 1] ? process.argv[i + 1] : fallback
}

function loadConfig() {
  const candidates = [
    process.env.PCS_SHD_CONFIG,
    path.join(HERE, 'pc-sshd.json'),
    path.join(HOME, '.dsh', 'pc-sshd.json')
  ].filter(Boolean)
  let file = null
  for (const c of candidates) {
    const j = readJson(c)
    if (j) { file = { path: c, data: j }; break }
  }
  const d = (file && file.data) || {}
  const base = path.join(HOME, '.dsh', 'pc-sshd')
  return {
    _file: file ? file.path : '(默认值)',
    port: Number(argValue('--port', d.port || 22)),
    host: d.host || '0.0.0.0',
    hostKey: d.hostKey || path.join(base, 'host_ed25519'),
    authorizedKeys: d.authorizedKeys || path.join(HOME, '.dsh', 'ssh', 'authorized_keys'),
    logFile: d.logFile || path.join(base, 'pc-sshd.log'),
    // 只允许转到回环。想放开成整片网段等于把本机变成跳板，不做成配置项。
    allowPorts: Array.isArray(d.allowPorts) && d.allowPorts.length ? d.allowPorts : null,
    banner: d.banner || 'dsh-pc-sshd'
  }
}

const cfg = loadConfig()

// ---- 日志 ----

function ts() {
  return new Date().toISOString().replace('T', ' ').slice(0, 19)
}
function log(...a) {
  const line = `[${ts()}] ${a.join(' ')}`
  console.log(line)
  try {
    fs.mkdirSync(path.dirname(cfg.logFile), { recursive: true })
    fs.appendFileSync(cfg.logFile, line + '\n')
  } catch { /* 日志写不了不能影响服务 */ }
}

// ---- 主机密钥 ----

function loadOrCreateHostKey() {
  if (fs.existsSync(cfg.hostKey)) return fs.readFileSync(cfg.hostKey)
  fs.mkdirSync(path.dirname(cfg.hostKey), { recursive: true })
  const { private: priv } = utils.generateKeyPairSync('ed25519')
  fs.writeFileSync(cfg.hostKey, priv, { mode: 0o600 })
  log(`已生成主机密钥 ${cfg.hostKey}`)
  return priv
}

function sha256Fp(blob) {
  return 'SHA256:' + crypto.createHash('sha256').update(blob).digest('base64').replace(/=+$/, '')
}

// ---- 授权公钥 ----

/**
 * 读 authorized_keys（只取算法与 base64 两列，注释无所谓）。
 * 文件不存在就返回空表 —— 宁可不让人进，也不做「首次自动信任任意公钥」。
 */
function loadAuthorized() {
  const map = new Map()
  let raw = ''
  try {
    raw = fs.readFileSync(cfg.authorizedKeys, 'utf8')
  } catch {
    return map
  }
  for (const line of raw.split(/\r?\n/)) {
    const t = line.trim()
    if (!t || t.startsWith('#')) continue
    const parts = t.split(/\s+/)
    if (parts.length < 2) continue
    const algo = parts[0]
    const b64 = parts[1]
    try {
      map.set(algo + ':' + b64, { algo, blob: Buffer.from(b64, 'base64'), line: t })
    } catch { /* 坏行跳过 */ }
  }
  return map
}

// ---- 服务 ----

const hostKey = loadOrCreateHostKey()
const hostKeyFp = sha256Fp(utils.parseKey(hostKey).getPublicSSH())

log('=== pc-sshd 启动 ===')
log(`配置来源 ${cfg._file}`)
log(`监听 ${cfg.host}:${cfg.port}`)
log(`主机公钥指纹 ${hostKeyFp}   ← 手机首次连接会钉住这个值`)
log(`授权公钥 ${cfg.authorizedKeys}`)
try {
  const n = loadAuthorized().size
  log(`已加载公钥 ${n} 个${n === 0 ? '（为空：任何连接都会被拒，先把手机公钥写进该文件）' : ''}`)
} catch { }

if (process.argv.includes('--print-config')) {
  log('--- 生效配置 ---')
  log(JSON.stringify({ ...cfg }, null, 2))
}

if (process.argv.includes('--add-key')) {
  const key = argValue('--add-key', '')
  if (!key) {
    log('✗ --add-key 后面要跟一整行公钥（ssh-ed25519 AAAA…）')
    process.exit(2)
  }
  fs.mkdirSync(path.dirname(cfg.authorizedKeys), { recursive: true })
  const cur = fs.existsSync(cfg.authorizedKeys) ? fs.readFileSync(cfg.authorizedKeys, 'utf8') : ''
  const b64 = key.trim().split(/\s+/)[1]
  if (b64 && cur.includes(b64)) {
    log('· 这把公钥已在 authorized_keys 里，不重复添加')
  } else {
    fs.appendFileSync(cfg.authorizedKeys, (cur.endsWith('\n') || cur === '' ? '' : '\n') + key.trim() + '\n')
    log(`✓ 已追加公钥到 ${cfg.authorizedKeys}`)
  }
  process.exit(0)
}

const server = new Server(
  {
    hostKeys: [hostKey],
    banner: cfg.banner
  },
  (client) => {
    const id = crypto.randomBytes(3).toString('hex')
    let who = ''

    client.on('authentication', (ctx) => {
      if (ctx.method !== 'publickey') {
        // 只开公钥：密码认证在无 askpass 的手机端也无法非交互完成
        return ctx.reject(['publickey'])
      }
      const authorized = loadAuthorized()   // 每次现读：加/删公钥不用重启服务
      const hit = authorized.get(ctx.key.algo + ':' + ctx.key.data.toString('base64'))
      const fp = sha256Fp(ctx.key.data)
      if (!hit) {
        log(`[${id}] ✗ 拒绝未授权公钥 ${fp}（不在 ${cfg.authorizedKeys}）`)
        return ctx.reject(['publickey'])
      }
      if (!ctx.signature) {
        // 探询阶段：告诉客户端"这把可以，请出示签名"
        return ctx.accept()
      }
      who = `${ctx.username || '-'} ${fp}`
      log(`[${id}] ✓ 公钥认证通过 user=${ctx.username || '-'} fp=${fp}`)
      return ctx.accept()
    })

    client.on('ready', () => {
      log(`[${id}] ready`)
    })

    // 本地转发（手机 `ssh -L`）走这里；拒绝一切非回环目标
    client.on('tcpip', (accept, reject, info) => {
      const dest = info.destIP
      const port = Number(info.destPort)
      if (!LOOPBACK.has(dest)) {
        log(`[${id}] ✗ 拒绝转发到非回环目标 ${dest}:${port}（本服务只做本机隧道）`)
        return reject()
      }
      if (cfg.allowPorts && !cfg.allowPorts.includes(port)) {
        log(`[${id}] ✗ 拒绝转发到未放行的端口 ${port}（allowPorts=${cfg.allowPorts.join(',')}）`)
        return reject()
      }
      const up = net.connect(port, dest, () => {
        const ch = accept()
        up.pipe(ch).pipe(up)
        up.on('close', () => ch.close())
        ch.on('close', () => up.destroy())
        log(`[${id}] ✓ 隧道 ${dest}:${port}`)
      })
      up.on('error', (e) => {
        log(`[${id}] ✗ 连不上 ${dest}:${port} → ${e.code || e.message}`)
        up.destroy()
        reject()
      })
    })

    // 没有 shell / exec / sftp：手机用 `-N -T`，本来就不该发这些
    client.on('session', (accept, reject) => {
      log(`[${id}] ✗ 拒绝 session（本服务不提供 shell/exec/sftp）`)
      reject()
    })

    client.on('error', (e) => log(`[${id}] 客户端错误 ${e.message}`))
    client.on('close', () => log(`[${id}] 关闭 ${who ? '(' + who + ')' : ''}`))
  }
)

server.on('error', (e) => {
  log(`✗ 服务端错误 ${e.message}`)
  if (e.code === 'EADDRINUSE') {
    log(`  端口 ${cfg.port} 被占用 —— 用 --port 换一个，或先停掉占用它的程序`)
  }
  process.exit(1)
})

server.listen(cfg.port, cfg.host, () => {
  log(`就绪。手机填：主机=<这台机器的 IP> 端口=${cfg.port} 用户名任意 目标端口=<本地要连的端口>`)
})
