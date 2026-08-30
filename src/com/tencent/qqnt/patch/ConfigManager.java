package com.tencent.qqnt.patch;

import android.content.Context;
import android.util.Log;
import java.io.File;

public class ConfigManager {

    private static final String TAG = "QQ_DEBUG";

    // ==========================================
    // 全局版本与自定义域名更新接口
    // ==========================================
    public static final String VERSION = "v0.1.0";
    public static final String GITHUB_REPO = "Cattafish/QQNT-Patcher";
    public static final String TG_CHANNEL_URL = "https://t.me/ZcraftMod";
    public static final String GITHUB_REPO_URL = "https://github.com/" + GITHUB_REPO;
    public static final String UPDATE_API_URL = "https://qqnt-patcher.zcraft.dpdns.org";

    // ==========================================
    // 标志文件名定义 (基于 Linux VFS 跨进程穿透)
    // ==========================================
    private static final String FLAG_ANTI_REVOKE_OFF  = "zzz_anti_revoke_off";
    private static final String FLAG_FLASH_PIC_OFF    = "zzz_flash_pic_off";
    private static final String FLAG_MEOW_ON          = "zzz_meow_helper_on";
    private static final String FLAG_DEBUG_LOG_ON     = "zzz_debug_log_on";
    private static final String FLAG_HAS_NEW_VERSION  = "zzz_has_new_version";

    private static File sFilesDir = null;
    private static boolean sColdStartChecked = false;

    private static synchronized File getFilesDir() {
        if (sFilesDir == null) {
            try {
                Class<?> appClass = Class.forName("com.tencent.qphone.base.util.BaseApplication");
                Context ctx = (Context) appClass.getMethod("getContext").invoke(null);
                if (ctx != null) {
                    sFilesDir = ctx.getFilesDir();
                }
            } catch (Throwable ignored) {}
        }
        return sFilesDir;
    }

    // === 【冷启动单次检测】 ===
    public static synchronized void triggerColdStartUpdateCheck() {
        if (sColdStartChecked) return;
        sColdStartChecked = true;
        UpdateHelper.checkUpdateSilent();
    }

    // === 【更新状态管理 (恢复动态检测)】 ===
    public static boolean hasNewVersion() {
        return hasFlag(FLAG_HAS_NEW_VERSION);
    }

    public static void setHasNewVersion(boolean hasNew) {
        setFlag(FLAG_HAS_NEW_VERSION, hasNew);
    }

    // === 【功能开关】 ===
    public static boolean isAntiRevokeEnabled() {
        return !hasFlag(FLAG_ANTI_REVOKE_OFF);
    }

    public static void setAntiRevokeEnabled(boolean enabled) {
        setFlag(FLAG_ANTI_REVOKE_OFF, !enabled);
    }

    public static boolean isFlashPicDecryptEnabled() {
        return !hasFlag(FLAG_FLASH_PIC_OFF);
    }

    public static void setFlashPicDecryptEnabled(boolean enabled) {
        setFlag(FLAG_FLASH_PIC_OFF, !enabled);
    }

    public static boolean isMeowEnabled() {
        return hasFlag(FLAG_MEOW_ON);
    }

    public static void setMeowEnabled(boolean enabled) {
        setFlag(FLAG_MEOW_ON, enabled);
    }

    public static boolean isDebugLogEnabled() {
        return hasFlag(FLAG_DEBUG_LOG_ON);
    }

    public static void setDebugLogEnabled(boolean enabled) {
        setFlag(FLAG_DEBUG_LOG_ON, enabled);
    }

    public static boolean hasFlag(String flagName) {
        File dir = getFilesDir();
        if (dir == null) return false;
        return new File(dir, flagName).exists();
    }

    public static void setFlag(String flagName, boolean present) {
        File dir = getFilesDir();
        if (dir == null) return;
        try {
            File file = new File(dir, flagName);
            if (present) {
                if (!file.exists()) file.createNewFile();
            } else {
                if (file.exists()) file.delete();
            }
        } catch (Throwable t) {
            Log.e(TAG, "设置配置标志异常: " + flagName, t);
        }
    }
}