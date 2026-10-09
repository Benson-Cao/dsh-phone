package com.dsh.harness;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

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
