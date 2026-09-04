package me.yxp.qfun.utils.qq;

import android.util.Log;

public class QQCurrentEnv {
    private static final String TAG = "QQ_DEBUG";
    public static final QQCurrentEnv INSTANCE = new QQCurrentEnv();

    public Object getQQAppInterface() {
        try {
            Class<?> mobileQQClz = Class.forName("mqq.app.MobileQQ");
            Object mobileQQ = mobileQQClz.getMethod("getMobileQQ").invoke(null);
            if (mobileQQ != null) {
                Object runtime = mobileQQ.getClass().getMethod("peekAppRuntime").invoke(mobileQQ);
                if (runtime != null) return runtime;
            }
        } catch (Throwable ignored) {}

        try {
            Class<?> baseAppClz = Class.forName("com.tencent.common.app.BaseApplicationImpl");
            Object app = baseAppClz.getMethod("getApplication").invoke(null);
            if (app != null) {
                Object runtime = app.getClass().getMethod("peekAppRuntime").invoke(app);
                if (runtime != null) return runtime;
            }
        } catch (Throwable ignored) {}

        try {
            Class<?> baseAppClz = Class.forName("com.tencent.common.app.BaseApplicationImpl");
            java.lang.reflect.Field sAppField = baseAppClz.getDeclaredField("sApplication");
            sAppField.setAccessible(true);
            Object app = sAppField.get(null);
            if (app != null) {
                Object runtime = app.getClass().getMethod("peekAppRuntime").invoke(app);
                if (runtime != null) return runtime;
            }
        } catch (Throwable ignored) {}

        Log.e(TAG, "[QQCurrentEnv] 无法获取 AppRuntime / QQAppInterface");
        return null;
    }
}
