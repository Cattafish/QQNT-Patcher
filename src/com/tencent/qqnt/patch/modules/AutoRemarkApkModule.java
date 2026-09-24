package com.tencent.qqnt.patch.modules;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import com.tencent.qqnt.kernel.nativeinterface.FileElement;
import com.tencent.qqnt.kernel.nativeinterface.MsgElement;
import com.tencent.qqnt.patch.AppContext;
import com.tencent.qqnt.patch.ConfigManager;
import com.tencent.qqnt.patch.IPatchModule;
import com.tencent.qqnt.patch.PLog;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class AutoRemarkApkModule implements IPatchModule {

    private static final String TAG = "AutoRemarkApk";
    private static final String CMD_FILE_UPLOAD = "OidbSvcTrpcTcp.0xe37_800";

    @Override public String getId() { return "auto_remark_apk"; }
    @Override public String getName() { return "上传 APK 自动重命名 (应用名_版本号.APK)"; }
    @Override public boolean defaultEnabled() { return true; }

    @Override
    public void onSendMsg(ArrayList<MsgElement> elements) {
        if (!isEnabled()) return;
        if (elements == null || elements.isEmpty()) return;
        Context context = AppContext.get();
        if (context == null) return;

        for (MsgElement elem : elements) {
            if (elem != null && elem.fileElement != null) {
                FileElement fe = elem.fileElement;
                if (fe.fileName != null && fe.fileName.toLowerCase().endsWith(".apk")) {
                    renameApkFile(fe, context);
                }
            }
        }
    }

    private void renameApkFile(FileElement fileElement, Context context) {
        try {
            String filePath = fileElement.filePath;
            String originalName = fileElement.fileName;
            String parsedName = null;

            if (filePath != null && !filePath.isEmpty()) {
                File file = new File(filePath);
                if (file.exists() && file.isFile()) {
                    PackageManager pm = context.getPackageManager();
                    PackageInfo packageInfo = pm.getPackageArchiveInfo(filePath, PackageManager.GET_META_DATA);

                    if (packageInfo != null && packageInfo.applicationInfo != null) {
                        ApplicationInfo appInfo = packageInfo.applicationInfo;
                        appInfo.sourceDir = filePath;
                        appInfo.publicSourceDir = filePath;

                        CharSequence label = appInfo.loadLabel(pm);
                        String appName = label != null ? label.toString() : "";
                        String versionName = packageInfo.versionName != null ? packageInfo.versionName : "未知版本";

                        String safeAppName = appName.replaceAll("[\\\\/:*?\"<>|]", "").trim();
                        if (!safeAppName.isEmpty()) {
                            parsedName = safeAppName + "_" + versionName + ".APK";
                        }
                    }
                }
            }

            if (parsedName != null) {
                fileElement.fileName = parsedName;
            } else if (originalName != null && !originalName.isEmpty()) {
                fileElement.fileName = originalName.replaceAll("(?i)\\.apk$", ".APK");
            } else {
                fileElement.fileName = "应用_" + System.currentTimeMillis() + ".APK";
            }

            PLog.i(TAG, "[onSendMsg] 重命名完成: [" + originalName + "] -> [" + fileElement.fileName + "]");
        } catch (Throwable t) {
            PLog.e(TAG, "[onSendMsg] 重命名异常", t);
            if (fileElement.fileName != null) {
                fileElement.fileName = fileElement.fileName.replaceAll("(?i)\\.apk$", ".APK");
            }
        }
    }

    /**
     * 1:1 完美复刻 QFun 逻辑：基于 Protobuf 树形递归重构，精准剥离私聊 .apk.1 污染！
     */
    public static void onDispatchRespMsg(Object msfMessagePair) {
        if (msfMessagePair == null) return;
        if (!ConfigManager.isModuleEnabled("auto_remark_apk", true)) return;

        try {
            Class<?> pairClz = msfMessagePair.getClass();
            Field fromMsgField = pairClz.getField("fromServiceMsg");
            Object fromServiceMsg = fromMsgField.get(msfMessagePair);
            if (fromServiceMsg == null) return;

            Method getCmdM = fromServiceMsg.getClass().getMethod("getServiceCmd");
            String cmd = (String) getCmdM.invoke(fromServiceMsg);

            if (CMD_FILE_UPLOAD.equals(cmd)) {
                Method getWupBufM = fromServiceMsg.getClass().getMethod("getWupBuffer");
                byte[] wupBuf = (byte[]) getWupBufM.invoke(fromServiceMsg);

                if (wupBuf != null && wupBuf.length > 4) {
                    byte[] fixedBuf = process0xe37WithQFunTree(wupBuf);
                    if (fixedBuf != null) {
                        try {
                            Method putWupBufM = fromServiceMsg.getClass().getMethod("putWupBuffer", byte[].class);
                            putWupBufM.invoke(fromServiceMsg, (Object) fixedBuf);
                        } catch (Throwable t) {
                            Field f = fromServiceMsg.getClass().getDeclaredField("wupBuffer");
                            f.setAccessible(true);
                            f.set(fromServiceMsg, fixedBuf);
                        }
                        PLog.i(TAG, "[0xe37_800] QFun 树形 Protobuf 剥除成功！数据包合法重写完成 (" + fixedBuf.length + " bytes)");
                    }
                }
            }
        } catch (Throwable t) {
            PLog.e(TAG, "[onDispatchRespMsg 异常]", t);
        }
    }

    /**
     * QFun 算法：4 -> 10 -> (40 -> 1 -> 5) 和 (30 -> 7)
     */
    private static byte[] process0xe37WithQFunTree(byte[] rawWupBuf) {
        try {
            byte[] pbBytes = rawWupBuf;
            if (rawWupBuf.length >= 4 && (rawWupBuf[0] & 0xFF) == 0) {
                pbBytes = Arrays.copyOfRange(rawWupBuf, 4, rawWupBuf.length);
            }

            PbTree root = PbTree.parseFrom(pbBytes);
            PbTree node4 = root.getSubMessage(4);
            if (node4 == null) return null;

            PbTree fileMeta = node4.getSubMessage(10);
            if (fileMeta == null) return null;

            boolean modified = false;

            // 1. meta.walk("40", "1") -> 5
            List<byte[]> list40 = fileMeta.getBytesList(40);
            if (list40 != null && !list40.isEmpty()) {
                List<byte[]> new40 = new ArrayList<>();
                for (byte[] b40 : list40) {
                    PbTree tree40 = PbTree.parseFrom(b40);
                    List<byte[]> list1 = tree40.getBytesList(1);
                    if (list1 != null && !list1.isEmpty()) {
                        List<byte[]> new1 = new ArrayList<>();
                        for (byte[] b1 : list1) {
                            PbTree tree1 = PbTree.parseFrom(b1);
                            String oldName = tree1.getString(5);
                            String fixed = fixSuffix(oldName);
                            if (fixed != null && !fixed.equals(oldName)) {
                                tree1.putString(5, fixed);
                                modified = true;
                                PLog.i(TAG, "[QFun] 修复 40->1->5 文件名: " + oldName + " -> " + fixed);
                            }
                            new1.add(tree1.toByteArray());
                        }
                        tree40.putBytesList(1, new1);
                    }
                    new40.add(tree40.toByteArray());
                }
                fileMeta.putBytesList(40, new40);
            }

            // 2. meta.walk("30") -> 7
            PbTree storage30 = fileMeta.getSubMessage(30);
            if (storage30 != null) {
                String oldStorageName = storage30.getString(7);
                String fixed = fixSuffix(oldStorageName);
                if (fixed != null && !fixed.equals(oldStorageName)) {
                    storage30.putString(7, fixed);
                    fileMeta.putSubMessage(30, storage30);
                    modified = true;
                    PLog.i(TAG, "[QFun] 修复 30->7 文件名: " + oldStorageName + " -> " + fixed);
                }
            }

            if (modified) {
                node4.putSubMessage(10, fileMeta);
                root.putSubMessage(4, node4);
                return packWithHeader(root.toByteArray());
            }
        } catch (Throwable t) {
            PLog.e(TAG, "解析重构 Protobuf 树异常", t);
        }
        return null;
    }

    private static String fixSuffix(String name) {
        if (name == null) return null;
        if (name.toUpperCase().matches(".*\\.APK\\.\\d+$")) {
            return name.replaceFirst("(?i)\\.apk\\.\\d+$", ".APK");
        }
        return name;
    }

    private static byte[] packWithHeader(byte[] data) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(bos);
        try {
            dos.writeInt(data.length + 4);
            dos.write(data);
            return bos.toByteArray();
        } catch (Throwable t) {
            return data;
        }
    }

    // =========================================================================
    // 纯 Java 轻量级 Protobuf 递归树解析与生成器 (零依赖，严格保持 Varint 长度合法)
    // =========================================================================
    static class PbTree {
        private final Map<Integer, List<FieldEntry>> mFields = new LinkedHashMap<>();

        static class FieldEntry {
            int wireType;
            Object val; // Long, Integer, Long(64-bit), byte[]
            FieldEntry(int w, Object v) { wireType = w; val = v; }
        }

        static PbTree parseFrom(byte[] data) {
            PbTree tree = new PbTree();
            if (data == null) return tree;
            int pos = 0;
            int end = data.length;

            while (pos < end) {
                long tag = 0; int shift = 0;
                while (pos < end) {
                    byte b = data[pos++];
                    tag |= (long) (b & 0x7F) << shift;
                    if ((b & 0x80) == 0) break;
                    shift += 7;
                }
                int fieldNum = (int) (tag >>> 3);
                int wireType = (int) (tag & 7);

                if (wireType == 0) { // Varint
                    long v = 0; shift = 0;
                    while (pos < end) {
                        byte b = data[pos++];
                        v |= (long) (b & 0x7F) << shift;
                        if ((b & 0x80) == 0) break;
                        shift += 7;
                    }
                    tree.addEntry(fieldNum, wireType, v);
                } else if (wireType == 1) { // 64-bit
                    long v = 0;
                    for (int i = 0; i < 8 && pos < end; i++) {
                        v |= ((long) (data[pos++] & 0xFF)) << (i * 8);
                    }
                    tree.addEntry(fieldNum, wireType, v);
                } else if (wireType == 2) { // Length-delimited
                    int len = 0; shift = 0;
                    while (pos < end) {
                        byte b = data[pos++];
                        len |= (b & 0x7F) << shift;
                        if ((b & 0x80) == 0) break;
                        shift += 7;
                    }
                    byte[] sub = new byte[len];
                    if (pos + len <= end) {
                        System.arraycopy(data, pos, sub, 0, len);
                        pos += len;
                    }
                    tree.addEntry(fieldNum, wireType, sub);
                } else if (wireType == 5) { // 32-bit
                    int v = 0;
                    for (int i = 0; i < 4 && pos < end; i++) {
                        v |= (data[pos++] & 0xFF) << (i * 8);
                    }
                    tree.addEntry(fieldNum, wireType, v);
                } else {
                    break; // 不支持的 wireType 或解析结束
                }
            }
            return tree;
        }

        void addEntry(int num, int wire, Object v) {
            List<FieldEntry> list = mFields.get(num);
            if (list == null) {
                list = new ArrayList<>();
                mFields.put(num, list);
            }
            list.add(new FieldEntry(wire, v));
        }

        PbTree getSubMessage(int num) {
            List<FieldEntry> list = mFields.get(num);
            if (list != null && !list.isEmpty()) {
                Object obj = list.get(0).val;
                if (obj instanceof byte[]) {
                    return PbTree.parseFrom((byte[]) obj);
                }
            }
            return null;
        }

        void putSubMessage(int num, PbTree sub) {
            byte[] bytes = sub.toByteArray();
            List<FieldEntry> list = new ArrayList<>();
            list.add(new FieldEntry(2, bytes));
            mFields.put(num, list);
        }

        List<byte[]> getBytesList(int num) {
            List<FieldEntry> list = mFields.get(num);
            if (list == null) return null;
            List<byte[]> res = new ArrayList<>();
            for (FieldEntry e : list) {
                if (e.val instanceof byte[]) res.add((byte[]) e.val);
            }
            return res;
        }

        void putBytesList(int num, List<byte[]> bytesList) {
            List<FieldEntry> list = new ArrayList<>();
            for (byte[] b : bytesList) {
                list.add(new FieldEntry(2, b));
            }
            mFields.put(num, list);
        }

        String getString(int num) {
            List<FieldEntry> list = mFields.get(num);
            if (list != null && !list.isEmpty()) {
                Object o = list.get(0).val;
                if (o instanceof byte[]) return new String((byte[]) o, StandardCharsets.UTF_8);
            }
            return null;
        }

        void putString(int num, String s) {
            byte[] b = s.getBytes(StandardCharsets.UTF_8);
            List<FieldEntry> list = new ArrayList<>();
            list.add(new FieldEntry(2, b));
            mFields.put(num, list);
        }

        byte[] toByteArray() {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            for (Map.Entry<Integer, List<FieldEntry>> entry : mFields.entrySet()) {
                int fieldNum = entry.getKey();
                for (FieldEntry f : entry.getValue()) {
                    int tag = (fieldNum << 3) | (f.wireType & 7);
                    writeVarint(bos, tag);
                    if (f.wireType == 0) {
                        writeVarint(bos, (Long) f.val);
                    } else if (f.wireType == 1) {
                        long v = (Long) f.val;
                        for (int i = 0; i < 8; i++) bos.write((int) (v >>> (i * 8)) & 0xFF);
                    } else if (f.wireType == 2) {
                        byte[] b = (byte[]) f.val;
                        writeVarint(bos, b.length);
                        bos.write(b, 0, b.length);
                    } else if (f.wireType == 5) {
                        int v = (Integer) f.val;
                        for (int i = 0; i < 4; i++) bos.write((v >>> (i * 8)) & 0xFF);
                    }
                }
            }
            return bos.toByteArray();
        }

        private static void writeVarint(ByteArrayOutputStream bos, long v) {
            while ((v & ~0x7FL) != 0) {
                bos.write((int) ((v & 0x7F) | 0x80));
                v >>>= 7;
            }
            bos.write((int) (v & 0x7F));
        }
    }
}