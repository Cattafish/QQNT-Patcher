package com.tencent.qqnt.patch;

import android.util.Log;
import com.tencent.qqnt.kernel.nativeinterface.IGetAioFirstViewLatestMsgCallback;
import com.tencent.qqnt.kernel.nativeinterface.IKernelMsgListener;
import com.tencent.qqnt.kernel.nativeinterface.IMsgOperateCallback;
import com.tencent.qqnt.kernel.nativeinterface.MsgElement;
import com.tencent.qqnt.kernel.nativeinterface.MsgRecord;
import com.tencent.qqnt.kernel.nativeinterface.PicElement;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;

public class FlashPicHelper {

    private static final String TAG = "QQ_DEBUG";
    private static File sDisableFlagFile = null;

    /**
     * 跨进程检测：闪照破解开关是否开启
     */
    public static boolean isFlashPicDecryptEnabled() {
        if (sDisableFlagFile == null) {
            try {
                Class<?> appClass = Class.forName("com.tencent.qphone.base.util.BaseApplication");
                android.content.Context ctx = (android.content.Context) appClass.getMethod("getContext").invoke(null);
                if (ctx != null) {
                    sDisableFlagFile = new File(ctx.getFilesDir(), "zzz_flash_pic_off");
                }
            } catch (Throwable ignored) {}
        }
        if (sDisableFlagFile != null) {
            return !sDisableFlagFile.exists();
        }
        return true;
    }

    /**
     * 核心拦截 1：清洗 Native 跨 JNI 桥接模型 en (eo.b() 返回值)
     */
    public static void handleNativeBridgeEn(Object en) {
        if (en == null) return;
        if (!isFlashPicDecryptEnabled()) return;
        try {
            Class<?> clz = en.getClass();

            // 1. 调用 f0(int) 强制设置 picSubType = 0
            try {
                Method f0 = clz.getMethod("f0", int.class);
                f0.invoke(en, 0);
            } catch (Throwable t) {
                Field fa = clz.getDeclaredField("a");
                fa.setAccessible(true);
                fa.setInt(en, 0);
            }

            // 2. 调用 T(Boolean) 强制设置 isFlashPic = false
            try {
                Method tMethod = clz.getMethod("T", Boolean.class);
                tMethod.invoke(en, Boolean.FALSE);
            } catch (Throwable t) {
                Field fC = clz.getDeclaredField("C");
                fC.setAccessible(true);
                fC.set(en, Boolean.FALSE);
            }

            // 3. 调用 l0(String) 修改 summary
            try {
                Method l0 = clz.getMethod("l0", String.class);
                l0.invoke(en, "[图片]");
            } catch (Throwable t) {
                Field ft = clz.getDeclaredField("t");
                ft.setAccessible(true);
                ft.set(en, "[图片]");
            }

            Log.d(TAG, "[FLASH_PIC_SUCCESS] ★ Native Bridge Model (en) 解密脱壳完成！");
        } catch (Throwable t) {
            Log.e(TAG, "[FLASH_PIC_ERROR] handleNativeBridgeEn 异常", t);
        }
    }

    /**
     * 核心拦截 2：Getter 辅助过滤方法 (en / PicElement)
     */
    public static Boolean fixIsFlashPic(Boolean original) {
        if (!isFlashPicDecryptEnabled()) {
            return original;
        }
        return Boolean.FALSE;
    }

    public static int fixPicSubType(int subType) {
        if (!isFlashPicDecryptEnabled()) {
            return subType;
        }
        if (subType == 8194 || (subType & 8192) != 0) {
            return 0;
        }
        return subType;
    }

    public static String fixSummary(String original) {
        if (!isFlashPicDecryptEnabled()) {
            return original;
        }
        if (original != null && original.contains("闪照")) {
            return "[图片]";
        }
        return original;
    }

    /**
     * 核心拦截 3：UI 渲染层模型 (AIOElementType$f) 清洗
     */
    public static void handleAIOElementPic(Object obj) {
        if (obj == null) return;
        if (!isFlashPicDecryptEnabled()) return;
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

    /**
     * 核心拦截 4：PicElement / MsgRecord 构造与实时数据流脱壳
     */
    public static void handlePicElement(PicElement pic) {
        if (pic == null) return;
        if (!isFlashPicDecryptEnabled()) return;

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
                            if (args != null && args.length > 0) {
                                if (args[0] instanceof List) {
                                    handleMsgList((List<?>) args[0]);
                                } else if (args[0] instanceof MsgRecord) {
                                    decryptSingleRecord((MsgRecord) args[0]);
                                }
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
        if (!isFlashPicDecryptEnabled()) return;

        try {
            boolean isFlash = false;
            int originSubMsgType = record.subMsgType;

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

            if (isFlash) {
                Log.d(TAG, "[FLASH_PIC_SUCCESS] ★★★ 消息体闪照解密成功！msgId=" + record.msgId);
            }
        } catch (Throwable t) {
            Log.e(TAG, "[FLASH_PIC_ERROR] 解密异常", t);
        }
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