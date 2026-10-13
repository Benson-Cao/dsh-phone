#!/usr/bin/env python3
"""bundle 布局快速自检（被 03-package-asset.sh 调用，也可单独跑）。

只读 tar 流的前若干条目做判断，不遍历全树 —— 700MB 的 bundle 完整列目录
要几分钟，够呛。输出单行状态给 shell 解析：

    OK       含usr/bin/node（布局正确）
    FLAT     只有 home/node_modules，缺 usr/bin/node（必须用 04-repack 重排）
    UNKNOWN  两者都没有（多半不是本项目打的包）
    BAD:xxx  打不开 / 不是有效 tar.gz

单独用法：
    python3 scripts/.check-bundle-layout.py path/to/dsh-bundle.tar.gz
"""
import sys
import tarfile


# bundle 里条目数以万计（实测 node_modules 就有 7000+ 条），而 tar 流的
# 顺序是打包时写入的，usr/bin 完全可能排在 home/node_modules 后面。
# 所以不能"读前N 条就break" —— 那样会漏判成FLAT（实测踩过：真bundle 有
# usr/bin/node，却因为先读到 20000 条 home/* 被误报扁平）。
# 这里全量扫描但只做字符串判断，不读文件内容，700MB 也只要十几秒。
def main(path: str) -> str:
    try:
        has_node = False
        has_nm = False
        with tarfile.open(path, "r:gz") as tf:
            for m in tf:
                n = m.name
                # 兼容 "./usr/bin/node" 这种带 ./ 前缀的写法
                nn = n[2:] if n.startswith("./") else n
                if nn == "usr/bin/node":
                    has_node = True
                    if has_nm:
                        return "OK"
                elif nn == "home/node_modules" or nn.startswith("home/node_modules/"):
                    has_nm = True
                    if has_node:
                        return "OK"
    except Exception as e:
        return "BAD:%s" % e

    if has_node:
        return "OK"
    if has_nm:
        return "FLAT"
    return "UNKNOWN"


if __name__ == "__main__":
    if len(sys.argv) < 2:
        print("usage: check-bundle-layout.py <bundle.tar.gz>", file=sys.stderr)
        sys.exit(2)
    print(main(sys.argv[1]))