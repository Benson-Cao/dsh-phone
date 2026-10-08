package com.dsh.harness;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
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

    // ===== 设计稿《移动端 UI 设计系统》token =====
    // 唯一依据：资料库页面《移动端 UI 设计系统》的 :root 变量表。
    // 之前 App 用的是深色 #0F1116，与设计稿的浅色体系冲突（也导致状态栏配色别扭）。
    private static final int BRAND_500 = 0xFF2B8AE8;  // 鲨鱼蓝，主色
    private static final int INK_900   = 0xFF0D1B2E;  // 描边深藏青，正文
    private static final int INK_500   = 0xFF5A6D84;  // 次级文字
    private static final int INK_400   = 0xFF8296AB;  // 弱化文字
    private static final int INK_50    = 0xFFF7F9FC;  // 页面底色

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private LinearLayout.LayoutParams subLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        return lp;
    }

    /** 水平线性渐变（用于品牌渐变细线）。 */
    private android.graphics.drawable.GradientDrawable gradient(
            int x1, int x2, int c1, int c2) {
        android.graphics.drawable.GradientDrawable g =
            new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{c1, c2});
        g.setCornerRadius(dp(2));
        return g;
    }

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
        // 设计稿「启动页 Splash」：品牌渐显 + 浅色底（--ink-50 #f7f9fc）。
        // 之前是深色 #0F1116 +系统深色状态栏，与设计稿的浅色体系完全冲突
        //（也是 r20「红色状态栏」刺眼的根源：深色 App 配浅色系统栏）。
        root.setBackgroundColor(INK_50);

        // --- 加载界面（替代黑屏），按设计稿重写 ---
        loadingBox = new LinearLayout(this);
        loadingBox.setOrientation(LinearLayout.VERTICAL);
        // CENTER = CENTER_VERTICAL | CENTER_HORIZONTAL，配合 ScrollView.fillViewport
        // 才是真正的「整体垂直居中」（只给 CENTER_HORIZONTAL 时垂直方向贴顶）。
        loadingBox.setGravity(Gravity.CENTER);
        // ⚠️ 上下不要留padding：fillViewport 会把 loadingBox 拉伸到一屏高，
        //   但上下 padding 会让内容整体下移，鲸鱼看起来"靠上"。
        //   垂直居中完全交给 gravity，padding 只留左右。
        loadingBox.setPadding(dp(32), 0, dp(32), 0);

        // 品牌图标 36×36pt 透明底（设计稿「首页布局规格」）—— 复用 adaptive 前景。
        // 用 getIdentifier 而非 R.mipmap.*：纯 javac 校验时没有生成的 R 类。
        ImageView logo = new ImageView(this);
        int fgId = getResources().getIdentifier("dsh_foreground", "mipmap", getPackageName());
        if (fgId != 0) logo.setImageResource(fgId);
        LinearLayout.LayoutParams logoLp = new LinearLayout.LayoutParams(dp(72), dp(72));
        logoLp.bottomMargin = dp(20);
        loadingBox.addView(logo, logoLp);

        // Display / 800 —— 设计稿字体层级
        TextView title = new TextView(this);
        title.setText("DeepSeek Harness");
        title.setTextColor(INK_900);
        title.setTextSize(26);
        title.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
        title.setLetterSpacing(0.02f);
        title.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tLp.bottomMargin = dp(10);
        loadingBox.addView(title, tLp);

        // 副标题：ink-500
        TextView sub = new TextView(this);
        sub.setText("正在初始化 Agent 运行时…");
        sub.setTextColor(INK_500);
        sub.setTextSize(13);
        sub.setGravity(Gravity.CENTER);
        loadingBox.addView(sub, subLp());

        statusText = new TextView(this);
        statusText.setText("正在准备运行环境…");
        statusText.setTextColor(INK_400);
        statusText.setTextSize(12);
        statusText.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams stLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        stLp.topMargin = dp(14);
        loadingBox.addView(statusText, stLp);

        // 品牌渐变细线（--brand-500 →透明），替代原来的 ProgressBar
        View rule = new View(this);
        rule.setBackground(gradient(0, 100, BRAND_500, 0x00FFFFFF));
        LinearLayout.LayoutParams rLp = new LinearLayout.LayoutParams(dp(120), dp(3));
        rLp.topMargin = dp(18);
        loadingBox.addView(rule, rLp);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(INK_50);
        // ⚠️ 必须开 fillViewport：ScrollView 的子View 默认只按内容高度布局，
        //    此时 LinearLayout 的 gravity(CENTER) **不会**产生垂直居中效果
        //    （真机表现：整块内容贴在上方，图标刚好在状态栏下面）。
        //    fillViewport 让子View 在内容不足一屏时**被拉伸到一屏高**，
        //    gravity 生效；内容超过一屏时仍可正常滚动。
        scroll.setFillViewport(true);
        scroll.addView(loadingBox);
        root.addView(scroll, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);

        // 浅色底→ 深色状态栏图标；状态栏背景 = --ink-50（与启动页同色）。
        getWindow().setStatusBarColor(INK_50);
        getWindow().setNavigationBarColor(INK_50);
        int sflags = getWindow().getDecorView().getSystemUiVisibility();
        //这次要**设置** LIGHT_STATUS_BAR（浅底深图标），与 r20 相反
        getWindow().getDecorView().setSystemUiVisibility(
            sflags | android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);

        // 品牌渐显：标题从 0.3alpha 淡入到 1（设计稿「品牌渐显」）
        title.setAlpha(0.3f);
        title.animate().alpha(1f).setDuration(700).setStartDelay(120).start();
        sub.setAlpha(0f);
        sub.animate().alpha(1f).setDuration(700).setStartDelay(320).start();

        // --- 耗时工作全部丢后台线程 ---
        new Thread(new Runnable() {
            @Override public void run() {
                final long tBoot = System.currentTimeMillis();
                setStatus("正在解包运行环境（首次约需 1–3 分钟）…");
                DshBootstrap.setupIfNeeded(MainActivity.this);
                final long tBootDone = System.currentTimeMillis();
                Log.i("MainActivity", "[计时] bootstrap " + (tBootDone - tBoot) + "ms");

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
                // 端口已监听 → dsh 进程还活着（切后台 / 重建 Activity 时常见），直接复用，
                // 省掉 15s node 冷启动；否则才拉起。
                final boolean reused =
                        DshProcessManager.isPortOpen(DshProcessManager.DSH_PORT);
                final long tStart = System.currentTimeMillis();
                if (reused) {
                    Log.i("MainActivity", "dsh 端口已监听，复用现有进程，不重新拉起");
                } else {
                    boolean ok = DshProcessManager.start(MainActivity.this);
                    if (!ok) {
                        // 启动失败：把真实日志显示在界面上，省得必须连电脑看 logcat
                        final String log = DshProcessManager.tailLog(MainActivity.this, 40);
                        final String msg = log.isEmpty() ? "（无日志，node 可能未启动）" : log;
                        setStatus("dsh 启动失败。日志：\n\n" + msg);
                        return;   // 停在错误页，不进入空白 WebView
                    }
                }
                // 预热 WebView（主线程）：渲染进程冷启动要 1~3s，与 node 启动并行，去掉串行等待
                main.post(new Runnable() {
                    @Override public void run() { prepareWebView(); }
                });

                // 统一等待：**只有「根路径真的返回 200」才算就绪**。
                // 端口打开、打印 token 都不作数 —— dsh 可能在 frontend-static
                // 注册 fallback 之前就打印 token，那时任何路径都是 404 空响应体。
                // （真机表现：首次启动误报失败，重试就好 —— 就是踩了这个坑。）
                final long t0 = System.currentTimeMillis();
                final long deadline = t0 + 120000;
                while (System.currentTimeMillis() < deadline) {
                    DshProbe.Ready ready = DshProbe.tryReady(MainActivity.this);
                    if (ready != null) {
                        final String cookie = ready.cookiePair;
                        final long tReady = System.currentTimeMillis();
                        final long dBoot = tBootDone - tBoot;
                        final long dWait = tReady - tStart;
                        Log.i("MainActivity", "[计时] 总 " + (tReady - tBoot)
                                + "ms | bootstrap " + dBoot
                                + "ms | " + (reused ? "复用" : "冷启") + " " + dWait + "ms");
                        // 直接显示在启动页上——真机没有 adb 时，这是唯一的取证渠道
                        setStatus("已用 " + ((tReady - tBoot) / 1000) + " 秒"
                                + "（环境 " + dBoot + "ms"
                                + " / " + (reused ? "复用服务" : "启动服务") + " " + dWait + "ms）");
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
                        return;
                    }
                    final int sec = (int) ((System.currentTimeMillis() - t0) / 1000);
                    setStatus("正在启动 dsh 服务…"
                        + (sec >= 1 ? "（已用 " + sec + " 秒）" : ""));
                    try { Thread.sleep(250); } catch (InterruptedException e) { return; }
                }

                // 超时：把原始证据摊在屏幕上（没有 adb 时唯一渠道）
                final long spent = System.currentTimeMillis() - t0;
                final String report = DshProbe.diagnose(MainActivity.this, spent);
                main.post(new Runnable() {
                    @Override public void run() { showDiagnostics(report); }
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
        scroll.setBackgroundColor(INK_50);
        scroll.addView(box);
        setContentView(scroll);
    }

    /**
     * 预热 WebView：提前创建并 load 空白页，让渲染进程在 node 启动期间就起来。
     * 这样等轮询探测到 200 时，WebView 已经 ready，不必再串行等 1~3s 的渲染进程冷启动。
     */
    private void prepareWebView() {
        if (webView != null) return;
        if (isFinishing() || isDestroyed()) return;   // Activity 已退出就不必建了
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
        // JS bridge：设置页的「插件市场」入口靠它触发原生安装
        // （dshmarket 要落盘到 $HOME/.dsh/profiles/web/，WebView 里的 JS 没有文件系统权限）
        try {
            webView.addJavascriptInterface(new DshBridge(this), "dshNative");
        } catch (Exception e) {
            Log.e("MainActivity", "注册 JS bridge 失败", e);
        }
        webView.setBackgroundColor(INK_50);
        webView.loadUrl("about:blank");   // 仅预热，不显示
        Log.i("MainActivity", "WebView 已预热");
    }

    /** DshBridge 需要在安装完成后回调 JS，故暴露 webView 引用。 */
    android.webkit.WebView webViewRef() {
        return webView;
    }

    private void showWebView(String url) {
        if (webView == null) prepareWebView();   // 极端情况：预热还没跑就就绪
        setContentView(webView);
        webView.loadUrl(url);
        Log.i("MainActivity", "已进入 dsh：loadUrl " + url);
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
