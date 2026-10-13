# 排障速查：启动失败清单

**用法**：先按症状定位，再去[`排障记录.md`](排障记录.md) 看完整分析（17 轮真机沉淀）。

> 没有 adb 时，App 启动页的「重试 / 查看诊断」就是唯一取证渠道——
> 它会打印 `dsh-web.log` 尾部。所以先点它，别急着连电脑。

---

## 速查表

| 症状 | 最可能的原因 | 处理 |
|---|---|---|
| 装不上，提示「降级安装」 | `versionCode` 没递增 | 改 `version.ini` 的 `version_code` 调大，重编 |
| 装上了但装的还是旧版| `BUNDLE_REV` 没递增，跳过了解包 | 递增 `DshBootstrap.java:37` 的 `BUNDLE_REV` |
| 一直停在「正在解包」 | 存储空间不足 | 诊断页会显示可用空间；清理后重试 |
| 解包反复重来，死循环 | 完整性校验用 `File.list()` 比递归条目数 | 见排障记录 §0.3（已修，别改回去） |
| bash / node / .so 全部「缺失」 | bundle 打成扁平结构 | 用 `04-repack-bundle-layout.py` 重排 |
| `library not found` | 打包时加了 `tar -h` / 先解包再重打包，软链丢了 | tar **不要**加 `-h`；`lint-sh.sh --fix-hints` 会查 |
| `Cannot find the native Koffi module` | koffi.node 路径没铺对 | `DshBootstrap.ensureKoffiNative()` 应幂等铺好；查 bundle 里有没有 `koffi.node` |
| boot 完立刻崩，提示 HMR | 缺 `--expose-internals` | launcher 已带该 flag；确认 `bin/dsh-web.sh` 是新版生成的 |
| UI 空白 / 首启误报失败，重试就好 | frontend-static 注册 fallback 前就打印了 token | **别改**就绪判定：必须 `DshProbe.tryReady` 拿到真200 |
| WebView `net::ERR_CLEARTEXT_NOT_PERMITTED` | manifest 缺 `networkSecurityConfig` | `01-modify-termux.sh` 会注入；确认资源 `res/xml/network_security_config.xml` 存在 |
| 日志面板什么都没有 | `$HOME` 目录不存在，重定向失败 | `tailLog()` 现在会输出一份环境自检清单（HOME/bash/node/脚本/libc++/libicuuc） |
| 状态文案一直停在「正在启动」 | 运行时没上报状态 | 已修：`DSH_STATE` 生产者补齐（start_dsh / termux_init / precheck / extracting / retrying / error）。若仍停住，直接看 `dsh-web.log` 有没有 `DSH_STATE:` 行 |
| 构建卡在 `maven.google.com` | 代理失效 | `--offline`（见 [`build.md`](build.md)） |
| 构建报 `APP_BUILD_SCRIPT points to an unknown file` | 工程路径含空格 | `mv` 到无空格路径，junction 无效 |

---

## 排查顺序（省时间）

1. **看诊断页** — App 已经把 `dsh-web.log` 尾部打出来了。
2. **看 `dsh-web.log` 有没有 `DSH_STATE:` 行** — 判断是「运行时没起来」还是「起来了但 UI 没收到」。
3. **`bash scripts/check-build-env.sh`** — 如果怀疑是构建/环境问题。
4. **才去连电脑 / logcat**。

---

## 三条让人白跑整轮的陷阱

摘自 [`排障记录.md`](排障记录.md) §0 —— 这三条都伪装成"技术问题"，其实是环境问题：

1. **`curl` 测 localhost 被代理劫持** → 拿到的是代理的 404/502，看起来像"服务有问题"。
   必须 `curl --noproxy '*'` 或用 `node -e "fetch(...)"`。
2. **Windows 上 Node 不认 `HOME`** → 用的是 `USERPROFILE` / `os.homedir()`。
3. **`File.list()` 只返回顶层条目** → 拿它跟递归条目数比，永远不达标。

---

## 环境自检清单

```bash
bash scripts/check-build-env.sh              # PC 构建环境
bash scripts/verify-sync.sh                 # harness Java 是否已同步进 termux-app
python scripts/.check-bundle-layout.py <bundle.tar.gz>   # bundle 布局（秒级）
```

真机侧的等价物：App 启动页的诊断按钮（打印 `dsh-web.log` 尾部 + 环境自检）。