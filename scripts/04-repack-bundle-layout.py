#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
04-repack-bundle-layout.py  (PC 侧，需 Python 3.8+ 与 NDK llvm-readelf)

把扁平布局的 dsh bundle 重打成 Termux 布局，并补回合并时丢失的 .so 符号链接：

  输入 tar 根:  bin/...  lib/...  node_modules/...
  输出 tar 根:  usr/bin/...  usr/lib/...  home/node_modules/...

为什么要做：
1) App 的 DshBootstrap 按 Termux 约定找 $PREFIX=files/usr、$HOME=files/home，
   而原始 bundle 是扁平的 bin/lib/node_modules，导致真机上 bash/node/.so 全部"缺失"。
2) 合并多个 tar.gz 时符号链接被丢弃（typeflag 只有 5/0），而 node/bash 的 NEEDED
   用的是短名（libz.so.1 / libsqlite3.so / libreadline.so.8 / libicu*.so.78），
   真实文件却是带完整版本号的（libz.so.1.3.2 ...），没有软链就起不来。

别名如何得出：
- 扫描 bin/node、bin/bash、lib 下所有 .so*、node_modules 下所有 .node 的 NEEDED；
- 对每个在 lib/ 里找不到同名真实文件的 NEEDED，按前缀匹配挑一个真实文件做软链；
- 再补上所有 "SONAME != 文件名" 的库的 SONAME 软链（传递依赖用）。
Android 系统自带的 libc/libm/libdl/liblog 等无候选，自然被跳过；
树里 glibc/musl/darwin/win32 预编译件带来的 libc.so.6 等同样无候选，也会被跳过。
"""

import collections
import os
import re
import subprocess
import sys
import tarfile
import time

SRC_TAR = r'D:\work\apk-extracted.tar'
FLAT_DIR = r'D:\work\tt_apkverify'
OUT_TAR = r'D:\Program AI\DSH Phone\dsh-bundle-gnu-v2.tar.gz'
READELF = r'D:\AndroidSDK\ndk\29.0.14206865\toolchains\llvm\prebuilt\windows-x86_64\bin\llvm-readelf.exe'


def elf_files():
    out = []
    for base, _, names in os.walk(FLAT_DIR):
        for n in names:
            p = os.path.join(base, n)
            rel = os.path.relpath(p, FLAT_DIR).replace('\\', '/')
            if rel in ('bin/node', 'bin/bash'):
                out.append(p)
            elif rel.startswith('lib/') and '.so' in n:
                out.append(p)
            elif n.endswith('.node'):
                out.append(p)
    return out


def scan():
    needed = collections.Counter()
    sonames = {}
    files = elf_files()
    failed = 0
    for p in files:
        try:
            r = subprocess.run([READELF, '-d', p], capture_output=True, text=True, timeout=20)
        except Exception:
            failed += 1
            continue
        for line in r.stdout.splitlines():
            m = re.search(r'\(NEEDED\).*\[(.+?)\]', line)
            if m:
                needed[m.group(1)] += 1
            m = re.search(r'\(SONAME\).*\[(.+?)\]', line)
            if m:
                sonames[os.path.basename(p)] = m.group(1)
    return files, needed, sonames, failed


def build_alias_map(existing, needed, sonames):
    aliases = {}
    for name in sorted(needed):
        if name in existing:
            continue
        cands = sorted([f for f in existing if f.startswith(name + '.')], key=len)
        if not cands:
            base = name.split('.so')[0] + '.so'
            cands = sorted([f for f in existing if f.startswith(base + '.')], key=len)
        if cands:
            aliases[name] = cands[0]
    for f, s in sorted(sonames.items()):
        # 只给 lib/ 顶层文件建别名。子目录里的插件（krb5 的 db2.so / otp.so 等）
        # 若建到顶层会变成悬空软链，没有意义。
        if s and s != f and s not in existing and s not in aliases and f in existing:
            aliases[s] = f
    return aliases


def map_name(n):
    if n.startswith('./'):
        n = n[2:]
    # 目录条目可能不带结尾斜杠（bin/ 带、lib 与 node_modules 不带）
    if n in ('bin', 'lib'):
        return 'usr/' + n
    if n == 'node_modules':
        return 'home/node_modules'
    if n.startswith('bin/'):
        return 'usr/' + n
    if n.startswith('lib/'):
        return 'usr/' + n
    if n.startswith('node_modules/'):
        return 'home/' + n
    return n


def main():
    try:
        sys.stdout.reconfigure(encoding='utf-8')
    except Exception:
        pass

    existing = set(os.listdir(os.path.join(FLAT_DIR, 'lib')))
    files, needed, sonames, failed = scan()
    aliases = build_alias_map(existing, needed, sonames)

    print('ELF scanned      : %d (readelf failed %d)' % (len(files), failed))
    print('unique NEEDED    : %d' % len(needed))
    print('aliases to create: %d' % len(aliases))
    for k in sorted(aliases):
        print('   usr/lib/%-28s -> %s' % (k, aliases[k]))
    print('')

    t0 = time.time()
    members = 0
    unknown = []
    with tarfile.open(OUT_TAR, 'w:gz', format=tarfile.GNU_FORMAT, compresslevel=6) as out:
        with tarfile.open(SRC_TAR, 'r:') as src:
            for m in src:
                nn = map_name(m.name)
                if nn == m.name and '/' not in m.name:
                    unknown.append(m.name)
                m.name = nn
                f = src.extractfile(m) if m.isfile() else None
                out.addfile(m, f)
                members += 1
                if members % 5000 == 0:
                    print('   ... %d members, %.0fs' % (members, time.time() - t0))
        for link in sorted(aliases):
            ti = tarfile.TarInfo('usr/lib/' + link)
            ti.type = tarfile.SYMTYPE
            ti.linkname = aliases[link]
            ti.mode = 0o777
            ti.mtime = int(time.time())
            out.addfile(ti)

    print('')
    print('members written : %d' % members)
    print('symlinks added  : %d' % len(aliases))
    print('unknown top-lvl : %s' % (unknown[:10] if unknown else 'none'))
    print('output          : %s (%d bytes)' % (OUT_TAR, os.path.getsize(OUT_TAR)))
    print('elapsed         : %.0fs' % (time.time() - t0))


if __name__ == '__main__':
    main()
