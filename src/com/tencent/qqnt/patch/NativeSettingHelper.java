package com.tencent.qqnt.patch;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.widget.CompoundButton;

import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

public class NativeSettingHelper {

    // =========================================================================
    // 1. 分组构建与提交
    // =========================================================================
    public static Object createGroup(ClassLoader cl, CharSequence topTitle, CharSequence bottomFooter, List<Object> items) {
        try {
            Class<?> itemBaseClass = cl.loadClass("com.tencent.mobileqq.widget.listitem.a");
            Object itemArray = Array.newInstance(itemBaseClass, items.size());
            for (int i = 0; i < items.size(); i++) {
                Array.set(itemArray, i, items.get(i));
            }

            Class<?> groupClass = cl.loadClass("com.tencent.mobileqq.widget.listitem.Group");
            Constructor<?> ctor = groupClass.getConstructor(
                    CharSequence.class,
                    CharSequence.class,
                    itemArray.getClass()
            );
            return ctor.newInstance(
                    topTitle != null ? topTitle : "",
                    bottomFooter != null ? bottomFooter : "",
                    itemArray
            );
        } catch (Throwable t) {
            PLog.e("UI", "createGroup 失败", t);
            return null;
        }
    }

    public static void applyGroupsToAdapter(Object adapter, List<Object> groups, ClassLoader cl) {
        if (adapter == null || groups == null || cl == null) return;
        try {
            Class<?> groupClass = cl.loadClass("com.tencent.mobileqq.widget.listitem.Group");
            Object groupArray = Array.newInstance(groupClass, groups.size());
            for (int i = 0; i < groups.size(); i++) {
                Array.set(groupArray, i, groups.get(i));
            }

            for (Method m : adapter.getClass().getMethods()) {
                Class<?>[] pts = m.getParameterTypes();
                if (pts.length == 1 && pts[0].isArray() &&
                    pts[0].getComponentType().getName().endsWith("Group")) {
                    m.invoke(adapter, new Object[]{groupArray});
                    break;
                }
            }
        } catch (Throwable ignored) {}
    }

    // =========================================================================
    // 2. 纯文本项 (Title + RightText)
    // =========================================================================
    public static Object createTextItem(ClassLoader cl, CharSequence title, CharSequence rightText) {
        try {
            Class<?> xbdClass = cl.loadClass("com.tencent.mobileqq.widget.listitem.x$b$d");
            Object left = newInstanceSmart(xbdClass, new Object[]{title});

            Class<?> xcgClass = cl.loadClass("com.tencent.mobileqq.widget.listitem.x$c$g");
            Object right = newInstanceSmart(xcgClass, new Object[]{rightText != null ? rightText : "", false, false});

            return assembleSingleLineRow(cl, left, right, null);
        } catch (Throwable t) {
            PLog.e("UI", "createTextItem 失败", t);
            return null;
        }
    }

    // =========================================================================
    // 3. 开关类型 (Switch Item，完美支持单行 x 与双行 c)
    // =========================================================================
    public static Object createSwitch(ClassLoader cl, CharSequence title, boolean isChecked, CompoundButton.OnCheckedChangeListener listener) {
        return createSwitch(cl, title, null, isChecked, listener);
    }

    public static Object createSwitch(ClassLoader cl, CharSequence title, CharSequence subTitle, boolean isChecked, CompoundButton.OnCheckedChangeListener listener) {
        try {
            // ★★★ 当指定了副标题时，采用 QQ 原生真正的双行组件 c (c$a$f + c$b$c) ★★★
            if (subTitle != null && subTitle.length() > 0) {
                try {
                    Class<?> cClass = cl.loadClass("com.tencent.mobileqq.widget.listitem.c");
                    Class<?> cafClass = cl.loadClass("com.tencent.mobileqq.widget.listitem.c$a$f");
                    Class<?> cbcClass = cl.loadClass("com.tencent.mobileqq.widget.listitem.c$b$c");

                    // 实例化左侧双行标题组件 c$a$f(title, subTitle)
                    Object left = newInstanceSmart(cafClass, new Object[]{title, subTitle});

                    // 实例化右侧双行开关组件 c$b$c(isChecked, listener, isEnabled)
                    Object right = newInstanceSmart(cbcClass, new Object[]{isChecked, listener, true});

                    // 对齐设置事件监听器
                    if (right != null && listener != null) {
                        try {
                            Method gMethod = cbcClass.getMethod("g", CompoundButton.OnCheckedChangeListener.class);
                            gMethod.invoke(right, listener);
                        } catch (Throwable ignored) {}
                    }

                    // 组装整行并返回
                    Object doubleLineRow = newInstanceSmart(cClass, new Object[]{left, right});
                    if (doubleLineRow != null) {
                        return doubleLineRow;
                    }
                } catch (Throwable t) {
                    PLog.w("UI", "双行组件加载异常，降级单行: " + t.getMessage());
                }
            }

            // 单行默认走 x
            Class<?> xbdClass = cl.loadClass("com.tencent.mobileqq.widget.listitem.x$b$d");
            Object left = newInstanceSmart(xbdClass, new Object[]{title});

            Class<?> xcfClass = cl.loadClass("com.tencent.mobileqq.widget.listitem.x$c$f");
            Object right = newInstanceSmart(xcfClass, new Object[]{isChecked, listener, true});

            return assembleSingleLineRow(cl, left, right, null);
        } catch (Throwable t) {
            PLog.e("UI", "createSwitch 失败", t);
            return null;
        }
    }

    // =========================================================================
    // 4. 点击跳转类型 (带右侧文字、箭头、QUIBadge红点)
    // =========================================================================
    public static Object createClickable(ClassLoader cl, CharSequence title, String rightText, boolean showArrow, boolean showRedDot, View.OnClickListener clickListener) {
        try {
            Class<?> xbdClass = cl.loadClass("com.tencent.mobileqq.widget.listitem.x$b$d");
            Object left = newInstanceSmart(xbdClass, new Object[]{title});

            Class<?> xcgClass = cl.loadClass("com.tencent.mobileqq.widget.listitem.x$c$g");
            Object right = newInstanceSmart(xcgClass, new Object[]{rightText != null ? rightText : "", showArrow, showRedDot});

            if (right != null && showRedDot) {
                try {
                    Method gMethod = xcgClass.getMethod("g", boolean.class);
                    gMethod.invoke(right, true);
                } catch (Throwable ignored) {}
            }

            Object rowItem = assembleSingleLineRow(cl, left, right, clickListener);

            if (showRedDot && rowItem != null) {
                try {
                    Class<?> gClass = cl.loadClass("com.tencent.mobileqq.widget.listitem.g");
                    Object gProxy = Proxy.newProxyInstance(cl, new Class<?>[]{gClass}, (proxy, method, args) -> {
                        if ("G".equals(method.getName()) && args != null && args.length == 1 && (args[0] instanceof View)) {
                            QUIBadgeHelper.attachNativeBadge((View) args[0], rightText, true, showArrow);
                        }
                        return null;
                    });
                    for (Method m : rowItem.getClass().getMethods()) {
                        Class<?>[] pts = m.getParameterTypes();
                        if ("w".equals(m.getName()) && pts.length == 1 && pts[0] == gClass) {
                            m.invoke(rowItem, gProxy);
                            break;
                        }
                    }
                } catch (Throwable ignored) {}
            }

            return rowItem;
        } catch (Throwable t) {
            PLog.e("UI", "createClickable 失败", t);
            return null;
        }
    }

    private static Object assembleSingleLineRow(ClassLoader cl, Object left, Object right, View.OnClickListener clickListener) throws Exception {
        Class<?> xbClass = cl.loadClass("com.tencent.mobileqq.widget.listitem.x$b");
        Class<?> xcClass = cl.loadClass("com.tencent.mobileqq.widget.listitem.x$c");
        Class<?> xClass = cl.loadClass("com.tencent.mobileqq.widget.listitem.x");
        Constructor<?> xCtor = xClass.getConstructor(xbClass, xcClass);

        Object rowItem = xCtor.newInstance(left, right);

        if (clickListener != null) {
            for (Method m : rowItem.getClass().getMethods()) {
                Class<?>[] pts = m.getParameterTypes();
                if (pts.length == 1 && pts[0] == View.OnClickListener.class) {
                    m.invoke(rowItem, clickListener);
                    break;
                }
            }
        }
        return rowItem;
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
                    } else {
                        args[i] = (i < preferredArgs.length) ? preferredArgs[i] : null;
                    }
                }
                return c.newInstance(args);
            } catch (Throwable ignored) {}
        }
        return null;
    }
}
