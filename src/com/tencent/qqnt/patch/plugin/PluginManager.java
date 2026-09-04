package com.tencent.qqnt.patch.plugin;

import android.content.Context;
import android.os.Environment;
import android.util.Log;

import com.tencent.qqnt.kernel.nativeinterface.MsgElement;
import com.tencent.qqnt.kernel.nativeinterface.MsgRecord;
import com.tencent.qqnt.kernel.nativeinterface.TextElement;
import com.tencent.qqnt.patch.ConfigManager;
import com.tencent.qqnt.patch.plugin.bean.MsgData;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class PluginManager {

    private static final String TAG = "QQ_DEBUG";
    private static volatile boolean sInitialized = false;
    private static final List<PluginCompiler> sLoadedPlugins = new CopyOnWriteArrayList<>();
    private static final ExecutorService sWorkerPool = Executors.newCachedThreadPool();
    private static volatile ClassLoader sBshClassLoader = null;

    private static final Set<Long> sHandledMsgIds = Collections.synchronizedSet(
            Collections.newSetFromMap(new LinkedHashMap<Long, Boolean>(100, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Long, Boolean> eldest) {
                    return size() > 300;
                }
            })
    );

    public static class PluginItem {
        public String id;
        public String name;
        public File dir;
        public boolean isEnabled;
        public boolean isRunning;
        public Map<String, String> menuItems = new LinkedHashMap<>();
    }

    public static void init(final Context context) {
        if (sInitialized || context == null) return;
        sInitialized = true;
        Log.i(TAG, "[PluginManager] 收到引擎初始化指令...");
        reloadAll(context);
    }

    public static List<PluginItem> scanAllPlugins(Context context) {
        List<PluginItem> items = new ArrayList<>();
        File pluginsDir = getPluginsStorageDir(context);
        if (!pluginsDir.exists()) return items;

        File[] subDirs = pluginsDir.listFiles();
        if (subDirs == null) return items;

        for (File dir : subDirs) {
            if (dir.isDirectory()) {
                File script = new File(dir, "main.java");
                if (script.exists()) {
                    PluginItem item = new PluginItem();
                    item.id = dir.getName();
                    item.dir = dir;
                    item.name = item.id;
                    item.isEnabled = ConfigManager.isPluginEnabled(item.id);

                    File propFile = new File(dir, "info.prop");
                    if (propFile.exists()) {
                        try (InputStreamReader isr = new InputStreamReader(new FileInputStream(propFile), StandardCharsets.UTF_8)) {
                            Properties p = new Properties();
                            p.load(isr);
                            String n = p.getProperty("pluginName");
                            if (n != null && !n.trim().isEmpty()) {
                                item.name = n.trim();
                            }
                        } catch (Throwable ignored) {}
                    }

                    for (PluginCompiler compiler : sLoadedPlugins) {
                        if (item.id.equals(compiler.getPluginId()) && compiler.isRunning()) {
                            item.isRunning = true;
                            item.menuItems.putAll(compiler.getMenuItems());
                            break;
                        }
                    }
                    items.add(item);
                }
            }
        }
        return items;
    }

    public static void setPluginActive(final Context context, final String pluginId, final boolean enabled) {
        ConfigManager.setPluginEnabled(pluginId, enabled);
        sWorkerPool.execute(() -> {
            if (enabled) {
                File pluginsDir = getPluginsStorageDir(context);
                File dir = new File(pluginsDir, pluginId);
                if (dir.isDirectory() && new File(dir, "main.java").exists()) {
                    ClassLoader bshLoader = getOrCreateBshClassLoader(context);
                    if (bshLoader != null) {
                        PluginCompiler compiler = new PluginCompiler(context, dir, bshLoader);
                        if (compiler.start()) {
                            sLoadedPlugins.add(compiler);
                            Log.i(TAG, "[PluginManager] 动态开启脚本成功: " + pluginId);
                        }
                    }
                }
            } else {
                for (PluginCompiler compiler : sLoadedPlugins) {
                    if (pluginId.equals(compiler.getPluginId())) {
                        compiler.stop();
                        sLoadedPlugins.remove(compiler);
                        Log.i(TAG, "[PluginManager] 动态卸载脚本成功: " + pluginId);
                    }
                }
            }
        });
    }

    public static void invokePluginMenu(final String pluginId, final String callback, final int cType, final String peerUin, final String name) {
        sWorkerPool.execute(() -> {
            for (PluginCompiler compiler : sLoadedPlugins) {
                if (pluginId.equals(compiler.getPluginId()) && compiler.isRunning()) {
                    compiler.invokeMenuItem(callback, cType, peerUin, name);
                    return;
                }
            }
        });
    }

    public static void dispatchRecvMsg(final List<?> msgList) {
        if (msgList == null || msgList.isEmpty() || sLoadedPlugins.isEmpty()) return;
        sWorkerPool.execute(() -> {
            try {
                for (Object obj : msgList) {
                    if (obj instanceof MsgRecord) {
                        MsgRecord record = (MsgRecord) obj;
                        if (record.msgId > 0 && !sHandledMsgIds.add(record.msgId)) {
                            continue;
                        }
                        MsgData msgData = new MsgData(record);
                        for (PluginCompiler compiler : sLoadedPlugins) {
                            compiler.onMsg(msgData);
                        }
                    }
                }
            } catch (Throwable ignored) {}
        });
    }

    public static void dispatchSendMsg(ArrayList elements) {
        if (elements == null || elements.isEmpty() || sLoadedPlugins.isEmpty()) return;
        try {
            for (Object obj : elements) {
                if (obj instanceof MsgElement) {
                    MsgElement msgElem = (MsgElement) obj;
                    TextElement textElem = msgElem.textElement;
                    if (textElem != null && textElem.content != null && !textElem.content.isEmpty()) {
                        String current = textElem.content;
                        for (PluginCompiler compiler : sLoadedPlugins) {
                            current = compiler.getMsg(current);
                        }
                        textElem.content = current;
                    }
                }
            }
        } catch (Throwable ignored) {}
    }

    private static synchronized ClassLoader getOrCreateBshClassLoader(Context context) {
        if (sBshClassLoader != null) return sBshClassLoader;
        try {
            File codeCache = context.getCodeCacheDir();
            if (codeCache == null) {
                codeCache = context.getFilesDir();
            }

            File customDex = new File(getPluginsStorageDir(context).getParentFile(), "bsh.dex");
            File targetDex = new File(codeCache, "bsh_engine.dex");

            if (customDex.exists() && customDex.length() > 0) {
                copyFile(customDex, targetDex);
            } else {
                if (!targetDex.exists() || targetDex.length() == 0) {
                    Log.i(TAG, "[PluginManager] 正在解压内置 assets/bsh.dex 引擎...");
                    try (InputStream is = context.getAssets().open("bsh.dex");
                         OutputStream os = new FileOutputStream(targetDex)) {
                        byte[] buf = new byte[8192];
                        int n;
                        while ((n = is.read(buf)) != -1) os.write(buf, 0, n);
                    }
                }
            }

            File optDir = new File(codeCache, "bsh_opt");
            if (!optDir.exists()) optDir.mkdirs();

            // ★ 核心突破：构建具备双向直通能力的智能桥接 Parent
            final Context appCtx = context.getApplicationContext() != null ? context.getApplicationContext() : context;
            ClassLoader bshParent = new ClassLoader(ClassLoader.getSystemClassLoader()) {
                @Override
                protected Class<?> findClass(String name) throws ClassNotFoundException {
                    // 1. 优先从动态脚本类注册表返回 (IReceiver, FunProtoData 等，0纳秒直出)
                    Class<?> scriptClz = FixClassLoader.getScriptClass(name);
                    if (scriptClz != null) {
                        return scriptClz;
                    }

                    // 2. 隔离并阻止 QQ 残缺 protobuf，确保使用 bsh.dex 的完整 protobuf 库
                    if (name.startsWith("com.google.protobuf.")) {
                        throw new ClassNotFoundException(name);
                    }

                    // 3. 查宿主 ClassLoader (QQ 与 helper dex，满足 QQCurrentEnv 等)
                    try {
                        return appCtx.getClassLoader().loadClass(name);
                    } catch (Throwable ignored) {}

                    throw new ClassNotFoundException(name);
                }
            };

            Class<?> dexLoaderClass = Class.forName("dalvik.system.DexClassLoader");
            Constructor<?> ctor = dexLoaderClass.getConstructor(
                    String.class, String.class, String.class, ClassLoader.class
            );
            sBshClassLoader = (ClassLoader) ctor.newInstance(
                    targetDex.getAbsolutePath(),
                    optDir.getAbsolutePath(),
                    null,
                    bshParent
            );
            Log.i(TAG, "[PluginManager] 独立完整版引擎装载成功，大小: " + targetDex.length() + " 字节");
            return sBshClassLoader;
        } catch (Throwable t) {
            Log.e(TAG, "[PluginManager] 动态加载 bsh.dex 失败: ", t);
            return null;
        }
    }

    public static void stopAllPlugins() {
        List<PluginCompiler> copyList = new ArrayList<>(sLoadedPlugins);
        sLoadedPlugins.clear();
        for (PluginCompiler compiler : copyList) {
            try {
                compiler.stop();
            } catch (Throwable t) {
                Log.w(TAG, "[PluginManager] 停止脚本异常: " + compiler.getPluginId(), t);
            }
        }
    }

    public static void reloadAll(final Context context) {
        sWorkerPool.execute(() -> {
            try {
                Log.i(TAG, "[PluginManager] 开始全量扫描与重载脚本...");
                stopAllPlugins();

                ClassLoader loader = getOrCreateBshClassLoader(context);
                if (loader == null) return;

                File pluginsDir = getPluginsStorageDir(context);
                if (!pluginsDir.exists()) pluginsDir.mkdirs();

                File[] subDirs = pluginsDir.listFiles();
                if (subDirs == null) return;

                int count = 0;
                for (File dir : subDirs) {
                    if (dir.isDirectory()) {
                        String id = dir.getName();
                        File mainScript = new File(dir, "main.java");
                        if (mainScript.exists() && ConfigManager.isPluginEnabled(id)) {
                            PluginCompiler compiler = new PluginCompiler(context, dir, loader);
                            if (compiler.start()) {
                                sLoadedPlugins.add(compiler);
                                count++;
                            }
                        }
                    }
                }
                Log.i(TAG, "[PluginManager] 全部脚本扫描重载完成，当前运行数: " + count);
            } catch (Throwable t) {
                Log.e(TAG, "[PluginManager] reloadAll 异常", t);
            }
        });
    }

    public static File getPluginsStorageDir(Context context) {
        File mediaDir = null;
        try {
            File[] mediaDirs = context.getExternalMediaDirs();
            if (mediaDirs != null && mediaDirs.length > 0 && mediaDirs[0] != null) {
                mediaDir = mediaDirs[0];
            }
        } catch (Throwable ignored) {}

        if (mediaDir == null) {
            mediaDir = new File(Environment.getExternalStorageDirectory(), "Android/media/" + context.getPackageName());
        }
        return new File(mediaDir, "zzz/plugins");
    }

    private static void copyFile(File src, File dst) throws Exception {
        try (InputStream in = new FileInputStream(src);
             OutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        }
    }
}
