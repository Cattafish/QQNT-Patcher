package com.tencent.qqnt.patch;

import com.tencent.qqnt.kernel.nativeinterface.MsgElement;
import com.tencent.qqnt.kernel.nativeinterface.TextElement;

import java.util.ArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MeowHelper {

    private static final Pattern TAIL_PAIR_OR_OPEN_PATTERN = Pattern.compile(
            "^(.*?)([\\s。\\.！!？\\?~～…]*(?:[\\(（\\[【]\\s*[\\)）\\]】]|[\\(（\\[【])[\\s。\\.！!？\\?~～…]*|[\\s。\\.！!？\\?~～…]+)$"
    );

    private static final Pattern TAIL_CLOSE_PATTERN = Pattern.compile(
            "^(.*?)([\\s。\\.！!？\\?~～…]*[\\)）\\]】][\\s。\\.！!？\\?~～…]*)$"
    );

    public static boolean isMeowEnabled() {
        return ConfigManager.isMeowEnabled();
    }

    @SuppressWarnings("rawtypes")
    public static void handleSendMsg(ArrayList elements) {
        if (elements == null || elements.isEmpty()) return;
        if (!ConfigManager.isMeowEnabled()) return;

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

    public static String transformToMeow(String original) {
        if (original == null || original.isEmpty()) return original;

        String text = original.replace("你", "主人").replace("我", "猫猫");

        Matcher matcher = TAIL_PAIR_OR_OPEN_PATTERN.matcher(text);
        if (matcher.matches()) {
            String body = matcher.group(1);
            String tail = matcher.group(2);
            if (body.endsWith("喵")) {
                return body + tail;
            }
            return body + "喵" + tail;
        }

        Matcher closeMatcher = TAIL_CLOSE_PATTERN.matcher(text);
        if (closeMatcher.matches()) {
            String body = closeMatcher.group(1);
            String tail = closeMatcher.group(2);

            boolean hasOpenBracket = body.contains("（") || body.contains("(") || body.contains("【") || body.contains("[");
            if (!hasOpenBracket) {
                if (body.endsWith("喵")) {
                    return body + tail;
                }
                return body + "喵" + tail;
            }
        }

        if (text.endsWith("喵") || text.endsWith("喵~")) {
            return text;
        }
        return text + "喵~";
    }
}