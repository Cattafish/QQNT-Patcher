package com.tencent.qqnt.patch;

import android.content.Context;
import java.io.File;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ConfigManager {

    private static final String TAG = "Config";

    public static final String VERSION = "v0.1.0";
    public static final String GITHUB_REPO = "Cattafish/QQNT-Patcher";
    public static final String TG_CHANNEL_URL = "https://t.me/ZcraftMod";
    public static final String GITHUB_REPO_URL = "https://github.com/" + GITHUB_REPO;
    public static final String UPDATE_API_URL = "https://qqnt-patcher.zcraft.dpdns.org";

    private static final String FLAG_DEBUG_LOG_ON     = "zzz_debug_log_on";
    private static final String FLAG_HAS_NEW_VERSION  = "zzz_has_new_version";
    private static final String PREFIX_PLUGIN_ON      = "zzz_plugin_on_";

    private static final Map<String, Boolean> sFlagCache = new ConcurrentHashMap<>();
    private static final ExecutorService sDiskExecutor = Executors.newSingleThreadExecutor();
    private static volatile boolean sCacheLoaded = false;
    private static boolean sColdStartChecked = false;

    private static File getFilesDir() {
        Context ctx = AppContext.get();
        return ctx != null ? ctx.getFilesDir() : null;
    }

    private static void ensureCacheLoaded() {
        if (sCacheLoaded) return;
        synchronized (sFlagCache) {
            if (sCacheLoaded) return;
            File dir = getFilesDir();
            if (dir != null && dir.exists()) {
                File[] files = dir.listFiles();
                if (files != null) {
                    for (File f : files) {
                        if (f.isFile() && f.getName().startsWith("zzz_")) {
                            sFlagCache.put(f.getName(), Boolean.TRUE);
                        }
                    }
                }
            }
            sCacheLoaded = true;
        }
    }

    public static synchronized void triggerColdStartUpdateCheck() {
        if (sColdStartChecked) return;
        sColdStartChecked = true;

        ensureCacheLoaded();
        PLog.i("Core", "QQNT-Patcher " + VERSION + " 引擎冷启动初始化完成");

        UpdateHelper.checkUpdateSilent();

        try {
            Context ctx = AppContext.get();
            if (ctx != null) {
                AppContext.init(ctx);
                ModuleManager.initAll(ctx);
                com.tencent.qqnt.patch.plugin.PluginManager.init(ctx);
            }
        } catch (Throwable t) {
            PLog.e("Core", "启动引擎异常", t);
        }
    }

    public static boolean isModuleEnabled(String moduleId, boolean defValue) {
        String flagOff = "zzz_mod_off_" + moduleId;
        String flagOn = "zzz_mod_on_" + moduleId;
        if (defValue) {
            return !hasFlag(flagOff);
        } else {
            return hasFlag(flagOn);
        }
    }

    public static void setModuleEnabled(String moduleId, boolean enabled) {
        setFlag("zzz_mod_off_" + moduleId, !enabled);
        setFlag("zzz_mod_on_" + moduleId, enabled);
        PLog.i("Config", "模块 [" + moduleId + "] 状态更新为: " + (enabled ? "开启" : "关闭"));
    }

    public static boolean isFloatingBallEnabled() {
        return isModuleEnabled("floating_ball", true);
    }

    public static void setFloatingBallEnabled(boolean enabled) {
        setModuleEnabled("floating_ball", enabled);
    }

    public static boolean isAntiRevokeEnabled() {
        return isModuleEnabled("anti_revoke", true);
    }

    public static void setAntiRevokeEnabled(boolean enabled) {
        setModuleEnabled("anti_revoke", enabled);
    }

    public static boolean isFlashPicDecryptEnabled() {
        return isModuleEnabled("flash_pic", true);
    }

    public static void setFlashPicDecryptEnabled(boolean enabled) {
        setModuleEnabled("flash_pic", enabled);
    }

    public static boolean isMeowEnabled() {
        return isModuleEnabled("meow_helper", false);
    }

    public static void setMeowEnabled(boolean enabled) {
        setModuleEnabled("meow_helper", enabled);
    }

    public static boolean isPluginEnabled(String pluginId) {
        if (pluginId == null || pluginId.isEmpty()) return false;
        return hasFlag(PREFIX_PLUGIN_ON + pluginId);
    }

    public static void setPluginEnabled(String pluginId, boolean enabled) {
        if (pluginId == null || pluginId.isEmpty()) return;
        setFlag(PREFIX_PLUGIN_ON + pluginId, enabled);
    }

    public static boolean hasNewVersion() {
        return hasFlag(FLAG_HAS_NEW_VERSION);
    }

    public static void setHasNewVersion(boolean hasNew) {
        setFlag(FLAG_HAS_NEW_VERSION, hasNew);
    }

    public static boolean isDebugLogEnabled() {
        return hasFlag(FLAG_DEBUG_LOG_ON);
    }

    public static void setDebugLogEnabled(boolean enabled) {
        setFlag(FLAG_DEBUG_LOG_ON, enabled);
        PLog.i("Config", "调试日志输出已" + (enabled ? "开启" : "关闭"));
    }

    public static boolean hasFlag(String flagName) {
        ensureCacheLoaded();
        Boolean cached = sFlagCache.get(flagName);
        if (cached != null) return cached;

        File dir = getFilesDir();
        if (dir == null) return false;
        boolean exists = new File(dir, flagName).exists();
        sFlagCache.put(flagName, exists);
        return exists;
    }

    public static void setFlag(String flagName, boolean present) {
        sFlagCache.put(flagName, present);
        sDiskExecutor.execute(() -> {
            File dir = getFilesDir();
            if (dir == null) return;
            try {
                File file = new File(dir, flagName);
                if (present) {
                    if (!file.exists()) file.createNewFile();
                } else {
                    if (file.exists()) file.delete();
                }
            } catch (Throwable ignored) {}
        });
    }
}
