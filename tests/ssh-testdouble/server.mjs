#!/usr/bin/env node
/**
 * server.mjs —— SSH 档联调用的**测试替身**（不是产品代码，不进 APK）。
 *
 * 为什么需要它：手机侧 SSH 档（DshSshTunnel）要验的是这一串动作 ——
 *   ssh-keyscan 取主机指纹 → TOFU 比对/钉住 → 用 ed25519 公钥认证 →
 *   `-N -T -L 127.0.0.1:3081:127.0.0.1:3080` 建隧道 → 本地端口能 accept。
 * 它只需要一个「肯接受公钥、肯转 direct-tcpip」的对端，不需要真 sshd。
 * 而本机（Windows）只有 OpenSSH **客户端**，`C:\Windows\System32\OpenSSH` 里没有 sshd.exe；
 * Windows 的 sshd 又必须由 SYSTEM 起服务，普通账户跑不起来。故用 ssh2 写这个替身。
 *
 * 用法：
 *   npm install                       # 只依 ssh2
 *   node server.mjs                   # 监听 0.0.0.0:2222，转发到 127.0.0.1:3080
 *   PORT=2222 TARGET=127.0.0.1:3080 HOSTKEY=./hostkey_ed25519 node server.mjs
 *   FRESH=1 node server.mjs           # 重新生成主机密钥（用来验「指纹不一致必须阻断」）
 *
 * 它做的事：
 *   1) 首次运行生成 ed25519 主机密钥并存盘（默认 ./hostkey_ed25519），
 *      之后每次复用 —— 这样手机上钉住的指纹仍然匹配，走 MATCH 分支。
 *   2) publickey 认证**接受任意公钥**，但把指纹与注释打到日志：
 *      手机侧 `ssh-keygen -lf` 的指纹应当与日志里的一致，这是「密钥确实来自本机 Keystore」的证据。
 *   3) direct-tcpip 通道一律连到 TARGET。连不上就 reject，手机侧表现为
 *      `waitLocalPort` 超时 —— 这正是要验的失败路径之一。
 *   4) session（shell/exec）请求一律拒绝并记录：手机侧用 `-N -T`，本来就不该发。
 */
import fs from 'node:fs';
import net from 'node:net';
import crypto from 'node:crypto';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import ssh2 from 'ssh2';

const { Server, utils } = ssh2;

const HERE = path.dirname(fileURLToPath(import.meta.url));
const PORT = Number(process.env.PORT || 2222);
const [TARGET_HOST, TARGET_PORT] = (process.env.TARGET || '127.0.0.1:3080').split(':');
const KEY_PATH = process.env.HOSTKEY || path.join(HERE, 'hostkey_ed25519');

function stamp() {
  return new Date().toISOString().replace('T', ' ').slice(0, 19);
}
function log(...a) {
  console.log(`[${stamp()}]`, ...a);
}

/** OpenSSH 口径的指纹：SHA256 + base64（去掉 '=' 填充）。与 SshHostKey.fingerprintOfBlob 同口径。 */
function sha256Fp(blob) {
  return 'SHA256:' + crypto.createHash('sha256').update(blob).digest('base64').replace(/=+$/, '');
}

function loadOrCreateHostKey() {
  if (process.env.FRESH === '1' && fs.existsSync(KEY_PATH)) {
    fs.unlinkSync(KEY_PATH);
    log(`FRESH=1 → 已删除旧主机密钥 ${KEY_PATH}（手机侧应报指纹不一致并阻断）`);
  }
  if (fs.existsSync(KEY_PATH)) {
    return fs.readFileSync(KEY_PATH);
  }
  const { private: priv } = utils.generateKeyPairSync('ed25519');
  fs.writeFileSync(KEY_PATH, priv, { mode: 0o600 });
  log(`已生成新主机密钥 ${KEY_PATH}`);
  return priv;
}

const hostKey = loadOrCreateHostKey();
// 主机公钥指纹：从私钥里导出公钥 blob（ssh2 的 parseKey 能给到 getPublicSSH()）
const parsed = utils.parseKey(hostKey);
const hostKeyBlob = parsed.getPublicSSH();
log(`主机公钥指纹 ${sha256Fp(hostKeyBlob)}（手机首次连接会钉住这个值）`);
log(`监听 0.0.0.0:${PORT} → 转发目标 ${TARGET_HOST}:${TARGET_PORT}`);

const server = new Server({ hostKeys: [hostKey] }, (client) => {
  const id = crypto.randomBytes(3).toString('hex');

  client.on('authentication', (ctx) => {
    if (ctx.method === 'none') {
      // 先问公钥：让客户端把可用的 key 送上来。返回 list 会让部分客户端直接跳过签名步骤。
      return ctx.reject(['publickey']);
    }
    if (ctx.method === 'publickey') {
      log(`[${id}] 公钥认证 algo=${ctx.key.algo} fp=${sha256Fp(ctx.key.data)}`
        + (ctx.signature ? ' （已附签名 → 接受）' : ' （探询 → 请客户端签名）'));
      return ctx.accept();
    }
    log(`[${id}] 拒绝其它认证方式 ${ctx.method}`);
    return ctx.reject(['publickey']);
  });

  client.on('ready', () => {
    log(`[${id}] 认证通过，进入 ready`);
  });

  // -L 本地转发对端会开 direct-tcpip 通道
  client.on('tcpip', (accept, reject, info) => {
    log(`[${id}] direct-tcpip ${info.destIP}:${info.destPort} ← ${info.srcIP}:${info.srcPort}`);
    if (String(info.destPort) !== String(TARGET_PORT)) {
      // 手机侧固定转发到 3080；端口不对就拒，避免「以为通了其实打到别处」
      log(`[${id}] ✗ 目标端口 ${info.destPort} ≠ ${TARGET_PORT}，拒绝`);
      return reject();
    }
    const up = net.connect(Number(TARGET_PORT), TARGET_HOST, () => {
      const ch = accept();
      up.pipe(ch).pipe(up);
      up.on('close', () => ch.close());
      ch.on('close', () => up.destroy());
      log(`[${id}] ✓ 隧道已建立`);
    });
    up.on('error', (e) => {
      log(`[${id}] ✗ 连不上转发目标 ${TARGET_HOST}:${TARGET_PORT} → ${e.code || e.message}`);
      up.destroy();
      reject();
    });
  });

  client.on('session', (accept, reject) => {
    // -N -T 不该开 session；开了就是调用方参数不对，留证据
    log(`[${id}] session 请求（-N -T 下不该出现），拒绝`);
    reject();
  });

  client.on('error', (e) => log(`[${id}] 客户端错误 ${e.message}`));
  client.on('close', () => log(`[${id}] 连接关闭`));
});

server.on('error', (e) => {
  log(`服务端错误 ${e.message}`);
  process.exit(1);
});

server.listen(PORT, '0.0.0.0', () => {
  log('就绪。Windows 防火墙若拦入站，手机会表现为连不上 —— 这属于环境问题，不是隧道代码问题。');
});
