// 编译期桩（compile-only scaffolding）—— 不参与打包与运行，详见本目录说明。
package androidx.core.content;

import android.content.Context;
import android.net.Uri;

import java.io.File;

/** 桩：androidx.core.content.FileProvider */
public final class FileProvider {
    public static Uri getUriForFile(Context context, String authority, File file) {
        return null;
    }
}
