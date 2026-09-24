package com.tencent.qqnt.patch.modules;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import com.tencent.qqnt.kernel.nativeinterface.FileElement;
import com.tencent.qqnt.kernel.nativeinterface.MsgElement;
import com.tencent.qqnt.patch.AppContext;
import com.tencent.qqnt.patch.ConfigManager;
import com.tencent.qqnt.patch.IPatchModule;
import com.tencent.qqnt.patch.PLog;

import java.io.File;
import java.util.ArrayList;

public class AutoRemarkApkModule implements IPatchModule {

    private static final String TAG = "AutoRemarkApk";

    @Override public String getId() { return "auto_remark_apk"; }
    @Override public String getName() { return "上传 APK 自动重命名"; }
    @Override public String getSubName() { return "上传的文件将重命名为：应用名_版本号.APK"; }
    @Override public boolean defaultEnabled() { return false; }

    @Override
    public void onSendMsg(ArrayList<MsgElement> elements) {
        if (!isEnabled()) return;
        if (elements == null || elements.isEmpty()) return;
        Context context = AppContext.get();
        if (context == null) return;

        for (MsgElement elem : elements) {
            if (elem != null && elem.fileElement != null) {
                FileElement fe = elem.fileElement;
                if (isCloudOrForwardedFile(fe)) {
                    continue;
                }

                if (fe.fileName != null && fe.fileName.toLowerCase().endsWith(".apk")) {
                    if (fe.filePath != null && !fe.filePath.isEmpty()) {
                        File localFile = new File(fe.filePath);
                        if (localFile.exists() && localFile.isFile()) {
                            renameApkFile(fe, localFile, context);
                        }
                    }
                }
            }
        }
    }

    private boolean isCloudOrForwardedFile(FileElement fe) {
        if (fe == null) return true;
        if (fe.fileUuid != null && !fe.fileUuid.trim().isEmpty()) return true;
        if (fe.fileSubId != null && !fe.fileSubId.trim().isEmpty()) return true;
        if (fe.filePath == null || fe.filePath.trim().isEmpty()) return true;
        try {
            File f = new File(fe.filePath);
            if (!f.exists() || !f.isFile()) return true;
        } catch (Throwable ignored) {
            return true;
        }
        return false;
    }

    private void renameApkFile(FileElement fileElement, File localFile, Context context) {
        try {
            String originalName = fileElement.fileName;
            String parsedName = null;

            PackageManager pm = context.getPackageManager();
            PackageInfo packageInfo = pm.getPackageArchiveInfo(localFile.getAbsolutePath(), PackageManager.GET_META_DATA);

            if (packageInfo != null && packageInfo.applicationInfo != null) {
                ApplicationInfo appInfo = packageInfo.applicationInfo;
                appInfo.sourceDir = localFile.getAbsolutePath();
                appInfo.publicSourceDir = localFile.getAbsolutePath();

                CharSequence label = appInfo.loadLabel(pm);
                String appName = label != null ? label.toString() : "";
                String versionName = packageInfo.versionName != null ? packageInfo.versionName : "未知版本";

                String safeAppName = appName.replaceAll("[\\\\/:*?\"<>|]", "").trim();
                if (!safeAppName.isEmpty()) {
                    parsedName = safeAppName + "_" + versionName + ".APK";
                }
            }

            if (parsedName != null) {
                fileElement.fileName = parsedName;
            } else if (originalName != null && !originalName.isEmpty()) {
                fileElement.fileName = originalName.replaceAll("(?i)\\.apk$", ".APK");
            } else {
                fileElement.fileName = "应用_" + System.currentTimeMillis() + ".APK";
            }

            PLog.i(TAG, "[重命名] [" + originalName + "] -> [" + fileElement.fileName + "]");
        } catch (Throwable t) {
            PLog.e(TAG, "[重命名异常]", t);
        }
    }

    public static void onDispatchRespMsg(Object msfMessagePair) {
    }
}
