package com.dsh.harness;

import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.util.Log;
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

    /** dsh 服务根地址（r72/P2-7：错误页「重试」需要直达它，而不是 reload 错误文档）。 */
    private static String dshBaseUrl() {
        return "http://127.0.0.1:" + DshProcessManager.DSH_PORT + "/";
    }

    /**
     * r72（P2-7）：错误页改为由方法生成 —— 原来「重试」是 location.reload()，
     * 但页面是 loadDataWithBaseURL(null,…) 的 about:blank 文档，重载的是错误
     * 文档本身，永远不会真正重试 dsh 服务。现在重试直接 location.href 回 dsh 根。
     */
    private static String errorHtml(String title, String body) {
        return "<html data-dsh-error=\"1\"><body style='font-family:sans-serif;padding:24px;color:#333'>"
            + "<h2>" + title + "</h2>"
            + "<p>" + body + "</p>"
            + "<button onclick='location.href=\"" + dshBaseUrl() + "\"' style='margin-top:12px;padding:8px 16px;"
            + "border:0;border-radius:8px;background:#1466b8;color:#fff;font-size:14px;cursor:pointer'>重试</button>"
            + "</body></html>";
    }

    private static String serverNotReadyHtml() {
        return errorHtml("服务还没就绪",
            "dsh 后台可能仍在启动，或已退出。请稍候刷新，或检查运行日志。");
    }

    private static String authExpiredHtml() {
        // r72（P3-4）：401（鉴权过期）与“服务未就绪”分开提示，避免误导排障方向
        return errorHtml("需要重新登录",
            "dsh 的登录状态已过期（401）。请完全退出应用后重新打开，应用会重新获取登录凭证。");
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

    @Override
    public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
        if (request != null && request.isForMainFrame() && view != null) {
            view.loadDataWithBaseURL(null, serverNotReadyHtml(), "text/html", "utf-8", null);
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
                view.loadDataWithBaseURL(null, serverNotReadyHtml(), "text/html", "utf-8", null);
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
            // r72（P3-4）：401 单独给“重新登录”文案
            view.loadDataWithBaseURL(null, code == 401 ? authExpiredHtml() : serverNotReadyHtml(),
                "text/html", "utf-8", null);
        }
    }

    /** r80（P0）：首一次 dsh 页面加载完成后清过历史了没有（每个进程一次）。 */
    private static boolean sHistoryBased = false;

    @Override
    public void onPageFinished(WebView view, String url) {
        super.onPageFinished(view, url);
        // r80（P0）：首次 dsh 页面加载完成后，立刻清掉 WebView 历史的"栈底" ——
        //   预热阶段的 about:blank 条目。原 onResume 的 clearHistory() 在
        //   loadUrl(dsh) **之前**执行，只清掉当时的 [blank]，随后 loadUrl 又把
        //   blank 压回栈底：[blank, dsh]。于是只要面板的历史标记全被消费掉，
        //   canGoBack() 依旧为 true，再多退一步就是白屏 about：blank
        //   （真机日志：32.235 jsHandled=false canGoBack=true → history.back()
        //   → 32.246 path=blank → 33.420 退出 App）。
        //   在这里清发生在 dsh 文档**已经落顶**之后，一刀剪掉底部 blank；
        //   SPA 自己的 pushState 条目是之后才压的，不受影响。
        if (!sHistoryBased && url != null && DshOrigin.isLocalUrl(url)) {
            sHistoryBased = true;
            try {
                view.clearHistory();
                Log.i("DshWebViewClient", "[dsh] clearHistory @ first dsh load: " + url);
            } catch (Throwable ignored) { }
        }
        // dsh 的 UI 是桌面优先布局，手机上必须先补一刀（限宽侧栏 + 自动折叠）
        MobileTuning.apply(view);

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
                                // ⚠️ r79 真机调试（第二轮）修正：**直接读 dsh 的权威判定结果**，
                                //   不要自己重算 body 背景色亮度。
                                //   原因：真机上出现过「回读=light 但页面明显是深色」的矛盾 ——
                                //   因为 dsh 的主题不一定落在 body 背景上（可能是内层容器或 CSS 变量），
                                //   自己算出来的结论与页面实际观感不一致，还把错误值落了盘。
                                //   而 `documentElement.classList.contains('dsh-dark')`
                                //   正是 mobile.js 的 detectDark() 算完后写下的**最终结论**，
                                //   读它 = 直接复用页面的判定，零分歧。
                                // ① 权威：mobile.js 的 detectDark() 算完写下的结论
                                // ② dsh 强制浅色标记
                                // ⚠️ ③ 之后全是**猜测** → 一律加 guess: 前缀，
                                //    Java 侧见到guess: 就丢弃结果、跟随系统。
                                //    （r79 真机：早期版本把猜测当权威，错误落盘，
                                //      导致深色系统下启动页画成浅色 —— 闪瞎眼）
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
                                + "if(b.hasAttribute('data-ds-dark-theme'))return 'dark';"
                                + "if(h.classList.contains('dsh-dark'))return 'dark';"
                                + "return 'guess:none';"
                                + "}catch(e){return 'guess:err'}})()",
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
