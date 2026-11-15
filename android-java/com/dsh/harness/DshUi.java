package com.dsh.harness;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
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
 * <p>本类只产出颜色值与 drawable，不含业务判断，可被桌面 javac 整树编译校验。
 */
final class DshUi {

    private DshUi() {}

    // ===== 品牌与语义色（设计稿 §2）=====
    static final int BRAND     = 0xFF4B5CF6;
    static final int BRAND_2   = 0xFF7C5CF2;
    static final int MINT      = 0xFF16B394;
    static final int AMBER     = 0xFFE09616;
    static final int DANGER    = 0xFFE1565B;

    static final int INK       = 0xFF151A27;
    static final int INK_2     = 0xFF69728B;
    static final int INK_3     = 0xFF9BA4B8;
    static final int BG        = 0xFFF3F5FB;
    static final int CARD      = 0xFFFFFFFF;
    static final int LINE      = 0xFFE9ECF4;
    static final int INPUT_BG  = 0xFFF7F8FD;

    private static final int D_BG      = 0xFF141821;
    private static final int D_CARD    = 0xFF1D2430;
    private static final int D_LINE    = 0xFF2C3542;
    private static final int D_INPUT  = 0xFF232C38;
    private static final int D_INK    = 0xFFE8ECF5;
    private static final int D_INK_2  = 0xFFA6B0C4;

    // ===== 随主题取色 =====

    static boolean dark(Context c) { return DshPalette.isDark(c); }

    static int pageBg(Context c) { return dark(c) ? D_BG : BG; }
    static int card(Context c) { return dark(c) ? D_CARD : CARD; }
    static int line(Context c) { return dark(c) ? D_LINE : LINE; }
    static int inputBg(Context c) { return dark(c) ? D_INPUT : INPUT_BG; }
    static int ink(Context c) { return dark(c) ? D_INK : INK; }
    static int ink2(Context c) { return dark(c) ? D_INK_2 : INK_2; }
    static int ink3(Context c) { return dark(c) ? D_INK_2 : INK_3; }

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
        GradientDrawable d = new GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                new int[] { BRAND, BRAND_2 });
        d.setCornerRadius(dp(c, 14));
        return d;
    }

    /** 次按钮：卡片底 + 边框 + 14dp 圆角。 */
    static GradientDrawable ghostDrawable(Context c) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(card(c));
        d.setCornerRadius(dp(c, 14));
        d.setStroke(Math.max(1, dp(c, 1)), line(c));
        return d;
    }

    /** 分段控件的外槽 / 选中项 / 端口映射 pill。 */
    static GradientDrawable pillDrawable(Context c, int fill) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(dp(c, 11));
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
                ViewGroupCompatMatch(), LinearLayout.LayoutParams.WRAP_CONTENT);
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

    /** 独立的 MATCH_PARENT 常量，避免各处直接写 ViewGroup.LayoutParams。 */
    private static int ViewGroupCompatMatch() {
        return LinearLayout.LayoutParams.MATCH_PARENT;
    }
}
