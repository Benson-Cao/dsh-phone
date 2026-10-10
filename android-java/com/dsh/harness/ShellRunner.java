package com.dsh.harness;

import android.content.Context;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;

/**
 * 在 Termux 风格的前缀（prefix）环境里执行 shell 命令的辅助类。
 *
 * fork 后 app 私有目录结构与 Termux 一致：
 *   $PREFIX = /data/data/com.dsh.harness/files/usr
 *   $HOME   = /data/data/com.dsh.harness/files/home
 */
public final class ShellRunner {

    private static final String TAG = "ShellRunner";

    private ShellRunner() {}

    public static File prefix(Context ctx) {
        return new File(ctx.getFilesDir(), "usr");
    }

    public static File home(Context ctx) {
        return new File(ctx.getFilesDir(), "home");
    }

    /** 注入给子进程的环境变量，让 node 与 .so 找到前缀。 */
    public static String[] buildEnv(Context ctx) {
        String p = prefix(ctx).getAbsolutePath();
        String h = home(ctx).getAbsolutePath();
        return new String[] {
            "PATH=" + p + "/bin:/system/bin:/system/xbin",
            "HOME=" + h,
            "PREFIX=" + p,
            "LD_LIBRARY_PATH=" + p + "/lib",
            // Termux 二进制把下面这些路径硬编码成 /data/data/com.termux/…，必须覆盖
            "SHELL=" + p + "/bin/bash",
            "TMPDIR=" + p + "/tmp",
            "SQLITE_TMPDIR=" + p + "/tmp",
            "OPENSSL_CONF=" + p + "/etc/tls/openssl.cnf",
            "OPENSSL_MODULES=" + p + "/lib/ossl-modules",
            "NODE_OPTIONS=--require " + p + "/libexec/dsh-node-shim.js"
        };
    }

    private static Process start(Context ctx, String cmd) throws Exception {
        return Runtime.getRuntime().exec(
            new String[] { prefix(ctx).getAbsolutePath() + "/bin/bash", "-c", cmd },
            buildEnv(ctx),
            ctx.getFilesDir());
    }

    /** 执行一条命令（阻塞到结束），返回退出码。会抽干 stdio 防止管道死锁。 */
    public static int exec(Context ctx, String cmd) {
        Process p = null;
        try {
            p = start(ctx, cmd);
            Thread out = drain(p.getInputStream());
            Thread err = drain(p.getErrorStream());
            out.start(); err.start();
            int code = p.waitFor();
            // r72（S1）：子进程的后代会继承管道 fd —— bash 退出后 EOF 未必到来，
            //   无超时 join 会永久挂起。超时后放弃等待（drain 线程泄漏到进程结束，
            //   可接受），并销毁直接子进程兑底。
            out.join(10000); err.join(10000);
            if (out.isAlive() || err.isAlive()) {
                Log.w(TAG, "exec 收尾超时（存在存活的孙进程），已放弃等待: " + cmd);
                try { p.destroyForcibly(); } catch (Throwable ignored) { }
            }
            return code;
        } catch (Exception e) {
            if (p != null) p.destroy();
            Log.e(TAG, "exec 失败: " + cmd, e);
            return -1;
        }
    }

    /** 执行并捕获标准输出（同时抽干 stderr，避免其撑爆管道）。 */
    public static String execForOutput(Context ctx, String cmd) {
        Process p = null;
        try {
            p = start(ctx, cmd);
            final Process pw = p;
            // r72（S1）：读行循环依赖 EOF —— 孙进程持有 stdout 时永不返回。
            //   挂一个 30s 看门狗，超时销毁进程让流关闭（当前无调用方，纯防御）。
            Thread watchdog = new Thread(new Runnable() {
                @Override public void run() {
                    try { Thread.sleep(30000); pw.destroy(); } catch (Throwable ignored) { }
                }
            });
            watchdog.setDaemon(true);
            watchdog.start();
            Thread err = drain(p.getErrorStream()); err.start();
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            StringBuilder sb = new StringBuilder(); String line;
            while ((line = r.readLine()) != null) sb.append(line).append('\n');
            int code = p.waitFor();
            err.join(10000);
            if (code != 0) {
                // r72（S2）：失败与空输出不可区分会掩盖问题 —— 至少留一条日志。
                Log.e(TAG, "execForOutput 退出码 " + code + ": " + cmd);
            }
            return sb.toString();
        } catch (Exception e) {
            Log.e(TAG, "execForOutput 异常: " + cmd, e);
            return "";
        } finally {
            if (p != null) p.destroy();
        }
    }

    /** 把输入流抽干到 /dev/null（防止写满管道阻塞对端）。 */
    private static Thread drain(final java.io.InputStream is) {
        return new Thread(new Runnable() {
            @Override public void run() {
                try { byte[] b = new byte[4096]; while (is.read(b) >= 0); } catch (Exception ignored) {}
            }
        });
    }
}
