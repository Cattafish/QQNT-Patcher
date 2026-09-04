package com.tencent.qqnt.patch.plugin;

import android.util.Log;
import java.lang.reflect.Field;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

public class FixClassLoader extends ClassLoader {
    private static final String TAG = "QQ_DEBUG";
    private final List<ClassLoader> loaders = new CopyOnWriteArrayList<>();
    private Object mBshClassManager = null;
    
    // ★ 全局动态类注册表：存放在运行期由脚本定义的类与接口
    private static final Map<String, Class<?>> sScriptDefinedClasses = new ConcurrentHashMap<>();

    public FixClassLoader(ClassLoader hostClassLoader, ClassLoader bshClassLoader) {
        super(getSystemClassLoader());
        loaders.add(getSystemClassLoader());
        if (bshClassLoader != null && !loaders.contains(bshClassLoader)) {
            loaders.add(bshClassLoader);
        }
        if (hostClassLoader != null && !loaders.contains(hostClassLoader)) {
            loaders.add(hostClassLoader);
        }
        try {
            ClassLoader selfLoader = FixClassLoader.class.getClassLoader();
            if (selfLoader != null && !loaders.contains(selfLoader)) {
                loaders.add(selfLoader);
            }
        } catch (Throwable ignored) {}
    }

    public static void registerScriptClass(String name, Class<?> clazz) {
        if (name != null && clazz != null) {
            sScriptDefinedClasses.put(name, clazz);
            Log.i(TAG, "[FixClassLoader] 动态注册脚本类: " + name + " -> " + clazz.getName());
        }
    }

    public static Class<?> getScriptClass(String name) {
        if (name == null) return null;
        return sScriptDefinedClasses.get(name);
    }

    public static void clearScriptClasses() {
        sScriptDefinedClasses.clear();
    }

    public void setBshClassManager(Object classManager) {
        this.mBshClassManager = classManager;
    }

    public void addClassLoader(ClassLoader classLoader) {
        if (classLoader != null && !loaders.contains(classLoader)) {
            loaders.add(0, classLoader);
        }
    }

    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
        // 1. 优先直接从动态注册表中秒级返回
        Class<?> scriptClz = sScriptDefinedClasses.get(name);
        if (scriptClz != null) {
            return scriptClz;
        }

        // 2. 遍历已知 ClassLoader 链表查找
        for (ClassLoader loader : loaders) {
            try {
                return loader.loadClass(name);
            } catch (Throwable ignored) {}
        }

        // 3. 继承链深度穿透
        if (mBshClassManager != null) {
            try {
                Field cacheField = getFieldInHierarchy(mBshClassManager.getClass(), "absoluteClassCache");
                if (cacheField != null) {
                    cacheField.setAccessible(true);
                    Map<?, ?> cache = (Map<?, ?>) cacheField.get(mBshClassManager);
                    if (cache != null && cache.containsKey(name)) {
                        Object obj = cache.get(name);
                        if (obj instanceof Class) {
                            Class<?> clz = (Class<?>) obj;
                            registerScriptClass(name, clz);
                            addClassLoader(clz.getClassLoader());
                            return clz;
                        }
                    }
                }
            } catch (Throwable ignored) {}
        }

        throw new ClassNotFoundException(name);
    }

    @Override
    public Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        Class<?> loaded = findLoadedClass(name);
        if (loaded != null) return loaded;

        try {
            return findClass(name);
        } catch (ClassNotFoundException ignored) {}

        return super.loadClass(name, resolve);
    }

    private static Field getFieldInHierarchy(Class<?> clazz, String fieldName) {
        Class<?> cur = clazz;
        while (cur != null) {
            try {
                return cur.getDeclaredField(fieldName);
            } catch (NoSuchFieldException e) {
                cur = cur.getSuperclass();
            }
        }
        return null;
    }

    @Override
    public URL getResource(String name) {
        for (ClassLoader loader : loaders) {
            URL res = loader.getResource(name);
            if (res != null) return res;
        }
        return super.getResource(name);
    }

    @Override
    public Enumeration<URL> getResources(String name) {
        List<URL> urlList = new ArrayList<>();
        for (ClassLoader loader : loaders) {
            try {
                Enumeration<URL> resources = loader.getResources(name);
                while (resources.hasMoreElements()) {
                    urlList.add(resources.nextElement());
                }
            } catch (Throwable ignored) {}
        }
        return Collections.enumeration(urlList);
    }
}
