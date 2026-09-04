package com.tencent.qqnt.patch.plugin;

import android.content.Context;
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
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

    public PluginCompiler(Context context, File pluginDir, ClassLoader bshClassLoader) {
        this.mContext = context;
        this.mPluginDir = pluginDir;
        this.mPluginId = pluginDir.getName();
        this.mBshClassLoader = bshClassLoader;
        this.mFixClassLoader = new FixClassLoader(context.getClassLoader(), bshClassLoader);
    }

    public String getPluginId() {
        return mPluginId;
    }

    public Map<String, String> getMenuItems() {
        return mMenuItems;
    }

    public FixClassLoader getClassLoader() {
        return mFixClassLoader;
    }

    public Object getInterpreter() {
        return mInterpreter;
    }

    public void addMenuItem(String name, String callback) {
        mMenuItems.put(name, callback);
        Log.i(TAG, "[PluginCompiler] 脚本 " + mPluginId + " 注册菜单动作: " + name + " -> " + callback);
    }

    public void loadJava(String path) {
        if (mInterpreter == null) return;
        ClassLoader originalTCCL = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(mFixClassLoader);
        try {
            File f = new File(path);
            if (!f.exists()) {
                Log.w(TAG, "[PluginCompiler] 关联文件不存在: " + path);
                return;
            }
            long t0 = System.currentTimeMillis();

            // 读取并清洗关联 Java 文件
            String content = readFileContent(f);
            String sanitized = sanitizeScriptContent(content);

            Method evalMethod = mInterpreter.getClass().getMethod("eval", String.class);
            evalMethod.invoke(mInterpreter, sanitized);

            syncNewClassLoaders(f.getName().replace(".java", ""));
            Log.i(TAG, "[PluginCompiler] 成功载入关联文件: " + f.getName() + " (耗时 " + (System.currentTimeMillis() - t0) + "ms)");
        } catch (Throwable t) {
            Throwable realEx = (t instanceof InvocationTargetException) ? ((InvocationTargetException) t).getTargetException() : t;
            logError("loadJava 异常 [" + path + "]:\n" + getStackTrace(realEx));
            Log.e(TAG, "[PluginCompiler] loadJava 异常: " + path, realEx);
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
                    try {
                        cacheField = cur.getDeclaredField("absoluteClassCache");
                    } catch (NoSuchFieldException e) {
                        cur = cur.getSuperclass();
                    }
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

                                try {
                                    Method addClM = mInterpreter.getClass().getMethod("addClassLoader", ClassLoader.class);
                                    addClM.invoke(mInterpreter, clazz.getClassLoader());
                                } catch (Throwable ignored) {}
                            }
                        }
                    }
                }
            }

            Method getNameSpaceM = mInterpreter.getClass().getMethod("getNameSpace");
            Object ns = getNameSpaceM.invoke(mInterpreter);
            Method getClassM = ns.getClass().getMethod("getClass", String.class);
            Class<?> loadedClz = (Class<?>) getClassM.invoke(ns, expectedClassName);
            if (loadedClz != null) {
                FixClassLoader.registerScriptClass(expectedClassName, loadedClz);
                if (loadedClz.getClassLoader() != null) {
                    mFixClassLoader.addClassLoader(loadedClz.getClassLoader());
                }
            }
        } catch (Throwable ignored) {}
    }

    public synchronized boolean start() {
        if (mIsRunning) stop();
        mMenuItems.clear();

        File scriptFile = new File(mPluginDir, "main.java");
        if (!scriptFile.exists() || !scriptFile.isFile() || mBshClassLoader == null) {
            Log.w(TAG, "[PluginCompiler] " + mPluginId + " main.java 不存在或 bshClassLoader 为空");
            return false;
        }

        long tStart = System.currentTimeMillis();
        Log.i(TAG, "[PluginCompiler] 开始编译启动脚本: " + mPluginId);

        ClassLoader originalTCCL = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(mFixClassLoader);

        try {
            Class<?> interpClass = mBshClassLoader.loadClass("bsh.Interpreter");
            mInterpreter = interpClass.getDeclaredConstructor().newInstance();

            try {
                Method getClassManagerM = mInterpreter.getClass().getMethod("getClassManager");
                Object bshClassManager = getClassManagerM.invoke(mInterpreter);
                if (bshClassManager != null) {
                    mFixClassLoader.setBshClassManager(bshClassManager);
                }
            } catch (Throwable ignored) {}

            Method setClassLoaderMethod = interpClass.getMethod("setClassLoader", ClassLoader.class);
            setClassLoaderMethod.invoke(mInterpreter, mFixClassLoader);

            Method setMethod = interpClass.getMethod("set", String.class, Object.class);
            setMethod.invoke(mInterpreter, "context", mContext);
            setMethod.invoke(mInterpreter, "classLoader", mFixClassLoader);
            setMethod.invoke(mInterpreter, "pluginPath", mPluginDir.getAbsolutePath());
            setMethod.invoke(mInterpreter, "pluginId", mPluginId);
            setMethod.invoke(mInterpreter, "myUin", MsgSender.getMyUin());

            PluginMethod api = new PluginMethod(mContext, mPluginDir);
            api.setCompiler(this);
            setMethod.invoke(mInterpreter, "api", api);

            // 注册 API 与通用方法调度器
            try {
                Method getNameSpaceMethod = interpClass.getMethod("getNameSpace");
                Object nameSpace = getNameSpaceMethod.invoke(mInterpreter);

                try {
                    Method invokeMethodInApi = PluginMethod.class.getMethod("invoke", String.class, Object[].class);
                    Class<?> bshMethodClz = mBshClassLoader.loadClass("bsh.BshMethod");
                    Constructor<?> bshMethodCtor = bshMethodClz.getConstructor(Method.class, Object.class);
                    Object bshM = bshMethodCtor.newInstance(invokeMethodInApi, api);
                    Method setMethodM = nameSpace.getClass().getMethod("setMethod", bshMethodClz);
                    setMethodM.invoke(nameSpace, bshM);
                } catch (Throwable ignored) {}

                Method importObjectMethod = nameSpace.getClass().getMethod("importObject", Object.class);
                importObjectMethod.invoke(nameSpace, api);
            } catch (Throwable ignored) {}

            // ★ 核心突破：读取并进行底层变量隔离清洗，杜绝任何外部脚本的单字母局部变量污染全局
            String rawCode = readFileContent(scriptFile);
            String safeCode = sanitizeScriptContent(rawCode);

            Method evalMethod = interpClass.getMethod("eval", String.class);
            evalMethod.invoke(mInterpreter, safeCode);

            mIsRunning = true;
            Log.i(TAG, "[PluginCompiler] 脚本 " + mPluginId + " 启动成功，耗时: " + (System.currentTimeMillis() - tStart) + "ms, 菜单项: " + mMenuItems.size());
            return true;
        } catch (Throwable t) {
            Throwable realEx = (t instanceof InvocationTargetException) ? ((InvocationTargetException) t).getTargetException() : t;
            logError("脚本启动异常:\n" + getStackTrace(realEx));
            Log.e(TAG, "[PluginCompiler] 脚本 " + mPluginId + " 启动失败: " + realEx.getMessage(), realEx);
            stop();
            return false;
        } finally {
            Thread.currentThread().setContextClassLoader(originalTCCL);
        }
    }

    /**
     * ★ 底层脚本自动免疫净化引擎：
     * 自动检测并隔离脚本中 helper 方法（如 createShape/createInput 等）里的局部变量，
     * 防止局部 Drawable / LayoutParams 溢出到全局命名空间，从而解决跨线程丢失变量的根本问题。
     */
    private String sanitizeScriptContent(String source) {
        if (source == null || source.isEmpty()) return "";

        // 1. 净化 createShape 内部的局部变量 g
        Pattern pShape = Pattern.compile("(GradientDrawable\\s+)(createShape\\s*\\([^)]*\\)\\s*\\{)([^}]+)(\\})", Pattern.DOTALL);
        Matcher mShape = pShape.matcher(source);
        StringBuffer sb1 = new StringBuffer();
        while (mShape.find()) {
            String body = mShape.group(3);
            body = body.replaceAll("\\bGradientDrawable\\s+g\\b", "GradientDrawable _bsh_shape_g")
                       .replaceAll("\\bg\\.", "_bsh_shape_g.")
                       .replaceAll("\\breturn\\s+g\\s*;", "return _bsh_shape_g;");
            mShape.appendReplacement(sb1, Matcher.quoteReplacement(mShape.group(1) + mShape.group(2) + body + mShape.group(4)));
        }
        mShape.appendTail(sb1);
        String code = sb1.toString();

        // 2. 净化 createInput / LayoutParams 内部的局部变量 p
        Pattern pParams = Pattern.compile("(createInput\\s*\\([^)]*\\)\\s*\\{)([^}]+)(\\})", Pattern.DOTALL);
        Matcher mParams = pParams.matcher(code);
        StringBuffer sb2 = new StringBuffer();
        while (mParams.find()) {
            String body = mParams.group(2);
            body = body.replaceAll("\\bLayoutParams\\s+p\\b", "LayoutParams _bsh_lp_p")
                       .replaceAll("\\bp\\.", "_bsh_lp_p.")
                       .replaceAll("\\bsetLayoutParams\\(\\s*p\\s*\\)", "setLayoutParams(_bsh_lp_p)");
            mParams.appendReplacement(sb2, Matcher.quoteReplacement(mParams.group(1) + body + mParams.group(3)));
        }
        mParams.appendTail(sb2);

        return sb2.toString();
    }

    private String readFileContent(File file) {
        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] b = new byte[(int) file.length()];
            fis.read(b);
            return new String(b, StandardCharsets.UTF_8);
        } catch (Throwable t) {
            return "";
        }
    }

    public void invokeMenuItem(String callback, int cType, String peerUin, String name) {
        if (!mIsRunning || mInterpreter == null) {
            logError("执行菜单失败：脚本未处于运行状态！");
            return;
        }

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
            } else {
                Method evalMethod = mInterpreter.getClass().getMethod("eval", String.class);
                Method setMethod = mInterpreter.getClass().getMethod("set", String.class, Object.class);
                setMethod.invoke(mInterpreter, "_mCType", cType);
                setMethod.invoke(mInterpreter, "_mPeerUin", peerUin);
                setMethod.invoke(mInterpreter, "_mName", name);
                evalMethod.invoke(mInterpreter, callback + "(_mCType, _mPeerUin, _mName);");
            }
        } catch (Throwable t) {
            Throwable realEx = (t instanceof InvocationTargetException) ? ((InvocationTargetException) t).getTargetException() : t;
            logError("执行动作 [" + callback + "] 异常:\n" + getStackTrace(realEx));
            Log.e(TAG, "[PluginCompiler] invokeMenuItem 异常", realEx);
        } finally {
            Thread.currentThread().setContextClassLoader(originalTCCL);
        }
    }

    public void onMsg(Object msgData) {
        if (!mIsRunning || mInterpreter == null || msgData == null) return;
        try {
            Method getNameSpaceMethod = mInterpreter.getClass().getMethod("getNameSpace");
            Object nameSpace = getNameSpaceMethod.invoke(mInterpreter);
            Method getMethodM = nameSpace.getClass().getMethod("getMethod", String.class, Class[].class);
            Object targetMethod = getMethodM.invoke(nameSpace, "onMsg", new Class[]{Object.class});

            if (targetMethod != null) {
                Method invokeM = targetMethod.getClass().getMethod("invoke", Object[].class, mInterpreter.getClass());
                invokeM.invoke(targetMethod, new Object[]{msgData}, mInterpreter);
            }
        } catch (Throwable t) {
            Throwable realEx = (t instanceof InvocationTargetException) ? ((InvocationTargetException) t).getTargetException() : t;
            logError("onMsg 异常:\n" + getStackTrace(realEx));
        }
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
        mInterpreter = null;
        Log.i(TAG, "[PluginCompiler] 脚本卸载完毕: " + mPluginId);
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

    public boolean isRunning() {
        return mIsRunning;
    }
}