package de.robv.android.xposed;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

public class XposedHelpers {
    public static Object getObjectField(Object obj, String fieldName) {
        try {
            Field f = findField(obj.getClass(), fieldName);
            return f.get(obj);
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }
    public static void setObjectField(Object obj, String fieldName, Object val) {
        try {
            Field f = findField(obj.getClass(), fieldName);
            f.set(obj, val);
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }
    public static Field findField(Class<?> clazz, String fieldName) {
        Class<?> clz = clazz;
        while (clz != null) {
            try {
                Field f = clz.getDeclaredField(fieldName);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException e) {
                clz = clz.getSuperclass();
            }
        }
        throw new NoSuchFieldError(fieldName);
    }
    public static Object callMethod(Object obj, String methodName, Object... args) {
        try {
            Class<?>[] types = new Class<?>[args.length];
            for (int i = 0; i < args.length; i++) {
                types[i] = args[i] == null ? Object.class : args[i].getClass();
            }
            Method m = findMethodExact(obj.getClass(), methodName, types);
            return m.invoke(obj, args);
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }
    public static Method findMethodExact(Class<?> clazz, String methodName, Class<?>... parameterTypes) {
        Class<?> clz = clazz;
        while (clz != null) {
            try {
                Method m = clz.getDeclaredMethod(methodName, parameterTypes);
                m.setAccessible(true);
                return m;
            } catch (NoSuchMethodException e) {
                clz = clz.getSuperclass();
            }
        }
        throw new NoSuchMethodError(methodName);
    }
    public static Class<?> findClass(String className, ClassLoader classLoader) {
        try {
            return classLoader.loadClass(className);
        } catch (ClassNotFoundException e) {
            throw new RuntimeException(e);
        }
    }
}
