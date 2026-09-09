package com.tencent.qqnt.patch;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

public class ToastHelper {
    private static Toast sLastToast = null;
    private static final Handler sMainHandler = new Handler(Looper.getMainLooper());

    /**
     * 立即弹出 Toast：自动取消上一个正在显示的 Toast，新内容秒级呈现
     */
    public static void show(Context context, CharSequence text) {
        if (context == null || text == null) return;

        Runnable task = () -> {
            try {
                // 1. 立即中断并取消上一个正在显示的 Toast
                if (sLastToast != null) {
                    try {
                        sLastToast.cancel();
                    } catch (Throwable ignored) {}
                }

                // 2. 立即创建并展示新 Toast
                Context appCtx = context.getApplicationContext() != null ? context.getApplicationContext() : context;
                Toast toast = Toast.makeText(appCtx, text, Toast.LENGTH_SHORT);
                sLastToast = toast;
                toast.show();
            } catch (Throwable ignored) {}
        };

        if (Looper.myLooper() == Looper.getMainLooper()) {
            task.run();
        } else {
            sMainHandler.post(task);
        }
    }
}