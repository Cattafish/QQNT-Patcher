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
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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

    public String getPluginId() { return mPluginId; }
    public Map<String, String> getMenuItems() { return mMenuItems; }
    public FixClassLoader getClassLoader() { return mFixClassLoader; }
    public Object getInterpreter() { return mInterpreter; }

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
            if (!f.exists()) return;
            long t0 = System.currentTimeMillis();
            String rawCode = readFileContent(f);
            String safeCode = universalSanitizeScript(rawCode);

            Method evalMethod = mInterpreter.getClass().getMethod("eval", String.class);
            evalMethod.invoke(mInterpreter, safeCode);

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
        Log.i(TAG, "[PluginCompiler] 开始编译启动脚本: " + mPluginId);

        ClassLoader originalTCCL = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(mFixClassLoader);

        try {
            Class<?> interpClass = mBshClassLoader.loadClass("bsh.Interpreter");
            mInterpreter = interpClass.getDeclaredConstructor().newInstance();

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

            try {
                Method getNameSpaceMethod = interpClass.getMethod("getNameSpace");
                Object nameSpace = getNameSpaceMethod.invoke(mInterpreter);

                // 将 PluginMethod 的全部方法注册到 BeanShell 命名空间，让脚本可以直接无前缀调用
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
            String safeCode = universalSanitizeScript(rawCode);

            Method evalMethod = interpClass.getMethod("eval", String.class);
            evalMethod.invoke(mInterpreter, safeCode);

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

    private String universalSanitizeScript(String source) {
        if (source == null || source.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        int len = source.length();
        int cursor = 0;
        Pattern methodHeader = Pattern.compile("(?:public|protected|private|static|final|synchronized|\\s)*\\s+([A-Za-z0-9_<>.\\[\\]]+)\\s+([A-Za-z0-9_]+)\\s*\\(([^)]*)\\)\\s*\\{");

        while (cursor < len) {
            Matcher m = methodHeader.matcher(source);
            if (!m.find(cursor)) {
                sb.append(source.substring(cursor));
                break;
            }

            int bodyStart = m.end();
            sb.append(source, cursor, bodyStart);

            int braceDepth = 1;
            int scanIdx = bodyStart;
            boolean inString = false;
            char quoteChar = 0;

            while (scanIdx < len && braceDepth > 0) {
                char c = source.charAt(scanIdx);
                if (inString) {
                    if (c == '\\') scanIdx++;
                    else if (c == quoteChar) inString = false;
                } else {
                    if (c == '"' || c == '\'') { inString = true; quoteChar = c; }
                    else if (c == '{') braceDepth++;
                    else if (c == '}') braceDepth--;
                }
                scanIdx++;
            }

            if (braceDepth == 0) {
                String returnType = m.group(1).trim();
                String methodName = m.group(2).trim();
                String methodBody = source.substring(bodyStart, scanIdx - 1);
                if (!"void".equals(returnType)) {
                    methodBody = isolateAnyLocalVariables(methodBody, methodName);
                }
                sb.append(methodBody);
                sb.append("}");
                cursor = scanIdx;
            } else {
                sb.append(source.substring(bodyStart));
                break;
            }
        }
        return sb.toString();
    }

    private String isolateAnyLocalVariables(String body, String methodName) {
        if (body == null || body.isEmpty()) return body;
        Pattern declPattern = Pattern.compile("\\b([A-Za-z0-9_$.]+)\\s+([a-zA-Z_][a-zA-Z0-9_]*)\\s*=");
        Matcher m = declPattern.matcher(body);
        List<String> detectedVars = new ArrayList<>();
        while (m.find()) {
            String varName = m.group(2);
            if ("if".equals(varName) || "do".equals(varName) || "while".equals(varName) || "for".equals(varName)) continue;
            if (!detectedVars.contains(varName)) detectedVars.add(varName);
        }

        String safeBody = body;
        for (String var : detectedVars) {
            String isolatedName = "_bsh_" + var + "_" + Math.abs(methodName.hashCode() % 10000);
            safeBody = safeBody.replaceAll("\\b" + var + "\\b", isolatedName);
        }
        return safeBody;
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

    public void chatInterface(int cType, String peerUin, String name) {
        if (!mIsRunning || mInterpreter == null) return;
        ClassLoader originalTCCL = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(mFixClassLoader);
        try {
            Method getNameSpaceMethod = mInterpreter.getClass().getMethod("getNameSpace");
            Object nameSpace = getNameSpaceMethod.invoke(mInterpreter);
            Method getMethodM = nameSpace.getClass().getMethod("getMethod", String.class, Class[].class);
            Object targetMethod = getMethodM.invoke(nameSpace, "chatInterface", new Class[]{int.class, String.class, String.class});

            if (targetMethod != null) {
                Method invokeM = targetMethod.getClass().getMethod("invoke", Object[].class, mInterpreter.getClass());
                invokeM.invoke(targetMethod, new Object[]{cType, peerUin, name}, mInterpreter);
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
        } catch (Throwable ignored) {}
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
