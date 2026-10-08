package com.dsh.harness;

import android.content.Context;

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

    /** 执行一条命令（阻塞到结束），返回退出码。 */
    public static int exec(Context ctx, String cmd) {
        try {
            return start(ctx, cmd).waitFor();
        } catch (Exception e) {
            e.printStackTrace();
            return -1;
        }
    }

    /** 执行并捕获标准输出。 */
    public static String execForOutput(Context ctx, String cmd) {
        Process p = null;
        try {
            p = start(ctx, cmd);
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append('\n');
            return sb.toString();
        } catch (Exception e) {
            return "";
        } finally {
            if (p != null) p.destroy();
        }
    }
}
