package com.tencent.qqnt.patch;

import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

public class QUIBadgeHelper {

    // ★ 来自 Smali 的 QQ 官方 QUIBadge 固定控件 ID (0x7f0a5eb2)
    public static final int QUI_BADGE_ID = 0x7f0a5eb2;

    /**
     * 激活并绑定 QQ 原厂 QUIBadge 控件与右侧文字
     */
    public static void attachNativeBadge(View root, String rightText, boolean showRedDot, boolean showArrow) {
        if (!(root instanceof ViewGroup)) return;
        try {
            ViewGroup vg = (ViewGroup) root;

            // 1. 设置右侧文字
            TextView rightTv = findRightTextView(vg);
            if (rightTv != null) {
                rightTv.setText(rightText);
                rightTv.setVisibility(View.VISIBLE);
                rightTv.setAlpha(0.7f);
            }

            // 2. 控制右侧箭头
            ImageView arrowIv = findArrowImageView(vg);
            if (arrowIv != null) {
                arrowIv.setVisibility(showArrow ? View.VISIBLE : View.GONE);
            }

            // 3. ★ 获取/唤醒 QQ 原厂 QUIBadge 控件并点亮红点
            View quiBadge = getOrCreateNativeQUIBadge(root);
            if (quiBadge != null) {
                if (showRedDot) {
                    try {
                        Method setRedDotMethod = quiBadge.getClass().getMethod("setRedDot");
                        setRedDotMethod.invoke(quiBadge);
                    } catch (Throwable ignored) {}
                    quiBadge.setVisibility(View.VISIBLE);
                } else {
                    quiBadge.setVisibility(View.GONE);
                }
            }
        } catch (Throwable ignored) {}
    }

    /**
     * 核心：通过官方 ID 或 RightBinding 唤醒原厂 QUIBadge
     */
    private static View getOrCreateNativeQUIBadge(View root) {
        if (root == null) return null;

        // 方式 1: 直接通过 QQ 原厂固定 ID 查找
        View badge = root.findViewById(QUI_BADGE_ID);
        if (badge != null && badge.getClass().getName().contains("QUIBadge")) {
            return badge;
        }

        // 方式 2: 若处于 lazy 状态尚未加载，反射触发 RightBinding 懒加载
        try {
            Class<?> rootClass = root.getClass();
            for (Field f : rootClass.getDeclaredFields()) {
                f.setAccessible(true);
                Object binding = f.get(root);
                if (binding != null && binding.getClass().getName().contains("RightBinding")) {
                    for (Method m : binding.getClass().getDeclaredMethods()) {
                        m.setAccessible(true);
                        if (m.getParameterTypes().length == 0) {
                            Class<?> returnType = m.getReturnType();
                            if (returnType.getName().contains("QUIBadge") || returnType == View.class) {
                                Object result = m.invoke(binding);
                                if (result instanceof View && result.getClass().getName().contains("QUIBadge")) {
                                    return (View) result;
                                }
                            }
                        }
                    }
                }
            }
        } catch (Throwable ignored) {}

        // 方式 3: 递归遍历 View 树查找已挂载的 QUIBadge
        if (root instanceof ViewGroup) {
            return findChildByClassName((ViewGroup) root, "QUIBadge");
        }

        return null;
    }

    private static View findChildByClassName(ViewGroup vg, String classNameKeyword) {
        int count = vg.getChildCount();
        for (int i = 0; i < count; i++) {
            View child = vg.getChildAt(i);
            if (child != null && child.getClass().getName().contains(classNameKeyword)) {
                return child;
            }
            if (child instanceof ViewGroup) {
                View res = findChildByClassName((ViewGroup) child, classNameKeyword);
                if (res != null) return res;
            }
        }
        return null;
    }

    private static TextView findRightTextView(ViewGroup vg) {
        int count = vg.getChildCount();
        TextView lastTv = null;
        for (int i = 0; i < count; i++) {
            View child = vg.getChildAt(i);
            if (child instanceof TextView) {
                CharSequence cs = ((TextView) child).getText();
                if (cs != null && !cs.toString().contains("Zzz") && !cs.toString().contains("检查更新")) {
                    lastTv = (TextView) child;
                }
            } else if (child instanceof ViewGroup) {
                TextView res = findRightTextView((ViewGroup) child);
                if (res != null) lastTv = res;
            }
        }
        return lastTv;
    }

    private static ImageView findArrowImageView(ViewGroup vg) {
        int count = vg.getChildCount();
        ImageView lastIv = null;
        for (int i = 0; i < count; i++) {
            View child = vg.getChildAt(i);
            if (child instanceof ImageView) {
                lastIv = (ImageView) child;
            } else if (child instanceof ViewGroup) {
                ImageView res = findArrowImageView((ViewGroup) child);
                if (res != null) lastIv = res;
            }
        }
        return lastIv;
    }
}