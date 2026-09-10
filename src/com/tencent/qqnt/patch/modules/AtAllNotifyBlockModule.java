package com.tencent.qqnt.patch.modules;

import com.tencent.qqnt.patch.IPatchModule;

public class AtAllNotifyBlockModule implements IPatchModule {
    @Override public String getId() { return "block_at_all_notify"; }
    @Override public String getName() { return "静默 @全体成员 弹窗通知"; }
    @Override public boolean defaultEnabled() { return true; }
}
