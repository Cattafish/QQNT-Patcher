package me.yxp.qfun.utils.hook;

import java.lang.reflect.Member;

public class HookExtensionsKt {
    public interface HookCallback {
        Object invoke(HookParam param);
    }
    public static class HookParam {
        public Object thisObject;
        public Object[] args;
        public Object result;
        public Object getThisObject() { return thisObject; }
        public Object[] getArgs() { return args; }
        public Object getResult() { return result; }
        public void setResult(Object res) { this.result = res; }
    }
    public static class Unhook {
        public void unhook() {}
    }
    public static Unhook hookBefore(Member member, Object priority, Object callback) {
        return new Unhook();
    }
    public static Unhook hookAfter(Member member, Object priority, Object callback) {
        return new Unhook();
    }
}
