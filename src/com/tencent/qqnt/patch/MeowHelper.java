package com.tencent.qqnt.patch;

import com.tencent.qqnt.kernel.nativeinterface.MsgElement;
import com.tencent.qqnt.kernel.nativeinterface.TextElement;

import java.io.File;
import java.util.ArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MeowHelper {

    private static File sMeowFlagFile = null;

    // 智能匹配末尾标点（。！!？?~～…）以及各种尴尬括号对（（）, (), ( ), （ ）, 【】, []）
    private static final Pattern TAIL_PATTERN = Pattern.compile(
            "^(.*?)([\\s。\\.！!？\\?~～…]*[\\(（\\[【]\\s*[\\)）\\]】][\\s。\\.！!？\\?~～…]*|[\\s。\\.！!？\\?~～…]+)$"
    );

    /**
     * 检查喵喵助手是否开启
     */
    public static boolean isMeowEnabled() {
        if (sMeowFlagFile == null) {
            try {
                Class<?> appClass = Class.forName("com.tencent.qphone.base.util.BaseApplication");
                android.content.Context ctx = (android.content.Context) appClass.getMethod("getContext").invoke(null);
                if (ctx != null) {
                    sMeowFlagFile = new File(ctx.getFilesDir(), "zzz_meow_helper_on");
                }
            } catch (Throwable ignored) {}
        }
        if (sMeowFlagFile != null) {
            return sMeowFlagFile.exists();
        }
        return false;
    }

    /**
     * 拦截并转换发送的消息列表
     */
    @SuppressWarnings("rawtypes")
    public static void handleSendMsg(ArrayList elements) {
        if (elements == null || elements.isEmpty()) return;
        if (!isMeowEnabled()) return;

        try {
            for (Object obj : elements) {
                if (obj instanceof MsgElement) {
                    MsgElement msgElem = (MsgElement) obj;
                    TextElement textElem = msgElem.textElement;
                    if (textElem != null && textElem.content != null && !textElem.content.trim().isEmpty()) {
                        textElem.content = transformToMeow(textElem.content);
                    }
                }
            }
        } catch (Throwable ignored) {}
    }

    /**
     * 喵化文本处理核心算法
     */
    public static String transformToMeow(String original) {
        if (original == null || original.isEmpty()) return original;

        // 1. 人称词汇替换: "你" -> "主人", "我" -> "猫猫"
        String text = original.replace("你", "主人").replace("我", "猫猫");

        // 2. 智能标点与尴尬括号位置识别
        Matcher matcher = TAIL_PATTERN.matcher(text);
        if (matcher.matches()) {
            String body = matcher.group(1); // 文本主体
            String tail = matcher.group(2); // 末尾标点或括号

            // 如果主体已经以 "喵" 结尾，不再重复拼接
            if (body.endsWith("喵")) {
                return body + tail;
            }
            // 将 "喵" 精准插入在标点或括号前面！
            return body + "喵" + tail;
        } else {
            // 没有末尾标点或括号，默认在末尾追加 "喵~"
            if (text.endsWith("喵") || text.endsWith("喵~")) {
                return text;
            }
            return text + "喵~";
        }
    }
}