package com.tencent.qqnt.patch;

import com.tencent.qqnt.kernel.nativeinterface.MsgElement;
import com.tencent.qqnt.kernel.nativeinterface.TextElement;

import java.io.File;
import java.util.ArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MeowHelper {

    private static File sMeowFlagFile = null;

    // 匹配 1：末尾标点、空括号对（（）, (), 【】, []）、单开括号（（, (, 【, [）
    private static final Pattern TAIL_PAIR_OR_OPEN_PATTERN = Pattern.compile(
            "^(.*?)([\\s。\\.！!？\\?~～…]*(?:[\\(（\\[【]\\s*[\\)）\\]】]|[\\(（\\[【])[\\s。\\.！!？\\?~～…]*|[\\s。\\.！!？\\?~～…]+)$"
    );

    // 匹配 2：末尾单闭括号（）, ), 】, ]）
    private static final Pattern TAIL_CLOSE_PATTERN = Pattern.compile(
            "^(.*?)([\\s。\\.！!？\\?~～…]*[\\)）\\]】][\\s。\\.！!？\\?~～…]*)$"
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

        // 2. 优先匹配：末尾标点、空括号对（）、单开括号（
        Matcher matcher = TAIL_PAIR_OR_OPEN_PATTERN.matcher(text);
        if (matcher.matches()) {
            String body = matcher.group(1);
            String tail = matcher.group(2);
            if (body.endsWith("喵")) {
                return body + tail;
            }
            return body + "喵" + tail;
        }

        // 3. 匹配末尾单闭括号 ），仅当主体没有对应开括号时才视为语气括号
        Matcher closeMatcher = TAIL_CLOSE_PATTERN.matcher(text);
        if (closeMatcher.matches()) {
            String body = closeMatcher.group(1);
            String tail = closeMatcher.group(2);

            // 检查 body 中是否含有未闭合的开括号
            boolean hasOpenBracket = body.contains("（") || body.contains("(") || body.contains("【") || body.contains("[");
            if (!hasOpenBracket) {
                // 没有开括号，说明是单闭括号语气
                if (body.endsWith("喵")) {
                    return body + tail;
                }
                return body + "喵" + tail;
            }
        }

        // 4. 默认情况（无特殊尾缀，或带有完整括号的内容），末尾追加 "喵~"
        if (text.endsWith("喵") || text.endsWith("喵~")) {
            return text;
        }
        return text + "喵~";
    }
}