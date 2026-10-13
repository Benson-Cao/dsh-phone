package com.dsh.harness;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.util.Log;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 方案 A 的树访问层：把「相对路径」翻译成 SAF 的 document Uri，再做读写。
 *
 * ## 为什么必须一层层走 child 查询
 *
 * SAF 的树 Uri 只给一个 {@code treeDocumentId}，<b>没有</b>「按路径直接拿 document」的 API。
 * 唯一办法是从根开始逐段 {@code buildChildDocumentsUriUsingTree} 查名字。
 * 代价是 {@code O(深度 × 目录项数)}，而 dsh 的目录深度很浅（工作区/会话/文件），
 * 实测远快于一次 HTTP 往返的开销，所以这里不做缓存 —— 缓存与「用户在别的 App 里改了文件」
 * 的失效问题相比不划算。
 *
 * ## 写文件的两步
 *
 * {@code ACTION_OPEN_DOCUMENT_TREE} 给的是<b>目录树</b>授权，写新文件必须先
 * {@code createDocument}（让 provider 分配 Uri），再 {@code openOutputStream}。
 * 覆盖写已有文件则直接 openOutputStream(…, "wt") —— 少了 "wt" 会把新内容接在旧内容后面。
 *
 * @see SafProxyServer
 */
public final class SafTree {

    private static final String TAG = "SafTree";

    /** 目录项。 */
    public static final class Entry {
        public String name;
        public boolean isDir;
        public long size;
        public long mtimeMs;
    }

    /** 单个路径的元信息。 */
    public static final class Stat {
        public boolean exists;
        public boolean isDir;
        public long size;
        public long mtimeMs;
        public String name;
    }

    private static final String[] PROJ = {
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE,
        DocumentsContract.Document.COLUMN_SIZE,
        DocumentsContract.Document.COLUMN_LAST_MODIFIED
    };

    private SafTree() {}

    // ===================== 路径 → document =====================

    /**
     * 解析相对路径到 document Uri；不存在返回 {@code null}。
     *
     * @param rel 相对挂载点的路径（{@code ""} 表示挂载根）
     */
    public static Uri docUri(Context ctx, String rel) {
        Uri tree = SafConfig.treeUri(ctx);
        if (tree == null) return null;
        String id = docIdOf(ctx, tree, rel);
        if (id == null) return null;
        return DocumentsContract.buildDocumentUriUsingTree(tree, id);
    }

    /** 逐段查找 documentId；任一段缺失即返回 null。 */
    private static String docIdOf(Context ctx, Uri tree, String rel) {
        String id;
        try {
            id = DocumentsContract.getTreeDocumentId(tree);
        } catch (Throwable t) {
            Log.w(TAG, "取树 documentId 失败: " + t.getMessage());
            return null;
        }
        if (id == null) return null;
        if (rel == null || rel.length() == 0) return id;
        for (String seg : split(rel)) {
            String child = findChildId(ctx, tree, id, seg);
            if (child == null) return null;
            id = child;
        }
        return id;
    }

    private static String findChildId(Context ctx, Uri tree, String parentId, String name) {
        ContentResolver r = ctx.getContentResolver();
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId);
        Cursor c = null;
        try {
            c = r.query(children, PROJ, null, null, null);
            if (c == null) return null;
            while (c.moveToNext()) {
                if (name.equals(c.getString(1))) return c.getString(0);
            }
        } catch (Throwable t) {
            Log.w(TAG, "列目录失败(" + parentId + "): " + t.getMessage());
        } finally {
            if (c != null) try { c.close(); } catch (Throwable ignored) { }
        }
        return null;
    }

    /** 拆分相对路径，顺手挡掉 {@code ..} 与空段。 */
    static List<String> split(String rel) {
        List<String> out = new ArrayList<String>();
        if (rel == null) return out;
        for (String s : rel.split("/")) {
            if (s == null || s.length() == 0 || s.equals(".") || s.equals("..")) continue;
            out.add(s);
        }
        return out;
    }

    // ===================== 查询 =====================

    public static Stat stat(Context ctx, String rel) {
        Stat s = new Stat();
        Uri u = docUri(ctx, rel);
        if (u == null) return s;                 // exists=false
        ContentResolver r = ctx.getContentResolver();
        Cursor c = null;
        try {
            c = r.query(u, PROJ, null, null, null);
            if (c == null || !c.moveToFirst()) return s;
            s.exists = true;
            s.name = c.getString(1);
            String mime = c.getString(2);
            s.isDir = DocumentsContract.Document.MIME_TYPE_DIR.equals(mime);
            s.size = c.getLong(3);
            s.mtimeMs = c.getLong(4);
        } catch (Throwable t) {
            Log.w(TAG, "stat 失败(" + rel + "): " + t.getMessage());
            s.exists = false;
        } finally {
            if (c != null) try { c.close(); } catch (Throwable ignored) { }
        }
        return s;
    }

    public static List<Entry> list(Context ctx, String rel) {
        List<Entry> out = new ArrayList<Entry>();
        Uri tree = SafConfig.treeUri(ctx);
        if (tree == null) return out;
        String id = docIdOf(ctx, tree, rel);
        if (id == null) return out;
        ContentResolver r = ctx.getContentResolver();
        Cursor c = null;
        try {
            c = r.query(DocumentsContract.buildChildDocumentsUriUsingTree(tree, id),
                PROJ, null, null, null);
            if (c == null) return out;
            while (c.moveToNext()) {
                Entry e = new Entry();
                e.name = c.getString(1);
                e.isDir = DocumentsContract.Document.MIME_TYPE_DIR.equals(c.getString(2));
                e.size = c.getLong(3);
                e.mtimeMs = c.getLong(4);
                out.add(e);
            }
        } catch (Throwable t) {
            Log.w(TAG, "list 失败(" + rel + "): " + t.getMessage());
        } finally {
            if (c != null) try { c.close(); } catch (Throwable ignored) { }
        }
        return out;
    }

    // ===================== 写入 =====================

    /**
     * 递归建目录。已存在则直接返回 true（SAF 没有 mkdir -p，只能逐层试建）。
     */
    public static boolean mkdirs(Context ctx, String rel) {
        Uri tree = SafConfig.treeUri(ctx);
        if (tree == null) return false;
        String id;
        try {
            id = DocumentsContract.getTreeDocumentId(tree);
        } catch (Throwable t) {
            return false;
        }
        if (id == null) return false;
        for (String seg : split(rel)) {
            String child = findChildId(ctx, tree, id, seg);
            if (child == null) {
                Uri created = createDoc(ctx, DocumentsContract.buildDocumentUriUsingTree(tree, id),
                    DocumentsContract.Document.MIME_TYPE_DIR, seg);
                if (created == null) return false;
                try {
                    id = DocumentsContract.getDocumentId(created);
                } catch (Throwable t) {
                    return false;
                }
            } else {
                id = child;
            }
        }
        return true;
    }

    private static Uri createDoc(Context ctx, Uri parent, String mime, String name) {
        try {
            return DocumentsContract.createDocument(ctx.getContentResolver(), parent, mime, name);
        } catch (Throwable t) {
            Log.w(TAG, "createDocument 失败(" + name + "): " + t.getMessage());
            return null;
        }
    }

    /**
     * 写文件（不存在则创建）。
     *
     * <p>父目录不存在时先 {@link #mkdirs} —— dsh 的原子写是「临时文件 → rename」，
     * 临时文件就在目标同目录，父目录必然已存在，这里主要是兜住直接写新路径的情况。
     */
    public static boolean write(Context ctx, String rel, byte[] data) {
        Uri tree = SafConfig.treeUri(ctx);
        if (tree == null || rel == null || rel.length() == 0) return false;
        List<String> segs = split(rel);
        if (segs.isEmpty()) return false;
        String name = segs.remove(segs.size() - 1);
        StringBuilder parentRel = new StringBuilder();
        for (int i = 0; i < segs.size(); i++) {
            if (i > 0) parentRel.append('/');
            parentRel.append(segs.get(i));
        }
        if (segs.size() > 0 && !mkdirs(ctx, parentRel.toString())) return false;

        Uri uri = docUri(ctx, rel);
        if (uri == null) {
            Uri parent = docUri(ctx, parentRel.toString());
            if (parent == null) return false;
            uri = createDoc(ctx, parent, guessMime(name), name);
            if (uri == null) return false;
        }
        OutputStream out = null;
        try {
            // "wt" = truncate。缺了它，新内容会接在旧内容后面（写一半的文件）。
            out = ctx.getContentResolver().openOutputStream(uri, "wt");
            if (out == null) return false;
            out.write(data);
            out.flush();
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "写文件失败(" + rel + "): " + t.getMessage());
            return false;
        } finally {
            if (out != null) try { out.close(); } catch (Throwable ignored) { }
        }
    }

    public static byte[] read(Context ctx, String rel) {
        Uri u = docUri(ctx, rel);
        if (u == null) return null;
        InputStream in = null;
        try {
            in = ctx.getContentResolver().openInputStream(u);
            if (in == null) return null;
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return bos.toByteArray();
        } catch (Throwable t) {
            Log.w(TAG, "读文件失败(" + rel + "): " + t.getMessage());
            return null;
        } finally {
            if (in != null) try { in.close(); } catch (Throwable ignored) { }
        }
    }

    /** 删除文件或目录（目录由 provider 负责递归）。 */
    public static boolean delete(Context ctx, String rel) {
        Uri u = docUri(ctx, rel);
        if (u == null) return false;
        try {
            return DocumentsContract.deleteDocument(ctx.getContentResolver(), u);
        } catch (Throwable t) {
            Log.w(TAG, "删除失败(" + rel + "): " + t.getMessage());
            return false;
        }
    }

    /**
     * 重命名。
     *
     * <p>只支持<b>同父目录</b>改名：SAF 的 {@code renameDocument} 不能跨目录移动。
     * dsh 的原子写是「{@code foo.tmp} → {@code foo}」，正好落在这一档。
     */
    public static boolean rename(Context ctx, String rel, String toRel) {
        List<String> from = split(rel);
        List<String> to = split(toRel);
        if (from.isEmpty() || to.isEmpty()) return false;
        String newName = to.get(to.size() - 1);
        // 父路径必须相同
        if (from.size() != to.size()) return false;
        for (int i = 0; i < from.size() - 1; i++) {
            if (!from.get(i).equals(to.get(i))) return false;
        }
        Uri u = docUri(ctx, rel);
        if (u == null) return false;
        try {
            Uri renamed = DocumentsContract.renameDocument(ctx.getContentResolver(), u, newName);
            return renamed != null;
        } catch (Throwable t) {
            Log.w(TAG, "重命名失败(" + rel + " → " + newName + "): " + t.getMessage());
            return false;
        }
    }

    private static String guessMime(String name) {
        int i = name.lastIndexOf('.');
        if (i >= 0 && i < name.length() - 1) {
            String m = android.webkit.MimeTypeMap.getSingleton()
                .getMimeTypeFromExtension(name.substring(i + 1).toLowerCase());
            if (m != null) return m;
        }
        return "application/octet-stream";
    }
}
