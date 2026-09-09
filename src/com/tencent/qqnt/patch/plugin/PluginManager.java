package com.tencent.qqnt.patch.plugin;

import android.content.Context;
import android.os.Environment;
import android.util.Log;

import com.tencent.qqnt.kernel.nativeinterface.MsgElement;
import com.tencent.qqnt.kernel.nativeinterface.MsgRecord;
import com.tencent.qqnt.kernel.nativeinterface.TextElement;
import com.tencent.qqnt.patch.ConfigManager;
import com.tencent.qqnt.patch.PLog;
import com.tencent.qqnt.patch.plugin.bean.MsgData;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.nio.ByteBuffer;
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
        PLog.i("Plugin", "收到引擎初始化指令，开始载入插件...");
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
                            PLog.i("Plugin", "动态开启脚本成功: " + pluginId);
                        } else {
                            PLog.e("Plugin", "动态开启脚本失败: " + pluginId + "，启动过程异常");
                        }
                    } else {
                        PLog.e("Plugin", "动态开启脚本失败: 引擎 ClassLoader 为空");
                    }
                }
            } else {
                for (PluginCompiler compiler : sLoadedPlugins) {
                    if (pluginId.equals(compiler.getPluginId())) {
                        compiler.stop();
                        sLoadedPlugins.remove(compiler);
                        PLog.i("Plugin", "动态卸载脚本成功: " + pluginId);
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

    public static void invokeMsgMenuItem(final String pluginId, final String callback, final Object msgData) {
        sWorkerPool.execute(() -> {
            for (PluginCompiler compiler : sLoadedPlugins) {
                if (pluginId.equals(compiler.getPluginId()) && compiler.isRunning()) {
                    compiler.invokeMsgMenuItem(callback, msgData);
                    return;
                }
            }
        });
    }

    public static void dispatchChatInterface(final int cType, final String peerUin, final String name) {
        if (sLoadedPlugins.isEmpty()) return;
        sWorkerPool.execute(() -> {
            for (PluginCompiler compiler : sLoadedPlugins) {
                compiler.chatInterface(cType, peerUin, name);
            }
        });
    }

    public static void dispatchPaiYiPai(final String peerUin, final int cType, final String opUin) {
        if (sLoadedPlugins.isEmpty()) return;
        sWorkerPool.execute(() -> {
            for (PluginCompiler compiler : sLoadedPlugins) {
                compiler.onPaiYiPai(peerUin, cType, opUin);
            }
        });
    }

    public static void dispatchTroopShutUp(final String troopUin, final String memberUin, final long time, final String opUin) {
        if (sLoadedPlugins.isEmpty()) return;
        sWorkerPool.execute(() -> {
            for (PluginCompiler compiler : sLoadedPlugins) {
                compiler.shutUpGroup(troopUin, memberUin, time, opUin);
            }
        });
    }

    public static void dispatchTroopJoin(final String troopUin, final String memberUin) {
        if (sLoadedPlugins.isEmpty()) return;
        sWorkerPool.execute(() -> {
            for (PluginCompiler compiler : sLoadedPlugins) {
                compiler.joinGroup(troopUin, memberUin);
            }
        });
    }

    public static void dispatchTroopQuit(final String troopUin, final String memberUin) {
        if (sLoadedPlugins.isEmpty()) return;
        sWorkerPool.execute(() -> {
            for (PluginCompiler compiler : sLoadedPlugins) {
                compiler.quitGroup(troopUin, memberUin);
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
                        if (record.msgId > 0 && !sHandledMsgIds.add(record.msgId)) continue;
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
            final Context appCtx = context.getApplicationContext() != null ? context.getApplicationContext() : context;
            ClassLoader bshParent = new ClassLoader(ClassLoader.getSystemClassLoader()) {
                @Override
                protected Class<?> findClass(String name) throws ClassNotFoundException {
                    Class<?> scriptClz = FixClassLoader.getScriptClass(name);
                    if (scriptClz != null) return scriptClz;
                    if (name.startsWith("com.google.protobuf.")) throw new ClassNotFoundException(name);
                    try {
                        return appCtx.getClassLoader().loadClass(name);
                    } catch (Throwable ignored) {}
                    throw new ClassNotFoundException(name);
                }
            };

            // 1. 读取 bsh.dex 资源字节 (优先读取外部，次选 assets)
            byte[] dexBytes = null;
            File customDex = new File(getPluginsStorageDir(context).getParentFile(), "bsh.dex");
            if (customDex.exists() && customDex.length() > 0) {
                dexBytes = readFileToByteArray(customDex);
            }
            if (dexBytes == null || dexBytes.length == 0) {
                try (InputStream is = context.getAssets().open("bsh.dex");
                     ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = is.read(buf)) != -1) bos.write(buf, 0, n);
                    dexBytes = bos.toByteArray();
                }
            }

            if (dexBytes == null || dexBytes.length == 0) {
                PLog.e("Plugin", "无法获取 bsh.dex 字节码资源！");
                return null;
            }

            // 2. ★★★ 终极适配 Android 14/15/16: 优先使用 InMemoryDexClassLoader (纯内存加载) ★★★
            // 从 API 26 (Android 8.0) 原生支持，不向磁盘写任何 dex 文件，从原理上彻底免疫 Writable Dex 安全限制！
            try {
                Class<?> inMemoryClz = Class.forName("dalvik.system.InMemoryDexClassLoader");
                Constructor<?> inMemCtor = inMemoryClz.getConstructor(ByteBuffer.class, ClassLoader.class);
                ByteBuffer buffer = ByteBuffer.wrap(dexBytes);
                sBshClassLoader = (ClassLoader) inMemCtor.newInstance(buffer, bshParent);
                PLog.i("Plugin", "已通过 InMemoryDexClassLoader (纯内存) 加载引擎成功，完美适配 Android 16！");
                return sBshClassLoader;
            } catch (Throwable tMem) {
                PLog.w("Plugin", "内存加载降级，尝试只读磁盘加载: " + tMem.getMessage());
            }

            // 3. 降级模式：写到 code_cache 并强制设置 setReadOnly()
            File codeCache = context.getCodeCacheDir();
            if (codeCache == null) codeCache = context.getFilesDir();
            File targetDex = new File(codeCache, "bsh_engine.dex");

            if (targetDex.exists()) {
                targetDex.delete();
            }
            try (FileOutputStream fos = new FileOutputStream(targetDex)) {
                fos.write(dexBytes);
                fos.flush();
            }

            // ★ 必须声明为只读，避免 Android 14+ 抛出 SecurityException
            targetDex.setReadOnly();

            File optDir = new File(codeCache, "bsh_opt");
            if (!optDir.exists()) optDir.mkdirs();

            Class<?> dexLoaderClass = Class.forName("dalvik.system.DexClassLoader");
            Constructor<?> ctor = dexLoaderClass.getConstructor(String.class, String.class, String.class, ClassLoader.class);
            sBshClassLoader = (ClassLoader) ctor.newInstance(targetDex.getAbsolutePath(), optDir.getAbsolutePath(), null, bshParent);
            PLog.i("Plugin", "磁盘加载引擎就绪 (已启用 ReadOnly 保护)");
            return sBshClassLoader;
        } catch (Throwable t) {
            PLog.e("Plugin", "动态加载 bsh 脚本引擎致命异常: " + t.getMessage(), t);
            return null;
        }
    }

    private static byte[] readFileToByteArray(File f) {
        try (FileInputStream fis = new FileInputStream(f);
             ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = fis.read(buf)) != -1) bos.write(buf, 0, n);
            return bos.toByteArray();
        } catch (Throwable t) {
            return null;
        }
    }

    public static void stopAllPlugins() {
        List<PluginCompiler> copyList = new ArrayList<>(sLoadedPlugins);
        sLoadedPlugins.clear();
        for (PluginCompiler compiler : copyList) {
            try { compiler.stop(); } catch (Throwable ignored) {}
        }
    }

    public static void reloadAll(final Context context) {
        PLog.i("Plugin", "正在重新扫描与重载全部脚本...");
        sWorkerPool.execute(() -> {
            try {
                stopAllPlugins();
                ClassLoader loader = getOrCreateBshClassLoader(context);
                if (loader == null) {
                    PLog.e("Plugin", "重载中止: bsh 解释器 ClassLoader 为 null");
                    return;
                }

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
                PLog.i("Plugin", "全部脚本重载完成，当前生效运行中: " + count + " 个");
            } catch (Throwable t) {
                PLog.e("Plugin", "reloadAll 异常: " + t.getMessage(), t);
            }
        });
    }

    public static File getPluginsStorageDir(Context context) {
        File mediaDir = null;
        try {
            File[] mediaDirs = context.getExternalMediaDirs();
            if (mediaDirs != null && mediaDirs.length > 0 && mediaDirs[0] != null) mediaDir = mediaDirs[0];
        } catch (Throwable ignored) {}
        if (mediaDir == null) mediaDir = new File(Environment.getExternalStorageDirectory(), "Android/media/" + context.getPackageName());
        return new File(mediaDir, "zzz/plugins");
    }
}
