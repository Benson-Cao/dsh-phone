/**
 * dsh-web-daemon 的转发规则（纯函数，无副作用，便于单测）。
 *
 * 只在「首载（还没有 dsh-auth cookie）且 URL 带 token 参数」时替换令牌。
 * 为什么必须看 cookie：上游在令牌有效时总是回 303，若在跟随跳转后的 `/`
 * 上继续注入令牌，就会 303 → 注入 → 303 … 无限循环。
 */
export function rewritePath(rawUrl, cookieHeader, token) {
  if (!token) return rawUrl
  if (/(?:^|;\s*)dsh-auth-/.test(cookieHeader || '')) return rawUrl
  let u
  try { u = new URL(rawUrl, 'http://placeholder') } catch { return rawUrl }
  if (!u.searchParams.has('token')) return rawUrl
  u.searchParams.set('token', token)
  return u.pathname + u.search
}

/**
 * 把上游的登录跳转 Location 规整成绝对路径（2026-11-19）。
 *
 * 上游 dsh web 令牌通过后回 `303 / Location: ./` + Set-Cookie。桌面浏览器与 curl
 * 都能正确解析这个相对地址，但手机 WebView 在「首载 /?token=旧值 → 303 → ./」这条
 * 链上出现过停在空白文档的情况（真机：隧道正常转发一次 303 后不再发第二跳）。
 * 改成绝对路径 `/` 语义完全等价，却去掉了 WebView 对相对 Location 的解析歧义。
 *
 * 只动 `./`、`./?...` 这类「指向根」的相对跳转；其它 Location（例如带路径的
 * 深层跳转）原样透传，避免改变上游语义。
 */
export function rewriteLocation(location) {
  if (!location) return location
  if (location === './' || location === '.') return '/'
  const q = location.indexOf('?')
  if (location.startsWith('./') && q >= 0) return '/' + location.slice(q)
  return location
}
