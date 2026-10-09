package com.dsh.harness;

import android.content.Context;
import android.content.res.AssetManager;
import android.util.Log;
import android.webkit.WebView;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * 手机端适配外壳（注入到 dsh Web UI 里）。
 *
 * ## A-04：样式与脚本已搬进 assets
 * 本类现在只剩「读资源 + 注入」这一层入口；CSS 与注入脚本的**真源是 assets 文件**：
 * <pre>
 *   assets/dsh/mobile.css            设置面板 + 首页外壳样式
 *   assets/dsh/market.css            /dsh-market 市场页适配
 *   assets/dsh/market-chrome.css     市场页顶栏/汉堡安全区
 *   assets/dsh/mobile.js             注入脚本（内含三处 CSS 占位符）
 * </pre>
 * 改样式请直接改这些文件（真源在 ③ 发布仓库，构建前跑
 * {@code scripts/sync-from-source.sh} 同步到构建树），**不要再往 Java 里塞字符串**。
 * 搬迁前后的内容一致性由 {@code DshAssetsTest} 拿黄金基准逐字节校验过。
 *
 * ## 为什么不能只靠 CSS 打补丁
 * dsh 的 Web UI 是**桌面优先**的三栏网格（{@code dsh-client-ui-layout}）：
 * <pre>
 *   narrow      = viewport &lt; 1024
 *   collapsed   = narrow ? !narrowExpanded : sidebar === 0
 *   sidebarPref = collapsed ? 0 : (sidebar === 0 ? 280 : sidebar)
 * </pre>
 * {@code narrowExpanded} 初值是 true，所以窄屏下侧栏**永不自动折叠**，
 * 而且未折叠时宽度直接取默认 **280px**。393px 的手机上侧栏吃掉 280px，
 * 中间对话区被压成一条竖排（标题逐字换行、输入框变成竖条）。
 *
 * 更关键的是：侧栏**只有"并排网格列"一种形态**，没有覆盖层/抽屉形态；
 * 而折叠态依赖 dsh 自己的状态机，原生折叠按钮又在侧栏内部——
 * 侧栏一旦被藏起来就再也打不开。所以 r16 那种"点一次原生折叠按钮"的补丁式做法体验不行。
 *
 * ## 这一版：注入自己的外壳，不碰 dsh 的状态机
 * 窄屏时：
 * <ol>
 *   <li>注入固定顶栏（汉堡按钮 + 标题），frame 加 padding-top 让出空间；</li>
 *   <li>侧栏 {@code position:absolute} 脱离网格流 → 变成抽屉，默认滑出屏幕外；</li>
 *   <li>遮罩 + 汉堡按钮控制抽屉，点遮罩或 Esc 关闭；</li>
 *   <li>隐藏 dsh 原生折叠按钮（避免两套导航入口打架）；</li>
 *   <li>主区 {@code minmax(0,1fr)} 独占整列，右栏列宽交给内容自适应。</li>
 * </ol>
 * 宽屏（≥1024px）下所有规则都不生效，桌面行为原样保留。
 *
 * <h2>⚠️ 铁律：新增 CSS 必须带作用域前缀（r38 血的教训）</h2>
 *
 * <b>文件里的 CSS 一律裸写在 {@code @media(max-width:1023px)} 里，会命中
 * 「当前页面里的任何元素」，而不是只命中设置面板。</b>
 * 而 dsh 的不同页面<b>类名同源</b>：设置面板与 dsh-market（{@code /dsh-market}
 * 独立路由页）都用 {@code ..._card} / {@code ..._cards} / {@code ..._group} /
 * {@code ..._options} / {@code ..._content} / {@code ..._header}。
 *
 * <p>r38 的真机后果：市场页被为设置面板写的规则污染 ——
 * 标题竖排成一列、插件名一字一行、网格被 {@code minmax(0,1fr)} 压成单列。
 *
 * <p><b>所以新增任何一条规则前，先问三个问题：</b>
 * <ol>
 *   <li>这条规则<b>只应该</b>作用在设置面板吗？→ 是则必须加
 *       {@code [class*="_panel"] } 前缀；</li>
 *   <li>目标元素的<b>真实宿主</b>是谁？（r34踩过：一直在改 {@code _panel}，
 *       但设置面板真正挂在 {@code sidebarCol} 下）；</li>
 *   <li>这个后缀<b>是否作为别的元素的前缀</b>？（r29 踩过：{@code [class*="_nav"]}
 *       同时命中 {@code _navIcon}/{@code _navLabel}，把图标和文字也变成 block）</li>
 * </ol>
 *
 * <p>若目标元素在 {@code market.css} 覆盖的页面里，则走市场页那一套，
 * <b>不要</b>往 {@code mobile.css} 里加。
 *
 * <p><b>铁律二：「元素不见了」先查 z-index，不是查显示逻辑。</b>
 * r37踩过：汉堡 {@code z-index:61} 低于侧栏 {@code 62} → 被盖住，
 * 却花时间去改 display/位置。
 *
 * <p><b>铁律三（r40）：往别人的 flex 容器里塞节点 = 把宽度/高度交给对方决定。</b>
 * 真机实测（截图逐像素量过）：{@code #dsh-market-card} 直接挂在
 * {@code [class*="_panel"]} 下时，{@code _panel} 在该机型上仍是 dsh 的
 * <b>行方向</b> flex，于是卡片
 * <ul>
 *   <li>宽度取 {@code max-content}（≈420px）→ 撑出 360px 的屏幕，文字不换行被裁切；</li>
 *   <li>高度被 {@code align-self:stretch} 拉成整屏（实测 727px），大片空白；</li>
 *   <li>兄弟 {@code _nav} 被挤到 x&gt;420 → <b>整个设置导航列表看不见</b>。</li>
 * </ul>
 * <b>所以：注入节点必须挂到自己 100% 可控的容器里</b>（这里是 JS 打标记的
 * {@code .dsh-s-nav}），并显式写死 {@code flex:0 0 auto} /
 * {@code align-self:stretch} / {@code box-sizing:border-box} / {@code max-width:100%}。
 * 再加一层 {@code guardCard()} 用实测几何自愈 —— <b>不要指望一条 {@code !important}
 * 的 CSS 一定命中</b>。
 *
 * <p><b>铁律四（r40）：非法 CSS 值会被静默丢弃，dsh 自己的规则随之复活。</b>
 * 原 {@code _overlay} 写的是 {@code justify-content:stretch !important} ——
 * {@code stretch} 对 {@code justify-content} <b>不是合法值</b>，整条声明被丢弃，
 * 于是 dsh 的 {@code justify-content:center} 生效：面板一旦宽于视口就被
 * "居中"到屏幕外。写覆盖前先确认值合法。
 *
 * <p><b>铁律五（r40）：{@code var} 提升会制造"看起来对、实际必崩"的死代码。</b>
 * r39 的 {@code onMarket()} 里 {@code btn.getAttribute(...)} 写在
 * {@code var btn=...} 之前 —— 不报语法错、不报编译错，但每次点击都
 * {@code TypeError}，"打开市场"永远点不动。<b>变量声明一律提到函数最前面。</b>
 *
 * <p><b>铁律六（r52）：注入的自定义属性声明在 {@code body} 上，
 * 所以深色覆写不能只写 {@code html.dsh-dark}。</b>
 * 那条选择器匹配的是 {@code <html>}，而 {@code body} 自己的声明会赢过
 * 从 html 继承的值 → 必须写成 {@code html.dsh-dark body} 才真正生效。
 *
 * <p><b>铁律七（r53 · A-02）：高危操作前必须校验来源。</b>
 * 见 {@link DshOrigin}。
 */
public final class MobileTuning {

    private static final String TAG = "MobileTuning";

    /**
     * 注入脚本的进程内缓存。
     * 资源是随 APK 打包的常量，进程存活期间不会变，所以只读一次。
     * 用 volatile + 双重检查：WebView 回调可能在任意线程。
     */
    private static volatile String sJs;

    private MobileTuning() {}

    /** 幂等注入。页面每次加载完成时调用一次即可。 */
    public static void apply(WebView webView) {
        if (webView == null) return;
        String js = loadJs(webView.getContext());
        if (js == null) return;   // 读资源失败：宁可什么都不注入，也不要注入半截脚本
        webView.evaluateJavascript(js, null);
    }

    private static String loadJs(Context ctx) {
        String s = sJs;
        if (s != null) return s;
        synchronized (MobileTuning.class) {
            if (sJs != null) return sJs;
            try {
                // 用 applicationContext：绝不能因为读个资源而拖住 Activity
                final AssetManager am = ctx.getApplicationContext().getAssets();
                sJs = DshAssets.loadJs(new DshAssets.Reader() {
                    @Override public String read(String path) throws IOException {
                        return readAsset(am, path);
                    }
                });
            } catch (Throwable t) {
                // ⚠️ 搬 assets 之后新增的失败模式：以前样式在编译期就定死了，
                // 现在读不到资源会注入空壳。这里必须留 error 日志，否则真机上
                // 只会表现为「手机端适配整个没了」，无从查起。
                Log.e(TAG, "读取注入资源失败（assets/dsh/*），本次注入已跳过", t);
                return null;
            }
            return sJs;
        }
    }

    /** 读一个 assets 文本资源。UTF-8 显式指定，不依赖平台默认编码。 */
    private static String readAsset(AssetManager am, String path) throws IOException {
        InputStream in = am.open(path);
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(8192, in.available()));
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            try { in.close(); } catch (IOException ignored) { }
        }
    }
}