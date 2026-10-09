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
 * （WebView 只 load 127.0.0.1:3080 的 dsh 实例），所以这里不额外做 origin 校验。
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

    public DshBridge(Activity act) {
        this.actRef = new WeakReference<>(act);
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
        Activity a = act();
        if (a == null) return "";
        try {
            String v = DshMarketInstaller.installedVersion(a);
            return v == null ? "" : v;
        } catch (Throwable e) {
            return "";
        }
    }

    /** pnpm 是否已就绪（市场装插件依赖它）。 */
    @JavascriptInterface
    public String pnpmReady() {
        Activity a = act();
        if (a == null) return "";
        try {
            return DshMarketInstaller.pnpmReady(a) ? "1" : "";
        } catch (Throwable e) {
            return "";
        }
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
     * 安装 pnpm（后台线程，完成后回调 JS）。
     * 真机上市场会报"找不到 npm/corepack…请单独装一个 pnpm"，就是这个。
     */
    @JavascriptInterface
    public void installPnpm(final String callbackId) {
        new Thread(new Runnable() {
            @Override public void run() {
                final Activity a = act();
                if (a == null) return;   // Activity 已死，放弃，避免泄漏/回调丢失
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
                if (a == null) return;   // Activity 已死，放弃
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
}
