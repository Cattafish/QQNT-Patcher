package com.tencent.qqnt.patch.modules;

import com.tencent.qqnt.patch.IPatchModule;

public class TodoNotifyBlockModule implements IPatchModule {
    @Override public String getId() { return "block_todo_notify"; }
    @Override public String getName() { return "静默 群待办 弹窗通知"; }
    @Override public boolean defaultEnabled() { return true; }
}
