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
    // 环节 ①：从 GroupFileListRepo 的 lg4.j 中提取 mg4.a 的 d (fileId) 和 p (downloadTimes)
    // =========================================================================
    public static void handleGroupFileListResponse(Object lg4jObj) {
        if (lg4jObj == null) return;
        if (!ConfigManager.isModuleEnabled("show_file_download_count", true)) return;

        try {
            Field iField = findField(lg4jObj.getClass(), "i");
            if (iField == null) return;
            Object listObj = iField.get(lg4jObj);
            if (!(listObj instanceof List)) return;

            List<?> cList = (List<?>) listObj;
            int countAdded = 0;

            for (Object cObj : cList) {
                if (cObj == null) continue;
                Field fField = findField(cObj.getClass(), "f");
                if (fField == null) continue;
                Object aObj = fField.get(cObj);
                if (aObj == null) continue;

                Class<?> aClz = aObj.getClass();
                Field dField = findField(aClz, "d"); // fileId (Tag 1)
                Field pField = findField(aClz, "p"); // uint32_download_times (Tag 9)
                Field eField = findField(aClz, "e"); // fileName (Tag 2)

                if (dField != null) {
                    Object idVal = dField.get(aObj);
                    if (idVal != null) {
                        String fileId = idVal.toString().replaceAll("^/+", "").trim();
                        int downloadTimes = 0;
                        if (pField != null) {
                            Object cntVal = pField.get(aObj);
                            if (cntVal instanceof Number) {
                                downloadTimes = ((Number) cntVal).intValue();
                            }
                        }
                        String name = (eField != null && eField.get(aObj) != null) ? eField.get(aObj).toString() : fileId;
                        sCountMap.put(fileId, downloadTimes);
                        sCountMap.put(fileId.toLowerCase(), downloadTimes);
                        countAdded++;
                        PLog.i(TAG, "[环节①-捕获] " + name + " (ID=" + fileId + ") -> 下载次数: " + downloadTimes);
                    }
                }
            }
            PLog.i(TAG, "[环节①] 成功装载 " + countAdded + " 条群文件记录，当前字典总数=" + sCountMap.size());
        } catch (Throwable t) {
            PLog.e(TAG, "[环节①] handleGroupFileListResponse 异常", t);
        }
    }

    // =========================================================================
    // 环节 ②：UI 状态文字追加下载次数 (GroupFileCellSlotProvider->o)
    // =========================================================================
    public static String appendDownloadCountToStatusText(String originalStatus, Object fileItemObj) {
        if (!ConfigManager.isModuleEnabled("show_file_download_count", true)) return originalStatus;

        try {
            String fileId = extractFileId(fileItemObj);
            if (fileId == null || fileId.isEmpty()) return originalStatus;

            Integer count = sCountMap.get(fileId);
            if (count == null) count = sCountMap.get(fileId.toLowerCase());

            if (count == null) {
                PLog.w(TAG, "[环节②] 字典未命中 (fileId=" + fileId + ")");
                return originalStatus;
            }

            if (originalStatus != null && originalStatus.endsWith("次")) return originalStatus;

            String newStatus = originalStatus + " · " + count + " 次";
            PLog.i(TAG, "[环节②] -> ★★★ 成功展示! [" + originalStatus + "] -> [" + newStatus + "]");
            return newStatus;

        } catch (Throwable t) {
            PLog.e(TAG, "[环节②] appendDownloadCountToStatusText 异常", t);
        }
        return originalStatus;
    }

    private static String extractFileId(Object obj) {
        if (obj == null) return null;

        // 途径 1 (9.3.60+): obj 是 kn4.j (实现了 fn4.b)，直接通过 getFileId() 提取
        try {
            Method m = obj.getClass().getMethod("getFileId");
            Object id = m.invoke(obj);
            if (id != null) return id.toString().replaceAll("^/+", "").trim();
        } catch (Throwable ignored) {}

        // 途径 2 (9.3.60+ 备用): obj 是 gg4.b，直接反射提取 a 字段
        try {
            Field f = obj.getClass().getDeclaredField("a");
            f.setAccessible(true);
            Object id = f.get(obj);
            if (id != null) return id.toString().replaceAll("^/+", "").trim();
        } catch (Throwable ignored) {}

        // 途径 3 (9.2.90): obj 是 lr5.e / xm4.e，通过 field d -> field d 提取
        try {
            Field dField = obj.getClass().getDeclaredField("d");
            dField.setAccessible(true);
            Object inner = dField.get(obj);
            if (inner != null) {
                Field fIdField = inner.getClass().getDeclaredField("d");
                fIdField.setAccessible(true);
                Object id = fIdField.get(inner);
                if (id != null) return id.toString().replaceAll("^/+", "").trim();
            }
        } catch (Throwable ignored) {}

        return null;
    }

    private static Field findField(Class<?> clz, String name) {
        try {
            Field f = clz.getField(name);
            f.setAccessible(true);
            return f;
        } catch (Throwable ignored) {}
        try {
            Field f = clz.getDeclaredField(name);
            f.setAccessible(true);
            return f;
        } catch (Throwable ignored) {}
        return null;
    }

    // =========================================================================
    // 3. 经典旧版列表支持 (TroopFileShowAdapter.getView)
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

    // 兼容老版本方法签名占位
    public static void handleTroopFileInfo(Object qObj) {}
    public static void handleGroupFileList(Object fileListObj, Object responseObj) {}
}