package com.tencent.qqnt.kernel.nativeinterface;

import java.util.ArrayList;

public interface IKernelMsgListener {
    void onRecvMsg(ArrayList<MsgRecord> msgList);
    void onMsgInfoListUpdate(ArrayList<MsgRecord> msgList);
}
