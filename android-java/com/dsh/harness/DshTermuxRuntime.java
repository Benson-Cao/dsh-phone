package com.dsh.harness;

import android.content.Context;

import java.util.List;

/**
 * r152（官方对齐）：Termux 派运行底座适配器。
 *
 * <p>复用现有 {@link DshBootstrap}（解包 + 补丁 + 启动脚本）与 {@link DshProcessManager}
 * 的进程守护（保活 / 端口探测 / DSH_STATE 扫描），只是把它们包进统一的
 * {@link RuntimeProvider} 接口，让选择器能在「PRoot 优先 / Termux 兜底」之间切换，
 * 而不改启动主流程。这是我们现在一直在跑、也最硬化的一条路。
 */
public final class DshTermuxRuntime implements RuntimeProvider {

    private final Context ctx;

    public DshTermuxRuntime(Context ctx) {
        this.ctx = ctx;
    }

    @Override
    public String name() {
        return "termux";
    }

    /** Termux 派永远可用（它就是我们一直在用的那条路）。 */
    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public boolean isReady() {
        return DshBootstrap.isInstalled(ctx);
    }

    @Override
    public void bootstrap(Context ctx, ProgressCallback cb) throws Exception {
        // 现有 DshBootstrap.setupIfNeeded 内部已判 stamp + 完整性抽查，幂等；
        // 失败通过 DshBootstrap.getLastError() 暴露，调用方照常处理，这里不重复判错。
        DshBootstrap.setupIfNeeded(ctx);
        if (cb != null) cb.onProgress(100);
    }

    @Override
    public Process startEngine(Context ctx, List<String> args) throws Exception {
        // 复用 DshProcessManager 的进程守护（保活 / 端口探测 / DSH_STATE 扫描）。
        // 现有启动脚本已写死 --port 3080，args 暂不向下传递（避免改脚本语义）。
        return DshProcessManager.launch(ctx);
    }
}
