package com.dsh.harness;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.webkit.CookieManager;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;

/**
 * WebView 客户端：内外链分流 + 页面就绪后注入手机适配补丁 + 友好错误页。
 * 127.0.0.1 / localhost 留在 WebView 内，其余外链丢给系统浏览器。
 */
public class DshWebViewClient extends WebViewClient {

    /**
     * dsh 服务根地址（r72/P2-7：错误页「重试」需要直达它，而不是 reload 错误文档）。
     * v2.3 批 B：按档案取 —— local 档维持引擎语义（可能是 3082 反代），
     * relay/ssh 档用档案自己的地址。
     */
    private static String dshBaseUrl(Context ctx) {
        HostProfile.Profile p = HostProfile.get(ctx).current();
        if (p.needsLocalEngine()) {
            return EngineEndpoints.currentUrl(ctx);
        }
        return p.loadUrl();
    }

    /**
     * v2.3 批 B：本页是否属于当前档案的可信页（回环 或 当前档案 origin）。
     * history 清理 / 注入 / 重试计数都以它为界；判定失败一律 false。
     */
    private static boolean isTrustedPage(WebView view, String url) {
        if (url == null) return false;
        if (DshOrigin.isLocalUrl(url)) return true;
        try {
            return DshOrigin.sameOrigin(url,
                    HostProfile.get(view.getContext().getApplicationContext()).current().baseOrigin());
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * r72（P2-7）：错误页改为由方法生成 —— 原来「重试」是 location.reload()，
     * 但页面是 loadDataWithBaseURL(null,…) 的 about:blank 文档，重载的是错误
     * 文档本身，永远不会真正重试 dsh 服务。现在重试直接 location.href 回 dsh 根。
     */
    /**
     * 内嵌占位页的配色（黑白单色，与原生启动页同一套灰阶）。
     *
     * <p>⚠️ 这几个页面是**字符串拼出来的 HTML**，跑在 WebView 里：拿不到 CSS 变量、
     *    也拿不到 {@link DshUi} 的 drawable，只能按深浅色各给一组字面色值。
     *    因此必须**显式传 dark**，不能像早年那样"默认浅色"—— 深色模式下给白底，
     *    「正在连接」会闪一下白，比原生启动页的深色底刺眼得多。
     *
     * <p>按钮走单色稿的「反白块」规则：底色 = 文字色，字色 = 页面底色。
     * 浅色下是黑底白字，深色下自动变成白底黑字，不需要第二套判断。
     */
    private static String pageBg(boolean dark)  { return dark ? "#151517" : "#FFFFFF"; }
    private static String pageInk(boolean dark) { return dark ? "#F4F4F5" : "#18181B"; }
    private static String pageMute(boolean dark){ return dark ? "#A1A1AA" : "#52525B"; }

    private static String errorHtml(String baseUrl, String title, String body, boolean dark) {
        String safeBase = baseUrl == null || baseUrl.isEmpty()
                ? "http://127.0.0.1:" + DshProcessManager.DSH_PORT : baseUrl;
        return "<html data-dsh-error=\"1\"><body style='font-family:sans-serif;padding:24px;"
            + "background:" + pageBg(dark) + ";color:" + pageInk(dark) + "'>"
            + "<h2>" + title + "</h2>"
            + "<p style='color:" + pageMute(dark) + "'>" + body + "</p>"
            + "<button onclick='location.href=\"" + safeBase + "\"' style='margin-top:12px;padding:8px 16px;"
            + "border:0;border-radius:8px;background:" + pageInk(dark) + ";color:" + pageBg(dark)
            + ";font-size:14px;cursor:pointer'>重试</button>"
            + "</body></html>";
    }

    private static String serverNotReadyHtml(String baseUrl, boolean dark) {
        return errorHtml(baseUrl, "服务还没就绪",
            "dsh 后台可能仍在启动，或已退出。请稍候刷新，或检查运行日志。", dark);
    }

    private static String authExpiredHtml(String baseUrl, boolean dark) {
        // r72（P3-4）：401（鉴权过期）与“服务未就绪”分开提示，避免误导排障方向
        return errorHtml(baseUrl, "需要重新登录",
            "dsh 的登录状态已过期（401）。请完全退出应用后重新打开，应用会重新获取登录凭证。", dark);
    }

    /**
     * r199：主帧失败后的占位页，用来盖住 Chromium 自带的
     * 「网页无法打开 … net::ERR_CONNECTION_REFUSED」原生错误页。
     *
     * <p>自动重试（r198）虽然最终能恢复，但失败那一瞬 WebView 会先把系统错误页
     * 渲染出来，用户看到的就是「启动闪一下报错」。这里在第一次失败时立刻塞一个
     * 与启动页同风格的轻量文档，重试成功后被 loadUrl 覆盖；
     * {@link #onPageFinished} 的 clearHistory 会剪掉这段 data: 历史。
     *
     * <p><b>r138 起按设计稿 p7「连接中」重做</b>：不再是「一句话 + 一根呼吸条」，
     * 而是「主机名 + 三步进度 + 预计落点」。理由是这一屏在慢网络上会停留十几秒，
     * 一句「请稍候」在这里等于没有信息 —— 用户既不知道在做哪一步，
     * 也不知道连的是哪台机器（多档案下这是最常见的困惑）。
     */
    private static String connectingHtml(Context ctx, int attempt) {
        boolean dark = DshPalette.isDark(ctx);
        String ink = hex(DshUi.ink(ctx));
        String ink2 = hex(DshUi.ink2(ctx));
        String label = hex(DshUi.label(ctx));
        String card = hex(DshUi.card(ctx));
        String subtle = hex(DshUi.subtle(ctx));
        String line = hex(DshUi.line(ctx));
        String page = hex(DshUi.pageBg(ctx));

        HostProfile.Profile p;
        try {
            p = HostProfile.get(ctx).current();
        } catch (Throwable t) {
            p = null;
        }
        String path = p == null ? "local" : p.kind;
        String host = esc(hostLabel(p));
        String target = p == null ? "—"
                : HostProfile.KIND_SSH.equals(p.kind)
                    ? esc("127.0.0.1:" + p.localPort + " → " + p.sshHost + ":" + p.sshTargetPort)
                    : HostProfile.KIND_LOCAL.equals(p.kind)
                        ? esc("127.0.0.1:" + DshProcessManager.DSH_PORT)
                        : esc(p.origin == null ? "—" : p.origin);
        // 第二步的说明按档案分：写死「Relay 反代」会让 ssh / local 档看到不存在的东西。
        String step2 = HostProfile.KIND_SSH.equals(path)
                ? "SSH 隧道转发到电脑侧端口"
                : HostProfile.KIND_LOCAL.equals(path)
                    ? "本机 dsh 引擎就绪探测"
                    : "Relay 反代到该 Host 的 dsh web";
        String tries = attempt > 0 ? " · 第 " + (attempt + 1) + " 次" : "";

        return "<html><head><meta name='viewport' content='width=device-width,initial-scale=1'>"
            + "<style>"
            + "html,body{margin:0;background:" + page + ";color:" + ink + ";"
            + "font-family:system-ui,-apple-system,'Segoe UI',sans-serif;"
            + "padding:10px 16px 18px;box-sizing:border-box}"
            + ".top{display:flex;align-items:center;gap:9px;margin-bottom:6px}"
            + ".x{width:26px;height:26px;border-radius:13px;border:1px solid " + line + ";"
            + "color:" + label + ";font-size:15px;line-height:24px;text-align:center}"
            + ".tt{font-size:14px;font-weight:700}"
            + ".hero{text-align:center;padding:14px 0 12px}"
            + ".logo{width:66px;height:66px;margin:0 auto;border-radius:21px;background:" + card
            + ";border:1px solid " + line + ";font-size:30px;line-height:64px;color:" + ink
            + ";animation:br 1.4s ease-in-out infinite}"
            + "@keyframes br{0%{opacity:.55}50%{opacity:1}100%{opacity:.55}}"
            + ".h2{font-size:17px;font-weight:700;margin-top:13px}"
            + ".sub{font-size:11px;color:" + label + ";margin-top:5px}"
            + ".card{background:" + card + ";border:1px solid " + line + ";border-radius:12px;"
            + "overflow:hidden;margin-top:12px}"
            + ".pst{display:flex;align-items:center;gap:11px;padding:10px 12px;"
            + "border-bottom:1px solid " + line + "}"
            + ".pst:last-child{border-bottom:0}"
            + ".pn{width:22px;height:22px;flex:0 0 22px;border-radius:4px;background:" + subtle
            + ";color:" + label + ";font-size:11px;font-weight:700;text-align:center;line-height:22px;"
            + "font-family:ui-monospace,Menlo,monospace}"
            + ".done .pn{color:" + ink + "}"
            + ".now .pn{background:" + ink + ";color:" + page + "}"
            + ".pt{font-size:12px;font-weight:700}"
            + ".ps{font-size:10px;color:" + label + ";margin-top:2px}"
            + ".pstat{margin-left:auto;font-size:10px;font-weight:700;color:" + label + ";"
            + "font-family:ui-monospace,Menlo,monospace}"
            + ".done .pstat,.now .pstat{color:" + ink + "}"
            + ".now .pstat{text-decoration:underline}"
            + ".sec{font-size:10.5px;font-weight:700;letter-spacing:.7px;color:" + label
            + ";margin:16px 0 7px;text-transform:none}"
            + ".diag{background:" + subtle + ";border:1px solid " + line + ";border-radius:8px;"
            + "padding:8px 11px;font-size:10px;line-height:1.7;color:" + label + ";"
            + "font-family:ui-monospace,Menlo,monospace;word-break:break-all}"
            + ".diag span{color:" + ink + ";font-weight:700}"
            + ".info{background:" + card + ";border:1px solid " + line + ";border-radius:8px;"
            + "padding:10px 12px;font-size:11px;line-height:1.6;color:" + ink2 + ";margin-top:9px}"
            + ".info b{color:" + ink + "}"
            + ".foot{margin-top:14px;font-size:10.5px;color:" + label + ";text-align:center}"
            + "</style></head><body>"
            + "<div class='top'><div class='x'>×</div><div class='tt'>正在连接</div></div>"
            + "<div class='hero'><div class='logo'>⌁</div>"
            + "<div class='h2'>" + host + "</div>"
            + "<div class='sub'>" + esc(path) + " · 正在建立链路" + tries + "</div></div>"
            + "<div class='card'>"
            + "<div class='pst done'><div class='pn'>✓</div><div>"
            + "<div class='pt'>身份校验</div>"
            + "<div class='ps'>配对凭据 / 指纹已钉住</div></div>"
            + "<div class='pstat'>完成</div></div>"
            + "<div class='pst now'><div class='pn'>2</div><div>"
            + "<div class='pt'>建立传输链路</div>"
            + "<div class='ps'>" + step2 + "</div></div>"
            + "<div class='pstat'>进行中</div></div>"
            + "<div class='pst'><div class='pn'>3</div><div>"
            + "<div class='pt'>加载官方界面</div>"
            + "<div class='ps'>390px 窄屏冒烟 · 不注入任何皮肤</div></div>"
            + "<div class='pstat'>等待</div></div>"
            + "</div>"
            + "<div class='sec'>预计落点</div>"
            + "<div class='diag'>activePath <span>" + esc(path) + "</span> · 目标 <span>"
            + target + "</span><br>超时后自动放弃这一轮，可返回上一层取消</div>"
            + "<div class='info'>连上后打开的是<b>官方 dsh web</b>，会话内容来自这台电脑。</div>"
            + "<div class='foot'>首次加载需要几秒，请稍候</div>"
            + "</body></html>";
    }

    /** 颜色转 CSS 十六进制：直接借用 DshUi 的明暗令牌，别在这类页面里再写一份调色板。 */
    private static String hex(int color) {
        return String.format(java.util.Locale.US, "#%06X", 0xFFFFFF & color);
    }

    /** 主机显示名：relay 用 hostId，ssh 用 user@host，local 说「这台手机」。 */
    private static String hostLabel(HostProfile.Profile p) {
        if (p == null) return "这台电脑";
        if (HostProfile.KIND_LOCAL.equals(p.kind)) return "这台手机";
        if (HostProfile.KIND_SSH.equals(p.kind)) return p.sshUser + "@" + p.sshHost;
        if (p.hostId != null && p.hostId.length() > 0) return p.hostId;
        return (p.origin == null || p.origin.isEmpty()) ? "这台电脑" : p.origin;
    }

    /** 极简 HTML 转义：主机名/地址来自用户输入，拼进文档前必须过一遍。 */
    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /**
     * A-02 修复用：当前 WebView 正在加载的页面 URL。
     *
     * <p>⚠️ 为什么不直接用 {@code webView.getUrl()}：
     * {@code @JavascriptInterface} 的方法跑在 WebView 的 <b>JavaBridge 线程</b>上，
     * 而 {@code getUrl()} 是 UI 线程侧 API —— 跨线程调用<b>实测返回 null</b>。
     * <p>r56 真机验证暴露了这个 bug：在设置面板点「一键安装」/「安装 pnpm」
     * 一律弹「拒绝：当前页面不是本地 dsh」，而截图里页面明明是 127.0.0.1。
     * 原因就是桥侧拿到 null，被 fail-safe 判成「非本地」→ 误杀正常功能。
     *
     * <p>所以改成：由页面加载回调（<b>在 UI 线程</b>）把 URL 记到这里，
     * 桥那边只做一次 volatile 读 —— 纯内存操作，跨线程安全。
     *
     * <p>注意 SPA 的 pushState <b>不触发</b> onPageStarted/Finished，
     * 所以这里记的是「最后一次整页导航的 URL」。但这恰好够用：
     * 本地页面之间互相跳转时它仍是本地 URL，判定照常通过。
     */
    private static volatile String sCurrentUrl = "";

    /** 当前页面 URL；空串表示尚未加载任何页面。 */
    public static String currentUrl() {
        return sCurrentUrl;
    }

    private static void noteUrl(String url) {
        sCurrentUrl = (url == null) ? "" : url;
    }

    /**
     * 整页导航开始 —— 这里是**唯一**能拿到「整页导航 URL」的地方。
     *
     * ⚠️ 签名里那个 {@code Bitmap favicon} 不是笔误：{@code WebViewClient}
     * **没有** {@code onPageStarted(WebView,String,WebResourceRequest)} 这个重载。
     * 我曾以为有，写上去直接编译失败：
     *   「方法不会覆盖父类方法」+「WebResourceRequest 无法转换为 Bitmap」
     * 实测 compileSdk=36 的 android.jar 里 onPageStarted 只有 Bitmap 版。
     * 它虽已 @Deprecated，但**仍会被 WebView 在所有 API 级别调用**，且没有替代品
     * （onPageFinished 只有 (WebView,String) 一个版本）。
     *
     * 对比：{@code shouldOverrideUrlLoading} 才有 request 版与 String 版**两个**重载，
     * 那两个都必须实现（见上）。
     */
    @Override
    @SuppressWarnings("deprecation")
    public void onPageStarted(WebView view, String url, Bitmap favicon) {
        noteUrl(url);
        // 不调 super：父类该实现为空，且已废弃
    }

    @Override
    public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
        if (request == null || request.getUrl() == null) return false;
        return handleUrl(view, request.getUrl().toString());
    }

    /**
     * API 24 以下**不会**回调上面那个 WebResourceRequest 版本，只走这个 String 重载。
     * minSdk=21，两个都必须实现 —— 否则老系统（5.0~7.0）上所有外链会直接
     * inside WebView 加载，等于把远程页面放进已注入 dshNative 桥的 WebView 里，
     * 正是 A-02 要堵的面。
     */
    @Override
    public boolean shouldOverrideUrlLoading(WebView view, String url) {
        if (url == null) return false;
        return handleUrl(view, url);
    }

    /**
     * A-02 兜底：本地源留在 WebView 内，远程 http(s) 丢系统浏览器，
     * 非 http(s)（tel:/mailto:/intent: 等）交回系统默认处理。
     * 判定统一走 {@link DshOrigin}（大小写、IPv6、userinfo 欺骗都归它管）。
     */
    private boolean handleUrl(WebView view, String url) {
        if (DshOrigin.isLocalUrl(url)) return false;
        // v2.3 批 B：当前档案自己的 origin 也算可信源（relay 档是远端 origin）。
        // 判定失败时落不到这里 → 继续按外链处理（fail-safe）。
        try {
            if (DshOrigin.sameOrigin(url,
                    HostProfile.get(view.getContext().getApplicationContext()).current().baseOrigin())) {
                return false;
            }
        } catch (Throwable ignored) { }
        Uri u = Uri.parse(url);
        String scheme = u.getScheme();
        if (scheme == null) return true;          // 判不出协议 → 拦
        scheme = scheme.toLowerCase(java.util.Locale.ROOT);
        if ("http".equals(scheme) || "https".equals(scheme)) {
            try {
                view.getContext().startActivity(new Intent(Intent.ACTION_VIEW, u));
            } catch (Exception ignored) { }
            return true;
        }
        return false;
    }

    // ==================================================================
    // r198：主帧加载失败自动重试 —— 根治冷启「服务还没就绪」
    // ==================================================================
    /**
     * <p><b>为什么需要</b>：{@link MainActivity#waitThenShowWeb} 里
     * {@code DshProbe.tryReady} 返回 200，只代表「那一刻」根路径可访问。
     * 紧接着 {@code WebView.loadUrl} 仍可能撞上三种窗口：
     * <ol>
     *   <li>3082 本地反代（engine-proxy）比 3080 晚监听；</li>
     *   <li>token → cookie 的下发与 loadUrl 竞争（预热 WebView 和 Activity 各探一次）；</li>
     *   <li>frontend-static 的 fallback 尚未注册，短暂 404（DshProbe 注释里已记录）。</li>
     * </ol>
     * 旧实现一收到主帧错误就 {@code loadDataWithBaseURL(serverNotReadyHtml)}，
     * 把真页面永久换成错误文档 —— 于是必须用户手点「重试」。
     *
     * <p><b>策略</b>：主帧失败先做有限次自动重试（重新取 cookie + reload 当前引擎根），
     * 成功落地后计数清零；连续失败到上限才显示错误页，保留真故障的可诊断性。
     * 不采用「无限重试/永不显示错误」：引擎真的挂了时用户需要对着证据排障。
     */
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final int MAX_AUTO_RETRY = 6;
    private static final long[] RETRY_DELAYS_MS = { 400L, 800L, 1200L, 2000L, 3000L, 4000L };
    private static final Object RETRY_LOCK = new Object();
    private static int sRetryCount = 0;
    private static long sLastFailureAt = 0L;
    private static boolean sRetryScheduled = false;

    /** 页面成功落到当前档案的可信文档时清零，别把上一轮失败算进新一轮。 */
    private static void noteLoadSucceeded(Context ctx, String url) {
        if (url == null) return;                                    // data: 错误文档不算
        if (!DshOrigin.isLocalUrl(url)) {
            try {
                if (!DshOrigin.sameOrigin(url, HostProfile.get(ctx).current().baseOrigin())) return;
            } catch (Throwable t) { return; }
        }
        synchronized (RETRY_LOCK) {
            sRetryCount = 0;
        }
    }

    /** 主帧失败统一入口：先自动重试，超过上限才出错误文档。 */
    private static void handleMainFrameFailure(final WebView view, final int httpCode) {
        if (view == null) return;
        final Context appCtx = view.getContext().getApplicationContext();
        // v2.3 批 B：relay 档不做本机就绪探测重试（DshProbe 探的是本机 3080，
        // 对远端反代毫无意义）—— 直接出错误页，由用户刷新。
        if (!HostProfile.get(appCtx).current().needsLocalEngine()) {
            showFinalError(view, httpCode);
            return;
        }
        synchronized (RETRY_LOCK) {
            final long now = SystemClock.elapsedRealtime();
            // onReceivedError 与 onReceivedHttpError 常对同一次失败各回调一次
            if (now - sLastFailureAt < 250L) return;
            sLastFailureAt = now;
            if (sRetryCount >= MAX_AUTO_RETRY) {
                showFinalError(view, httpCode);
                return;
            }
            if (sRetryScheduled) return;
            final int attempt = sRetryCount++;
            sRetryScheduled = true;
            // 第一次失败：立刻盖住 Chromium 原生错误页（回调本身在主线程，直接同步
            // loadData 才不会和后面的 loadUrl 抢顺序）。后续重试期间沿用这个占位页。
            if (attempt == 0 && view != null) {
                view.loadDataWithBaseURL(null,
                        connectingHtml(view.getContext(), attempt),
                        "text/html", "utf-8", null);
            }
            final long delay = RETRY_DELAYS_MS[Math.min(attempt, RETRY_DELAYS_MS.length - 1)];
            final String base = dshBaseUrl(appCtx);
            MAIN.postDelayed(new Runnable() {
                @Override public void run() {
                    synchronized (RETRY_LOCK) { sRetryScheduled = false; }
                    // DshProbe.tryReady 会发 HttpURLConnection —— 严禁放主线程（targetSdk 28）。
                    new Thread(new Runnable() {
                        @Override public void run() {
                            try {
                                Context ctx = view.getContext().getApplicationContext();
                                DshProbe.Ready ready = DshProbe.tryReady(ctx);
                                if (ready != null && ready.cookiePair != null) {
                                    CookieManager cm = CookieManager.getInstance();
                                    cm.setAcceptCookie(true);
                                    cm.setCookie(base, ready.cookiePair);
                                    cm.flush();
                                }
                            } catch (Throwable ignored) { }
                            MAIN.post(new Runnable() {
                                @Override public void run() {
                                    Log.i("DshWebViewClient", "[dsh-retry] attempt=" + (attempt + 1)
                                            + " url=" + base);
                                    view.loadUrl(base);
                                }
                            });
                        }
                    }, "dsh-retry-probe").start();
                }
            }, delay);
        }
    }

    /** 重试耗尽：显示错误文档（必须主线程）。 */
    private static void showFinalError(final WebView view, final int httpCode) {
        Log.w("DshWebViewClient", "[dsh-retry] 重试耗尽，显示错误页 httpCode=" + httpCode);
        final String base = dshBaseUrl(view.getContext().getApplicationContext());
        MAIN.post(new Runnable() {
            @Override public void run() {
                // r121：远端档失败**不能**复用本机那套文案。本机档失败时说「dsh 后台可能仍在
                // 启动，或已退出」是对的；远端档失败的真实原因是「连不上那台电脑」，
                // 而 relay 档按设计不跑本机引擎 —— 说成引擎问题会把人引到错的方向去排查。
                HostProfile.Profile prof = HostProfile.get(view.getContext()).current();
                if (!prof.needsLocalEngine() && view.getContext() instanceof MainActivity) {
                    ((MainActivity) view.getContext()).showRemoteUnreachable(prof);
                    return;
                }
                final boolean dk = DshPalette.isDark(view.getContext());
                view.loadDataWithBaseURL(null,
                        httpCode == 401 ? authExpiredHtml(base, dk) : serverNotReadyHtml(base, dk),
                        "text/html", "utf-8", null);
            }
        });
    }

    @Override
    public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
        if (request != null && request.isForMainFrame() && view != null) {
            handleMainFrameFailure(view, 0);
        }
    }

    /**
     * r72（P3-3）：API&lt;23 走这个旧重载（minSdk=21）—— 原来没实现，
     * Android 5.x/6.x 上主帧错误没有任何友好页。用“失败 URL == 当前 URL”
     * 近似判定主帧（旧回调对子资源也会触发，不能不加这道闸）。
     */
    @Override
    @SuppressWarnings("deprecation")
    public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
        if (view == null || failingUrl == null) return;
        try {
            String cur = view.getUrl();
            if (failingUrl.equals(cur)) {
                handleMainFrameFailure(view, 0);
            }
        } catch (Throwable ignored) { }
    }

    @Override
    public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse errorResponse) {
        if (request != null && request.isForMainFrame() && view != null) {
            int code = 0;
            try {
                if (android.os.Build.VERSION.SDK_INT >= 23) code = errorResponse.getStatusCode();
            } catch (Throwable ignored) { }
            // r72（P3-4）：401 与其他失败分开记，但**不立刻**弹「重新登录」——
            // 冷启时 401 多半只是 cookie 还没下发，交给自动重试再判。
            handleMainFrameFailure(view, code);
        }
    }

    /** r80（P0）：首一次 dsh 页面加载完成后清过历史了没有（每个进程一次）。 */
    private static boolean sHistoryBased = false;

    @Override
    public void onPageFinished(WebView view, String url) {
        super.onPageFinished(view, url);
        // r198：本地 dsh 文档真正加载完成 → 清掉自动重试计数。
        // 错误页是 data: URL，可信判定不认它，所以不会误清。
        // v2.3 批 B：relay 档的成功页是档案 origin，同样算成功。
        noteLoadSucceeded(view.getContext().getApplicationContext(), url);
        // r80（P0）：首次 dsh 页面加载完成后，立刻清掉 WebView 历史的"栈底" ——
        //   预热阶段的 about:blank 条目。原 onResume 的 clearHistory() 在
        //   loadUrl(dsh) **之前**执行，只清掉当时的 [blank]，随后 loadUrl 又把
        //   blank 压回栈底：[blank, dsh]。于是只要面板的历史标记全被消费掉，
        //   canGoBack() 依旧为 true，再多退一步就是白屏 about：blank
        //   （真机日志：32.235 jsHandled=false canGoBack=true → history.back()
        //   → 32.246 path=blank → 33.420 退出 App）。
        //   在这里清发生在 dsh 文档**已经落顶**之后，一刀剪掉底部 blank；
        //   SPA 自己的 pushState 条目是之后才压的，不受影响。
        if (!sHistoryBased && isTrustedPage(view, url)) {
            sHistoryBased = true;
            try {
                view.clearHistory();
                Log.i("DshWebViewClient", "[dsh] clearHistory @ first dsh load: " + url);
            } catch (Throwable ignored) { }
        }
        // r151：官方 dsh 0.2.x web 已自带移动端响应式布局（侧栏抽屉 / 窄屏自适应），
        // 不再注入针对 0.1.x 桌面优先布局的手机适配外壳（assets/dsh/mobile.css|js 等）。
        // 保留下面的主题探测（已对齐到 0.2.x 的主题信号）。

        // r79：启动页深色模式的第二段 —— 页面真正加载完，**此时才读得到 dsh 的主题**。
        // 启动页阶段只能按系统 uiMode 猜（见 DshPalette 注释），若用户是在 dsh 里
        // 设的主题且与系统不同，这里就是唯一一次纠正机会。
        //
        // ⚠️ 真机调试（r79）发现：只探一次**不够**。dsh 是 SPA，
        // onPageFinished 触发时 DOM 可能还没渲染出背景色，getComputedStyle 返回空，
        // 于是静默失败 —— 真机日志里连一条记录都没有，查了很久。
        // 现在改成**最多重试 5 次、间隔递增**（1s/2s/4s/8s/12s），
        // 覆盖 SPA 首屏渲染的不确定时长。
        probeTheme(view, 0);

        // r169（用户回滚）：恢复 official-bridge.js 注入。
        //   原委：r167 曾按用户要求改成「零注入」，移动端适配交给 dsh-web-mobile 插件；
        //   真机用下来反馈插件不好用，遂回滚到 r166 的注入形态。
        //   ⚠️ 只注入**这一段脚本**（设置入口拦截 → 原生 Compose 设置页 + 返回状态上报），
        //      不注入任何 CSS、不改布局 —— 页面观感与官方 WebView 一致。
        //      历史上更早的三层（mobile.css / mobile.js / market*.css / dvh 结构修复）
        //      保持移除状态，不要因回滚而复活。
        if (isTrustedPage(view, url)) {
            com.dsh.harness.webview.OfficialShell.injectIfAllowed(view);
        }
    }

    /** 探测 dsh 页面主题（带退避重试）。attempt 从 0 开始。 */
    private void probeTheme(final WebView view, final int attempt) {
        final int MAX_ATTEMPT = 5;
        // ⚠️ r79 真机实测：首次延迟必须给足 —— dsh 是 SPA，onPageFinished 触发时
        //   主题（@media/class）往往还没应用上，body 背景还是浅色。
        //   原先只等 400ms → 必然误判。1.2s 起，配合上面的 sys: 兜底才稳。
        long delay = attempt == 0 ? 1200L : Math.min(15000L, 1500L << attempt);
        try {
            view.postDelayed(new Runnable() {
                @Override public void run() {
                    try {
                        view.evaluateJavascript(
                                // 0.2.x 原生暗色主题探测（不再依赖已移除的 mobile.js 注入层）：
                                //   dsh 官方 CSS 用 `body[data-ds-dark-theme]` 切换深色变量，这是权威信号；
                                //   同时兜底 prefers-color-scheme（0.2.x 默认跟随系统暗色）。
                                //   直接读这些信号 → 首探即返回确定结论，避免旧逻辑在 SPA 未渲染时
                                //   误判并退避重试最多 ~46s 才跟随系统（深色系统下启动页闪白条）。
                                //   data-ds-dark-theme 可能落在 body 也可能落在 html（不同构建），两者都查；
                                //   兜底 matchMedia 立即可得，与 0.2.x 的系统暗色渲染保持一致。
                                "(function(){try{"
                                + "var h=document.documentElement,b=document.body;"
                                + "if(!h||!b)return '';"
                                // ⚠️⚠️ r79 真机踩坑：**语义曾经搞反过，务必看清再改**。
                                //   `data-ds-dark-theme` 的名字像"强制浅色"，但它其实是
                                //   **「dsh 深色主题已启用」的标记** —— mobile.css 第 2 行：
                                //     body[data-ds-dark-theme]{--ds-ink-fb-900:#e6edf5;
                                //                                 --ds-surface-fb:#141b23;...}
                                //   配的全是**深色变量**。
                                //   mobile.js 里那句 `classList.remove('dsh-dark')` 只是
                                //   **避免双重应用**（页面已经用 data-* 应用过了），
                                //   不是"改成浅色"。
                                //   我曾误判为浅色 → 真机上深色系统却画出浅色启动页。
                                + "var dark=b.hasAttribute('data-ds-dark-theme')"
                                + "||h.hasAttribute('data-ds-dark-theme')"
                                + "||h.classList.contains('dsh-dark')"
                                + "||h.classList.contains('dark')"
                                + "||(window.matchMedia&&window.matchMedia('(prefers-color-scheme: dark)').matches);"
                                + "return dark?'dark':'light';"
                                + "}catch(e){return '';}})()",
                                new android.webkit.ValueCallback<String>() {
                                    @Override public void onReceiveValue(String value) {
                                        // ⚠️ **每条分支都打日志**：静默失败是最难查的一类问题，
                                        //   尤其在无法本地复现的真机上。
                                        String v = value == null ? "" : value.replace("\"", "").trim();
                                        Log.i("DshWebViewClient", "[dsh] theme probe #"
                                                + attempt + " raw=" + value);
                                        // ★ r79 真机教训：探测结果**不是**绝对权威。
                                        //   dsh 是 SPA，onPageFinished 时主题常常还没生效，
                                        //   此时读到浅色会误导启动页（浅色启动页 + 深色页面，闪瞎眼）。
                                        //   所以 JS 只报**权威信号**（html.dsh-dark /
                                        //   data-ds-dark-theme）；拿不到就报 guess:*，
                                        //   Java 侧见到 guess: 一律丢弃、跟随系统。
                                        //   ——探测只是"系统的补充"，不是"系统的主人"。
                                        if (v.startsWith("guess:")) {
                                            Log.i("DshWebViewClient",
                                                    "[dsh] 未拿到权威主题信号，跟随系统（"
                                                            + v.substring(6).trim() + "）");
                                            if (attempt < MAX_ATTEMPT) {
                                                probeTheme(view, attempt + 1);
                                            } else {
                                                // 重试用尽仍无权威信号 → 确定跟随系统，并刷新系统栏
                                                DshPalette.preferSystem(view.getContext());
                                                Log.i("DshWebViewClient",
                                                        "[dsh] 最终跟随系统主题（页面未给出权威信号）");
                                                try {
                                                    MainActivity ma = (MainActivity) view.getContext();
                                                    ma.refreshSystemBarsForTheme();
                                                } catch (Throwable ignored) { }
                                            }
                                            return;
                                        }
                                        if ("dark".equals(v) || "light".equals(v)) {
                                            boolean dark = "dark".equals(v);
                                            boolean changed = DshPalette.rememberDshDark(
                                                    view.getContext(), dark);
                                            Log.i("DshWebViewClient", "[dsh] 页面权威主题=" + v
                                                    + (changed ? "（配色已更新）" : "（配色本就一致）"));
                                            // ⚠️ 关键：知道真实主题后**立刻重刷系统栏**。
                                            //   否则状态栏会停留在启动页的颜色 —— 深色页面配浅色状态栏，
                                            //   顶部一条白边（r79 真机截图确认过的现象）。
                                            if (changed) {
                                                try {
                                                    MainActivity ma = (MainActivity) view.getContext();
                                                    ma.refreshSystemBarsForTheme();
                                                } catch (Throwable ignored) { }
                                            }
                                            return;
                                        }
                                        // 空值 = SPA 还没渲染出背景色，继续等
                                        if (attempt < MAX_ATTEMPT) {
                                            probeTheme(view, attempt + 1);
                                        } else {
                                            Log.w("DshWebViewClient",
                                                    "[dsh] theme probe 5 次仍无结果，放弃（沿用系统主题）");
                                        }
                                    }
                                });
                    } catch (Throwable t) {
                        Log.w("DshWebViewClient", "[dsh] theme probe 异常: " + t.getMessage());
                    }
                }
            }, delay);
        } catch (Throwable t) {
            Log.w("DshWebViewClient", "[dsh] theme probe postDelayed 失败: " + t.getMessage());
        }
    }
}
