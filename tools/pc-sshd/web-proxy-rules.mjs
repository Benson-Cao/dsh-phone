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
