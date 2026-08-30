package com.tencent.qqnt.patch;

import android.util.Log;
import com.tencent.mobileqq.qroute.QRoute;
import com.tencent.qqnt.kernel.nativeinterface.IKernelMsgService;
import com.tencent.qqnt.kernel.nativeinterface.IQQNTWrapperSession;
import com.tencent.qqnt.kernelpublic.nativeinterface.Contact;
import com.tencent.qqnt.kernelpublic.nativeinterface.JsonGrayElement;
import com.tencent.qqnt.ntrelation.friendsinfo.api.IFriendsInfoService;
import com.tencent.relation.common.api.IRelationNTUinAndUidApi;

import java.io.ByteArrayOutputStream;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

public class AntiRevokeHelper {

    private static final String TAG = "QQ_DEBUG";
    private static final String CMD_MSG_PUSH = "trpc.msg.olpush.OlPushService.MsgPush";
    private static final String CMD_SYNC_PUSH = "trpc.msg.register_proxy.RegisterProxy.InfoSyncPush";

    private static final long BUSI_ID_C2C = 2021L;
    private static final long BUSI_ID_GROUP = 2022L;

    public static boolean isAntiRevokeEnabled() {
        return ConfigManager.isAntiRevokeEnabled();
    }

    private static final Set<String> revokedCache = Collections.synchronizedSet(
            Collections.newSetFromMap(new LinkedHashMap<String, Boolean>(100, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                    return size() > 300;
                }
            })
    );

    public static byte[] handleMsfPush(IQQNTWrapperSession session, String cmd, byte[] buf) {
        // ★ 每次冷启动 QQ 仅触发 1 次静默检查更新
        ConfigManager.triggerColdStartUpdateCheck();

        if (cmd == null || buf == null) {
            return buf;
        }

        if (!ConfigManager.isAntiRevokeEnabled()) {
            return buf;
        }

        if (CMD_MSG_PUSH.equals(cmd)) {
            int revokeType = checkRevokeType(buf);
            if (revokeType != 0) {
                try {
                    boolean isSelf = processRecall(session, buf, revokeType);
                    if (isSelf) {
                        return buf;
                    }
                } catch (Throwable t) {
                    Log.e(TAG, "处理撤回灰条异常", t);
                }
                return null;
            }
            return buf;
        }

        if (CMD_SYNC_PUSH.equals(cmd)) {
            if (hasRecallSignature(buf)) {
                return filterProtoTree(buf);
            }
            return buf;
        }

        return buf;
    }

    private static int checkRevokeType(byte[] buf) {
        if (buf == null || buf.length < 5) return 0;
        int limit = Math.min(buf.length - 4, 150);
        for (int i = 0; i < limit; i++) {
            if (buf[i] == 0x08) {
                if ((buf[i + 1] & 0xFF) == 0xDC && (buf[i + 2] & 0xFF) == 0x05
                        && (buf[i + 3] & 0xFF) == 0x10 && (buf[i + 4] & 0xFF) == 0x11) {
                    return 1;
                }
                if (i + 5 < buf.length && (buf[i + 1] & 0xFF) == 0x90 && (buf[i + 2] & 0xFF) == 0x04
                        && (buf[i + 3] & 0xFF) == 0x10 && (buf[i + 4] & 0xFF) == 0x8A && (buf[i + 5] & 0xFF) == 0x01) {
                    return 2;
                }
            }
        }
        return 0;
    }

    private static boolean hasRecallSignature(byte[] data) {
        if (data == null || data.length < 5) return false;
        int len = data.length;
        for (int i = 0; i <= len - 5; i++) {
            if (data[i] == 0x08) {
                if ((data[i + 1] & 0xFF) == 0xDC && (data[i + 2] & 0xFF) == 0x05
                        && (data[i + 3] & 0xFF) == 0x10 && (data[i + 4] & 0xFF) == 0x11) {
                    return true;
                }
                if (i + 5 < len && (data[i + 1] & 0xFF) == 0x90 && (data[i + 2] & 0xFF) == 0x04
                        && (data[i + 3] & 0xFF) == 0x10 && (data[i + 4] & 0xFF) == 0x8A && (data[i + 5] & 0xFF) == 0x01) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isDirectRecallMsg(byte[] data) {
        if (data == null || data.length < 5) return false;
        int limit = Math.min(data.length - 4, 60);
        for (int i = 0; i < limit; i++) {
            if (data[i] == 0x08) {
                if ((data[i + 1] & 0xFF) == 0xDC && (data[i + 2] & 0xFF) == 0x05
                        && (data[i + 3] & 0xFF) == 0x10 && (data[i + 4] & 0xFF) == 0x11) {
                    return true;
                }
                if (i + 5 < data.length && (data[i + 1] & 0xFF) == 0x90 && (data[i + 2] & 0xFF) == 0x04
                        && (data[i + 3] & 0xFF) == 0x10 && (data[i + 4] & 0xFF) == 0x8A && (data[i + 5] & 0xFF) == 0x01) {
                    return true;
                }
            }
        }
        return false;
    }

    private static byte[] filterProtoTree(byte[] data) {
        if (data == null || data.length == 0 || !hasRecallSignature(data)) {
            return data;
        }

        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            int pos = 0;
            int len = data.length;

            while (pos < len) {
                int start = pos;
                long tag = Proto.readVarint(data, pos);
                pos = Proto.lastPos;
                int wire = (int) (tag & 7);

                if (wire == 2) {
                    int l = (int) Proto.readVarint(data, pos);
                    pos = Proto.lastPos;
                    if (pos + l <= len) {
                        byte[] sub = Proto.subArray(data, pos, l);
                        pos += l;

                        if (isDirectRecallMsg(sub)) {
                            continue;
                        }

                        if (hasRecallSignature(sub)) {
                            byte[] cleanedSub = filterProtoTree(sub);
                            Proto.writeVarint(out, tag);
                            Proto.writeVarint(out, cleanedSub.length);
                            out.write(cleanedSub);
                        } else {
                            out.write(data, start, pos - start);
                        }
                    } else {
                        break;
                    }
                } else if (wire == 0) {
                    Proto.readVarint(data, pos);
                    pos = Proto.lastPos;
                    out.write(data, start, pos - start);
                } else if (wire == 1) {
                    pos += 8;
                    out.write(data, start, pos - start);
                } else if (wire == 5) {
                    pos += 4;
                    out.write(data, start, pos - start);
                } else {
                    break;
                }
            }
            return out.toByteArray();
        } catch (Throwable t) {
            return data;
        }
    }

    private static boolean processRecall(IQQNTWrapperSession session, byte[] buf, int revokeType) {
        if (session == null) return false;
        IKernelMsgService msgService = session.getMsgService();
        if (msgService == null) return false;

        byte[] qqMsgBytes = Proto.getBytes(buf, 1);
        if (qqMsgBytes == null) return false;

        byte[] headerBytes = Proto.getBytes(qqMsgBytes, 1);
        if (headerBytes == null) return false;

        byte[] bodyBytes = Proto.getBytes(qqMsgBytes, 3);
        byte[] opBytes = bodyBytes != null ? Proto.getBytes(bodyBytes, 2) : null;

        String selfUid = Proto.getString(headerBytes, 6);

        if (revokeType == 1) {
            String groupCode = Proto.getString(headerBytes, 2);
            if (groupCode.isEmpty()) {
                long g = Proto.getVarint(headerBytes, 1);
                if (g > 0) groupCode = String.valueOf(g);
            }

            String operatorUid = findFirstUidRecursively(opBytes, selfUid);

            if (operatorUid.isEmpty() || (selfUid != null && !selfUid.isEmpty() && operatorUid.equals(selfUid))) {
                return true;
            }

            long msgSeq = findSeqInOpBytes(opBytes);
            if (msgSeq == 0) {
                msgSeq = extractGroupSeq(opBytes);
            }

            if (groupCode.isEmpty()) return false;

            String cacheKey = "grp_" + groupCode + "_seq_" + msgSeq;
            if (msgSeq > 0 && !revokedCache.add(cacheKey)) return false;

            String uin = getUin(operatorUid);
            String nickName = getUserNickName(operatorUid, uin);

            String json = buildGroupClickableJson(operatorUid, uin, nickName, msgSeq);
            Contact contact = new Contact(2, groupCode, "");
            JsonGrayElement grayElement = new JsonGrayElement(BUSI_ID_GROUP, json, "", false, null);

            msgService.addLocalJsonGrayTipMsg(contact, grayElement, true, true, null);

        } else if (revokeType == 2) {
            String peerUid = Proto.getString(headerBytes, 2);
            if (peerUid.isEmpty() && opBytes != null) {
                byte[] infoBytes = Proto.getBytes(opBytes, 1);
                if (infoBytes != null) {
                    peerUid = Proto.getString(infoBytes, 1);
                }
            }

            if (peerUid.isEmpty() || (selfUid != null && !selfUid.isEmpty() && peerUid.equals(selfUid))) {
                return true;
            }

            long msgSeq = extractC2CSeq(opBytes);
            if (msgSeq == 0) {
                msgSeq = findSeqInOpBytes(opBytes);
            }

            String cacheKey = "c2c_" + peerUid + "_seq_" + msgSeq;
            if (msgSeq > 0 && !revokedCache.add(cacheKey)) return false;

            String json = buildC2CClickableJson(msgSeq);
            Contact contact = new Contact(1, peerUid, "");
            JsonGrayElement grayElement = new JsonGrayElement(BUSI_ID_C2C, json, "", false, null);

            msgService.addLocalJsonGrayTipMsg(contact, grayElement, true, true, null);
        }

        return false;
    }

    private static long findSeqInOpBytes(byte[] data) {
        if (data == null || data.length == 0) return 0;
        int pos = 0;
        int len = data.length;
        long foundTime = 0;
        long foundSeq = 0;

        while (pos < len) {
            long tag = Proto.readVarint(data, pos);
            pos = Proto.lastPos;
            int wire = (int) (tag & 7);

            if (wire == 0) {
                long val = Proto.readVarint(data, pos);
                pos = Proto.lastPos;
                if (val >= 1577836800L && val <= 2051222400L) {
                    foundTime = val;
                } else if (val > 0 && val < 100000000L && val != 732 && val != 528 && val != 17 && val != 138) {
                    foundSeq = val;
                }
            } else if (wire == 1) {
                pos += 8;
            } else if (wire == 2) {
                int l = (int) Proto.readVarint(data, pos);
                pos = Proto.lastPos;
                if (pos + l <= len && l > 0) {
                    byte[] sub = Proto.subArray(data, pos, l);
                    long subSeq = findSeqInOpBytes(sub);
                    if (subSeq > 0) return subSeq;
                }
                pos += l;
            } else if (wire == 5) {
                pos += 4;
            } else {
                pos++;
            }
        }

        if (foundTime > 0 && foundSeq > 0) {
            return foundSeq;
        }
        return 0;
    }

    private static String findFirstUidRecursively(byte[] data, String excludeUid) {
        if (data == null || data.length == 0) return "";
        int pos = 0;
        int len = data.length;
        while (pos < len) {
            long tag = Proto.readVarint(data, pos);
            pos = Proto.lastPos;
            int wire = (int) (tag & 7);
            if (wire == 2) {
                int l = (int) Proto.readVarint(data, pos);
                pos = Proto.lastPos;
                if (pos + l <= len && l > 0) {
                    byte[] sub = Proto.subArray(data, pos, l);
                    if (l >= 4 && l <= 40 && isAscii(sub)) {
                        String s = new String(sub);
                        if (s.startsWith("u_") && (excludeUid == null || !s.equals(excludeUid))) {
                            return s;
                        }
                    }
                    String inner = findFirstUidRecursively(sub, excludeUid);
                    if (!inner.isEmpty()) {
                        return inner;
                    }
                }
                pos += l;
            } else if (wire == 0) {
                Proto.readVarint(data, pos);
                pos = Proto.lastPos;
            } else if (wire == 1) {
                pos += 8;
            } else if (wire == 5) {
                pos += 4;
            } else {
                pos++;
            }
        }
        return "";
    }

    private static long extractGroupSeq(byte[] opBytes) {
        if (opBytes == null || opBytes.length == 0) return 0;
        byte[] realBytes = (opBytes.length > 7 && opBytes[0] != 0x08) ? Proto.subArray(opBytes, 7) : opBytes;
        byte[] infoBytes = Proto.getBytes(realBytes, 2);
        if (infoBytes != null) {
            byte[] msgInfoBytes = Proto.getBytes(infoBytes, 2);
            if (msgInfoBytes != null) {
                return Proto.getVarint(msgInfoBytes, 1);
            }
        }
        return 0;
    }

    private static long extractC2CSeq(byte[] opBytes) {
        if (opBytes == null || opBytes.length == 0) return 0;
        byte[] infoBytes = Proto.getBytes(opBytes, 1);
        if (infoBytes != null) {
            return Proto.getVarint(infoBytes, 2);
        }
        return 0;
    }

    private static String getUin(String uid) {
        if (uid == null || uid.isEmpty()) return "";
        try {
            IRelationNTUinAndUidApi api = QRoute.api(IRelationNTUinAndUidApi.class);
            if (api != null) {
                String uin = api.getUinFromUid(uid);
                if (uin != null && !uin.isEmpty() && !uin.equals("0")) return uin;
            }
        } catch (Throwable ignored) {}
        return "";
    }

    private static String getUserNickName(String uid, String uin) {
        if (uid == null || uid.isEmpty()) return "群成员";
        try {
            IFriendsInfoService service = QRoute.api(IFriendsInfoService.class);
            if (service != null) {
                String remark = service.getRemarkWithUid(uid, "");
                if (remark != null && !remark.isEmpty()) return remark;
                String nick = service.getNickWithUid(uid, "");
                if (nick != null && !nick.isEmpty()) return nick;
            }
        } catch (Throwable ignored) {}

        if (uin != null && !uin.isEmpty() && !uin.equals("0")) {
            return uin;
        }
        return "群成员";
    }

    private static String buildGroupClickableJson(String operatorUid, String uin, String nickName, long msgSeq) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"align\":\"center\",\"items\":[");

        String displayNick = escapeJson(nickName);
        String uinVal = (uin != null) ? uin : "";

        sb.append("{\"col\":\"3\",\"jp\":\"").append(operatorUid).append("\",\"nm\":\"").append(displayNick)
          .append("\",\"tp\":\"0\",\"type\":\"qq\",\"uid\":\"").append(operatorUid).append("\",\"uin\":\"").append(uinVal).append("\"},");

        sb.append("{\"txt\":\" 尝试撤回 \",\"type\":\"nor\"},");

        sb.append("{\"col\":\"3\",\"local_jp\":58,");
        if (msgSeq > 0) {
            sb.append("\"param\":{\"seq\":").append(msgSeq).append("},");
        } else {
            sb.append("\"param\":{},");
        }
        sb.append("\"txt\":\"一条消息\",\"type\":\"url\"}");

        sb.append("]}");
        return sb.toString();
    }

    private static String buildC2CClickableJson(long msgSeq) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"align\":\"center\",\"items\":[");
        sb.append("{\"txt\":\"对方 尝试撤回 \",\"type\":\"nor\"},");

        sb.append("{\"col\":\"3\",\"local_jp\":58,");
        if (msgSeq > 0) {
            sb.append("\"param\":{\"seq\":").append(msgSeq).append("},");
        } else {
            sb.append("\"param\":{},");
        }
        sb.append("\"txt\":\"一条消息\",\"type\":\"url\"}");

        sb.append("]}");
        return sb.toString();
    }

    private static boolean isAscii(byte[] b) {
        if (b == null || b.length == 0) return false;
        for (byte v : b) {
            if (v < 32 || v > 126) return false;
        }
        return true;
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static class Proto {
        static int lastPos = 0;

        static long readVarint(byte[] data, int pos) {
            long result = 0;
            for (int shift = 0; shift < 64 && pos < data.length; shift += 7) {
                byte b = data[pos++];
                result |= (long) (b & 0x7F) << shift;
                if ((b & 0x80) == 0) break;
            }
            lastPos = pos;
            return result;
        }

        static void writeVarint(ByteArrayOutputStream out, long value) {
            while ((value & ~0x7FL) != 0) {
                out.write((int) ((value & 0x7F) | 0x80));
                value >>>= 7;
            }
            out.write((int) (value & 0x7F));
        }

        static byte[] getBytes(byte[] data, int targetField) {
            if (data == null) return null;
            int pos = 0;
            int len = data.length;
            while (pos < len) {
                long tag = readVarint(data, pos);
                pos = lastPos;
                int field = (int) (tag >>> 3);
                int wire = (int) (tag & 7);
                if (wire == 0) {
                    readVarint(data, pos);
                    pos = lastPos;
                } else if (wire == 1) {
                    pos += 8;
                } else if (wire == 2) {
                    int l = (int) readVarint(data, pos);
                    pos = lastPos;
                    if (field == targetField) {
                        return subArray(data, pos, l);
                    }
                    pos += l;
                } else if (wire == 5) {
                    pos += 4;
                } else {
                    break;
                }
            }
            return null;
        }

        static String getString(byte[] data, int targetField) {
            byte[] b = getBytes(data, targetField);
            return b != null ? new String(b) : "";
        }

        static long getVarint(byte[] data, int targetField) {
            if (data == null) return 0;
            int pos = 0;
            int len = data.length;
            while (pos < len) {
                long tag = readVarint(data, pos);
                pos = lastPos;
                int field = (int) (tag >>> 3);
                int wire = (int) (tag & 7);
                if (wire == 0) {
                    long val = readVarint(data, pos);
                    pos = lastPos;
                    if (field == targetField) return val;
                } else if (wire == 1) {
                    pos += 8;
                } else if (wire == 2) {
                    int l = (int) readVarint(data, pos);
                    pos = lastPos + l;
                } else if (wire == 5) {
                    pos += 4;
                } else {
                    break;
                }
            }
            return 0;
        }

        static byte[] subArray(byte[] src, int start) {
            return subArray(src, start, src.length - start);
        }

        static byte[] subArray(byte[] src, int start, int length) {
            if (src == null || start < 0 || length <= 0 || start + length > src.length) return new byte[0];
            byte[] dest = new byte[length];
            System.arraycopy(src, start, dest, 0, length);
            return dest;
        }
    }
}