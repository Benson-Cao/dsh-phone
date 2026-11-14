// 编译期桩（compile-only scaffolding）—— **不参与打包，也不参与运行**。
//
// 为什么需要：本仓库只含 com.dsh.harness 的源码，androidx 与 com.termux.R 由
// gradle 工程（termux-app 源码树）提供，桌面 javac 看不到它们。没有这些桩，
// `scripts/test-java.sh` 就只能编「新增的那几个文件」，而改到 DshSafBridge /
// MainActivity 这类交叉引用多的文件时，编译错误要等出包才暴露。
//
// 用法：只有做整树编译校验时才把本目录加入 sourcepath。
// 桩的签名必须与真实依赖一致 —— 签名漂了会变成「假通过」，
// 所以这里每个成员都按实际调用点写，不做宽泛兜底。
package androidx.webkit;

/** 桩：androidx.webkit.JavaScriptReplyProxy */
public interface JavaScriptReplyProxy {
    void postMessage(String message);
}
