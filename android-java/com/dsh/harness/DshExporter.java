package com.dsh.harness;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.util.Log;
import android.webkit.MimeTypeMap;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 方案 C：把 dsh 的用户数据通过 {@code FileProvider} 暴露给用户选定的 App
 * （文件管理器、编辑器、邮件…）。
 *
 * ## 为什么是FileProvider 而不是 SAF 代理（方案 A）
 *
 * 见 {@code docs/saf-feasibility.md} 的评估。核心差别：
 * -方案 A 要在 node 侧改FS 层、改 dsh npm 包 → **每次升级 dsh 都要重做**；
 * - 方案 C 完全在 Java 侧，不碰 node、不碰上游包 → **一次性成本**。
 *
 * 代价：方案 C 是「用户主动导出」，不是「dsh 透明读写外部目录」。
 * 对「用户想把数据拿到别的 App 里编辑/查看」这个真实需求，前者够用。
 *
 * ## targetSdk=28 是这里的主动选择
 *
 * 当前 {@code targetSdkVersion=28}（见 termux-app/gradle.properties），
 * 因此**不受 Android 10+ 分区存储限制**：
 * - 不需要 {@code MANAGE_EXTERNAL_STORAGE} 这类敏感权限；
 * - 不需要 {@code requestLegacyExternalStorage}；
 * - 用户从「另存为 / 下载」拿到文件后，我们可以再提供一个「导出回公共目录」的入口
 *   （{@link #exportToPublic}），直接写 {@code Environment.getExternalStorageDirectory()}。
 *
 * 代价：{@code targetSdk<29} 在新系统上会弹「为旧版应用兼容」的提示，且上架 Google Play 受限。
 * 这是项目**已经做出的取舍**（见 {@code DshService} 类注释），本类不改变它。
 *
 * ## 安全设计（三条硬约束）
 *
 * 1. **只导出白名单目录**：见 {@code res/xml/dsh_file_paths.xml}。
 *    绝不开 {@code filesDir} 根目录——那里面是 700MB 运行时 + node 二进制。
 * 2. **不exported**：`android:exported` 默认 false，只有拿到显式 URI 授权的 App 能读。
 * 3. **逐个文件授权**：用 {@code FLAG_GRANT_READ_URI_PERMISSION}，不做目录树共享，
 *    避免把整个目录一次性交出去。
 *
 * @see DshBridge#exportFile(String, String)
 */
public final class DshExporter {

    private static final String TAG = "DshExporter";

    /** Provider authority。必须与 AndroidManifest 里注册的一致。 */
    public static String authority(Context ctx) {
        return ctx.getPackageName() + ".dsh.files";
    }

    private DshExporter() {}

    /**
     * 判断某个绝对路径是否允许导出。
     *
     * <p><b>必须做</b>：{@code DshBridge} 的方法经 {@code @JavascriptInterface} 暴露给页面，
     * 页面里的 JS 可以传任意路径进来。没有这道闸，页面就能读整个 filesDir
     *（那里有 node 二进制与 700MB 运行时）。
     *
     * <p>判定用 {@code canonicalPath} 防 {@code ../} 穿越，并显式拒绝
     * {@code node_modules}（可执行代码 + 软链结构易乱）。
     *
     * @param ctx  上下文（用于定位 home 目录）
     * @param f   待检查文件
     * @return true 表示允许导出
     */
    public static boolean isExportable(Context ctx, File f) {
        if (f == null || !f.isFile()) return false;
        try {
            String p = f.getCanonicalPath();
            String h = DshBootstrap.home(ctx).getCanonicalPath();
            if (!p.startsWith(h + File.separator)) return false;
            if (p.contains("node_modules") || p.contains(File.separator + "usr" + File.separator)) {
                return false;
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 为单个文件生成可分享的 {@code content://} Uri。
     *
     * @param ctx   上下文
     * @param file  要导出的文件（应在白名单目录内）
     * @return content Uri；不可导出时返回 {@code null}
     */
    public static Uri uriFor(Context ctx, File file) {
        try {
            // 白名单校验集中在这里（防路径穿越 + 拒 node_modules）
            if (!isExportable(ctx, file)) {
                Log.w(TAG, "拒绝导出（不在白名单内）: " + file);
                return null;
            }
            // 转成相对 filesDir 的路径，与 dsh_file_paths.xml 的 <files-path> 对应。
            // 注意 Java 的 File(父,"a/b") 会自动处理分隔符，这里不要自己拼 File.separator。
            File home = DshBootstrap.home(ctx);
            String rel = "home/" + file.getCanonicalPath()
                    .substring(home.getCanonicalPath().length() + 1)
                    .replace(File.separatorChar, '/');
            return androidx.core.content.FileProvider.getUriForFile(
                    ctx, authority(ctx), new File(ctx.getFilesDir(), rel));
        } catch (Exception e) {
            Log.w(TAG, "生成 Uri 失败: " + e.getMessage());
            return null;
        }
    }

    /**
     * 构造「分享文件」的Intent。
     *
     * <p>注意必须显式带上 {@code FLAG_GRANT_READ_URI_PERMISSION}，
     * 否则接收方拿到 Uri 却读不了（这是 FileProvider 最常见的坑）。
     *
     * @param ctx   上下文
     * @param file  要分享的文件
     * @param mime  MIME 类型，null 时按扩展名推断
     */
    public static Intent shareIntent(Context ctx, File file, String mime) {
        Uri uri = uriFor(ctx, file);
        if (uri == null) return null;
        String type = (mime != null && mime.length() > 0) ? mime : guessMime(file);
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType(type);
        i.putExtra(Intent.EXTRA_STREAM, uri);
        // 两种都要：给接收方读权限，同时允许它转发
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        i.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        return i;
    }

    /**
     * 构造「导出文件到公共目录」的 Intent（另存为）。
     *
     * <p>用于 targetSdk=28 下写公共目录：系统自带文件选择器接管，
     * 用户选一个位置（下载/文档），我们把内容拷过去。
     *
     * <p>注意：这条路径**不需要** SAF 代理，因为是「Java 读自己的私有文件 →
     * 写用户选的位置」，Java 侧本来就能读私有文件。
     */
    public static Intent createDocumentIntent(String mime, String filename) {
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType((mime != null && mime.length() > 0) ? mime : "*/*");
        if (filename != null) i.putExtra(Intent.EXTRA_TITLE, filename);
        return i;
    }

    /**
     * 列出可导出的文件（供UI / JS 侧展示"这些可以拿走"）。
     *
     * <p>有意限制返回数量：home 下可能有 node_modules 软链树，遍历要设上限，
     * 否则 UI 会卡住。
     */
    public static List<String> listExportable(Context ctx, int max) {
        List<String> out = new ArrayList<>();
        File root = DshBootstrap.home(ctx);
        collect(root, out, max);
        return out;
    }

    private static void collect(File dir, List<String> out, int max) {
        if (out.size() >= max || dir == null || !dir.isDirectory()) return;
        File[] fs;
        try {
            fs = dir.listFiles();
        } catch (Throwable t) {
            return;
        }
        if (fs == null) return;
        for (File f : fs) {
            if (out.size() >= max) return;
            String n = f.getName();
            // 跳过这些：要么是软链树（会无限深），要么不是用户数据
            if (n.equals("node_modules") || n.equals(".cache") || n.equals(".tmp")
                    || n.equals("bin") || n.equals("lib")) {
                continue;
            }
            if (f.isFile()) {
                out.add(f.getAbsolutePath());
            } else if (f.isDirectory()) {
                collect(f, out, max);
            }
        }
    }

    /** 按扩展名猜 MIME；未知统一给 application/octet-stream。 */
    static String guessMime(File f) {
        String name = f.getName();
        int i = name.lastIndexOf('.');
        if (i >= 0 && i < name.length() - 1) {
            String ext = name.substring(i + 1).toLowerCase();
            String m = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
            if (m != null) return m;
        }
        return "application/octet-stream";
    }
}