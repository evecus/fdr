package com.firefoxdl.mod;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.File;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * LSPosed / Xposed 模块入口：把 Firefox 的下载保存目录重定向到自定义文件夹。
 *
 * Firefox 的下载有两条路径，这里分别处理：
 * 1. 系统下载管理器：Firefox 通过 AndroidDownloadManager 调用
 *    android.app.DownloadManager.Request#setDestinationInExternalPublicDir(dirType, fileName)
 *    保存文件（公开下载，所有 Android 版本）。把 dirType 换成用户目录即可重定向。
 * 2. 内置下载服务：Android 10+ 的私密下载走 AbstractFetchDownloadService，
 *    它向 MediaStore.Downloads 插入记录且不写 RELATIVE_PATH（默认落到 Download/）。
 *    在 insert 时补上 RELATIVE_PATH 即可重定向。
 */
public class XposedEntry implements IXposedHookLoadPackage {

    public static final String MODULE_PACKAGE = "com.firefoxdl.mod";
    public static final String PREFS_NAME = "fdr_prefs";
    public static final String KEY_FOLDER = "download_folder";

    private static final String[] FIREFOX_PACKAGES = {
            "org.mozilla.firefox",
            "org.mozilla.firefox_beta",
            "org.mozilla.fenix",
            "org.mozilla.fennec_fdroid",
            "org.mozilla.firefox_preview",
    };

    @Override
    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lpparam) {
        if (!isFirefox(lpparam.packageName)) {
            return;
        }
        try {
            hookDownloadManager();
            hookMediaStoreInsert();
            hookLegacyDirectory();
            String folder = currentFolder();
            XposedBridge.log("[FirefoxDownloadDir] hooks installed for " + lpparam.packageName
                    + ", folder=" + (folder.isEmpty()
                    ? "(未配置，或偏好文件不可读：请确认 LSPosed>=1.8.x 已启用本模块并在设置页填好目录)"
                    : "/storage/emulated/0/" + folder));
        } catch (Throwable t) {
            XposedBridge.log(t);
        }
    }

    private static boolean isFirefox(String packageName) {
        for (String pkg : FIREFOX_PACKAGES) {
            if (pkg.equals(packageName)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 主路径钩子：拦截 DownloadManager.Request.setDestinationInExternalPublicDir，
     * 把 dirType（默认 "Download"）替换成用户配置的目录名。
     * DownloadManager 下载流程在 DownloadProvider（系统进程）中会自动创建目标目录。
     */
    private static void hookDownloadManager() {
        XposedHelpers.findAndHookMethod(
                "android.app.DownloadManager$Request",
                null,
                "setDestinationInExternalPublicDir",
                String.class, String.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        String folder = currentFolder();
                        if (folder.isEmpty()) {
                            return;
                        }
                        param.args[0] = folder;
                        XposedBridge.log("[FirefoxDownloadDir] DownloadManager dirType -> " + folder);
                    }
                });
    }

    /**
     * 旧版路径钩子：老版本 Firefox（或 Android 9- 的私密下载）会调用
     * Environment.getExternalStoragePublicDirectory("Download") 来计算保存路径。
     * 只在参数为 "Download" 时重定向，不影响其他目录用途。
     */
    private static void hookLegacyDirectory() {
        XposedHelpers.findAndHookMethod(
                Environment.class,
                "getExternalStoragePublicDirectory",
                String.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        String type = (String) param.args[0];
                        if (type == null || !"Download".equals(type)) {
                            return;
                        }
                        String folder = currentFolder();
                        if (folder.isEmpty()) {
                            return;
                        }
                        param.setResult(new File(Environment.getExternalStorageDirectory(), folder));
                        XposedBridge.log("[FirefoxDownloadDir] getExternalStoragePublicDirectory -> " + folder);
                    }
                });
    }

    /**
     * 备用路径钩子：拦截 MediaStore.Downloads 的 insert，写入用户配置的 RELATIVE_PATH。
     * 仅处理 authority 为 media 且路径以 external 开头、包含 downloads 段的插入，不影响其他媒体库。
     */
    private static void hookMediaStoreInsert() {
        XposedHelpers.findAndHookMethod(
                ContentResolver.class,
                "insert",
                Uri.class, ContentValues.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        Uri uri = (Uri) param.args[0];
                        ContentValues values = (ContentValues) param.args[1];
                        if (uri == null || values == null || !isDownloadsUri(uri)) {
                            return;
                        }
                        String folder = currentFolder();
                        if (folder.isEmpty()) {
                            return;
                        }
                        values.put(MediaStore.MediaColumns.RELATIVE_PATH, folder + "/");
                        XposedBridge.log("[FirefoxDownloadDir] MediaStore.Downloads RELATIVE_PATH -> " + folder);
                    }
                });
    }

    private static boolean isDownloadsUri(Uri uri) {
        String authority = uri.getAuthority();
        String path = uri.getPath();
        return authority != null
                && authority.equals("media")
                && path != null
                && path.startsWith("/external")
                && path.contains("/downloads");
    }

    /**
     * 读取用户在设置页填写的目录名；为空表示不重定向。
     * XSharedPreferences 由 LSPosed 提供，可读取模块自己的偏好设置。
     */
    private static String currentFolder() {
        try {
            XSharedPreferences prefs = new XSharedPreferences(MODULE_PACKAGE, PREFS_NAME);
            prefs.makeWorldReadable();
            prefs.reload();
            return normalizeFolder(prefs.getString(KEY_FOLDER, ""));
        } catch (Throwable t) {
            XposedBridge.log("[FirefoxDownloadDir] read prefs failed: " + t);
            return "";
        }
    }

    /**
     * 兼容各种用户输入：去掉 /sdcard、/storage/emulated/0 等前缀，得到相对目录名。
     */
    private static String normalizeFolder(String raw) {
        if (raw == null) {
            return "";
        }
        String p = raw.trim().replace('\\', '/');
        if (p.isEmpty()) {
            return "";
        }
        p = p.replaceFirst("^/+", "");
        p = p.replaceFirst("^(storage/emulated/\\d+/|sdcard\\d*/|sdcard/|mnt/[^/]+/)", "");
        while (p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        return p;
    }
}
