package com.dsh.harness;

import android.app.Activity;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.util.Log;
import android.webkit.ConsoleMessage;
import android.webkit.JsResult;
import android.webkit.JsPromptResult;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.widget.EditText;
import android.widget.Toast;

import java.util.ArrayList;

/**
 * 补上 WebView 缺失的"选择文件"能力 + JS 弹窗能力。
 *
 * Android WebView 默认**不实现** {@link WebChromeClient#onShowFileChooser}：
 * 页面里的 `<input type="file">` 被点击时既不弹选择器、也不回调，
 * 表现就是"附件按钮点了没反应"。
 * 这里把它接到系统文件选择器（ACTION_GET_CONTENT）上，并把结果回灌给页面。
 *
 * <h2>r72（P0-1）：补上 onJsAlert / onJsConfirm / onJsPrompt</h2>
 * WebChromeClient 基类这三个方法返回 false 即"客户端不处理"，WebView 会直接
 * 取消对话框：{@code window.confirm()} 恒返回 false、{@code window.alert()}
 * 静默不显示。注入脚本里「安装 dshmarket/pnpm」的确认框、「更新插件市场」
 * 按钮、安装失败提示全部依赖它们 —— 不覆写就是真机上的死按钮（且 alert 的
 * 失败反馈用户根本看不到）。发布仓库全量 git 历史（git log -S）确认这三个
 * 方法从未实现过。
 */
public class DshChromeClient extends WebChromeClient {

    private static final String TAG = "DshChromeClient";

    private final Activity act;
    // r219：待处理回调改为静态 sPending（见下方「附件选择：r219 回程重做」）。

    public DshChromeClient(Activity act) {
        this.act = act;
    }

    // ===== r72（P0-1）：JS 弹窗三件套 =====

    @Override
    public boolean onJsAlert(WebView view, String url, String message, JsResult result) {
        // alert 没有"取消"语义 —— 只有确定；取消对话框等价于确认并继续执行。
        return jsDialog(view, "提示", message, result, null, false);
    }

    @Override
    public boolean onJsConfirm(WebView view, String url, String message, JsResult result) {
        return jsDialog(view, "确认", message, result, null, true);
    }

    @Override
    public boolean onJsPrompt(WebView view, String url, String message,
                              String defaultValue, JsPromptResult result) {
        EditText input = new EditText(view.getContext());
        if (defaultValue != null) input.setText(defaultValue);
        return jsDialog(view, "输入", message, result, input, true);
    }

    /**
     * 统一的 JS 弹窗实现。
     *
     * @param input 非空表示 prompt（确认时把输入框内容 {@link JsPromptResult#confirm(String)} 回给页面）
     */
    private boolean jsDialog(WebView view, String title, String message,
                             final JsResult result, final EditText input, boolean withCancel) {
        try {
            android.app.AlertDialog.Builder b =
                new android.app.AlertDialog.Builder(view.getContext());
            b.setTitle(title).setMessage(message);
            if (input != null) b.setView(input);
            b.setCancelable(true);
            b.setPositiveButton("确定", new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface d, int which) {
                    try {
                        if (input != null && result instanceof JsPromptResult) {
                            ((JsPromptResult) result).confirm(input.getText().toString());
                        } else {
                            result.confirm();
                        }
                    } catch (Throwable ignored) { }
                }
            });
            if (withCancel) {
                b.setNegativeButton("取消", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        try { result.cancel(); } catch (Throwable ignored) { }
                    }
                });
            }
            b.setOnCancelListener(new DialogInterface.OnCancelListener() {
                @Override public void onCancel(DialogInterface d) {
                    try { result.cancel(); } catch (Throwable ignored) { }
                }
            });
            b.show();
            return true;   // 本客户端负责这个对话框，WebView 不要再走默认取消路径
        } catch (Throwable t) {
            Log.e(TAG, "JS 弹窗创建失败", t);
            try { result.cancel(); } catch (Throwable ignored) { }   // 绝不能把 JS 挂死在等回执
            return false;
        }
    }

    /**
     * r74 [diag]：把页面 console 消息转发到 logcat，供真机定位注入脚本异常。
     *
     * <p>默认情况下 WebView 的 {@code console.log/error} 不落 logcat，注入脚本里
     * 的 {@code [dsh]} 日志全部看不到。这里转发：错误级全量打，其余只打带
     * {@code [dsh} 前缀的（避免被 dsh 业务脚本的噪音刷屏）。
     */
    @Override
    public boolean onConsoleMessage(ConsoleMessage cm) {
        if (cm == null) return false;
        String msg = cm.message();
        String loc = cm.sourceId() + ":" + cm.lineNumber();
        try {
            if (cm.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
                Log.e(TAG, "[console] " + msg + " @" + loc);
            } else if (msg != null && msg.contains("[dsh")) {
                Log.i(TAG, "[console] " + msg + " @" + loc);
            }
        } catch (Throwable ignored) { }
        return true;
    }

    // ===== 附件选择：r219 回程重做 =====

    /**
     * 跨 Activity 重建的回调持有（r219）。
     *
     * <p>真机根因：vivo 的系统文件管理器是独立 Activity，用户在里面挑文件时本进程
     * 的 MainActivity 可能已被回收重建。旧实现把回调放在**实例字段** {@code pending}
     * 上 —— 重建后是新实例、{@code pending == null}，{@code onActivityResult} 里
     * {@code deliver()} 命中 null 分支后直接 {@code return true} 什么都不做，页面永远
     * 收不到文件，表现就是"文件管理器弹出来了，选完没反应"。
     * 改为静态持有：同一进程内 Activity 重建不再丢回调。
     */
    private static volatile ValueCallback<Uri[]> sPending;
    /** 诊断回显用的 WebView（vivo 会吞掉三方应用的 logcat，只能借页面 console 取证）。 */
    private static volatile WebView sView;

    @Override
    public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                     FileChooserParams params) {
        ValueCallback<Uri[]> old = sPending;
        if (old != null && old != callback) {
            try { old.onReceiveValue(null); } catch (Exception ignored) { }
        }
        sPending = callback;
        sView = view;

        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        // r219：必须带读授权。vivo 的文件管理器不是 AOSP DocumentsUI，发起方没写
        // FLAG_GRANT_READ_URI_PERMISSION 时它回传的 content:// 对我们的进程不可读，
        // WebView 读文件失败后会**静默丢弃**整次选择结果 —— 与"选完没反应"一致。
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

        // 页面的 accept 属性可能给出一串 MIME 类型
        String[] accept = params == null ? null : params.getAcceptTypes();
        if (accept != null && accept.length > 0) {
            ArrayList<String> types = new ArrayList<String>();
            for (String a : accept) {
                if (a != null && !a.trim().isEmpty()) types.add(a.trim());
            }
            if (types.size() == 1) {
                intent.setType(types.get(0));
            } else if (types.size() > 1) {
                intent.putExtra(Intent.EXTRA_MIME_TYPES, types.toArray(new String[0]));
            }
        }
        if (params != null && params.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE) {
            intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        }
        diag("open accept=" + java.util.Arrays.toString(accept)
                + " mode=" + (params == null ? -1 : params.getMode()));

        try {
            act.startActivityForResult(intent, MainActivity.REQ_FILE_CHOOSER);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "打开文件选择器失败", e);
            diag("open-fail " + e);
            sPending = null;
            sView = null;
            Toast.makeText(act, "没有找到可用的文件管理器", Toast.LENGTH_LONG).show();
            // 返回 false 让 WebView 走它自己的默认处理
            return false;
        }
    }

    /**
     * 由 {@code Activity.onActivityResult} 转发（实例入口，保留给既有调用方）。
     * @return true 表示这次回调已被消费。
     */
    public boolean deliver(int requestCode, int resultCode, Intent data) {
        return deliverStatic(requestCode, resultCode, data);
    }

    /**
     * 与实例无关的回灌入口（r219）：Activity 重建后 {@code chromeClient} 可能是
     * 新实例甚至为 null，只有静态回调还在。{@code MainActivity} 一律走这里。
     * @return true 表示这次回调属于附件选择、已被消费。
     */
    public static boolean deliverStatic(int requestCode, int resultCode, Intent data) {
        if (requestCode != MainActivity.REQ_FILE_CHOOSER) return false;
        ValueCallback<Uri[]> cb = sPending;
        sPending = null;
        if (cb == null) {
            diag("deliver: no-pending resultCode=" + resultCode
                    + " hasData=" + (data != null));
            return true;
        }
        Uri[] result = extractUris(resultCode, data);
        diag("deliver resultCode=" + resultCode
                + " uris=" + (result == null ? "null" : java.util.Arrays.toString(result)));
        try {
            cb.onReceiveValue(result);
        } catch (Throwable t) {
            Log.w(TAG, "回灌选择结果失败", t);
            diag("deliver-callback-fail " + t);
        }
        sView = null;
        return true;
    }

    /**
     * 自己解析选择结果，不用 {@link FileChooserParams#parseResult}。
     *
     * <p>{@code parseResult} 只认 AOSP 那套（{@code ClipData} 或 {@code getDataString()}），
     * 第三方文件管理器常把 URI 塞在 {@code EXTRA_STREAM} 里、甚至带 {@code RESULT_OK}
     * 却两者皆空，于是它返回 null → WebView 拿到空文件列表 → 页面无任何反应。
     * 这里按「ClipData → getData → EXTRA_STREAM → extras 里的 ClipData」逐级兜底。
     *
     * @return 选中的 URI 列表；用户取消或确实没有 URI 时返回 null。
     */
    private static Uri[] extractUris(int resultCode, Intent data) {
        if (resultCode != Activity.RESULT_OK || data == null) return null;
        ArrayList<Uri> out = new ArrayList<Uri>();
        android.content.ClipData clip = data.getClipData();
        if (clip != null) {
            for (int i = 0; i < clip.getItemCount(); i++) {
                Uri u = clip.getItemAt(i).getUri();
                if (u != null) out.add(u);
            }
        }
        if (out.isEmpty() && data.getData() != null) out.add(data.getData());
        if (out.isEmpty()) {
            Object stream = data.getParcelableExtra(Intent.EXTRA_STREAM);
            if (stream instanceof Uri) out.add((Uri) stream);
        }
        if (out.isEmpty()) {
            Object raw = data.getParcelableExtra("android.intent.extra.CLIP_DATA");
            if (raw instanceof android.content.ClipData) {
                android.content.ClipData c2 = (android.content.ClipData) raw;
                for (int i = 0; i < c2.getItemCount(); i++) {
                    Uri u = c2.getItemAt(i).getUri();
                    if (u != null) out.add(u);
                }
            }
        }
        if (out.isEmpty()) return null;
        return out.toArray(new Uri[0]);
    }

    /**
     * 附件链路的可观测性：logcat 在 vivo 上会被系统吞掉，所以**同时**把关键节点
     * 回显到页面的 console（{@code [dsh-upload]} 前缀），CDP 侧可直接读。
     * @param msg 诊断信息（不可包含未转义的引号/换行）。
     */
    private static void diag(String msg) {
        Log.i(TAG, "[upload] " + msg);
        final WebView v = sView;
        if (v == null) return;
        String safe = msg.replace("\\", "\\\\").replace("'", "\\'").replace("\n", " ");
        final String js = "try{console.info('[dsh-upload] " + safe + "')}catch(e){}";
        v.post(new Runnable() {
            @Override public void run() {
                try { v.evaluateJavascript(js, null); } catch (Throwable ignored) { }
            }
        });
    }
}