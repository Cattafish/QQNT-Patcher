package com.tencent.qqnt.kernel.nativeinterface;

import com.tencent.qqnt.kernelpublic.nativeinterface.Contact;
import com.tencent.qqnt.kernelpublic.nativeinterface.JsonGrayElement;
import java.util.ArrayList;
import java.util.HashMap;

public interface IKernelMsgService {
    void addLocalJsonGrayTipMsg(Contact contact, JsonGrayElement jsonGrayElement, boolean z, boolean z2, IAddJsonGrayTipMsgCallback callback);

    public static class CppProxy implements IKernelMsgService {
        public void addLocalJsonGrayTipMsg(Contact contact, JsonGrayElement jsonGrayElement, boolean z, boolean z2, IAddJsonGrayTipMsgCallback callback) {}
        public void sendMsg(long msgId, Contact contact, ArrayList elements, HashMap map, IOperateCallback callback) {}
        public void sendMsg(long msgId, Contact contact, ArrayList elements, HashMap map) {}
        public void addSendMsg(long msgId, Contact contact, ArrayList elements, HashMap map, IOperateCallback callback) {}
        public void addSendMsg(long msgId, Contact contact, ArrayList elements, HashMap map) {}
        public long addKernelMsgListener(IKernelMsgListener listener) { return 0L; }
        public void forwardMsg(ArrayList msgIds, Contact srcContact, ArrayList dstContacts, Object msgAttrs, Object callback) {}
    }
}
