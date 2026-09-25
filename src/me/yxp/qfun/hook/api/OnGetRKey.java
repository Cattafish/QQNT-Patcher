package me.yxp.qfun.hook.api;

import com.tencent.qqnt.patch.plugin.RKeyManager;

public class OnGetRKey {
    public static final OnGetRKey INSTANCE = new OnGetRKey();

    public String getFriendRkey() {
        String k = RKeyManager.getFriendRKey();
        return k != null ? k : "";
    }

    public String getGroupRkey() {
        String k = RKeyManager.getGroupRKey();
        return k != null ? k : "";
    }
}
