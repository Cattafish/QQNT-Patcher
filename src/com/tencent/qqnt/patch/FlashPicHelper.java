package com.tencent.qqnt.patch;

import android.util.Log;
import com.tencent.qqnt.kernel.nativeinterface.IKernelMsgListener;
import com.tencent.qqnt.kernel.nativeinterface.MsgElement;
import com.tencent.qqnt.kernel.nativeinterface.MsgRecord;
import com.tencent.qqnt.kernel.nativeinterface.PicElement;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;

public class FlashPicHelper {

    private static final String TAG = "QQ_DEBUG";

    public static boolean isFlashPicDecryptEnabled() {
        return ConfigManager.isFlashPicDecryptEnabled();
    }

    public static void handleNativeBridgeEn(Object en) {
        if (en == null) return;
        if (!ConfigManager.isFlashPicDecryptEnabled()) return;
        try {
            Class<?> clz = en.getClass();
            try {
                Method f0 = clz.getMethod("f0", int.class);
                f0.invoke(en, 0);
            } catch (Throwable t) {
                Field fa = clz.getDeclaredField("a");
                fa.setAccessible(true);
                fa.setInt(en, 0);
            }
            try {
                Method tMethod = clz.getMethod("T", Boolean.class);
                tMethod.invoke(en, Boolean.FALSE);
            } catch (Throwable t) {
                Field fC = clz.getDeclaredField("C");
                fC.setAccessible(true);
                fC.set(en, Boolean.FALSE);
            }
            try {
                Method l0 = clz.getMethod("l0", String.class);
                l0.invoke(en, "[图片]");
            } catch (Throwable t) {
                Field ft = clz.getDeclaredField("t");
                ft.setAccessible(true);
                ft.set(en, "[图片]");
            }
        } catch (Throwable ignored) {}
    }

    public static void handleAIOElementPic(Object obj) {
        if (obj == null) return;
        if (!ConfigManager.isFlashPicDecryptEnabled()) return;
        try {
            Class<?> clz = obj.getClass();
            Field[] fields = clz.getDeclaredFields();
            for (Field f : fields) {
                f.setAccessible(true);
                String name = f.getName();
                if ("E".equals(name) || "isFlashPic".equalsIgnoreCase(name)) {
                    if (f.getType() == boolean.class) {
                        f.setBoolean(obj, false);
                    } else if (f.getType() == Boolean.class) {
                        f.set(obj, Boolean.FALSE);
                    }
                } else if ("h".equals(name) || "subType".equalsIgnoreCase(name)) {
                    if (f.getType() == int.class) {
                        int v = f.getInt(obj);
                        if (v == 8194 || (v & 8192) != 0) {
                            f.setInt(obj, 0);
                        }
                    }
                } else if ("i".equals(name) || "summary".equalsIgnoreCase(name)) {
                    if (f.getType() == String.class) {
                        String s = (String) f.get(obj);
                        if (s != null && s.contains("闪照")) {
                            f.set(obj, "[图片]");
                        }
                    }
                }
            }
        } catch (Throwable ignored) {}
    }

    public static void handlePicElement(PicElement pic) {
        if (pic == null) return;
        if (!ConfigManager.isFlashPicDecryptEnabled()) return;
        try {
            if (Boolean.TRUE.equals(pic.isFlashPic) || pic.picSubType == 8194 || (pic.picSubType & 8192) != 0) {
                pic.isFlashPic = Boolean.FALSE;
                pic.picSubType = 0;
                pic.summary = "[图片]";
                cleanEmojiPropertiesReflectively(pic);
            }
        } catch (Throwable ignored) {}
    }

    public static void handleMsgRecord(MsgRecord record) {
        if (record == null) return;
        decryptSingleRecord(record);
        // ★ 核心修复：彻底剔除 updateActiveAIO 调用，防止后台新消息污染当前 AIO 界面群号
    }

    public static IKernelMsgListener wrapKernelMsgListener(IKernelMsgListener original) {
        if (original == null) return null;
        try {
            ClassLoader cl = original.getClass().getClassLoader();
            return (IKernelMsgListener) Proxy.newProxyInstance(
                    cl,
                    new Class<?>[]{IKernelMsgListener.class},
                    (proxy, method, args) -> {
                        try {
                            String mName = method.getName();
                            if ("onRecvMsg".equals(mName) && args != null && args.length > 0 && (args[0] instanceof List)) {
                                List<?> list = (List<?>) args[0];
                                handleMsgList(list);
                                com.tencent.qqnt.patch.plugin.PluginManager.dispatchRecvMsg(list);
                            } else if (args != null && args.length > 0 && (args[0] instanceof List)) {
                                handleMsgList((List<?>) args[0]);
                            } else if (args != null && args.length > 0 && (args[0] instanceof MsgRecord)) {
                                decryptSingleRecord((MsgRecord) args[0]);
                            }
                        } catch (Throwable ignored) {}
                        return method.invoke(original, args);
                    }
            );
        } catch (Throwable t) {
            return original;
        }
    }

    public static void handleMsgList(List<?> msgList) {
        if (msgList == null || msgList.isEmpty()) return;
        for (Object obj : msgList) {
            if (obj instanceof MsgRecord) {
                decryptSingleRecord((MsgRecord) obj);
            }
        }
    }

    public static void decryptSingleRecord(MsgRecord record) {
        if (record == null) return;
        if (!ConfigManager.isFlashPicDecryptEnabled()) return;

        try {
            boolean isFlash = false;
            if (record.subMsgType == 8194 || (record.subMsgType & 8192) != 0) {
                isFlash = true;
                record.subMsgType = 0;
                record.msgType = 2;
                record.extInfoForUI = null;
            }

            if (record.elements != null) {
                for (MsgElement elem : record.elements) {
                    if (elem == null) continue;
                    PicElement pic = elem.picElement;
                    if (pic != null) {
                        boolean matchPic = Boolean.TRUE.equals(pic.isFlashPic)
                                || pic.picSubType == 8194
                                || (pic.picSubType & 8192) != 0
                                || (pic.summary != null && pic.summary.contains("闪照"));

                        if (matchPic) {
                            isFlash = true;
                            pic.isFlashPic = Boolean.FALSE;
                            pic.picSubType = 0;
                            pic.summary = "[图片]";
                            elem.extBufForUI = null;
                            cleanEmojiPropertiesReflectively(pic);
                        }
                    }
                }
            }
        } catch (Throwable ignored) {}
    }

    private static void cleanEmojiPropertiesReflectively(PicElement pic) {
        try {
            for (Field f : pic.getClass().getDeclaredFields()) {
                f.setAccessible(true);
                String name = f.getName().toLowerCase();
                if (name.contains("emoji") || name.contains("zplan") || name.contains("mall")) {
                    if (!f.getType().isPrimitive()) {
                        f.set(pic, null);
                    }
                }
            }
        } catch (Throwable ignored) {}
    }
}