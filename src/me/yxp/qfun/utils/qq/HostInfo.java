package me.yxp.qfun.utils.qq;

import android.content.Context;
import android.content.pm.PackageInfo;
import com.tencent.qqnt.patch.AppContext;

public class HostInfo {
    public static final HostInfo INSTANCE = new HostInfo();

    public String getPackageName() {
        Context ctx = AppContext.get();
        return ctx != null ? ctx.getPackageName() : "com.tencent.mobileqq";
    }

    public String getVersionName() {
        try {
            Context ctx = AppContext.get();
            if (ctx != null) {
                PackageInfo pi = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0);
                return pi.versionName;
            }
        } catch (Throwable ignored) {}
        return "9.3.55";
    }

    public long getVersionCode() {
        return 1000L;
    }

    public boolean isTIM() {
        return "com.tencent.tim".equals(getPackageName());
    }
}
