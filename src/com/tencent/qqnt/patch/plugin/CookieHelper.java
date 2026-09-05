package com.tencent.qqnt.patch.plugin;

import com.tencent.qqnt.patch.AppContext;
import java.lang.reflect.Method;
import java.util.Map;

public class CookieHelper {

    private static Object getTicketManager() {
        try {
            Object runtime = AppContext.getAppRuntime();
            if (runtime == null) return null;
            Method getManager = runtime.getClass().getMethod("getManager", int.class);
            return getManager.invoke(runtime, 2); // 2 = TicketManager
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static String getRealSkey() {
        try {
            Object tm = getTicketManager();
            if (tm == null) return "";
            Method m = tm.getClass().getMethod("getRealSkey", String.class);
            String skey = (String) m.invoke(tm, MsgSender.getMyUin());
            return skey != null ? skey : "";
        } catch (Throwable ignored) {
            return "";
        }
    }

    public static String getSkey() {
        try {
            Object tm = getTicketManager();
            if (tm == null) return "";
            Method m = tm.getClass().getMethod("getSkey", String.class);
            String skey = (String) m.invoke(tm, MsgSender.getMyUin());
            return skey != null ? skey : "";
        } catch (Throwable ignored) {
            return "";
        }
    }

    public static String getStweb() {
        try {
            Object tm = getTicketManager();
            if (tm == null) return "";
            Method m = tm.getClass().getMethod("getStweb", String.class);
            String stweb = (String) m.invoke(tm, MsgSender.getMyUin());
            return stweb != null ? stweb : "";
        } catch (Throwable ignored) {
            return "";
        }
    }

    public static String getPskey(String domain) {
        if (domain == null || domain.isEmpty()) return "";
        try {
            Object runtime = AppContext.getAppRuntime();
            if (runtime == null) return "";
            Class<?> pskeyClz = Class.forName("com.tencent.mobileqq.pskey.api.IPskeyManager");
            Method getRuntimeService = runtime.getClass().getMethod("getRuntimeService", Class.class, String.class);
            Object pskeyMgr = getRuntimeService.invoke(runtime, pskeyClz, "");
            if (pskeyMgr != null) {
                Method getPskeySync = pskeyMgr.getClass().getMethod("getPskeySync", String[].class);
                Map<?, ?> map = (Map<?, ?>) getPskeySync.invoke(pskeyMgr, (Object) new String[]{domain});
                if (map != null && map.containsKey(domain)) {
                    Object val = map.get(domain);
                    return val != null ? String.valueOf(val) : "";
                }
            }
        } catch (Throwable ignored) {}
        return "";
    }

    public static String getPt4Token(String domain) {
        if (domain == null || domain.isEmpty()) return "";
        try {
            Object tm = getTicketManager();
            if (tm == null) return "";
            Method m = tm.getClass().getMethod("getPt4Token", String.class, String.class);
            String token = (String) m.invoke(tm, MsgSender.getMyUin(), domain);
            return token != null ? token : "";
        } catch (Throwable ignored) {
            return "";
        }
    }

    public static long getBkn(String key) {
        if (key == null || key.isEmpty()) return 0L;
        long hash = 5381L;
        for (int i = 0; i < key.length(); i++) {
            hash += (hash << 5) + (int) key.charAt(i);
        }
        return 2147483647L & hash;
    }

    public static String getGTK(String domain) {
        String pskey = getPskey(domain);
        if (pskey.isEmpty()) pskey = getSkey();
        return String.valueOf(getBkn(pskey));
    }
}
