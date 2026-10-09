package com.dsh.harness;

import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
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

    private static final String ERROR_HTML =
        "<html data-dsh-error=\"1\"><body style='font-family:sans-serif;padding:24px;color:#333'>"
        + "<h2>服务还没就绪</h2>"
        + "<p>dsh 后台可能仍在启动，或已退出。请稍候刷新，或检查运行日志。</p>"
        + "<button onclick='location.reload()' style='margin-top:12px;padding:8px 16px;"
        + "border:0;border-radius:8px;background:#1466b8;color:#fff;font-size:14px;cursor:pointer'>重试</button>"
        + "</body></html>";

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
            view.loadDataWithBaseURL(null, ERROR_HTML, "text/html", "utf-8", null);
        }
    }

    @Override
    public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse errorResponse) {
        if (request != null && request.isForMainFrame() && view != null) {
            view.loadDataWithBaseURL(null, ERROR_HTML, "text/html", "utf-8", null);
        }
    }

    @Override
    public void onPageFinished(WebView view, String url) {
        super.onPageFinished(view, url);
        // dsh 的 UI 是桌面优先布局，手机上必须先补一刀（限宽侧栏 + 自动折叠）
        MobileTuning.apply(view);
    }
}
