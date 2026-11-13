/**
 * patch-profile.mjs —— dsh-nas-sync 内置插件的 profile 注册 / 自愈（patch.sh 调用）
 *
 * 背景：重解包（BUNDLE_REV 变化）会清掉 files/home，但 home/.dsh 整体保留
 * （DshBootstrap 的 r161 机制）→ profile 的 package.json 幸存，而 wstest/ 下的
 * 插件文件全丢。本脚本在 patch.sh 重新部署插件文件后执行，保证三件事同时成立：
 *
 *   1) profile package.json：dependencies 里 dsh-nas-sync → link:，且
 *      dsh.profile.bundles 含 "dsh-nas-sync"（缺一不激活）；
 *   2) profile/node_modules/dsh-nas-sync → 软链到部署目录
 *      （dsh 的 resolveBundleDir 只认安装锚点与 profile 目录两个位置，
 *       没有这条软链就报 cannot resolve profile bundle）；
 *   3) 清掉把本插件关掉的 disabled 行（用户或插件管理器在
 *      profiles/web/cordis.patch.yml、$DSH_HOME/cordis.patch.yml 里留下的）。
 *
 * 为什么必须存在 3)：dsh 的 patch 语义是「按 id 覆盖已存在的行」，一行
 * `- id: nas-sync, disabled: true` 会把它永久按在停用态 —— 表现就是
 * 「插件列表里有它，但界面上没有任何入口」。这条曾经真实发生过。
 *
 * 全部幂等，可重复执行；任何一步失败都不阻断启动（只打日志）。
 */
import { readFileSync, writeFileSync, existsSync, mkdirSync, symlinkSync, lstatSync, rmSync } from 'node:fs';
import { join } from 'node:path';

const home = process.env.HOME || '';
if (!home) {
  console.log('[nas-sync] HOME 未设置，跳过 profile 注册');
  process.exit(0);
}

const profileName = process.env.DSH_PROFILE || 'web';
const profDir = join(home, '.dsh', 'profiles', profileName);
const profPath = join(profDir, 'package.json');
const pluginDir = join(home, 'wstest', 'dsh-nas-sync');
const log = [];
const say = (m) => { log.push(m); console.log('[nas-sync] ' + m); };

if (!existsSync(profPath)) {
  say('profile package.json 不存在（' + profPath + '），跳过注册');
  process.exit(0);
}

// ---- 0) 验收部署产物本身：没有 dsh.bundle.patch 就不是 bundle ----
try {
  const meta = JSON.parse(readFileSync(join(pluginDir, 'package.json'), 'utf-8'));
  const declared = meta.dsh && meta.dsh.bundle && meta.dsh.bundle.patch;
  if (!declared) say('⚠️ 部署包 package.json 缺 dsh.bundle.patch —— 它不会被当成 profile 层（请用新版 assets）');
  else if (!existsSync(join(pluginDir, declared))) say('⚠️ dsh.bundle.patch 指向的 ' + declared + ' 不存在');
  else say('bundle 声明 OK: ' + declared);
  if (!(meta.dsh && meta.dsh.client)) say('⚠️ 缺 dsh.client —— 前端不会加载 client.js');
} catch (e) {
  say('⚠️ 读取部署包 package.json 失败: ' + e.message);
}

// ---- 1) profile package.json：依赖 + bundles ----
let j;
try {
  j = JSON.parse(readFileSync(profPath, 'utf-8'));
} catch (e) {
  say('profile package.json 解析失败（' + e.message + '），不动它');
  process.exit(0);
}

j.dependencies = j.dependencies || {};
const wanted = 'link:' + pluginDir;
if (j.dependencies['dsh-nas-sync'] !== wanted) {
  j.dependencies['dsh-nas-sync'] = wanted;
  say('dependencies.dsh-nas-sync = ' + wanted);
}

if (j.dependencies['@local/dsh-plugin-nas']) {
  delete j.dependencies['@local/dsh-plugin-nas'];
  say('已移除断链的旧插件依赖 @local/dsh-plugin-nas');
}

j.dsh = j.dsh || {};
j.dsh.profile = j.dsh.profile || {};
const bundles = Array.isArray(j.dsh.profile.bundles) ? j.dsh.profile.bundles.filter(Boolean) : [];
const cleaned = bundles.filter((b) => b !== '@local/dsh-plugin-nas');
if (cleaned.length !== bundles.length) say('已从 bundles 移除 @local/dsh-plugin-nas');
if (!cleaned.includes('dsh-nas-sync')) {
  cleaned.push('dsh-nas-sync');
  say('bundles 追加 dsh-nas-sync');
}
j.dsh.profile.bundles = cleaned;

writeFileSync(profPath, JSON.stringify(j, null, 2) + '\n');
say('profile 注册完成: ' + profPath);

// ---- 2) profile/node_modules/dsh-nas-sync 软链 ----
try {
  const linkDir = join(profDir, 'node_modules');
  mkdirSync(linkDir, { recursive: true });
  const link = join(linkDir, 'dsh-nas-sync');
  // 必须是「软链且目标真的存在」：patch.sh 先 rm -rf 部署目录再 cp，
  // 指向旧目录的软链会变成断链，只判断 isSymbolicLink 会把它当好的留下。
  let ok = false;
  try { ok = lstatSync(link).isSymbolicLink() && existsSync(link); } catch (e) { ok = false; }
  if (!ok) {
    try { rmSync(link, { recursive: true, force: true }); } catch (e) {}
    symlinkSync(pluginDir, link, 'dir');
    say('已建软链 ' + link + ' → ' + pluginDir);
  }
} catch (e) {
  say('⚠️ 建软链失败（' + e.message + '）—— 引擎会报 cannot resolve profile bundle');
}

// ---- 3) 清掉 disabled 行（profile 层 + home 层）----
function stripDisabled(file) {
  if (!existsSync(file)) return;
  let lines;
  try { lines = readFileSync(file, 'utf-8').split('\n'); } catch (e) { return; }
  const out = [];
  let i = 0, removed = 0;
  while (i < lines.length) {
    const line = lines[i];
    // 只处理顶层 list item（`- id:` / `- name:` 开头的行）
    const m = /^-\s+(id|name):\s*(.+?)\s*$/.exec(line);
    if (m) {
      const block = [line];
      let k = i + 1;
      while (k < lines.length && /^\s+\S/.test(lines[k])) { block.push(lines[k]); k++; }
      const text = block.join('\n');
      const isOurs = /id:\s*(nas-sync|dsh-nas-sync)\b/.test(text) || /name:\s*dsh-nas-sync\b/.test(text);
      if (isOurs && /disabled:\s*true/.test(text)) {
        removed++;
        i = k;
        continue;
      }
      out.push(...block);
      i = k;
      continue;
    }
    out.push(line);
    i++;
  }
  if (removed > 0) {
    try {
      writeFileSync(file, out.join('\n'));
      say('已清除 ' + removed + ' 条 disabled 行: ' + file);
    } catch (e) {
      say('⚠️ 写回失败（' + e.message + '）: ' + file);
    }
  }
}
try { stripDisabled(join(profDir, 'cordis.patch.yml')); } catch (e) {}
try { stripDisabled(join(home, '.dsh', 'cordis.patch.yml')); } catch (e) {}

// ---- 4) 落一份部署日志（无法 adb 时的唯一取证渠道）----
try {
  const logPath = join(home, '..', 'nas-sync-deploy.log');
  writeFileSync(logPath, new Date().toISOString() + '\n' + log.join('\n') + '\n');
} catch (e) {}
