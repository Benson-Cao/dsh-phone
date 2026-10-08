package com.dsh.harness;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.ViewGroup;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * 应用主入口。
 *
 * 启动顺序（全部在后台线程，避免首启解压 735MB 导致 ANR / 黑屏）：
 *   1. DshBootstrap.setupIfNeeded() —— 首次解包 + 补符号链接 + 打补丁 + 生成启动脚本
 *   2. DshProcessManager.start()    —— 后台拉起 dsh Web 服务
 *   3. WebView 等待 3080 端口就绪后加载 http://127.0.0.1:3080
 *
 * 期间显示"正在启动"界面，而不是黑屏。
 */
public class MainActivity extends Activity {

    /** 附件选择器的requestCode（转发给 {@link DshChromeClient#deliver}）。 */
    public static final int REQ_FILE_CHOOSER = 4001;

    private WebView webView;
    private DshChromeClient chromeClient;
    private LinearLayout loadingBox;
    private TextView statusText;
    private final Handler main = new Handler(Looper.getMainLooper());

    private String dshUrl() {
        return "http://127.0.0.1:" + DshProcessManager.DSH_PORT;
    }

    private void setStatus(final String s) {
        main.post(new Runnable() {
            @Override public void run() {
                if (statusText != null) statusText.setText(s);
            }
        });
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.parseColor("#0F1116"));

        // --- 加载界面（替代黑屏）---
        loadingBox = new LinearLayout(this);
        loadingBox.setOrientation(LinearLayout.VERTICAL);
        loadingBox.setGravity(Gravity.CENTER);
        loadingBox.setPadding(48, 48, 48, 48);

        ProgressBar pb = new ProgressBar(this);
        LinearLayout.LayoutParams pbLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pbLp.bottomMargin = 40;
        loadingBox.addView(pb, pbLp);

        TextView title = new TextView(this);
        title.setText("DeepSeek Harness");
        title.setTextColor(Color.parseColor("#E6E8EB"));
        title.setTextSize(22);
        title.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tLp.bottomMargin = 16;
        loadingBox.addView(title, tLp);

        statusText = new TextView(this);
        statusText.setText("正在准备运行环境…");
        statusText.setTextColor(Color.parseColor("#9AA0A6"));
        statusText.setTextSize(14);
        statusText.setGravity(Gravity.CENTER);
        loadingBox.addView(statusText);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(loadingBox);
        root.addView(scroll, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);

        // --- 耗时工作全部丢后台线程 ---
        new Thread(new Runnable() {
            @Override public void run() {
                setStatus("正在解包运行环境（首次约需 1–3 分钟）…");
                DshBootstrap.setupIfNeeded(MainActivity.this);

                // 解包失败：立刻把真实原因显示出来，别让用户对着黑屏猜
                final String bootErr = DshBootstrap.getLastError();
                if (bootErr != null) {
                    final long free = DshBootstrap.usableBytes(MainActivity.this);
                    main.post(new Runnable() {
                        @Override public void run() {
                            showDiagnostics("环境准备失败：\n\n" + bootErr
                                + "\n\n可用空间: " + (free < 0 ? "未知" : (free / 1048576) + " MB")
                                + "\n\n" + DshBootstrap.describeIntegrity(MainActivity.this)
                                + "\n点下面的“重试”会重新尝试解包；"
                                + "若是空间不足，请先清理手机存储。");
                        }
                    });
                    return;
                }

                setStatus("正在启动 dsh 服务…");
                boolean ok = DshProcessManager.start(MainActivity.this);

                if (!ok) {
                    // 启动失败：把真实日志显示在界面上，省得必须连电脑看 logcat
                    final String log = DshProcessManager.tailLog(MainActivity.this, 40);
                    final String msg = log.isEmpty() ? "（无日志，node 可能未启动）" : log;
                    setStatus("dsh 启动失败。日志：\n\n" + msg);
                    return;   // 停在错误页，不进入空白 WebView
                }

                DshProcessManager.waitForPort(DshProcessManager.DSH_PORT, 60000,
                    new Runnable() {
                        @Override public void run() {
                            // 端口就绪还不够：裸的 / 会返回 401 鉴权失败，
                            // 必须拿到 dsh 打印出来的带 token 的 URL 才能进页面。
                            setStatus("正在获取访问凭据…");
                            DshProcessManager.waitForTokenUrl(MainActivity.this, 30000,
                                new DshProcessManager.TokenUrlListener() {
                                    @Override public void onTokenUrl(String url) {
                                        // 不再把 303/Set-Cookie 交给 WebView 处理：
                                        // Java 侧自己走一遍握手，拿到 cookie 与真实状态码。
                                        setStatus("正在自检服务响应…");
                                        final DshProbe.Result r = DshProbe.run(MainActivity.this, url);
                                        if (r.rootOk) {
                                            final String cookie = r.cookiePair;
                                            main.post(new Runnable() {
                                                @Override public void run() {
                                                    if (cookie != null) {
                                                        android.webkit.CookieManager cm =
                                                            android.webkit.CookieManager.getInstance();
                                                        cm.setCookie("http://127.0.0.1:"
                                                            + DshProcessManager.DSH_PORT + "/", cookie);
                                                        cm.flush();
                                                    }
                                                    showWebView("http://127.0.0.1:"
                                                        + DshProcessManager.DSH_PORT + "/");
                                                }
                                            });
                                        } else {
                                            // 拿不到 200 就把原始证据摊在屏幕上（无 adb 时唯一渠道）
                                            main.post(new Runnable() {
                                                @Override public void run() {
                                                    showDiagnostics(r.report);
                                                }
                                            });
                                        }
                                    }
                                });
                        }
                    });
            }
        }).start();
    }

    /**
     * 自检没通过时把原始证据摊在屏幕上（这是没有 adb 时唯一能看到真相的渠道）。
     * 文本可长按选中复制；底部给一个"重试"。
     */
    private void showDiagnostics(String report) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(24, 24, 24, 24);

        TextView head = new TextView(this);
        head.setText("dsh 服务自检未通过");
        head.setTextColor(Color.parseColor("#FF6B6B"));
        head.setTextSize(18);
        LinearLayout.LayoutParams hLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hLp.bottomMargin = 16;
        box.addView(head, hLp);

        TextView tv = new TextView(this);
        tv.setText(report);
        tv.setTextColor(Color.parseColor("#C9CDD2"));
        tv.setTextSize(11);
        tv.setTypeface(android.graphics.Typeface.MONOSPACE);
        tv.setTextIsSelectable(true);
        box.addView(tv);

        Button retry = new Button(this);
        retry.setText("重试");
        retry.setOnClickListener(new android.view.View.OnClickListener() {
            @Override public void onClick(android.view.View v) {
                finish();
                startActivity(getIntent());
            }
        });
        LinearLayout.LayoutParams rLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rLp.topMargin = 24;
        box.addView(retry, rLp);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.parseColor("#0F1116"));
        scroll.addView(box);
        setContentView(scroll);
    }

    private void showWebView(String url) {
        webView = new WebView(this);
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);
        // 让 dsh 下发的 dsh-auth cookie 能落盘，否则跳回 / 又是 401
        android.webkit.CookieManager.getInstance().setAcceptCookie(true);
        // 附件：WebView 默认不弹文件选择器，必须挂 WebChromeClient
        chromeClient = new DshChromeClient(this);
        webView.setWebChromeClient(chromeClient);
        webView.setWebViewClient(new DshWebViewClient());
        webView.setBackgroundColor(Color.parseColor("#0F1116"));
        setContentView(webView);

        webView.loadUrl(url);

        // 已进入 dsh 界面后，若服务其实没起来，onReceivedError 会显示友好页
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        // 附件选择结果必须先回灌给 WebView，否则页面拿不到文件
        if (chromeClient != null && chromeClient.deliver(requestCode, resultCode, data)) return;
        super.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK && webView != null && webView.canGoBack()) {
            webView.goBack();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }
}
