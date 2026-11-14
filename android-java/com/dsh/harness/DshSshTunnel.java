package com.dsh.harness;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * SSH 本地隧道（S-6.7 / 批 C）：`ssh -L 127.0.0.1:localPort:127.0.0.1:3080 user@host`。
 *
 * <p>对 WebView 而言隧道口仍是<b>回环</b>，所以信任边界不变、桥照常注入
 * （与 relay 档的远端 origin 完全相反）。
 *
 * <h3>当前的真实状态</h3>
 * r117 之后本安装包的 bundle 已带 openssh（ssh / ssh-keygen / scp / sftp，r122 起还有
 * ssh-keyscan），{@link #available} 在真机上为 true；隧道层已在真机上端到端跑通
 * （见 `docs/native-pages.md` §18）。bundle 万一没带，{@link #available} 返回 false，
 * {@link #unavailableReason} 给出可执行的处置。**不要**在不可用时假装连接并加载一个空端口
 * —— 那只会得到白屏。
 *
 * <h3>三条安全与稳定性约束</h3>
 * <ol>
 *   <li><b>主机指纹 TOFU</b>：取对端主机公钥（首选 `ssh-keyscan`，缺它时用一次试探连接，
 *       见 {@link #scanKeyLine}）→ 算指纹 → 与上次记录比对 → 不同即阻断
 *       （{@link SshHostKey#decide}）。正式连接永远 `StrictHostKeyChecking=yes` +
 *       正式 known_hosts：那等于关掉中间人防线的事一件都不做。</li>
 *   <li><b>密钥认证而非密码</b>：包内既无 askpass 也无 sshpass，密码认证无法非交互完成；
 *       首次使用生成 ed25519 密钥，用户把公钥加到对端 `authorized_keys`。</li>
 *   <li><b>断线可见</b>：`ServerAliveInterval` 让死连接自己退出，进程退出由调用方重试，
 *       不要用无限重连掩盖「对端没开」。</li>
 * </ol>
 */
public final class DshSshTunnel {

    private static final String TAG = "DshSshTunnel";
    private static final String PREFS = "dsh_ssh";
    private static final Charset UTF8 = Charset.forName("UTF-8");

    private static volatile Process proc;
    private static volatile int forwardedPort;

    private DshSshTunnel() {}

    // ---- 环境 ----

    /** `files/usr`（bundle 根）。 */
    private static File prefix(Context c) {
        return new File(c.getApplicationInfo().dataDir, "files/usr");
    }

    /** `files/home`（Termux 风格 HOME）。 */
    private static File home(Context c) {
        return new File(c.getApplicationInfo().dataDir, "files/home");
    }

    private static File sshBin(Context c) {
        return new File(prefix(c), "bin/ssh");
    }

    private static File keygenBin(Context c) {
        return new File(prefix(c), "bin/ssh-keygen");
    }

    /** bundle 里是否带了 ssh 客户端（决定整档能否工作）。 */
    public static boolean available(Context c) {
        return sshBin(c).isFile() && sshBin(c).canExecute();
    }

    /**
     * bundle 里是否带了 `ssh-keyscan`（取主机公钥的首选工具）。
     *
     * <p>r122 之前收割的包**只有** ssh / ssh-keygen / scp / sftp 四个二进制
     * （`fetch-openssh.sh` 的白名单漏了 keyscan），所以首次连接取不到主机公钥。
     * 现在 keyscan 已随包；{@link #scanKeyLine} 另有试探连接兜底，缺它也能用，
     * 但界面要能说清是「包里没有」还是「对端不可达」。
     */
    public static boolean keyscanAvailable(Context c) {
        File bin = new File(prefix(c), "bin/ssh-keyscan");
        return bin.isFile() && bin.canExecute();
    }

    /** 不可用时的可执行处置（直接展示给用户，不要含糊）。 */
    public static String unavailableReason(Context c) {
        return "当前安装包不含 ssh 客户端（bundle 的 usr/bin 里没有 ssh / ssh-keygen）。\n"
                + "SSH 档案需要随包带 openssh：在出包用的 Termux 里执行\n"
                + "  pkg install -y openssh\n"
                + "再重跑 02b-harvest-bundle.sh 与 02-build-bundle.sh 出新包。\n"
                + "在那之前请用「本机」或「Relay」档案。";
    }

    // ---- 密钥 ----

    /**
     * 确保存在 ed25519 密钥对；返回公钥单行（供用户贴到对端 authorized_keys）。
     * 已存在则直接返回已有公钥。失败返回 {@code null}。
     */
    public static String ensureKeyPair(Context c) {
        File dir = new File(home(c), ".ssh");
        File priv = new File(dir, "id_ed25519");
        File pub = new File(dir, "id_ed25519.pub");
        if (priv.isFile() && pub.isFile()) {
            return readText(pub);
        }
        if (!dir.exists() && !dir.mkdirs()) return null;
        try {
            List<String> cmd = new ArrayList<String>();
            cmd.add(keygenBin(c).getAbsolutePath());
            cmd.add("-t"); cmd.add("ed25519");
            cmd.add("-N"); cmd.add("");
            cmd.add("-C"); cmd.add("dsh-phone");
            cmd.add("-f"); cmd.add(priv.getAbsolutePath());
            runOnce(c, cmd, null, 20000);
            chmod600(priv);
            return pub.isFile() ? readText(pub) : null;
        } catch (Exception e) {
            Log.w(TAG, "生成密钥失败: " + e.getMessage());
            return null;
        }
    }

    /**
     * 直接跑 `ssh`（用户在设置里「连接」前不需要它）。
     *
     * @return 已启动的进程；调用方用它判断存活。失败返回 {@code null}
     */
    public static synchronized Process start(Context c, HostProfile.Profile p) {
        stop();
        if (!available(c)) {
            Log.w(TAG, "bundle 无 ssh，拒绝启动隧道");
            return null;
        }
        try {
            List<String> cmd = new ArrayList<String>();
            cmd.add(sshBin(c).getAbsolutePath());
            cmd.add("-N");                                  // 只做转发，不要远端 shell
            cmd.add("-T");
            cmd.add("-o"); cmd.add("BatchMode=yes");         // 任何交互式提问都视为失败
            cmd.add("-o"); cmd.add("ExitOnForwardFailure=yes"); // 端口占不上就退出，别假装成功
            cmd.add("-o"); cmd.add("StrictHostKeyChecking=yes");
            cmd.add("-o"); cmd.add("UserKnownHostsFile=" + new File(home(c), ".ssh/known_hosts"));
            cmd.add("-o"); cmd.add("ServerAliveInterval=20");
            cmd.add("-o"); cmd.add("ServerAliveCountMax=3");
            cmd.add("-i"); cmd.add(new File(home(c), ".ssh/id_ed25519").getAbsolutePath());
            cmd.add("-p"); cmd.add(String.valueOf(p.sshPort));
            cmd.add("-L"); cmd.add("127.0.0.1:" + p.localPort + ":127.0.0.1:3080");
            cmd.add(p.sshUser + "@" + p.sshHost);

            ProcessBuilder pb = new ProcessBuilder(cmd);
            Map<String, String> env = pb.environment();
            env.put("PREFIX", prefix(c).getAbsolutePath());
            env.put("HOME", home(c).getAbsolutePath());
            env.put("PATH", prefix(c).getAbsolutePath() + "/bin:" + env.get("PATH"));
            env.put("LD_LIBRARY_PATH", prefix(c).getAbsolutePath() + "/lib");
            env.put("SHELL", prefix(c).getAbsolutePath() + "/bin/bash");
            env.put("TMPDIR", prefix(c).getAbsolutePath() + "/tmp");
            env.put("OPENSSL_CONF", prefix(c).getAbsolutePath() + "/etc/tls/openssl.cnf");
            pb.redirectErrorStream(true);

            Process pr = pb.start();
            proc = pr;
            forwardedPort = p.localPort;
            Log.i(TAG, "隧道启动: 127.0.0.1:" + p.localPort + " -> " + p.sshUser + "@"
                    + p.sshHost + ":" + p.sshPort);
            return pr;
        } catch (Exception e) {
            Log.w(TAG, "隧道启动失败: " + e.getMessage());
            return null;
        }
    }

    public static synchronized void stop() {
        Process pr = proc;
        proc = null;
        forwardedPort = 0;
        if (pr == null) return;
        try { pr.destroy(); } catch (Throwable ignored) { }
    }

    public static boolean running() {
        Process pr = proc;
        if (pr == null) return false;
        try {
            pr.exitValue();
            return false;          // 已退出
        } catch (IllegalThreadStateException alive) {
            return true;
        }
    }

    public static int forwardedPort() {
        return forwardedPort;
    }

    // ---- 主机指纹（TOFU）----

    /** 已钉住的指纹（host:port），没有返回 {@code null}。 */
    public static String pinned(Context c, String host, int port) {
        return prefs(c).getString(key(host, port), null);
    }

    /** 记录指纹。 */
    public static void pin(Context c, String host, int port, String fingerprint) {
        prefs(c).edit().putString(key(host, port), fingerprint).apply();
    }

    /**
     * 用 `ssh-keyscan` 取对端主机公钥**原始行**（写 known_hosts 需要它，指纹不够）。
     *
     * <p>r122：keyscan 不在包里时改走 {@link #scanKeyLineViaConnect}，不再直接判失败 ——
     * 否则「本包缺一个二进制」会被界面说成「对端不可达」，把人引到错的方向。
     *
     * @return 形如 `host ssh-ed25519 AAAA…`；取不到返回 {@code null}
     */
    public static String scanKeyLine(Context c, String host, int port) {
        if (!available(c)) return null;
        if (!keyscanAvailable(c)) {
            Log.i(TAG, "ssh-keyscan 不在 bundle 里，改用试探连接取主机公钥");
            return scanKeyLineViaConnect(c, host, port);
        }
        File keyscan = new File(prefix(c), "bin/ssh-keyscan");
        try {
            List<String> cmd = new ArrayList<String>();
            cmd.add(keyscan.getAbsolutePath());
            if (port != 22) { cmd.add("-p"); cmd.add(String.valueOf(port)); }
            cmd.add("-T"); cmd.add("8");
            cmd.add("-t"); cmd.add("ed25519");
            cmd.add(host);
            String out = runOnce(c, cmd, null, 12000);
            String line = firstKeyLine(out);
            if (line == null) {
                // keyscan 在但什么都没扫到（对端不可达 / 只提供 rsa）：再用一次试探连接。
                // 两条路都失败才判「取不到」，避免单一工具的兼容性问题变成功能不可用。
                Log.i(TAG, "ssh-keyscan 无输出，改用试探连接重试");
                return scanKeyLineViaConnect(c, host, port);
            }
            return line;
        } catch (Exception e) {
            Log.w(TAG, "ssh-keyscan 失败: " + e.getMessage());
            return null;
        }
    }

    /**
     * 备用取主机公钥：用一次「不做主机校验」的**试探连接**，让 ssh 自己把对端公钥
     * 写进临时 known_hosts，再读出来。
     *
     * <p>为什么不是安全降级：host key 在密钥交换阶段就完成校验并落盘，**早于**用户认证；
     * 这与 `ssh-keyscan` 的信任模型完全一致（都信任「第一次看到的那把」）。真正的
     * TOFU 比对仍在调用方：拿到 line → 算指纹 → 与上次记录比 → 不同即阻断。
     * 之后的正式连接依旧 `StrictHostKeyChecking=yes` + 正式 known_hosts。
     *
     * <p>试探连接故意用不存在的用户名 + BatchMode，认证必然失败 —— 我们要的只是公钥，
     * 所以进程退出也算「可以读文件了」，不当作失败。
     */
    private static String scanKeyLineViaConnect(Context c, String host, int port) {
        File dir = new File(home(c), ".ssh");
        if (!dir.isDirectory() && !dir.mkdirs()) return null;
        File tmp = new File(dir, "scan_known_hosts.tmp");
        try { if (tmp.exists() && !tmp.delete()) return null; } catch (Throwable ignored) { }

        Process pr = null;
        try {
            List<String> cmd = new ArrayList<String>();
            cmd.add(sshBin(c).getAbsolutePath());
            cmd.add("-N"); cmd.add("-T");
            cmd.add("-o"); cmd.add("BatchMode=yes");
            cmd.add("-o"); cmd.add("StrictHostKeyChecking=no");   // 只为拿公钥，随后由本类钉住
            cmd.add("-o"); cmd.add("UpdateHostKeys=no");
            cmd.add("-o"); cmd.add("UserKnownHostsFile=" + tmp.getAbsolutePath());
            cmd.add("-o"); cmd.add("GlobalKnownHostsFile=/dev/null");
            cmd.add("-o"); cmd.add("ConnectTimeout=8");
            cmd.add("-p"); cmd.add(String.valueOf(port));
            cmd.add("-l"); cmd.add("dsh-keyscan");                // 不存在的用户：不让它真的登进去
            cmd.add(host);

            ProcessBuilder pb = new ProcessBuilder(cmd);
            Map<String, String> env = pb.environment();
            env.put("PREFIX", prefix(c).getAbsolutePath());
            env.put("HOME", home(c).getAbsolutePath());
            env.put("PATH", prefix(c).getAbsolutePath() + "/bin:" + env.get("PATH"));
            env.put("LD_LIBRARY_PATH", prefix(c).getAbsolutePath() + "/lib");
            env.put("TMPDIR", prefix(c).getAbsolutePath() + "/tmp");
            env.put("OPENSSL_CONF", prefix(c).getAbsolutePath() + "/etc/tls/openssl.cnf");
            pb.redirectErrorStream(true);
            pr = pb.start();

            long deadline = System.currentTimeMillis() + 14000;
            while (System.currentTimeMillis() < deadline) {
                String line = firstKeyLine(readText(tmp));
                if (line != null) return line;
                if (!alive(pr)) break;      // 进程已退出（认证失败也算）：再读一次就收工
                try { Thread.sleep(150); } catch (InterruptedException e) { break; }
            }
            return firstKeyLine(readText(tmp));
        } catch (Exception e) {
            Log.w(TAG, "试探连接取主机公钥失败: " + e.getMessage());
            return null;
        } finally {
            if (pr != null) { try { pr.destroy(); } catch (Throwable ignored) { } }
            try { if (tmp.exists()) tmp.delete(); } catch (Throwable ignored) { }
        }
    }

    /** 从多行文本里挑出第一行「主机 算法 base64」形态的公钥行。 */
    private static String firstKeyLine(String text) {
        if (text == null || text.isEmpty()) return null;
        for (String line : text.split("\n")) {
            if (SshHostKey.blobOfKeyLine(line) != null) return line.trim();
        }
        return null;
    }

    private static boolean alive(Process pr) {
        try { pr.exitValue(); return false; } catch (IllegalThreadStateException running) { return true; }
    }

    /** 取对端主机公钥指纹（`SHA256:…`）；取不到返回 {@code null}。 */
    public static String scanFingerprint(Context c, String host, int port) {
        return SshHostKey.fingerprintOfKeyLine(scanKeyLine(c, host, port));
    }

    /**
     * 把主机公钥写进 known_hosts（`StrictHostKeyChecking=yes` 需要它）。
     *
     * <p>行首主机形态有讲究：非 22 端口必须是 `[host]:port`，否则 ssh 认不出这条记录，
     * 表现是「明明写进去了还是提示未知主机」。
     */
    public static boolean writeKnownHost(Context c, String host, int port, String keyLine) {
        if (keyLine == null || keyLine.trim().isEmpty()) return false;
        File dir = new File(home(c), ".ssh");
        File kh = new File(dir, "known_hosts");
        try {
            if (!dir.exists() && !dir.mkdirs()) return false;
            String[] parts = keyLine.trim().split("\\s+");
            if (parts.length < 3) return false;
            String algo = parts[1];
            String b64 = parts[2];
            String hostForm = port == 22 ? host : "[" + host + "]:" + port;
            String entry = hostForm + " " + algo + " " + b64;

            StringBuilder sb = new StringBuilder();
            if (kh.isFile()) {
                for (String line : readText(kh).split("\n")) {
                    if (line.trim().isEmpty()) continue;
                    // 同主机的旧记录先删掉（换过指纹时避免两行并存导致 ssh 困惑）
                    if (line.startsWith(hostForm + " ")) continue;
                    sb.append(line).append('\n');
                }
            }
            sb.append(entry).append('\n');
            FileOutputStream fos = new FileOutputStream(kh, false);
            try {
                fos.write(sb.toString().getBytes(UTF8));
            } finally {
                fos.close();
            }
            return true;
        } catch (Exception e) {
            Log.w(TAG, "写 known_hosts 失败: " + e.getMessage());
            return false;
        }
    }

    // ---- 内部 ----

    /** 跑一次性命令，返回 stdout+stderr；超时/失败返回 null。 */
    private static String runOnce(Context c, List<String> cmd, File workDir, int timeoutMs) {
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            Map<String, String> env = pb.environment();
            env.put("PREFIX", prefix(c).getAbsolutePath());
            env.put("HOME", home(c).getAbsolutePath());
            env.put("PATH", prefix(c).getAbsolutePath() + "/bin:" + env.get("PATH"));
            env.put("LD_LIBRARY_PATH", prefix(c).getAbsolutePath() + "/lib");
            env.put("TMPDIR", prefix(c).getAbsolutePath() + "/tmp");
            env.put("OPENSSL_CONF", prefix(c).getAbsolutePath() + "/etc/tls/openssl.cnf");
            if (workDir != null) pb.directory(workDir);
            pb.redirectErrorStream(true);
            Process pr = pb.start();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            InputStream in = pr.getInputStream();
            byte[] buf = new byte[4096];
            long deadline = System.currentTimeMillis() + timeoutMs;
            int n;
            while (System.currentTimeMillis() < deadline && (n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            try { pr.waitFor(); } catch (InterruptedException ignored) { }
            return new String(bos.toByteArray(), UTF8);
        } catch (Exception e) {
            return null;
        }
    }

    private static String readText(File f) {
        try {
            java.io.FileInputStream in = new java.io.FileInputStream(f);
            try {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
                return new String(bos.toByteArray(), UTF8);
            } finally {
                in.close();
            }
        } catch (Exception e) {
            return "";
        }
    }

    private static void chmod600(File f) {
        try { f.setReadable(false, false); f.setReadable(true, true);
              f.setWritable(false, false); f.setWritable(true, true); } catch (Throwable ignored) { }
    }

    private static String key(String host, int port) {
        return "fp:" + host + ":" + port;
    }

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
