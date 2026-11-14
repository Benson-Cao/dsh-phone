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
 *   3. WebView 等待 3081 端口就绪后加载 http://127.0.0.1:3081
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

    /**
     * r113：显式进入远端档案。带上 {@code true} 的启动 Intent 会跳过「按需连接」的
     * 选择页守卫，直接进 relay 档 —— 只在用户刚刚确认过时使用
     * （切换器切完主机、离线页点「进入这台电脑」）。
     */
    public static final String EXTRA_ENTER_REMOTE = "enterRemote";

    /** 待写入「另存为」目标的源文件（onActivityResult 要用它）。 */
    private File pendingSaveSrc = null;

    // ===== 设计稿《移动端 UI 设计系统》token =====
    // 唯一依据：资料库页面《移动端 UI 设计系统》的 :root 变量表。
    // 之前 App 用的是深色 #0F1116，与设计稿的浅色体系冲突（也导致状态栏配色别扭）。
    private static final int BRAND_500 = 0xFF4D6BFE;  // 设计稿 brand #4D6BFE（选中/链接/品牌渐变）

    // 动效规范（设计稿 07）：4 档时长 + 2 条缓动，原生动画统一走这里。
    private static final long DUR_PRESS  = 120;   // 按压反馈
    private static final long DUR_PANE   = 200;   // Tab / 面板切换
    private static final long DUR_TRACE  = 320;   // 轨迹 / 指标 / WebView 入场
    private static final long DUR_SCREEN = 450;   // 屏入场 / 启动页淡出 / 主题换肤
    // 出场通用缓动 cubic-bezier(.2,.8,.2,1)；换肤缓动 cubic-bezier(.45,.05,.2,1)
    private static final android.view.animation.Interpolator EASE_OUT =
            new android.view.animation.PathInterpolator(0.2f, 0.8f, 0.2f, 1f);
    private static final android.view.animation.Interpolator EASE_THEME =
            new android.view.animation.PathInterpolator(0.45f, 0.05f, 0.2f, 1f);
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

    /** 启动页各处视图引用：主题切换时要能就地改色，故持有引用。
     *  r163（回滚 v34/v35）：恢复品牌启动页（图二状态）。v35 的骨架屏方案有硬伤——
     *  crossfadeSplashToWeb() 只淡入 WebView，从不移除 homeSkeleton，骨架永远
     *  盖在最上层，真机表现为「卡在骨架屏，无法使用」。整组撤掉：呼吸线、
     *  引擎连接成功浮层、首页骨架，全部回归启动页 → 交叉淡入 → 首页。 */

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
        // 三引擎切换：本机直连 3080，电脑/服务器走 3082 本地反代。
        // 两者都是 loopback，A-02 的 DshOrigin 判定与 JS 桥信任边界保持不变。
        return EngineEndpoints.currentUrl(this);
    }

    /** 由 DshBridge.engineSwitch() 在切换完成后调用（已在主线程）。 */
    public void reloadForEngine() {
        if (webView == null) return;
        webView.loadUrl(EngineEndpoints.currentUrl(this));
    }

    /**
     * v2.3 批 B（S-6.9 骨架）：切换档案。
     *
     * <p>切档铁律（挡 R-22 串会话）：<b>进程级 cookie 必须清空</b> ——
     * CookieManager 是单例，上一台主机的 dsh-auth / Relay 反代会话 cookie
     * 若带进下一台，就是跨主机串会话。同时销毁重建 WebView（不复用实例），
     * 让缓存/历史/表单态跟着档案一起归零。
     *
     * <p>必须在主线程调用（悬浮球面板点击回调天然在主线程）。
     * 本批只落骨架：持久化 + 清 cookie + recreate()；悬浮球 UI 批 D 接。
     */
    public void switchProfile(String profileId) {
        HostProfile hp = HostProfile.get(this);
        if (!hp.setCurrent(profileId)) {
            Log.w("MainActivity", "切档失败：未知或未配置完整的档案 " + profileId);
            return;
        }
        Log.i("MainActivity", "切档 -> " + profileId
                + "（" + hp.current().describe() + "）");
        // 清 cookie + 作废预热：两个入口（原生切换器 / 离线页）共用，见 ProfileSession
        ProfileSession.resetRuntime();
        if (webView != null) {
            try {
                webView.removeAllViews();
                webView.destroy();
            } catch (Throwable ignored) { }
            webView = null;
        }
        recreate();   // onCreate 按新档案走对应分支
    }

    private void setStatus(final String s) {
        main.post(new Runnable() {
            @Override public void run() {
                if (statusText != null) statusText.setText(s);
            }
        });
    }

    /** v31：进程内单例引用 —— 原生设置页「网页设置」回程需要拿 WebView 执行 JS。 */
    public static volatile java.lang.ref.WeakReference<MainActivity> sInstance;

    /** r204: real timing origin (first line of onCreate). The old tBoot was taken
     *  *inside* the background thread, so every "total Xms" silently omitted the
     *  whole onCreate main-thread segment (splash views, WebView provider, etc.). */
    private static volatile long sCreateAt = 0L;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        sCreateAt = System.currentTimeMillis();   // r204: real timing origin
        super.onCreate(savedInstanceState);
        Log.i("Startup", "[mark] onCreate start");
        sInstance = new java.lang.ref.WeakReference<>(this);

        // r204: WebView debugging switch moved down to just before prepareWebView().
        //   setWebContentsDebuggingEnabled is the FIRST android.webkit API touched;
        //   it synchronously loads WebViewFactoryProvider (1~3s on low-end phones).
        //   Leaving it here pushed Node launch back by W -- the two costs added up.

        // r107：SAF 代理必须在拉起 dsh **之前**就位（幂等）。
        //   为什么不能只靠 DshService：DshService 并没有在 AndroidManifest 里注册，
        //   startForegroundService 每次都抛（被 catch 吞掉），onStartCommand 永不回调
        //   → 那条「代理随服务起停」的路径实际上是死的。
        //   后果：上一次进程留下的 saf_config.json 仍是 enabled:true + 已失效的端口/token，
        //   node 侧 shim 会拿着它去撞一个没人监听的端口，每次 SAF 操作白等 20s 超时。
        //   这里起一次即可：未配置 → 只写 enabled:false；已配置 → 用新端口/token 覆盖。
        //   于是配置文件永远与真实状态一致（shim 按 mtime 现读，不需要重启 dsh）。
        SafProxyServer.start(this);

        // r204: launch the engine at the earliest legal point. DshService.start /
        // ensureEngineStarted used to sit at the END of onCreate's background thread,
        // behind splash view building, setContentView, WebView provider loading and
        // prepareWebView() -- all main-thread work with zero dependency on Node boot.
        // This thread only does the *spawn*: ensureEngineStarted synchronizes on
        // ENGINE_GATE for its whole body, so the original thread queues on that lock,
        // then proceeds with the 3082 proxy and readiness polling exactly as before.
        // DshService.start() deliberately stays in the original thread --
        // startForegroundService has a hard 5s deadline to call startForeground().
        // SAF proxy must already be up (r107 hard constraint, see above).
        //
        // v2.3 批 B：只有 local 档需要拉本机引擎；relay/ssh 档后台线程里另有分支。
        if (HostProfile.get(this).current().needsLocalEngine()) {
            new Thread(new Runnable() {
                @Override public void run() {
                    Log.i("Startup", "[mark] onCreate->NodeLaunch="
                            + (System.currentTimeMillis() - sCreateAt) + "ms");
                    Log.i("Startup", "[mark] earlySpawn="
                            + DshProcessManager.ensureEngineStarted(MainActivity.this));
                }
            }, "dsh-engine-early-start").start();
        } else {
            Log.i("Startup", "[mark] 档案 " + HostProfile.get(this).currentId()
                    + " 不需要本机引擎，跳过 earlySpawn");
        }

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
        // r163：恢复品牌启动页（鲸鱼标 → DeepSeek Harness → 状态文案 → 品牌渐变线）。
        // 引擎就绪后由 crossfadeSplashToWeb() 交叉淡入真实首页。
        loadingBox = new LinearLayout(this);
        loadingBox.setOrientation(LinearLayout.VERTICAL);
        // CENTER = CENTER_VERTICAL | CENTER_HORIZONTAL，配合 ScrollView.fillViewport
        // 才是真正的「整体垂直居中」（只给 CENTER_HORIZONTAL 时垂直方向贴顶）。
        loadingBox.setGravity(Gravity.CENTER);
        // ⚠️ 上下不要留padding：fillViewport 会把 loadingBox 拉伸到一屏高，
        //   但上下 padding 会让内容整体下移，鲸鱼看起来"靠上"。
        //   垂直居中完全交给 gravity，padding 只留左右。
        loadingBox.setPadding(dp(32), 0, dp(32), 0);

        // 品牌图标：优先用裁边版鲸鱼（r200，strip_icon_bg.py 生成的
        // drawable-nodpi/dsh_splash_whale.png，内容 bbox+8% 留白，鲸鱼几乎占满画布），
        // 找不到时回退 adaptive 前景（原图鲸鱼只占画布 48%×35%，视觉偏小）。
        // 用 getIdentifier 而非 R.mipmap.*：纯 javac 校验时没有生成的 R 类。
        ImageView logo = new ImageView(this);
        // v36：深浅色各备一张鲸鱼（同一剪影两色）。判定用 DshPalette.isDark（含
        // dshDarkOverride 持久化主题），**不能**用 drawable-night 资源限定符 ——
        // 那个只跟系统 uiMode 走，用户「系统浅色 + dsh 深色」时启动页底是深色、
        // 图还是深炭色，肉眼几乎看不见（用户实拍图二）。
        int fgId = getResources().getIdentifier(
            DshPalette.isDark(this) ? "dsh_splash_whale_night" : "dsh_splash_whale",
            "drawable", getPackageName());
        if (fgId == 0) fgId = getResources().getIdentifier("dsh_splash_whale", "drawable", getPackageName());
        if (fgId == 0) fgId = getResources().getIdentifier("dsh_foreground", "mipmap", getPackageName());
        if (fgId != 0) logo.setImageResource(fgId);
        LinearLayout.LayoutParams logoLp = new LinearLayout.LayoutParams(dp(80), dp(80));
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
        statusText = new TextView(this);
        statusText.setText("正在准备运行环境…");
        statusText.setTextColor(DshPalette.muted(this));
        statusText.setTextSize(14);
        statusText.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams stLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        stLp.topMargin = dp(14);
        loadingBox.addView(statusText, stLp);

        // 品牌渐变细线（--brand-500 →透明），替代原来的 ProgressBar
        View rule = new View(this);
        // ⚠️ r79：末端透明色取**当前底色**，不再写死 0x00FFFFFF。
        rule.setBackground(gradient(0, 100, BRAND_500, DshPalette.fadeToBg(this)));
        splashRule = rule;
        LinearLayout.LayoutParams rLp = new LinearLayout.LayoutParams(dp(120), dp(3));
        rLp.topMargin = dp(18);
        loadingBox.addView(rule, rLp);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(DshPalette.bg(this));
        splashScroll = scroll;
        // ⚠️ 必须开 fillViewport：ScrollView 的子View 默认只按内容高度布局，
        //    此时 LinearLayout 的 gravity(CENTER) **不会**产生垂直居中效果。
        scroll.setFillViewport(true);
        scroll.addView(loadingBox);
        root.addView(scroll, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);

        // r216：启动时先回收常驻预热 WebView（用户选定方案）。
        // 旧实例的 uiMode/主题停在上一轮配置，且预热阶段没挂 WebViewClient →
        // official-bridge.js 从未注入，是一张裸的浅色首页；真机 CDP 见过两个
        // 同源 target，浅色那个就是它。回收后本次进程只留一个干净的 WebView。
        DshPreloadWeb.discardOnLaunch();
        // r204: debugging switch moved here from the top of onCreate (see above).
        //   Still before new WebView(), so CDP works unchanged; but the provider
        //   load now happens AFTER Node launch and overlaps the ~7.2s engine boot.
        try {
            android.webkit.WebView.setWebContentsDebuggingEnabled(true);
        } catch (Throwable t) {
            android.util.Log.w("MainActivity", "远程调试开关失败：" + t.getMessage());
        }
        // WebView 照旧提前创建预热（渲染进程冷启动 1~3s，与 node 启动并行），
        // 但**不挂进视图树** —— 由 crossfadeSplashToWeb() 在就绪时加到最上层淡入。
        prepareWebView();

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

        // 品牌渐显：标题上浮 18px + 淡入（设计稿 07 屏入场 450ms / cubic-bezier(.2,.8,.2,1)）
        title.setAlpha(0f);
        title.setTranslationY(dp(18));
        title.animate().alpha(1f).translationY(0f)
                .setDuration(DUR_SCREEN).setStartDelay(80).setInterpolator(EASE_OUT).start();

        // --- 耗时工作全部丢后台线程 ---
        new Thread(new Runnable() {
            @Override public void run() {
                final long tBoot = System.currentTimeMillis();

                // v2.3 批 B：档案分支 —— 只有 local 档才走本机引擎全套流程；
                // relay 直连 Relay origin，ssh 等隧道（批 C）。见 handleNonLocalProfile()。
                final HostProfile hp = HostProfile.get(MainActivity.this);
                final HostProfile.Profile prof = hp.current();
                if (!prof.needsLocalEngine()) {
                    // r113 · 按需连接：远端档案默认**不**在启动时自动进入。
                    // 「打开应用」多数意味着「用手机上的 dsh」；自动连一台可能已离线/被收回的
                    // 电脑，失败时只会停在白屏。改成停在选择页，用户点了才连。
                    // 例外：切换器/离线页在用户确认后拉起本页时会带 enterRemote=true。
                    final boolean forced = getIntent() != null
                            && getIntent().getBooleanExtra(EXTRA_ENTER_REMOTE, false);
                    if (!forced && !hp.autoEnterRemote()) {
                        main.post(new Runnable() {
                            @Override public void run() {
                                startActivity(new Intent(MainActivity.this, OfflineActivity.class)
                                        .putExtra(OfflineActivity.EXTRA_CHOICE, true));
                                finish();
                            }
                        });
                        return;
                    }
                    handleNonLocalProfile(prof, tBoot);
                    return;
                }

                // ===== 常驻服务 =====
                // 启动 DshService，让 dsh 在 App 退出后仍存活。
                // 这样第二次打开只需探测端口（亚秒级），不必等 node 冷启动。
                //
                // ⚠️ 这里**不再自己判断端口然后 bootstrap + startEngine**：
                //    服务那条路径此刻也在做同一件事，而 isPortOpen 只是「现在开没开」，
                //    node 冷启动要十几秒 —— 两条路径都会判"没开"，于是各起一个进程，
                //    后到的被 EADDRINUSE 顶掉。死实例的日志还会让 readTokenUrl 取错
                //    token，首页就卡在「服务还没就绪」。
                //    统一交给 ensureEngineStarted 的闸门：谁先到谁拉，另一条只等。
                DshService.start(MainActivity.this);

                setStatus("正在准备运行环境…");
                final long tStart = System.currentTimeMillis();
                final DshProcessManager.StartOutcome outcome =
                        DshProcessManager.ensureEngineStarted(MainActivity.this);
                final long tBootDone = System.currentTimeMillis();
                Log.i("MainActivity", "[计时] ensureEngineStarted=" + outcome
                        + " " + (tBootDone - tStart) + "ms（总 " + (tBootDone - tBoot) + "ms）");

                if (outcome == DshProcessManager.StartOutcome.FAILED) {
                    // 失败：把真实原因显示出来，别让用户对着黑屏猜
                    final String errMsg = DshProcessManager.getLastStartError();
                    final long free = DshBootstrap.usableBytes(MainActivity.this);
                    main.post(new Runnable() {
                        @Override public void run() {
                            showDiagnostics("环境准备失败：\n\n" + errMsg
                                + "\n\n可用空间: " + (free < 0 ? "未知" : (free / 1048576) + " MB")
                                + "\n\n" + DshBootstrap.describeIntegrity(MainActivity.this)
                                + "\n点下面的“重试”会重新尝试解包；"
                                + "若是空间不足，请先清理手机存储。");
                        }
                    });
                    return;
                }

                // r199：把 3082 本地反代的启动**提前到就绪探测之前**。
                // 原来 EngineEndpoints.apply() 只在 showWebView() 里调用 —— 等于刚
                // Runtime.exec 拉起 node 反代就立刻 loadUrl(3082)，必然撞上
                // ERR_CONNECTION_REFUSED（用户图二的系统错误页）。现在探测循环有
                // 充足时间等反代监听，且 DshProbe 已改为探同一个 URL。
                try {
                    EngineEndpoints.apply(MainActivity.this);
                } catch (Throwable e) {
                    Log.e("MainActivity", "启动本地反代失败", e);
                }

                // REUSED（端口已开）/ STARTED（本次拉起）/ ALREADY_STARTING（服务正在拉）
                // 三条都进同一条等待路径 —— 区别只在文案与耗时统计。
                final boolean reused = outcome == DshProcessManager.StartOutcome.REUSED;
                if (reused) {
                    Log.i("MainActivity", "dsh 端口已监听，复用现有进程，不重新拉起");
                } else if (outcome == DshProcessManager.StartOutcome.ALREADY_STARTING) {
                    Log.i("MainActivity", "常驻服务正在拉起 dsh，本路径只等待不重复启动");
                }
                waitThenShowWeb(tBoot, reused ? 0 : (tBootDone - tBoot), 0, reused);
            }
        }).start();
    }

    /**
     * v2.3 批 B：非 local 档案的启动分支（后台线程）。
     *
     * <p>relay：三要素齐 → 直接进 WebView 开反代 URL（无引擎、无 cookie 下发、
     * 无就绪探测 —— 反代页自身有登录态）；未配置 → 结构化诊断页。
     * ssh：隧道未接（批 C），诚实占位，不假装连接。
     *
     * <p>故意<b>不</b>调 DshService.start()：前台服务是为了保活本机 node，
     * relay/ssh 档没有本机引擎，常驻服务纯属浪费电（S-6.6 省电目标）。
     */
    private void handleNonLocalProfile(final HostProfile.Profile prof, final long tBoot) {
        Log.i("MainActivity", "[档案] " + prof.describe());
        if (HostProfile.KIND_RELAY.equals(prof.kind)) {
            if (!prof.relayConfigured()) {
                main.post(new Runnable() {
                    @Override public void run() {
                        showDiagnostics("Relay 档案未配置完整。\n\n"
                            + "需要在悬浮球面板里填入 Relay 地址、主机 ID 和手机令牌。\n"
                            + "（配对方式见 wb-cloud-sync 项目的手机配对流程）");
                    }
                });
                return;
            }
            setStatus("直连 Relay（" + prof.baseOrigin() + "）…");
            final long tReady = System.currentTimeMillis();
            Log.i("MainActivity", "[计时] relay 直连=" + (tReady - tBoot) + "ms");
            main.post(new Runnable() {
                @Override public void run() { showWebView(prof.loadUrl()); }
            });
            return;
        }
        // ssh：本地隧道（批 C）。三件事按顺序做，任何一步失败都给出具体原因，
        // 不假装连接 —— 假装的结果是一个连不上任何东西的回环端口和一张白屏。
        if (!prof.sshConfigured()) {
            main.post(new Runnable() {
                @Override public void run() {
                    showDiagnostics("SSH 档案未配置完整。\n\n需要：主机地址、端口、用户名、本地端口。");
                }
            });
            return;
        }
        if (!DshSshTunnel.available(this)) {
            main.post(new Runnable() {
                @Override public void run() {
                    showDiagnostics(DshSshTunnel.unavailableReason(MainActivity.this));
                }
            });
            return;
        }
        main.post(new Runnable() {
            @Override public void run() { setStatus("正在建立 SSH 隧道…"); }
        });
        new Thread(new Runnable() {
            @Override public void run() {
                final String host = prof.sshHost;
                final int port = prof.sshPort;

                // 1) 主机指纹 TOFU：先取对端公钥，再与记录比对，不同即拒绝
                final boolean hasKeyscan = DshSshTunnel.keyscanAvailable(MainActivity.this);
                String line = DshSshTunnel.scanKeyLine(MainActivity.this, host, port);
                final String fp = SshHostKey.fingerprintOfKeyLine(line);
                if (fp == null) {
                    main.post(new Runnable() {
                        @Override public void run() {
                            // r122：区分「工具缺失」与「对端不可达」。这两种情况的处置完全不同，
                            // 说成一句「确认主机可达」会让缺二进制的包永远查不出问题。
                            showDiagnostics("取不到 " + host + ":" + port + " 的主机公钥。\n\n"
                                    + (hasKeyscan ? "" :
                                       "本安装包的 bundle 里没有 ssh-keyscan，且试探连接也没拿到公钥"
                                       + "（试探连接同样依赖包内的 ssh）。\n\n")
                                    + "确认：主机地址与端口正确、对端 sshd 在运行、手机与它同网段"
                                    + "（或已连上同一 VPN）、对端放行该端口。");
                        }
                    });
                    return;
                }
                final String pinned = DshSshTunnel.pinned(MainActivity.this, host, port);
                SshHostKey.Decision d = SshHostKey.decide(pinned, fp);
                if (d == SshHostKey.Decision.MISMATCH) {
                    main.post(new Runnable() {
                        @Override public void run() {
                            showDiagnostics("主机指纹与上次记录不一致，已拒绝连接。\n\n"
                                    + "上次记录：" + pinned + "\n本次取得：" + fp + "\n\n"
                                    + "这可能是对端重装过系统，也可能是中间人。确认无误后清除本机记录再重试。");
                        }
                    });
                    return;
                }
                if (d == SshHostKey.Decision.FIRST_SEEN) {
                    DshSshTunnel.pin(MainActivity.this, host, port, fp);
                    Log.i("MainActivity", "[ssh] 首次连接，已钉住主机指纹 " + fp);
                }
                // 2) 写 known_hosts（StrictHostKeyChecking=yes 需要），再确保密钥存在
                DshSshTunnel.writeKnownHost(MainActivity.this, host, port, line);
                if (DshSshTunnel.ensureKeyPair(MainActivity.this) == null) {
                    main.post(new Runnable() {
                        @Override public void run() {
                            showDiagnostics("生成 SSH 密钥失败（bundle 里缺 ssh-keygen 或目录不可写）。");
                        }
                    });
                    return;
                }

                // 3) 起隧道，等本地端口真的通（进程活着但端口没通 = 密钥没被接受）
                Process pr = DshSshTunnel.start(MainActivity.this, prof);
                if (pr == null) {
                    main.post(new Runnable() {
                        @Override public void run() { showDiagnostics("SSH 隧道启动失败（进程未能创建）。"); }
                    });
                    return;
                }
                if (!waitLocalPort(prof.localPort, 15000)) {
                    DshSshTunnel.stop();
                    main.post(new Runnable() {
                        @Override public void run() {
                            showDiagnostics("隧道进程起来了，但 127.0.0.1:" + prof.localPort + " 没通。\n\n"
                                    + "最常见原因：本机公钥没有被对端接受。把下面这行加到对端"
                                    + " ~/.ssh/authorized_keys 后重试：\n\n"
                                    + "(在电脑侧执行 ssh-keygen 对应的 authorized_keys 追加；"
                                    + "公钥可在配对页/本机档案里查看)");
                        }
                    });
                    return;
                }
                main.post(new Runnable() {
                    @Override public void run() {
                        showWebView("http://127.0.0.1:" + prof.localPort);
                    }
                });
            }
        }, "dsh-ssh").start();
    }

    /**
     * 等本地端口可连（隧道真正的就绪信号）。
     *
     * <p>为什么不用「进程还活着」当就绪：`ssh -L` 在密钥未被接受时会先建立连接再失败，
     * 进程可能存活数秒；只有端口 accept 了才说明转发真的建立。
     */
    private boolean waitLocalPort(int port, int timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            java.net.Socket s = null;
            try {
                s = new java.net.Socket();
                s.connect(new java.net.InetSocketAddress("127.0.0.1", port), 400);
                return true;
            } catch (Throwable t) {
                try { Thread.sleep(300); } catch (InterruptedException ignored) { }
            } finally {
                if (s != null) { try { s.close(); } catch (Throwable ignored) { } }
            }
        }
        return false;
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
                Log.i("Startup", "[mark] onCreate->HTTP200="
                        + (tReady - sCreateAt) + "ms (old scope " + (tReady - tBoot) + "ms)");
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
                        showWebView(EngineEndpoints.currentUrl(MainActivity.this));
                    }
                });
                return;
            }
            final int sec = (int) ((System.currentTimeMillis() - t0) / 1000);
            setStatus("正在启动 dsh 服务…"
                + (sec >= 1 ? "（已用 " + sec + " 秒）" : ""));
            // r163 方案 A：自适应轮询。前 3 秒 120ms 快探（常驻命中路径就绪极快，
            // 250ms 固定间隔平均多等 ~125ms），之后回落 250ms 给引擎喘息。
            long gap = (System.currentTimeMillis() - t0 < 3000) ? 120 : 250;
            try { Thread.sleep(gap); } catch (InterruptedException e) { return; }
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
    /** 远端错误页是否已显示（WebView 对同一次失败可能回调多次，避免反复重建视图）。 */
    private boolean remoteErrorShown = false;

    /**
     * r121：远端档案（relay / ssh）加载失败时的原生错误页。
     *
     * <p>为什么不能复用 {@link #showDiagnostics}：那套文案是给**本机引擎**的
     * （「dsh 后台可能仍在启动，或已退出」）。远端档失败的真实原因是「连不上那台电脑」，
     * 而 relay 档按设计**不跑**本机引擎 —— 说成引擎问题会把用户引到错误的排查方向，
     * 也解释不了为什么「重试」永远不成功。
     *
     * <p>三个出口对应三种真实意图：再试一次 / 不连了用本机 / 退出。
     */
    void showRemoteUnreachable(final HostProfile.Profile prof) {
        if (remoteErrorShown) return;
        remoteErrorShown = true;

        int bg = DshPalette.bg(this);
        int fg = DshPalette.text(this);
        int muted = DshPalette.muted(this);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(22), dp(26), dp(22), dp(26));
        box.setBackgroundColor(bg);

        TextView head = new TextView(this);
        head.setText("连不上这台电脑");
        head.setTextColor(fg);
        head.setTextSize(20);
        head.setTypeface(head.getTypeface(), android.graphics.Typeface.BOLD);
        box.addView(head);

        TextView target = new TextView(this);
        String origin = prof == null ? null : prof.origin;
        target.setText(origin == null || origin.isEmpty() ? "（未记录地址）" : origin);
        target.setTextColor(muted);
        target.setTextSize(13);
        target.setTypeface(android.graphics.Typeface.MONOSPACE);
        LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tLp.topMargin = dp(6);
        box.addView(target, tLp);

        TextView why = new TextView(this);
        why.setText("这台手机现在连的是电脑，不是本机引擎，所以这里没有 dsh 后台可等。\n\n"
                + "常见原因：电脑休眠或关机；电脑上的 Relay 没在运行；手机与电脑不在同一网络；"
                + "或这台手机已被管理台收回。");
        why.setTextColor(muted);
        why.setTextSize(13);
        why.setLineSpacing(dp(2), 1f);
        LinearLayout.LayoutParams wLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        wLp.topMargin = dp(14);
        box.addView(why, wLp);

        Button retry = new Button(this);
        retry.setText("重试");
        retry.setAllCaps(false);
        retry.setOnClickListener(new android.view.View.OnClickListener() {
            @Override public void onClick(android.view.View v) {
                remoteErrorShown = false;
                finish();
                startActivity(getIntent());
            }
        });
        LinearLayout.LayoutParams rLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rLp.topMargin = dp(20);
        box.addView(retry, rLp);

        Button local = new Button(this);
        local.setText("改用本机引擎");
        local.setAllCaps(false);
        local.setOnClickListener(new android.view.View.OnClickListener() {
            @Override public void onClick(android.view.View v) {
                HostProfile hp = HostProfile.get(MainActivity.this);
                if (!hp.setCurrent(HostProfile.KIND_LOCAL)) return;
                // 切档必须归零 cookie 与预热实例（R-22：防跨主机串会话）
                ProfileSession.resetRuntime();
                finish();
                startActivity(new android.content.Intent(MainActivity.this, MainActivity.class));
            }
        });
        LinearLayout.LayoutParams lLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lLp.topMargin = dp(8);
        box.addView(local, lLp);

        Button quit = new Button(this);
        quit.setText("退出");
        quit.setAllCaps(false);
        quit.setOnClickListener(new android.view.View.OnClickListener() {
            @Override public void onClick(android.view.View v) { finish(); }
        });
        LinearLayout.LayoutParams qLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        qLp.topMargin = dp(8);
        box.addView(quit, qLp);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(bg);
        scroll.addView(box);
        setContentView(scroll);
    }

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
     *
     * <p>r163 方案 B：优先领养 DshService 预热的常驻 WebView —— 那个不仅渲染进程
     * 就位，连首页 HTML/JS 都已加载完。领养后重设绑 Activity 的回调
     * （ChromeClient 的附件选择器需要 Activity 的 startActivityForResult，
     * Service 预热阶段故意没设），并跳过后续 loadUrl（页面已在）。
     */
    private void prepareWebView() {
        if (webView != null) return;
        if (isFinishing() || isDestroyed()) return;   // Activity 已退出就不必建了
        try {
            if (DshPreloadWeb.isAvailable()
                    && HostProfile.get(this).current().needsLocalEngine()) {
                webView = DshPreloadWeb.adopt();
                bindActivityClients();
                // 领养时页面早已 onPageFinished，注入钩子不会再触发；主动补一次
                // （脚本自带 __dshOfficialBridge 守卫，重复注入是 no-op）。
                // v2.3 批 B：injectIfAllowed —— relay 档不领养也不注入（见上 needsLocalEngine 门）。
                com.dsh.harness.webview.OfficialShell.injectIfAllowed(webView);
                Log.i("MainActivity", "已领养常驻预热的 WebView（首页已加载）");
                return;
            }
        } catch (Throwable t) {
            Log.w("MainActivity", "领养预热 WebView 失败，回退自建: " + t.getMessage());
        }
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
        // 1134 登录白屏修复（r110）：让 window.open 走 DshChromeClient.onCreateWindow，
        // 而不是把当前 WebView 导航到远程授权页（会落成 about:blank 空白文档）。
        s.setSupportMultipleWindows(true);
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        // 让 dsh 下发的 dsh-auth cookie 能落盘，否则跳回 / 又是 401
        android.webkit.CookieManager.getInstance().setAcceptCookie(true);
        bindActivityClients();
        webView.setBackgroundColor(DshPalette.bg(this));
        webView.loadUrl("about:blank");   // 仅预热，不显示
        Log.i("MainActivity", "WebView 已预热（自建）");
        Log.i("Startup", "[mark] onCreate->WebViewInstance="
                + (System.currentTimeMillis() - sCreateAt) + "ms");
    }

    /**
     * 给当前 webView 绑定**依赖 Activity** 的回调与 JS 桥（r163 方案 B 抽出）。
     * 自建与领养两条路径都要走：领养的 WebView 在 Service 里只做了设置与加载，
     * 这些回调故意留空等 Activity 补上。
     */
    private void bindActivityClients() {
        if (webView == null) return;
        // 附件：WebView 默认不弹文件选择器，必须挂 WebChromeClient
        chromeClient = new DshChromeClient(this);
        webView.setWebChromeClient(chromeClient);
        webView.setWebViewClient(new DshWebViewClient());
        // v2.3 批 B（A-02 边界随档案走）：JS 桥只进回环档案（local/ssh）。
        // relay 档的页面是远端 origin，dshNative 能下载并执行代码，
        // 绝不能暴露给远端页面 —— 一个桥都不挂（dsh-bridge 消息通道同理）。
        if (!HostProfile.get(this).current().bridgeAllowed()) {
            Log.i("MainActivity", "relay 档：不注册任何 JS 桥（远端 origin）");
            return;
        }
        // JS bridge：设置页的「插件市场」入口靠它触发原生安装
        // （dshmarket 要落盘到 $HOME/.dsh/profiles/web/，WebView 里的 JS 没有文件系统权限）
        try {
            webView.addJavascriptInterface(new DshBridge(this), "dshNative");
            // 方案 C：文件导出桥（dshSaf）。单独一个对象名，避免和插件市场逻辑混在一起。
            webView.addJavascriptInterface(new DshSafBridge(this), "dshSaf");
            // r152（官方对齐）：官方 dsh-bridge 通道（WebViewCompat.addWebMessageListener）。
            // 与上面两个接口并存：现有前端继续走 dshNative/dshSaf，本通道是官方「祝福路径」
            // 的前向兼容实现（消息名 dsh-bridge，JS 侧 window.DshBridge.call）。
            new com.dsh.harness.webview.DshWebBridge(this).register(webView);
        } catch (Exception e) {
            Log.e("MainActivity", "注册 JS bridge 失败", e);
        }
    }

    /** DshBridge 需要在安装完成后回调 JS，故暴露 webView 引用。 */
    /** v31：改 public —— 原生设置页（mobile.SettingsActivity）「网页设置」回程经它执行 JS。 */
    public android.webkit.WebView webViewRef() {
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
    /**
     * v30.38：应用页面持续上报的 dsh 真实主题（由 {@code official-bridge.js} 轮询触发）。
     *
     * <p>与 {@code DshWebViewClient.probeTheme()} 的区别：那个只在页面加载完成时探
     * 有限几次；本方法处理「页面已加载、用户在原生设置里改了主题后返回」的场景 ——
     * WebView 不 reload，但 dsh 的 CSS 变量已切换，此时必须重刷系统栏。
     *
     * @param dark 页面自报的深浅色
     */
    public void applyDshTheme(boolean dark) {
        try {
            boolean changed = DshPalette.rememberDshDark(this, dark);
            if (changed) {
                Log.i("MainActivity", "[dsh] 页面主题变化 → " + (dark ? "dark" : "light")
                        + "，刷新系统栏");
                refreshSystemBarsForTheme();
            }
        } catch (Throwable t) {
            Log.w("MainActivity", "applyDshTheme 失败: " + t.getMessage());
        }
    }

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

    /**
     * 引擎就绪提示浮层（v35）→ r163 已随骨架屏方案一并移除（见类头注释）。
     * 回归图二状态：启动页只负责「正在启动 dsh 服务…」，就绪即交叉淡入首页。
     */
    /**
     * 冷启动骨架屏（v35）→ r163 已移除。硬伤：crossfadeSplashToWeb() 只负责把
     * WebView 淡入，骨架从未被淡出/移除，真机永远停在骨架屏（用户 2026-11-03
     * 反馈「打开后无法使用」）。v41 之后的回归验证必须先点首页确认可交互。
     */
    private void showWebView(String url) {
        // v2.3 批 B：以档案为准 —— local 档维持引擎配置 + 3082 反代原语义；
        // relay/ssh 档绝不调 EngineEndpoints.apply（那是本机引擎/反代的拉起器），
        // URL 一律用档案自己的 loadUrl。
        HostProfile.Profile prof = HostProfile.get(this).current();
        if (prof.needsLocalEngine()) {
            // 以引擎配置为准：调用方即使传了旧的 3080 常量也重算一遍，
            // 并按需拉起 3082 本地反代（本机引擎时是 no-op）。
            EngineEndpoints.apply(this);
            url = EngineEndpoints.currentUrl(this);
        } else {
            url = prof.loadUrl();
        }
        if (webView == null) prepareWebView();   // 极端情况：预热还没跑就就绪
        // r163 方案 B 领养兜底：到这一刻（已就绪、主线程）预热若刚完成，
        // 而本 Activity 自建的 WebView 还没加载过真首页（webShown=false 保证），
        // 换成预热的更划算 —— 它连 dsh 前端都初始化完了，进入即首页。
        if (webView != null && !DshPreloadWeb.adopted() && DshPreloadWeb.isAvailable()) {
            try {
                webView.removeAllViews();
                webView.destroy();
            } catch (Throwable ignored) { }
            webView = null;
            prepareWebView();    // 这次会走领养分支
        }
        // r79 真机发现：进入 dsh 后**状态栏仍是启动页的浅色**，
        //   而页面已经是深色 → 顶部一条白边，深色下非常刺眼（真机截图确认）。
        //   原因：dsh 页面自己不管系统栏（mobile.js 里没有 statusBar 相关逻辑），
        //   而 showWebView() 也没有重新下发配色，于是沿用了 applyPalette() 当时的值。
        // 这里用**当前页面的实际主题**重刷一次系统栏。
        applyPalette();
        // 领养的预热 WebView 加载的是本机 3080 首页；只有 local 档可以沿用，
        // relay/ssh 档必须强制 loadUrl 到档案自己的地址，否则会停在旧档案页面上。
        if (prof.needsLocalEngine() && DshPreloadWeb.adopted() && !EngineEndpoints.isRemote(this)) {
            // 领养的常驻 WebView 已加载首页：绝不能再 loadUrl ——
            // 那会把已就绪的页面整个重刷（白屏 + 前端重新初始化），领养白费。
            Log.i("MainActivity", "领养的预热 WebView 已在首页，跳过 loadUrl");
        } else {
            webView.loadUrl(url);
        }
        webShown = true;
        Log.i("MainActivity", "已进入 dsh：" + (DshPreloadWeb.adopted() ? "（预热直入）" : "loadUrl " + url));
        Log.i("Startup", "[mark] onCreate->crossfade="
                + (System.currentTimeMillis() - sCreateAt) + "ms");
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

        // 进场（设计稿 07 轨迹/指标档 320ms / cubic-bezier(.2,.8,.2,1)）
        webView.animate().alpha(1f).setDuration(DUR_TRACE)
                .setInterpolator(EASE_OUT).start();

        // 退场（设计稿 07 屏入场档 450ms / cubic-bezier(.2,.8,.2,1)）：轻推放大收起
        // ⚠️ 这里是 v35 翻车点：v41 只做了上面的「淡入」，漏了这段「启动页退场」，
        //    骨架屏盖在 WebView 上永不移除。恢复完整交叉淡入。
        for (int i = 0; i < host.getChildCount(); i++) {
            final View v = host.getChildAt(i);
            if (v == webView) continue;          // 别把自己也淡掉
            v.animate().alpha(0f).scaleX(1.04f).scaleY(1.04f).setDuration(DUR_SCREEN)
                    .setInterpolator(EASE_OUT)
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
        // 附件选择结果必须先回灌给 WebView，否则页面拿不到文件。
        // r219：改走静态入口 —— 文件管理器占据前台期间本 Activity 可能已被回收重建，
        // 那时 chromeClient 是新实例甚至为 null，只有静态回调还认得出这次结果。
        if (DshChromeClient.deliverStatic(requestCode, resultCode, data)) return;

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

    /**
     * 1134 登录白屏修复（r110）：空白页守卫。
     * 领养的常驻 WebView 被系统回收、或跨应用跳转后 WebView 停在 about:blank 时，
     * showWebView() 已完成（webShown=true）不会再 loadUrl，页面永久纯白。
     * 仅在确实停在空白文档时重载当前引擎首页，正常首页不会被刷新。
     */
    private void restoreBlankWebView() {
        if (isFinishing() || isDestroyed()) return;
        if (!webShown || webView == null) return;
        String url;
        try { url = webView.getUrl(); } catch (Throwable t) { return; }
        if (url != null && !url.equals("about:blank") && !url.startsWith("data:")) return;
        String base;
        try { base = EngineEndpoints.currentUrl(this); } catch (Throwable t) { return; }
        if (base == null || base.length() == 0) return;
        Log.i("MainActivity", "[blank-guard] WebView 停在 " + url + "，重新加载 " + base);
        try { webView.loadUrl(base); } catch (Throwable t) {
            Log.w("MainActivity", "[blank-guard] loadUrl 失败: " + t.getMessage());
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 1134 登录白屏修复（r110）：从浏览器/后台回到 App 时补一次重加载，
        // 并在 1.5s 后再兜一次（预热 WebView 归还的空白文档常晚于 onResume 落地）。
        restoreBlankWebView();
        if (webView != null) {
            webView.postDelayed(new Runnable() {
                @Override public void run() { restoreBlankWebView(); }
            }, 1500L);
        }
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
        // r163 方案 B：把领养的常驻 WebView 归还给 DshPreloadWeb，供下次点图标
        // 再次领养（不销毁 —— 引擎活着，页面 JS 状态还在，下次直接回到原界面）。
        // 新 Activity 领养时会重设绑 Activity 的回调，旧引用随之释放。
        if (webView != null && DshPreloadWeb.adopted()) {
            try {
                DshPreloadWeb.release(webView);
                webView = null;
            } catch (Throwable ignored) { }
        }
        super.onDestroy();
        DshProcessManager.setObserver(null);   // 释放对 Activity 的引用，避免泄漏
    }
}
