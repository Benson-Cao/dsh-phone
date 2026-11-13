package com.dsh.harness;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.webkit.WebSettings;
import android.webkit.WebView;

/**
 * r163 方案 B：常驻预加载 WebView —— 把「点图标 → 首页可交互」压到最短。
 *
 * ## 思路
 * DshService 常驻保活已经让引擎「永远在线」，剩下的等待只有：
 *   ① WebView 渲染进程冷启动（1~3s）② dsh 前端 HTML/JS/CSS 加载与初始化（1~2s）。
 * 既然 Service 反正活着，就让它**提前**把这两步做完：
 *   引擎就绪 → Service 在主线程创建 WebView（渲染进程就位）→ 塞好 dsh-auth
 *   cookie → loadUrl 首页。等用户点图标时，首页已经加载完了。
 *
 * MainActivity 的 {@code prepareWebView()} 发现预热可用就直接「领养」这个
 * WebView（重设绑 Activity 的 ChromeClient/WebViewClient/JS 桥），不再
 * loadUrl（页面已在），交叉淡入直接显示完整首页 —— 0 等待。
 *
 * ## 关键设计
 * - **同一进程**：Service 与 Activity 同进程（Manifest 未配 process），
 *   静态引用直接共享；CookieManager 是进程级单例，cookie 两侧一致。
 * - **领养时重绑**：预热阶段不设 WebChromeClient（附件选择器需要 Activity
 *   的 startActivityForResult，Service 里没有）；领养后由 MainActivity
 *   补设全套回调。addJavascriptInterface 同名覆盖，桥对象切换无痕。
 * - **生命周期 = 进程**：预热 WebView 用 applicationContext 创建、常驻不销毁
 *   （这是特性不是泄漏）；只有引擎重启（{@link #invalidate}）才销毁重建。
 * - **幂等**：多次调用 {@link #ensureStarted} 只预热一次；领养后不可再领。
 *
 * ## 失效路径（全部兜底回旧行为）
 * 预热未完成 / 已被领养 / 引擎重启中 → {@code isAvailable()=false}，
 * MainActivity 走原来的「自己建 WebView + loadUrl」路径，零回归风险。
 */
public final class DshPreloadWeb {

    private static final String TAG = "DshPreloadWeb";
    private static final String HOME_URL_PREFIX = "http://127.0.0.1:";

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    /** 预热的 WebView（主线程创建与销毁；引擎在跑时它加载着完整首页）。 */
    private static WebView sWeb;

    /** 预热状态机：0=idle 1=starting 2=loaded。volatile：工作线程读、主线程写。 */
    private static volatile int sState = 0;

    /** 是否已被 MainActivity 领养（领养后 Service 不再管它的死活）。 */
    private static volatile boolean sAdopted = false;

    /** 引擎纪元：每次要求重启引擎（bootstrap/start 分支）+1，与引擎重启同步作废预热。 */
    private static volatile long sEpoch = 0;

    /** r216：用户选定「启动时回收」——置位后本进程不再预热 / 归还常驻 WebView。 */
    private static volatile boolean sDiscardOnLaunch = false;

    private DshPreloadWeb() { }

    /** 引擎就绪后由 {@link DshService} 调用（任意线程）。幂等；已领养/已预热则跳过。 */
    public static void ensureStarted(final Context appCtx) {
        if (sDiscardOnLaunch || sState != 0 || sAdopted) return;
        sState = 1;
        final long myEpoch = sEpoch;
        // 探测就绪在工作线程；创建/加载 WebView 必须主线程。
        new Thread(new Runnable() {
            @Override public void run() {
                final long t0 = System.currentTimeMillis();
                final long deadline = t0 + 120000;
                String cookie = null;
                while (System.currentTimeMillis() < deadline) {
                    if (myEpoch != sEpoch) return;          // 引擎重启了，本次预热作废
                    DshProbe.Ready r = DshProbe.tryReady(appCtx);
                    if (r != null) { cookie = r.cookiePair; break; }
                    try { Thread.sleep(300); } catch (InterruptedException e) { return; }
                }
                if (cookie == null) {
                    Log.w(TAG, "预热放弃：120s 内引擎未就绪");
                    sState = 0;
                    return;
                }
                final String ck = cookie;
                MAIN.post(new Runnable() {
                    @Override public void run() {
                        try {
                            startOnMain(appCtx, ck, myEpoch);
                        } catch (Throwable t) {
                            Log.e(TAG, "预热失败（回退 Activity 自建路径）", t);
                            sState = 0;
                        }
                    }
                });
            }
        }, "dsh-preload-probe").start();
    }

    /** 主线程：创建 WebView、写 cookie、loadUrl 首页。 */
    private static void startOnMain(Context appCtx, String cookiePair, long myEpoch) {
        // 启动已决定自建 WebView：这次预热直接作废，别再造一个孤立实例出来。
        if (sDiscardOnLaunch || myEpoch != sEpoch || sState != 1) { sState = 0; return; }
        WebView w = new WebView(appCtx);
        WebSettings st = w.getSettings();
        st.setJavaScriptEnabled(true);
        st.setDomStorageEnabled(true);
        st.setAllowFileAccess(false);
        st.setAllowContentAccess(false);
        st.setAllowFileAccessFromFileURLs(false);
        st.setAllowUniversalAccessFromFileURLs(false);
        st.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        android.webkit.CookieManager.getInstance().setAcceptCookie(true);
        if (cookiePair != null) {
            android.webkit.CookieManager cm = android.webkit.CookieManager.getInstance();
            cm.setCookie(EngineEndpoints.currentUrl(appCtx), cookiePair);
            cm.flush();
        }
        // ⚠️ 故意不设 WebChromeClient / WebViewClient / JS 桥 ——
        //   它们都绑 Activity（startActivityForResult、openAppSettings 回程等），
        //   领养时由 MainActivity 重设。预热阶段页面只是"自己跑起来"。
        w.setBackgroundColor(0xFF141B23);
        // r199：预热必须加载与 Activity 相同的引擎地址。原来恒为 3080，
        // 远程引擎时 Activity 走 3082 反代，预热的首页就成了「另一个端口的页面」。
        w.loadUrl(EngineEndpoints.currentUrl(appCtx));
        sWeb = w;
        sState = 2;
        Log.i(TAG, "预热完成：首页已在常驻 WebView 中加载（引擎就绪后即触发）");
    }

    /** MainActivity 可领养？（预热完成且未被领养） */
    public static boolean isAvailable() {
        return sState == 2 && !sAdopted && sWeb != null;
    }

    /** 领养后由 MainActivity 查询：本次 WebView 是否来自预热（决定要不要再 loadUrl）。 */
    public static boolean adopted() {
        return sAdopted;
    }

    /**
     * MainActivity 领养（必须在主线程调用）。领养后所有权移交：销毁策略随
     * Activity 场景由调用方决定（本类不再动它）。返回预热的 WebView。
     */
    public static WebView adopt() {
        WebView w = sWeb;
        sAdopted = true;
        sWeb = null;
        sState = 0;
        Log.i(TAG, "预热 WebView 已被 MainActivity 领养");
        return w;
    }

    /**
     * r216：Activity 销毁时**回收**领养的 WebView，不再归还复用（用户选定方案）。
     *
     * <p>原来「归还」是为了下次点图标秒回上次首页，但真机 CDP 实测留下了两个
     * 同源 target：常驻预热实例从没设 WebViewClient，{@code official-bridge.js}
     * 从未注入（裸页面：浅色、38x38 原 fab、没有主题兜底），而且它的 uiMode 停在
     * 上一轮配置。系统切到夜间后，这个旧页面就是一团白色残留。
     * 现在销毁它，下次启动由 Activity 用**当前**配置新建 WebView，页面全新、
     * 桥一定注入。代价是失去 r163 的「预热直入 0 等待」，换来干净一致。
     * 必须主线程调用（销毁 post 到主线程）。
     */
    public static void release(final WebView w) {
        if (w == null) return;
        sWeb = null;
        sAdopted = false;
        sState = 0;
        MAIN.post(new Runnable() {
            @Override public void run() {
                try { w.destroy(); } catch (Throwable ignored) { }
            }
        });
        Log.i(TAG, "领养 WebView 已回收（销毁，不再归还复用）");
    }

    /**
     * r216：MainActivity 启动时调用 —— 把常驻预热 WebView 整个回收掉。
     *
     * <p>三件事一次做完：① 置 sDiscardOnLaunch，让 Service 之后不再起预热线程、
     * 也不再接受迟到的 startOnMain；② epoch++ 作废正在探测的那次；
     * ③ 销毁已经预热好但没被领养的实例。之后 Activity 走自建 WebView 路径，
     * 进程里始终只有一个 WebView、一个 CDP target。
     *
     * <p>⚠️ 若此刻已被领养（理论上 onCreate 阶段不会），只清状态、绝不销毁
     * Activity 正在显示的那个。
     */
    public static void discardOnLaunch() {
        sDiscardOnLaunch = true;
        sEpoch++;
        if (sAdopted) { sState = 0; return; }
        final WebView w = sWeb;
        sWeb = null;
        sState = 0;
        if (w != null) {
            MAIN.post(new Runnable() {
                @Override public void run() {
                    try { w.destroy(); } catch (Throwable ignored) { }
                }
            });
        }
        Log.i(TAG, "启动时回收：预热 WebView 已销毁，本次进程不再复用旧页面");
    }

    /**
     * 引擎要重启（bootstrap/start 分支）或服务销毁时调用：作废预热。
     * 若 WebView 已被领养则**不能** destroy（Activity 正在显示），
     * 只清状态；未领养才安全销毁。必须在主线程调（本类内部已保证 post）。
     */
    public static void invalidate() {
        sEpoch++;
        if (sAdopted) { sState = 0; return; }
        final WebView w = sWeb;
        sWeb = null;
        sState = 0;
        if (w != null) {
            MAIN.post(new Runnable() {
                @Override public void run() {
                    try { w.destroy(); } catch (Throwable ignored) { }
                }
            });
        }
        Log.i(TAG, "预热已作废（epoch=" + sEpoch + "）");
    }
}
