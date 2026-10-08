package com.dsh.harness;

import android.content.Intent;
import android.net.Uri;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;

/**
 * WebView 客户端：内外链分流 + 页面就绪后注入手机适配补丁 + 友好错误页。
 * 127.0.0.1 / localhost 留在 WebView 内，其余外链丢给系统浏览器。
 */
public class DshWebViewClient extends WebViewClient {

    private static final String ERROR_HTML =
        "<html><body style='font-family:sans-serif;padding:24px;color:#333'>"
        + "<h2>服务还没就绪</h2>"
        + "<p>dsh 后台可能仍在启动，或已退出。请稍候刷新，或检查运行日志。</p>"
        + "</body></html>";

    @Override
    public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
        if (request == null || request.getUrl() == null) return false;
        Uri url = request.getUrl();
        String host = url.getHost();
        if ("127.0.0.1".equals(host) || "localhost".equals(host)) return false;
        try {
            view.getContext().startActivity(new Intent(Intent.ACTION_VIEW, url));
        } catch (Exception ignored) { }
        return true;
    }

    @Override
    public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
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
