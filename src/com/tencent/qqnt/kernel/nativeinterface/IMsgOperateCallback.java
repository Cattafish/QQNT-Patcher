package com.tencent.qqnt.kernel.nativeinterface;

import java.util.ArrayList;

public interface IMsgOperateCallback {
    void onResult(int result, String errMsg, ArrayList<MsgRecord> msgList);
}
