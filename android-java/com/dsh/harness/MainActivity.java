package com.dsh.harness;

import android.app.Activity;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.provider.Settings;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import java.io.File;
import android.widget.TextView;

import org.json.JSONObject;

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

    /** 「另存为」的请求码（方案 C，见 {@link DshSafBridge#saveAs}）。 */
    public static final int REQ_SAVE_AS = 4002;

    /** 选择 SAF 存储目录的请求码（方案 A，见 {@link DshSafBridge#safPickDir}）。 */
    public static final int REQ_SAF_DIR = 4003;

    /** 待写入「另存为」目标的源文件（onActivityResult 要用它）。 */
    private File pendingSaveSrc = null;

    // ===== 设计稿《移动端 UI 设计系统》token =====
    // 唯一依据：资料库页面《移动端 UI 设计系统》的 :root 变量表。
    // 之前 App 用的是深色 #0F1116，与设计稿的浅色体系冲突（也导致状态栏配色别扭）。
    private static final int BRAND_500 = 0xFF2B8AE8;  // 鲨鱼蓝，主色
    // ⚠️ INK_900 / INK_400 / INK_50 只在**渐变线等品牌元素**上直接用；
    //    所有「会随深浅色切换」的用色一律走 {@link DshPalette}。
    //    r79：启动页加深色模式，原先这里硬编码导致深色系统下白底黑字（刺眼）。
    private static final int INK_900   = 0xFF0D1B2E;  // 描边深藏青，正文
    private static final int INK_400   = 0xFF8296AB;  // 弱化文字
    private static final int INK_50    = 0xFFF7F9FC;  // 页面底色（仅浅色模式，见 DshPalette.bg）

    /** 启动页各处视图引用：主题切换时要能就地改色，故持有点击得到。 */
    private View splashRoot;
    private ScrollView splashScroll;
    private TextView splashTitle;
    private View splashRule;

    /**
     * 应用当前调色板到启动页的全部视图。
     *
     * <p>抽成方法而不是写在 onCreate 里，是为了 {@link #onPageFinished} 拿到
     * dsh 真实主题后能**再跑一次**修正（见 {@link DshPalette#refreshFromDsh}）。
     */
    private void applyPalette() {
        int bg = DshPalette.bg(this);
        if (splashRoot != null) splashRoot.setBackgroundColor(bg);
        if (splashScroll != null) splashScroll.setBackgroundColor(bg);
        if (splashTitle != null) splashTitle.setTextColor(DshPalette.text(this));
        if (statusText != null) statusText.setTextColor(DshPalette.muted(this));
        if (splashRule != null) {
            //渐变末端必须渐变到**底色**，不能固定白色（深底下会泛白雾）
            splashRule.setBackground(gradient(0, 100, BRAND_500, DshPalette.fadeToBg(this)));
        }
        getWindow().setStatusBarColor(DshPalette.systemBar(this));
        getWindow().setNavigationBarColor(DshPalette.systemBar(this));
        getWindow().getDecorView().setSystemUiVisibility(
                DshPalette.systemUiFlags(this, getWindow().getDecorView().getSystemUiVisibility()));
        DshPalette.logContrast(this);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
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

    /**
     * r65：返回键去重时间戳。同一次物理按下在部分系统上会先走
     * {@link #onKeyDown} 再走 {@link #onBackPressed}，两者共用一个实现，
     * 必须防重复处理（否则一次返回退两级，甚至直接退出 App）。
     */
    private long mLastBackAt = 0L;

    /** r65：注入脚本上报的"当前返回该由 JS 处理"（设置面板 / 抽屉开着）。 */
    private volatile boolean mJsBackHandled = false;

    /**
     * WebView 历史是否已清理过（每个 App 进程一次）。r60 起只在首次清理 ——
     * 详见 {@link #onResume()} 里那段说明。
     */
    private static boolean sHistoryCleared = false;
    private LinearLayout loadingBox;
    private TextView statusText;
    private final Handler main = new Handler(Looper.getMainLooper());

    /** WebView 是否已真正进入（用于避免 DSH_STATE 错误诊断覆盖正常界面）。 */
    private boolean webShown = false;

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

        // r107：开 WebView 远程调试（仅 debug 构建有价值，release 会被 R8 掉也无所谓）。
        //   为什么必须开：vivo 机型会吞掉第三方应用的 logcat（实测 persist.log.tag=VERBOSE
        //   且进程 alive，logcat --pid 仍 0 行），页面内 JS 的执行情况完全看不到。
        //   开了之后可以用 chrome://inspect 或 CDP 直连，直接读 DOM / console / 执行 JS，
        //   排查前端问题不再依赖 logcat。
        try {
            android.webkit.WebView.setWebContentsDebuggingEnabled(true);
        } catch (Throwable t) {
            android.util.Log.w("MainActivity", "远程调试开关失败：" + t.getMessage());
        }

        // r107：SAF 代理必须在拉起 dsh **之前**就位（幂等）。
        //   为什么不能只靠 DshService：DshService 并没有在 AndroidManifest 里注册，
        //   startForegroundService 每次都抛（被 catch 吞掉），onStartCommand 永不回调
        //   → 那条「代理随服务起停」的路径实际上是死的。
        //   后果：上一次进程留下的 saf_config.json 仍是 enabled:true + 已失效的端口/token，
        //   node 侧 shim 会拿着它去撞一个没人监听的端口，每次 SAF 操作白等 20s 超时。
        //   这里起一次即可：未配置 → 只写 enabled:false；已配置 → 用新端口/token 覆盖。
        //   于是配置文件永远与真实状态一致（shim 按 mtime 现读，不需要重启 dsh）。
        SafProxyServer.start(this);

        // r79：必须在任何取色之前读上次记住的 dsh 主题，
        // 否则启动页会先按系统主题画一遍、再改一次（肉眼可见的闪色）。
        DshPalette.preloadFromPrefs(this);

        FrameLayout root = new FrameLayout(this);
        // 设计稿「启动页 Splash」：品牌渐显 + 浅色底（--ink-50 #f7f9fc）。
        // 之前是深色 #0F1116 +系统深色状态栏，与设计稿的浅色体系完全冲突
        //（也是 r20「红色状态栏」刺眼的根源：深色 App 配浅色系统栏）。
        root.setBackgroundColor(DshPalette.bg(this));
        splashRoot = root;   // r79：留给 applyPalette() 二次改色

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
        title.setTextColor(DshPalette.text(this));
        title.setTextSize(26);
        title.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
        title.setLetterSpacing(0.02f);
        title.setGravity(Gravity.CENTER);
        splashTitle = title;   // r79：applyPalette() 二次改色要用
        LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tLp.bottomMargin = dp(10);
        loadingBox.addView(title, tLp);

        // 设计稿「启动页 / 服务初始化」：**只保留**鲸鱼标 → 标题 → 启动服务文案 → 进度条。
        // 标注原文：「已按标注去掉『正在初始化 Agent 运行时…』文案」，
        // 所以这里**不再**添加任何中间副标题行。
        statusText = new TextView(this);
        statusText.setText("正在准备运行环境…");
        statusText.setTextColor(DshPalette.muted(this));
        // r79：12px → 14px。既然浅色下 muted 已被迫压深到接近正文
        //（见 DshPalette.L_MUTED 的实测说明），层级就靠字号表达。
        // 14px 仍是小字，AA 依旧要求 4.5:1 —— 已达标。
        statusText.setTextSize(14);
        statusText.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams stLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        stLp.topMargin = dp(14);
        loadingBox.addView(statusText, stLp);

        // 品牌渐变细线（--brand-500 →透明），替代原来的 ProgressBar
        View rule = new View(this);
        // ⚠️ r79：末端透明色取**当前底色**，不再写死 0x00FFFFFF。
        //   深色底下若渐变到"白色透明"，末端会泛出白雾，比不渐变更显眼。
        rule.setBackground(gradient(0, 100, BRAND_500, DshPalette.fadeToBg(this)));
        splashRule = rule;
        LinearLayout.LayoutParams rLp = new LinearLayout.LayoutParams(dp(120), dp(3));
        rLp.topMargin = dp(18);
        loadingBox.addView(rule, rLp);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(DshPalette.bg(this));
        splashScroll = scroll;
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

        // 接入 DSH_STATE 协议（contract.md §6）：运行时把结构化状态写进 dsh-web.log，
        // DshProcessManager 增量扫描并回调此处，统一驱动启动页状态文案 / 错误诊断。
        // 观察者内部用 runOnUiThread 切回主线程，避免在工作线程改 UI。
        DshProcessManager.setObserver(new DshProcessManager.StateObserver() {
            @Override public void onState(final JSONObject s) {
                main.post(new Runnable() {
                    @Override public void run() { handleDshState(s); }
                });
            }
            @Override public void onLog(final String line, final int level) {
                // 启动页暂无实时日志面板；保留接口，后续可接诊断页。
                // level 已按 [ERR]/[WARN]/其余 分类（0=info,1=warn,2=err）。
            }
        });

        // r79：状态栏/导航栏配色统一走 applyPalette()（浅底→深图标，深底→浅图标）。
        //原来这里写死 INK_50 + LIGHT_STATUS_BAR，深色系统下会出现"浅状态栏 + 深页面"割裂。
        applyPalette();

        // 品牌渐显：标题从 0.3alpha 淡入到 1（设计稿「品牌渐显」）
        title.setAlpha(0.3f);
        title.animate().alpha(1f).setDuration(700).setStartDelay(120).start();

        // --- 耗时工作全部丢后台线程 ---
        new Thread(new Runnable() {
            @Override public void run() {
                final long tBoot = System.currentTimeMillis();

                // ===== 常驻服务 =====
                // 启动 DshService，让 dsh 在 App 退出后仍存活。
                // 这样第二次打开只需探测端口（亚秒级），不必等 node 冷启动。
                DshService.start(MainActivity.this);

                // ⚠️ **先探测端口**：若 DshService 已经把 dsh 拉起来了，
                //   这里应立刻短路返回，不要再走一遍 bootstrap + start
                //   （那会把秒开浪费掉）。这是"8s → 亚秒"的关键分支。
                if (DshProcessManager.isPortOpen(DshProcessManager.DSH_PORT)) {
                    Log.i("MainActivity", "[计时] 端口已就绪（常驻服务命中），跳过 bootstrap");
                    waitThenShowWeb(tBoot, 0, 0, true);
                    return;
                }

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
                // dWait = 从「决定拉起/复用」到「就绪」的耗时
                waitThenShowWeb(tBoot, tBootDone - tBoot, 0, reused);
            }
        }).start();
    }

    /**
     * 等待 dsh 就绪并进入 WebView —— 常驻命中与冷启动两条路径**共用**。
     *
     * @param tBoot流程起点（用于展示总耗时）
     * @param dBoot bootstrap 耗时（常驻命中时传 0）
     * @param dWait  拉起/等待耗时（常驻命中时传 0）
     * @param reused 是否命中常驻服务
     */
    private void waitThenShowWeb(final long tBoot, final long dBoot0,
            final long dWait0, final boolean reused) {
        final long dBoot = dBoot0;
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
                // 等待耗时：常驻命中时 dWait0=0，这里算的就是"探测到就绪"那点时间
                final long dWait = dWait0 > 0 ? dWait0 + (tReady - t0) : (tReady - t0);
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
        scroll.setBackgroundColor(DshPalette.bg(this));
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
        // C-03：显式收紧 WebView 安全开关（默认值在部分 ROM 上不可信）
        s.setAllowContentAccess(false);
        s.setAllowFileAccessFromFileURLs(false);
        s.setAllowUniversalAccessFromFileURLs(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
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
            // 方案 C：文件导出桥（dshSaf）。单独一个对象名，避免和插件市场逻辑混在一起。
            webView.addJavascriptInterface(new DshSafBridge(this), "dshSaf");
        } catch (Exception e) {
            Log.e("MainActivity", "注册 JS bridge 失败", e);
        }
        webView.setBackgroundColor(DshPalette.bg(this));
        webView.loadUrl("about:blank");   // 仅预热，不显示
        Log.i("MainActivity", "WebView 已预热");
    }

    /** DshBridge 需要在安装完成后回调 JS，故暴露 webView 引用。 */
    android.webkit.WebView webViewRef() {
        return webView;
    }

    /**
     * 处理一条 DSH_STATE 状态（已在主线程）。contract.md §6：
     * - error → 更新文案，并在 WebView 尚未进入时弹结构化诊断（错误码 + 消息 + 日志）；
     * - ready/retrying/start_dsh 等 → 仅更新状态文案；
     * - ready **不在此加载 WebView**：避免"token 已打印但服务未就绪"的假就绪，
     *   WebView 加载仍由 DshProbe.tryReady 的「根路径真返回 200」把关；
     * - 带 progress 时附百分比。
     */
    private void handleDshState(JSONObject s) {
        try {
            String state = s.optString("state", "");
            String msg = s.optString("msg", "");
            int progress = s.optInt("progress", -1);
            // r73（A2）：crashed 与 error 同属「启动链断了」，但语义不同——
            //   error 是我们自己判定的不可恢复错误，crashed 是 node 进程意外退出。
            //   两者都要在 WebView 未进入时弹诊断页，否则用户只看到一句文案、
            //   不知道该去看什么日志。
            if ("error".equals(state) || "crashed".equals(state)) {
                String code = s.optString("code", "UNKNOWN");
                setStatus("启动错误 [" + code + "]: " + msg);
                if (!webShown) {
                    showDiagnostics("启动失败 [" + code + "]:\n\n" + msg
                            + "\n\n（运行时通过 DSH_STATE 上报，详情见下方日志）\n\n"
                            + DshProcessManager.tailLog(MainActivity.this, 40));
                }
                return;
            }
            StringBuilder sb = new StringBuilder(msg);
            if (progress >= 0) sb.append(" (").append(progress).append("%)");
            setStatus(sb.toString());
        } catch (Exception e) {
            Log.w("MainActivity", "handleDshState 解析失败", e);
        }
    }

    /**
 * 按 dsh 页面的真实主题刷新**系统栏**（状态栏 + 导航栏）。
 *
     * <p>r79 真机发现：进入 dsh 后状态栏一直沿用启动页的浅色，而页面已是深色，
     * 顶部一条白边。因为 dsh 页面自己不管理系统栏（mobile.js 无相关逻辑），
     * Java 侧就必须在拿到页面主题后补这一刀。
     *
     * <p>只改系统栏，**不动页面视图** —— 此时 setContentView 已是 WebView。
     */
    public void refreshSystemBarsForTheme() {
        try {
            // ⚠️ 这里必须用 systemBar()（与 dsh 首页背景对齐），
            //   不能用 bg()（我们自己的启动页底色，偏蓝）——
            //   两者相邻会出现可见色差：深色下状态栏"发蓝"（用户 2026-10-13 反馈）。
            int sb = DshPalette.systemBar(this);
            getWindow().setStatusBarColor(sb);
            getWindow().setNavigationBarColor(sb);
            getWindow().getDecorView().setSystemUiVisibility(
                    DshPalette.systemUiFlags(this,
                            getWindow().getDecorView().getSystemUiVisibility()));
            Log.i("MainActivity", "[dsh] 系统栏已按页面主题刷新 #"
                    + String.format("%06X", 0xFFFFFF & sb));
        } catch (Throwable t) {
            Log.w("MainActivity", "刷新系统栏失败: " + t.getMessage());
        }
    }

    private void showWebView(String url) {
        if (webView == null) prepareWebView();   // 极端情况：预热还没跑就就绪
        // r79 真机发现：进入 dsh 后**状态栏仍是启动页的浅色**，
        //   而页面已经是深色 → 顶部一条白边，深色下非常刺眼（真机截图确认）。
        //   原因：dsh 页面自己不管系统栏（mobile.js 里没有 statusBar 相关逻辑），
        //   而 showWebView() 也没有重新下发配色，于是沿用了 applyPalette() 当时的值。
        // 这里用**当前页面的实际主题**重刷一次系统栏。
        applyPalette();
        webView.loadUrl(url);
        webShown = true;
        Log.i("MainActivity", "已进入 dsh：loadUrl " + url);
        // 已进入 dsh 界面后，若服务其实没起来，onReceivedError 会显示友好页

        // r106：原来这里直接 setContentView(webView) 硬切 —— 冷启十几秒后画面
        //   "啪"一下替换，深色下那一下白闪很明显。改为交叉淡入，见下。
        crossfadeSplashToWeb();
    }

    /**
     * r106 交互动效：启动页 → dsh 页面交叉淡入。
     *
     * 取代原来的 {@code setContentView(webView)} 硬切。冷启要十几秒，
     * 用户盯了半天，最后一帧"啪"地换掉，观感上像是闪了一下。
     *
     * ⚠️ 两个坑，改前务必看懂：
     *   1. 只能淡出**启动页的子元素**，绝不能淡出 splashRoot 本身 ——
     *      webView 是作为它的子视图加进去的，淡父视图会把刚要显示的内容
     *      一起淡掉，结果就是白屏。
     *   2. 必须读 ANIMATOR_DURATION_SCALE：用户在开发者选项里把动画时长
     *      调成 0（关闭动画）时要退回硬切，别自作主张地演。
     *      这是 Android 的无障碍约定，不是可选项。
     */
    private void crossfadeSplashToWeb() {
        float animScale = 1f;
        try {
            animScale = Settings.Global.getFloat(getContentResolver(),
                    Settings.Global.ANIMATOR_DURATION_SCALE, 1f);
        } catch (Throwable ignored) {
            // 极少数 ROM 读不到该设置：按"动画开着"处理，不至于崩
        }

        // 没有启动页 / webView 还没建好 / 用户关了动画 → 退回原来的硬切
        if (splashRoot == null || !(splashRoot instanceof ViewGroup)
                || webView == null || animScale == 0f) {
            setContentView(webView);
            return;
        }

        final ViewGroup host = (ViewGroup) splashRoot;

        // WebView 加在**最上层**淡入（addView 追加到末尾 = 顶层）
        webView.setAlpha(0f);
        host.addView(webView, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        // 进场减速（对应 CSS 的 --ease-out）：320ms
        webView.animate().alpha(1f).setDuration(320)
                .setInterpolator(new DecelerateInterpolator()).start();

        // 退场加速（对应 CSS 的 --ease-in）：离场不该恋恋不舍
        for (int i = 0; i < host.getChildCount(); i++) {
            final View v = host.getChildAt(i);
            if (v == webView) continue;          // 别把自己也淡掉
            v.animate().alpha(0f).scaleX(1.04f).scaleY(1.04f).setDuration(220)
                    .setInterpolator(new AccelerateInterpolator())
                    .setListener(new AnimatorListenerAdapter() {
                        @Override public void onAnimationEnd(Animator a) {
                            try {
                                host.removeView(v);   // 释放合成层，别白占内存
                            } catch (Throwable ignored) { }
                        }
                    }).start();
        }
        Log.i("MainActivity", "[动效] 启动页 → dsh 交叉淡入");
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // r79：系统深浅色变了 → 之前缓存的 dsh 主题判断立刻作废。
        //   dsh 的深色默认跟随系统，用户中途改系统设置时缓存就成了过期值，
        //   而 isDark() 优先用它 → 启动页会继续按旧主题画。
        DshPalette.invalidateCacheOnSystemThemeChange(this);
        // r79：系统深浅色切换时重刷启动页配色。
        // 当前 manifest 的 configChanges 没含 uiMode，所以正常路径是重建 Activity
        // （onCreate 会重跑，同样拿到正确配色）；这个回调是为将来加了 uiMode 之后准备的，
        // 也覆盖「WebView 在屏上、状态栏需要立刻跟上」的情况。
        // ⚠️ 只有在启动页可见时刷新：已进入 dsh 后界面由 WebView 负责，
        //    强改状态栏反而会与页面主题打架。
        if (splashRoot != null && webShown != true) {
            applyPalette();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        // 附件选择结果必须先回灌给 WebView，否则页面拿不到文件
        if (chromeClient != null && chromeClient.deliver(requestCode, resultCode, data)) return;

        // 方案 C：「另存为」——把私有文件写到用户选的位置（主线程）
        if (requestCode == REQ_SAVE_AS) {
            File src = pendingSaveSrc;
            pendingSaveSrc = null;      // 一次性，先清，防止重复消费
            if (resultCode != RESULT_OK || data == null || data.getData() == null) {
                Log.i("MainActivity", "用户取消了另存为");
                return;
            }
            final boolean ok = DshSafBridge.completeSave(this, src, data.getData());
            setStatus(ok ? "已导出 " + src.getName() : "导出失败：" + src.getName());
            return;
        }

        // 方案 A：用户选定了外部存储目录（挂载到 $HOME/saf）
        if (requestCode == REQ_SAF_DIR) {
            onSafDirPicked(resultCode, data);
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    /**
     * 发起「另存为」（方案 C）。由 {@link DshSafBridge#saveAs} 在主线程调用。
     *
     * <p>只接受白名单内的文件——即使调用方忘了校验，这里再挡一道。
     */
    void startSaveAs(File src, Intent createDocIntent) {
        if (src == null || !DshExporter.isExportable(this, src)) {
            Log.w("MainActivity", "startSaveAs 拒绝（白名单外）: " + src);
            return;
        }
        pendingSaveSrc = src;
        try {
            startActivityForResult(createDocIntent, REQ_SAVE_AS);
        } catch (Throwable e) {
            pendingSaveSrc = null;
            Log.w("MainActivity", "startActivityForResult 失败: " + e.getMessage());
        }
    }

    /**
     * 处理 SAF 目录选择结果（方案 A）。
     *
     * <p>关键一步是 {@code takePersistableUriPermission}：少了它，授权只在本次
     * Activity 存活期间有效，进程一重启 {@code ContentResolver} 就抛
     * {@code SecurityException} —— 这是 SAF 最常见的坑，且只在真机重启后才暴露。
     */
    private void onSafDirPicked(int resultCode, Intent data) {
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            Log.i("MainActivity", "用户取消了选择存储目录");
            return;
        }
        Uri tree = data.getData();
        int flags = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION
            | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        try {
            getContentResolver().takePersistableUriPermission(tree, flags);
        } catch (Throwable t) {
            Log.w("MainActivity", "固化 SAF 授权失败: " + t.getMessage());
        }
        String name = readableName(tree);
        SafConfig.save(this, tree, name);
        SafProxyServer.start(this);      // 幂等；配置每次请求现读，换目录无需重启
        Log.i("MainActivity", "已挂载 SAF 目录: " + name);
        setStatus("已挂载存储目录：" + name);
    }

    /**
     * 打开系统「选择目录」界面（方案 A）。由 {@link DshSafBridge#safPickDir} 在主线程调用。
     */
    void startSafPick() {
        try {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            startActivityForResult(i, REQ_SAF_DIR);
        } catch (Throwable t) {
            Log.w("MainActivity", "打开目录选择器失败: " + t.getMessage());
        }
    }

    /** 把 {@code primary:Documents/dsh} 这类 documentId 尾巴转成人类可读的名字。 */
    private static String readableName(Uri tree) {
        String s = tree.getLastPathSegment();
        if (s == null) return "存储目录";
        try {
            s = Uri.decode(s);
        } catch (Throwable ignored) { }
        int i = s.lastIndexOf(':');
        if (i >= 0 && i < s.length() - 1) s = s.substring(i + 1);
        int j = s.lastIndexOf('/');
        if (j >= 0 && j < s.length() - 1) s = s.substring(j + 1);
        return s.length() == 0 ? "存储目录" : s;
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            Log.i("MainActivity", "[dsh] BACK from onKeyDown repeat=" + event.getRepeatCount()
                + " action=" + event.getAction());
            handleBack();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    /**
     * 侧滑手势返回 / 三个虚拟键返回 / 部分 ROM 的手势回退，最终都会走到这里。
     *
     * <p>r65 新增。为什么不能只有 {@link #onKeyDown}：
     * <ul>
     *   <li>不同 ROM / 导航方式（手势导航、EMUI/MIUI 手势、Android 15 的
     *       OnBackInvoked 路径）把 BACK 分发成"按键"还是"回调"并不一致，只挂
     *       一条路就会在某些机器上彻底收不到；</li>
     *   <li>{@code onKeyDown} 与 {@code onBackPressed} 在部分系统上**会同一次
     *       按下里被先后调用**（onKeyUp 转 onBackPressed），所以两者必须共用
     *       同一段实现并做去重，否则一次返回会退两级、甚至直接退出 App。</li>
     * </ul>
     */
    @Override
    public void onBackPressed() {
        Log.i("MainActivity", "[dsh] BACK from onBackPressed");
        handleBack();
    }

    /**
     * 统一的"返回上一级"实现 —— 由 {@link #onKeyDown} 与 {@link #onBackPressed} 共用。
     *
     * <p>优先级：
     * <ol>
     *   <li>注入脚本上报"现在有覆盖层（设置面板 / 抽屉），应当由 JS 处理" →
     *       调 {@code window.__dshBack()}。覆盖层是 SPA 内的 DOM，原生侧看不见
     *       它们，只有 JS 知道该退到哪一级；</li>
     *   <li>否则看 WebView 是否有历史可退 → {@code history.back()}；</li>
     *   <li>都没有 → 交给系统默认行为（退出 Activity）。</li>
     * </ol>
     *
     * <p>⚠️ 一律走 {@code history.back()}，**绝不用** {@code webView.goBack()}：
     * dsh 是 SPA（history.pushState 路由），goBack() 会让 WebView **重新加载**
     * 那个历史项的文档 —— 一旦历史里混进服务端不存在的路径（404 空响应），
     * 重新加载就是一片空白（用户反馈"侧滑返回后白屏"）。
     */
    private void handleBack() {
        // 同一次物理按下可能同时触发 onKeyDown 与 onBackPressed，去重，
        // 否则一次返回退两级。200ms 远小于人手连按两次的间隔。
        long now = SystemClock.uptimeMillis();
        if (now - mLastBackAt < 200L) return;
        mLastBackAt = now;

        // r74 [diag]：返回决策记录 —— 退出 App 只可能发生在 jsHandled=false 且 canGoBack=false
        Log.i("MainActivity", "[dsh] handleBack: jsHandled=" + mJsBackHandled
            + " canGoBack=" + (webView != null && webView.canGoBack()));

        if (webView == null) { super.onBackPressed(); return; }
        if (mJsBackHandled) {
            // 面板/抽屉开着：交给注入脚本的返回状态机（闸门 / L3→L2 / 退出设置）
            Log.i("MainActivity", "[dsh] handleBack: -> window.__dshBack()");
            webView.evaluateJavascript(
                "try{window.__dshBack&&window.__dshBack()}catch(e){}", null);
            return;
        }
        if (webView.canGoBack()) {
            Log.i("MainActivity", "[dsh] handleBack: -> window.history.back()");
            webView.evaluateJavascript("window.history.back();", null);
            return;
        }
        Log.w("MainActivity", "[dsh] handleBack: 无可退路径 -> 退出 App");
        super.onBackPressed();
    }

    /**
     * 注入脚本通过 {@code dshNative.setBackHandled()} 上报的"原生返回该由 JS 处理"。
     * volatile：由 JavaBridge 线程写、UI 线程读。
     */
    void setJsBackHandled(boolean handled) {
        mJsBackHandled = handled;
        Log.i("MainActivity", "[dsh] setJsBackHandled -> " + handled);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // r60：**只清一次**（每个 App 进程首次），之后保留 WebView 历史。
        //   r49 当初每次都清，是因为 r43 用整页跳 /dsh-market，在 WebView 历史里
        //   留下了一条 404 文档 —— 侧滑回去就是一整页白屏（只剩注入的汉堡）。
        //   而 r48 已把 openMarket 改成 cell.click()（SPA 内部切换），**不再产生
        //   新的脏条目**，于是「每次清历史」这个硬规避手段既没必要，又把侧滑返回
        //   彻底堵死：canGoBack() 恒为 false，返回键只能退出 App。
        //   现在只清一次：既清掉旧版本遗留的 404，又让此后的侧滑/返回键能逐级回退。
        //   万一仍退到失效页面，注入脚本里的 rescue() 会 location.replace 拉回首页兜底
        //   （r52 起还加了 docLooksAlive 与 RESCUE_MAX 上限，比 r49 时更可靠）。
        if (webView != null && !sHistoryCleared) {
            try { webView.clearHistory(); sHistoryCleared = true; } catch (Throwable ignored) { }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        DshProcessManager.setObserver(null);   // 释放对 Activity 的引用，避免泄漏
    }
}
