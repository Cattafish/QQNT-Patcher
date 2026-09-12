package com.tencent.qqnt.patch;

import android.view.View;
import com.tencent.qqnt.kernel.nativeinterface.IKernelMsgListener;
import com.tencent.qqnt.kernel.nativeinterface.IQQNTWrapperSession;
import com.tencent.qqnt.kernel.nativeinterface.MsgElement;
import com.tencent.qqnt.kernel.nativeinterface.MsgRecord;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

public class PatchBridge {

    public static boolean isTabletModeEnabled() {
        return ConfigManager.isModuleEnabled("tablet_mode", false);
    }

    public static boolean shouldDropMsgNotify(Object msgNotifyItemObj) {
        if (msgNotifyItemObj == null) return false;
        if (!ConfigManager.isModuleEnabled("block_at_all_notify", true)) return false;
        try {
            Class<?> dClz = Class.forName("com.tencent.qqnt.notification.util.d");
            Object dInstance = dClz.getField("a").get(null);
            Method aMethod = dClz.getMethod("a", Class.forName("com.tencent.qqnt.kernel.nativeinterface.MsgNotifyItem"));
            Object recentContactInfo = aMethod.invoke(dInstance, msgNotifyItemObj);
            return shouldDropRecentContact(recentContactInfo);
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean shouldDropRecentContact(Object recentContactInfoObj) {
        if (recentContactInfoObj == null) return false;
        if (!ConfigManager.isModuleEnabled("block_at_all_notify", true)) return false;
        try {
            Class<?> clz = recentContactInfoObj.getClass();
            int chatType = clz.getField("chatType").getInt(recentContactInfoObj);
            int atType = clz.getField("atType").getInt(recentContactInfoObj);

            if (chatType == 2) {
                boolean isAtAll = (atType & 1) != 0;
                boolean isAtMe  = (atType & 4) != 0;

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

    // =========================================================================
    // 群文件下载次数 Smali 外部桥梁
    // =========================================================================

    public static void handleGroupFileList(Object fileListObj, Object responseObj) {
        com.tencent.qqnt.patch.modules.ShowDownloadTimesModule.handleGroupFileList(fileListObj, responseObj);
    }

    public static void handleTroopFileGetView(View view, Object adapter, int position) {
        com.tencent.qqnt.patch.modules.ShowDownloadTimesModule.handleTroopFileGetView(view, adapter, position);
    }

    public static String appendDownloadCountToStatusText(String originalStatus, Object lr5eObj) {
        return com.tencent.qqnt.patch.modules.ShowDownloadTimesModule.appendDownloadCountToStatusText(originalStatus, lr5eObj);
    }

    // =========================================================================
    // 核心生命周期与消息监听
    // =========================================================================

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
