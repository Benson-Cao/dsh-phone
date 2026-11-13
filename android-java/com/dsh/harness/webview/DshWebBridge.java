package com.dsh.harness.webview;

import android.app.Activity;
import android.os.Build;
import android.util.Log;
import android.webkit.WebView;

import androidx.webkit.JavaScriptReplyProxy;
import androidx.webkit.WebMessageCompat;
import androidx.webkit.WebViewCompat;

import com.dsh.harness.DshBridge;
import com.dsh.harness.DshSafBridge;

import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.ref.WeakReference;
import java.util.HashSet;
import java.util.Set;

/**
 * r152（官方对齐）：官方桥接通道 {@code dsh-bridge}。
 *
 * <p>手册 6.3 明确推荐用 {@link WebViewCompat#addWebMessageListener} 取代裸
 * {@code addJavascriptInterface}（消息名 {@code dsh-bridge}，JS 侧 {@code window.DshBridge.call(...)}）。
 * 本类实现该通道，并把官方工具协议（{@code {id,method,params}} → {@code {id,ok?,error?}}）
 * 路由到本 App 已有的原生能力：
 *
 * <ul>
 *   <li>SAF 控制面：{@code saf_status} / {@code saf_pick} / {@code saf_clear} /
 *       {@code saf_selftest} / {@code saf_list_exportable} / {@code saf_share} /
 *       {@code saf_save} → {@link DshSafBridge}</li>
 *   <li>NAS 同步：{@code sync_config} / {@code sync_now} / {@code sync_last} → {@link DshSafBridge}</li>
 *   <li>插件市场：{@code market_install} / {@code market_pnpm} / {@code back_handled} → {@link DshBridge}</li>
 * </ul>
 *
 * <p>对暂无对应 WebView 工具的 {@code nas_*} / {@code device_*} / SAF 文件操作
 * （{@code saf_list/read/write}，本 App 这些走 node → {@code SafProxyServer} HTTP 代理），
 * 返回明确的不支持错误，避免静默失败。
 *
 * <p>本通道与现有 {@code addJavascriptInterface(dshNative/dshSaf)} <b>并存</b>：
 * 现有接口继续服务当前前端，本通道是官方「祝福路径」的前向兼容实现，
 * 待前端切到 {@code window.DshBridge.call} 后可逐步退场旧接口。
 *
 * <p>安全要求：仅对本地 dsh 源（127.0.0.1 / localhost）处理消息；来源非本地一律拒绝。
 * 与 {@link DshBridge#localOriginAllowed} 同一套判定。
 */
public final class DshWebBridge {

    private static final String TAG = "DshWebBridge";

    /** 官方约定的消息名（JS 侧 {@code addWebMessageListener('dsh-bridge', ...)} 对应）。 */
    public static final String MSG_NAME = "dsh-bridge";

    /** 允许的源（仅回环）。空集合 = 接受任意源，故显式列回环的两个写法。 */
    private static final Set<String> ALLOWED_SCHEMES = new HashSet<String>() {{
        add("http://127.0.0.1");
        add("http://localhost");
    }};

    private final WeakReference<Activity> actRef;
    private final DshBridge bridge;
    private final DshSafBridge saf;

    public DshWebBridge(Activity act) {
        this.actRef = new WeakReference<>(act);
        this.bridge = new DshBridge(act);
        this.saf = new DshSafBridge(act);
    }

    /**
     * 在 API 23+ 上注册官方 dsh-bridge 通道；低版本静默跳过（走旧 addJavascriptInterface）。
     *
     * @return 是否成功注册
     */
    public boolean register(WebView view) {
        if (view == null) return false;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            Log.i(TAG, "API " + Build.VERSION.SDK_INT + " < 23，跳过 addWebMessageListener（走旧桥）");
            return false;
        }
        try {
            WebViewCompat.addWebMessageListener(view, MSG_NAME, ALLOWED_SCHEMES,
                    new WebViewCompat.WebMessageListener() {
                        @Override
                        public void onPostMessage(WebView view, WebMessageCompat message,
                                android.net.Uri sourceOrigin, boolean isMainFrame,
                                JavaScriptReplyProxy replyProxy) {
                            handle(view, message, sourceOrigin, replyProxy);
                        }
                    });
            Log.i(TAG, "dsh-bridge WebMessageListener 已注册");
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "注册 dsh-bridge 失败: " + t.getMessage());
            return false;
        }
    }

    /** 解析请求并分发，构造回复。 */
    private void handle(WebView view, WebMessageCompat message,
            android.net.Uri sourceOrigin, JavaScriptReplyProxy replyProxy) {
        String raw = message != null ? message.getData() : null;
        String id = "";
        try {
            if (raw == null || raw.trim().isEmpty()) {
                replyProxy.postMessage(jsonErr(id, "空消息"));
                return;
            }
            JSONObject req = new JSONObject(raw);
            id = req.optString("id", "");
            String method = req.optString("method", "");
            JSONObject params = req.optJSONObject("params");
            if (params == null) params = new JSONObject();

            // 来源校验：仅本地 dsh
            if (!isLocalOrigin(sourceOrigin)) {
                replyProxy.postMessage(jsonErr(id, "拒绝：非本地来源 " + sourceOrigin));
                return;
            }

            Object result = dispatch(method, params);
            if (result instanceof String && isJson((String) result)) {
                // 原生方法已返回 JSON 字符串 → 直接作为 ok 载荷
                replyProxy.postMessage(jsonOkRaw(id, (String) result));
            } else {
                replyProxy.postMessage(jsonOk(id, result));
            }
        } catch (org.json.JSONException je) {
            replyProxy.postMessage(jsonErr(id, "协议错误: " + je.getMessage()));
        } catch (Throwable t) {
            Log.w(TAG, "dsh-bridge 处理异常: " + t.getMessage());
            replyProxy.postMessage(jsonErr(id, "内部错误: " + t.getMessage()));
        }
    }

    /** 把工具名分发到对应原生方法；返回 String（多为 JSON）或 Boolean/其他 JSON 可序列化值。 */
    private Object dispatch(String method, JSONObject p) {
        switch (method) {
            // ---- SAF 控制面 ----
            case "saf_status":        return saf.safStatus();
            case "saf_pick":          return Integer.toString(saf.safPickDir());
            case "saf_clear":         return saf.safClear();
            case "saf_selftest":      return saf.safSelfTest();
            case "saf_list_exportable":return saf.listExportable();
            case "saf_share":         return saf.shareFile(p.optString("path"));
            case "saf_save":          return Integer.toString(
                                          saf.saveAs(p.optString("path"), p.optString("mime")));

            // ---- NAS 同步 ----
            case "sync_config":       return saf.syncConfig();
            case "sync_now":          return saf.syncNow();
            case "sync_last":         return saf.syncLastResult();
            case "sync_save":         return saf.saveSyncConfig(
                                          p.optString("url"), p.optString("user"),
                                          p.optString("pass"), p.optInt("interval"));

            // ---- 插件市场（走 dshNative 异步回调，不在 dsh-bridge 同步通道内）----
            case "market_install":
            case "market_pnpm":
                return errBody("市场安装/ pnpm 仍走 dshNative 异步通道（window.__dshMarketDone / __dshPnpmDone）");
            case "back_handled":      return "ok";

            // ---- 本 App 走 HTTP 代理 / 独立客户端的工具：明确不支持 ----
            case "saf_list":
            case "saf_read":
            case "saf_write":
                return errBody("SAF 文件操作在本 App 走 node→SafProxyServer HTTP 代理，不经由 WebView 桥");
            default:
                if (method.startsWith("nas_") || method.startsWith("device_")) {
                    return errBody("本 App 的 NAS/设备能力由独立客户端提供，未通过 dsh-bridge 暴露");
                }
                return errBody("未知工具: " + method);
        }
    }

    private static boolean isLocalOrigin(android.net.Uri o) {
        if (o == null) return false;
        String s = o.toString();
        return s.startsWith("http://127.0.0.1") || s.startsWith("http://localhost");
    }

    private static boolean isJson(String s) {
        if (s == null) return false;
        String t = s.trim();
        return t.startsWith("{") || t.startsWith("[");
    }

    private static String jsonOk(String id, Object ok) {
        try {
            JSONObject o = new JSONObject();
            o.put("id", id);
            o.put("ok", ok == null ? JSONObject.NULL : ok);
            return o.toString();
        } catch (Throwable t) {
            return jsonErr(id, "序列化失败");
        }
    }

    /** ok 载荷为原生已生成的 JSON 字符串，原样嵌入（避免二次转义）。 */
    private static String jsonOkRaw(String id, String rawJson) {
        try {
            JSONObject o = new JSONObject();
            o.put("id", id);
            o.put("ok", new JSONObject(rawJson));
            return o.toString();
        } catch (Throwable t) {
            // rawJson 不是对象（可能是数组或标量）→ 当作字符串
            try {
                JSONObject o = new JSONObject();
                o.put("id", id);
                o.put("ok", rawJson);
                return o.toString();
            } catch (Throwable t2) {
                return jsonErr(id, "序列化失败");
            }
        }
    }

    private static String jsonErr(String id, String msg) {
        try {
            JSONObject o = new JSONObject();
            o.put("id", id);
            o.put("error", msg);
            return o.toString();
        } catch (Throwable t) {
            return "{\"id\":\"" + id + "\",\"error\":\"序列化失败\"}";
        }
    }

    private static String errBody(String msg) {
        try {
            JSONObject o = new JSONObject();
            o.put("error", msg);
            return o.toString();
        } catch (Throwable t) {
            return "{\"error\":\"" + msg + "\"}";
        }
    }
}
