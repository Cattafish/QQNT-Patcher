package com.tencent.qqnt.patch;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ImageSpan;
import android.util.Log;
import android.view.View;

import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class SettingInjector {
    private static final String TAG = "QQ_DEBUG";
    private static final String TITLE_TEXT = "Zzz";
    private static final boolean SHOW_ARROW = true;

    @SuppressWarnings({"unchecked", "rawtypes"})
    public static void inject(Context context, List resultList, String itemClassName) {
        if (context == null || resultList == null || resultList.isEmpty() || itemClassName == null) {
            return;
        }

        try {
            ClassLoader cl = context.getClassLoader();
            Class<?> itemClass = cl.loadClass(itemClassName);

            CharSequence finalTitle = TITLE_TEXT;
            try {
                InputStream is = context.getAssets().open("zzz_icon.png");
                Bitmap rawBitmap = BitmapFactory.decodeStream(is);
                if (rawBitmap != null) {
                    float density = context.getResources().getDisplayMetrics().density;
                    int iconSize = (int) (24 * density + 0.5f);
                    Bitmap scaledBitmap = Bitmap.createScaledBitmap(rawBitmap, iconSize, iconSize, true);
                    Drawable drawable = new BitmapDrawable(context.getResources(), scaledBitmap);
                    drawable.setBounds(0, 0, iconSize, iconSize);

                    SpannableString sp = new SpannableString("   " + finalTitle);
                    sp.setSpan(new ImageSpan(drawable, ImageSpan.ALIGN_BOTTOM), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    finalTitle = sp;
                }
            } catch (Throwable ignored) {}

            Object[] preferredItemArgs = new Object[]{context, 10, finalTitle, 0, null};
            Object mySettingItem = newInstanceSmart(itemClass, preferredItemArgs);
            if (mySettingItem == null) return;

            Class<?> func0Class = cl.loadClass("kotlin.jvm.functions.Function0");
            Object unitInstance = getKotlinUnitInstance(cl);

            Object clickProxy = Proxy.newProxyInstance(
                    cl,
                    new Class[]{func0Class},
                    (proxy, method, args) -> {
                        if ("invoke".equals(method.getName())) {
                            ZzzSettingFragment.start(context);
                        }
                        return unitInstance;
                    }
            );

            for (Method m : itemClass.getMethods()) {
                Class<?>[] pts = m.getParameterTypes();
                if (pts.length == 1 && pts[0] == func0Class && m.getReturnType() == void.class) {
                    m.invoke(mySettingItem, clickProxy);
                    break;
                }
            }

            // ★ 绑定原生 View 渲染回调，挂载 [QQ 原厂 QUIBadge 红点 + 版本号]
            Class<?> func1Class = cl.loadClass("kotlin.jvm.functions.Function1");
            Object viewBindProxy = Proxy.newProxyInstance(
                    cl,
                    new Class[]{func1Class},
                    (proxy, method, args) -> {
                        if ("invoke".equals(method.getName()) && args != null && args.length == 1) {
                            if (args[0] instanceof View) {
                                boolean hasNew = ConfigManager.hasNewVersion();
                                QUIBadgeHelper.attachNativeBadge((View) args[0], ConfigManager.VERSION, hasNew, SHOW_ARROW);
                            }
                        }
                        return unitInstance;
                    }
            );

            for (Method m : itemClass.getMethods()) {
                Class<?>[] pts = m.getParameterTypes();
                if (pts.length == 1 && pts[0] == func1Class && m.getReturnType() == void.class) {
                    m.invoke(mySettingItem, viewBindProxy);
                    break;
                }
            }

            Object firstGroup = resultList.get(0);
            Class<?> groupClass = firstGroup.getClass();

            Object[] preferredGroupArgs = new Object[]{
                    Collections.singletonList(mySettingItem),
                    "",
                    "",
                    0,
                    null
            };
            Object myGroup = newInstanceSmart(groupClass, preferredGroupArgs);

            if (myGroup != null) {
                if (resultList.size() >= 2) {
                    resultList.add(1, myGroup);
                } else {
                    resultList.add(myGroup);
                }
            }

        } catch (Throwable t) {
            Log.e(TAG, "设置中心注入异常", t);
        }
    }

    private static Object newInstanceSmart(Class<?> clazz, Object[] preferredArgs) {
        if (clazz == null) return null;
        Constructor<?>[] constructors = clazz.getDeclaredConstructors();
        for (Constructor<?> c : constructors) {
            try {
                c.setAccessible(true);
                Class<?>[] paramTypes = c.getParameterTypes();
                Object[] args = new Object[paramTypes.length];

                for (int i = 0; i < paramTypes.length; i++) {
                    Class<?> pt = paramTypes[i];
                    if (i < preferredArgs.length && preferredArgs[i] != null && pt.isAssignableFrom(preferredArgs[i].getClass())) {
                        args[i] = preferredArgs[i];
                    } else if (pt == int.class || pt == Integer.class) {
                        args[i] = (i < preferredArgs.length && preferredArgs[i] instanceof Number)
                                ? ((Number) preferredArgs[i]).intValue() : 0;
                    } else if (pt == boolean.class || pt == Boolean.class) {
                        args[i] = (i < preferredArgs.length && preferredArgs[i] instanceof Boolean)
                                ? (Boolean) preferredArgs[i] : false;
                    } else if (pt == long.class || pt == Long.class) {
                        args[i] = 0L;
                    } else if (pt == float.class || pt == Float.class) {
                        args[i] = 0.0f;
                    } else if (pt == double.class || pt == Double.class) {
                        args[i] = 0.0d;
                    } else if (pt == byte.class || pt == Byte.class) {
                        args[i] = (byte) 0;
                    } else if (pt == short.class || pt == Short.class) {
                        args[i] = (short) 0;
                    } else if (pt == char.class || pt == Character.class) {
                        args[i] = ' ';
                    } else {
                        args[i] = (i < preferredArgs.length) ? preferredArgs[i] : null;
                    }
                }

                return c.newInstance(args);
            } catch (Throwable ignored) {}
        }
        return null;
    }

    private static Object getKotlinUnitInstance(ClassLoader cl) {
        try {
            Class<?> unitClass = cl.loadClass("kotlin.Unit");
            Field field = unitClass.getDeclaredField("INSTANCE");
            field.setAccessible(true);
            return field.get(null);
        } catch (Throwable t) {
            return null;
        }
    }
}