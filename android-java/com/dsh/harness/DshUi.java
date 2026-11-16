package com.dsh.harness;

import android.content.Context;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.StyleSpan;
import android.text.style.UnderlineSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
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
        // 高度必须写死 0，不能用 WRAP_CONTENT：普通 View 在 AT_MOST 测量下
        // getDefaultSize() 会返回父容器给的**上限**（整屏高），于是这枚纯占位 View
        // 把 bar 撑成全屏、文字被垂直居中、后面的分段控件与内容全被挤出屏幕。
        // 应用栏移出 ScrollView（r145 固定头部）后父高度从 UNSPECIFIED 变成 AT_MOST，
        // 这个原本「无害」的 WRAP_CONTENT 就成了致命值（r146 实测回归修复）。
        bar.addView(new View(c), new LinearLayout.LayoutParams(0, 0, 1f));
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
        // ⚠️ 必须同时给 minHeight：调用方普遍用 addView(btn, matchWrapTop(...)) 添加，
        // 那套 LayoutParams 是 MATCH_PARENT × **WRAP_CONTENT**，会把上面这个固定高度盖掉。
        // 只设 LayoutParams 的写法在真机上是「按钮只有文字那么高」（r141 三处截图都栽在这）。
        t.setMinHeight(dp(c, h));
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

    // ===== 表单 / 说明 / 分段控件（设计稿 .field .inp .help .info .err .step .map .seg）=====
    //
    // 这一组存在的理由和上面的 appBar/rowBox 一样：05（SSH）与 04（配对）都要画
    // 「字段标题 + 输入框 + 说明」，07/08 都要画「说明小卡 / 虚线错误卡」。
    // 上一版这些是各页私有的小工具，于是同一份视觉在 SSH 页长成了另一套字号
    // （标题 18sp、hint 当标签、系统 Button 13sp）—— 单色稿落地后它成了唯一
    // 还留着旧视觉的页面。抽到这里之后，改一处就是改全站。

    /** 分段控件的点击回调（按下标，不按文字 —— 文案会变，下标不会）。 */
    interface OnIndexClick {
        void onIndex(int index);
    }

    /** 字段标题行（设计稿 .lab）：11sp 加粗 label 色，右侧可带 ? 气泡。 */
    static LinearLayout fieldLabel(Context c, String text, String badge) {
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.addView(label(c, text, 11, label(c), true));
        if (badge != null && badge.length() > 0) {
            TextView q = label(c, badge, 9, mute(c), true);
            q.setGravity(Gravity.CENTER);
            q.setBackground(pillDrawable(c, subtle(c)));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(c, 13), dp(c, 13));
            lp.leftMargin = dp(c, 6);
            box.addView(q, lp);
        }
        return box;
    }

    /**
     * 输入框（设计稿 .inp）：37dp 高、8dp 圆角、极浅底、等宽 12.5sp。
     *
     * <p>用 EditText 而不是 TextView：所有调用点都需要真实输入。等宽是设计稿的
     * 明确口径（地址/端口/令牌都是机器字），也是「一眼看出填错位」的辅助。
     */
    static EditText input(Context c, String hint, int inputType) {
        EditText e = new EditText(c);
        e.setHint(hint);
        e.setHintTextColor(mute(c));
        e.setTextColor(ink(c));
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        e.setTypeface(Typeface.MONOSPACE);
        e.setSingleLine(true);
        e.setInputType(inputType);
        e.setBackground(inputDrawable(c));
        int h = dp(c, 11);
        e.setPadding(h, dp(c, 8), h, dp(c, 8));
        e.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(c, 37)));
        return e;
    }

    /** 字段说明（设计稿 .help）：10sp mute、行距 1.6。 */
    static TextView help(Context c, CharSequence text) {
        TextView t = label(c, "", 10, mute(c), false);
        t.setText(text);
        t.setLineSpacing(dp(c, 2), 1f);
        t.setPadding(dp(c, 12), dp(c, 5), dp(c, 12), dp(c, 10));
        return t;
    }

    /**
     * 「部分加粗」的说明文字：设计稿里 {@code .help b} 是**加粗 + 下划线**。
     *
     * @param parts 逐段文字
     * @param bold  与 parts 等长；true 的段落加粗并加下划线
     */
    static CharSequence emphasis(Context c, String[] parts, boolean[] bold) {
        SpannableStringBuilder sb = new SpannableStringBuilder();
        for (int i = 0; i < parts.length; i++) {
            int start = sb.length();
            sb.append(parts[i]);
            if (bold != null && i < bold.length && bold[i]) {
                sb.setSpan(new StyleSpan(Typeface.BOLD), start, sb.length(),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                sb.setSpan(new UnderlineSpan(), start, sb.length(),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }
        return sb;
    }

    /**
     * 说明小卡（设计稿 .info）：左侧状态点 + 11sp 文字。
     *
     * @param dot 0 = 无点，1 = 实心点，2 = 空心环（设计稿用空心环表示"未确认/历史"）
     */
    static LinearLayout infoCard(Context c, int dot, CharSequence text) {
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(card(c));
        bg.setCornerRadius(dp(c, R_CONTROL));
        bg.setStroke(Math.max(1, dp(c, 1)), line(c));
        box.setBackground(bg);
        box.setPadding(dp(c, 12), dp(c, 10), dp(c, 12), dp(c, 10));
        if (dot != 0) {
            box.addView(dotView(c, dot), dotLp(c));
        }
        TextView t = label(c, "", 11, label(c), false);
        t.setText(text);
        t.setLineSpacing(dp(c, 2), 1f);
        box.addView(t, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        return box;
    }

    /** 状态点视图（设计稿 .dot）：7dp；实心 / 空心环。 */
    static View dotView(Context c, int style) {
        View v = new View(c);
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        if (style == 1) {
            d.setColor(ink(c));
        } else if (style == 3) {
            d.setColor(0x00000000);
            d.setStroke(Math.max(2, dp(c, 2)), ink(c));
        } else {
            d.setColor(0x00000000);
            d.setStroke(Math.max(2, dp(c, 2)), dark(c) ? D_MUTE : STATE_OFF);
        }
        v.setBackground(d);
        return v;
    }

    static LinearLayout.LayoutParams dotLp(Context c) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(c, 7), dp(c, 7));
        lp.rightMargin = dp(c, 9);
        return lp;
    }

    /**
     * 虚线错误卡（设计稿 .err）：空心环 + 结论标题 + 等宽地址 + 编号步骤。
     *
     * <p>单色体系里「错误」靠 **1.5px 近黑虚线** 表达，不用红底红字 —— 这一屏
     * 出现的多数是「电脑没开 / Relay 没跑」这类可处置状态，红色读起来像崩溃。
     *
     * @param addr  可为 null（不画地址行）
     * @param steps 可为 null（不画步骤块）
     */
    static LinearLayout errCard(Context c, String title, String addr, String[] steps) {
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(dashedCardDrawable(c, ink2(c)));
        box.setPadding(dp(c, 12), dp(c, 12), dp(c, 12), dp(c, 12));

        LinearLayout head = new LinearLayout(c);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(dotView(c, 2), dotLp(c));
        TextView t = label(c, title, 12.5f, ink(c), true);
        head.addView(t, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        box.addView(head, matchWrap());

        if (addr != null && addr.length() > 0) {
            box.addView(mono(c, addr, 10.5f, label(c), false), matchWrapTop(c, 6));
        }
        if (steps != null && steps.length > 0) {
            LinearLayout list = new LinearLayout(c);
            list.setOrientation(LinearLayout.VERTICAL);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(card(c));
            bg.setCornerRadius(dp(c, R_CONTROL));
            bg.setStroke(Math.max(1, dp(c, 1)), line(c));
            list.setBackground(bg);
            list.setPadding(dp(c, 10), dp(c, 2), dp(c, 10), dp(c, 2));
            for (int i = 0; i < steps.length; i++) {
                if (i > 0) list.addView(divider(c));
                LinearLayout row = new LinearLayout(c);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setPadding(0, dp(c, 7), 0, dp(c, 7));
                TextView idx = mono(c, String.valueOf(i + 1), 11, ink(c), true);
                row.addView(idx, new LinearLayout.LayoutParams(dp(c, 14),
                        LinearLayout.LayoutParams.WRAP_CONTENT));
                TextView txt = label(c, steps[i], 11, ink(c), false);
                txt.setLineSpacing(dp(c, 2), 1f);
                row.addView(txt, new LinearLayout.LayoutParams(
                        0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
                list.addView(row, matchWrap());
            }
            box.addView(list, matchWrapTop(c, 9));
        }
        return box;
    }

    /**
     * 端口映射示意（设计稿 .map）：左侧方框 ── 隧道 ──▶ 右侧方框。
     *
     * <p>两端文案由调用方给**真实端口值**，不在这里写死 3081/3080 ——
     * 那两个数字在 SSH 档里是可改的，写死会与实际生效的隧道对不上。
     */
    static LinearLayout mapRow(Context c, String from, String to) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(dark(c) ? D_SUBTLE2 : SUBTLE_2);
        bg.setCornerRadius(dp(c, R_CONTROL));
        row.setBackground(bg);
        row.setPadding(dp(c, 9), dp(c, 9), dp(c, 9), dp(c, 9));
        row.addView(boxed(c, from));
        TextView arrow = mono(c, "── 隧道 ──▶", 10, ink(c), true);
        arrow.setPadding(dp(c, 8), 0, dp(c, 8), 0);
        row.addView(arrow);
        row.addView(boxed(c, to));
        return row;
    }

    /** 映射示意图里的小方框（等宽、加粗、白底细边）。 */
    private static TextView boxed(Context c, String text) {
        TextView t = mono(c, text, 10, ink(c), true);
        GradientDrawable d = new GradientDrawable();
        d.setColor(card(c));
        d.setCornerRadius(dp(c, R_CONTROL));
        d.setStroke(Math.max(1, dp(c, 1)), line(c));
        t.setBackground(d);
        t.setPadding(dp(c, 9), dp(c, 5), dp(c, 9), dp(c, 5));
        return t;
    }

    /**
     * 分段控件（设计稿 .seg）：浅灰槽 + 反白选中块。
     *
     * <p>**只画选择态，不做假切换**：点击一律回调，由调用方决定是跳页还是重绘 ——
     * 设计稿里两段分别对应两个真实页面（04 配对 / 05 SSH）。
     */
    static LinearLayout segmented(Context c, String[] names, int selected,
                                  final OnIndexClick onClick) {
        LinearLayout seg = new LinearLayout(c);
        seg.setOrientation(LinearLayout.HORIZONTAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(subtle(c));
        bg.setCornerRadius(dp(c, R_CONTROL));
        bg.setStroke(Math.max(1, dp(c, 1)), line(c));
        seg.setBackground(bg);
        seg.setPadding(dp(c, 2), dp(c, 2), dp(c, 2), dp(c, 2));
        for (int i = 0; i < names.length; i++) {
            final int idx = i;
            boolean on = i == selected;
            // 选中块是反白块（底色 = ink）：文字用 ink 常量本身 —— 深色下它是近黑、
            // 浅色下是白，恰好都是 ink(c) 的反色。浅色下用 0xFFFFFFFF、深色下用 INK 常量。
            TextView tab = label(c, names[i], 11.5f,
                    on ? (dark(c) ? INK : 0xFFFFFFFF) : label(c), on);
            tab.setGravity(Gravity.CENTER);
            tab.setPadding(0, dp(c, 7), 0, dp(c, 7));
            if (on) {
                tab.setBackground(pillDrawable(c, ink(c)));
            }
            tab.setClickable(true);
            tab.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    if (onClick != null && idx != -1) onClick.onIndex(idx);
                }
            });
            pressable(tab);
            seg.addView(tab, new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        }
        return seg;
    }

    /**
     * 「固定头部 + 可滚动内容」的页面骨架（r145）。
     *
     * <p>04 配对页与 05 SSH 页共用：应用栏与分段控件**不进** {@link ScrollView}，
     * 切换分段时顶部这一行像素级不动，也不会随表单滚动滚出屏幕。两页以前各画一份
     * 头部（连分段控件的上边距都一个 14dp、一个 12dp），跳 Activity 时必然错位。
     *
     * @param page 交给 {@code Activity.setContentView} 的外层容器；本方法设置方向、
     *             底色与 MATCH_PARENT 参数
     * @return 内容容器，调用方照旧往里 {@code addView}
     */
    static LinearLayout fixedSegmentedPage(Context c, LinearLayout page, String title,
                                           View.OnClickListener back,
                                           int selected, OnIndexClick onTab) {
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(pageBg(c));
        page.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        int pad = dp(c, 16);

        LinearLayout head = new LinearLayout(c);
        head.setOrientation(LinearLayout.VERTICAL);
        head.setPadding(pad, dp(c, 10), pad, 0);
        head.addView(appBar(c, title, "‹", back, null, null), matchWrap());
        head.addView(segmented(c, new String[] { "配对码", "SSH" }, selected, onTab),
                matchWrapTop(c, 12));
        page.addView(head, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        ScrollView scroll = new ScrollView(c);
        scroll.setBackgroundColor(pageBg(c));
        LinearLayout body = new LinearLayout(c);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(pad, 0, pad, dp(c, 18));
        scroll.addView(body, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        page.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        return body;
    }

    /** 键值行（设计稿 .row + .rt）：左侧 12.5sp 名字，右侧任意视图。 */
    static LinearLayout kvRow(Context c, String name, View value) {
        LinearLayout row = rowBox(c);
        row.addView(label(c, name, 12.5f, ink(c), true), new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(value);
        return row;
    }

    /**
     * 弹性空白：设计稿用 {@code .spacer} 把底部按钮压到屏底。
     *
     * <p>本项目所有页都在 ScrollView 里（内容可能长于屏高），weight 无处生效，
     * 所以这里退化为一段固定 8dp 空白 —— 视觉上等价于「按钮组与内容留一口气」。
     */
    static View spacer(Context c) {
        View v = new View(c);
        v.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(c, 8)));
        return v;
    }
}
