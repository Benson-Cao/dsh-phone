package com.dsh.harness;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * WB-Sync v2 设计稿（2026-11-15）的 Android 设计令牌层。
 *
 * <p>来源：{@code WB-Sync_UI完整版_设计稿 / v2/README.md} §2 设计系统 ——
 * 品牌 {@code #4B5CF6 → #7C5CF2}、本机真源薄荷 {@code #16B394}、
 * 三级文字 {@code #151A27 / #69728B / #9BA4B8}、状态色、卡片 18dp / 按钮 14dp /
 * 输入 11dp 圆角、无外链资源。
 *
 * <p><b>为什么另起一个类而不改 {@link DshPalette}</b>：DshPalette 管的是启动页与
 * 系统栏，里面有一整套真机踩坑换来的深浅色优先级逻辑（r79 / r105 / r108）。
 * 新稿是浅色高保真稿，直接改它会把已验证的旧页一起带崩。这里与它并存，
 * 只服务新原生页；深色系统下给出对应的深色变体，避免白卡片刺眼。
 *
 * <p>本类只产出颜色值、drawable 与「无业务判断」的复合组件，
 * 可被桌面 javac 整树编译校验。
 */
final class DshUi {

    private DshUi() {}

    // ===== 品牌与语义色（设计稿 §2）=====
    static final int BRAND     = 0xFF4B5CF6;
    static final int BRAND_2   = 0xFF7C5CF2;
    static final int MINT      = 0xFF16B394;
    static final int MINT_2    = 0xFF3CC9A7;
    static final int AMBER     = 0xFFE09616;
    static final int DANGER    = 0xFFE1565B;

    static final int INK       = 0xFF151A27;
    static final int INK_2     = 0xFF69728B;
    static final int INK_3     = 0xFF9BA4B8;
    static final int BG        = 0xFFF3F5FB;
    static final int CARD      = 0xFFFFFFFF;
    static final int LINE      = 0xFFE9ECF4;
    static final int INPUT_BG  = 0xFFF7F8FD;

    /** 状态标签底色（设计稿 .tag 系列）。 */
    static final int SOFT_MINT  = 0xFFE3F8F2;
    static final int SOFT_BLUE  = 0xFFEDF0FF;
    static final int SOFT_AMBER = 0xFFFFF3DF;
    static final int SOFT_RED   = 0xFFFDECEE;
    static final int SOFT_GRAY  = 0xFFF0F2F7;
    static final int ON_MINT    = 0xFF0E876F;
    static final int ON_AMBER   = 0xFFA96D0B;
    static final int ON_GRAY    = 0xFF8B94A7;

    private static final int D_BG      = 0xFF141821;
    private static final int D_CARD    = 0xFF1D2430;
    private static final int D_LINE    = 0xFF2C3542;
    private static final int D_INPUT   = 0xFF232C38;
    private static final int D_INK     = 0xFFE8ECF5;
    private static final int D_INK_2   = 0xFFA6B0C4;
    private static final int D_DIAG    = 0xFF212936;

    // ===== 按钮样式（{@link #button}）=====
    static final int BTN_PRIMARY   = 0;
    static final int BTN_SECONDARY = 1;
    static final int BTN_GHOST     = 2;
    static final int BTN_DANGER    = 3;

    // ===== 随主题取色 =====

    static boolean dark(Context c) { return DshPalette.isDark(c); }

    static int pageBg(Context c) { return dark(c) ? D_BG : BG; }
    static int card(Context c) { return dark(c) ? D_CARD : CARD; }
    static int line(Context c) { return dark(c) ? D_LINE : LINE; }
    static int inputBg(Context c) { return dark(c) ? D_INPUT : INPUT_BG; }
    static int ink(Context c) { return dark(c) ? D_INK : INK; }
    static int ink2(Context c) { return dark(c) ? D_INK_2 : INK_2; }
    static int ink3(Context c) { return dark(c) ? D_INK_2 : INK_3; }

    /** 诊断条底色：浅色稿是 #E9EDF7，深色下压到比卡片再深一档。 */
    static int diagBg(Context c) { return dark(c) ? D_DIAG : 0xFFE9EDF7; }

    /**
     * 标签底色。浅色稿给的是固定浅色；深色下若照搬会出现「白底标签上浅灰字」，
     * 所以按同色系压暗。
     */
    static int soft(Context c, int light, int darkVariant) {
        return dark(c) ? darkVariant : light;
    }

    /** dp → px，所有圆角/间距都走这里，避免不同页各写一套密度换算。 */
    static int dp(Context c, int v) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics());
    }

    // ===== drawable 工厂 =====

    /** 分组卡片：白底 + 1px 边框 + 18dp 圆角。 */
    static GradientDrawable cardDrawable(Context c) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(card(c));
        d.setCornerRadius(dp(c, 18));
        d.setStroke(Math.max(1, dp(c, 1)), line(c));
        return d;
    }

    /** 输入框：浅灰底 + 1px 边框 + 11dp 圆角。 */
    static GradientDrawable inputDrawable(Context c) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(inputBg(c));
        d.setCornerRadius(dp(c, 11));
        d.setStroke(Math.max(1, dp(c, 1)), line(c));
        return d;
    }

    /** 主按钮：品牌渐变 + 14dp 圆角（设计稿无纯色主按钮）。 */
    static GradientDrawable primaryDrawable(Context c) {
        return gradient(c, BRAND, BRAND_2, 14);
    }

    /** 次按钮：卡片底 + 边框 + 14dp 圆角。 */
    static GradientDrawable ghostDrawable(Context c) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(card(c));
        d.setCornerRadius(dp(c, 14));
        d.setStroke(Math.max(1, dp(c, 1)), line(c));
        return d;
    }

    /** 危险操作按钮：淡红底 + 红字（不描边，与设计稿 .btn.dan 一致）。 */
    static GradientDrawable dangerDrawable(Context c) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(dark(c) ? 0xFF3A2226 : SOFT_RED);
        d.setCornerRadius(dp(c, 14));
        return d;
    }

    /** 通用渐变块：头像 / 主按钮 / 进度圆点共用。 */
    static GradientDrawable gradient(Context c, int from, int to, int radiusDp) {
        GradientDrawable d = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR, new int[] { from, to });
        d.setCornerRadius(dp(c, radiusDp));
        return d;
    }

    /** 分段控件的外槽 / 选中项 / 端口映射 pill。 */
    static GradientDrawable pillDrawable(Context c, int fill) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(dp(c, 11));
        return d;
    }

    /** 状态标签：7dp 圆角（比 pill 更小，与设计稿 .tag 一致）。 */
    static GradientDrawable chipDrawable(Context c, int fill) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(dp(c, 7));
        return d;
    }

    /** 圆形底（应用栏的 34dp 圆钮不是正圆，是 12dp 圆角方块，见 {@link #roundButton}）。 */
    static GradientDrawable roundDrawable(Context c) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(card(c));
        d.setCornerRadius(dp(c, 12));
        d.setStroke(Math.max(1, dp(c, 1)), line(c));
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

    /** 等分的一行两列：weight 各 1，中间留 10dp。 */
    static LinearLayout.LayoutParams half(Context c, boolean left) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMargins(left ? 0 : dp(c, 5), 0, left ? dp(c, 5) : 0, 0);
        return lp;
    }

    // ===== 复合组件 =====
    //
    // 这些是设计稿里「每一屏都长一样」的骨架：应用栏、分组卡片、行、状态标签、
    // 诊断条、按钮。抽出来是为了让 06/07/11/12/13 之间不会各自漂移 ——
    // 尤其在圆角、字号、行距这些一眼就能看出差别的数值上。

    /**
     * 应用栏：左圆钮（返回）+ 标题 + 右侧可选圆钮。
     *
     * @param backGlyph 左侧圆钮里的字形；传 null 表示不画左侧圆钮
     * @param rightGlyph 右侧圆钮里的字形；传 null 表示不画
     */
    static LinearLayout appBar(Context c, String title,
                               String backGlyph, View.OnClickListener back,
                               String rightGlyph, View.OnClickListener right) {
        LinearLayout bar = new LinearLayout(c);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        if (backGlyph != null) {
            // ⚠️ 必须用「34dp 固定尺寸」的 LayoutParams 加进去：`addView(v, lp)` 会**覆盖**
            // 视图自己 setLayoutParams 设的尺寸。早年版这里传的是 WRAP_CONTENT 参数，
            // 于是 34dp 圆钮被压成一个字形宽 —— 真机截图上就是「‹ 没有底框」。
            TextView backBtn = roundButton(c, backGlyph, back);
            bar.addView(backBtn, fixedEnd(c, 34, 10));
        }
        bar.addView(label(c, title, 15, ink(c), true));
        View spacer = new View(c);
        bar.addView(spacer, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        if (rightGlyph != null) {
            bar.addView(roundButton(c, rightGlyph, right));
        }
        return bar;
    }

    /** 34dp 圆角方块按钮（设计稿 .rnd），用于返回/刷新这类单字形动作。 */
    static TextView roundButton(Context c, String glyph, View.OnClickListener l) {
        TextView t = new TextView(c);
        t.setText(glyph);
        t.setTextColor(ink(c));
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        t.setGravity(Gravity.CENTER);
        t.setBackground(roundDrawable(c));
        t.setClickable(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                dp(c, 34), dp(c, 34));
        t.setLayoutParams(lp);
        t.setOnClickListener(l);
        pressable(t);
        return t;
    }

    /** 分组小标题（设计稿 .sec）：11.5sp、muted、加粗。 */
    static TextView section(Context c, String text) {
        TextView t = label(c, text, 11.5f, ink3(c), true);
        t.setPadding(dp(c, 3), 0, dp(c, 3), 0);
        return t;
    }

    /** 分组卡片容器：纵向、圆角 18、白底描边；子视图自己带内边距。 */
    static LinearLayout cardBox(Context c) {
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(cardDrawable(c));
        return box;
    }

    /** 卡片内的一行：横向、垂直居中、四角 13/12dp 内边距。 */
    static LinearLayout rowBox(Context c) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(c, 13), dp(c, 12), dp(c, 13), dp(c, 12));
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

    /** 40dp 渐变头像块（设计稿 .av，13dp 圆角）：一个汉字或一个字形。 */
    static TextView avatar(Context c, String text, int from, int to) {
        TextView t = new TextView(c);
        t.setText(text);
        t.setTextColor(0xFFFFFFFF);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        t.setTypeface(t.getTypeface(), Typeface.BOLD);
        t.setGravity(Gravity.CENTER);
        t.setBackground(gradient(c, from, to, 13));
        t.setLayoutParams(new LinearLayout.LayoutParams(dp(c, 38), dp(c, 38)));
        return t;
    }

    /**
     * 状态标签：小圆点 + 文字（设计稿 .tag）。
     *
     * @param fill 底色；{@code 0} 表示不画底色
     * @param dotColor 圆点颜色；{@code 0} 表示不画圆点
     */
    static LinearLayout tag(Context c, String text, int fill, int fg, int dotColor) {
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        if (fill != 0) box.setBackground(chipDrawable(c, fill));
        box.setPadding(dp(c, 8), dp(c, 4), dp(c, 8), dp(c, 4));
        if (dotColor != 0) {
            View dot = new View(c);
            GradientDrawable d = new GradientDrawable();
            d.setShape(GradientDrawable.OVAL);
            d.setColor(dotColor);
            dot.setBackground(d);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(c, 7), dp(c, 7));
            lp.rightMargin = dp(c, 5);
            box.addView(dot, lp);
        }
        TextView t = label(c, text, 10, fg, true);
        box.addView(t);
        return box;
    }

    /** 诊断条（设计稿 .diag）：等宽字体、10sp、浅蓝底。 */
    static TextView diagBar(Context c, String text) {
        TextView t = label(c, text, 10, ink2(c), false);
        t.setTypeface(Typeface.MONOSPACE);
        t.setBackground(pillDrawable(c, diagBg(c)));
        t.setPadding(dp(c, 12), dp(c, 10), dp(c, 12), dp(c, 10));
        t.setLineSpacing(dp(c, 3), 1f);
        return t;
    }

    /**
     * 按钮（设计稿 .btn 系列）。
     *
     * <p>用 TextView 而不是 Button：系统 Button 自带 CAPS、最小宽度与灰色底，
     * 逐项 reset 比自绘更啰嗦，而且换个主题色就会露出默认描边。
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
                t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
                t.setTypeface(t.getTypeface(), Typeface.BOLD);
                h = 46;
                break;
            case BTN_DANGER:
                t.setBackground(dangerDrawable(c));
                t.setTextColor(DANGER);
                t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
                t.setTypeface(t.getTypeface(), Typeface.BOLD);
                h = 38;
                break;
            case BTN_GHOST:
                t.setBackgroundColor(0x00000000);
                t.setTextColor(ink2(c));
                t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
                h = 34;
                break;
            case BTN_SECONDARY:
            default:
                t.setBackground(ghostDrawable(c));
                t.setTextColor(ink(c));
                t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
                t.setTypeface(t.getTypeface(), Typeface.BOLD);
                h = 46;
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
                        view.setAlpha(0.72f);
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
}
