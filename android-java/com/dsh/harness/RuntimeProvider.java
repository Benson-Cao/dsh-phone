package com.dsh.harness;

import android.content.Context;

import java.io.File;
import java.util.List;

/**
 * r152（官方对齐）：运行底座统一接口。
 *
 * <p>本机模式两个实现（Termux / PRoot）共用；后续接远程主机模式时，只要再写一个
 * {@code RemoteRuntime} 实现本接口即可（来自 dsh-Go 的产品化经验：免 Root、版本钉死、可替换）。
 *
 * <p>设计要点：
 * <ul>
 *   <li>免 Root：不依赖 su、不触碰系统分区；</li>
 *   <li>版本钉死：内置 dsh 版本固定，升级由打包侧 manifest 控制；</li>
 *   <li>可替换：底座选型上移到 {@link DshRuntimeSelector}，调用方只认本接口。</li>
 * </ul>
 */
public interface RuntimeProvider {

    /** 底座类型标识（"termux" / "proot"），用于日志与诊断。 */
    String name();

    /** 当前设备 / 产物是否支持该底座（架构 + 关键二进制）。 */
    boolean isAvailable();

    /** 是否已经解压完成 + 补丁应用 + 可执行权限正确（无需再 bootstrap）。 */
    boolean isReady();

    /** 首启解压 + 打补丁；onProgress 返回 0..100。实现可抛异常，调用方负责兜底。 */
    void bootstrap(Context ctx, ProgressCallback cb) throws Exception;

    /** 启动 dsh 引擎进程，返回进程句柄（调用方负责销毁 / 保活）。 */
    Process startEngine(Context ctx, List<String> args) throws Exception;

    /** bootstrap 进度回调（SAM 接口，Kotlin 侧可用 lambda 传入）。 */
    interface ProgressCallback {
        void onProgress(int percent);
    }
}
