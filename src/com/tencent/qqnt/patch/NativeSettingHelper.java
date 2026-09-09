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

/**
 * QQNT 原生设置项全功能封装器
 * 涵盖：Icon、文本、副标题、Switch 开关、Radio 打勾单选、红点 Badge、Group 底部说明等全部原版样式
 */
public class NativeSettingHelper {

    // =========================================================================
    // 1. 分组构建 (Group)
    // =========================================================================

    /**
     * 创建一个原生设置卡片分组
     * @param topTitle 组顶部标题（如："核心功能"），传 null 或 "" 则不显示
     * @param bottomFooter 组底部灰色说明注脚（如："开启后将在聊天会话中生效"），传 null 或 "" 则不显示
     * @param items 分组内的列表项列表
     */
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

    // =========================================================================
    // 2. 开关类型 (Switch Item)
    // =========================================================================

    /**
     * 创建普通纯文本开关项
     */
    public static Object createSwitch(ClassLoader cl, CharSequence title, boolean isChecked, CompoundButton.OnCheckedChangeListener listener) {
        return createSwitchWithIcon(cl, title, 0, null, isChecked, listener);
    }

    /**
     * 创建带图标的开关项（可传 DrawableResId 或已生成的 Drawable）
     */
    public static Object createSwitchWithIcon(ClassLoader cl, CharSequence title, int iconResId, Drawable iconDrawable, boolean isChecked, CompoundButton.OnCheckedChangeListener listener) {
        try {
            Object left = createLeftPart(cl, title, iconResId, iconDrawable);

            // 右侧 Switch: x$c$f
            Class<?> xcfClass = cl.loadClass("com.tencent.mobileqq.widget.listitem.x$c$f");
            Constructor<?> xcfCtor = xcfClass.getConstructor(boolean.class, CompoundButton.OnCheckedChangeListener.class, boolean.class);
            Object right = xcfCtor.newInstance(isChecked, listener, true);

            return assembleSingleLineRow(cl, left, right, null);
        } catch (Throwable t) {
            PLog.e("UI", "createSwitch 失败", t);
            return null;
        }
    }

    // =========================================================================
    // 3. 点击/跳转类型 (Clickable Item: 箭头、右侧文字、原生红点)
    // =========================================================================

    /**
     * 创建标准点击跳转项（带右侧文字、箭头、支持原生小红点）
     */
    public static Object createClickable(ClassLoader cl, CharSequence title, CharSequence rightText, boolean showArrow, boolean showRedDot, View.OnClickListener clickListener) {
        return createClickableWithIcon(cl, title, rightText, 0, null, showArrow, showRedDot, clickListener);
    }

    /**
     * 创建带图标的点击跳转项
     */
    public static Object createClickableWithIcon(ClassLoader cl, CharSequence title, CharSequence rightText, int iconResId, Drawable iconDrawable, boolean showArrow, boolean showRedDot, View.OnClickListener clickListener) {
        try {
            Object left = createLeftPart(cl, title, iconResId, iconDrawable);

            // 右侧文本/箭头: x$c$g
            Class<?> xcgClass = cl.loadClass("com.tencent.mobileqq.widget.listitem.x$c$g");
            Constructor<?> xcgCtor = xcgClass.getConstructor(CharSequence.class, boolean.class, boolean.class);
            Object right = xcgCtor.newInstance(rightText != null ? rightText : "", showArrow, false);

            // 原生红点：x$c$g.g(boolean)
            if (showRedDot) {
                try {
                    Method setRedDotMethod = xcgClass.getMethod("g", boolean.class);
                    setRedDotMethod.invoke(right, true);
                } catch (Throwable ignored) {}
            }

            return assembleSingleLineRow(cl, left, right, clickListener);
        } catch (Throwable t) {
            PLog.e("UI", "createClickable 失败", t);
            return null;
        }
    }

    // =========================================================================
    // 4. 单选打勾项 (Radio Checkmark: 日间/夜间模式同款)
    // =========================================================================

    public interface OnRadioCheckedListener {
        void onChecked(Object item, boolean isChecked);
    }

    /**
     * 创建原版单选打勾项（右侧带原生蓝色对勾 Checkmark）
     */
    public static Object createRadioItem(ClassLoader cl, CharSequence title, int iconResId, boolean isChecked, OnRadioCheckedListener listener) {
        try {
            Object left = createLeftPart(cl, title, iconResId, null);

            // 监听接口代理: com.tencent.mobileqq.widget.listitem.h
            Class<?> hClass = cl.loadClass("com.tencent.mobileqq.widget.listitem.h");

            // 右侧对勾: x$c$k
            Class<?> xckClass = cl.loadClass("com.tencent.mobileqq.widget.listitem.x$c$k");
            Constructor<?> xckCtor = xckClass.getConstructor(boolean.class, hClass);

            final Object[] rowRef = new Object[1];

            Object hProxy = Proxy.newProxyInstance(cl, new Class<?>[]{hClass}, (proxy, method, args) -> {
                // 原版通过 h 接口回调选择改变
                if (listener != null && args != null && args.length >= 2) {
                    boolean checked = (Boolean) args[1];
                    listener.onChecked(rowRef[0], checked);
                }
                return null;
            });

            Object right = xckCtor.newInstance(isChecked, hProxy);
            Object row = assembleSingleLineRow(cl, left, right, v -> {
                try {
                    Method getRightM = rowRef[0].getClass().getMethod("M");
                    Object curRight = getRightM.invoke(rowRef[0]);
                    Method getCheckedM = xckClass.getMethod("d");
                    boolean curChecked = (Boolean) getCheckedM.invoke(curRight);

                    Method setCheckedM = xckClass.getMethod("f", boolean.class);
                    setCheckedM.invoke(curRight, !curChecked);

                    if (listener != null) {
                        listener.onChecked(rowRef[0], !curChecked);
                    }
                } catch (Throwable ignored) {}
            });
            rowRef[0] = row;
            return row;
        } catch (Throwable t) {
            PLog.e("UI", "createRadioItem 失败", t);
            return null;
        }
    }

    // =========================================================================
    // 5. 纯操作按钮项 (居中或纯点击项，右侧全空)
    // =========================================================================

    /**
     * 创建右侧全空的纯按钮项（例如：退出登录、清空缓存）
     */
    public static Object createButtonItem(ClassLoader cl, CharSequence title, int iconResId, View.OnClickListener clickListener) {
        try {
            Object left = createLeftPart(cl, title, iconResId, null);

            // 右侧全空: x$c$c.b
            Class<?> xccClass = cl.loadClass("com.tencent.mobileqq.widget.listitem.x$c$c");
            Field bField = xccClass.getField("b");
            Object right = bField.get(null);

            return assembleSingleLineRow(cl, left, right, clickListener);
        } catch (Throwable t) {
            PLog.e("UI", "createButtonItem 失败", t);
            return null;
        }
    }

    // =========================================================================
    // 6. 动态更新单行数据 (免刷新整个页面，杜绝闪烁)
    // =========================================================================

    /**
     * 更新某一个 Item 右侧的文字并无闪烁重绘
     */
    public static void updateRightText(Object adapter, Object itemRow, CharSequence newText) {
        if (itemRow == null) return;
        try {
            Method getRightM = itemRow.getClass().getMethod("M");
            Object rightObj = getRightM.invoke(itemRow);
            if (rightObj != null) {
                // x$c$g.h(CharSequence)
                Method hMethod = rightObj.getClass().getMethod("h", CharSequence.class);
                hMethod.invoke(rightObj, newText);

                // 通知 Adapter 单行局部刷新: adapter.e0(a)
                if (adapter != null) {
                    Class<?> aClass = itemRow.getClass().getSuperclass().getSuperclass(); // a 是基类
                    Method e0Method = adapter.getClass().getMethod("e0", aClass);
                    e0Method.invoke(adapter, itemRow);
                }
            }
        } catch (Throwable ignored) {}
    }

    // =========================================================================
    // 内部私有辅助逻辑
    // =========================================================================

    private static Object createLeftPart(ClassLoader cl, CharSequence title, int iconResId, Drawable iconDrawable) throws Exception {
        if (iconResId != 0 || iconDrawable != null) {
            // x$b$b(CharSequence text, int resId)
            Class<?> xbbClass = cl.loadClass("com.tencent.mobileqq.widget.listitem.x$b$b");
            Constructor<?> ctor = xbbClass.getConstructor(CharSequence.class, int.class);
            Object left = ctor.newInstance(title, iconResId);
            if (iconDrawable != null) {
                Method setDrawableM = xbbClass.getMethod("e", Drawable.class);
                setDrawableM.invoke(left, iconDrawable);
            }
            return left;
        } else {
            // x$b$d(CharSequence text)
            Class<?> xbdClass = cl.loadClass("com.tencent.mobileqq.widget.listitem.x$b$d");
            Constructor<?> ctor = xbdClass.getConstructor(CharSequence.class);
            return ctor.newInstance(title);
        }
    }

    private static Object assembleSingleLineRow(ClassLoader cl, Object left, Object right, View.OnClickListener clickListener) throws Exception {
        Class<?> xbClass = cl.loadClass("com.tencent.mobileqq.widget.listitem.x$b");
        Class<?> xcClass = cl.loadClass("com.tencent.mobileqq.widget.listitem.x$c");
        Class<?> xClass = cl.loadClass("com.tencent.mobileqq.widget.listitem.x");
        Constructor<?> xCtor = xClass.getConstructor(xbClass, xcClass);

        Object rowItem = xCtor.newInstance(left, right);

        // 绑定点击事件: a.x(OnClickListener)
        if (clickListener != null) {
            Method xMethod = rowItem.getClass().getMethod("x", View.OnClickListener.class);
            xMethod.invoke(rowItem, clickListener);
        }
        return rowItem;
    }
}