package com.tencent.qqnt.patch.modules;

import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import com.tencent.qqnt.patch.ConfigManager;
import com.tencent.qqnt.patch.IPatchModule;
import com.tencent.qqnt.patch.PLog;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ShowDownloadTimesModule implements IPatchModule {

    private static final String TAG = "GroupFile";
    private static final Map<String, Integer> sCountMap = Collections.synchronizedMap(new HashMap<>());

    @Override public String getId() { return "show_file_download_count"; }
    @Override public String getName() { return "群文件显示下载次数"; }
    @Override public boolean defaultEnabled() { return true; }

    // =========================================================================
    // 1. GroupFileListRepo 回包拦截点：装载 20 条下载记录
    // =========================================================================
    public static void handleGroupFileList(Object fileListObj, Object responseObj) {
        if (fileListObj == null || responseObj == null) return;
        if (!ConfigManager.isModuleEnabled("show_file_download_count", true)) return;

        try {
            Map<String, Integer> extractedMap = extractDownloadCounts(responseObj);
            if (!extractedMap.isEmpty()) {
                sCountMap.putAll(extractedMap);
                PLog.i(TAG, "成功捕获并装载 " + extractedMap.size() + " 个文件的下载记录");
            }
        } catch (Throwable t) {
            PLog.e(TAG, "handleGroupFileList 异常", t);
        }
    }

    // =========================================================================
    // 2. ★ 原生状态文字追加点 (截图成功同款)
    // =========================================================================
    public static String appendDownloadCountToStatusText(String originalStatus, Object lr5eObj) {
        if (originalStatus == null || lr5eObj == null) return originalStatus;
        if (!ConfigManager.isModuleEnabled("show_file_download_count", true)) return originalStatus;

        try {
            String fileId = extractFileIdFromLr5e(lr5eObj);
            if (fileId == null || fileId.isEmpty()) return originalStatus;

            Integer count = sCountMap.get(fileId);
            if (count == null) count = sCountMap.get(fileId.toLowerCase());
            if (count == null || count <= 0) return originalStatus;

            if (originalStatus.endsWith("次")) return originalStatus;

            String newStatus = originalStatus + " · " + count + " 次";
            PLog.once(TAG, fileId, "已在原生状态后成功注入: [" + originalStatus + "] -> [" + newStatus + "]");
            return newStatus;

        } catch (Throwable t) {
            PLog.e(TAG, "appendDownloadCountToStatusText 异常", t);
        }
        return originalStatus;
    }

    private static String extractFileIdFromLr5e(Object lr5e) {
        try {
            Field dField = lr5e.getClass().getDeclaredField("d");
            dField.setAccessible(true);
            Object lr5b = dField.get(lr5e);
            if (lr5b != null) {
                Field fIdField = lr5b.getClass().getDeclaredField("d");
                fIdField.setAccessible(true);
                Object idObj = fIdField.get(lr5b);
                if (idObj != null) {
                    return idObj.toString().replaceAll("^/+", "").trim();
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    // =========================================================================
    // 3. 经典旧版群文件列表处理 (TroopFileShowAdapter.getView)
    // =========================================================================
    public static void handleTroopFileGetView(View view, Object adapter, int position) {
        if (view == null || adapter == null) return;
        if (!ConfigManager.isModuleEnabled("show_file_download_count", true)) return;
        try {
            if (!(view instanceof ViewGroup)) return;
            Class<?> adapterClz = adapter.getClass();
            Method getItemM = adapterClz.getMethod("getItem", int.class);
            Object fileInfo = getItemM.invoke(adapter, position);
            if (fileInfo == null) return;

            String infoStr = fileInfo.toString();
            Matcher m = Pattern.compile("uint32_download_times=(\\d+)").matcher(infoStr);
            if (m.find()) {
                String countStr = m.group(1);
                appendCountToTextView((ViewGroup) view, fileInfo, countStr);
            }
        } catch (Throwable ignored) {}
    }

    private static void appendCountToTextView(ViewGroup vg, Object fileInfo, String countStr) {
        int count = vg.getChildCount();
        for (int i = 0; i < count; i++) {
            View child = vg.getChildAt(i);
            if (child instanceof TextView) {
                TextView tv = (TextView) child;
                if (tv.getTag() == fileInfo) {
                    CharSequence cs = tv.getText();
                    if (cs != null && !cs.toString().endsWith("次")) {
                        tv.setText(cs + " · " + countStr + " 次");
                    }
                    return;
                }
            } else if (child instanceof ViewGroup) {
                appendCountToTextView((ViewGroup) child, fileInfo, countStr);
            }
        }
    }

    // =========================================================================
    // 4. 回包提取下载次数
    // =========================================================================
    private static Map<String, Integer> extractDownloadCounts(Object responseObj) {
        Map<String, Integer> countMap = new HashMap<>();
        try {
            List<?> cList = null;
            for (Field f : responseObj.getClass().getFields()) {
                if (List.class.isAssignableFrom(f.getType())) {
                    cList = (List<?>) f.get(responseObj);
                    if (cList != null && !cList.isEmpty()) break;
                }
            }
            if (cList == null) {
                for (Field f : responseObj.getClass().getDeclaredFields()) {
                    if (List.class.isAssignableFrom(f.getType())) {
                        f.setAccessible(true);
                        cList = (List<?>) f.get(responseObj);
                        if (cList != null && !cList.isEmpty()) break;
                    }
                }
            }
            if (cList == null) return countMap;

            for (Object cObj : cList) {
                if (cObj == null) continue;
                for (Field cf : cObj.getClass().getFields()) {
                    Object aObj = cf.get(cObj);
                    if (aObj == null) continue;

                    String fId = null;
                    Integer count = null;

                    try {
                        Field dF = aObj.getClass().getField("d");
                        Field eF = aObj.getClass().getField("E");
                        fId = (String) dF.get(aObj);
                        count = (Integer) eF.get(aObj);
                    } catch (Throwable ignored) {}

                    if (fId == null || count == null) {
                        try {
                            Method dMethod = aObj.getClass().getMethod("d");
                            Map<?, ?> pbMap = (Map<?, ?>) dMethod.invoke(aObj);
                            if (pbMap != null) {
                                Object tag1 = pbMap.get(1);
                                Object tag9 = pbMap.get(9);
                                if (tag1 != null) fId = (String) getPbValueSafe(tag1);
                                if (tag9 != null) {
                                    Object cntObj = getPbValueSafe(tag9);
                                    if (cntObj instanceof Number) count = ((Number) cntObj).intValue();
                                }
                            }
                        } catch (Throwable ignored) {}
                    }

                    if (fId != null && count != null) {
                        String cleanId = fId.replaceAll("^/+", "").trim();
                        countMap.put(cleanId, count);
                    }
                }
            }
        } catch (Throwable ignored) {}
        return countMap;
    }

    private static Object getPbValueSafe(Object entry) {
        if (entry == null) return null;
        try {
            Method m = entry.getClass().getMethod("getValue");
            return m.invoke(entry);
        } catch (Throwable ignored) {}
        try {
            Field f = entry.getClass().getDeclaredField("value");
            f.setAccessible(true);
            return f.get(entry);
        } catch (Throwable ignored) {}
        return null;
    }
}
