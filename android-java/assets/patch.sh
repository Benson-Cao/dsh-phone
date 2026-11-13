#!/usr/bin/env bash
# patch.sh —— 内置补丁，打进 APK assets，重解包（BUNDLE_REV 变化）后由 DshBootstrap 调用
#
# dsh 0.2.x：v0.1 时代的兼容性补丁已全部废弃 ——
#   · flock 兼容已在 bundle 内的 flock.js 用 try/catch 降级解决，sed 暴力改写会破坏它；
#   · 0.2.x 不依赖 node-pty 预编译二进制。
# r167 起本脚本**不再改任何 UI**（移动端适配交给 dsh-web-mobile 插件，在页面内部生效）。
# 历史上这里有过「补丁 4：插件页开关缩到 28×16」的前端 CSS 追加，已随 r167 移除；
# 若需要回滚外观，改插件或官方前端，不要再往 dist 里的 index-*.css 追加 override。
# 现在只承担两件事（全部幂等，可重复执行）：
#   5) dsh-nas-sync 内置插件部署：assets 经 DshBootstrap 落地到 files/dsh-nas-sync，
#      本脚本拷到 home/wstest/（重解包会清 home）+ node_modules 软链 + profile 注册
#   5b) pnpm 就绪保障：$PREFIX 每次重解包都会被清空，内置 tarball 在这里落地
#   6) 清 App 级 cache/WebView（覆盖安装不清运行时缓存，旧页面会顶掉新资源）
set -e

HM="$HOME"
echo "==> dsh 0.2.x 运行环境补丁 (r169)"

# ---- 补丁 4：内置插件页开关缩小（28×16）----
# r169 回滚恢复：r167 曾按「不再改任何 UI」把这条删掉，真机反馈插件开关太大，遂复原。
# 只改一个类名固定的原子（开关轨道/滑块尺寸），颜色与布局一律不动。
SW_DIR="$HM/node_modules/@deepseek-ai/dsh-web-frontend/dist/assets"
if [ -d "$SW_DIR" ]; then
  for css in "$SW_DIR"/index-*.css; do
    [ -f "$css" ] || continue
    if ! grep -q "dsh-phone-switch-patch" "$css"; then
      printf '\n/* dsh-phone-switch-patch r165: 28x16 */\n._switch_1ik0f_5{width:28px!important;height:16px!important;padding:2px!important}\n._switch_1ik0f_5 ._thumb_1ik0f_33{width:12px!important;height:12px!important}\n._switch_1ik0f_5[aria-checked=true] ._thumb_1ik0f_33{transform:translate(12px)!important}\n' >> "$css"
      echo "  ✓ 开关缩小 override 已追加: $(basename "$css")"
    else
      echo "  · 开关 override 已存在: $(basename "$css")"
    fi
  done
else
  echo "  ! 未找到 dsh-web-frontend assets，跳过开关补丁"
fi

# ---- 补丁 5：dsh-nas-sync 内置插件部署 ----
SRC="$HM/../dsh-nas-sync"          # DshBootstrap.copyAssetDir 落地：files/dsh-nas-sync
DST="$HM/wstest/dsh-nas-sync"
if [ -f "$SRC/package.json" ]; then
  mkdir -p "$HM/wstest"
  rm -rf "$DST"
  cp -r "$SRC" "$DST"
  # 依赖软链：插件 require('@deepseek-ai/*') 指向主 node_modules
  mkdir -p "$DST/node_modules"
  ln -sfn "$HM/node_modules/@deepseek-ai" "$DST/node_modules/@deepseek-ai"
  echo "  ✓ dsh-nas-sync 已部署到 $DST"
  # profile/node_modules 软链：dsh 用两个锚点解析 bundle（安装目录 → profile 目录），
  # 少了这条链就报 cannot resolve profile bundle "dsh-nas-sync"，整层不生效。
  # 这里用 ln 兜底（与上面 @deepseek-ai 同款手法），不依赖 node 的特权。
  mkdir -p "$HM/.dsh/profiles/web/node_modules"
  ln -sfn "$DST" "$HM/.dsh/profiles/web/node_modules/dsh-nas-sync" 2>/dev/null || true
  # profile 注册（link 依赖 + bundles + 清 disabled 行；幂等，见 patch-profile.mjs）
  NODE_BIN="$(command -v node 2>/dev/null || true)"
  if [ -n "$NODE_BIN" ] && [ -f "$DST/patch-profile.mjs" ]; then
    # 输出同时落盘：patch.sh 的 stdout 在真机上没人看，失败过一次就再也没线索
    # ⚠️ r168：`NODE_OPTIONS=` 必须清空（原因见下方 pnpm 那段的注释）——
    #   否则 node 预加载 dsh-node-shim.js 失败，整个脚本没跑就退出了。
    NODE_OPTIONS= "$NODE_BIN" "$DST/patch-profile.mjs" >> "$HM/../nas-sync-deploy.log" 2>&1 \
      || echo "  ! profile 注册失败（细节见 files/nas-sync-deploy.log）"
  else
    echo "  ! node 或 patch-profile.mjs 缺失，跳过 profile 注册"
  fi

  # 自检：这三个条件缺一个，界面上就不会出现 NAS 同步入口（r166 根因复盘）
  PROF_PKG="$HM/.dsh/profiles/web/package.json"
  if [ -f "$PROF_PKG" ] && grep -q '"dsh-nas-sync"' "$PROF_PKG"; then
    echo "  ✓ profile 已登记 dsh-nas-sync"
  else
    echo "  ! profile 未登记 dsh-nas-sync（不会有入口）"
  fi
  if grep -q '"bundle"' "$DST/package.json" 2>/dev/null; then
    echo "  ✓ 插件声明 dsh.bundle"
  else
    echo "  ! 插件未声明 dsh.bundle（引擎报 declares no dsh.bundle，整层无效）"
  fi
  if grep -q 'insert:' "$DST/cordis.patch.yml" 2>/dev/null; then
    echo "  ✓ bundle patch 带 insert"
  else
    echo "  ! cordis.patch.yml 缺 insert（root 是空数组，什么也插不进去）"
  fi
else
  echo "  · assets 未包含 dsh-nas-sync，跳过插件部署"
fi

# ---- 补丁 5b：pnpm 就绪保障（市场装插件的前提条件） ----
# 为什么必须放在这里：$PREFIX（files/usr）在 BUNDLE_REV 变化时会被整包重解包，
# 手工装进去的 pnpm 会随之消失，于是「能装插件」这个能力在一次升级后就没了。
#
# 而 bundle 里**只有 corepack 的 shim，没有 pnpm 实体**，且那些 shim 是 Termux 布局：
#   ① 权限 0600，没有执行位；② shebang 写死 /data/data/com.termux/files/usr/bin/sh
#   （本 App 的路径是 com.dsh.harness，该解释器根本不存在）。
# 后果：市场按名字探测到 pnpm → 执行时命中的是这堆坏 shim → 报 Permission denied
# → 被 install-failure.js 的 /permission denied/i 判成 permission 类
# → 界面显示「没有写入权限，无法安装」。这条文案与真实原因（启动器不可执行）完全无关，
# 排查时极易被带偏（本次实测踩过）。
#
# 做法：内置官方 pnpm tarball（零依赖纯 JS），解包到 $PREFIX/opt/pnpm 并写一个
# 指向本 App node 的 shim —— 与 DshMarketInstaller.installPnpm 产物布局完全一致。
PREFIX="$HM/../usr"
PNPM_VENDOR="$SRC/vendor/pnpm-12.10.1.tgz"
# patch.sh 里 node 能跑依赖这两个变量（bundle 的 node 把 Termux 前缀编译进去了）
export LD_LIBRARY_PATH="${LD_LIBRARY_PATH:-$PREFIX/lib}"
export OPENSSL_CONF="${OPENSSL_CONF:-$PREFIX/etc/tls/openssl.cnf}"
if [ -x "$PREFIX/bin/pnpm" ]; then
  echo "  · pnpm 已就绪: $("$PREFIX/bin/pnpm" --version 2>/dev/null || echo '?')"
elif [ -f "$PNPM_VENDOR" ]; then
  mkdir -p "$PREFIX/libexec"
  cat > "$PREFIX/libexec/dsh-pnpm-boot.js" <<'PNPMBOOT'
// 从内置 tarball 落地 pnpm（幂等；产物布局同 DshMarketInstaller.installPnpm）
const fs = require('node:fs'), path = require('node:path'), zlib = require('node:zlib');
const crypto = require('node:crypto'), { execFileSync } = require('node:child_process');
const TGZ = process.argv[2], PREFIX = process.argv[3];
const EXPECT = 'ba40a37eb1a370d60fea8c4cf1d364c13bcccef518e98ad2bcee954f539d38da37458e58261a3fa55e06523450ea0ffff9f497b135be41f2c15c1642bacace6c';
const out = (m) => process.stdout.write(m + '\n');
try {
  const buf = fs.readFileSync(TGZ);
  const got = crypto.createHash('sha512').update(buf).digest('hex');
  if (got !== EXPECT) { out('ERR:sha512-mismatch'); process.exit(5); }
  const raw = zlib.gunzipSync(buf);
  const opt = path.join(PREFIX, 'opt', 'pnpm');
  fs.rmSync(opt, { recursive: true, force: true });
  fs.mkdirSync(opt, { recursive: true });
  let off = 0, files = 0;
  while (off + 512 <= raw.length) {
    const name = raw.toString('utf8', off, off + 100).replace(/\0.*$/, '');
    if (!name) { off += 512; continue; }
    const sz = parseInt(raw.toString('ascii', off + 124, off + 136).replace(/\0.*$/, '').trim() || '0', 8);
    const type = String.fromCharCode(raw[off + 156]);
    const rel = name.replace(/^package\//, '');
    const dOff = off + 512;
    if (rel && type === '0') {
      const dest = path.join(opt, rel);
      fs.mkdirSync(path.dirname(dest), { recursive: true });
      fs.writeFileSync(dest, raw.slice(dOff, dOff + sz));
      files++;
    } else if (rel && type === '5') {
      fs.mkdirSync(path.join(opt, rel), { recursive: true });
    }
    off = dOff + Math.ceil(sz / 512) * 512;
  }
  if (files === 0) { out('ERR:bad-tarball'); process.exit(5); }
  const shim = '#!/bin/sh\nexec "' + path.join(PREFIX, 'bin', 'node') + '" "' +
    path.join(opt, 'bin', 'pnpm.mjs') + '" "$@"\n';
  const shimPath = path.join(PREFIX, 'bin', 'pnpm');
  fs.writeFileSync(shimPath, shim, { mode: 0o755 });
  try { fs.chmodSync(shimPath, 0o755); } catch (e) {}
  fs.copyFileSync(path.join(opt, 'bin', 'pnpm.mjs'), path.join(opt, 'bin', 'pnpm.cjs'));
  out('EXTRACTED:' + files);
  out('PNPM:' + execFileSync(shimPath, ['--version'], { encoding: 'utf8' }).trim());
} catch (e) {
  out('ERR:' + (e && e.message || e));
  process.exit(6);
}
PNPMBOOT
  NODE_BIN="$(command -v node 2>/dev/null || echo "$PREFIX/bin/node")"
  # ⚠️⚠️ r168 真机踩坑：`NODE_OPTIONS=` 这个前缀是**必须**的。
  #   App 进程为引擎导出了 `NODE_OPTIONS=--require $PREFIX/libexec/dsh-node-shim.js`，
  #   patch.sh 由 bootstrap 在 **createLauncher 之前** 调用，那时 dsh-node-shim.js 还没写出来
  #   → node 启动阶段 preload 就 MODULE_NOT_FOUND 直接退出（真机表现：
  #   files/pnpm-boot.log 里只有 "Cannot find module .../dsh-node-shim.js"，
  #   然后 $PREFIX/bin/pnpm 不存在，市场又变回「没有写入权限，无法安装」）。
  #   pnpm bootstrap 只往 $PREFIX 写普通文件，完全不需要 SAF fs 路由 shim。
  if NODE_OPTIONS= "$NODE_BIN" "$PREFIX/libexec/dsh-pnpm-boot.js" "$PNPM_VENDOR" "$PREFIX" >> "$HM/../pnpm-boot.log" 2>&1; then
    echo "  ✓ pnpm 已就绪（内置 tarball）: $("$PREFIX/bin/pnpm" --version 2>/dev/null || echo '?')"
  else
    echo "  ! pnpm 供给失败（细节见 files/pnpm-boot.log）"
  fi
  # vendor 只是给本脚本用的，别让它跟着进插件目录（会被当成插件内容）
  rm -rf "$DST/vendor" 2>/dev/null || true
else
  echo "  ! 未内置 pnpm tarball —— 市场「安装插件」将不可用"
fi


# ---- 补丁 6：清 App 级 WebView 缓存 ----
# Android WebView 的 HTTP 缓存在 App 级 cache/WebView（不是 code_cache），
# 覆盖安装不会清；不清则旧 CSS/页面继续被 WebView 使用（v30.45 教训）。
CACHE_DIR="$HM/../cache"
if [ -d "$CACHE_DIR" ]; then
  rm -rf "$CACHE_DIR/WebView" 2>/dev/null || true
  echo "  ✓ 已清 cache/WebView"
fi

echo "==> 补丁完成"
exit 0
