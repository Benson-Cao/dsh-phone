package com.dsh.harness;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.util.Log;

import org.json.JSONObject;

import java.io.File;

/**
 * 方案 A 的授权与挂载配置（对应 {@code docs/saf-feasibility.md} §3「授权信息持久化」）。
 *
 * ## 存两份、各管一段
 *
 * <ul>
 *   <li><b>SharedPreferences</b>（{@code dsh_saf}）：Java 侧的真相源。存树 Uri 与显示名，
 *       Uri 是 {@code Parcelable} 级的东西，不适合进 JSON。</li>
 *   <li><b>{@code $HOME/.dsh/saf_config.json}</b>：给 node 侧看的那一份（挂载点 + 代理地址 + token）。
 *       走文件而不是环境变量，是为了「用户中途才授权」时<b>不必重启 dsh</b> ——
 *       环境变量只在进程启动时注入一次，而重启 dsh 要 8s（{@code DshService} 注释）。
 *       shim 侧按 mtime 做秒级缓存，所以文件读写不是热路径。</li>
 * </ul>
 *
 * ## 为什么挂载点固定成 {@code $HOME/saf}
 *
 * node 侧靠「路径前缀」判断要不要走代理，前缀必须是编译期可知的常量。
 * 让用户自选挂载点等于把判断逻辑搬到运行期，收益为零、复杂度翻倍。
 * 这个目录是<b>空的真实目录</b>（见 {@link #ensureMountDir}）：它的存在只是为了让
 * dsh 的目录浏览能看到并选中它，实际内容全部由 {@link SafTree} 从 content Uri 提供。
 */
public final class SafConfig {

    private static final String TAG = "SafConfig";
    private static final String PREFS = "dsh_saf";
    private static final String K_URI = "tree_uri";
    private static final String K_NAME = "display_name";

    /** 挂载目录名（挂在 $HOME 下）。改名会连带改 node shim 的路径判定，勿动。 */
    public static final String MOUNT_NAME = "saf";

    private SafConfig() {}

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** 挂载点绝对路径，如 {@code /data/user/0/com.dsh.harness/files/home/saf}。 */
    public static File mountDir(Context ctx) {
        return new File(DshBootstrap.home(ctx), MOUNT_NAME);
    }

    /** node 侧读取的配置文件。 */
    public static File configFile(Context ctx) {
        return new File(DshBootstrap.home(ctx), ".dsh/saf_config.json");
    }

    public static Uri treeUri(Context ctx) {
        String s = prefs(ctx).getString(K_URI, null);
        if (s == null || s.length() == 0) return null;
        try {
            return Uri.parse(s);
        } catch (Throwable t) {
            Log.w(TAG, "树 Uri 解析失败: " + s);
            return null;
        }
    }

    public static String displayName(Context ctx) {
        String n = prefs(ctx).getString(K_NAME, null);
        return (n == null || n.length() == 0) ? "存储目录" : n;
    }

    public static boolean isConfigured(Context ctx) {
        return treeUri(ctx) != null && hasPersistedPermission(ctx);
    }

    /**
     * 授权是否仍然握在手里。
     *
     * <p>{@code takePersistableUriPermission} 的授权跨进程重启有效，但有两个失效途径：
     * 用户去设置里撤权、或目标 App（文件管理器）被卸载 / 数据被清。
     * 失效后所有 {@code ContentResolver} 调用都会抛 {@code SecurityException}，
     * 与其让它炸在运行中，不如在这里先认怂、让 UI 提示重新授权。
     */
    public static boolean hasPersistedPermission(Context ctx) {
        Uri tree = treeUri(ctx);
        if (tree == null) return false;
        try {
            for (android.content.UriPermission p
                    : ctx.getContentResolver().getPersistedUriPermissions()) {
                if (tree.equals(p.getUri())) return true;
            }
        } catch (Throwable t) {
            Log.w(TAG, "读取持久化授权失败: " + t.getMessage());
            return true;   // 查不到不武断判失效（部分 ROM 该 API 异常），交由实际读写去暴露
        }
        return false;
    }

    /** 保存用户选中的目录树，并立刻刷新 node 侧配置。 */
    public static synchronized void save(Context ctx, Uri tree, String name) {
        prefs(ctx).edit()
            .putString(K_URI, tree == null ? null : tree.toString())
            .putString(K_NAME, name)
            .apply();
        ensureMountDir(ctx);
    }

    public static synchronized void clear(Context ctx) {
        Uri tree = treeUri(ctx);
        if (tree != null) {
            try {
                ctx.getContentResolver().releasePersistableUriPermission(tree,
                    Intent_GRANT_RW);
            } catch (Throwable t) {
                Log.w(TAG, "释放授权失败（可忽略）: " + t.getMessage());
            }
        }
        prefs(ctx).edit().remove(K_URI).remove(K_NAME).apply();
    }

    private static final int Intent_GRANT_RW =
        android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
        | android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION;

    /**
     * 建出（空的）挂载目录。
     *
     * <p>它<b>不是</b>数据真正存放的地方，只是给 dsh 的目录选择器一个可点中的入口 ——
     * 否则 {@code $HOME} 下根本看不到 {@code saf}，用户无从选择。
     */
    public static void ensureMountDir(Context ctx) {
        try {
            File d = mountDir(ctx);
            if (!d.isDirectory() && !d.mkdirs()) {
                Log.w(TAG, "挂载目录创建失败: " + d);
            }
        } catch (Throwable t) {
            Log.w(TAG, "挂载目录创建异常: " + t.getMessage());
        }
    }

    /**
     * 写出 node 侧配置。代理端口/token 由 {@link SafProxyServer} 提供。
     *
     * @return 成功写出返回 true；未配置或代理未运行时也会写出 {@code enabled:false}，
     *         目的是让 shim 明确地「不走代理」，而不是拿着过期 token 去撞。
     */
    public static synchronized boolean writeRuntimeConfig(Context ctx) {
        try {
            File f = configFile(ctx);
            File dir = f.getParentFile();
            if (dir != null && !dir.isDirectory() && !dir.mkdirs()) {
                Log.w(TAG, "配置目录创建失败: " + dir);
                return false;
            }
            JSONObject o = new JSONObject();
            boolean on = SafConfig.isConfigured(ctx) && SafProxyServer.isRunning();
            o.put("enabled", on);
            o.put("mount", mountDir(ctx).getAbsolutePath());
            o.put("url", on ? SafProxyServer.url() : "");
            o.put("token", on ? SafProxyServer.token() : "");
            o.put("name", displayName(ctx));
            IoUtil.writeAllBytes(f, o.toString().getBytes("UTF-8"));
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "写 saf_config.json 失败: " + t.getMessage());
            return false;
        }
    }

    /** 给页面（{@code DshSafBridge.safStatus}）用的状态 JSON。 */
    public static String statusJson(Context ctx, boolean granted) {
        try {
            JSONObject o = new JSONObject();
            o.put("configured", SafConfig.isConfigured(ctx));
            o.put("name", displayName(ctx));
            o.put("mount", mountDir(ctx).getAbsolutePath());
            o.put("running", SafProxyServer.isRunning());
            o.put("port", SafProxyServer.port());
            o.put("permission", granted ? "granted" : "missing");
            return o.toString();
        } catch (Throwable t) {
            return "{}";
        }
    }
}
