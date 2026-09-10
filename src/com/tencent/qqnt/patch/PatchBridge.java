package com.tencent.qqnt.patch;

import com.tencent.qqnt.kernel.nativeinterface.IKernelMsgListener;
import com.tencent.qqnt.kernel.nativeinterface.IQQNTWrapperSession;
import com.tencent.qqnt.kernel.nativeinterface.MsgElement;
import com.tencent.qqnt.kernel.nativeinterface.MsgRecord;
import com.tencent.qqnt.kernel.nativeinterface.RecentContactInfo;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

public class PatchBridge {

    public static boolean isTabletModeEnabled() {
        return ConfigManager.isModuleEnabled("tablet_mode", false);
    }

    /**
     * ★ 手术刀核心 1：拦截实时消息 MsgNotifyItem
     */
    public static boolean shouldDropMsgNotify(Object msgNotifyItemObj) {
        if (msgNotifyItemObj == null) return false;
        if (!ConfigManager.isModuleEnabled("block_at_all_notify", true)) return false;
        try {
            Class<?> dClz = Class.forName("com.tencent.qqnt.notification.util.d");
            Field aField = dClz.getField("a");
            Object dInstance = aField.get(null);
            Method aMethod = dClz.getMethod("a", Class.forName("com.tencent.qqnt.kernel.nativeinterface.MsgNotifyItem"));
            Object recentContactInfo = aMethod.invoke(dInstance, msgNotifyItemObj);
            return shouldDropRecentContact(recentContactInfo);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * ★ 手术刀核心 2：纯位运算判定 (严防误杀单人@我)
     */
    public static boolean shouldDropRecentContact(Object recentContactInfoObj) {
        if (recentContactInfoObj == null) return false;
        if (!ConfigManager.isModuleEnabled("block_at_all_notify", true)) return false;
        try {
            Class<?> clz = recentContactInfoObj.getClass();
            int chatType = clz.getField("chatType").getInt(recentContactInfoObj);
            int atType = clz.getField("atType").getInt(recentContactInfoObj);

            // 仅对群聊消息生效 (chatType == 2)
            if (chatType == 2) {
                boolean isAtAll = (atType & 1) != 0; // 第0位：@全体成员
                boolean isAtMe  = (atType & 4) != 0; // 第2位：定向 @我

                // ★ 纯 @全体成员 且 没有 @我 时才丢弃通知
                if (isAtAll && !isAtMe) {
                    PLog.i("NotifyBlock", "命中静默：丢弃纯 @全体成员 实时通知 (atType=" + atType + ")");
                    return true;
                }
            }
        } catch (Throwable t) {
            PLog.e("NotifyBlock", "判断失败", t);
        }
        return false;
    }

    public static byte[] handleMsfPush(IQQNTWrapperSession session, String cmd, byte[] buf) {
        ConfigManager.triggerColdStartUpdateCheck();
        if (session != null) {
            com.tencent.qqnt.patch.plugin.MsgSender.setSession(session);
        }
        return ModuleManager.dispatchMsfPush(session, cmd, buf);
    }

    @SuppressWarnings("unchecked")
    public static void handleSendMsg(ArrayList elements) {
        if (elements == null || elements.isEmpty()) return;
        try {
            com.tencent.qqnt.patch.plugin.PluginManager.dispatchSendMsg(elements);
        } catch (Throwable ignored) {}
        ModuleManager.dispatchSendMsg((ArrayList<MsgElement>) elements);
    }

    public static void handleAIOMsgItem(MsgRecord record) {
        if (record == null) return;
        ModuleManager.dispatchAIOMsgItem(record);
    }

    @SuppressWarnings("unchecked")
    public static void handleRecvMsgList(List list) {
        if (list == null || list.isEmpty()) return;
        ModuleManager.dispatchRecvMsg((List<MsgRecord>) list);
    }

    public static void handleAIOShow(Object delegate) {
        ModuleManager.dispatchAIOShow(delegate);
    }

    public static void handleAIOHide() {
        ModuleManager.dispatchAIOHide();
    }

    public static IKernelMsgListener wrapKernelMsgListener(IKernelMsgListener original) {
        if (original == null) return null;
        try {
            ClassLoader cl = original.getClass().getClassLoader();
            if (cl == null) cl = PatchBridge.class.getClassLoader();

            return (IKernelMsgListener) Proxy.newProxyInstance(
                    cl,
                    new Class<?>[]{IKernelMsgListener.class},
                    (proxy, method, args) -> {
                        try {
                            String mName = method.getName();
                            if (("onRecvMsg".equals(mName) || "onMsgInfoListUpdate".equals(mName))
                                    && args != null && args.length > 0 && (args[0] instanceof List)) {
                                List<?> list = (List<?>) args[0];
                                handleRecvMsgList(list);
                                if ("onRecvMsg".equals(mName)) {
                                    com.tencent.qqnt.patch.plugin.PluginManager.dispatchRecvMsg(list);
                                }
                            }
                        } catch (Throwable ignored) {}

                        try {
                            return method.invoke(original, args);
                        } catch (InvocationTargetException ite) {
                            throw ite.getTargetException();
                        }
                    }
            );
        } catch (Throwable t) {
            return original;
        }
    }
}
