#!/usr/bin/env node
/** dsh-web-daemon 转发规则的确定性断言，不启动任何服务。 */
import { rewritePath, rewriteLocation } from './web-proxy-rules.mjs'

const results = []
function check(name, ok, detail = '') {
  results.push(ok)
  console.log(`${ok ? '  PASS' : '  FAIL'}  ${name}${detail ? '  — ' + detail : ''}`)
}

const OLD = 'ogULwWH3qvOGmN73M4zjvP7Ivu89sJnco6qIMhxKfAQ'
const NEW = 'OpWr2ni0LsReC-tVZP8BOfiXQffuzB05EyxvM-NeqcI'

check('首载把旧令牌换成当前真令牌',
  rewritePath(`/?token=${OLD}`, '', NEW) === `/?token=${NEW}`)
check('替换后不再残留旧令牌',
  !rewritePath(`/?token=${OLD}`, '', NEW).includes(OLD))
check('已带 dsh-auth cookie 时不再注入（防 303 死循环）',
  rewritePath(`/?token=${OLD}`, 'dsh-auth-abc=v1.xxx', NEW) === `/?token=${OLD}`)
check('dsh-auth 出现在 cookie 串中段也能识别',
  rewritePath(`/?token=${OLD}`, 'foo=1; dsh-auth-abc=v1.xxx; bar=2', NEW) === `/?token=${OLD}`)
check('只有无关 cookie 时仍替换',
  rewritePath(`/?token=${OLD}`, 'foo=1', NEW) === `/?token=${NEW}`)
check('上游令牌未就绪时原样转发',
  rewritePath(`/?token=${OLD}`, '', '') === `/?token=${OLD}`)
check('URL 不带 token 参数时原样转发',
  rewritePath('/assets/app.js', '', NEW) === '/assets/app.js')
check('替换时其它查询参数保留',
  rewritePath(`/?token=${OLD}&x=1`, '', NEW).includes('x=1'))
check('非根路径带 token 也替换',
  rewritePath(`/api/x?token=${OLD}`, '', NEW).includes(NEW))
check('含 - / _ 的令牌解码后不失真', (() => {
  const out = rewritePath('/?token=old', '', NEW)
  return new URL(out, 'http://x').searchParams.get('token') === NEW
})())
check('畸形 URL 不抛异常', (() => {
  try { return rewritePath('http://[bad', '', NEW) === 'http://[bad' } catch { return false }
})())
check('跟随跳转后的裸根路径不再被注入',
  rewritePath('/', 'dsh-auth-abc=v1.xxx', NEW) === '/')
check('相对 Location ./ 规整为绝对路径 /', rewriteLocation('./') === '/')
check('相对 Location ./?x=1 保留查询串', rewriteLocation('./?x=1') === '/?x=1')
check('相对 Location . 规整为 /', rewriteLocation('.') === '/')
check('带路径的相对 Location 原样透传', rewriteLocation('./dashboard') === './dashboard')
check('绝对 Location 原样透传', rewriteLocation('/dashboard') === '/dashboard')
check('外部 Location 原样透传', rewriteLocation('https://example.com/x') === 'https://example.com/x')
check('空 Location 不抛异常', rewriteLocation(null) === null && rewriteLocation('') === '')

const failed = results.filter((r) => !r).length
console.log(`\ndsh-web-daemon 规则：${results.length - failed} passed, ${failed} failed`)
if (failed) process.exitCode = 1
