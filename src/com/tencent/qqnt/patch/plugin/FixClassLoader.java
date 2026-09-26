package com.tencent.qqnt.patch.plugin;

import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class FixClassLoader extends ClassLoader {
    private final List<ClassLoader> loaders = new CopyOnWriteArrayList<>();
    private final ClassLoader mHostClassLoader;
    private final ClassLoader mBshClassLoader;

    public FixClassLoader(ClassLoader hostClassLoader, ClassLoader bshClassLoader) {
        super(getSystemClassLoader());
        this.mHostClassLoader = hostClassLoader;
        this.mBshClassLoader = bshClassLoader;

        loaders.add(getSystemClassLoader());
        if (bshClassLoader != null && !loaders.contains(bshClassLoader)) {
            loaders.add(bshClassLoader);
        }
        ClassLoader selfLoader = FixClassLoader.class.getClassLoader();
        if (selfLoader != null && !loaders.contains(selfLoader)) {
            loaders.add(selfLoader);
        }
        if (hostClassLoader != null && !loaders.contains(hostClassLoader)) {
            loaders.add(hostClassLoader);
        }
    }

    public static Class<?> getScriptClass(String name) {
        return null;
    }

    public static void registerScriptClass(String name, Class<?> clazz) {}

    public static void clearScriptClasses() {}

    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
        // ★ 核心屏蔽：QQ 宿主包自带了被腾讯严重裁剪的 protobuf-lite，缺少 newInstance(OutputStream)！
        // 当请求 com.google.protobuf.* 时，绝对不能走宿主，必须从包含完整官方实现的 bshClassLoader 中加载！
        if (name.startsWith("com.google.protobuf.") && mBshClassLoader != null) {
            try {
                return mBshClassLoader.loadClass(name);
            } catch (Exception ignored) {}
        }

        // 自动兼容 Android 内部类语法糖 (如 LinearLayout.LayoutParams -> android.widget.LinearLayout$LayoutParams)
        if (cleanInnerClassName(name)) {
            String candidate = name.replace(".LayoutParams", "$LayoutParams");
            if (!candidate.startsWith("android.")) {
                candidate = "android.widget." + candidate;
            }
            try {
                if (mHostClassLoader != null) return mHostClassLoader.loadClass(candidate);
            } catch (Throwable ignored) {}
        }

        for (ClassLoader loader : loaders) {
            try {
                return loader.loadClass(name);
            } catch (Exception ignored) {}
        }
        throw new ClassNotFoundException(name);
    }

    @Override
    public Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        // 关键隔离：拦截 protobuf 宿主污染
        if (name.startsWith("com.google.protobuf.") && mBshClassLoader != null) {
            try {
                return mBshClassLoader.loadClass(name);
            } catch (Exception ignored) {}
        }

        if (cleanInnerClassName(name)) {
            String candidate = name.replace(".LayoutParams", "$LayoutParams");
            if (!candidate.startsWith("android.")) {
                candidate = "android.widget." + candidate;
            }
            try {
                if (mHostClassLoader != null) return mHostClassLoader.loadClass(candidate);
            } catch (Throwable ignored) {}
        }

        for (ClassLoader loader : loaders) {
            try {
                return loader.loadClass(name);
            } catch (Exception ignored) {}
        }
        throw new ClassNotFoundException(name);
    }

    private boolean cleanInnerClassName(String name) {
        return name.contains(".LayoutParams") || name.endsWith("LayoutParams");
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
            } catch (Exception ignored) {}
        }
        return Collections.enumeration(urlList);
    }

    public void addClassLoader(ClassLoader classLoader) {
        if (classLoader != null && !loaders.contains(classLoader)) {
            loaders.add(classLoader);
        }
    }
}