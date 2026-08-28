package com.tencent.qqnt.kernel.nativeinterface;
import com.tencent.qqnt.kernelpublic.nativeinterface.Contact;
import com.tencent.qqnt.kernelpublic.nativeinterface.JsonGrayElement;
public interface IKernelMsgService {
    void addLocalJsonGrayTipMsg(Contact contact, JsonGrayElement jsonGrayElement, boolean z, boolean z2, IAddJsonGrayTipMsgCallback callback);
}
