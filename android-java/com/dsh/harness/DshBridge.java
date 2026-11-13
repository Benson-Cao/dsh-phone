package com.dsh.harness;

import android.app.Activity;
import android.content.Context;
import android.util.Log;
import android.webkit.JavascriptInterface;

import org.json.JSONObject;

import java.lang.ref.WeakReference;

/**
 * 注入页面的 {@code window.dshNative} —— 原生能力桥。
 *
 * 目前只暴露 dshmarket（DSH 插件市场）安装：
 * 插件必须落到 {@code $HOME/.dsh/profiles/web/node_modules/} 并注册进 profile
 * manifest 的 {@code dsh.profile.bundles}，WebView 里的 JS 没有文件系统权限，
 * 所以只能由原生做。
 *
 * ⚠️ 安全：{@code @JavascriptInterface} 只在**已加载我们信任的页面**时可达
 * （WebView 只 load 127.0.0.1:3081 的 dsh 实例），所以这里不额外做 origin 校验。
 * 若将来要加载远程页面，必须补上域名白名单。
 *
 * ⚠️ 生命周期（C-04 / U12 修复）：
 * 早期这里持有 Activity 的**强引用**，WebView 销毁 / Activity 重建时桥对象若被
 * JS 侧长驻引用，会拖住整个 Activity 造成泄漏；同时后台线程在 Activity 已
 * finish 后仍 {@code runOnUiThread} 回调，可能落到已死的 UI 上。
 * 改为 {@link WeakReference}：Activity 可回收；所有回调先取活着的 Activity，
 * 取不到或正在销毁就放弃本次调用，既不泄漏也不丢到死 UI。
 */
public final class DshBridge {

    private static final String TAG = "DshBridge";
    private final WeakReference<Activity> actRef;
    /** Application Context 不持有 Activity，供纯读判断使用（可长期持有）。 */
    private final Context appCtx;

    public DshBridge(Activity act) {
        this.actRef = new WeakReference<>(act);
        this.appCtx = act.getApplicationContext();
    }

    /**
     * 取出仍存活的 Activity；若已回收 / 正在销毁 / 已销毁，返回 {@code null}。
     * 所有需要 Activity 上下文或 UI 线程的入口都先过这一关。
     */
    private Activity act() {
        Activity a = actRef.get();
        if (a == null) return null;
        if (a.isFinishing() || a.isDestroyed()) return null;
        return a;
    }

    /** 已安装则返回版本号，否则返回空串。同步、极快。 */
    @JavascriptInterface
    public String marketInstalled() {
        // r72（P3-7）：JS 侧每 500ms 轮询市场状态，每次都跨线程读文件+正则解析。
        //   2s TTL 缓存把 IO 降到 1/4，轮询成本可忽略；安装完成后的徽标刷新
        //   最晚自愈延迟 2s（500ms 轮询本身就有幂等补回，无感）。
        long now = System.currentTimeMillis();
        String c = sMktCache;
        if (c != null && now - sMktCacheAt < STATE_TTL_MS) return c;
        String v = "";
        Activity a = act();
        if (a != null) {
            try {
                String r = DshMarketInstaller.installedVersion(a);
                v = (r == null) ? "" : r;
            } catch (Throwable e) {
                v = "";
            }
        }
        sMktCache = v;
        sMktCacheAt = now;
        return v;
    }

    /** pnpm 是否已就绪（市场装插件依赖它）。 */
    @JavascriptInterface
    public String pnpmReady() {
        long now = System.currentTimeMillis();
        String c = sPnpmCache;
        if (c != null && now - sPnpmCacheAt < STATE_TTL_MS) return c;
        String v = "";
        Activity a = act();
        if (a != null) {
            try {
                v = DshMarketInstaller.pnpmReady(a) ? "1" : "";
            } catch (Throwable e) {
                v = "";
            }
        }
        sPnpmCache = v;
        sPnpmCacheAt = now;
        return v;
    }

    private static final long STATE_TTL_MS = 2000L;
    private static volatile String sMktCache = null;
    private static volatile long sMktCacheAt = 0L;
    private static volatile String sPnpmCache = null;
    private static volatile long sPnpmCacheAt = 0L;

    /**
     * 打开原生 App 设置页（若该分支提供）。
     *
     * <p>dsh-phone 是纯 Java 工程，没有 Compose 设置页；原实现直接引用
     * {@code com.dsh.harness.mobile.SettingsActivity}，会让 javac 在编译期就失败。
     * 这里改为按类名反射启动：类存在则打开，不存在则静默降级（官方网页设置仍可用）。
     *
     * <p>调用点：{@code assets/dsh/official-bridge.js} 拦下官方侧栏「设置」入口后调用。
     */
    @JavascriptInterface
    public void openAppSettings() {
        final Activity a = act();
        if (a == null) return;
        a.runOnUiThread(new Runnable() {
            @Override public void run() {
                try {
                    Class<?> cls = Class.forName("com.dsh.harness.mobile.SettingsActivity");
                    android.content.Intent it = new android.content.Intent(a, cls);
                    a.startActivity(it);
                    a.overridePendingTransition(
                        com.termux.R.anim.slide_in_right, com.termux.R.anim.slide_out_left);
                } catch (ClassNotFoundException e) {
                    // 本工程没有 Compose 设置页：交给官方网页设置处理，不算错误。
                    Log.i(TAG, "无原生设置页，回落到网页设置");
                } catch (Throwable t) {
                    Log.w(TAG, "打开原生设置页失败: " + t.getMessage());
                }
            }
        });
    }

    /**
     * r65：注入脚本上报"当前这一级返回该由 JS 处理"（设置面板 / 抽屉这类
     * <b>覆盖层</b>开着）。
     *
     * <p>为什么需要它：覆盖层是 SPA 内的 DOM，不改变 URL、也不产生 WebView
     * 历史项，原生侧 {@code canGoBack()} 完全看不见它们。没有这个上报，
     * {@code MainActivity.handleBack()} 只能靠 canGoBack() 猜 —— 猜错就是
     * "面板开着时按返回直接退出 App"。
     *
     * <p>只在 JS 侧状态**变化**时被调用（JS 里做了去重），同步、无 IO。
     */
    @JavascriptInterface
    public void setBackHandled(boolean handled) {
        Activity a = actRef.get();   // 这里刻意不用 act()：只是写个标志位，不碰 UI
        if (a instanceof MainActivity) {
            ((MainActivity) a).setJsBackHandled(handled);
        }
    }

    /**
     * v30.38：页面持续上报自己的真实深浅色（不再只在 onPageFinished 探一次）。
     *
     * <p>为什么需要：dsh 主题是 {@code ui-theme.preference}（跟随系统/浅色/深色），
     * 用户在原生设置页改完后返回，WebView 不会 reload —— 而
     * {@code DshWebViewClient.probeTheme()} 只在加载完成时跑那几次，于是系统栏
     * 一直停在旧颜色（真机实测：dsh 已深色、状态栏仍是浅色，顶部一条白边）。
     *
     * <p>由 {@code official-bridge.js} 每 700ms 采样一次，**只有变化时**才过桥
     * （JS 侧已去重），因此这里不会产生高频 IO。
     */
    @JavascriptInterface
    public void applyDshTheme(final boolean dark) {
        final Activity a = actRef.get();
        if (!(a instanceof MainActivity)) { return; }
        a.runOnUiThread(new Runnable() {
            @Override public void run() {
                try {
                    ((MainActivity) a).applyDshTheme(dark);
                } catch (Throwable t) {
                    Log.w(TAG, "应用 dsh 主题失败: " + t.getMessage());
                }
            }
        });
    }

    /**
     * r214：把「此刻页面**应该**是深色吗」直接交给 JS。
     *
     * <p>为什么需要：真机实测（19:03）系统已是深色（uiMode=UI_MODE_NIGHT_YES），
     * 但 dsh 主 WebView 里的 `matchMedia('(prefers-color-scheme: dark)').matches`
     * 返回 **false**（同一个进程里的 about:blank WebView 却返回 true）。dsh 的
     * `ui-theme.preference = system` 于是被解析成浅色 → 整页白，用户报的
     * 「首页深色模式失效」就是这个，跟我们注入的 CSS 无关。
     *
     * <p>判据与 dsh 一致：显式 dark/light 优先，其余（system / 未读到）跟随系统
     * uiMode。只读内存缓存，同步且无 IO —— JS 侧每 500ms 会调一次。
     */
    @JavascriptInterface
    public boolean dshShouldBeDark() {
        // dsh-phone 无 Compose 设置层，读不到 ui-theme.preference 的内存缓存
        // （原实现走 mobile.data.DshSettingsStore.INSTANCE.peekThemePreference）。
        // 缓存缺失时按 dsh 默认值「跟随系统」处理，与原实现的降级语义一致。
        Context c = (appCtx != null) ? appCtx : actRef.get();
        return c != null && DshPalette.systemIsDark(c);
    }

    /**
     * A-02 纵深防御：确认 WebView **此刻**仍停在本地 dsh 上，才允许执行
     * 「下载远程 tarball 并由 node 执行」这类高危操作。
     *
     * <p>⚠️ 这里刻意取**两个**来源，任一能确认本地即放行：
     * <ol>
     *   <li>{@link DshWebViewClient#currentUrl()} —— 由页面加载回调在 UI 线程写入，
     *       桥这边只是 volatile 读，跨线程安全；</li>
     *   <li>{@code webView.getUrl()} —— 覆盖 SPA 内的即时状态，但它属于 UI 线程侧
     *       API，从 JavaBridge 线程调用<b>实测会返回 null</b>（r56 真机 bug 的根因）。</li>
     * </ol>
     * 两者都拿不到才拒绝。
     *
     * <p>放宽到「任一为本地」<b>不降低安全性</b>：远程导航已被
     * {@code shouldOverrideUrlLoading} 拦截并丢给系统浏览器，页面永远留在本地，
     * 攻击者无法把 WebView 变成远程页面（真正的残余风险是 dsh 自身 XSS 在
     * 本地页面里调桥，那本来就挡不住，这里只是纵深防御）。
     */
    private boolean localOriginAllowed(String api) {
        Activity a = act();
        if (a == null) {
            Log.w(TAG, api + " 拒绝：Activity 已失效");
            return false;
        }
        android.webkit.WebView w = null;
        try {
            w = ((MainActivity) a).webViewRef();
        } catch (Throwable t) {
            // ignore：拿不到就只靠 trackedUrl
        }
        String tracked = DshWebViewClient.currentUrl();
        String direct = null;
        if (w != null) {
            try {
                direct = w.getUrl();
            } catch (Throwable t) {
                // 跨线程可能抛，忽略即可
            }
        }
        if (DshOrigin.isLocalUrl(tracked) || DshOrigin.isLocalUrl(direct)) {
            return true;
        }
        Log.w(TAG, api + " 拒绝：tracked=" + tracked + " direct=" + direct
                + " webView=" + (w == null ? "null" : "ok"));
        return false;
    }

    /** 统一的「拒绝执行」回执：把原因送回 JS，避免卡片永久卡在「安装中…」。 */
    private void notifyRejected(String jsFn, String callbackId, String msg) {
        final Activity ua = act();
        if (ua == null) return;
        ua.runOnUiThread(new Runnable() {
            @Override public void run() {
                try {
                    JSONObject o = new JSONObject();
                    o.put("ok", false);
                    o.put("message", msg);
                    final String js = "window." + jsFn + "&&window." + jsFn + "("
                        + JSONObject.quote(callbackId) + "," + o.toString() + ");";
                    android.webkit.WebView w = ((MainActivity) ua).webViewRef();
                    if (w != null) w.evaluateJavascript(js, null);
                } catch (Exception e) {
                    Log.e(TAG, "拒绝回执失败", e);
                }
            }
        });
    }

    /**
     * r72（P2-1）：Activity 已死时的**尽力回执** —— 原来直接 return，JS 侧的
     * busy 标志（pnpmBusy/installing）永久为 true，之后所有安装点击静默无响应。
     * WebView 可能仍在销毁中，试一把；不行还有 JS 侧的 120s 超时兑底。
     */
    private void notifyRejectedBestEffort(final String jsFn, final String callbackId, final String msg) {
        try {
            Activity a = actRef.get();   // 不做 isFinishing 闸 —— 尽力而为
            if (a == null) return;
            a.runOnUiThread(new Runnable() {
                @Override public void run() {
                    try {
                        JSONObject o = new JSONObject();
                        o.put("ok", false);
                        o.put("message", msg);
                        final String js = "window." + jsFn + "&&window." + jsFn + "("
                            + JSONObject.quote(callbackId) + "," + o.toString() + ");";
                        android.webkit.WebView w = (a instanceof MainActivity)
                            ? ((MainActivity) a).webViewRef() : null;
                        if (w != null) w.evaluateJavascript(js, null);
                    } catch (Throwable ignored) { }
                }
            });
        } catch (Throwable ignored) { }
    }

    /**
     * 安装 pnpm（后台线程，完成后回调 JS）。
     * 真机上市场会报"找不到 npm/corepack…请单独装一个 pnpm"，就是这个。
     */
    @JavascriptInterface
    public void installPnpm(final String callbackId) {
        new Thread(new Runnable() {
            @Override public void run() {
                final Activity a = act();
                if (a == null) {
                    // r72（P2-1）：Activity 已死也要尽力回执，别把 JS 的 busy 标志卡死
                    notifyRejectedBestEffort("__dshPnpmDone", callbackId, "拒绝：应用正在退出，请重试");
                    return;
                }
                if (!localOriginAllowed("installPnpm")) {
                    notifyRejected("__dshPnpmDone", callbackId, "拒绝：当前页面不是本地 dsh");
                    return;
                }
                String err;
                try {
                    err = DshMarketInstaller.installPnpm(a);
                } catch (Throwable e) {
                    Log.e(TAG, "安装 pnpm 异常", e);
                    err = "安装异常: " + e.getMessage();
                }
                final String result = err;
                final boolean ok = (result == null);
                final String msg = ok ? "pnpm 安装完成" : result;
                Log.i(TAG, "pnpm 安装" + (ok ? "成功" : "失败: " + result));
                final Activity ui = act();
                if (ui == null) return;  // 回 UI 前再校验一次
                ui.runOnUiThread(new Runnable() {
                    @Override public void run() {
                        try {
                            JSONObject o = new JSONObject();
                            o.put("ok", ok);
                            o.put("message", msg);
                            final String js = "window.__dshPnpmDone&&window.__dshPnpmDone("
                                + JSONObject.quote(callbackId) + "," + o.toString() + ");";
                            android.webkit.WebView w = ((MainActivity) ui).webViewRef();
                            if (w != null) w.evaluateJavascript(js, null);
                        } catch (Exception e) {
                            Log.e(TAG, "回调 JS 失败", e);
                        }
                    }
                });
            }
        }, "dsh-pnpm-install").start();
    }

    /**
     * 拉起安装（后台线程执行，完成后回调 JS）。
     * JS 侧传入的 callbackId 会被 {@code window.__dshMarketDone(id, ok, msg)} 接收。
     */
    @JavascriptInterface
    public void installMarket(final String callbackId) {
        new Thread(new Runnable() {
            @Override public void run() {
                final Activity a = act();
                if (a == null) {
                    // r72（P2-1）：同 installPnpm —— 尽力回执，防 JS busy 卡死
                    notifyRejectedBestEffort("__dshMarketDone", callbackId, "拒绝：应用正在退出，请重试");
                    return;
                }
                // A-02 纵深防御：远程 tarball + node 执行，只允许在本地 dsh 上发起
                if (!localOriginAllowed("installMarket")) {
                    notifyRejected("__dshMarketDone", callbackId, "拒绝：当前页面不是本地 dsh");
                    return;
                }
                String err;
                try {
                    err = DshMarketInstaller.install(a);
                } catch (Throwable e) {
                    Log.e(TAG, "安装 dshmarket 异常", e);
                    err = "安装异常: " + e.getMessage();
                }
                final String result = err;
                final boolean ok = (result == null);
                final String msg = ok ? "安装完成，重启应用后生效" : result;
                final String ver = ok ? DshMarketInstaller.installedVersion(a) : "";
                if (ok) Log.i(TAG, "dshmarket 安装成功 " + ver);
                else Log.e(TAG, "dshmarket 安装失败: " + err);
                final Activity ui = act();
                if (ui == null) return;  // 回 UI 前再校验一次
                ui.runOnUiThread(new Runnable() {
                    @Override public void run() {
                        try {
                            JSONObject o = new JSONObject();
                            o.put("ok", ok);
                            o.put("message", msg);
                            o.put("version", ver == null ? "" : ver);
                            final String js = "window.__dshMarketDone&&window.__dshMarketDone("
                                + JSONObject.quote(callbackId) + "," + o.toString() + ");";
                            android.webkit.WebView w = ((MainActivity) ui).webViewRef();
                            if (w != null) w.evaluateJavascript(js, null);
                        } catch (Exception e) {
                            Log.e(TAG, "回调 JS 失败", e);
                        }
                    }
                });
            }
        }, "dsh-market-install").start();
    }

    // ===== 三引擎切换（手机本地 / 多台电脑 / 服务器）=====
    // WebView 的 origin 始终是 127.0.0.1，所以这里不扩大 JS 桥的信任面：
    // 远程引擎由 App 内 3082 本地反代承载，页面依旧是本地页面。

    /** 返回端点列表 JSON，供 engine-switcher.js 渲染。 */
    @JavascriptInterface
    public String engineList() {
        return EngineEndpoints.toJson(appCtx);
    }

    @JavascriptInterface
    public String engineAdd(String json) {
        try {
            JSONObject o = new JSONObject(json);
            EngineEndpoints.Ep ep = EngineEndpoints.add(appCtx,
                o.optString("label", ""), o.optString("url", ""), o.optString("kind", "pc"));
            if (ep == null) return "{\"ok\":false,\"error\":\"URL 无效或已存在\"}";
            return "{\"ok\":true}";
        } catch (Exception e) {
            return "{\"ok\":false,\"error\":\"" + e.getMessage() + "\"}";
        }
    }

    @JavascriptInterface
    public String engineRemove(String id) {
        return EngineEndpoints.remove(appCtx, id)
            ? "{\"ok\":true}"
            : "{\"ok\":false,\"error\":\"至少保留一个端点\"}";
    }

    /** 异步切引擎：写反代上游 → 等 3082 就绪 → 回主线程让 WebView 重新 loadUrl。 */
    @JavascriptInterface
    public String engineSwitch(final String id) {
        if (EngineEndpoints.select(appCtx, id) == null) {
            return "{\"ok\":false,\"error\":\"端点不存在\"}";
        }
        new Thread(new Runnable() {
            @Override public void run() {
                EngineEndpoints.apply(appCtx);
                if (EngineEndpoints.isRemote(appCtx)) {
                    for (int i = 0; i < 30; i++) {
                        if (DshProcessManager.isPortOpen(EngineEndpoints.PROXY_PORT)) break;
                        try { Thread.sleep(100); } catch (Exception ignored) { }
                    }
                }
                final Activity a = act();
                if (a == null) return;
                a.runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (a instanceof MainActivity) ((MainActivity) a).reloadForEngine();
                    }
                });
            }
        }, "dsh-engine-switch").start();
        return "{\"ok\":true}";
    }
}
