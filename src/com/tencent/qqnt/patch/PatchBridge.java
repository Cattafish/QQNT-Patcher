package com.tencent.qqnt.patch;

import android.view.View;
import com.tencent.qqnt.kernel.nativeinterface.IKernelMsgListener;
import com.tencent.qqnt.kernel.nativeinterface.IQQNTWrapperSession;
import com.tencent.qqnt.kernel.nativeinterface.MsgElement;
import com.tencent.qqnt.kernel.nativeinterface.MsgRecord;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

public class PatchBridge {

    public static boolean isTabletModeEnabled() {
        return ConfigManager.isModuleEnabled("tablet_mode", false);
    }

    public static boolean shouldDropMsgNotify(Object msgNotifyItemObj) {
        return com.tencent.qqnt.patch.modules.AtAllNotifyBlockModule.shouldDropMsgNotify(msgNotifyItemObj);
    }

    public static boolean shouldDropRecentContact(Object recentContactInfoObj) {
        return com.tencent.qqnt.patch.modules.AtAllNotifyBlockModule.shouldDropRecentContact(recentContactInfoObj);
    }

    public static void handleDispatchRespMsg(Object msfMessagePair) {
        if (msfMessagePair == null) return;
        com.tencent.qqnt.patch.plugin.RKeyManager.onDispatchRespMsg(msfMessagePair);
        com.tencent.qqnt.patch.modules.AutoRemarkApkModule.onDispatchRespMsg(msfMessagePair);
    }

    public static void handleGroupFileListResponse(Object responseObj) {
        com.tencent.qqnt.patch.modules.ShowDownloadTimesModule.handleGroupFileListResponse(responseObj);
    }

    public static void handleGroupFileList(Object fileListObj, Object responseObj) {
        com.tencent.qqnt.patch.modules.ShowDownloadTimesModule.handleGroupFileList(fileListObj, responseObj);
    }

    public static void handleTroopFileGetView(View view, Object adapter, int position) {
        com.tencent.qqnt.patch.modules.ShowDownloadTimesModule.handleTroopFileGetView(view, adapter, position);
    }

    public static String appendDownloadCountToStatusText(String originalStatus, Object fileItemObj) {
        return com.tencent.qqnt.patch.modules.ShowDownloadTimesModule.appendDownloadCountToStatusText(originalStatus, fileItemObj);
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
            // 联动 QFun 的 OnSendMsg 监听器 (支持 ModifyPicSize 等脚本动态改包)
            me.yxp.qfun.hook.api.OnSendMsg.INSTANCE.dispatch(elements);
        } catch (Throwable ignored) {}

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
