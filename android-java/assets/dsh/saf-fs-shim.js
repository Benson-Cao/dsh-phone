/**
 * saf-fs-shim.js —— 方案 A 的 node 侧 FS 路由适配层
 *
 * ## 它解决什么问题
 *
 * dsh 跑在 Termux 的独立 Linux 子进程里，node 侧没有 ContentResolver，
 * 打不开 content:// Uri，也就**读不到用户在系统文件选择器里授权的那个目录**。
 * 本文件把「落在 SAF 挂载点下」的路径改写成本地 HTTP 请求，交给 JVM 侧的
 * SafProxyServer 用 ContentResolver 真正去读写。
 *
 * ## 为什么不改 dsh 的 npm 包
 *
 * docs/saf-feasibility.md §3 把「node 侧 FS 路由」列为**最大风险项**，理由是
 * 改 @deepseek-ai/dsh 包内代码等于每次升级都要重做。这里用
 * `NODE_OPTIONS=--require` 预加载 + 猴子补丁绕开它：
 * 补丁打在 **node 的 fs 模块对象**上，不碰 dsh 一行源码，升级 dsh 不受影响。
 *
 * ## 为什么只覆盖异步 API（v1 的明确边界）
 *
 * dsh 的全部本地文件访问都走 `dsh-fs-local`，而它的 import 列表是
 * `chmod/link/lstat/mkdir/open/readFile/readdir/rename/rm/stat`（+ `createReadStream`），
 * **全是 fs/promises 的异步 API**。同步 API（*Sync）在挂载点下按下面的保守策略处理：
 * 读类一律报 ENOENT（"当作不存在"），写类 fire-and-forget 后返回。
 * 真要支持同步读写，得再起一个 worker 线程做阻塞式等待（Atomics.wait），
 * 那是 v2 的事 —— 现在没有任何调用方需要它，先不付这个复杂度。
 *
 * ## ESM facade 的坑（最关键的一处）
 *
 * dsh-fs-local 是 ESM（package.json "type":"module"），它这样导入：
 *   import { readFile, stat } from "node:fs/promises";
 * ESM 从 CJS 内建模块做**具名导入**时，值是在 facade 模块首次求值那一刻
 * **快照**下来的，之后改 fs.promises.readFile 对它无效。
 * 对策：本 shim 在预加载阶段就打完补丁，并立刻 `import()` 一次强制 facade
 * 在此时定型 —— 这样 dsh-fs-local 之后拿到的必然是包装后的函数。
 * 定型成功与否会打到 stdout（进 dsh-web.log），便于真机核对。
 */
(function () {
  'use strict';

  var TAG = '[dsh-saf]';

  try {
    boot();
  } catch (e) {
    try { console.error(TAG + ' 初始化失败（不阻塞启动）: ' + (e && e.message)); } catch (_) {}
  }

  function boot() {
    var fs = require('node:fs');
    var fsp = require('node:fs/promises');
    var http = require('node:http');
    var nodePath = require('node:path');

    // ↓↓ 必须在打补丁之前抓住原生实现（见下面 config() 的说明）。
    //    这是本文件最容易翻车的一处：一旦 config() 用了**补丁版**的 statSync /
    //    readFileSync，调用链就变成 config() → statSync → relOf() → config() 的
    //    无限互递归，靠 RangeError 恰好被 catch 兜住才"看起来能用"。
    var nativeStatSync = fs.statSync;
    var nativeReadFileSync = fs.readFileSync;

    // ---- 配置：由 JVM 侧写成 $HOME/.dsh/saf_config.json ----
    // 走文件而不是环境变量，是为了「用户中途才授权」时不必重启 dsh
    // （环境变量只在进程启动时注入一次，重启 dsh 要 8s）。
    var cfgCache = null;
    var cfgStamp = '';
    var cfgAt = 0;
    var inConfig = false;

    function cfgPath() {
      return (process.env.HOME || '') + '/.dsh/saf_config.json';
    }

    /**
     * 读配置（带 1.5s 记忆）。
     *
     * ⚠️⚠️ 这里只能用 nativeStatSync / nativeReadFileSync，**绝不能**用 fs.statSync /
     * fs.readFileSync —— 那两条在本文件后半段已被换成包装版，而包装版第一步就调
     * relOf() → config()，于是构成 config → statSync → relOf → config 的无限互递归。
     *
     * 事故记录（2026-10-13，r106 真机自检未通过）：
     *   递归靠栈溢出抛 RangeError、恰好被本函数的 catch 吞掉，所以"不报错"，但每次
     *   调用都要烧掉约 2200 层栈帧。配置不存在时 cfgCache 恒为 null、缓存永不命中，
     *   于是**每次** relOf 都重跑一遍溢出。dsh 启动要跑成千上万次 fs 操作 →
     *   启动被拖到分钟级：真机表现是 node 打完 `[dsh-saf] FS 路由已装载` 就再无输出，
     *   3080 端口始终不监听，启动页等到 120s 超时报「自检未通过」。
     *   本地 A/B 实测：改前 75s 仍未就绪（进程存活），改后 6s 就绪。
     */
    function config() {
      var now = Date.now();
      // 重入直接返回 null（二级防御）：即便将来有人不小心又引入递归，也只是这一
      // 次判定退化成"不走 SAF"，不会再把栈烧穿、拖垮整个启动链。
      if (inConfig) return null;
      // 命中/未命中都按 1.5s 记忆：配置文件不存在时同样不该每次去 stat 一遍。
      if (cfgAt && now - cfgAt < 1500) return cfgCache;
      inConfig = true;
      try {
        cfgAt = now;
        try {
          var st = nativeStatSync.call(fs, cfgPath());
          var stamp = st.mtimeMs + ':' + st.size;
          if (stamp === cfgStamp && cfgStamp !== '') return cfgCache;
          cfgStamp = stamp;
          cfgCache = JSON.parse(nativeReadFileSync.call(fs, cfgPath(), 'utf8'));
        } catch (e) {
          cfgCache = null;
          cfgStamp = '';
        }
        return cfgCache;
      } finally {
        inConfig = false;
      }
    }

    /** 路径落在挂载点内 → 返回相对路径（挂载根为 ''）；否则 null（走原生 fs）。 */
    function relOf(p) {
      var c = config();
      if (!c || !c.enabled || !c.mount || !c.url || !c.token) return null;
      if (typeof p !== 'string') return null;
      var m = String(c.mount);
      var sep = nodePath.sep;
      if (m.length > 1 && m.charAt(m.length - 1) === sep) m = m.slice(0, -1);
      var s = nodePath.normalize(p);
      if (s === m) return '';
      if (s.indexOf(m + sep) !== 0) return null;
      // 统一成正斜杠再交给 Java：node 在 Windows 上给出的是反斜杠，
      // 而 SAF 那侧的文档 id 语义一律用 '/'。
      return s.slice(m.length + 1).split(sep).join('/');
    }

    function warnOnce(key, msg) {
      if (warnOnce[key]) return;
      warnOnce[key] = 1;
      try { console.warn(TAG + ' ' + msg); } catch (_) {}
    }

    function errno(code, syscall, p) {
      var e = new Error(code + ': ' + syscall + " '" + p + "'");
      e.code = code;
      e.errno = code === 'ENOENT' ? -2 : -1;
      e.syscall = syscall;
      e.path = p;
      return e;
    }

    // ---- HTTP 通道 ----
    function call(op, obj) {
      return new Promise(function (resolve, reject) {
        var c = config();
        if (!c || !c.url || !c.token) { reject(errno('ENOENT', op, '')); return; }
        var body = Buffer.from(JSON.stringify(obj || {}), 'utf8');
        var u;
        try { u = new URL(c.url.replace(/\/$/, '') + '/' + op); }
        catch (e) { reject(errno('ENOENT', op, '')); return; }
        var req = http.request({
          hostname: u.hostname,
          port: u.port,
          path: u.pathname,
          method: 'POST',
          headers: {
            'content-type': 'application/json',
            'content-length': body.length,
            'x-dsh-token': String(c.token)
          }
        }, function (res) {
          var chunks = [];
          res.on('data', function (d) { chunks.push(d); });
          res.on('end', function () {
            var buf = Buffer.concat(chunks);
            if (res.statusCode !== 200) {
              var msg = '';
              try { msg = JSON.parse(buf.toString('utf8')).error || ''; } catch (_) {}
              reject(errno(msg === 'ENOENT' ? 'ENOENT' : 'EIO', op, (obj && obj.path) || ''));
              return;
            }
            resolve(buf);
          });
        });
        req.on('error', function (e) { reject(errno('EIO', op, (obj && obj.path) || '')); });
        req.setTimeout(20000, function () { req.destroy(new Error('ETIMEDOUT')); });
        req.end(body);
      });
    }

    function callJson(op, obj) {
      return call(op, obj).then(function (buf) {
        return JSON.parse(buf.toString('utf8'));
      });
    }

    // ---- Stats 合成 ----
    // dsh-fs-local 的 versionOf() 用 dev:ino:size:mtimeNs:ctimeNs 做乐观并发版本号，
    // 所以 ino/mtimeNs **必须稳定** —— 每次调用都变的伪版本号会让编辑无谓冲突。
    // ino 取路径哈希（同一文件恒定），mtimeNs 取 provider 上报的 LAST_MODIFIED。
    function hash32(s) {
      var h = 2166136261;
      for (var i = 0; i < s.length; i++) {
        h ^= s.charCodeAt(i);
        h = (h * 16777619) >>> 0;
      }
      return (h % 2147483647) + 1;   // 正数，避开 0（inode 0 无意义）
    }

    function makeStats(rel, s, bigint) {
      var isDir = !!s.isDir;
      var size = s.size || 0;
      var ms = s.mtimeMs || 0;
      var ns = Math.round(ms * 1e6);
      var ino = hash32(rel || '/');
      var mode = isDir ? 16877 : 33188;          // 0o40755 / 0o100644
      var B = bigint ? BigInt : Number;
      var date = new Date(ms);
      return {
        dev: B(2049),
        ino: B(ino),
        mode: B(mode),
        nlink: 1,
        uid: 0,
        gid: 0,
        rdev: 0,
        size: B(size),
        blksize: 4096,
        blocks: B(Math.ceil(size / 4096)),
        atimeMs: ms, mtimeMs: ms, ctimeMs: ms, birthtimeMs: ms,
        atimeNs: B(ns), mtimeNs: B(ns), ctimeNs: B(ns), birthtimeNs: B(ns),
        atime: date, mtime: date, ctime: date, birthtime: date,
        isFile: function () { return !isDir; },
        isDirectory: function () { return isDir; },
        isSymbolicLink: function () { return false; },
        isBlockDevice: function () { return false; },
        isCharacterDevice: function () { return false; },
        isFIFO: function () { return false; },
        isSocket: function () { return false; }
      };
    }

    // ---- 各操作的 SAF 实现 ----
    function safStat(rel, bigint) {
      return callJson('stat', { path: rel }).then(function (s) {
        if (!s.exists) throw errno('ENOENT', 'stat', rel);
        return makeStats(rel, s, bigint);
      });
    }

    function safRead(rel) {
      return call('read', { path: rel });
    }

    function safWrite(rel, data) {
      var buf = Buffer.isBuffer(data) ? data : Buffer.from(String(data), 'utf8');
      return callJson('write', { path: rel, data: buf.toString('base64') });
    }

    function safReaddir(rel, opts) {
      return callJson('list', { path: rel }).then(function (r) {
        var entries = (r && r.entries) || [];
        entries.sort(function (a, b) { return String(a.name).localeCompare(String(b.name)); });
        if (opts && opts.withFileTypes) {
          return entries.map(function (e) { return dirent(e.name, !!e.isDir); });
        }
        return entries.map(function (e) { return e.name; });
      });
    }

    function dirent(name, isDir) {
      return {
        name: name,
        isDirectory: function () { return isDir; },
        isFile: function () { return !isDir; },
        isSymbolicLink: function () { return false; },
        isBlockDevice: function () { return false; },
        isCharacterDevice: function () { return false; },
        isFIFO: function () { return false; },
        isSocket: function () { return false; }
      };
    }

    /**
     * FileHandle 模拟。
     * dsh-fs-local 的两处用法：
     *   open(p,'r')  → handle.stat() / handle.read(buf,off,len,null) / close()
     *   open(p,'wx') → handle.chmod() / handle.writeFile(content) / sync() / close()
     * 读：整文件取回内存（会话/配置文件量级，够用）；写：在 writeFile 时落一次 HTTP。
     */
    function makeHandle(rel, flags, data) {
      var buf = data || null;
      var pos = 0;                 // 顺序读游标（position 传 null 时推进它）
      return {
        fd: -1,
        stat: function (o) { return safStat(rel, !!(o && o.bigint)); },
        read: function (buffer, offset, length, position) {
          return Promise.resolve().then(function () {
            if (buf === null) throw errno('EBADF', 'read', rel);
            var from = (typeof position === 'number' && position >= 0) ? position : pos;
            var n = Math.min(length, Math.max(buf.length - from, 0));
            if (n > 0) buf.copy(buffer, offset, from, from + n);
            pos = from + n;
            return { bytesRead: n, buffer: buffer };
          });
        },
        chmod: function () { return Promise.resolve(); },
        writeFile: function (data) { return safWrite(rel, data); },
        write: function (data) {
          return safWrite(rel, data).then(function () {
            return { bytesWritten: Buffer.byteLength(data) };
          });
        },
        sync: function () { return Promise.resolve(); },
        datasync: function () { return Promise.resolve(); },
        truncate: function () { return Promise.resolve(); },
        close: function () { buf = null; return Promise.resolve(); }
      };
    }

    function safOpen(rel, flags) {
      flags = String(flags || 'r');
      // 只区分「读」与「写」：'wx'/'w'/'a' 都归到写路径（真正落盘在 writeFile）
      var isRead = flags.indexOf('r') >= 0 && flags.indexOf('+') < 0;
      if (!isRead) return Promise.resolve(makeHandle(rel, flags, null));
      return safRead(rel).then(function (data) {
        return makeHandle(rel, flags, data);
      });
    }

    // ---- 补丁安装 ----
    function patchAsync(obj, name, fn) {
      var orig = obj[name];
      if (typeof orig !== 'function') return;
      obj[name] = function (p) {
        var rel = relOf(p);
        if (rel === null) return orig.apply(this, arguments);
        try { return fn(rel, arguments); }
        catch (e) { return Promise.reject(e); }
      };
    }

    // fs/promises
    patchAsync(fsp, 'readFile', function (rel, a) {
      return safRead(rel).then(function (b) {
        var enc = a[1];
        if (typeof enc === 'object' && enc && enc.encoding) return b.toString(enc.encoding);
        if (typeof enc === 'string') return b.toString(enc);
        return b;
      });
    });
    patchAsync(fsp, 'writeFile', function (rel, a) { return safWrite(rel, a[1]); });
    patchAsync(fsp, 'appendFile', function (rel, a) {
      return safRead(rel).catch(function () { return Buffer.alloc(0); }).then(function (old) {
        return safWrite(rel, Buffer.concat([old, Buffer.isBuffer(a[1]) ? a[1] : Buffer.from(String(a[1])) ]));
      });
    });
    patchAsync(fsp, 'stat', function (rel, a) { return safStat(rel, !!(a[1] && a[1].bigint)); });
    patchAsync(fsp, 'lstat', function (rel, a) { return safStat(rel, !!(a[1] && a[1].bigint)); });
    patchAsync(fsp, 'readdir', function (rel, a) { return safReaddir(rel, a[1]); });
    patchAsync(fsp, 'mkdir', function (rel) {
      return callJson('mkdir', { path: rel }).then(function () { return undefined; });
    });
    patchAsync(fsp, 'rm', function (rel) {
      return callJson('delete', { path: rel }).then(function () { return undefined; });
    });
    patchAsync(fsp, 'rmdir', function (rel) {
      return callJson('delete', { path: rel }).then(function () { return undefined; });
    });
    patchAsync(fsp, 'unlink', function (rel) {
      return callJson('delete', { path: rel }).then(function () { return undefined; });
    });
    patchAsync(fsp, 'rename', function (rel, a) {
      var to = a[1];
      var relTo = relOf(to);
      if (relTo === null) throw errno('EXDEV', 'rename', rel);
      return callJson('rename', { path: rel, to: relTo }).then(function () { return undefined; });
    });
    // realpath 必须回**绝对路径**：dsh 用它做 targetKey（stable identity），
    // 返回相对路径会让后续所有以它为基准的解析错位。
    patchAsync(fsp, 'realpath', function (rel, a) {
      return Promise.resolve(nodePath.normalize(String(a[0])));
    });
    patchAsync(fsp, 'access', function (rel) { return Promise.resolve(); });
    patchAsync(fsp, 'chmod', function () { return Promise.resolve(); });
    patchAsync(fsp, 'open', function (rel, a) { return safOpen(rel, a[1]); });
    patchAsync(fsp, 'link', function () { return Promise.reject(errno('ENOSYS', 'link', '')); });

    // fs（回调式）：把 promise 结果回灌给最后一个回调参数
    function patchCb(name, fn) {
      var orig = fs[name];
      if (typeof orig !== 'function') return;
      fs[name] = function (p) {
        var rel = relOf(p);
        if (rel === null) return orig.apply(this, arguments);
        var cb = arguments[arguments.length - 1];
        if (typeof cb !== 'function') return orig.apply(this, arguments);
        Promise.resolve()
          .then(function () { return fn(rel, arguments); })
          .then(function (v) { cb(null, v); }, function (e) { cb(e); });
      };
    }
    patchCb('readFile', function (rel) { return safRead(rel); });
    patchCb('writeFile', function (rel, a) { return safWrite(rel, a[1]).then(function () {}); });
    patchCb('stat', function (rel, a) { return safStat(rel, !!(a[1] && a[1].bigint)); });
    patchCb('lstat', function (rel, a) { return safStat(rel, !!(a[1] && a[1].bigint)); });
    patchCb('readdir', function (rel, a) { return safReaddir(rel, a[1]); });
    patchCb('mkdir', function (rel) { return callJson('mkdir', { path: rel }).then(function () {}); });
    patchCb('rm', function (rel) { return callJson('delete', { path: rel }).then(function () {}); });
    patchCb('rmdir', function (rel) { return callJson('delete', { path: rel }).then(function () {}); });
    patchCb('unlink', function (rel) { return callJson('delete', { path: rel }).then(function () {}); });
    patchCb('rename', function (rel, a) {
      var relTo = relOf(a[1]);
      if (relTo === null) throw errno('EXDEV', 'rename', rel);
      return callJson('rename', { path: rel, to: relTo }).then(function () {});
    });
    patchCb('realpath', function (rel, a) { return Promise.resolve(nodePath.normalize(String(a[0]))); });
    patchCb('access', function (rel) { return Promise.resolve(); });
    patchCb('chmod', function () { return Promise.resolve(); });

    // createReadStream：必须**同步**返回一个流，先给空 PassThrough 再异步灌数据
    var streamMod = require('node:stream');
    var _crs = fs.createReadStream;
    fs.createReadStream = function (p, opts) {
      var rel = relOf(p);
      if (rel === null) return _crs.apply(this, arguments);
      var s = new streamMod.PassThrough();
      safRead(rel).then(function (buf) {
        var start = (opts && typeof opts.start === 'number') ? opts.start : 0;
        var end = (opts && typeof opts.end === 'number') ? opts.end + 1 : buf.length;
        s.end(buf.subarray(start, end));
      }, function (e) { s.destroy(e); });
      return s;
    };

    // ---- 同步 API：v1 的保守策略（见文件头说明）----
    var _existsSync = fs.existsSync;
    fs.existsSync = function (p) {
      var rel = relOf(p);
      if (rel === null) return _existsSync.apply(this, arguments);
      return rel === '' ? true : false;      // 挂载根是真的本地空目录，子项一律"当作不存在"
    };
    ['statSync', 'lstatSync', 'readFileSync', 'readdirSync'].forEach(function (name) {
      var orig = fs[name];
      if (typeof orig !== 'function') return;
      fs[name] = function (p) {
        var rel = relOf(p);
        if (rel === null) return orig.apply(this, arguments);
        warnOnce(name, '同步读接口在 SAF 挂载点下暂不支持，按 ENOENT 处理: ' + name);
        throw errno('ENOENT', name, String(p));
      };
    });
    ['writeFileSync', 'appendFileSync', 'mkdirSync', 'unlinkSync', 'rmdirSync', 'rmSync', 'renameSync'].forEach(function (name) {
      var orig = fs[name];
      if (typeof orig !== 'function') return;
      fs[name] = function (p) {
        var rel = relOf(p);
        if (rel === null) return orig.apply(this, arguments);
        warnOnce(name, '同步写接口在 SAF 挂载点下为 fire-and-forget（不保证返回时已完成）: ' + name);
        // fire-and-forget：写类操作没有返回值依赖，异步补做即可
        try {
          if (name === 'writeFileSync' || name === 'appendFileSync') {
            safWrite(rel, arguments[1]);
          } else if (name === 'mkdirSync') {
            callJson('mkdir', { path: rel });
          } else if (name === 'renameSync') {
            var t = relOf(arguments[1]);
            if (t !== null) callJson('rename', { path: rel, to: t });
          } else {
            callJson('delete', { path: rel });
          }
        } catch (_) {}
        return undefined;
      };
    });

    // ---- ESM facade 定型（详见文件头「ESM facade 的坑」）----
    // 打完补丁立刻 import() 一次，强制 node:fs / node:fs/promises 的 ESM facade
    // 在**此刻**求值，把包装后的函数快照进去。dsh-fs-local 之后 import 的是
    // 同一个被缓存的 facade，因此必然拿到我们的版本。
    var pinned = { promises: false, fs: false };
    try {
      import('node:fs/promises').then(function (m) {
        pinned.promises = !!(m && m.readFile === fsp.readFile && m.stat === fsp.stat);
        report();
      }, report);
      import('node:fs').then(function (m) {
        pinned.fs = !!(m && m.createReadStream === fs.createReadStream && m.existsSync === fs.existsSync);
        report();
      }, report);
    } catch (e) {
      try { console.warn(TAG + ' ESM facade 定型跳过: ' + (e && e.message)); } catch (_) {}
    }
    var settled = 0;
    function report() {
      // 两个 facade 都定型完再打这一行：只报一半会让人误判"fs 没接上"，
      // 真机排查时这条日志是唯一线索，不能是半截的。
      settled++;
      if (settled < 2) return;
      try {
        console.log(TAG + ' FS 路由已装载（ESM facade: promises=' + pinned.promises
          + ' fs=' + pinned.fs + '）');
      } catch (_) {}
    }

    // 调试出口：node -e 里可以手测，也方便真机 adb 排查
    global.__DSH_SAF__ = {
      config: config,
      relOf: relOf,
      call: callJson,
      read: function (p) { return safRead(relOf(p)); },
      write: function (p, d) { return safWrite(relOf(p), d); }
    };
  }
})();
