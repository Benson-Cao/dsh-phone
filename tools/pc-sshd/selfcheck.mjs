#!/usr/bin/env node
/**
 * pc-sshd 自测：不依赖手机、不碰用户的 %USERPROFILE%\.dsh，全部用临时目录。
 *
 * 为什么要有它：README 的「已验」是真机手工证据，换台机器/改过代码后没法复跑。
 * 这里把安全口径本身变成断言 —— 只认公钥、只转发回环、不提供 session。
 *
 * 跑法：npm test   （或 node selfcheck.mjs）
 * 退出码 0 = 全通过；非 0 = 有断言失败。
 */
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import net from 'node:net'
import http from 'node:http'
import { spawn } from 'node:child_process'
import { fileURLToPath } from 'node:url'
import ssh2 from 'ssh2'

const { Client, utils } = ssh2
const HERE = path.dirname(fileURLToPath(import.meta.url))

const results = []
function check(name, ok, detail = '') {
  results.push({ name, ok, detail })
  console.log(`${ok ? '  PASS' : '  FAIL'}  ${name}${detail ? '  — ' + detail : ''}`)
}

const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'pc-sshd-check-'))
let child = null
let fake = null
const conns = []

function freePort() {
  return new Promise((resolve, reject) => {
    const s = net.createServer()
    s.once('error', reject)
    s.listen(0, '127.0.0.1', () => {
      const p = s.address().port
      s.close(() => resolve(p))
    })
  })
}

function waitPort(port, timeoutMs = 10000) {
  const deadline = Date.now() + timeoutMs
  return new Promise(async (resolve) => {
    while (Date.now() < deadline) {
      const ok = await new Promise((r) => {
        const s = net.connect(port, '127.0.0.1')
        s.once('connect', () => { s.destroy(); r(true) })
        s.once('error', () => r(false))
      })
      if (ok) return resolve(true)
      await new Promise((r) => setTimeout(r, 120))
    }
    resolve(false)
  })
}

function connect(port, privateKey) {
  return new Promise((resolve, reject) => {
    const c = new Client()
    c.once('ready', () => { conns.push(c); resolve(c) })
    c.once('error', reject)
    c.connect({
      host: '127.0.0.1', port, username: 'selfcheck',
      privateKey, readyTimeout: 6000
    })
  })
}

function forward(c, dstIP, dstPort) {
  return new Promise((resolve, reject) => {
    c.forwardOut('127.0.0.1', 0, dstIP, dstPort, (err, stream) =>
      err ? reject(err) : resolve(stream))
  })
}

function fetchOver(stream) {
  return new Promise((resolve, reject) => {
    let body = ''
    const timer = setTimeout(() => reject(new Error('HTTP over tunnel 超时')), 5000)
    stream.on('data', (d) => { body += d })
    stream.on('error', (e) => { clearTimeout(timer); reject(e) })
    stream.on('close', () => { clearTimeout(timer); resolve(body) })
    stream.write('GET / HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n')
  })
}

async function main() {
  // 一对「已登记」的钥匙、一对「未登记」的钥匙
  const good = utils.generateKeyPairSync('ed25519')
  const bad = utils.generateKeyPairSync('ed25519')
  const pubB64 = utils.parseKey(good.public).getPublicSSH().toString('base64')
  fs.writeFileSync(path.join(tmp, 'authorized_keys'), `ssh-ed25519 ${pubB64} selfcheck\n`)

  const sshPort = await freePort()
  fake = http.createServer((_req, res) => res.end('SELFCHECK-OK'))
  await new Promise((r) => fake.listen(0, '127.0.0.1', r))
  const fakePort = fake.address().port

  const cfgPath = path.join(tmp, 'pc-sshd.json')
  const logPath = path.join(tmp, 'pc-sshd.log')
  fs.writeFileSync(cfgPath, JSON.stringify({
    host: '127.0.0.1',
    port: sshPort,
    hostKey: path.join(tmp, 'host_ed25519'),
    authorizedKeys: path.join(tmp, 'authorized_keys'),
    logFile: logPath,
    banner: 'selfcheck'
  }))

  child = spawn(process.execPath, [path.join(HERE, 'pc-sshd.mjs'), '--port', String(sshPort)], {
    cwd: HERE,
    env: { ...process.env, PCS_SHD_CONFIG: cfgPath },
    stdio: ['ignore', 'pipe', 'pipe']
  })
  let bootLog = ''
  child.stdout.on('data', (d) => { bootLog += d })
  child.stderr.on('data', (d) => { bootLog += d })

  const ready = await waitPort(sshPort)
  check('服务在指定端口起来', ready, ready ? `127.0.0.1:${sshPort}` : bootLog.trim().slice(-200))
  if (!ready) return

  // 1) 授权公钥能通过
  let c
  try {
    c = await connect(sshPort, good.private)
    check('authorized_keys 内的公钥通过认证', true)
  } catch (e) {
    check('authorized_keys 内的公钥通过认证', false, e.message)
    return
  }

  // 2) direct-tcpip 到回环假服务，真的能收发 HTTP
  try {
    const s = await forward(c, '127.0.0.1', fakePort)
    const body = await fetchOver(s)
    check('direct-tcpip 转发到回环目标', body.includes('SELFCHECK-OK'),
      body.split('\r\n')[0] || '(空响应)')
  } catch (e) {
    check('direct-tcpip 转发到回环目标', false, e.message)
  }

  // 3) 非回环目标必须被拒（不做跳板）
  try {
    await forward(c, '192.0.2.1', 80)
    check('非回环转发目标被拒', false, '竟然放行了')
  } catch {
    check('非回环转发目标被拒', true)
  }

  // 4) 不提供 shell/exec/sftp
  try {
    await new Promise((resolve, reject) => c.shell((err) => err ? reject(err) : resolve()))
    check('session(shell) 被拒', false, '竟然给了 shell')
  } catch {
    check('session(shell) 被拒', true)
  }

  // 5) 未登记的公钥必须被拒
  try {
    const c2 = await connect(sshPort, bad.private)
    c2.end()
    check('未登记公钥被拒', false, '竟然认证通过')
  } catch {
    check('未登记公钥被拒', true)
  }

  // 6) 日志口径：认证 / 隧道 / 拒绝都要留痕
  const log = fs.existsSync(logPath) ? fs.readFileSync(logPath, 'utf8') : ''
  check('日志记录认证、隧道与拒绝原因',
    log.includes('公钥认证通过') && log.includes(`隧道 127.0.0.1:${fakePort}`)
      && log.includes('拒绝转发到非回环目标') && log.includes('拒绝 session'),
    `${log.split('\n').filter(Boolean).length} 行`)
}

function cleanup() {
  for (const c of conns) { try { c.end() } catch { } }
  if (fake) { try { fake.close() } catch { } }
  if (child && child.exitCode === null) { try { child.kill() } catch { } }
  try { fs.rmSync(tmp, { recursive: true, force: true }) } catch { }
}

const guard = setTimeout(() => {
  console.error('\n整体超时（20s），强制收尾')
  cleanup()
  process.exit(1)
}, 20000)

try {
  await main()
} catch (e) {
  check('自测脚本自身无异常', false, e.stack || e.message)
} finally {
  clearTimeout(guard)
  cleanup()
}

const failed = results.filter((r) => !r.ok)
console.log(`\npc-sshd selfcheck: ${results.length - failed.length} passed, ${failed.length} failed`)
if (failed.length) process.exitCode = 1
