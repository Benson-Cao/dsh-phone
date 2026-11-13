package com.dsh.harness;

import android.content.Context;

import java.util.Collections;
import java.util.List;

/**
 * r152（官方对齐）：运行底座选择器。
 *
 * <p>PRoot 优先（免 Root、免 Termux、体积更小），不可用时回退 Termux 派。
 * 全局唯一实例，由第一次 {@link #get(Context)} 写入一次，之后只读。
 * 若后续要支持「本机 / 远程」双轨切换，把选择逻辑上移到 DshEngineService 即可。
 */
public final class DshRuntimeSelector {

    /** 懒初始化；首次 get 时由 detect 写入真实实例。 */
    private static volatile RuntimeProvider sInstance = null;

    private DshRuntimeSelector() { }

    /** 取当前选中的底座（PRoot 可用则 PRoot，否则 Termux）。 */
    public static RuntimeProvider get(Context ctx) {
        if (sInstance == null) {
            synchronized (DshRuntimeSelector.class) {
                if (sInstance == null) sInstance = detect(ctx);
            }
        }
        return sInstance;
    }

    /** 选型：PRoot 二进制就位则 PRoot，否则 Termux 兜底。 */
    public static RuntimeProvider detect(Context ctx) {
        DshProotRuntime proot = new DshProotRuntime(ctx);
        if (proot.isAvailable()) return proot;
        return new DshTermuxRuntime(ctx);
    }

    /** 便捷封装：用当前选中底座解压（出错抛异常，由调用方兜底）。 */
    public static void bootstrap(Context ctx, RuntimeProvider.ProgressCallback cb) throws Exception {
        get(ctx).bootstrap(ctx, cb);
    }

    /** 便捷封装：用当前选中底座启动引擎进程。 */
    public static Process startEngine(Context ctx, List<String> args) throws Exception {
        return get(ctx).startEngine(ctx, args == null ? Collections.emptyList() : args);
    }
}
