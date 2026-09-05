package com.tencent.qqnt.patch.plugin;

import com.tencent.qqnt.patch.PLog;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;

public class RKeyManager {

    private static final String TAG = "RKeyManager";
    private static final String CMD_RKEY = "OidbSvcTrpcTcp.0x9067_202";

    private static volatile String sFriendRKey = "";
    private static volatile String sGroupRKey = "";

    public static String getFriendRKey() { return sFriendRKey; }
    public static String getGroupRKey() { return sGroupRKey; }

    public static void onDispatchRespMsg(Object msfMessagePair) {
        if (msfMessagePair == null) return;
        try {
            Class<?> pairClz = msfMessagePair.getClass();
            Field fromMsgField = pairClz.getField("fromServiceMsg");
            Object fromServiceMsg = fromMsgField.get(msfMessagePair);
            if (fromServiceMsg == null) return;

            Method getCmdM = fromServiceMsg.getClass().getMethod("getServiceCmd");
            String cmd = (String) getCmdM.invoke(fromServiceMsg);

            if (CMD_RKEY.equals(cmd)) {
                Method getWupBufM = fromServiceMsg.getClass().getMethod("getWupBuffer");
                byte[] wupBuf = (byte[]) getWupBufM.invoke(fromServiceMsg);
                if (wupBuf != null && wupBuf.length > 4) {
                    parseRKeyBuffer(wupBuf);
                }
            }
        } catch (Throwable ignored) {}
    }

    private static void parseRKeyBuffer(byte[] buf) {
        try {
            int offset = (buf[0] == 0) ? 4 : 0;
            byte[] data = new byte[buf.length - offset];
            System.arraycopy(buf, offset, data, 0, data.length);

            // 搜索含有 rkey 的两组参数串 (好友与群)
            String content = new String(data, StandardCharsets.ISO_8859_1);
            int idx1 = content.indexOf("&rkey=");
            if (idx1 != -1) {
                int end1 = content.indexOf('\u0000', idx1);
                if (end1 == -1) end1 = Math.min(content.length(), idx1 + 160);
                sFriendRKey = content.substring(idx1, end1);

                int idx2 = content.indexOf("&rkey=", end1);
                if (idx2 != -1) {
                    int end2 = content.indexOf('\u0000', idx2);
                    if (end2 == -1) end2 = Math.min(content.length(), idx2 + 160);
                    sGroupRKey = content.substring(idx2, end2);
                } else {
                    sGroupRKey = sFriendRKey;
                }
                PLog.i(TAG, "已成功捕获高清图片密钥: " + sFriendRKey);
            }
        } catch (Throwable t) {
            PLog.e(TAG, "解析 RKey 报文异常", t);
        }
    }
}
