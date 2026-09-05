package me.yxp.qfun.utils.qq;

import android.util.Log;
import com.tencent.qqnt.patch.AppContext;

public class QQCurrentEnv {
    private static final String TAG = "QQ_DEBUG";
    public static final QQCurrentEnv INSTANCE = new QQCurrentEnv();

    public Object getQQAppInterface() {
        Object runtime = AppContext.getAppRuntime();
        if (runtime != null) return runtime;
        Log.e(TAG, "[QQCurrentEnv] 无法获取 AppRuntime / QQAppInterface");
        return null;
    }
}
