package de.robv.android.xposed;

import java.lang.reflect.Member;

public class XposedBridge {
    public static XC_MethodHook.Unhook hookMethod(Member hookMethod, XC_MethodHook callback) {
        return new XC_MethodHook.Unhook(callback);
    }
    public static void log(String text) {}
    public static void log(Throwable t) {}
}
