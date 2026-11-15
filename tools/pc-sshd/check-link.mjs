#!/usr/bin/env node
/**
 * check-link.mjs —— 一条命令验完「手机 SSH 档 → 电脑 dsh」链路（纯只读）。
 *
 * 为什么要它：代理是会话后台任务，生命周期结束时会被回收；登录自启只在
 * 下次登录生效。以前要靠六条手工 curl 才能判断链路是否还活着，现在一次跑完。
 *
 *   node check-link.mjs            # 默认 22 / 3080 / 3082
 *   node check-link.mjs --adb <serial>   # 附带读手机档案（adb 不在时自动跳过）
 *
 * 退出码 0 = 全通过；非 0 = 有检查失败。只读，不改任何文件/状态。
 */
import net from 'node:net'
import http from 'node:http'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { execFileSync } from 'node:child_process'

const argv = process.argv.slice(2)
const arg = (name, fallback) => {
  const i = argv.indexOf(name)
  return i >= 0 && argv[i + 1] ? argv[i + 1] : fallback
}
const SSHD_PORT = Number(arg('--sshd', 22))
const PROXY_PORT = Number(arg('--listen', 3080))
const UPSTREAM_PORT = Number(arg('--upstream', 3082))
const SERIAL = arg('--adb', '')
const TOKEN_FILE = path.join(os.homedir(), '.dsh', 'pc-sshd', 'dsh-web-token.txt')

const results = []
function check(name, ok, detail = '') {
  results.push({ name, ok, detail })
  console.log(`${ok ? '  PASS' : '  FAIL'}  ${name}${detail ? '  — ' + detail : ''}`)
}
function skip(name, why) {
  console.log(`  SKIP  ${name}  — ${why}`)
}

function canConnect(port) {
  return new Promise((resolve) => {
    const s = net.connect(port, '127.0.0.1')
    const done = (v) => { s.destroy(); resolve(v) }
    s.setTimeout(2000)
    s.once('connect', () => done(true))
    s.once('error', () => done(false))
    s.once('timeout', () => done(false))
  })
}

function request(port, reqPath, headers = {}) {
  return new Promise((resolve) => {
    const req = http.request({
      host: '127.0.0.1', port, path: reqPath, method: 'GET',
      headers: { Host: '127.0.0.1:3081', ...headers }, timeout: 8000
    }, (res) => {
      let body = ''
      res.on('data', (d) => { body += d })
      res.on('end', () => resolve({ code: res.statusCode, headers: res.headers, body }))
    })
    req.on('error', (e) => resolve({ code: 0, headers: {}, body: e.message }))
    req.on('timeout', () => { req.destroy(); resolve({ code: -1, headers: {}, body: 'timeout' }) })
    req.end()
  })
}

async function main() {
  console.log(`链路自检 ${SSHD_PORT} / ${PROXY_PORT} / ${UPSTREAM_PORT}\n`)

  check('pc-sshd 端口可连', await canConnect(SSHD_PORT), `127.0.0.1:${SSHD_PORT}`)
  check('代理端口可连', await canConnect(PROXY_PORT), `127.0.0.1:${PROXY_PORT}`)
  check('上游 dsh web 端口可连', await canConnect(UPSTREAM_PORT), `127.0.0.1:${UPSTREAM_PORT}`)

  const anon = await request(PROXY_PORT, '/')
  check('无令牌被拒（安全边界仍在）', anon.code === 401, `HTTP ${anon.code}`)

  const token = fs.existsSync(TOKEN_FILE)
    ? fs.readFileSync(TOKEN_FILE, 'utf8').trim() : ''
  check('令牌文件存在', !!token, token ? `${token.length} 字符` : TOKEN_FILE)

  if (token) {
    const direct = await request(UPSTREAM_PORT, `/?token=${encodeURIComponent(token)}`)
    check('令牌与上游当前值一致', direct.code === 303, `直打上游 HTTP ${direct.code}`)
  }

  const first = await request(PROXY_PORT, '/?token=stale-token-from-phone')
  check('旧令牌经代理被换成当前令牌', first.code === 303, `HTTP ${first.code}`)
  const setCookie = first.headers['set-cookie']
  const cookie = Array.isArray(setCookie) ? setCookie[0] : setCookie
  const cookiePair = cookie ? String(cookie).split(';')[0] : ''
  check('下发签名 cookie', !!cookiePair && cookiePair.includes('dsh-auth-'))
  if (cookiePair) {
    try {
      const payload = cookiePair.split('=')[1].split('.')[1]
      const claim = JSON.parse(Buffer.from(payload, 'base64url').toString())
      check('cookie 授权域是手机侧 Host', claim.authority === '127.0.0.1:3081', claim.authority)
    } catch (e) {
      check('cookie 授权域是手机侧 Host', false, e.message)
    }
  }

  const page = await request(PROXY_PORT, '/', { Cookie: cookiePair })
  check('跟随跳转拿到真实 dsh 页面',
    page.code === 200 && page.body.includes('<!doctype html>')
      && !page.body.includes('FAKE 3080'),
    `HTTP ${page.code} / ${page.body.length} 字节`)

  if (SERIAL) {
    try {
      // Node 的 execFileSync 要 Windows 形式路径；Git Bash 的 /d/... 会 ENOENT。
      const adb = arg('--adb-bin', 'D:/AndroidSDK/platform-tools/adb.exe')
      const out = execFileSync(adb, ['-s', SERIAL, 'shell', 'run-as', 'com.dsh.harness',
        'cat', 'shared_prefs/dsh_profiles.xml'], { encoding: 'utf8', timeout: 10000 })
      check('手机档案仍是 SSH 档', out.includes('&quot;current&quot;:&quot;ssh&quot;'))
      check('手机 targetPort 仍为 3080', out.includes('&quot;targetPort&quot;:3080'))
    } catch (e) {
      skip('手机档案', `adb 不可用：${String(e.message).slice(0, 80)}`)
    }
  } else {
    skip('手机档案', '未传 --adb <serial>')
  }
}

await main()
const failed = results.filter((r) => !r.ok).length
console.log(`\n链路自检：${results.length - failed} passed, ${failed} failed`)
if (failed) process.exitCode = 1
