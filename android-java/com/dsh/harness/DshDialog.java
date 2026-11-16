package com.dsh.harness;

import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 统一的圆角对话框（r148）—— 替掉系统 {@code AlertDialog} 的方角灰卡 + 红字按钮。
 *
 * <h3>为什么不用 styles.xml 主题</h3>
 * 本工程真正被编译的是 {@code /d/work/termux-app}，仓库侧 {@code res/values} 并不在
 * 出包脚本的同步清单里（01 只拷 java 与两份 res/xml）。新增一个 theme + drawable 会
 * 在出包时静默丢失，真机上仍是系统方角卡片。这里全部用纯 Java 画：卡片底用
 * {@link DshUi#cardDrawable}（12dp 圆角 + 发丝线），窗口底设透明让圆角露出来，
 * 按钮用项目自己的 {@link DshUi#button}，深色/浅色两端都跟页面同源。
 *
 * <h3>视觉</h3>
 * 顶部一条 24×2dp 的 ink 短横条作为视觉锚点 → 16sp 粗体标题 → 12.5sp 正文（行距 3dp）
 * → 可选输入视图 → 发丝分隔线 → 等分的两枚圆角按钮（次要在左、主操作在右，近黑实底
 * 白字，不再是系统那对红字）。单按钮时占满整行。
 *
 * <p>按钮点击先 {@code dismiss()} 再执行回调；取消（返回键 / 点卡片外）走 onCancel，
 * WebView 的 JsResult 靠它回执，不会被挂死。
 */
public final class DshDialog {

    /** 回调：比 DialogInterface.OnClickListener 更轻，调用方不必关心对话框对象。 */
    public interface Action {
        void run();
    }

    private DshDialog() { }

    public static Builder with(Context c) {
        return new Builder(c);
    }

    public static final class Builder {
        private final Context c;
        private String title;
        private String message;
        private String negative;
        private String positive;
        private Action onNegative;
        private Action onPositive;
        private Action onCancel;
        private View customView;
        private boolean cancelable = true;

        private Builder(Context c) {
            this.c = c;
        }

        public Builder title(String s) { title = s; return this; }

        public Builder message(CharSequence s) {
            message = s == null ? null : s.toString();
            return this;
        }

        /** 可选：插在正文与分隔线之间的自定义视图（JS prompt 的输入框走这里）。 */
        public Builder view(View v) { customView = v; return this; }

        public Builder negative(String text, Action action) {
            negative = text;
            onNegative = action;
            return this;
        }

        public Builder positive(String text, Action action) {
            positive = text;
            onPositive = action;
            return this;
        }

        public Builder onCancel(Action action) { onCancel = action; return this; }

        public Builder cancelable(boolean b) { cancelable = b; return this; }

        public Dialog show() {
            final Dialog dialog = new Dialog(c);
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

            LinearLayout card = new LinearLayout(c);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setBackground(DshUi.cardDrawable(c));
            int pad = DshUi.dp(c, 20);
            card.setPadding(pad, DshUi.dp(c, 20), pad, DshUi.dp(c, 16));

            // 视觉锚点：一条短 ink 横条。单色稿里不用彩色 accent，粗细与留白做层次。
            View accent = new View(c);
            accent.setBackgroundColor(DshUi.ink(c));
            LinearLayout.LayoutParams accentLp = new LinearLayout.LayoutParams(
                    DshUi.dp(c, 24), Math.max(2, DshUi.dp(c, 2)));
            accentLp.bottomMargin = DshUi.dp(c, 12);
            card.addView(accent, accentLp);

            if (title != null && title.length() > 0) {
                card.addView(DshUi.label(c, title, 16, DshUi.ink(c), true), DshUi.matchWrap());
            }
            if (message != null && message.length() > 0) {
                TextView body = DshUi.label(c, message, 12.5f, DshUi.label(c), false);
                body.setLineSpacing(DshUi.dp(c, 3), 1f);
                card.addView(body, DshUi.matchWrapTop(c, 12));
            }
            if (customView != null) {
                customView.setBackground(DshUi.inputDrawable(c));
                int vp = DshUi.dp(c, 10);
                customView.setPadding(vp, vp, vp, vp);
                // JS prompt 传进来的 EditText 是系统默认样式（下划线 + 主题字色）。
                // 卡片已经是项目单色了，输入框不能还留着另一套颜色。
                if (customView instanceof TextView) {
                    TextView tv = (TextView) customView;
                    tv.setTextColor(DshUi.ink(c));
                    tv.setTextSize(12.5f);          // 无单位重载 = sp
                    tv.setHintTextColor(DshUi.mute(c));
                    if (tv instanceof EditText) ((EditText) tv).setSingleLine(true);
                }
                card.addView(customView, DshUi.matchWrapTop(c, 14));
            }

            // ⚠️ 不能直接用 matchWrapTop()：那套参数是 WRAP_CONTENT 高，会把 divider
            // 自己设的 1dp 覆盖掉，纯 View 在 WRAP_CONTENT 下量到 0 高 → 分隔线消失。
            // 卡片里的按钮要的就是一条看得见的发丝线，所以显式给回固定高度。
            View rule = DshUi.divider(c);
            LinearLayout.LayoutParams ruleLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, Math.max(1, DshUi.dp(c, 1)));
            ruleLp.topMargin = DshUi.dp(c, 18);
            card.addView(rule, ruleLp);

            final boolean two = negative != null && positive != null;
            LinearLayout row = new LinearLayout(c);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            if (negative != null) {
                TextView b = DshUi.button(c, negative, DshUi.BTN_SECONDARY);
                b.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        dialog.dismiss();
                        if (onNegative != null) onNegative.run();
                    }
                });
                row.addView(b, two ? DshUi.half(c, true) : DshUi.matchWrap());
            }
            if (positive != null) {
                TextView b = DshUi.button(c, positive, DshUi.BTN_PRIMARY);
                b.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        dialog.dismiss();
                        if (onPositive != null) onPositive.run();
                    }
                });
                row.addView(b, two ? DshUi.half(c, false) : DshUi.matchWrap());
            }
            card.addView(row, DshUi.matchWrapTop(c, 12));

            dialog.setContentView(card);
            Window w = dialog.getWindow();
            if (w != null) {
                // 不设透明底，系统会给窗口套一层不透明方角背景，圆角卡片被盖住看不见。
                w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
                w.setDimAmount(0.62f);
            }
            dialog.setCancelable(cancelable);
            dialog.setOnCancelListener(new DialogInterface.OnCancelListener() {
                @Override public void onCancel(DialogInterface d) {
                    if (onCancel != null) onCancel.run();
                }
            });
            dialog.show();
            if (w != null) {
                int margin = DshUi.dp(c, 20);
                int width = Math.min(
                        c.getResources().getDisplayMetrics().widthPixels - margin * 2,
                        DshUi.dp(c, 360));
                w.setLayout(width, WindowManager.LayoutParams.WRAP_CONTENT);
            }
            return dialog;
        }
    }
}
