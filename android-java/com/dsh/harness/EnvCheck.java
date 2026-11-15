package com.dsh.harness;

import android.content.Context;
import android.util.Log;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

/**
 * 环境自检（设计稿 02「准备工作 · 先检查一下环境」的数据层）。
 *
 * <h3>为什么单独一层，不写在 Activity 里</h3>
 * <ol>
 *   <li>「Termux + Node」这一行要真去问 node（{@code node -v}），是**会起进程**的，
 *       必须能在后台线程跑；Activity 只该管渲染。</li>
 *   <li>四行的语义是统一的：标题 + 一句人话 + 一个状态。状态只有四档，
 *       而「形状编码」在 {@link DshUi}（实心点 / 空心环 / 虚线）。集中在这里
 *       才看得出来四行是不是同一套口径。</li>
 *   <li>可被 {@code scripts/test-java.sh} 直接断言，不必起 Activity。</li>
 * </ol>
 *
 * <h3>不造假</h3>
 * 拿不到的数据如实说拿不到，绝不写死「已就绪」：
 * <ul>
 *   <li>Node 版本问不到 → 只说「已安装 · 版本未知」，不编一个号；</li>
 *   <li>「双端 commit 一致」在**还没连上电脑**时是无从判断的 —— 本页出现在准备阶段，
 *       所以只报本地基线，把比对留给连接后的诊断页，而不是先写一句「一致」。</li>
 * </ul>
 */
final class EnvCheck {

    private static final String TAG = "EnvCheck";

    /** 就绪：实心点 + 浅灰底实线框。 */
    static final int STATE_READY = 0;
    /** 待放行：空心环 + 虚线框（用户要去做一件事）。 */
    static final int STATE_WAIT = 1;
    /** 通过：与 READY 同一视觉，语义是「检查过、没问题」。 */
    static final int STATE_PASS = 2;
    /** 未安装 / 不满足：灰虚线。 */
    static final int STATE_MISSING = 3;

    /** 一行自检结果。 */
    static final class Item {
        /** 34dp 方块头像里的单字（与 06 主机切换器的 本/隧/中 同一套做法）。 */
        final String glyph;
        final String title;
        /** 第二行说明：一句人话，不是错误码。 */
        final String meta;
        final int state;

        Item(String glyph, String title, String meta, int state) {
            this.glyph = glyph;
            this.title = title;
            this.meta = meta;
            this.state = state;
        }

        /** 标签文字：四档各自的词，读屏与色弱都拿得到语义。 */
        String tagText() {
            switch (state) {
                case STATE_READY:   return "就绪";
                case STATE_WAIT:    return "待放行";
                case STATE_PASS:    return "通过";
                default:            return "未安装";
            }
        }
    }

    /** 一个档案（执行位置）。端口是**本机监听**的口径，不是对端地址。 */
    static final class Place {
        final String glyph;
        final String name;
        final String meta;
        final String port;

        Place(String glyph, String name, String meta, String port) {
            this.glyph = glyph;
            this.name = name;
            this.meta = meta;
            this.port = port;
        }
    }

    private EnvCheck() { }

    /**
     * 「执行位置 · 三个档案」。**静态常量**，不做运行时探测。
     *
     * <p>这三行讲的是**产品口径**（三种执行位置各自的监听端口），不是当前状态；
     * 当前选中哪个档案由诊断页的 activePath 表达。把静态口径画成动态状态
     * 会让人误以为「8899 此刻在监听」。
     */
    static List<Place> places() {
        List<Place> out = new ArrayList<Place>(3);
        out.add(new Place("本", "on-device",
                "手机 Termux · 独立真源", String.valueOf(EngineEndpoints.PORT_ON_DEVICE_VIEW)));
        out.add(new Place("隧", "ssh",
                "隧道回连电脑 · TOFU 主机指纹", String.valueOf(EngineEndpoints.PORT_SSH_VIEW)));
        out.add(new Place("中", "relay",
                "自建 Relay 反代 · HMAC 挑战", String.valueOf(EngineEndpoints.PORT_RELAY_VIEW)));
        return out;
    }

    /**
     * 跑四项检查。**必须在后台线程调用**（第一项会起子进程）。
     *
     * <p>顺序与设计稿一致：先本机运行环境，再引擎本体，再系统层面的保活，
     * 最后是「会不会打架」的适配检查 —— 从「能不能跑」到「跑起来会不会被打断」。
     */
    static List<Item> run(Context ctx) {
        List<Item> out = new ArrayList<Item>(4);
        boolean installed = DshBootstrap.isInstalled(ctx);
        out.add(termuxItem(ctx, installed));
        out.add(dshItem(installed));
        out.add(powerItem(ctx));
        out.add(skinItem(ctx));
        return out;
    }

    // ---- 1) Termux + Node ----

    private static Item termuxItem(Context ctx, boolean installed) {
        if (!installed) {
            return new Item("T", "Termux + Node",
                    "未解包 · 先启动一次完成环境准备", STATE_MISSING);
        }
        String v = nodeVersion(ctx);
        String meta = v == null
                ? "已安装 · 版本未知（node -v 无输出）"
                : "已安装 · Node " + v;
        return new Item("T", "Termux + Node", meta, STATE_READY);
    }

    /**
     * 真去问 node 自己的版本。
     *
     * <p>为什么不从文件名或 bundle 版本号推断：bundle 的 rev 只标 dsh 版本，
     * node 是随 bundle 打的另一条线；写死一个号会在换 bundle 后立刻变成假话。
     *
     * <p>为什么必须给全套 Termux 环境：Android 上的 node 靠 LD_LIBRARY_PATH 找 .so，
     * 缺了会**静默退出**（{@link DshMarketInstaller} 里踩过同一个坑），
     * 所以复用 {@link ShellRunner#buildEnv} 与 dsh-web.sh 保持一致。
     */
    private static String nodeVersion(Context ctx) {
        File node = new File(DshBootstrap.prefix(ctx), "bin/node");
        if (!node.isFile()) return null;
        Process p = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(node.getAbsolutePath(), "-v");
            pb.directory(DshBootstrap.home(ctx));
            pb.redirectErrorStream(true);
            pb.environment().clear();
            for (String kv : ShellRunner.buildEnv(ctx)) {
                int i = kv.indexOf('=');
                if (i > 0) pb.environment().put(kv.substring(0, i), kv.substring(i + 1));
            }
            p = pb.start();
            guardKill(p, 4000);
            String s = readAll(p.getInputStream(), 64).trim();
            if (s.startsWith("v")) s = s.substring(1);
            // node 正常输出形如 24.9.0；不是这个形状就当没拿到，别把错误页当版本
            return s.matches("\\d+\\.\\d+\\.\\d+.*") ? s : null;
        } catch (Throwable t) {
            Log.w(TAG, "读 node 版本失败：" + t);
            return null;
        } finally {
            if (p != null) {
                try { p.destroy(); } catch (Throwable ignored) { }
            }
        }
    }

    // ---- 2) 原版 DSH ----

    private static Item dshItem(boolean installed) {
        String v = DshBootstrap.engineVersion();
        if (!installed || v == null) {
            return new Item("D", "原版 DSH", "未解包 · 无本地引擎基线", STATE_MISSING);
        }
        // 只说本地基线：双端一致要连上电脑才谈得上（本页在连接之前）
        return new Item("D", "原版 DSH",
                "v" + v + " · 内置基线，连上电脑后比对 commit", STATE_READY);
    }

    // ---- 3) 后台保活 ----

    private static Item powerItem(Context ctx) {
        boolean exempt = DshPowerGuard.isExempt(ctx);
        return new Item("电", "后台保活",
                exempt ? "省电白名单已放行，长任务不会被系统结束"
                       : "省电白名单未放行，长任务可能被系统结束",
                exempt ? STATE_READY : STATE_WAIT);
    }

    // ---- 4) 窄屏适配插件 ----

    /**
     * 检查有没有**第三方客户端插件**可能改动官方 dsh web 的窄屏布局。
     *
     * <p>口径：只看 profile 的**直接依赖**里、package.json 声明了 {@code dsh.client}
     * 且不是官方 {@code @deepseek-ai/*} 的那些 —— 只有客户端半边才会往页面里注入
     * 样式或 DOM，服务端插件改不动布局。不去递归整个 node_modules：
     * 传递依赖不改变「用户主动装了什么」，却会让扫描慢得没有意义。
     */
    private static Item skinItem(Context ctx) {
        List<String> third = thirdPartyClientPlugins(ctx);
        if (third.isEmpty()) {
            return new Item("屏", "窄屏适配插件检查",
                    "未安装第三方客户端插件 · 与官方适配无冲突", STATE_PASS);
        }
        String names = join(third, 3);
        return new Item("屏", "窄屏适配插件检查",
                names + " · 布局异常时先停用它们", STATE_WAIT);
    }

    /** profile 直接依赖里声明了 dsh.client 的非官方包名。探不到就返回空表。 */
    static List<String> thirdPartyClientPlugins(Context ctx) {
        List<String> out = new ArrayList<String>();
        File profile = new File(DshBootstrap.home(ctx), ".dsh/profiles/web");
        String root = readText(new File(profile, "package.json"), 256 * 1024);
        if (root == null) return out;
        try {
            JSONObject obj = new JSONObject(root);
            JSONObject deps = obj.optJSONObject("dependencies");
            if (deps == null) return out;
            Iterator<String> it = deps.keys();
            while (it.hasNext()) {
                String name = it.next();
                if (name.startsWith("@deepseek-ai/")) continue;
                String pj = readText(
                        new File(profile, "node_modules/" + name + "/package.json"), 256 * 1024);
                if (pj == null) continue;
                try {
                    JSONObject p = new JSONObject(pj);
                    JSONObject dsh = p.optJSONObject("dsh");
                    if (dsh != null && dsh.optJSONObject("client") != null) out.add(name);
                } catch (Throwable ignored) { /* 单个包坏了不该拖垮整项检查 */ }
            }
        } catch (Throwable t) {
            Log.w(TAG, "读 profile 依赖失败：" + t);
        }
        Collections.sort(out);
        return out;
    }

    // ---- 小工具 ----

    /** 超时就杀：node 万一卡在启动，这一页不能跟着卡死。 */
    private static void guardKill(final Process p, final long ms) {
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                try { Thread.sleep(ms); } catch (InterruptedException e) { return; }
                try { p.destroy(); } catch (Throwable ignored) { }
            }
        }, "envcheck-node-guard");
        t.setDaemon(true);
        t.start();
    }

    private static String readAll(InputStream in, int cap) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try {
            byte[] buf = new byte[512];
            int n;
            while ((n = in.read(buf)) > 0 && bos.size() < cap) bos.write(buf, 0, n);
            return new String(bos.toByteArray(), "UTF-8");
        } catch (Throwable t) {
            return "";
        } finally {
            try { in.close(); } catch (Throwable ignored) { }
        }
    }

    private static String readText(File f, int cap) {
        if (!f.isFile()) return null;
        FileInputStream in = null;
        try {
            in = new FileInputStream(f);
            return readAll(in, cap);
        } catch (Throwable t) {
            return null;
        } finally {
            if (in != null) try { in.close(); } catch (Throwable ignored) { }
        }
    }

    private static String join(List<String> xs, int max) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < xs.size() && i < max; i++) {
            if (i > 0) sb.append(" · ");
            sb.append(xs.get(i));
        }
        if (xs.size() > max) sb.append(" 等 ").append(xs.size()).append(" 个");
        return sb.toString();
    }
}
