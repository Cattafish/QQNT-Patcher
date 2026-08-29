package com.tencent.qqnt.patch;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.Toast;

import java.io.File;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class ZzzSettingFragment {

    public static final String EXTRA_FLAG = "open_zzz_settings";

    /**
     * 启动 QQ 原生二级设置页面
     */
    public static void start(Context context) {
        try {
            ClassLoader cl = context.getClassLoader();
            Intent intent = new Intent();
            intent.putExtra(EXTRA_FLAG, true);

            Class<?> fragmentClass = cl.loadClass("com.tencent.mobileqq.setting.generalSetting.GeneralSettingFragment");
            Class<?> activityClass = cl.loadClass("com.tencent.mobileqq.activity.QPublicFragmentActivity");

            Method startMethod = activityClass.getMethod("start", Context.class, Intent.class, Class.class);
            startMethod.invoke(null, context, intent, fragmentClass);
        } catch (Throwable t) {
            Toast.makeText(context, "打开原生设置失败: " + t.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * 劫持 GeneralSettingFragment.onViewCreated，渲染 100% QQ 原生设置卡片
     * @return true: 拦截原生通用设置并展示 Zzz 设置; false: 放行原生通用设置
     */
    public static boolean onHijackViewCreated(Object fragment, View view, Bundle bundle) {
        try {
            Method getActivityMethod = fragment.getClass().getMethod("getActivity");
            Activity activity = (Activity) getActivityMethod.invoke(fragment);
            if (activity == null || activity.getIntent() == null) return false;
            if (!activity.getIntent().getBooleanExtra(EXTRA_FLAG, false)) return false;

            ClassLoader cl = activity.getClassLoader();

            // 1. 设置 QQ 官方原厂顶栏标题
            Method setTitleMethod = fragment.getClass().getMethod("setTitle", CharSequence.class);
            setTitleMethod.invoke(fragment, "Zzz 设置");

            // 2. 获取 QQ 原生的列表适配器 QUIListItemAdapter
            Method getAdapterMethod = fragment.getClass().getMethod("Zc");
            Object adapter = getAdapterMethod.invoke(fragment);
            if (adapter == null) return false;

            // 3. 构建 QQ 原生卡片列表
            List<Object> groups = new ArrayList<>();

            // --- 卡片 1: 功能 (带 QQ 原生 Switch 开关) ---
            File antiRevokeFlag = new File(activity.getFilesDir(), "zzz_anti_revoke_off");
            boolean isAntiRevokeOn = !antiRevokeFlag.exists();
            Object itemAntiRevoke = createNativeSwitchItem(
                    cl, "消息防撤回", isAntiRevokeOn,
                    (btn, checked) -> {
                        try {
                            if (checked) antiRevokeFlag.delete();
                            else antiRevokeFlag.createNewFile();
                        } catch (Throwable ignored) {}
                        Toast.makeText(activity, "消息防撤回" + (checked ? " 已开启" : " 已关闭"), Toast.LENGTH_SHORT).show();
                    }
            );
            groups.add(createNativeGroup(cl, "功能", Collections.singletonList(itemAntiRevoke)));

            // --- 卡片 2: 高级 ---
            File debugFlag = new File(activity.getFilesDir(), "zzz_debug_log_on");
            boolean isDebugOn = debugFlag.exists();
            Object itemDebug = createNativeSwitchItem(
                    cl, "调试日志输出", isDebugOn,
                    (btn, checked) -> {
                        try {
                            if (checked) debugFlag.createNewFile();
                            else debugFlag.delete();
                        } catch (Throwable ignored) {}
                        Toast.makeText(activity, "调试日志" + (checked ? " 已开启" : " 已关闭"), Toast.LENGTH_SHORT).show();
                    }
            );
            groups.add(createNativeGroup(cl, "高级", Collections.singletonList(itemDebug)));

            // --- 卡片 3: 关于 ---
            Object itemVersion = createNativeTextItem(cl, "版本号", "v0.0.1");
            groups.add(createNativeGroup(cl, "关于", Collections.singletonList(itemVersion)));

            // 4. 将卡片数组直接喂给 QQ 原生适配器渲染 (adapter.k0(groups))
            Class<?> groupClass = cl.loadClass("com.tencent.mobileqq.widget.listitem.Group");
            Object groupArray = Array.newInstance(groupClass, groups.size());
            for (int i = 0; i < groups.size(); i++) {
                Array.set(groupArray, i, groups.get(i));
            }

            Method setConfigsMethod = adapter.getClass().getMethod("k0", groupArray.getClass());
            setConfigsMethod.invoke(adapter, new Object[]{groupArray});

            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 创建 QQ 原生 Switch 开关项 (基于 x$c$f)
     */
    private static Object createNativeSwitchItem(ClassLoader cl, String title, boolean isChecked, CompoundButton.OnCheckedChangeListener listener) throws Exception {
        // 左侧标题: new x$b$d(title)
        Class<?> xbdClass = cl.loadClass("com.tencent.mobileqq.widget.listitem.x$b$d");
        Constructor<?> xbdConst = xbdClass.getConstructor(CharSequence.class);
        Object leftObj = xbdConst.newInstance(title);

        // 右侧开关: new x$c$f(isChecked, listener, isEnabled)
        Class<?> xcfClass = cl.loadClass("com.tencent.mobileqq.widget.listitem.x$c$f");
        Object rightObj = newInstanceSmart(xcfClass, new Object[]{isChecked, listener, true});

        // 组合为 ListItem: new x(left, right)
        Class<?> xClass = cl.loadClass("com.tencent.mobileqq.widget.listitem.x");
        Constructor<?> xConst = xClass.getConstructor(
                cl.loadClass("com.tencent.mobileqq.widget.listitem.x$b"),
                cl.loadClass("com.tencent.mobileqq.widget.listitem.x$c")
        );
        return xConst.newInstance(leftObj, rightObj);
    }

    /**
     * 创建 QQ 原生带右侧文字的展示项 (基于 x$c$g)
     */
    private static Object createNativeTextItem(ClassLoader cl, String title, String rightText) throws Exception {
        Class<?> xbdClass = cl.loadClass("com.tencent.mobileqq.widget.listitem.x$b$d");
        Constructor<?> xbdConst = xbdClass.getConstructor(CharSequence.class);
        Object leftObj = xbdConst.newInstance(title);

        // 右侧文字: new x$c$g(rightText, showArrow=false, hasRedDot=false)
        Class<?> xcgClass = cl.loadClass("com.tencent.mobileqq.widget.listitem.x$c$g");
        Object rightObj = newInstanceSmart(xcgClass, new Object[]{rightText, false, false});

        Class<?> xClass = cl.loadClass("com.tencent.mobileqq.widget.listitem.x");
        Constructor<?> xConst = xClass.getConstructor(
                cl.loadClass("com.tencent.mobileqq.widget.listitem.x$b"),
                cl.loadClass("com.tencent.mobileqq.widget.listitem.x$c")
        );
        return xConst.newInstance(leftObj, rightObj);
    }

    /**
     * 创建 QQ 原生分组卡片 Group
     */
    private static Object createNativeGroup(ClassLoader cl, String topTitle, List<Object> items) throws Exception {
        Class<?> itemBaseClass = cl.loadClass("com.tencent.mobileqq.widget.listitem.a");
        Object itemArray = Array.newInstance(itemBaseClass, items.size());
        for (int i = 0; i < items.size(); i++) {
            Array.set(itemArray, i, items.get(i));
        }

        Class<?> groupClass = cl.loadClass("com.tencent.mobileqq.widget.listitem.Group");
        Constructor<?> groupConst = groupClass.getConstructor(CharSequence.class, CharSequence.class, itemArray.getClass());
        return groupConst.newInstance(topTitle, "", itemArray);
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
                        args[i] = (i < preferredArgs.length && preferredArgs[i] instanceof Number) ? ((Number) preferredArgs[i]).intValue() : 0;
                    } else if (pt == boolean.class || pt == Boolean.class) {
                        args[i] = (i < preferredArgs.length && preferredArgs[i] instanceof Boolean) ? (Boolean) preferredArgs[i] : false;
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