package com.dsh.harness;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.util.Log;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.widget.Toast;

import java.util.ArrayList;

/**
 * 补上 WebView 缺失的"选择文件"能力。
 *
 * Android WebView 默认**不实现** {@link WebChromeClient#onShowFileChooser}：
 * 页面里的 `<input type="file">` 被点击时既不弹选择器、也不回调，
 * 表现就是"附件按钮点了没反应"。
 * 这里把它接到系统文件选择器（ACTION_GET_CONTENT）上，并把结果回灌给页面。
 */
public class DshChromeClient extends WebChromeClient {

    private static final String TAG = "DshChromeClient";

    private final Activity act;
    /** WebView 只允许一个待处理的回调；新的选择请求到来时先把旧的以 null 交掉。 */
    private ValueCallback<Uri[]> pending;

    public DshChromeClient(Activity act) {
        this.act = act;
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