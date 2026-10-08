package com.dsh.harness;

import android.content.Context;
import android.util.Log;

import java.io.File;

/**
 * dshmarket（DSH 可视化插件市场）安装器。
 *
 * ## 为什么不用 `dsh plugin --profile web add dshmarket`
 * 该子命令**转发给 pnpm**（见 dsh/lib/plugin-Ddi42qoW.js），而 bundle 里
 * **既没有 pnpm 也没有 corepack**（node_modules/.bin 为空、usr/bin 无 npm/npx），
 * 直接跑必然失败。所以改为：用 node 直接下载 tarball + 解包 + 改 profile manifest。
 *
 * ## profile 机制（从 @deepseek-ai/dsh-app-boot/lib/index.js 读出来的，勿猜）
 * ```
 * PROFILES_DIR = "profiles"
 * resolveProfileDir(name, home) = join(home, "profiles", name)
 * initProfile 写的 manifest:
 *   { name:"dsh-profile-web", private:true, dependencies:{},
 *     dsh: { profile: { bundles:[...], patchReload:"live" } } }
 * ```
 * 所以装插件 = ① 解包到 profiles/web/node_modules/<name> ② 把 <name> 加进 bundles。
 *
 * ## 依赖
 * dshmarket 依赖 `undici` + `js-yaml` ——实测**两者都已在 bundle 里**，
 * 所以只需下载 dshmarket 自身的 tarball（约 1.2MB），不再拉依赖。
 *
 * ## 对启动时间的影响（实测 48 个 JS 文件 / 现有 bundle 7473 个 = 0.64%）
 * - 不改 `$HOME/.dsh-installed` stamp，也不碰 dist/assets，
 *   所以**不会触发 r28 那个「递归完整性抽查」**（那个只扫 dist/assets，已按天节流）。
 * - 装完的**首次**启动会多编译这 48 个文件（约 +0.3~0.5s），
 *   之后 NODE_COMPILE_CACHE(r19) 命中，**启动时间回到与装之前基本持平**。
 */
public final class DshMarketInstaller {

    private static final String TAG = "DshMarket";
    private static final String PKG = "dshmarket";
    private static final String PKG_VERSION = "1.66.11";
    /** 实测：undici@7.x + js-yaml@4.x 已在 bundle 里，安装时只需本包 tarball。 */
    private static final String TARBALL =
        "https://registry.npmjs.org/dshmarket/-/dshmarket-" + PKG_VERSION + ".tgz";

    /** 安装脚本：跑在 node 里，逻辑放在 JS 比 Java 手搓 tar 可靠（node 自带 zlib+tar 解析）。 */
    private static final String INSTALL_JS =
        "const fs = require('node:fs');\n" +
        "const path = require('node:path');\n" +
        "const zlib = require('node:zlib');\n" +
        "const home = process.env.HOME;\n" +
        "const url = process.argv[2];\n" +
        "const pkg = process.argv[3];\n" +
        "const profileDir = path.join(home, '.dsh', 'profiles', 'web');\n" +
        "const target = path.join(profileDir, 'node_modules', pkg);\n" +
        "function log(m){ process.stdout.write(m + '\\n'); }\n" +
        // 1) 已安装则只确保 manifest 里有它（幂等）
        "function ensureBundled(){\n" +
        "  const mf = path.join(profileDir, 'package.json');\n" +
        "  if(!fs.existsSync(mf)){ log('ERR:no-manifest'); process.exit(3); }\n" +
        "  const m = JSON.parse(fs.readFileSync(mf, 'utf8'));\n" +
        "  m.dsh = m.dsh || {};\n" +
        "  m.dsh.profile = m.dsh.profile || {};\n" +
        "  const b = m.dsh.profile.bundles || (m.dsh.profile.bundles = []);\n" +
        "  if(b.indexOf(pkg) < 0){ b.push(pkg); }\n" +
        "  if(!m.dependencies) m.dependencies = {};\n" +
        "  m.dependencies[pkg] = '*';\n" +
        "  fs.writeFileSync(mf, JSON.stringify(m, null, 2) + '\\n');\n" +
        "  log('OK:bundled');\n" +
        "}\n" +
        "if(fs.existsSync(path.join(target, 'package.json'))){ ensureBundled(); process.exit(0); }\n" +
        // 2) 下载到临时文件
        "const tgz = path.join(home, '.tmp', pkg + '.tgz');\n" +
        "fs.mkdirSync(path.dirname(tgz), {recursive:true});\n" +
        "(async () => {\n" +
        "  try {\n" +
        "    log('DOWNLOAD:' + url);\n" +
        "    const res = await fetch(url);\n" +
        "    if(!res.ok){ log('ERR:http-' + res.status); process.exit(4); }\n" +
        "    const buf = Buffer.from(await res.arrayBuffer());\n" +
        "    fs.writeFileSync(tgz, buf);\n" +
        "    log('DOWNLOADED:' + buf.length);\n" +
        // 3) 解包：**自己解 tar**（bundle 里只有 bash + node，没有 tar 命令）。
        //    npm tarball 是标准 ustar：512 字节头部 + 数据 + 补齐到 512 的倍数。
        //    顶层目录恒为 package/，直接剥掉这一层写入 target。
        "    const raw = zlib.gunzipSync(fs.readFileSync(tgz));\n" +
        "    let off = 0, files = 0;\n" +
        "    while(off + 512 <= raw.length){\n" +
        "      const name = raw.toString('utf8', off, off + 100).replace(/\\0.*$/, '');\n" +
        "      if(!name){ off += 512; continue; }\n" +
        "      const sz = parseInt(raw.toString('ascii', off + 124, off + 136).replace(/\\0.*$/,'').trim() || '0', 8);\n" +
        "      const type = String.fromCharCode(raw[off + 156]);\n" +
        "      const rel = name.replace(/^package\\//, '');\n" +
        "      const dataOff = off + 512;\n" +
        "      const dataEnd = dataOff + sz;\n" +
        "      if(rel && type === '0'){\n" +
        "        const dest = path.join(target, rel);\n" +
        "        fs.mkdirSync(path.dirname(dest), {recursive:true});\n" +
        "        fs.writeFileSync(dest, raw.slice(dataOff, dataEnd));\n" +
        "        files++;\n" +
        "      } else if(rel && (type === '5')){\n" +
        "        fs.mkdirSync(path.join(target, rel), {recursive:true});\n" +
        "      }\n" +
        "      off = dataOff + Math.ceil(sz / 512) * 512;\n" +
        "    }\n" +
        "    if(files === 0){ log('ERR:bad-tarball'); process.exit(5); }\n" +
        "    fs.rmSync(tgz, {force:true});\n" +
        "    log('EXTRACTED:' + files + ' files');\n" +
        "    ensureBundled();\n" +
        "  } catch(e){ log('ERR:' + (e && e.message || e)); process.exit(6); }\n" +
        "})();\n";

    private DshMarketInstaller() {}

    /** 已安装则返回版本号，未安装返回 null。 */
    public static String installedVersion(Context ctx) {
        File f = new File(profileDir(ctx), "node_modules/" + PKG + "/package.json");
        if (!f.isFile()) return null;
        try {
            String s = new String(java.nio.file.Files.readAllBytes(f.toPath()), "UTF-8");
            java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"version\"\\s*:\\s*\"([^\"]+)\"").matcher(s);
            return m.find() ? m.group(1) : "unknown";
        } catch (Exception e) {
            return "unknown";
        }
    }

    private static File profileDir(Context ctx) {
        return new File(DshBootstrap.home(ctx), ".dsh/profiles/web");
    }

    /**
     * 执行安装（在后台线程调用）。
     * @return null 表示成功；否则为可读的错误原因。
     */
    public static String install(Context ctx) {
        File home = DshBootstrap.home(ctx);
        File node = new File(DshBootstrap.prefix(ctx), "bin/node");
        if (!node.isFile()) return "node 不可用: " + node;
        if (!new File(home, ".tmp").isDirectory() && !new File(home, ".tmp").mkdirs()) {
            return "无法创建 .tmp 目录";
        }
        File js = new File(DshBootstrap.prefix(ctx), "libexec/dsh-market-install.js");
        try {
            // ⚠️ libexec 目录未必存在（只有跑过 createLauncher 才会建），先mkdirs
            File libexec = js.getParentFile();
            if (!libexec.isDirectory() && !libexec.mkdirs()) {
                return "无法创建 " + libexec.getAbsolutePath();
            }
            java.io.FileWriter fw = new java.io.FileWriter(js);
            fw.write(INSTALL_JS);
            fw.close();
        } catch (Exception e) {
            return "写入安装脚本失败: " + e.getMessage();
        }

        StringBuilder out = new StringBuilder();
        try {
            ProcessBuilder pb = new ProcessBuilder(
                node.getAbsolutePath(), js.getAbsolutePath(), TARBALL, PKG);
            pb.directory(home);
            // ⚠️ 必须给全Termux 前缀环境：Android 的 node 靠 LD_LIBRARY_PATH 找 .so，
            //   缺了它会**静默退出码 1**（真机踩过：只设 HOME/NODE_PATH 时报"退出码 1"，
            //   连 stderr 都没有）。复用 ShellRunner.buildEnv 与 dsh-web.sh 的环境保持一致。
            pb.environment().clear();
            for (String kv : ShellRunner.buildEnv(ctx)) {
                int i = kv.indexOf('=');
                if (i > 0) pb.environment().put(kv.substring(0, i), kv.substring(i + 1));
            }
            pb.environment().put("NODE_PATH", home.getAbsolutePath() + "/node_modules");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            java.io.InputStream in = p.getInputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) out.append(new String(buf, 0, n, "UTF-8"));
            int code = p.waitFor();
            String logAll = out.toString();
            Log.i(TAG, "install exit=" + code + " out=" + logAll);
            if (code != 0) {
                for (String line : logAll.split("\n")) {
                    if (line.startsWith("ERR:")) return "安装失败: " + line.substring(4);
                }
                // 把 node 的原始 stderr/异常回显出来，否则用户只看到"退出码 1"
                String detail = logAll.trim();
                if (detail.length() > 300) detail = detail.substring(0, 300) + "…";
                return detail.isEmpty()
                    ? "安装失败（退出码 " + code + "，无输出）"
                    : "安装失败（退出码 " + code + "）：" + detail;
            }
            return null;
        } catch (Exception e) {
            Log.e(TAG, "安装异常", e);
            return "安装异常: " + e.getMessage();
        }
    }
}