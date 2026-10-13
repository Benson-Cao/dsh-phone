package com.dsh.harness;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.util.Log;
import android.webkit.JavascriptInterface;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.ref.WeakReference;
import java.util.List;

/**
 * 方案 C 的页面入口：把 dsh 的用户数据导出给用户选定的外部 App。
 *
 * ## 与 {@link DshBridge} 的分工
 *
 * {@code DshBridge} 已有完整的生命周期与安全约定（WeakReference、{@code act()}、
 * {@code localOriginAllowed} 纵深防御），但它的方法是围绕「插件市场」写的。
 * 本类只管导出，逻辑独立，避免把「市场安装」和「文件导出」两件不相干的事
 * 混在一个桥里（{@code DshBridge} 已经300+ 行了）。
 *
 * ## 三条安全约束（与 {@link DshExporter} 一致，不重复论证）
 *
 * 1. {@link DshExporter#isExportable} 白名单 —— 挡住 {@code ../} 穿越与 node_modules；
 * 2. {@link #localOriginAllowed} —— 只有页面仍停在本地 dsh 时才执行导出；
 * 3. Provider 默认 {@code exported=false}，Uri 只逐个文件授权。
 *
 * @see DshExporter
 * @see docs/saf-feasibility.md
 */
public final class DshSafBridge {

    private static final String TAG = "DshSafBridge";

    private final WeakReference<Activity> actRef;

    public DshSafBridge(Activity act) {
        this.actRef = new WeakReference<>(act);
    }

    private Activity act() {
        Activity a = actRef.get();
        if (a == null) return null;
        if (a.isFinishing() || a.isDestroyed()) return null;
        return a;
    }

    /**
     * 纵深防御：与 {@code DshBridge.localOriginAllowed} 同思路 ——
     * 导出属于「把数据交出去」的高危操作，必须确认 WebView 此刻仍停在本地 dsh。
     */
    private boolean localOriginAllowed(String api) {
        Activity a = act();
        if (a == null) {
            Log.w(TAG, api + " 拒绝：Activity 已失效");
            return false;
        }
        // 与 DshBridge.localOriginAllowed 同一套双来源判定：
        //   tracked = 页面加载回调在 UI 线程写入的 URL（跨线程安全）
        //   direct  = webView.getUrl()，但从 JS 桥线程读会返回 null（r56 真机 bug 根因）
        // 任一能确认本地即放行。
        String tracked = DshWebViewClient.currentUrl();
        String direct = null;
        android.webkit.WebView w = null;
        try {
            w = ((MainActivity) a).webViewRef();
            if (w != null) direct = w.getUrl();
        } catch (Throwable t) {
            // ignore
        }
        if (DshOrigin.isLocalUrl(tracked) || DshOrigin.isLocalUrl(direct)) {
            return true;
        }
        Log.w(TAG, api + " 拒绝：tracked=" + tracked + " direct=" + direct);
        return false;
    }

    /**
     * 列出当前可导出的文件（JSON 数组字符串）。
     *
     * <p>页面用它渲染「这些数据可以拿走」的列表。只返回 {@code home} 下的普通文件，
     * 不含 node_modules（有数量上限，见 {@link DshExporter#listExportable}）。
     */
    @JavascriptInterface
    public String listExportable() {
        if (!localOriginAllowed("listExportable")) return "[]";
        Activity a = act();
        if (a == null) return "[]";
        try {
            List<String> paths = DshExporter.listExportable(a, 200);
            JSONArray arr = new JSONArray();
            for (String p : paths) {
                File f = new File(p);
                JSONObject o = new JSONObject();
                o.put("path", p);
                o.put("name", f.getName());
                o.put("size", f.length());
                arr.put(o);
            }
            return arr.toString();
        } catch (Throwable e) {
            Log.w(TAG, "listExportable 失败: " + e.getMessage());
            return "[]";
        }
    }

    /**
     * 用系统「分享」面板导出单个文件。
     *
     * @param path 文件绝对路径（须在白名单内）
     * @return "ok" / "denied" / "nofile"
     */
    @JavascriptInterface
    public String shareFile(final String path) {
        final Activity a = act();
        if (a == null || !localOriginAllowed("shareFile")) return "denied";
        final File f = new File(path);
        if (!f.isFile()) return "nofile";
        if (!DshExporter.isExportable(a, f)) {
            Log.w(TAG, "shareFile 拒绝（白名单外）: " + path);
            return "denied";
        }
        a.runOnUiThread(new Runnable() {
            @Override public void run() {
                try {
                    Intent i = DshExporter.shareIntent(a, f, null);
                    if (i == null) return;
                    // 必须用 createChooser：直接 startActivity 传 ACTION_SEND
                    // 在部分机型上没有接收 App 会直接崩。
                    a.startActivity(Intent.createChooser(i, "导出 " + f.getName()));
                } catch (Throwable e) {
                    Log.w(TAG, "分享失败: " + e.getMessage());
                }
            }
        });
        return "ok";
    }

    /**
     * 导出到用户选定的新位置（另存为）。
     *
     * <p>返回请求码，页面可据此提示「请在弹出的保存界面选位置」。
     * 真正写入在 {@link MainActivity} 的 onActivityResult 里完成
     * （{@link #completeSave}）。
     */
    @JavascriptInterface
    public int saveAs(String path, String mime) {
        final Activity a = act();
        if (a == null || !localOriginAllowed("saveAs")) return -1;
        final File f = new File(path);
        if (!f.isFile()) return -1;
        if (!DshExporter.isExportable(a, f)) return -1;
        a.runOnUiThread(new Runnable() {
            @Override public void run() {
                try {
                    Intent i = DshExporter.createDocumentIntent(mime, f.getName());
                    if (!(a instanceof MainActivity)) return;
                    ((MainActivity) a).startSaveAs(f, i);
                } catch (Throwable e) {
                    Log.w(TAG, "saveAs 失败: " + e.getMessage());
                }
            }
        });
        return MainActivity.REQ_SAVE_AS;
    }

    /**
     * 写入 {@code ACTION_CREATE_DOCUMENT} 返回的目标 Uri。
     *
     * <p>由 {@link MainActivity#onActivityResult} 调用（主线程）。
     *
     * @param src  被导出的源文件
     * @param dst  目标 content Uri
     * @return 成功 true
     */
    public static boolean completeSave(Context ctx, File src, Uri dst) {
        if (ctx == null || src == null || dst == null || !src.isFile()) return false;
        InputStream in = null;
        OutputStream out = null;
        try {
            in = new FileInputStream(src);
            // ⚠️ ContentResolver 是抽象类，必须经 Context 拿，不能 new。
            // "wt" = truncate，缺它会把新内容接在旧内容后面（写一半的文件）。
            out = ctx.getContentResolver().openOutputStream(dst, "wt");
            if (in == null || out == null) return false;
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            out.flush();
            return true;
        } catch (Throwable e) {
            Log.w(TAG, "写入目标文件失败: " + e.getMessage());
            return false;
        } finally {
            try { if (in != null) in.close(); } catch (Throwable ignored) { }
            try { if (out != null) out.close(); } catch (Throwable ignored) { }
        }
    }

    // ============================================================
    // r75 · NAS 同步（把会话/工作区数据同步到飞牛 NAS）
    //
    // 同样受localOriginAllowed 保护：同步会把本地数据传出去，属高危操作。
    // 网络 IO 一律丢后台线程 —— @JavascriptInterface 方法跑在 WebView 的
    // JavaBridge 线程上，阻塞它会让整个页面的 JS 卡死。
    // ============================================================

    /** 当前同步配置（不含密码）的 JSON，供页面渲染表单。 */
    @JavascriptInterface
    public String syncConfig() {
        Activity a = act();
        if (a == null) return "{}";
        try {
            JSONObject o = new JSONObject();
            o.put("configured", DshSync.isConfigured(a));
            o.put("url", DshSync.url(a));
            o.put("user", DshSync.user(a));
            o.put("interval", DshSync.intervalSec(a));
            o.put("device", DshSync.deviceTag());
            return o.toString();
        } catch (Throwable e) {
            return "{}";
        }
    }

    /**
     * 保存同步配置。
     *
     * @param url      WebDAV 地址，如 http://192.168.1.10:5005
     * @param user     NAS 账号
     * @param pass     NAS 密码（传空串表示"不改动已存密码"）
     * @param interval 间隔秒数，内部强制下限 30s
     * @return "ok" / "denied"
     */
    @JavascriptInterface
    public String saveSyncConfig(final String url, final String user, final String pass,
            final int interval) {
        final Activity a = act();
        if (a == null || !localOriginAllowed("saveSyncConfig")) return "denied";
        if (url == null || url.trim().length() == 0) return "denied";
        if (!(url.trim().startsWith("http://") || url.trim().startsWith("https://"))) {
            return "badurl";
        }
        // 空密码 = 保留原密码（避免页面回显密码时把它抹掉）
        String oldPass = "";
        try {
            oldPass = a.getApplicationContext().getSharedPreferences("dsh_sync",
                    Context.MODE_PRIVATE).getString("pass", "");
        } catch (Throwable ignored) { }
        String usePass = (pass == null || pass.length() == 0) ? oldPass : pass;
        DshSync.configure(a, url, user, usePass, interval);
        // 配置完立刻同步一次：用户不该干等一个周期才发现配错了
        runSyncAsync(a, true);
        return "ok";
    }

    /** 立即同步一次；异步执行，结果通过 {@link #syncLastResult()} 查。 */
    @JavascriptInterface
    public String syncNow() {
        final Activity a = act();
        if (a == null || !localOriginAllowed("syncNow")) return "denied";
        if (!DshSync.isConfigured(a)) return "unconfigured";
        runSyncAsync(a, true);
        return "started";
    }

    private void runSyncAsync(final Activity a, final boolean manual) {
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    DshSync.Result r = DshSync.syncOnce(a);
                    Log.i(TAG, "同步(" + (manual ? "手动" : "自动") + "): "
                            + r.message + " ↑" + r.uploaded + " ↓" + r.downloaded
                            + " 冲突" + r.conflicts);
                } catch (Throwable e) {
                    Log.w(TAG, "同步线程异常: " + e.getMessage());
                }
            }
        }, "dsh-sync-manual");
        t.setDaemon(true);
        t.start();
    }

    /** 最近一次同步结果（JSON），页面轮询它显示状态。 */
    @JavascriptInterface
    public String syncLastResult() {
        Activity a = act();
        if (a == null) return "{}";
        try {
            return DshSync.lastResultJson(a);
        } catch (Throwable e) {
            return "{}";
        }
    }

    // ============================================================
    // 方案 A · 存储目录（把用户授权的外部目录挂到 $HOME/saf）
    //
    // 与上面的「导出」是两件事：导出是**用户主动把文件交出去**（一次性动作），
    // 存储目录是**让 dsh 透明读写外部目录**（长期配置）。所以入口分开，
    // 但同受 localOriginAllowed 保护 —— 后者等于把整个目录的读写权交出去。
    // 详见 docs/saf-feasibility.md §7。
    // ============================================================

    /** 当前状态（JSON）：是否配置、目录名、挂载点、代理是否活着。 */
    @JavascriptInterface
    public String safStatus() {
        Activity a = act();
        if (a == null) return "{}";
        return SafConfig.statusJson(a, SafConfig.hasPersistedPermission(a));
    }

    /**
     * 打开系统目录选择器。返回请求码，页面可据此提示「请在弹出的界面里选目录」。
     *
     * @return {@link MainActivity#REQ_SAF_DIR}；不可用时 -1
     */
    @JavascriptInterface
    public int safPickDir() {
        final Activity a = act();
        if (a == null || !localOriginAllowed("safPickDir")) return -1;
        if (!(a instanceof MainActivity)) return -1;
        a.runOnUiThread(new Runnable() {
            @Override public void run() {
                ((MainActivity) a).startSafPick();
            }
        });
        return MainActivity.REQ_SAF_DIR;
    }

    /** 取消挂载并释放授权。 */
    @JavascriptInterface
    public String safClear() {
        Activity a = act();
        if (a == null || !localOriginAllowed("safClear")) return "denied";
        SafConfig.clear(a);
        SafProxyServer.stop();
        SafConfig.writeRuntimeConfig(a);
        return "ok";
    }

    /**
     * 端到端自检：先探代理（HTTP + token），再在挂载点下写/读/删一个临时文件。
     *
     * <p>两步都做是因为它们的失败模式不同：代理不通是服务没起来/token 不对；
     * 读写不通是授权失效或 provider 不支持写。只报一个"失败"没法排障。
     */
    @JavascriptInterface
    public String safSelfTest() {
        final Activity a = act();
        if (a == null || !localOriginAllowed("safSelfTest")) return safFail("denied");
        try {
            long t0 = System.currentTimeMillis();
            JSONObject o = new JSONObject();
            o.put("proxy", pingProxy());
            String rel = ".dsh-saf-selftest.txt";
            String payload = "dsh-saf-ok-" + t0;
            boolean wrote = SafTree.write(a, rel, payload.getBytes("UTF-8"));
            if (!wrote) { o.put("ok", false); o.put("err", "写入失败（授权可能已失效）"); return o.toString(); }
            byte[] back = SafTree.read(a, rel);
            boolean same = back != null && payload.equals(new String(back, "UTF-8"));
            SafTree.delete(a, rel);
            o.put("ok", same);
            o.put("ms", System.currentTimeMillis() - t0);
            o.put("read", back == null ? "(null)" : new String(back, "UTF-8"));
            return o.toString();
        } catch (Throwable t) {
            return safFail(t.getMessage());
        }
    }

    /** 探一次代理（带 token），验证 HTTP + 鉴权这一环。 */
    private boolean pingProxy() {
        if (!SafProxyServer.isRunning()) return false;
        java.net.HttpURLConnection c = null;
        try {
            c = (java.net.HttpURLConnection)
                new java.net.URL(SafProxyServer.url() + "/ping").openConnection();
            c.setRequestMethod("GET");
            c.setRequestProperty("x-dsh-token", SafProxyServer.token());
            c.setConnectTimeout(3000);
            c.setReadTimeout(3000);
            return c.getResponseCode() == 200;
        } catch (Throwable t) {
            Log.w(TAG, "代理自检失败: " + t.getMessage());
            return false;
        } finally {
            if (c != null) try { c.disconnect(); } catch (Throwable ignored) { }
        }
    }

    private static String safFail(String msg) {
        try {
            JSONObject o = new JSONObject();
            o.put("ok", false);
            o.put("err", msg == null ? "unknown" : msg);
            return o.toString();
        } catch (Throwable t) {
            return "{\"ok\":false}";
        }
    }
}