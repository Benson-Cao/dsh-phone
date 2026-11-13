package com.dsh.harness;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 三引擎切换层：手机本地引擎 / 多台电脑引擎 / 服务器引擎。
 *
 * <p><b>为什么不直接把 WebView loadUrl 到远程：</b>本项目的 A-02 安全模型规定
 * WebView 只信任 127.0.0.1（{@link DshOrigin#isLocalUrl}），远程 http(s) 一律
 * 丢系统浏览器；{@code official-bridge.js} 也只在本地 origin 才注入。直接换
 * origin 等于同时拆掉 JS 桥纵深防御和整套手机侧栏 UI。
 *
 * <p><b>本层做法：</b>WebView 的 origin 永远是 127.0.0.1。
 * <ul>
 *   <li>本机引擎 → 直连 {@value #LOCAL_URL}（原有路径完全不变，零风险、零额外跳）</li>
 *   <li>电脑/服务器引擎 → 连 {@value #PROXY_URL}，由 App 内 node 反代转发到上游</li>
 * </ul>
 * 两个地址都是 loopback，A-02、Cookie、OfficialShell 注入、手机 UI 全部照常。
 *
 * <p>切换只换「执行者」，不迁移会话：各引擎有自己的 $DSH_HOME 与 append-only
 * session log；跨引擎可见性由上层 NAS 同步负责，与本层无关。
 */
public final class EngineEndpoints {

    private static final String TAG = "EngineEndpoints";
    private static final String PREF = "dsh_engines";
    private static final String KEY_LIST = "list";
    private static final String KEY_CURRENT = "current";

    /** 远程引擎专用的本地反代端口（本机引擎不占用）。 */
    public static final int PROXY_PORT = 3082;
    public static final String LOCAL_URL = "http://127.0.0.1:" + DshBootstrap.DSH_PORT + "/";
    public static final String PROXY_URL = "http://127.0.0.1:" + PROXY_PORT + "/";

    private static volatile String sCurrentUrl = LOCAL_URL;
    private static volatile Process sProxy;
    /** 当前反代进程绑定的上游，用于切换时判断是否需要重建进程。 */
    private static volatile String sProxyUpstream;

    private EngineEndpoints() { }

    public static final class Ep {
        public final String id;
        public final String label;
        public final String url;
        public final String kind;   // local | pc | server
        public final boolean local;

        Ep(String id, String label, String url, String kind, boolean local) {
            this.id = id; this.label = label; this.url = url;
            this.kind = kind; this.local = local;
        }
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    /** 首次启动种一个本机引擎；解析失败退回默认，绝不让 App 起不来。 */
    public static List<Ep> list(Context ctx) {
        List<Ep> out = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(prefs(ctx).getString(KEY_LIST, "[]"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                String url = o.optString("url", "");
                if (url.length() == 0) continue;
                out.add(new Ep(
                    o.optString("id", UUID.randomUUID().toString().substring(0, 8)),
                    o.optString("label", url),
                    url,
                    o.optString("kind", "pc"),
                    o.optBoolean("local", false)));
            }
        } catch (Exception e) {
            Log.w(TAG, "端点配置损坏，重建默认", e);
        }
        if (out.isEmpty()) {
            out.add(new Ep("local", "本机引擎", LOCAL_URL, "local", true));
            save(ctx, out);
            prefs(ctx).edit().putString(KEY_CURRENT, "local").apply();
        }
        return out;
    }

    private static void save(Context ctx, List<Ep> items) {
        JSONArray arr = new JSONArray();
        for (Ep e : items) {
            try {
                arr.put(new JSONObject()
                    .put("id", e.id).put("label", e.label).put("url", e.url)
                    .put("kind", e.kind).put("local", e.local));
            } catch (Exception ignored) { }
        }
        prefs(ctx).edit().putString(KEY_LIST, arr.toString()).apply();
    }

    public static Ep current(Context ctx) {
        String id = prefs(ctx).getString(KEY_CURRENT, "local");
        List<Ep> items = list(ctx);
        for (Ep e : items) if (e.id.equals(id)) return e;
        return items.get(0);
    }

    public static boolean isRemote(Context ctx) {
        return !current(ctx).local;
    }

    /** 供 WebView 使用的 URL；顺带刷新静态缓存给错误页用。 */
    public static String currentUrl(Context ctx) {
        sCurrentUrl = isRemote(ctx) ? PROXY_URL : LOCAL_URL;
        return sCurrentUrl;
    }

    /** 无 Context 场景（DshWebViewClient 的错误页是 static）。 */
    public static String currentUrlStatic() {
        return sCurrentUrl;
    }

    public static Ep select(Context ctx, String id) {
        List<Ep> items = list(ctx);
        Ep found = null;
        for (Ep e : items) if (e.id.equals(id)) { found = e; break; }
        if (found == null) return null;
        prefs(ctx).edit().putString(KEY_CURRENT, id).apply();
        return found;
    }

    public static Ep add(Context ctx, String label, String rawUrl, String kind) {
        String url = normalize(rawUrl);
        if (url == null) return null;
        List<Ep> items = list(ctx);
        for (Ep e : items) if (e.url.equalsIgnoreCase(url)) return null;   // 去重
        String k = "server".equals(kind) ? "server" : "pc";
        Ep ep = new Ep(UUID.randomUUID().toString().substring(0, 8),
            label == null || label.trim().isEmpty() ? url : label.trim(), url, k, false);
        items.add(ep);
        save(ctx, items);
        return ep;
    }

    public static boolean remove(Context ctx, String id) {
        List<Ep> items = list(ctx);
        if (items.size() <= 1) return false;
        boolean changed = false;
        for (int i = items.size() - 1; i >= 0; i--) {
            if (items.get(i).id.equals(id)) { items.remove(i); changed = true; }
        }
        if (!changed) return false;
        save(ctx, items);
        if (id.equals(prefs(ctx).getString(KEY_CURRENT, "local"))) {
            prefs(ctx).edit().putString(KEY_CURRENT, items.get(0).id).apply();
        }
        return true;
    }

    public static String toJson(Context ctx) {
        try {
            JSONArray arr = new JSONArray();
            for (Ep e : list(ctx)) {
                arr.put(new JSONObject().put("id", e.id).put("label", e.label)
                    .put("url", e.url).put("kind", e.kind).put("local", e.local));
            }
            return new JSONObject().put("ok", true).put("endpoints", arr)
                .put("currentId", current(ctx).id)
                .put("webUrl", currentUrl(ctx)).toString();
        } catch (Exception e) {
            return "{\"ok\":false,\"error\":\"" + e.getMessage() + "\"}";
        }
    }

    /** 综合应用当前选择：刷新 URL、写反代上游配置、按需起停 node 反代。 */
    public static void apply(Context ctx) {
        Context app = ctx.getApplicationContext();
        currentUrl(app);
        writeUpstream(app);
        if (isRemote(app)) ensureProxy(app);
        else stopProxy();   // 切回本机时释放 3082，恢复零额外跳
    }

    private static File dshDir(Context ctx) {
        File d = new File(ShellRunner.home(ctx), ".dsh");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    private static File configFile(Context ctx) {
        return new File(dshDir(ctx), "engine-upstream.json");
    }

    /** node 反代用 fs.watch 热读本文件，切换无需重启进程。 */
    private static void writeUpstream(Context ctx) {
        Ep ep = current(ctx);
        String upstream = ep.local ? LOCAL_URL : ep.url;
        try {
            FileOutputStream out = new FileOutputStream(configFile(ctx));
            out.write(("{\"upstream\":" + JSONObject.quote(upstream) + "}\n").getBytes("UTF-8"));
            out.close();
        } catch (Exception e) {
            Log.w(TAG, "写反代上游配置失败", e);
        }
    }

    private static boolean proxyAlive() {
        Process p = sProxy;
        if (p == null) return false;
        try { p.exitValue(); return false; } catch (IllegalThreadStateException e) { return true; }
    }

    public static synchronized void stopProxy() {
        Process p = sProxy;
        if (p != null) {
            try { p.destroy(); } catch (Throwable ignored) { }
        }
        sProxy = null;
        sProxyUpstream = null;
    }

    private static synchronized void ensureProxy(Context ctx) {
        String want = current(ctx).url;
        // 不依赖 fs.watch：上游一变就重建进程，避免 Termux 上文件监听失效
        // 导致切换后仍连着旧引擎（这是最容易被误判成「切换没生效」的坑）。
        if (proxyAlive() && want.equals(sProxyUpstream)) {
            writeUpstream(ctx);
            return;
        }
        stopProxy();
        try {
            File dir = dshDir(ctx);
            File script = new File(dir, "engine-proxy.mjs");
            copyAsset(ctx, "dsh/engine-proxy.mjs", script);
            writeUpstream(ctx);

            File prefix = ShellRunner.prefix(ctx);
            File node = new File(prefix, "bin/node");
            File bash = new File(prefix, "bin/bash");
            String cmd = node.getAbsolutePath() + " " + script.getAbsolutePath()
                + " --port " + PROXY_PORT
                + " --config " + configFile(ctx).getAbsolutePath();
            sProxy = Runtime.getRuntime().exec(
                new String[] { bash.getAbsolutePath(), "-c", cmd },
                ShellRunner.buildEnv(ctx), ctx.getFilesDir());
            sProxyUpstream = want;
            Log.i(TAG, "反代已启动: " + PROXY_PORT + " -> " + want);
        } catch (Exception e) {
            Log.e(TAG, "启动反代失败", e);
        }
    }

    private static void copyAsset(Context ctx, String asset, File dst) throws Exception {
        InputStream in = ctx.getAssets().open(asset);
        FileOutputStream out = new FileOutputStream(dst);
        try {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        } finally {
            in.close(); out.close();
        }
    }

    private static String normalize(String raw) {
        if (raw == null) return null;
        String u = raw.trim();
        if (!u.startsWith("http://") && !u.startsWith("https://")) u = "http://" + u;
        while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        try {
            java.net.URI uri = new java.net.URI(u);
            if (uri.getHost() == null || uri.getHost().isEmpty()) return null;
        } catch (Exception e) {
            return null;
        }
        return u;
    }
}
