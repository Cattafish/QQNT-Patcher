package me.yxp.qfun.utils.qq;

import com.tencent.qqnt.patch.plugin.MsgSender;

public class FriendTool {
    public static final FriendTool INSTANCE = new FriendTool();

    public String getUidFromUin(String uin) {
        return MsgSender.getUidFromUin(uin);
    }

    public String getUinFromUid(String uid) {
        return MsgSender.getUinFromUid(uid);
    }
}
