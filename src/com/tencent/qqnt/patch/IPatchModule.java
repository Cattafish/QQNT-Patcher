package com.tencent.qqnt.patch;

import android.content.Context;
import com.tencent.qqnt.kernel.nativeinterface.IQQNTWrapperSession;
import com.tencent.qqnt.kernel.nativeinterface.MsgElement;
import com.tencent.qqnt.kernel.nativeinterface.MsgRecord;

import java.util.ArrayList;
import java.util.List;

public interface IPatchModule {
    /** 模块唯一标识 (用于持久化标记) */
    String getId();

    /** 模块名称 (在设置中心中自动展示) */
    String getName();

    /** 默认开关状态 */
    default boolean defaultEnabled() { return true; }

    /** 是否启用 */
    default boolean isEnabled() {
        return ConfigManager.isModuleEnabled(getId(), defaultEnabled());
    }

    /** 设置开关状态 */
    default void setEnabled(boolean enabled) {
        ConfigManager.setModuleEnabled(getId(), enabled);
    }

    /** 是否在设置页面自动生成开关 */
    default boolean showInSettings() { return true; }

    // === 事件生命周期分发 (所有方法默认空实现，功能需要哪个就重写哪个) ===

    /** 引擎冷启动初始化 */
    default void onInit(Context context) {}

    /** 接收到 MSF 底层长连接推送 (防撤回等) */
    default byte[] onMsfPush(IQQNTWrapperSession session, String cmd, byte[] buf) { return buf; }

    /** 发送消息前置拦截 (发包属性探测、文本喵化改写等) */
    default void onSendMsg(ArrayList<MsgElement> elements) {}

    /** 接收远端新消息/更新列表 */
    default void onRecvMsg(List<MsgRecord> msgList) {}

    /** 聊天会话 (AIO) 气泡数据绑定 (闪照解密、气泡感知等) */
    default void onAIOMsgItem(MsgRecord record) {}

    /** 进入聊天会话 (AIO) */
    default void onAIOShow(Object delegate) {}

    /** 退出聊天会话 (AIO) */
    default void onAIOHide() {}
}
