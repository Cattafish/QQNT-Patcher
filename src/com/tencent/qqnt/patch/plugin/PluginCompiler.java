package com.tencent.qqnt.patch.plugin;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.util.Log;
import com.tencent.qqnt.kernelpublic.nativeinterface.Contact;

import java.io.File;
import java.io.FileInputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class PluginCompiler {

    private static final String TAG = "QQ_DEBUG";
    private final Context mContext;
    private final File mPluginDir;
    private final String mPluginId;
    private final ClassLoader mBshClassLoader;
    private Object mInterpreter = null;
    private volatile boolean mIsRunning = false;
    private final Map<String, String> mMenuItems = new LinkedHashMap<>();
    private final FixClassLoader mFixClassLoader;

    private static final Map<String, MsgMenuItemInfo> sAllMsgMenuItems = new ConcurrentHashMap<>();

    public static class MsgMenuItemInfo {
        public String pluginId;
        public String name;
        public String callback;
        public int[] msgTypes;
        public MsgMenuItemInfo(String pId, String name, String cb, int[] types) {
            this.pluginId = pId; this.name = name; this.callback = cb; this.msgTypes = types;
        }
    }

    public static Map<String, MsgMenuItemInfo> getAllMsgMenuItems() {
        return Collections.unmodifiableMap(sAllMsgMenuItems);
    }

    public PluginCompiler(Context context, File pluginDir, ClassLoader bshClassLoader) {
        this.mContext = context;
        this.mPluginDir = pluginDir;
        this.mPluginId = pluginDir.getName();
        this.mBshClassLoader = bshClassLoader;
        this.mFixClassLoader = new FixClassLoader(context.getClassLoader(), bshClassLoader);
    }

    public String getPluginId() { return mPluginId; }
    public Map<String, String> getMenuItems() { return mMenuItems; }
    public FixClassLoader getClassLoader() { return mFixClassLoader; }
    public Object getInterpreter() { return mInterpreter; }

    public void addMenuItem(String name, String callback) {
        mMenuItems.put(name, callback);
        Log.i(TAG, "[PluginCompiler] 脚本 " + mPluginId + " 注册悬浮球菜单: " + name + " -> " + callback);
    }

    public void addMsgMenuItem(String name, String callback, int[] msgTypes) {
        String key = mPluginId + "_" + name;
        sAllMsgMenuItems.put(key, new MsgMenuItemInfo(mPluginId, name, callback, msgTypes));
        Log.i(TAG, "[PluginCompiler] 脚本 " + mPluginId + " 注册气泡长按菜单: " + name + " -> " + callback);
    }

    public void loadJava(String path) {
        if (mInterpreter == null) return;
        ClassLoader originalTCCL = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(mFixClassLoader);
        try {
            File f = new File(path);
            if (!f.exists()) return;
            long t0 = System.currentTimeMillis();
            String rawCode = readFileContent(f);
            Method evalMethod = mInterpreter.getClass().getMethod("eval", String.class);
            evalMethod.invoke(mInterpreter, rawCode);
            syncNewClassLoaders(f.getName().replace(".java", ""));
            Log.i(TAG, "[PluginCompiler] 成功载入关联文件: " + f.getName() + " (耗时 " + (System.currentTimeMillis() - t0) + "ms)");
        } catch (Throwable t) {
            Throwable realEx = (t instanceof InvocationTargetException) ? ((InvocationTargetException) t).getTargetException() : t;
            logError("loadJava 异常 [" + path + "]:\n" + getStackTrace(realEx));
        } finally {
            Thread.currentThread().setContextClassLoader(originalTCCL);
        }
    }

    private void syncNewClassLoaders(String expectedClassName) {
        try {
            Method getClassManagerM = mInterpreter.getClass().getMethod("getClassManager");
            Object cm = getClassManagerM.invoke(mInterpreter);
            if (cm != null) {
                Class<?> cur = cm.getClass();
                Field cacheField = null;
                while (cur != null && cacheField == null) {
                    try { cacheField = cur.getDeclaredField("absoluteClassCache"); }
                    catch (NoSuchFieldException e) { cur = cur.getSuperclass(); }
                }
                if (cacheField != null) {
                    cacheField.setAccessible(true);
                    Map<?, ?> cache = (Map<?, ?>) cacheField.get(cm);
                    if (cache != null) {
                        for (Map.Entry<?, ?> entry : cache.entrySet()) {
                            if (entry.getKey() instanceof String && entry.getValue() instanceof Class) {
                                String className = (String) entry.getKey();
                                Class<?> clazz = (Class<?>) entry.getValue();
                                FixClassLoader.registerScriptClass(className, clazz);
                                mFixClassLoader.addClassLoader(clazz.getClassLoader());
                            }
                        }
                    }
                }
            }
        } catch (Throwable ignored) {}
    }

    public synchronized boolean start() {
        if (mIsRunning) stop();
        mMenuItems.clear();

        File scriptFile = new File(mPluginDir, "main.java");
        if (!scriptFile.exists() || !scriptFile.isFile() || mBshClassLoader == null) return false;

        long tStart = System.currentTimeMillis();
        Log.i(TAG, "[PluginCompiler] 开始启动脚本: " + mPluginId);

        ClassLoader originalTCCL = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(mFixClassLoader);

        try {
            Class<?> interpClass = mBshClassLoader.loadClass("bsh.Interpreter");
            mInterpreter = interpClass.getDeclaredConstructor().newInstance();

            Method setClassLoaderMethod = interpClass.getMethod("setClassLoader", ClassLoader.class);
            setClassLoaderMethod.invoke(mInterpreter, mFixClassLoader);

            Method setMethod = interpClass.getMethod("set", String.class, Object.class);

            Context smartContext = new ContextWrapper(mContext) {
                @Override
                public Object getSystemService(String name) {
                    if ("window".equals(name) || (Context.class.getName().equals(name))) {
                        Activity act = com.tencent.qqnt.patch.AppContext.getCurrentActivity();
                        if (act != null) return act.getSystemService(Context.WINDOW_SERVICE);
                    }
                    return super.getSystemService(name);
                }
            };

            setMethod.invoke(mInterpreter, "context", smartContext);
            setMethod.invoke(mInterpreter, "classLoader", mFixClassLoader);
            setMethod.invoke(mInterpreter, "pluginPath", mPluginDir.getAbsolutePath());
            setMethod.invoke(mInterpreter, "pluginId", mPluginId);
            setMethod.invoke(mInterpreter, "myUin", MsgSender.getMyUin());

            PluginMethod api = new PluginMethod(mContext, mPluginDir);
            api.setCompiler(this);
            setMethod.invoke(mInterpreter, "api", api);

            try {
                Method getNameSpaceMethod = interpClass.getMethod("getNameSpace");
                Object nameSpace = getNameSpaceMethod.invoke(mInterpreter);

                Class<?> bshMethodClz = mBshClassLoader.loadClass("bsh.BshMethod");
                Constructor<?> bshMethodCtor = bshMethodClz.getConstructor(Method.class, Object.class);
                Method setMethodM = nameSpace.getClass().getMethod("setMethod", bshMethodClz);

                for (Method m : PluginMethod.class.getDeclaredMethods()) {
                    if (Modifier.isPublic(m.getModifiers()) && !m.getName().contains("$")) {
                        try {
                            Object bshM = bshMethodCtor.newInstance(m, api);
                            setMethodM.invoke(nameSpace, bshM);
                        } catch (Throwable ignored) {}
                    }
                }

                Method importObjectMethod = nameSpace.getClass().getMethod("importObject", Object.class);
                importObjectMethod.invoke(nameSpace, api);
            } catch (Throwable ignored) {}

            String rawCode = readFileContent(scriptFile);
            Method evalMethod = interpClass.getMethod("eval", String.class);
            evalMethod.invoke(mInterpreter, rawCode);

            mIsRunning = true;
            Log.i(TAG, "[PluginCompiler] 脚本 " + mPluginId + " 启动成功，耗时: " + (System.currentTimeMillis() - tStart) + "ms");
            return true;
        } catch (Throwable t) {
            Throwable realEx = (t instanceof InvocationTargetException) ? ((InvocationTargetException) t).getTargetException() : t;
            logError("脚本启动异常:\n" + getStackTrace(realEx));
            stop();
            return false;
        } finally {
            Thread.currentThread().setContextClassLoader(originalTCCL);
        }
    }

    private String readFileContent(File file) {
        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] b = new byte[(int) file.length()];
            fis.read(b);
            return new String(b, StandardCharsets.UTF_8);
        } catch (Throwable t) { return ""; }
    }

    public void chatInterface(int cType, String peerUin, String name) {
        invokeScriptMethod("chatInterface", new Class[]{int.class, String.class, String.class}, new Object[]{cType, peerUin, name});
    }

    public void onPaiYiPai(String peerUin, int chatType, String opUin) {
        invokeScriptMethod("onPaiYiPai", new Class[]{String.class, int.class, String.class}, new Object[]{peerUin, chatType, opUin});
    }

    public void shutUpGroup(String troopUin, String memberUin, long time, String opUin) {
        invokeScriptMethod("shutUpGroup", new Class[]{String.class, String.class, long.class, String.class}, new Object[]{troopUin, memberUin, time, opUin});
    }

    public void joinGroup(String troopUin, String memberUin) {
        invokeScriptMethod("joinGroup", new Class[]{String.class, String.class}, new Object[]{troopUin, memberUin});
    }

    public void quitGroup(String troopUin, String memberUin) {
        invokeScriptMethod("quitGroup", new Class[]{String.class, String.class}, new Object[]{troopUin, memberUin});
    }

    public void invokeMsgMenuItem(String callback, Object msgData) {
        invokeScriptMethod(callback, new Class[]{Object.class}, new Object[]{msgData});
    }

    private void invokeScriptMethod(String methodName, Class<?>[] types, Object[] args) {
        if (!mIsRunning || mInterpreter == null) return;
        ClassLoader originalTCCL = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(mFixClassLoader);
        try {
            Method getNameSpaceMethod = mInterpreter.getClass().getMethod("getNameSpace");
            Object nameSpace = getNameSpaceMethod.invoke(mInterpreter);
            Method getMethodM = nameSpace.getClass().getMethod("getMethod", String.class, Class[].class);
            Object targetMethod = getMethodM.invoke(nameSpace, methodName, types);
            if (targetMethod != null) {
                Method invokeM = targetMethod.getClass().getMethod("invoke", Object[].class, mInterpreter.getClass());
                invokeM.invoke(targetMethod, args, mInterpreter);
            }
        } catch (Throwable ignored) {}
        finally {
            Thread.currentThread().setContextClassLoader(originalTCCL);
        }
    }

    public void invokeMenuItem(String callback, int cType, String peerUin, String name) {
        if (!mIsRunning || mInterpreter == null) return;
        ClassLoader originalTCCL = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(mFixClassLoader);
        try {
            Method getNameSpaceMethod = mInterpreter.getClass().getMethod("getNameSpace");
            Object nameSpace = getNameSpaceMethod.invoke(mInterpreter);
            Method getMethodsMethod = nameSpace.getClass().getMethod("getMethods");
            Object[] methods = (Object[]) getMethodsMethod.invoke(nameSpace);

            Object targetMethod = null;
            Object[] invokeArgs = null;

            if (methods != null) {
                for (Object m : methods) {
                    Method getNameM = m.getClass().getMethod("getName");
                    String mName = (String) getNameM.invoke(m);
                    if (callback.equals(mName)) {
                        Method getParamTypesM = m.getClass().getMethod("getParameterTypes");
                        Class<?>[] pts = (Class<?>[]) getParamTypesM.invoke(m);
                        if (pts.length == 3) {
                            targetMethod = m;
                            invokeArgs = new Object[]{cType, peerUin, name};
                            break;
                        } else if (pts.length == 4) {
                            targetMethod = m;
                            Contact contact = MsgSender.makeContact(peerUin, cType);
                            invokeArgs = new Object[]{cType, peerUin, name, contact};
                            break;
                        } else if (pts.length == 0) {
                            targetMethod = m;
                            invokeArgs = new Object[0];
                            break;
                        }
                    }
                }
            }

            if (targetMethod != null) {
                Method invokeM = targetMethod.getClass().getMethod("invoke", Object[].class, mInterpreter.getClass());
                invokeM.invoke(targetMethod, invokeArgs, mInterpreter);
            }
        } catch (Throwable t) {
            Throwable realEx = (t instanceof InvocationTargetException) ? ((InvocationTargetException) t).getTargetException() : t;
            logError("执行动作 [" + callback + "] 异常:\n" + getStackTrace(realEx));
        } finally {
            Thread.currentThread().setContextClassLoader(originalTCCL);
        }
    }

    public void onMsg(Object msgData) {
        invokeScriptMethod("onMsg", new Class[]{Object.class}, new Object[]{msgData});
    }

    public String getMsg(String original) {
        if (!mIsRunning || mInterpreter == null || original == null) return original;
        try {
            Method getNameSpaceMethod = mInterpreter.getClass().getMethod("getNameSpace");
            Object nameSpace = getNameSpaceMethod.invoke(mInterpreter);
            Method getMethodM = nameSpace.getClass().getMethod("getMethod", String.class, Class[].class);
            Object targetMethod = getMethodM.invoke(nameSpace, "getMsg", new Class[]{String.class});
            if (targetMethod != null) {
                Method invokeM = targetMethod.getClass().getMethod("invoke", Object[].class, mInterpreter.getClass());
                Object res = invokeM.invoke(targetMethod, new Object[]{original}, mInterpreter);
                if (res instanceof String) return (String) res;
            }
        } catch (Throwable ignored) {}
        return original;
    }

    public synchronized void stop() {
        if (!mIsRunning && mInterpreter == null) return;
        mIsRunning = false;
        try {
            if (mInterpreter != null) {
                Method getNameSpaceMethod = mInterpreter.getClass().getMethod("getNameSpace");
                Object nameSpace = getNameSpaceMethod.invoke(mInterpreter);
                Method getMethodM = nameSpace.getClass().getMethod("getMethod", String.class, Class[].class);
                Object targetMethod = getMethodM.invoke(nameSpace, "unLoadPlugin", new Class[0]);
                if (targetMethod != null) {
                    Method invokeM = targetMethod.getClass().getMethod("invoke", Object[].class, mInterpreter.getClass());
                    invokeM.invoke(targetMethod, new Object[0], mInterpreter);
                }
            }
        } catch (Throwable ignored) {}
        mMenuItems.clear();
        sAllMsgMenuItems.entrySet().removeIf(e -> mPluginId.equals(e.getValue().pluginId));
        mInterpreter = null;
    }

    private void logError(String text) {
        PluginMethod errorLog = new PluginMethod(mContext, mPluginDir);
        errorLog.log("error.log", text);
    }

    private String getStackTrace(Throwable t) {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        t.printStackTrace(pw);
        return sw.toString();
    }

    public boolean isRunning() { return mIsRunning; }
}
