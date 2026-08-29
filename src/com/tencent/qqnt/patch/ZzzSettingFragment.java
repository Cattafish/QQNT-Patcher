package com.tencent.qqnt.patch;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
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
    private static final String TG_CHANNEL_URL = "https://t.me/ZcraftMod";
    private static final String GITHUB_REPO_URL = "https://github.com/Cattafish/QQNT-Patcher";

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

    public static boolean onHijackViewCreated(Object fragment, View view, Bundle bundle) {
        try {
            Method getActivityMethod = fragment.getClass().getMethod("getActivity");
            Activity activity = (Activity) getActivityMethod.invoke(fragment);
            if (activity == null || activity.getIntent() == null) return false;
            if (!activity.getIntent().getBooleanExtra(EXTRA_FLAG, false)) return false;

            ClassLoader cl = activity.getClassLoader();

            // 1. 设置 QQ 原厂顶栏标题
            try {
                Method setTitleMethod = fragment.getClass().getMethod("setTitle", CharSequence.class);
                setTitleMethod.invoke(fragment, "Zzz 设置");
            } catch (Throwable ignored) {}

            // 2. 动态自适应获取 QUIListItemAdapter
            Object adapter = null;
            for (Method m : fragment.getClass().getMethods()) {
                if (m.getParameterTypes().length == 0 &&
                    m.getReturnType().getName().endsWith("QUIListItemAdapter")) {
                    adapter = m.invoke(fragment);
                    break;
                }
            }
            if (adapter == null) return false;

            // 3. 构建 QQ 原厂卡片列表
            List<Object> groups = new ArrayList<>();

            // --- 卡片 1: 功能 (Switch 开关列表) ---
            List<Object> funcItems = new ArrayList<>();

            // 开关 1: 消息防撤回
            File antiRevokeFlag = new File(activity.getFilesDir(), "zzz_anti_revoke_off");
            boolean isAntiRevokeOn = !antiRevokeFlag.exists();
            funcItems.add(createNativeSwitchItem(
                    cl, "消息防撤回", isAntiRevokeOn,
                    (btn, checked) -> {
                        try {
                            if (checked) antiRevokeFlag.delete();
                            else antiRevokeFlag.createNewFile();
                        } catch (Throwable ignored) {}
                        Toast.makeText(activity, "消息防撤回" + (checked ? " 已开启" : " 已关闭"), Toast.LENGTH_SHORT).show();
                    }
            ));

            // 开关 2: 喵喵助手 (自动替换文字并在句末加喵)
            File meowFlag = new File(activity.getFilesDir(), "zzz_meow_helper_on");
            boolean isMeowOn = meowFlag.exists();
            funcItems.add(createNativeSwitchItem(
                    cl, "喵喵助手", isMeowOn,
                    (btn, checked) -> {
                        try {
                            if (checked) meowFlag.createNewFile();
                            else meowFlag.delete();
                        } catch (Throwable ignored) {}
                        Toast.makeText(activity, "喵喵助手" + (checked ? " 已开启喵~" : " 已关闭"), Toast.LENGTH_SHORT).show();
                    }
            ));
            groups.add(createNativeGroup(cl, "功能", funcItems));

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
            List<Object> aboutItems = new ArrayList<>();
            aboutItems.add(createNativeTextItem(cl, "版本号", "v0.0.1"));
            aboutItems.add(createNativeClickableItem(cl, "Telegram 频道", "加入", v -> {
                try {
                    Intent tgIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(TG_CHANNEL_URL));
                    tgIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    activity.startActivity(tgIntent);
                } catch (Throwable t) {
                    Toast.makeText(activity, "打开链接失败: " + t.getMessage(), Toast.LENGTH_SHORT).show();
                }
            }));
            aboutItems.add(createNativeClickableItem(cl, "GitHub 仓库", "前往", v -> {
                try {
                    Intent ghIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(GITHUB_REPO_URL));
                    ghIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    activity.startActivity(ghIntent);
                } catch (Throwable t) {
                    Toast.makeText(activity, "打开链接失败: " + t.getMessage(), Toast.LENGTH_SHORT).show();
                }
            }));
            groups.add(createNativeGroup(cl, "关于", aboutItems));

            // 4. 提交卡片数组给 Adapter
            Class<?> groupClass = cl.loadClass("com.tencent.mobileqq.widget.listitem.Group");
            Object groupArray = Array.newInstance(groupClass, groups.size());
            for (int i = 0; i < groups.size(); i++) {
                Array.set(groupArray, i, groups.get(i));
            }

            Method setConfigsMethod = null;
            for (Method m : adapter.getClass().getMethods()) {
                Class<?>[] pts = m.getParameterTypes();
                if (pts.length == 1 && pts[0].isArray() &&
                    pts[0].getComponentType().getName().endsWith("Group")) {
                    setConfigsMethod = m;
                    break;
                }
            }

            if (setConfigsMethod != null) {
                setConfigsMethod.invoke(adapter, new Object[]{groupArray});
                return true;
            }

            return false;
        } catch (Throwable t) {
            return false;
        }
    }

    private static Object createNativeSwitchItem(ClassLoader cl, String title, boolean isChecked, CompoundButton.OnCheckedChangeListener listener) throws Exception {
        Class<?> xbdClass = cl.loadClass("com.tencent.mobileqq.widget.listitem.x$b$d");
        Constructor<?> xbdConst = xbdClass.getConstructor(CharSequence.class);
        Object leftObj = xbdConst.newInstance(title);

        Class<?> xcfClass = cl.loadClass("com.tencent.mobileqq.widget.listitem.x$c$f");
        Object rightObj = newInstanceSmart(xcfClass, new Object[]{isChecked, listener, true});

        Class<?> xClass = cl.loadClass("com.tencent.mobileqq.widget.listitem.x");
        Constructor<?> xConst = xClass.getConstructor(
                cl.loadClass("com.tencent.mobileqq.widget.listitem.x$b"),
                cl.loadClass("com.tencent.mobileqq.widget.listitem.x$c")
        );
        return xConst.newInstance(leftObj, rightObj);
    }

    private static Object createNativeTextItem(ClassLoader cl, String title, String rightText) throws Exception {
        Class<?> xbdClass = cl.loadClass("com.tencent.mobileqq.widget.listitem.x$b$d");
        Constructor<?> xbdConst = xbdClass.getConstructor(CharSequence.class);
        Object leftObj = xbdConst.newInstance(title);

        Class<?> xcgClass = cl.loadClass("com.tencent.mobileqq.widget.listitem.x$c$g");
        Object rightObj = newInstanceSmart(xcgClass, new Object[]{rightText, false, false});

        Class<?> xClass = cl.loadClass("com.tencent.mobileqq.widget.listitem.x");
        Constructor<?> xConst = xClass.getConstructor(
                cl.loadClass("com.tencent.mobileqq.widget.listitem.x$b"),
                cl.loadClass("com.tencent.mobileqq.widget.listitem.x$c")
        );
        return xConst.newInstance(leftObj, rightObj);
    }

    private static Object createNativeClickableItem(ClassLoader cl, String title, String rightText, View.OnClickListener listener) throws Exception {
        Class<?> xbdClass = cl.loadClass("com.tencent.mobileqq.widget.listitem.x$b$d");
        Constructor<?> xbdConst = xbdClass.getConstructor(CharSequence.class);
        Object leftObj = xbdConst.newInstance(title);

        Class<?> xcgClass = cl.loadClass("com.tencent.mobileqq.widget.listitem.x$c$g");
        Object rightObj = newInstanceSmart(xcgClass, new Object[]{rightText, true, false});

        Class<?> xClass = cl.loadClass("com.tencent.mobileqq.widget.listitem.x");
        Constructor<?> xConst = xClass.getConstructor(
                cl.loadClass("com.tencent.mobileqq.widget.listitem.x$b"),
                cl.loadClass("com.tencent.mobileqq.widget.listitem.x$c")
        );
        Object item = xConst.newInstance(leftObj, rightObj);

        if (listener != null) {
            for (Method m : item.getClass().getMethods()) {
                Class<?>[] pts = m.getParameterTypes();
                if (pts.length == 1 && pts[0] == View.OnClickListener.class) {
                    m.invoke(item, listener);
                    break;
                }
            }
        }
        return item;
    }

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