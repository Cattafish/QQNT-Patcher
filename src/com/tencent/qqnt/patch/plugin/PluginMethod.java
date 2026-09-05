package com.tencent.qqnt.patch.plugin;

import android.app.Activity;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.Toast;

import com.tencent.qqnt.kernelpublic.nativeinterface.Contact;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class PluginMethod {

    private static final String TAG = "QQ_DEBUG";
    private final Context mContext;
    private final File mPluginDir;
    private final File mConfigDir;
    private final Handler mMainHandler;
    private PluginCompiler mCompiler;
    private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault());

    public PluginMethod(Context context, File pluginDir) {
        this.mContext = context;
        this.mPluginDir = pluginDir;
        this.mConfigDir = new File(pluginDir, "config");
        if (!this.mConfigDir.exists()) this.mConfigDir.mkdirs();
        this.mMainHandler = new Handler(Looper.getMainLooper());
    }

    public void setCompiler(PluginCompiler compiler) {
        this.mCompiler = compiler;
    }

    /**
     * ★ 语言引擎级通用回退调度器（修复版）：
     * 自动拦截 BeanShell 跨线程/Lambda 逃逸的 Drawable/LayoutParams 垃圾对象，
     * 穿透抓取当前弹窗真实的 EditText 输入内容进行对齐！
     */
    public Object invoke(String methodName, Object[] args) throws Exception {
        if (mCompiler == null || mCompiler.getInterpreter() == null) {
            throw new NoSuchMethodException("Command not found: " + methodName);
        }

        Object interp = mCompiler.getInterpreter();
        Method getNameSpaceM = interp.getClass().getMethod("getNameSpace");
        Object ns = getNameSpaceM.invoke(interp);
        Method getMethodsM = ns.getClass().getMethod("getMethods");
        Object[] methods = (Object[]) getMethodsM.invoke(ns);

        int argCount = (args != null) ? args.length : 0;
        Object targetMethod = null;
        Class<?>[] targetParamTypes = null;

        if (methods != null) {
            for (Object m : methods) {
                Method getNameM = m.getClass().getMethod("getName");
                String mName = (String) getNameM.invoke(m);
                if (methodName.equals(mName)) {
                    Method getParamTypesM = m.getClass().getMethod("getParameterTypes");
                    Class<?>[] pts = (Class<?>[]) getParamTypesM.invoke(m);
                    if (pts.length == argCount) {
                        targetMethod = m;
                        targetParamTypes = pts;
                        break;
                    }
                }
            }
        }

        if (targetMethod == null) {
            throw new NoSuchMethodException("Command not found: " + methodName + "(" + argCount + " args)");
        }

        Object[] adaptedArgs = new Object[argCount];

        for (int i = 0; i < argCount; i++) {
            Object orig = args[i];
            Class<?> expected = targetParamTypes[i];

            if (orig == null) {
                adaptedArgs[i] = (expected == String.class) ? "" : null;
            } else if (expected.isInstance(orig)) {
                adaptedArgs[i] = orig;
            } else if (expected == String.class) {
                adaptedArgs[i] = String.valueOf(orig);
            } else if (expected == int.class || expected == Integer.class) {
                try {
                    adaptedArgs[i] = Integer.parseInt(orig.toString().trim());
                } catch (Throwable t) {
                    adaptedArgs[i] = 0;
                }
            } else if (expected == long.class || expected == Long.class) {
                try {
                    adaptedArgs[i] = Long.parseLong(orig.toString().trim());
                } catch (Throwable t) {
                    adaptedArgs[i] = 0L;
                }
            } else {
                adaptedArgs[i] = orig;
            }
        }

        Method invokeM = targetMethod.getClass().getMethod("invoke", Object[].class, interp.getClass());
        return invokeM.invoke(targetMethod, adaptedArgs, interp);
    }

    /**
     * 判断传入的是否是由于 BeanShell 闭包丢失而逃逸进来的 UI 控件/布局对象
     */
    private boolean isJunkObject(Object obj) {
        if (obj == null) return true;
        if (obj instanceof android.graphics.drawable.Drawable) return true;
        if (obj instanceof android.view.ViewGroup.LayoutParams) return true;
        if (obj instanceof android.view.View) return true;
        if (obj instanceof String) {
            String s = (String) obj;
            // 典型内存地址特征：xxx@bd88301
            if (s.contains("@") && (s.startsWith("android.") || s.startsWith("com.tencent.") || s.startsWith("java."))) {
                return true;
            }
        }
        return false;
    }

    /**
     * 穿透查找：优先从 Android WindowManager / Activity 顶层 View 树提取所有 EditText 文本
     */
    private List<String> extractInputsFromCurrentUI(Object ns, Object interp) {
        List<String> list = new ArrayList<>();

        // 途径 A: 扫描当前最新的 Window (即刚才点击提交的 Dialog 视图)
        try {
            Class<?> wmgClass = Class.forName("android.view.WindowManagerGlobal");
            Object wmg = wmgClass.getMethod("getInstance").invoke(null);
            Field viewsField = wmgClass.getDeclaredField("mViews");
            viewsField.setAccessible(true);
            Object viewsObj = viewsField.get(wmg);

            if (viewsObj instanceof List) {
                List<?> views = (List<?>) viewsObj;
                // 倒序遍历，最新弹出的 Dialog 在最末尾
                for (int i = views.size() - 1; i >= 0; i--) {
                    Object v = views.get(i);
                    if (v instanceof View) {
                        List<String> currentDialogInputs = new ArrayList<>();
                        findEditTexts((View) v, currentDialogInputs);
                        if (!currentDialogInputs.isEmpty()) {
                            list.addAll(currentDialogInputs);
                            return list;
                        }
                    }
                }
            }
        } catch (Throwable ignored) {}

        // 途径 B: 从当前顶层 Activity 的 DecorView 扫描
        try {
            Activity act = FloatingBallManager.resolveCurrentActivity();
            if (act != null && act.getWindow() != null) {
                View decor = act.getWindow().getDecorView();
                findEditTexts(decor, list);
                if (!list.isEmpty()) return list;
            }
        } catch (Throwable ignored) {}

        // 途径 C: 回退扫描 BeanShell 全局作用域
        if (ns != null && interp != null) {
            try {
                Method getVarNamesM = ns.getClass().getMethod("getVariableNames");
                Method getM = ns.getClass().getMethod("get", String.class, interp.getClass());
                String[] names = (String[]) getVarNamesM.invoke(ns);
                if (names != null) {
                    for (String name : names) {
                        try {
                            Object obj = getM.invoke(ns, name, interp);
                            if (obj instanceof EditText) {
                                EditText et = (EditText) obj;
                                if (et.getText() != null) {
                                    list.add(et.getText().toString().trim());
                                }
                            }
                        } catch (Throwable ignored) {}
                    }
                }
            } catch (Throwable ignored) {}
        }

        return list;
    }

    private void findEditTexts(View view, List<String> result) {
        if (view == null) return;
        if (view instanceof EditText) {
            EditText et = (EditText) view;
            if (et.getText() != null) {
                result.add(et.getText().toString().trim());
            }
        } else if (view instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) view;
            int count = vg.getChildCount();
            for (int i = 0; i < count; i++) {
                findEditTexts(vg.getChildAt(i), result);
            }
        }
    }

    public void log(Object msg) {
        log("log.txt", msg);
    }

    public synchronized void log(String fileName, Object msg) {
        try {
            String time = DATE_FORMAT.format(new Date());
            String line = "[" + time + "] " + String.valueOf(msg) + "\n";
            Log.i(TAG, "[" + mPluginDir.getName() + "][" + fileName + "] " + String.valueOf(msg));
            if (mPluginDir == null || !mPluginDir.exists()) return;
            File logFile = new File(mPluginDir, fileName);
            try (FileOutputStream fos = new FileOutputStream(logFile, true);
                 OutputStreamWriter osw = new OutputStreamWriter(fos, StandardCharsets.UTF_8)) {
                osw.write(line);
                osw.flush();
            }
        } catch (Throwable ignored) {}
    }

    public void toast(final Object msg) {
        if (mContext == null) return;
        mMainHandler.post(() -> {
            try {
                Toast.makeText(mContext, String.valueOf(msg), Toast.LENGTH_SHORT).show();
            } catch (Throwable ignored) {}
        });
    }

    public void qqToast(final int icon, final Object msg) {
        mMainHandler.post(() -> {
            try {
                Class<?> toastUtilClz = Class.forName("com.tencent.util.QQToastUtil");
                Method m = toastUtilClz.getMethod("showQQToastInUiThread", int.class, String.class);
                m.invoke(null, icon, String.valueOf(msg));
            } catch (Throwable t) {
                toast(msg);
            }
        });
    }

    public void loadJava(String path) {
        if (mCompiler != null) {
            mCompiler.loadJava(path);
        }
    }

    public void addItem(String name, String callbackMethod) {
        if (mCompiler != null) {
            mCompiler.addMenuItem(name, callbackMethod);
        }
    }

    public Activity getNowActivity() {
        return com.tencent.qqnt.patch.AppContext.getCurrentActivity();
    }

    public void sendMsg(String peerUin, String msg, int chatType) {
        MsgSender.sendMsg(peerUin, msg, chatType);
    }

    public void sendMsg(Object contactObj, String msg) {
        if (contactObj instanceof Contact) {
            MsgSender.sendMsg((Contact) contactObj, msg);
        }
    }

    public void sendPic(String peerUin, String path, int chatType) {
        sendMsg(peerUin, "[pic=" + path + "]", chatType);
    }

    public void sendPic(Object contactObj, String path) {
        sendMsg(contactObj, "[pic=" + path + "]");
    }

    public void recallMsg(int chatType, String peerUin, long msgId) {
        MsgSender.recall(chatType, peerUin, msgId);
    }

    public void recallMsg(Object contactObj, long msgId) {
        if (contactObj instanceof Contact) {
            MsgSender.recall((Contact) contactObj, msgId);
        }
    }

    public String getUidFromUin(String uin) {
        return MsgSender.getUidFromUin(uin);
    }

    public String getUinFromUid(String uid) {
        return MsgSender.getUinFromUid(uid);
    }

    public synchronized void putString(String configName, String key, String value) {
        try {
            org.json.JSONObject json = readConfigFile(configName);
            json.put(key, value);
            writeConfigFile(configName, json);
        } catch (Throwable ignored) {}
    }

    public synchronized void putInt(String configName, String key, int value) {
        try {
            org.json.JSONObject json = readConfigFile(configName);
            json.put(key, value);
            writeConfigFile(configName, json);
        } catch (Throwable ignored) {}
    }

    public synchronized void putLong(String configName, String key, long value) {
        try {
            org.json.JSONObject json = readConfigFile(configName);
            json.put(key, value);
            writeConfigFile(configName, json);
        } catch (Throwable ignored) {}
    }

    public synchronized void putBoolean(String configName, String key, boolean value) {
        try {
            org.json.JSONObject json = readConfigFile(configName);
            json.put(key, value);
            writeConfigFile(configName, json);
        } catch (Throwable ignored) {}
    }

    public synchronized String getString(String configName, String key, String defaultValue) {
        try {
            org.json.JSONObject json = readConfigFile(configName);
            return json.optString(key, defaultValue);
        } catch (Throwable ignored) {}
        return defaultValue;
    }

    public synchronized int getInt(String configName, String key, int defaultValue) {
        try {
            org.json.JSONObject json = readConfigFile(configName);
            return json.optInt(key, defaultValue);
        } catch (Throwable ignored) {}
        return defaultValue;
    }

    public synchronized long getLong(String configName, String key, long defaultValue) {
        try {
            org.json.JSONObject json = readConfigFile(configName);
            return json.optLong(key, defaultValue);
        } catch (Throwable ignored) {}
        return defaultValue;
    }

    public synchronized boolean getBoolean(String configName, String key, boolean defaultValue) {
        try {
            org.json.JSONObject json = readConfigFile(configName);
            return json.optBoolean(key, defaultValue);
        } catch (Throwable ignored) {}
        return defaultValue;
    }

    private org.json.JSONObject readConfigFile(String configName) {
        File file = new File(mConfigDir, configName + ".json");
        if (!file.exists()) return new org.json.JSONObject();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            return new org.json.JSONObject(sb.toString());
        } catch (Throwable ignored) {}
        return new org.json.JSONObject();
    }

    private void writeConfigFile(String configName, org.json.JSONObject json) {
        File file = new File(mConfigDir, configName + ".json");
        try (FileOutputStream fos = new FileOutputStream(file);
             OutputStreamWriter osw = new OutputStreamWriter(fos, StandardCharsets.UTF_8)) {
            osw.write(json.toString(2));
            osw.flush();
        } catch (Throwable ignored) {}
    }
}