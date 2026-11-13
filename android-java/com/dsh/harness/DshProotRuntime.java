package com.dsh.harness;

import android.content.Context;
import android.os.Build;
import android.util.Log;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * r152（官方对齐）：PRoot 派运行底座（对标 dsh-Go 产品化方案）。
 *
 * <p>优势（相对 Termux）：免 Root、免 Termux、无 PTY 管理层包袱；内置 Ubuntu 24.04 用户态 +
 * 静态 proot，体积更小；首启更干净，不污染 $PREFIX 目录。
 *
 * <p>数据流：
 * <pre>
 *   assets/dsh-proot-bundle.tar.gz
 *     -> 解压到 files/rootfs（Ubuntu 用户态 + node + dsh）
 *     -> proot -r rootfs -b /system -b /vendor -b /proc --kill-on-exit \
 *        node ... bin.js web --port 3080
 * </pre>
 *
 * <p>⚠️ 本产物需要 Linux 环境构建（见 scripts/05-build-proot.sh），Windows 沙箱无法生成；
 * 未放入 assets 时 {@link #isAvailable()} 返回 false，选择器自动回退 Termux 派 —— 因此
 * 在 proot bundle 落地前，App 行为与旧版完全一致（PRoot 代码路径处于休眠状态）。
 */
public final class DshProotRuntime implements RuntimeProvider {

    private static final String TAG = "DshProotRuntime";
    private static final String BUNDLE = "dsh-proot-bundle.tar.gz";
    private static final String PROOT_BIN = "usr/bin/proot";
    private static final String MARKER = ".dsh_proot_version";
    /** 与官方对齐的 proot bundle 修订号（独立常量，不随 App 版本号漂移，避免无谓重解包）。 */
    private static final String EXPECTED = "0.2.1-rc.5-proot";

    private final Context ctx;
    private final File filesDir;
    private final File rootFs;
    private final File dshHome;

    public DshProotRuntime(Context ctx) {
        this.ctx = ctx;
        this.filesDir = ctx.getFilesDir();
        this.rootFs = new File(filesDir, "rootfs");
        this.dshHome = new File(rootFs, "home/dsh");
    }

    @Override
    public String name() {
        return "proot";
    }

    /** 可用性：架构必须是 arm64-v8a，且 proot 二进制已随 bundle 解压就位。 */
    @Override
    public boolean isAvailable() {
        if (Build.SUPPORTED_ABIS.length == 0
                || !"arm64-v8a".equals(Build.SUPPORTED_ABIS[0])) {
            Log.w(TAG, "不支持的 ABI：" + java.util.Arrays.toString(Build.SUPPORTED_ABIS));
            return false;
        }
        File proot = new File(filesDir, PROOT_BIN);
        return proot.exists() && proot.canExecute();
    }

    @Override
    public boolean isReady() {
        File marker = new File(filesDir, MARKER);
        if (!marker.exists() || !EXPECTED.equals(readText(marker))) return false;
        return new File(dshHome, "node_modules").exists();
    }

    @Override
    public void bootstrap(Context ctx, ProgressCallback cb) throws Exception {
        if (isReady()) {
            if (cb != null) cb.onProgress(100);
            return;
        }
        // 1) 解压 rootfs（Ubuntu 24.04 用户态 + node + dsh）
        if (cb != null) cb.onProgress(5);
        java.io.InputStream in = ctx.getAssets().open(BUNDLE);
        try {
            // 复用 DshBootstrap 的硬化 tar 解包器（符号链接 / 校验和 / Zip-Slip 防护）
            DshBootstrap.extractStream(in, filesDir, null);
        } finally {
            try { in.close(); } catch (Exception ignored) { }
        }

        // 2) 修正 rootfs 内可执行权限（PRoot 对 +x 敏感）
        if (cb != null) cb.onProgress(87);
        fixExecutable(rootFs);

        // 3) 写版本标记，避免重复解压
        if (cb != null) cb.onProgress(95);
        writeText(new File(filesDir, MARKER), EXPECTED);

        if (cb != null) cb.onProgress(100);
        Log.i(TAG, "bootstrap done (proot)");
    }

    /** 递归给 rootfs 内 bin/ sbin/ 下文件加执行位（chmod 等价）。 */
    private void fixExecutable(File dir) {
        File[] kids = dir.listFiles();
        if (kids == null) return;
        for (File f : kids) {
            if (f.isDirectory()) {
                fixExecutable(f);
            } else if (f.getAbsolutePath().contains("/bin/")
                    || f.getAbsolutePath().contains("/sbin/")
                    || "node".equals(f.getName())
                    || "proot".equals(f.getName())) {
                f.setExecutable(true, false);
            }
        }
    }

    @Override
    public Process startEngine(Context ctx, List<String> args) throws Exception {
        String proot = new File(filesDir, PROOT_BIN).getAbsolutePath();
        // dsh 入口：优先 npm 全局布局（home/dsh/node_modules/@deepseek-ai/dsh/lib/bin.js），
        // 否则回退官方脚本假设的 home/dsh/bin.js。二选一，兼容两种 bundle 打包方式。
        File binJs = new File(dshHome, "node_modules/@deepseek-ai/dsh/lib/bin.js");
        if (!binJs.exists()) binJs = new File(dshHome, "bin.js");
        String node = new File(rootFs, "usr/bin/node").getAbsolutePath();

        List<String> cmd = new ArrayList<String>();
        cmd.add(proot);
        cmd.add("-r"); cmd.add(rootFs.getAbsolutePath());   // 根切换
        cmd.add("-b"); cmd.add("/system");                  // 绑定系统分区（WebView/证书链需要）
        cmd.add("-b"); cmd.add("/vendor");
        cmd.add("-b"); cmd.add("/proc");
        cmd.add("--kill-on-exit");                          // 引擎退出即回收子进程
        cmd.add(node);
        cmd.add("--expose-internals");
        cmd.add(binJs.getAbsolutePath());
        cmd.add("web");
        cmd.add("--port");
        cmd.add(String.valueOf(DshBootstrap.DSH_PORT));
        if (args != null) cmd.addAll(args);

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(dshHome);
        java.util.Map<String, String> env = pb.environment();
        env.clear();
        env.put("HOME", dshHome.getAbsolutePath());
        env.put("NODE_PATH", new File(dshHome, "node_modules").getAbsolutePath());
        env.put("TMPDIR", ctx.getCacheDir().getAbsolutePath());
        // PRoot 内 PATH，不带宿主 $PATH，避免污染
        env.put("PATH", "/usr/bin:/bin:/usr/local/bin");
        Log.i(TAG, "startEngine (proot): " + cmd);
        return pb.start();
    }

    private static String readText(File f) {
        try {
            return new String(IoUtil.readAllBytes(f), "UTF-8").trim();
        } catch (Exception e) {
            return null;
        }
    }

    private static void writeText(File f, String s) {
        try {
            IoUtil.writeAllBytes(f, s.getBytes("UTF-8"));
        } catch (Exception e) {
            Log.w(TAG, "写 proot marker 失败: " + e.getMessage());
        }
    }
}
