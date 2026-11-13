package com.dsh.harness;

import android.content.Context;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;

/**
 * UiPatches —— 对「必须改、但源码不归我们」的前端插件做**幂等文本补丁**。
 *
 * <h3>为什么要有这个类</h3>
 * 用户反馈「侧边栏点『更多』时抽屉先消失、菜单浮在裸首页上」。根因在
 * {@code dsh-web-mobile}（市场装的第三方插件）：它的 {@code TAP_CLOSE_NAV_SELECTOR}
 * 把 {@code [class*="panelRow"]} 一律当成「导航行」，点击就收抽屉；而宿主 dsh 的
 * 「更多」在 DOM 上恰好也是 {@code panelRow}，只是它带 {@code aria-haspopup="menu"}、
 * 点开的是弹层而不是页面 ⇒ 抽屉收掉、菜单无处可依。
 *
 * 插件是我们不能改源码的（市场包），但它的 {@code lib/client.js} 就落在 app 私有目录里，
 * 而且是一份**未压缩、可读**的构建产物。所以这里做两处最小文本替换：
 * <ol>
 *   <li>{@code isDrawerNavTarget} 末尾：带 {@code aria-haspopup} 的元素不算导航行
 *       → 点「更多」不再收抽屉；</li>
 *   <li>{@code onDrawerClick} 末尾：点菜单项（portal 在抽屉外）时收抽屉
 *       → 从「更多」里真跳面板时抽屉不会盖住面板。</li>
 * </ol>
 *
 * <h3>为什么每次启动跑，而不是只在解包时跑</h3>
 * 放在 {@link DshBootstrap} 的「每次启动轻量修护」段（跟 ICU / koffi 修护同一位置）：
 * 插件可以用市场更新覆盖掉这个文件，覆盖后下一次启动就自动补回来，用户无感。
 * 补丁本身是幂等的（文件里已有标记就直接跳过），开销只是一次 861KB 读 + 字符串查找。
 *
 * <h3>安全边界</h3>
 * <ul>
 *   <li>只替换**唯一命中**的锚点；命中 0 次或多次 ⇒ 一律不改、只记警告（插件换代后
 *       锚点失配是正常现象，绝不能瞎改）。</li>
 *   <li>写盘走「临时文件 + rename」，写一半断电不会留下半个 client.js。</li>
 *   <li>任何异常都吞掉：插件是首屏渲染的关键路径，补丁失败只能退化，不能拖垮启动。</li>
 * </ul>
 */
public final class UiPatches {

    private static final String TAG = "DshUiPatch";

    /** 幂等标记：文件里出现它 = 本补丁已生效。 */
    private static final String MARK = "dsh-phone:popup-row-keeps-drawer";

    /** dsh-web-mobile 的构建产物路径（相对 app 私有目录）。 */
    private static final String MOBILE_CLIENT =
            "home/.dsh/profiles/web/node_modules/dsh-web-mobile/lib/client.js";

    /**
     * 锚点 A：{@code isDrawerNavTarget} 的收尾 return（全文件唯一）。
     * 在它前面插一行「带 aria-haspopup 就不算导航目标」。
     */
    private static final String ANCHOR_A =
            "            return target.closest(exports.TAP_CLOSE_NAV_SELECTOR) !== null;";

    private static final String PATCH_A =
            "            // " + MARK + " —— 带 aria-haspopup 的行（侧栏「更多」）只开弹层、不做导航：\n"
            + "            // 在这里收抽屉会让菜单浮在裸首页上（真机实拍）。\n"
            + "            if (target.closest('[aria-haspopup]') !== null)\n"
            + "                return false;\n"
            + ANCHOR_A;

    /**
     * 锚点 B：{@code onDrawerClick} 末尾「触摸行已处理过就返回」之后的三行（全文件唯一）。
     */
    private static final String ANCHOR_B =
            "            if (performance.now() - lastTouchNavAt < 500)\n"
            + "                return;\n"
            + "            if (shouldCloseOnTapInsideDrawer(target))\n"
            + "                toggleSidebar();";

    private static final String PATCH_B =
            "            if (performance.now() - lastTouchNavAt < 500)\n"
            + "                return;\n"
            + "            // " + MARK + " —— 菜单项（portal 渲染在抽屉外）选中即导航，必须收抽屉，\n"
            + "            // 否则抽屉会盖住刚从菜单里打开的面板。\n"
            + "            if (target instanceof Element && target.closest('[role=\"menu\"]') !== null) {\n"
            + "                if (drawerOpen())\n"
            + "                    toggleSidebar();\n"
            + "                return;\n"
            + "            }\n"
            + "            if (shouldCloseOnTapInsideDrawer(target))\n"
            + "                toggleSidebar();";

    private UiPatches() {
    }

    /** 入口：每次启动都可以安全调用（幂等、全异常兜底）。 */
    public static void apply(Context ctx) {
        try {
            File f = new File(ctx.getFilesDir(), MOBILE_CLIENT);
            if (!f.isFile()) {
                // 没装这个插件（或换了目录结构）→ 什么都不做，不要打警告刷日志。
                Log.i(TAG, "dsh-web-mobile 未安装，跳过抽屉补丁");
                return;
            }
            String src = readAll(f);
            if (src == null) {
                return;
            }
            if (src.indexOf(MARK) >= 0) {
                return;                       // 已打过，安静返回
            }

            String out = replaceOnce(src, ANCHOR_A, PATCH_A, "A/aria-haspopup");
            if (out == null) {
                return;
            }
            String out2 = replaceOnce(out, ANCHOR_B, PATCH_B, "B/menu-close");
            if (out2 == null) {
                return;
            }
            writeAtomic(f, out2);
            Log.i(TAG, "dsh-web-mobile 抽屉补丁已应用（" + f.length() + " B）");
        } catch (Throwable t) {
            // 补丁是加固项：失败只记录，绝不让启动失败。
            Log.w(TAG, "抽屉补丁失败：" + t);
        }
    }

    /** 唯一命中才替换；否则返回 null（并记警告）。 */
    private static String replaceOnce(String src, String anchor, String replacement, String what) {
        int first = src.indexOf(anchor);
        if (first < 0) {
            Log.w(TAG, "锚点 " + what + " 未命中（插件版本可能变了），本次不打补丁");
            return null;
        }
        if (src.indexOf(anchor, first + anchor.length()) >= 0) {
            Log.w(TAG, "锚点 " + what + " 命中多次，拒绝修改（避免误伤）");
            return null;
        }
        return src.substring(0, first) + replacement + src.substring(first + anchor.length());
    }

    private static String readAll(File f) {
        Reader r = null;
        try {
            r = new InputStreamReader(new FileInputStream(f), "UTF-8");
            char[] buf = new char[8192];
            StringBuilder sb = new StringBuilder((int) Math.min(f.length() + 16, 1 << 21));
            int n;
            while ((n = r.read(buf)) > 0) {
                sb.append(buf, 0, n);
            }
            return sb.toString();
        } catch (Throwable t) {
            Log.w(TAG, "读取 client.js 失败：" + t);
            return null;
        } finally {
            if (r != null) {
                try {
                    r.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /** 先写 .tmp 再 rename：避免半个文件被前端加载。rename 失败则退化为原地覆盖。 */
    private static void writeAtomic(File target, String text) {
        File tmp = new File(target.getParentFile(), target.getName() + ".dshpatch.tmp");
        Writer w = null;
        try {
            w = new OutputStreamWriter(new FileOutputStream(tmp), "UTF-8");
            w.write(text);
            w.flush();
            ((OutputStreamWriter) w).close();
            w = null;
            target.setReadable(true, true);
            if (!tmp.renameTo(target)) {
                Log.w(TAG, "rename 失败，改为原地覆盖");
                copy(tmp, target);
                if (!tmp.delete()) {
                    Log.w(TAG, "临时文件删除失败：" + tmp);
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "写 client.js 失败：" + t);
            if (tmp.exists() && !tmp.delete()) {
                Log.w(TAG, "临时文件清理失败：" + tmp);
            }
        } finally {
            if (w != null) {
                try {
                    w.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static void copy(File from, File to) throws Exception {
        FileInputStream in = new FileInputStream(from);
        ByteArrayOutputStream bos = new ByteArrayOutputStream((int) from.length() + 16);
        try {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
        } finally {
            in.close();
        }
        FileOutputStream out = new FileOutputStream(to);
        try {
            out.write(bos.toByteArray());
        } finally {
            out.close();
        }
    }
}
