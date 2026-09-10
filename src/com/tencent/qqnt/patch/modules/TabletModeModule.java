package com.tencent.qqnt.patch.modules;

import com.tencent.qqnt.patch.IPatchModule;

public class TabletModeModule implements IPatchModule {
    @Override 
    public String getId() { 
        return "tablet_mode"; 
    }

    @Override 
    public String getName() { 
        return "强制平板模式 (需重启QQ)"; 
    }

    @Override 
    public boolean defaultEnabled() { 
        return false; 
    }
}