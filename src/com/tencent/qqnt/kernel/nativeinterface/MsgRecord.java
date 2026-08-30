package com.tencent.qqnt.kernel.nativeinterface;

import java.util.ArrayList;

public class MsgRecord {
    public int msgType;
    public int subMsgType;
    public int chatType;
    public long msgId;
    public String guildName;
    public ArrayList<MsgElement> elements;
    public byte[] extInfoForUI;
}
