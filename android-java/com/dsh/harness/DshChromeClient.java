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
    /** WebView 只允许一个待处理的回调；新的选择请求到来时先把旧的以 null 交掉。 */
    private ValueCallback<Uri[]> pending;

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

    @Override
    public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                     FileChooserParams params) {
        if (pending != null) {
            try { pending.onReceiveValue(null); } catch (Exception ignored) { }
        }
        pending = callback;

        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");

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

        try {
            act.startActivityForResult(intent, MainActivity.REQ_FILE_CHOOSER);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "打开文件选择器失败", e);
            pending = null;
            Toast.makeText(act, "没有找到可用的文件管理器", Toast.LENGTH_LONG).show();
            // 返回 false 让 WebView 走它自己的默认处理
            return false;
        }
    }

    /**
     * 由 {@code Activity.onActivityResult} 转发。
     * @return true 表示这次回调已被消费。
     */
    public boolean deliver(int requestCode, int resultCode, Intent data) {
        if (requestCode != MainActivity.REQ_FILE_CHOOSER) return false;
        ValueCallback<Uri[]> cb = pending;
        pending = null;
        if (cb == null) return true;

        Uri[] result = null;
        try {
            if (resultCode == Activity.RESULT_OK) {
                result = FileChooserParams.parseResult(resultCode, data);
            }
        } catch (Exception e) {
            Log.w(TAG, "解析选择结果失败: " + e.getMessage());
        }
        cb.onReceiveValue(result);
        return true;
    }
}