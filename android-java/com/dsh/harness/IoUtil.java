package com.dsh.harness;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 文件读写小工具。
 *
 * <p>为什么不直接用 {@code java.nio.file.Files}：目标Sdk 定在 28，
 * 而 {@code DshBootstrap} 要在 API 21+ 的旧设备上解包/校验大文件，
 * 用最朴素的流式读写最稳，不引入任何版本兼容分支。
 *
 * <p>⚠️ 大文件注意：{@link #readAllBytes} 会把整个文件读进内存。
 * 只用于日志（尾部几十 KB）这类小文件；700MB 的 bundle 一律走流式，
 * 绝不要调它。
 */
final class IoUtil {
    private IoUtil() {}

    static byte[] readAllBytes(File f) throws Exception {
        long len = f.length();
        if (len > Integer.MAX_VALUE) throw new Exception("file too large");
        byte[] buf = new byte[(int) len];
        try (FileInputStream in = new FileInputStream(f)) {
            int off = 0, n;
            while (off < buf.length && (n = in.read(buf, off, buf.length - off)) >= 0) off += n;
        }
        return buf;
    }

    static void writeAllBytes(File f, byte[] b) throws Exception {
        try (OutputStream out = new FileOutputStream(f)) { out.write(b); }
    }

    static List<String> readAllLines(File f) throws Exception {
        List<String> lines = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(new FileInputStream(f), "UTF-8"))) {
            String line;
            while ((line = r.readLine()) != null) lines.add(line);
        }
        return lines;
    }

    static void copyFile(File src, File dst) throws Exception {
        try (FileInputStream in = new FileInputStream(src);
             FileOutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[8192]; int n;
            while ((n = in.read(buf)) >= 0) out.write(buf, 0, n);
        }
    }
}
