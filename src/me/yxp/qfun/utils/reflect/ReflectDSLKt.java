package me.yxp.qfun.utils.reflect;

import java.lang.reflect.Method;

public class ReflectDSLKt {
    public static class MethodFinder {
        public String name;
        public void setName(String name) { this.name = name; }
    }
    public interface FinderAction {
        void invoke(MethodFinder finder);
    }
    public static Method findMethod(Class<?> clazz, Object actionObj) {
        if (clazz == null) return null;
        MethodFinder finder = new MethodFinder();
        if (actionObj != null) {
            try {
                Method m = actionObj.getClass().getMethod("invoke", Object.class);
                m.invoke(actionObj, finder);
            } catch (Throwable ignored) {}
        }
        if (finder.name != null) {
            for (Method m : clazz.getDeclaredMethods()) {
                if (m.getName().equals(finder.name)) {
                    m.setAccessible(true);
                    return m;
                }
            }
        }
        return null;
    }
}
