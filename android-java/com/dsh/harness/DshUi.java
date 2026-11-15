package com.dsh.harness;

import android.content.Context;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * DSH「原装黑白单色」设计令牌层（2026-11-15 第二版设计稿）。
 *
 * <p>来源：{@code WB-Sync_两端UI_原装黑白单色_设计稿 / mono/README.md} §2 中性色板与 token、
 * §3 单色状态编码。
 *
 * <p><b>为什么从彩色改成黑白</b>：上一版按「DeepSeek 官网风」做成了品牌蓝 + 薄荷 + 琥珀，
 * 但那是<b>误判</b> —— DSH 原装 {@code dsh web} 是 near-black / near-white 单色
 * （{@code theme-default} 清爽浅色、只有克制的强调；{@code theme-brand} 才是可选蓝）。
 * 手机打开的就是这台电脑的官方 web，壳跟着原装单色才不会在用户切主题时打架。
 *
 * <p><b>去掉色相后状态怎么表达</b>（本类的核心约束）：不靠颜色，靠四个通道 ——
 * <ol>
 *   <li><b>形状</b>：实心点 = 在线/完成；空心环 = 待处理；浅灰空心 = 离线；</li>
 *   <li><b>线型</b>：实线 = 确定；虚线 = 待定/警告；1.5px 近黑粗边 = 危险；</li>
 *   <li><b>明度</b>：近黑底反白 = 关键标识（{@code activePath} / {@code ssh} / 当前选中）；</li>
 *   <li><b>文字标签</b>：每个状态都有中文词，不只靠视觉。</li>
 * </ol>
 * 所以本类里<b>没有</b> {@code BRAND/MINT/AMBER/DANGER} 这样的语义色名，只有明度角色 +
 * {@code tagOn/tagOff/tagWait/tagInvert} 这组状态组件。
 *
 * <p>本类只产出颜色、drawable 与「无业务判断」的复合组件，可被桌面 javac 整树编译校验。
 */
final class DshUi {

    private DshUi() {}

    // ===== 中性色阶（设计稿 §2，零彩度）=====
    /** 近黑：正文 / 主按钮 / 实心状态 / 反白块底。 */
    static final int INK        = 0xFF18181B;
    static final int INK_2      = 0xFF3F3F46;
    /** 次要文字。 */
    static final int LABEL      = 0xFF52525B;
    /** 三级文字。 */
    static final int MUTE       = 0xFF8B8B93;
    static final int FAINT      = 0xFFA1A1AA;

    /** 近白画布 / 抬升面板。 */
    static final int CANVAS     = 0xFFFFFFFF;
    static final int SURFACE    = 0xFFFFFFFF;
    /** 次级块 / 诊断条。 */
    static final int SUBTLE     = 0xFFF4F4F5;
    static final int SUBTLE_2   = 0xFFFAFAFA;
    /** 发丝描边 / 强分隔 / 占位虚线。 */
    static final int HAIRLINE   = 0xFFE4E4E7;
    static final int HAIRLINE_2 = 0xFFD4D4D8;
    static final int RULE       = 0xFFC9C9CE;

    /** 状态明度三档：实心点 / 待处理 / 离线。 */
    static final int STATE_ON   = 0xFF18181B;
    static final int STATE_WAIT = 0xFF52525B;
    static final int STATE_OFF  = 0xFFB5B5BA;

    // 深色系统下的对应档（同一套明度语义，整体反相）
    private static final int D_CANVAS  = 0xFF131316;
    private static final int D_SURFACE = 0xFF1A1A1D;
    private static final int D_SUBTLE  = 0xFF232327;
    private static final int D_SUBTLE2 = 0xFF1E1E21;
    private static final int D_HAIR    = 0xFF2E2E33;
    private static final int D_HAIR2   = 0xFF3F3F45;
    private static final int D_INK     = 0xFFF4F4F5;
    private static final int D_INK2    = 0xFFD4D4D8;
    private static final int D_LABEL   = 0xFFA1A1AA;
    private static final int D_MUTE    = 0xFF71717A;

    // ===== 圆角尺度（设计稿 §2 六个值里用得到四个）=====
    static final int R_PANEL   = 12;
    static final int R_CONTROL = 8;
    static final int R_BADGE   = 4;
    static final int R_AVATAR  = 8;

    // ===== 按钮样式（{@link #button}）=====
    static final int BTN_PRIMARY   = 0;
    static final int BTN_SECONDARY = 1;
    static final int BTN_GHOST     = 2;
    static final int BTN_DANGER    = 3;

    // ===== 旧的语义色名（v2 彩色稿遗留）=====
    //
    // 保留它们只是为了少改调用点：**值已经全部收敛到灰阶**，任何一处用到都不会再冒彩。
    // 新代码请直接用 {@link #INK}/{@link #LABEL} 与 tagXxx 系列 —— 名字里带颜色的常量
    // 在单色体系里会误导后来人。
    static final int BRAND     = INK;
    static final int BRAND_2   = INK;
    static final int MINT      = INK;
    static final int MINT_2    = INK_2;
    static final int AMBER     = INK_2;
    static final int DANGER    = INK;
    static final int INK_3     = MUTE;

    // ===== 随主题取色 =====

    static boolean dark(Context c) { return DshPalette.isDark(c); }

    static int pageBg(Context c) { return dark(c) ? D_CANVAS : CANVAS; }
    static int card(Context c) { return dark(c) ? D_SURFACE : SURFACE; }
    static int line(Context c) { return dark(c) ? D_HAIR : HAIRLINE; }
    static int lineStrong(Context c) { return dark(c) ? D_HAIR2 : HAIRLINE_2; }
    static int inputBg(Context c) { return dark(c) ? D_SUBTLE2 : SUBTLE_2; }
    static int subtle(Context c) { return dark(c) ? D_SUBTLE : SUBTLE; }
    static int ink(Context c) { return dark(c) ? D_INK : INK; }
    static int ink2(Context c) { return dark(c) ? D_INK2 : INK_2; }
    static int label(Context c) { return dark(c) ? D_LABEL : LABEL; }
    static int mute(Context c) { return dark(c) ? D_MUTE : MUTE; }

    // 旧名兼容：v2 稿里有一批 `DshUi.ink3(this)` / `DshUi.soft(this)` 的调用点。
    // 前者是「更淡一档的文字色」，后者是「浅色底块」——单色体系里各归到 MUTE / SUBTLE，
    // 数值上等价，所以无需逐处改调用点。
    static int ink3(Context c) { return mute(c); }
    static int soft(Context c) { return subtle(c); }
    /** 诊断条底色。 */
    static int diagBg(Context c) { return dark(c) ? D_SUBTLE2 : SUBTLE_2; }

    /** dp → px，所有圆角/间距都走这里，避免不同页各写一套密度换算。 */
    static int dp(Context c, int v) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics());
    }

    // ===== drawable 工厂 =====

    /** 分组卡片：近白底 + 1px 发丝线 + 12dp 圆角（设计稿 panel）。 */
    static GradientDrawable cardDrawable(Context c) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(card(c));
        d.setCornerRadius(dp(c, R_PANEL));
        d.setStroke(Math.max(1, dp(c, 1)), line(c));
        return d;
    }

    /** 输入框：极浅底 + 发丝线 + 8dp 圆角（control）。 */
    static GradientDrawable inputDrawable(Context c) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(inputBg(c));
        d.setCornerRadius(dp(c, R_CONTROL));
        d.setStroke(Math.max(1, dp(c, 1)), line(c));
        return d;
    }

    /** 主按钮：**近黑实底**（设计稿明确不再用渐变），8dp 圆角。 */
    static GradientDrawable primaryDrawable(Context c) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(dark(c) ? INK : INK);
        d.setCornerRadius(dp(c, R_CONTROL));
        return d;
    }

    /** 次按钮：近白底 + 发丝线，8dp 圆角。 */
    static GradientDrawable ghostDrawable(Context c) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(card(c));
        d.setCornerRadius(dp(c, R_CONTROL));
        d.setStroke(Math.max(1, dp(c, 1)), line(c));
        return d;
    }

    /** 危险操作：近白底 + **1.5px 近黑粗边**（单色体系用粗边表示危险，不用红）。 */
    static GradientDrawable dangerDrawable(Context c) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(card(c));
        d.setCornerRadius(dp(c, R_CONTROL));
        d.setStroke(Math.max(2, dp(c, 2)), ink(c));
        return d;
    }

    /** 状态标签底：4dp 圆角（badge），可带发丝线；{@code dashed} 用于「待定/离线」。 */
    static GradientDrawable chipDrawable(Context c, int fill, int strokeColor, boolean dashed) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(dp(c, R_BADGE));
        if (strokeColor != 0) {
            if (dashed) {
                d.setStroke(Math.max(1, dp(c, 1)), strokeColor, dp(c, 3), dp(c, 3));
            } else {
                d.setStroke(Math.max(1, dp(c, 1)), strokeColor);
            }
        }
        return d;
    }

    /** 兼容旧调用：无描边、实线的 8dp 圆角块（分段外槽 / 端口 pill 用）。 */
    static GradientDrawable pillDrawable(Context c, int fill) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(dp(c, R_CONTROL));
        return d;
    }

    /** 应用栏圆钮 / 头像这类方块的底：近白 + 发丝线。 */
    static GradientDrawable roundDrawable(Context c) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(card(c));
        d.setCornerRadius(dp(c, R_CONTROL));
        d.setStroke(Math.max(1, dp(c, 1)), line(c));
        return d;
    }

    /** 虚线卡（错误/警告/待处理）：1.5px 虚线近黑边，不填充告警色。 */
    static GradientDrawable dashedCardDrawable(Context c, int strokeColor) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(dark(c) ? D_SUBTLE2 : SUBTLE_2);
        d.setCornerRadius(dp(c, R_PANEL));
        d.setStroke(Math.max(2, dp(c, 2)), strokeColor, dp(c, 4), dp(c, 4));
        return d;
    }

    // ===== 文字与布局 =====

    static TextView label(Context c, String text, float sp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(text);
        t.setTextColor(color);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        if (bold) t.setTypeface(t.getTypeface(), Typeface.BOLD);
        t.setLineSpacing(dp(c, 2), 1f);
        return t;
    }

    /** 等宽文字：hostId / seq / 指纹 / 令牌 / 端口 / 诊断行统一走这里。 */
    static TextView mono(Context c, String text, float sp, int color, boolean bold) {
        TextView t = label(c, text, sp, color, bold);
        t.setTypeface(Typeface.MONOSPACE);
        return t;
    }

    /** 可点击文字（下划线，无颜色）：设计稿里所有「›」「显示」「复制」都是这个形态。 */
    static TextView link(Context c, String text, float sp, int color, boolean bold) {
        TextView t = label(c, text, sp, color, bold);
        t.setPaintFlags(t.getPaintFlags() | Paint.UNDERLINE_TEXT_FLAG);
        return t;
    }

    static LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    static LinearLayout.LayoutParams matchWrapTop(Context c, int topDp) {
        LinearLayout.LayoutParams lp = matchWrap();
        lp.topMargin = dp(c, topDp);
        return lp;
    }

    /** 等分的一行两列：weight 各 1，中间留 8dp。 */
    static LinearLayout.LayoutParams half(Context c, boolean left) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMargins(left ? 0 : dp(c, 4), 0, left ? dp(c, 4) : 0, 0);
        return lp;
    }

    /**
     * 带右边距的「固定尺寸」LayoutParams。
     *
     * <p>存在的理由：{@code LinearLayout.addView(v, lp)} 会用传入的 lp <b>覆盖</b>
     * 视图自己 setLayoutParams 设的宽高，所以圆钮这类定尺控件必须由调用方给出
     * 同样的尺寸，否则会被压成 WRAP_CONTENT（真机表现：底框消失、只剩一个字形）。
     */
    static LinearLayout.LayoutParams fixedEnd(Context c, int sizeDp, int endDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(c, sizeDp), dp(c, sizeDp));
        lp.rightMargin = dp(c, endDp);
        return lp;
    }

    // ===== 复合组件 =====
    //
    // 这些是设计稿里「每一屏都长一样」的骨架。抽出来是为了让 06/07/09/11 之间
    // 不会各自漂移 —— 尤其在圆角、字号、行距这些一眼就能看出差别的数值上。

    /**
     * 应用栏：左圆钮（返回）+ 标题 + 右侧可选圆钮 + 底部发丝线。
     *
     * <p>返回纵向容器（行 + 分隔线），调用方照旧 {@code addView(appBar(...), matchWrap())}。
     *
     * @param backGlyph 传 null 表示不画左侧圆钮
     * @param rightGlyph 传 null 表示不画右侧圆钮
     */
    static LinearLayout appBar(Context c, String title,
                               String backGlyph, View.OnClickListener back,
                               String rightGlyph, View.OnClickListener right) {
        LinearLayout wrap = new LinearLayout(c);
        wrap.setOrientation(LinearLayout.VERTICAL);

        LinearLayout bar = new LinearLayout(c);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        int vp = dp(c, 4);
        bar.setPadding(0, vp, 0, vp);
        if (backGlyph != null) {
            bar.addView(roundButton(c, backGlyph, back), fixedEnd(c, 30, 9));
        }
        bar.addView(label(c, title, 14, ink(c), true));
        bar.addView(new View(c), new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        if (rightGlyph != null) {
            bar.addView(roundButton(c, rightGlyph, right));
        }
        wrap.addView(bar, matchWrap());
        wrap.addView(divider(c));
        return wrap;
    }

    /** 30dp 圆角方块按钮（设计稿 .rnd），用于返回/刷新这类单字形动作。 */
    static TextView roundButton(Context c, String glyph, View.OnClickListener l) {
        TextView t = new TextView(c);
        t.setText(glyph);
        t.setTextColor(label(c));
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        t.setGravity(Gravity.CENTER);
        t.setBackground(roundDrawable(c));
        t.setClickable(true);
        t.setLayoutParams(new LinearLayout.LayoutParams(dp(c, 30), dp(c, 30)));
        t.setOnClickListener(l);
        pressable(t);
        return t;
    }

    /** 分组小标题（设计稿 .sec）：10.5sp、mute、加粗、字距略放。 */
    static TextView section(Context c, String text) {
        TextView t = label(c, text, 10.5f, mute(c), true);
        t.setPadding(dp(c, 2), 0, dp(c, 2), 0);
        return t;
    }

    /** 分组卡片容器：纵向、12dp 圆角、近白底 + 发丝线；子视图自己带内边距。 */
    static LinearLayout cardBox(Context c) {
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(cardDrawable(c));
        return box;
    }

    /** 卡片内的一行：横向、垂直居中、12/11dp 内边距。 */
    static LinearLayout rowBox(Context c) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(c, 12), dp(c, 11), dp(c, 12), dp(c, 11));
        return row;
    }

    /** 行的下分隔线（设计稿用 border-bottom，Android 需要单独一条 1dp 视图）。 */
    static View divider(Context c) {
        View v = new View(c);
        v.setBackgroundColor(line(c));
        v.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Math.max(1, dp(c, 1))));
        return v;
    }

    /**
     * 34dp 方块头像：浅灰底 + 近黑字（设计稿 .av，8dp 圆角）。
     *
     * <p>单色稿**没有渐变头像** —— 上一版的品牌渐变正是「彩度」的主要来源之一。
     *
     * @param tone 0 = 最浅（当前设备），1 = 次级，2 = 最淡（离线）
     */
    static TextView avatar(Context c, String text, int tone) {
        TextView t = new TextView(c);
        t.setText(text);
        t.setTextColor(tone == 2 ? mute(c) : (tone == 1 ? ink2(c) : ink(c)));
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        t.setTypeface(t.getTypeface(), Typeface.BOLD);
        t.setGravity(Gravity.CENTER);
        GradientDrawable d = new GradientDrawable();
        d.setColor(dark(c) ? D_SUBTLE : (tone == 0 ? 0xFFE9E9EC : SUBTLE));
        d.setCornerRadius(dp(c, R_AVATAR));
        t.setBackground(d);
        t.setLayoutParams(new LinearLayout.LayoutParams(dp(c, 34), dp(c, 34)));
        return t;
    }

    /** 兼容旧签名（渐变参数已无意义：单色稿没有渐变头像）。 */
    static TextView avatar(Context c, String text, int ignoredFrom, int ignoredTo) {
        return avatar(c, text, 0);
    }

    // ===== 单色状态标签（设计稿 §3）=====
    //
    // 四个通道里「形状 + 线型」全在这里：实心点 / 空心环 / 反白块 / 虚线。
    // 每个都带中文词，读屏与色弱都能拿到语义。

    /** 在线 / 已完成 / 可用：浅灰底 + 实心近黑点 + 实线边。 */
    static LinearLayout tagOn(Context c, String text) {
        return tag(c, text, subtle(c), ink(c), 1, lineStrong(c), false);
    }

    /** 离线 / 失效：近白底 + 浅灰空心圈 + 灰虚线边 + 灰字。 */
    static LinearLayout tagOff(Context c, String text) {
        return tag(c, text, dark(c) ? D_SUBTLE2 : SUBTLE_2, mute(c), 2, lineStrong(c), true);
    }

    /** 待处理 / 待审批 / 归属：近白底 + 空心环 + 近黑虚线边。 */
    static LinearLayout tagWait(Context c, String text) {
        return tag(c, text, dark(c) ? D_SUBTLE2 : SURFACE, ink2(c), 2, ink2(c), true);
    }

    /** 关键标识 / 真源 / 当前选中：**反白块**（黑底白字）。 */
    static LinearLayout tagInvert(Context c, String text) {
        return tag(c, text, ink(c), dark(c) ? INK : 0xFFFFFFFF, 0, 0, false);
    }

    /**
     * 状态标签底层。
     *
     * @param dot 0 = 无点，1 = 实心点，2 = 空心环
     */
    static LinearLayout tag(Context c, String text, int fill, int fg,
                            int dot, int strokeColor, boolean dashed) {
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.setBackground(chipDrawable(c, fill, strokeColor, dashed));
        box.setPadding(dp(c, 7), dp(c, 3), dp(c, 7), dp(c, 3));
        if (dot != 0) {
            View v = new View(c);
            GradientDrawable d = new GradientDrawable();
            d.setShape(GradientDrawable.OVAL);
            if (dot == 1) {
                d.setColor(fg);
            } else {
                d.setColor(0x00000000);
                d.setStroke(Math.max(2, dp(c, 2)), dot == 2 ? strokeColorForRing(c, fg) : fg);
            }
            v.setBackground(d);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(c, 6), dp(c, 6));
            lp.rightMargin = dp(c, 5);
            box.addView(v, lp);
        }
        TextView t = label(c, text, 9.5f, fg, true);
        box.addView(t);
        return box;
    }

    /** 空心环的描边色：离线用浅灰，待处理用中灰，其余跟随文字色。 */
    private static int strokeColorForRing(Context c, int fg) {
        if (fg == mute(c)) return dark(c) ? D_MUTE : STATE_OFF;
        return fg;
    }

    /** 诊断条（设计稿 .diag）：等宽、10sp、极浅底 + 发丝线。 */
    static TextView diagBar(Context c, String text) {
        TextView t = mono(c, text, 9.5f, label(c), false);
        GradientDrawable d = new GradientDrawable();
        d.setColor(diagBg(c));
        d.setCornerRadius(dp(c, R_CONTROL));
        d.setStroke(Math.max(1, dp(c, 1)), line(c));
        t.setBackground(d);
        t.setPadding(dp(c, 11), dp(c, 9), dp(c, 11), dp(c, 9));
        t.setLineSpacing(dp(c, 3), 1f);
        return t;
    }

    /**
     * 按钮（设计稿 .btn 系列）。
     *
     * <p>用 TextView 而不是 Button：系统 Button 自带 CAPS、最小宽度与灰色底，
     * 逐项 reset 比自绘更啰嗦。
     */
    static TextView button(Context c, String text, int style) {
        TextView t = new TextView(c);
        t.setText(text);
        t.setGravity(Gravity.CENTER);
        t.setClickable(true);
        int h;
        switch (style) {
            case BTN_PRIMARY:
                t.setBackground(primaryDrawable(c));
                t.setTextColor(0xFFFFFFFF);
                t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
                t.setTypeface(t.getTypeface(), Typeface.BOLD);
                h = 42;
                break;
            case BTN_DANGER:
                t.setBackground(dangerDrawable(c));
                t.setTextColor(ink(c));
                t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
                t.setTypeface(t.getTypeface(), Typeface.BOLD);
                h = 36;
                break;
            case BTN_GHOST:
                // 幽灵按钮 = 下划线文字（设计稿 .btn.gho），没有底色与边框
                t.setBackgroundColor(0x00000000);
                t.setTextColor(label(c));
                t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
                t.setPaintFlags(t.getPaintFlags() | Paint.UNDERLINE_TEXT_FLAG);
                h = 30;
                break;
            case BTN_SECONDARY:
            default:
                t.setBackground(ghostDrawable(c));
                t.setTextColor(ink(c));
                t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
                t.setTypeface(t.getTypeface(), Typeface.BOLD);
                h = 42;
                break;
        }
        t.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(c, h)));
        pressable(t);
        return t;
    }

    /** 按钮/可点行的按下反馈：自绘背景没有 ripple，用透明度兜住。 */
    static void pressable(View v) {
        v.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View view, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        view.setAlpha(0.62f);
                        break;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        view.setAlpha(1f);
                        break;
                    default:
                        break;
                }
                return false;
            }
        });
    }
}
