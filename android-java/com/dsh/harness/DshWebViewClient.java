package com.dsh.harness;

import android.content.Intent;
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
