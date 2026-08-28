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
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

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

    // ==========================================
    // 自定义配置区
    // ==========================================
    private static final String TITLE_TEXT = "Zzz";          // 左侧主标题
    private static final String RIGHT_SUB_TEXT = "v0.0.1";    // 右侧副标题/版本号
    private static final boolean SHOW_ARROW = true;           // 是否显示右侧小箭头 (true: 显示, false: 隐藏)

    @SuppressWarnings({"unchecked", "rawtypes"})
    public static void inject(Context context, List resultList, String itemClassName) {
        if (context == null || resultList == null || resultList.isEmpty() || itemClassName == null) {
            return;
        }

        try {
            ClassLoader cl = context.getClassLoader();
            Class<?> itemClass = cl.loadClass(itemClassName);

            // 1. 设置主标题并在图标与文字间留出空格
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
            } catch (Throwable t) {
                Log.w(TAG, "从 assets 加载 zzz_icon.png 失败: " + t.getMessage());
            }

            // 2. 实例化 ItemProcessor 对象
            Object[] preferredItemArgs = new Object[]{context, 10, finalTitle, 0, null};
            Object mySettingItem = newInstanceSmart(itemClass, preferredItemArgs);
            if (mySettingItem == null) {
                Log.e(TAG, "无法实例化 Item: " + itemClassName);
                return;
            }

            // 3. 注入点击事件 (Function0)
            Class<?> func0Class = cl.loadClass("kotlin.jvm.functions.Function0");
            Object unitInstance = getKotlinUnitInstance(cl);

            Object clickProxy = Proxy.newProxyInstance(
                    cl,
                    new Class[]{func0Class},
                    (proxy, method, args) -> {
                        if ("invoke".equals(method.getName())) {
                            // 【核心】：启动真正的原生 Material 3 Activity！
                            ZzzSettingActivity.start(context);
                        }
                        return unitInstance;
                    }
            );

            // 寻找 B(Function0) 绑定点击
            for (Method m : itemClass.getMethods()) {
                Class<?>[] pts = m.getParameterTypes();
                if (pts.length == 1 && pts[0] == func0Class && m.getReturnType() == void.class) {
                    m.invoke(mySettingItem, clickProxy);
                    break;
                }
            }

            // 4. 【核心黑科技】：利用 Function1 拦截原生 View 绘制，写入右侧小字与控制箭头
            Class<?> func1Class = cl.loadClass("kotlin.jvm.functions.Function1");
            Object viewBindProxy = Proxy.newProxyInstance(
                    cl,
                    new Class[]{func1Class},
                    (proxy, method, args) -> {
                        if ("invoke".equals(method.getName()) && args != null && args.length == 1) {
                            if (args[0] instanceof View) {
                                setupRightSideView((View) args[0], RIGHT_SUB_TEXT, SHOW_ARROW);
                            }
                        }
                        return unitInstance;
                    }
            );

            // 寻找 C(Function1) 绑定 View 绘制监听
            for (Method m : itemClass.getMethods()) {
                Class<?>[] pts = m.getParameterTypes();
                if (pts.length == 1 && pts[0] == func1Class && m.getReturnType() == void.class) {
                    m.invoke(mySettingItem, viewBindProxy);
                    break;
                }
            }

            // 5. 实例化 Group 对象
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
                Log.d(TAG, "设置项成功注入！");
            }

        } catch (Throwable t) {
            Log.e(TAG, "设置中心注入异常: ", t);
        }
    }

    /**
     * 动态遍历 View 树，定位并设置右侧文本及箭头状态
     */
    private static void setupRightSideView(View root, String rightText, boolean showArrow) {
        if (!(root instanceof ViewGroup)) return;
        try {
            ViewGroup vg = (ViewGroup) root;
            List<TextView> textViews = new ArrayList<>();
            List<ImageView> imageViews = new ArrayList<>();
            collectViews(vg, textViews, imageViews);

            // 1. 设置右侧文本
            for (TextView tv : textViews) {
                CharSequence currentText = tv.getText();
                // 排除包含主标题的左侧 TextView
                if (currentText == null || !currentText.toString().contains(TITLE_TEXT)) {
                    tv.setText(rightText);
                    tv.setVisibility(View.VISIBLE);
                    tv.setAlpha(0.6f); // 优雅的半透明灰字效果
                }
            }

            // 2. 自定义右侧箭头
            if (!showArrow && !imageViews.isEmpty()) {
                // 列表项中最右侧的 ImageView 通常就是向右的小箭头
                ImageView arrowView = imageViews.get(imageViews.size() - 1);
                arrowView.setVisibility(View.GONE);
            }
        } catch (Throwable t) {
            Log.w(TAG, "设置右侧视图异常: " + t.getMessage());
        }
    }

    private static void collectViews(ViewGroup vg, List<TextView> textViews, List<ImageView> imageViews) {
        int count = vg.getChildCount();
        for (int i = 0; i < count; i++) {
            View child = vg.getChildAt(i);
            if (child instanceof TextView) {
                textViews.add((TextView) child);
            } else if (child instanceof ImageView) {
                imageViews.add((ImageView) child);
            } else if (child instanceof ViewGroup) {
                collectViews((ViewGroup) child, textViews, imageViews);
            }
        }
    }

    private static Object newInstanceSmart(Class<?> clazz, Object[] preferredArgs) {
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
            } catch (Throwable ignored) {
            }
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