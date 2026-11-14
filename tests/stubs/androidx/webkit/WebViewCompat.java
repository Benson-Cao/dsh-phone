// 编译期桩（compile-only scaffolding）—— 不参与打包与运行，详见本目录说明。
package androidx.webkit;

import android.net.Uri;
import android.webkit.WebView;

import java.util.Set;

/** 桩：androidx.webkit.WebViewCompat */
public final class WebViewCompat {

    /** 与真实依赖同签名（5 参）。 */
    public interface WebMessageListener {
        void onPostMessage(WebView view, WebMessageCompat message,
                           Uri sourceOrigin, boolean isMainFrame,
                           JavaScriptReplyProxy replyProxy);
    }

    public static void addWebMessageListener(WebView webView, String jsObjectName,
                                             Set<String> allowedOriginRules,
                                             WebMessageListener listener) {
        // 桩：不做事
    }
}
