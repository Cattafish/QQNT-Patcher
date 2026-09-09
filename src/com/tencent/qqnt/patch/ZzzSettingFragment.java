package com.tencent.qqnt.patch;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.SpannableString;
import android.text.Spanned;
import android.view.View;
import android.widget.CompoundButton;

import com.tencent.qqnt.patch.plugin.PluginManager;

import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class ZzzSettingFragment {

    public static final String EXTRA_FLAG = "open_zzz_settings";
    public static final String EXTRA_PAGE = "zzz_page_type";
    public static final String PAGE_CORE = "page_core";
    public static final String PAGE_PLUGINS = "page_plugins";

    public static void startCore(Context context) {
        start(context, PAGE_CORE);
    }

    public static void startPlugins(Context context) {
        start(context, PAGE_PLUGINS);
    }

    public static void start(Context context, String pageType) {
        try {
            ClassLoader cl = context.getClassLoader();
            Intent intent = new Intent();
            intent.putExtra(EXTRA_FLAG, true);
            intent.putExtra(EXTRA_PAGE, pageType);

            Class<?> fragmentClass = cl.loadClass("com.tencent.mobileqq.setting.generalSetting.GeneralSettingFragment");
            Class<?> activityClass = cl.loadClass("com.tencent.mobileqq.activity.QPublicFragmentActivity");

            Method startMethod = activityClass.getMethod("start", Context.class, Intent.class, Class.class);
            startMethod.invoke(null, context, intent, fragmentClass);
        } catch (Throwable t) {
            ToastHelper.show(context, "打开设置失败: " + t.getMessage());
        }
    }

    public static boolean onHijackViewCreated(Object fragment, View view, Bundle bundle) {
        try {
            ConfigManager.triggerColdStartUpdateCheck();

            Method getActivityMethod = fragment.getClass().getMethod("getActivity");
            Activity activity = (Activity) getActivityMethod.invoke(fragment);
            if (activity == null || activity.getIntent() == null) return false;
            if (!activity.getIntent().getBooleanExtra(EXTRA_FLAG, false)) return false;

            String pageType = activity.getIntent().getStringExtra(EXTRA_PAGE);
            if (pageType == null) pageType = PAGE_CORE;

            ClassLoader cl = activity.getClassLoader();

            try {
                Method setTitleMethod = fragment.getClass().getMethod("setTitle", CharSequence.class);
                if (PAGE_PLUGINS.equals(pageType)) {
                    setTitleMethod.invoke(fragment, "动态脚本");
                    PLog.i("Settings", "打开动态脚本控制台");
                } else {
                    setTitleMethod.invoke(fragment, "Zzz 设置");
                    PLog.i("Settings", "打开 Zzz 核心设置");
                }
            } catch (Throwable ignored) {}

            renderSettingsList(fragment, activity, cl, pageType);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    public static void renderSettingsList(Object fragment, Activity activity, ClassLoader cl, String pageType) {
        try {
            Object adapter = null;
            for (Method m : fragment.getClass().getMethods()) {
                if (m.getParameterTypes().length == 0 &&
                    m.getReturnType().getName().endsWith("QUIListItemAdapter")) {
                    adapter = m.invoke(fragment);
                    break;
                }
            }
            if (adapter == null) return;

            List<Object> groups = new ArrayList<>();
            CharSequence centeredItalicFooter = createCenteredItalicFooter("Created by Zcraft with ❤️");

            if (PAGE_PLUGINS.equals(pageType)) {
                // =====================================================
                // 页面 B: 动态脚本独立管理页
                // =====================================================
                List<Object> pluginItems = new ArrayList<>();
                List<PluginManager.PluginItem> allPlugins = PluginManager.scanAllPlugins(activity);

                pluginItems.add(createNativeClickableItem(cl, "重新扫描与重载全部脚本", "刷新", true, false, v -> {
                    ToastHelper.show(activity, "正在重载全部脚本...");
                    PluginManager.reloadAll(activity, () -> {
                        if (!activity.isFinishing() && !activity.isDestroyed()) {
                            renderSettingsList(fragment, activity, cl, pageType);
                            ToastHelper.show(activity, "重载完成并已刷新");
                        }
                    });
                }));

                if (allPlugins.isEmpty()) {
                    pluginItems.add(createNativeTextItem(cl, "暂无外部脚本", "放入zzz/plugins"));
                } else {
                    for (PluginManager.PluginItem item : allPlugins) {
                        final String pId = item.id;
                        final String pName = item.name;

                        pluginItems.add(createNativeSwitchItem(
                                cl, pName + " (" + pId + ")", item.isEnabled,
                                (btn, checked) -> {
                                    ToastHelper.show(activity, pName + (checked ? " 正在启动..." : " 正在停止..."));
                                    PluginManager.setPluginActive(activity, pId, checked, () -> {
                                        if (!activity.isFinishing() && !activity.isDestroyed()) {
                                            renderSettingsList(fragment, activity, cl, pageType);
                                            ToastHelper.show(activity, pName + (checked ? " 已启动" : " 已停止"));
                                        }
                                    });
                                }
                        ));

                        if (item.isEnabled && item.menuItems != null && !item.menuItems.isEmpty()) {
                            for (Map.Entry<String, String> entry : item.menuItems.entrySet()) {
                                final String actionName = entry.getKey();
                                final String actionCallback = entry.getValue();
                                pluginItems.add(createNativeClickableItem(
                                        cl, "  ↳ " + actionName, "打开界面", true, false,
                                        v -> PluginManager.invokePluginMenu(pId, actionCallback, 2, "", actionName)
                                ));
                            }
                        }
                    }
                }
                groups.add(createNativeGroup(cl, "已安装插件 (" + allPlugins.size() + ")", centeredItalicFooter, pluginItems));

            } else {
                // =====================================================
                // 页面 A: Zzz 核心设置页
                // =====================================================

                // 1. 核心功能
                List<Object> funcItems = new ArrayList<>();
                for (IPatchModule module : ModuleManager.getModules()) {
                    if (!module.showInSettings()) continue;
                    final IPatchModule m = module;
                    funcItems.add(createNativeSwitchItem(
                            cl, m.getName(), m.isEnabled(),
                            (btn, checked) -> {
                                m.setEnabled(checked);
                                ToastHelper.show(activity, m.getName() + (checked ? " 已开启" : " 已关闭"));
                            }
                    ));
                }
                groups.add(createNativeGroup(cl, "核心功能 (" + funcItems.size() + " 个模块)", "", funcItems));

                // 2. 高级与调试
                List<Object> advancedItems = new ArrayList<>();
                advancedItems.add(createNativeSwitchItem(
                        cl, "调试日志输出 (Logcat)", ConfigManager.isDebugLogEnabled(),
                        (btn, checked) -> {
                            ConfigManager.setDebugLogEnabled(checked);
                            ToastHelper.show(activity, "调试日志" + (checked ? " 已开启" : " 已关闭"));
                        }
                ));
                advancedItems.add(createNativeClickableItem(
                        cl, "实时运行日志", "查看 (" + PLog.getBufferCount() + "条)", true, false,
                        v -> PLog.showLogDialog(activity)
                ));
                groups.add(createNativeGroup(cl, "高级与调试", "", advancedItems));

                // 3. 关于
                List<Object> aboutItems = new ArrayList<>();
                aboutItems.add(createNativeTextItem(cl, "当前版本", ConfigManager.VERSION));

                boolean hasNew = ConfigManager.hasNewVersion();
                String updateText = hasNew ? "有新版本可用" : "已是最新版本";
                boolean showArrow = hasNew;

                // ★ 准确传递 hasNew 作为 showRedDot 参数！
                aboutItems.add(createNativeClickableItem(
                        cl, "检查更新", updateText, showArrow, hasNew,
                        v -> UpdateHelper.checkUpdate(activity, () -> {
                            if (!activity.isFinishing() && !activity.isDestroyed()) {
                                renderSettingsList(fragment, activity, cl, pageType);
                            }
                        })
                ));

                aboutItems.add(createNativeClickableItem(cl, "Telegram 频道", "加入", true, false, v -> {
                    try {
                        Intent tgIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(ConfigManager.TG_CHANNEL_URL));
                        tgIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        activity.startActivity(tgIntent);
                    } catch (Throwable t) {
                        ToastHelper.show(activity, "打开链接失败: " + t.getMessage());
                    }
                }));
                aboutItems.add(createNativeClickableItem(cl, "GitHub 仓库", "前往", true, false, v -> {
                    try {
                        Intent ghIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(ConfigManager.GITHUB_REPO_URL));
                        ghIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        activity.startActivity(ghIntent);
                    } catch (Throwable t) {
                        ToastHelper.show(activity, "打开链接失败: " + t.getMessage());
                    }
                }));

                groups.add(createNativeGroup(cl, "关于", centeredItalicFooter, aboutItems));
            }

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

    private static CharSequence createCenteredItalicFooter(String text) {
        if (text == null || text.isEmpty()) return "";
        try {
            String fullText = "\n" + text;
            SpannableString sp = new SpannableString(fullText);
            int len = fullText.length();

            try {
                Class<?> alignEnumClz = Class.forName("android.text.Layout$Alignment");
                Object alignCenter = Enum.valueOf((Class<Enum>) alignEnumClz, "ALIGN_CENTER");
                Class<?> spanClz = Class.forName("android.text.style.AlignmentSpan$Standard");
                Constructor<?> ctor = spanClz.getConstructor(alignEnumClz);
                Object alignSpan = ctor.newInstance(alignCenter);
                sp.setSpan(alignSpan, 0, len, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } catch (Throwable ignored) {}

            try {
                Class<?> styleSpanClz = Class.forName("android.text.style.StyleSpan");
                Constructor<?> styleCtor = styleSpanClz.getConstructor(int.class);
                Object italicSpan = styleCtor.newInstance(2);
                sp.setSpan(italicSpan, 1, len, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } catch (Throwable ignored) {}

            return sp;
        } catch (Throwable t) {
            return text;
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

    private static Object createNativeClickableItem(ClassLoader cl, String title, String rightText, boolean showArrow, boolean showRedDot, View.OnClickListener listener) throws Exception {
        Class<?> xbdClass = cl.loadClass("com.tencent.mobileqq.widget.listitem.x$b$d");
        Constructor<?> xbdConst = xbdClass.getConstructor(CharSequence.class);
        Object leftObj = xbdConst.newInstance(title);

        Class<?> xcgClass = cl.loadClass("com.tencent.mobileqq.widget.listitem.x$c$g");
        // ★ 核心修复：第三个参数直接传 showRedDot，让 x$c$g 原生激活红点标志！
        Object rightObj = newInstanceSmart(xcgClass, new Object[]{rightText, showArrow, showRedDot});

        // ★ 再次双重确保调用 rightObj.g(showRedDot)
        if (rightObj != null) {
            try {
                Method gMethod = xcgClass.getMethod("g", boolean.class);
                gMethod.invoke(rightObj, showRedDot);
            } catch (Throwable ignored) {}
        }

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

        // ★ 挂载 View 绘制阶段监听，确保 QUIBadge 双保险点亮
        if (showRedDot) {
            try {
                Class<?> gClass = cl.loadClass("com.tencent.mobileqq.widget.listitem.g");
                Object gProxy = Proxy.newProxyInstance(cl, new Class<?>[]{gClass}, (proxy, method, args) -> {
                    if ("G".equals(method.getName()) && args != null && args.length == 1 && (args[0] instanceof View)) {
                        QUIBadgeHelper.attachNativeBadge((View) args[0], rightText, true, showArrow);
                    }
                    return null;
                });
                for (Method m : item.getClass().getMethods()) {
                    Class<?>[] pts = m.getParameterTypes();
                    if ("w".equals(m.getName()) && pts.length == 1 && pts[0] == gClass) {
                        m.invoke(item, gProxy);
                        break;
                    }
                }
            } catch (Throwable ignored) {}
        }

        return item;
    }

    private static Object createNativeGroup(ClassLoader cl, String topTitle, CharSequence bottomFooter, List<Object> items) throws Exception {
        Class<?> itemBaseClass = cl.loadClass("com.tencent.mobileqq.widget.listitem.a");
        Object itemArray = Array.newInstance(itemBaseClass, items.size());
        for (int i = 0; i < items.size(); i++) {
            Array.set(itemArray, i, items.get(i));
        }

        Class<?> groupClass = cl.loadClass("com.tencent.mobileqq.widget.listitem.Group");
        Constructor<?> groupConst = groupClass.getConstructor(CharSequence.class, CharSequence.class, itemArray.getClass());
        return groupConst.newInstance(topTitle != null ? topTitle : "", bottomFooter != null ? bottomFooter : "", itemArray);
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
}