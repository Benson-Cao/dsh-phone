package com.dsh.harness;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Color;
import android.util.Log;

/**
 * 启动页 / 状态页的**调色板**，随系统深浅色切换。
 *
 * ## 为什么要这个类（而不是在 onCreate 里 if 一句）
 *
 * 启动页有5 处用色（root 底、ScrollView 底、标题字、状态文案、状态栏/导航栏），
 * 分散在 {@link MainActivity} 的不同位置。原地if 会导致：
 * 「底色换了但状态栏还是浅的」这种半深半浅的割裂 —— 那是 r20「红色状态栏」的同一类问题。
 * 所以收在一处，取色时保证整套同时生效。
 *
 * ## 判定标准：为什么不能只看系统 UI_MODE_NIGHT
 *
 * dsh（WebView 里的页面）**有自己的主题**，由用户在 dsh 里选，存localStorage。
 * {@code assets/dsh/mobile.js} 的 {@code detectDark()} 读的是**页面自身背景色的亮度**
 * （lum &lt; 0.5 判深色），还有个反向标记 {@code data-ds-dark-theme}
 * （dsh 强制浅色时打上，此时即便系统是深色也走浅色）。
 *
 * 如果启动页只跟系统走，会出现：dsh 里选了浅色 + 手机系统深色
 * → 启动页闪一下深色 → 页面载入后变浅色 → **肉眼可见的一次闪白/闪黑**。
 *
 * ## 解决：两段式
 *
 * 1. 启动页阶段（WebView 还没加载 dsh，**读不到 dsh 主题**）
 *    → 用 {@code uiMode & UI_MODE_NIGHT_MASK} 判断，这是此刻唯一可得的信息；
 * 2. {@link #refreshFromDsh(Context, boolean)} —— WebView 加载完、拿到 dsh 主题后回调，
 *    用真实主题修正并刷新界面。
 *
 * 这样最坏情况只是「启动页与页面主题不一致的那几百毫秒」，
 * 而不是全程色错 + 闪烁。
 *
 * @see MainActivity#applyPalette()
 */
final class DshPalette {

    private DshPalette() {}

    // ===== 浅色（黑白单色稿 mono/v3）=====
    //
    // 2026-11-15：上一版这里是「深蓝 + 品牌蓝」的一整套（底 #F7F9FC、正文 #0D1B2E、
    // 弱化 #5A6D84）。用户判定那次方向错了 —— 官方 `dsh web` 本身就是近黑近白，
    // 壳层再套一层蓝调，在用户切主题时会明显打架。现在全部收敛到零彩度灰阶，
    // 与 {@link DshUi} 的 token 取同一组值。
    //
    // ⚠️ 启动页的鲸鱼是**透明底剪影 PNG**（`dsh_splash_whale` / `_night`），
    //    深蓝从来只来自这里的 bg，所以改色值不需要重出图。
    /** 页面底色 —— 与 dsh 首页浅色底一致（纯白）。 */
    static final int L_BG      = 0xFFFFFFFF;
    /** 正文/标题 —— --dsw-ink #18181B（白底 ≈17.7:1）。 */
    static final int L_TEXT    = 0xFF18181B;
    /**
     * 浅色下的弱化文案 —— --dsw-label #52525B（白底 ≈7.7:1，仍明显弱于正文）。
     *
     * <p>⚠️ 下面这段历史别删：这里曾经被<b>算错过一次</b>，而错的方向很反直觉。
     *
     * <p>当年用的是漏了伽马线性化的公式（见 {@link #lum}），得到一串<b>偏低的错值</b>，
     * 并据此认为"弱化与 AA 在浅底上几乎互斥"，把色值一路压到近黑的 {@code #2C2D35}
     * （真值 12.98:1 —— 跟正文几乎同深，"弱化"层级其实被抹平了）。
     * 用正确公式重算后结论完全变了：设计稿原值 {@code #8296AB} 是 2.89:1（确实不达标），
     * 但 {@code #5A6D84} 就有 5.03:1，所以根本不需要压到近黑。
     *
     * <p>危害方向永远是<b>误杀</b>：数值偏低看着"保守"，实际会毁掉视觉层级。
     *
     * <p>{@code logContrast()} 每次启动都会打真实对比度，别信注释里的数字，信日志。
     */
    static final int L_MUTED_AA = 0xFF52525B;
    /** 实际使用：见 {@link #muted}。 */
    static final int L_MUTED   = L_MUTED_AA;
    // ===== 深色 =====
    /**
     * 深色底。
     *
     * <p>⚠️ 两个色值分工不同，**别混用**：
     * <ul>
     *   <li>{@link #D_BG} —— 启动页/诊断页等<b>我们自己的</b>界面底色。</li>
     *   <li>{@link #D_SYSTEM_BAR} —— <b>系统栏</b>底色（状态栏/导航栏）。
     *       必须与 <b>dsh 首页背景完全一致</b>，否则两者相邻会出现可见色差。</li>
     * </ul>
     *
     * <p>单色稿里两者取同一个值 {@code #151517}（真机量得的 dsh 首页背景）。
     * 早年 D_BG 用的是偏蓝的 {@code #141B23}，深色下能看出状态栏"发蓝"
     * （用户 2026-10-13 反馈），现在没有存在的理由了 —— **刻意让它们相等**，
     * 这样"启动页 → 页面"的过渡连色差都没有。
     */
    static final int D_BG      = 0xFF151517;
    /**
     * 系统栏底色（深色）—— 真机量得的 dsh 首页背景值。
     *
     * <p>来源：adb screencap 后解码取样，首页主容器 {@code #151517}。
     * 若 dsh 将来改了首页背景，记得重新量一次，两处色差会被立刻看见。
     */
    static final int D_SYSTEM_BAR = 0xFF151517;
    /** 深色底的正文 —— --dsw-ink 深色档 #F4F4F5（≈16.6:1 PASS）。 */
    static final int D_TEXT    = 0xFFF4F4F5;
    /** 深色底的弱化文案 —— --dsw-label-faint #A1A1AA（≈7.1:1 PASS，仍弱于正文）。 */
    static final int D_MUTED   = 0xFFA1A1AA;

    /**
     * 品牌色锚点。
     *
     * <p>单色稿里品牌色<b>就是近黑</b>（{@code #18181B}）：主按钮黑底白字、
     * 分段选中反白块、导航选中反白 —— 没有任何一处需要色相。
     *
     * <p>⚠️ 上一版这里是 {@code #4D6BFE}（蓝）。当年还写过一大段"品牌色作底色时
     * 对比度只有 2.49:1，必须配深字"的论证 —— 那段分析本身没错，但既然不再有彩色，
     * 这一整类问题就消失了。<b>不要再把它改回彩色</b>：官方 `dsh web` 近黑近白，
     * 壳层上彩色会在用户切主题时打架。
     *
     * <p>本常量仅作文档锚点，不参与取色。
     */
    static final int BRAND_NOTE = 0xFF18181B;

    // 字段与取色方法见下方「优先级」注释块——那里的说明有真机踩坑记录，务必先读。

    /**
     * 渐变线的末端透明色。
     *
     * <p>⚠️ 原代码写死 {@code 0x00FFFFFF}（白→透明），这只在**浅底**上成立：
     * 白底下渐变末端是"白色透明"，看起来像渐隐。
     * 深底下若仍用白色透明，末端会泛出**白雾**，反而更显眼 —— 必须换成底色透明。
     */
    static int fadeToBg(Context c) { return (bg(c) & 0x00FFFFFF); }

    /**
     * 当前是否深色。
     *
     * <p>🔴 <b>优先级是这里最关键的设计决策，改之前先读下面这段。</b>
     *
     * <p>r79 真机踩过的坑：探测逻辑只探一次、在 dsh 主题生效前就读 body 背景，
     * 读到浅色 → 落盘 {@code dshDark=false}。之后每次冷启动
     * {@code dshDarkOverride != null} 就<**直接返回，不再读系统**</b>
     * → 设备明明是 {@code Night mode: yes}，启动页却画成浅色，
     * 而 dsh 页面（跟随系统）是深色 → <b>浅色启动页 + 深色页面</b>，闪瞎眼。
     * 一条错误的缓存把错误值固化了。
     *
     * <p>所以现在的规则是<b>「只有权威信号才允许覆盖系统」</b>：
     * <ul>
     *   <li>{@link #SYS}（null）= 没拿到可信结论 → <b>跟随系统</b>。</li>
     *   <li>页面自报 {@code html.dsh-dark} / {@code data-ds-dark-theme}
     *       → 那是 {@code mobile.js} 的 detectDark() 算完写下的最终结论，
     *       属权威，此时才覆盖系统。</li>
     * </ul>
     *
     * <p>换句话说：<b>探测只是"系统的补充"，不是"系统的主人"</b>。
     * 猜错时宁可跟系统走 —— 最多浅深略有出入，也不会出现整屏闪色。
     */
    private static Boolean dshDarkOverride = null;

    /** 页面底色。 */
    static int bg(Context c) { return isDark(c) ? D_BG : L_BG; }
    /** 卡片/骨架块填充色：页面底色之上的一层（浅色=纯白，深色=比底色亮一档的 #1A1A1D）。 */
    static int surface(Context c) { return isDark(c) ? 0xFF1A1A1D : 0xFFFFFFFF; }
    /** 主文字色。 */
    static int text(Context c) { return isDark(c) ? D_TEXT : L_TEXT; }
    /** 弱化文字色。 */
    static int muted(Context c) { return isDark(c) ? D_MUTED : L_MUTED; }

    /**
     * 当前是否深色：<b>权威探测结果优先，否则跟随系统</b>。
     *
     * @see #dshDarkOverride 的说明（这里是有历史教训的地方，别随便改成无条件信任缓存）
     */
    static boolean isDark(Context c) {
        Boolean o = dshDarkOverride;
        if (o != null) return o.booleanValue();
        return systemIsDark(c);
    }

    /** 系统自身的深浅色（不含页面探测）。 */
    static boolean systemIsDark(Context c) {
        try {
            int ui = c.getResources().getConfiguration().uiMode
                    & Configuration.UI_MODE_NIGHT_MASK;
            return ui == Configuration.UI_MODE_NIGHT_YES;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 只用<b>系统</b>判断（忽略页面探测）。
     *
     * <p>用于「一致性校验」：如果页面自报浅色、系统却是深色，
     * 说明探测很可能踩在主题生效前的空窗期，此时应回到系统口径。
     */
    static boolean preferSystem(Context c) {
        dshDarkOverride = null;
        return systemIsDark(c);
    }

    /**
     * 用 dsh 页面的真实主题修正配色（WebView 加载完成后调用）。
     *
     * @param dshDark dsh 当前是否深色（由页面侧读自身背景亮度得出）
     * @return 若配色发生变化返回 true，调用方据此刷新界面
     */
    static boolean refreshFromDsh(Context c, boolean dshDark) {
        boolean was = isDark(c);
        dshDarkOverride = Boolean.valueOf(dshDark);
        return was != dshDark;
    }

    /**
     * 记住 dsh 的主题并**持久化**，供下次冷启动使用。
     *
     * <p>为什么必须持久化：启动页在 WebView 加载 dsh **之前**就已经画好了，
     * 那一刻拿不到 dsh 主题，只能按系统 uiMode 猜。若用户在 dsh 里选的主题与系统不同，
     * 就只能等页面加载完再纠正 —— 而那时启动页已经被 WebView 顶掉了。
     * <p>把结果落盘后，**下一次**冷启动就能直接用对颜色，不再闪错色。
     *
     * @return 若本次修正改变了判定结果返回 true
     */
    static boolean rememberDshDark(Context c, boolean dshDark) {
        boolean changed = refreshFromDsh(c, dshDark);
        try {
            c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putBoolean(KEY_DSH_DARK, dshDark).apply();
        } catch (Throwable ignored) { }
        return changed;
    }

    /**
     * 系统深浅色变化时，让缓存失效。
     *
     * <p>r79 真机教训：dsh 的深色默认<b>跟随系统</b>（实测 localStorage 为空）。
     * 用户中途切系统主题时，之前缓存的 {@code dshDark} 就成了**过期值**——
     * 而且 {@link #isDark} 会优先用它，导致启动页继续按旧主题画。
     * 所以系统主题一变，立刻丢掉缓存重新跟随。
     */
    static void invalidateCacheOnSystemThemeChange(Context c) {
        if (dshDarkOverride != null) {
            Log.i("DshPalette", "系统主题变化 → 丢弃 dsh 主题缓存，重新跟随系统");
            dshDarkOverride = null;
        }
    }

    private static final String PREFS = "dsh_theme";
    private static final String KEY_DSH_DARK = "dshDark";

    /**
     * 冷启动时读取上次记住的 dsh 主题。
     *
     * <p>必须在 {@link #isDark} 首次被调用**之前**执行（onCreate 早期），
     * 否则启动页会用系统主题画一遍再改。
     *
     * @return 是否成功读到
     */
    static boolean preloadFromPrefs(Context c) {
        try {
            if (!c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .contains(KEY_DSH_DARK)) {
                return false;
            }
            boolean d = c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getBoolean(KEY_DSH_DARK, false);
            dshDarkOverride = Boolean.valueOf(d);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 仅供测试/切换场景复位（正常流程不需要调）。 */
    static void resetDshOverride() { dshDarkOverride = null; }

    /**
     * 浅色下的系统栏底色 —— 与 dsh 首页浅色背景一致（纯白）。
     *
     * <p>单独列常量是为了让「系统栏取色」有一处明确来源：首页改底色时只改这里。
     * 单色稿下它与 {@link #L_BG} 相等 —— 不需要色差，也就不该有色差。
     */
    static final int L_SYSTEM_BAR = 0xFFFFFFFF;

    /**
     * 系统栏底色（跟随当前主题）。
     *
     * <p>与 {@link #bg} 刻意<b>分开</b>：启动页可以用我们自己的底色，
     * 而系统栏必须与 <b>dsh 首页背景对齐</b>（{@link #D_SYSTEM_BAR}），
     * 否则两者相邻会出现肉眼可见的色差。
     */
    static int systemBar(Context c) {
        return isDark(c) ? D_SYSTEM_BAR : L_SYSTEM_BAR;
    }

    /**
     * 状态栏图标是否应为「深色图标」（=浅色底）。
     *
     * <p>API 23+ 用 {@code SYSTEM_UI_FLAG_LIGHT_STATUS_BAR}；
     * API 30+ 推荐 {@code WindowInsetsController}。这里保留旧 API —— minSdk=21，
     * 且项目里状态栏处理一直是这条路径。
     */
    static int systemUiFlags(Context c, int current) {
        int f = current;
        boolean lightBg = !isDark(c);
        if (lightBg) {
            f |= android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        } else {
            f &= ~android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        }
        return f;
    }

    /** 供 CSS 侧参考：把当前背景色写成 "#RRGGBB"，方便日志对齐。 */
    static String hex(Context c) {
        int v = bg(c);
        return String.format("#%06X", 0xFFFFFF & v);
    }

    /** 对比度自检（WCAG AA 正文需≥4.5:1）。启动页用色就这几对，启动时打日志便于回归。 */
    static void logContrast(Context c) {
        try {
            Log.i("DshPalette", "bg=" + hex(c) + " text=" + contrast(bg(c), text(c))
                    + ":1 muted=" + contrast(bg(c), muted(c)) + ":1 (AA 正文需≥4.5)");
        } catch (Throwable ignored) { }
    }

    private static double contrast(int a, int b) {
        double l1 = lum(a), l2 = lum(b);
        if (l1 < l2) { double t = l1; l1 = l2; l2 = t; }
        return Math.round(((l1 + 0.05) / (l2 + 0.05)) * 100) / 100.0;
    }

    /**
     * 相对亮度（WCAG 2.1 定义）。
     *
     * <p>⚠️ r105 修了一个真 BUG，改之前先读：
     *
     * <p>原实现直接拿通道原始值加权（{@code 0.2126*r + 0.7152*g + 0.0722*b}），
     * <b>漏掉了 sRGB 的伽马线性化</b>。WCAG 要求先把每个通道做
     * {@code c<=0.03928 ? c/12.92 : ((c+0.055)/1.055)^2.4}，
     * 缺这一步会让中间调的亮度被明显高估 → 对比度被<b>系统性低估</b>：
     * <pre>
     *   #0D1B2E（正文）  旧 6.86:1  → 真 16.41:1
     *   #2C2D35（弱化）  旧 4.50:1  → 真 12.98:1
     *   #E6EDF5（深正文）旧 6.41:1  → 真 14.70:1
     * </pre>
     *
     * <p>危害方向是<b>误杀</b>：本来达标的设计稿色被判 FAIL，
     * 于是被迫把颜色一路改深 —— {@link #L_MUTED_AA} 被定成近黑色就是这么来的。
     * 数值偏低看似"保守"，实际会毁掉视觉层级，所以必须修。
     */
    private static double lum(int rgb) {
        return 0.2126 * chan((rgb >> 16) & 0xFF)
             + 0.7152 * chan((rgb >> 8) & 0xFF)
             + 0.0722 * chan(rgb & 0xFF);
    }

    /** sRGB 单通道线性化（WCAG 2.1 相对亮度的前置步骤）。 */
    private static double chan(int v) {
        double c = v / 255.0;
        return c <= 0.03928 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
    }
}
