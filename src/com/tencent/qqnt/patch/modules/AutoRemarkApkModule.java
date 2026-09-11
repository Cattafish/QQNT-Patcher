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
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

public class AutoRemarkApkModule implements IPatchModule {

    private static final String TAG = "AutoRemarkApk";
    private static final String CMD_FILE_UPLOAD = "OidbSvcTrpcTcp.0xe37_800";

    @Override public String getId() { return "auto_remark_apk"; }
    @Override public String getName() { return "上传 APK 自动重命名 (应用名_版本号.APK)"; }
    @Override public boolean defaultEnabled() { return true; }

    @Override
    public void onSendMsg(ArrayList<MsgElement> elements) {
        if (!isEnabled()) return;
        if (elements == null || elements.isEmpty()) return;
        Context context = AppContext.get();
        if (context == null) return;

        for (MsgElement elem : elements) {
            if (elem != null && elem.fileElement != null) {
                FileElement fe = elem.fileElement;
                if (fe.fileName != null && fe.fileName.toLowerCase().endsWith(".apk")) {
                    renameApkFile(fe, context);
                }
            }
        }
    }

    private void renameApkFile(FileElement fileElement, Context context) {
        try {
            String filePath = fileElement.filePath;
            String originalName = fileElement.fileName;
            String parsedName = null;

            if (filePath != null && !filePath.isEmpty()) {
                File file = new File(filePath);
                if (file.exists()) {
                    PackageManager pm = context.getPackageManager();
                    PackageInfo packageInfo = pm.getPackageArchiveInfo(filePath, PackageManager.GET_META_DATA);

                    if (packageInfo != null && packageInfo.applicationInfo != null) {
                        ApplicationInfo appInfo = packageInfo.applicationInfo;
                        appInfo.sourceDir = filePath;
                        appInfo.publicSourceDir = filePath;

                        CharSequence label = appInfo.loadLabel(pm);
                        String appName = label != null ? label.toString() : "";
                        String versionName = packageInfo.versionName != null ? packageInfo.versionName : "未知版本";

                        String safeAppName = appName.replaceAll("[\\\\/:*?\"<>|]", "").trim();
                        if (!safeAppName.isEmpty()) {
                            parsedName = safeAppName + "_" + versionName + ".APK";
                        }
                    }
                }
            }

            if (parsedName != null) {
                fileElement.fileName = parsedName;
            } else if (originalName != null && !originalName.isEmpty()) {
                fileElement.fileName = originalName.replaceAll("(?i)\\.apk$", ".APK");
            } else {
                fileElement.fileName = "应用_" + System.currentTimeMillis() + ".APK";
            }

            PLog.i(TAG, "已自动重命名待上传的 APK: [" + originalName + "] -> [" + fileElement.fileName + "]");
        } catch (Throwable t) {
            PLog.e(TAG, "解析 APK 异常", t);
            if (fileElement.fileName != null) {
                fileElement.fileName = fileElement.fileName.replaceAll("(?i)\\.apk$", ".APK");
            }
        }
    }

    /**
     * 拦截 0xe37_800 文件服务回包，动态抹平腾讯后台强制追加的 .1 后缀
     */
    public static void onDispatchRespMsg(Object msfMessagePair) {
        if (msfMessagePair == null) return;
        // ★ 核心受控：检查开关，如果关闭则直接放行官方原版逻辑
        if (!ConfigManager.isModuleEnabled("auto_remark_apk", true)) return;

        try {
            Class<?> pairClz = msfMessagePair.getClass();
            Field fromMsgField = pairClz.getField("fromServiceMsg");
            Object fromServiceMsg = fromMsgField.get(msfMessagePair);
            if (fromServiceMsg == null) return;

            Method getCmdM = fromServiceMsg.getClass().getMethod("getServiceCmd");
            String cmd = (String) getCmdM.invoke(fromServiceMsg);

            if (CMD_FILE_UPLOAD.equals(cmd)) {
                Method getWupBufM = fromServiceMsg.getClass().getMethod("getWupBuffer");
                byte[] wupBuf = (byte[]) getWupBufM.invoke(fromServiceMsg);
                if (wupBuf != null && wupBuf.length > 4) {
                    byte[] fixedBuf = fixApkDotOneSuffix(wupBuf);
                    if (fixedBuf != null) {
                        Method putWupBufM = fromServiceMsg.getClass().getMethod("putWupBuffer", byte[].class);
                        putWupBufM.invoke(fromServiceMsg, (Object) fixedBuf);
                        PLog.i(TAG, "已成功从 0xe37_800 回包中剥离 .apk.1 污染后缀！");
                    }
                }
            }
        } catch (Throwable ignored) {}
    }

    private static byte[] fixApkDotOneSuffix(byte[] data) {
        try {
            String content = new String(data, StandardCharsets.ISO_8859_1);
            if (content.contains(".apk.") || content.contains(".APK.")) {
                String replaced = content.replaceAll("(?i)\\.apk\\.\\d+", ".APK");
                return replaced.getBytes(StandardCharsets.ISO_8859_1);
            }
        } catch (Throwable ignored) {}
        return null;
    }
}
