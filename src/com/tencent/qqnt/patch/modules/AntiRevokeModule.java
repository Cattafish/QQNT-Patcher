package com.tencent.qqnt.patch.modules;

import android.util.Log;
import com.tencent.mobileqq.qroute.QRoute;
import com.tencent.qqnt.kernel.nativeinterface.IKernelMsgService;
import com.tencent.qqnt.kernel.nativeinterface.IQQNTWrapperSession;
import com.tencent.qqnt.kernelpublic.nativeinterface.Contact;
import com.tencent.qqnt.kernelpublic.nativeinterface.JsonGrayElement;
import com.tencent.qqnt.ntrelation.friendsinfo.api.IFriendsInfoService;
import com.tencent.qqnt.patch.IPatchModule;
import com.tencent.qqnt.patch.PLog;
import com.tencent.qqnt.patch.plugin.MsgSender;
import com.tencent.qqnt.patch.plugin.PluginManager;
import com.tencent.relation.common.api.IRelationNTUinAndUidApi;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

public class AntiRevokeModule implements IPatchModule {

    private static final String TAG = "QQ_DEBUG";
    private static final String CMD_MSG_PUSH = "trpc.msg.olpush.OlPushService.MsgPush";
    private static final String CMD_SYNC_PUSH = "trpc.msg.register_proxy.RegisterProxy.InfoSyncPush";

    private static final long BUSI_ID_C2C = 2021L;
    private static final long BUSI_ID_GROUP = 2022L;

    @Override public String getId() { return "anti_revoke"; }
    @Override public String getName() { return "消息防撤回"; }

    private static final Set<String> sRevokedCache = Collections.synchronizedSet(
            Collections.newSetFromMap(new LinkedHashMap<String, Boolean>(100, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                    return size() > 300;
                }
            })
    );

    @Override
    public byte[] onMsfPush(IQQNTWrapperSession session, String cmd, byte[] buf) {
        if (cmd == null || buf == null) return buf;

        if (CMD_MSG_PUSH.equals(cmd)) {
            dispatchNudgeAndShutUpEvents(buf);

            int revokeType = checkRevokeType(buf);
            if (revokeType != 0) {
                try {
                    boolean isSelf = processRecall(session, buf, revokeType);
                    if (isSelf) return buf;
                } catch (Throwable t) {
                    Log.e(TAG, "[AntiRevoke] 处理撤回灰条异常", t);
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

    private static void dispatchNudgeAndShutUpEvents(byte[] buf) {
        try {
            byte[] qqMsgBytes = Proto.getBytes(buf, 1);
            if (qqMsgBytes == null) return;
            byte[] headerBytes = Proto.getBytes(qqMsgBytes, 2);
            if (headerBytes == null) return;

            long cmd1 = Proto.getVarint(headerBytes, 1);
            long cmd2 = Proto.getVarint(headerBytes, 2);

            byte[] bodyBytes = Proto.getBytes(qqMsgBytes, 3);
            if (bodyBytes == null) return;
            byte[] subBytes = Proto.getBytes(bodyBytes, 2);
            if (subBytes == null) return;

            // 1. 群禁言事件
            if (cmd1 == 732 && cmd2 == 12) {
                long troopUin = Proto.getVarint(subBytes, 1);
                String opUid = Proto.getString(subBytes, 4);
                byte[] shutUpInfo = Proto.getBytes(subBytes, 5);
                if (shutUpInfo != null) {
                    byte[] inner = Proto.getBytes(shutUpInfo, 3);
                    if (inner != null) {
                        String memberUid = Proto.getString(inner, 1);
                        long time = Proto.getVarint(inner, 2);
                        PluginManager.dispatchTroopShutUp(String.valueOf(troopUin), getUin(memberUid), time, getUin(opUid));
                    }
                }
                return;
            }

            // 2. 拍一拍事件
            int chatType = 0;
            String peerUin = "";
            String fromUin = "";
            String toUin = "";

            byte[] head1 = Proto.getBytes(qqMsgBytes, 1);

            if (cmd1 == 732 && cmd2 == 20) {
                chatType = 2;
                peerUin = readUin(head1, 1);
                String content = new String(subBytes, StandardCharsets.UTF_8);
                fromUin = extractQQ(content, "1");
                toUin = extractQQ(content, "2");
            } else if (cmd1 == 528 && cmd2 == 290) {
                chatType = 1;
                peerUin = readUin(head1, 1);
                fromUin = peerUin;
                toUin = extractToUinFromField7(subBytes);
            }

            if (chatType != 0) {
                String myUin = MsgSender.getMyUin();
                if (isValidQQ(fromUin) && isValidQQ(toUin) && (toUin.equals(myUin) || myUin.isEmpty())) {
                    PLog.i("PaiYiPai", "成功捕获拍一拍事件: peer=" + peerUin + ", 来自=" + fromUin + ", 目标=" + toUin);
                    PluginManager.dispatchPaiYiPai(peerUin, chatType, fromUin);
                }
            }
        } catch (Throwable ignored) {}
    }

    private static String readUin(byte[] data, int targetField) {
        if (data == null) return "";
        ProtoReader reader = new ProtoReader(data);
        while (reader.hasMore()) {
            long tag = reader.readVarint();
            int field = (int) (tag >>> 3);
            int wire = (int) (tag & 7);
            if (wire == 0) {
                long val = reader.readVarint();
                if (field == targetField) return String.valueOf(val);
            } else if (wire == 1) {
                reader.skip(8);
            } else if (wire == 2) {
                byte[] sub = reader.readBytes();
                if (field == targetField && sub != null) {
                    return new String(sub, StandardCharsets.UTF_8);
                }
            } else if (wire == 5) {
                reader.skip(4);
            } else {
                break;
            }
        }
        return "";
    }

    private static String extractQQ(String target, String type) {
        if (target == null || target.isEmpty()) return "";
        String key = "uin_str" + type;
        int keyIndex = target.indexOf(key);
        if (keyIndex == -1) return "";

        int startIndex = keyIndex + key.length();
        int digitStart = -1;
        for (int i = startIndex; i < target.length(); i++) {
            if (Character.isDigit(target.charAt(i))) {
                digitStart = i;
                break;
            }
        }
        if (digitStart == -1) return "";

        int colonIdx = target.indexOf(':', digitStart);
        int realEnd = (colonIdx != -1) ? colonIdx : target.length();

        String raw = target.substring(digitStart, realEnd).trim();
        int end = 0;
        while (end < raw.length() && Character.isDigit(raw.charAt(end))) {
            end++;
        }
        return raw.substring(0, end);
    }

    private static String extractToUinFromField7(byte[] data) {
        if (data == null) return "";
        ProtoReader reader = new ProtoReader(data);
        while (reader.hasMore()) {
            long tag = reader.readVarint();
            int field = (int) (tag >>> 3);
            int wire = (int) (tag & 7);
            if (wire == 2) {
                byte[] itemBytes = reader.readBytes();
                if (field == 7 && itemBytes != null) {
                    String k = Proto.getString(itemBytes, 1);
                    if ("uin_str2".equals(k)) {
                        String v = Proto.getString(itemBytes, 2);
                        if (!v.isEmpty()) return v;
                    }
                }
            } else if (wire == 0) {
                reader.readVarint();
            } else if (wire == 1) {
                reader.skip(8);
            } else if (wire == 5) {
                reader.skip(4);
            } else {
                break;
            }
        }
        return "";
    }

    private static boolean isValidQQ(String input) {
        return input != null && input.matches("[1-9]\\d{4,12}");
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
        if (data == null || data.length == 0 || !hasRecallSignature(data)) return data;
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ProtoReader reader = new ProtoReader(data);

            while (reader.hasMore()) {
                int start = reader.getPos();
                long tag = reader.readVarint();
                int wire = (int) (tag & 7);

                if (wire == 2) {
                    int l = (int) reader.readVarint();
                    int subStart = reader.getPos();
                    if (subStart + l <= data.length) {
                        byte[] sub = Proto.subArray(data, subStart, l);
                        reader.skip(l);
                        if (isDirectRecallMsg(sub)) continue;
                        if (hasRecallSignature(sub)) {
                            byte[] cleanedSub = filterProtoTree(sub);
                            Proto.writeVarint(out, tag);
                            Proto.writeVarint(out, cleanedSub.length);
                            out.write(cleanedSub);
                        } else {
                            out.write(data, start, reader.getPos() - start);
                        }
                    } else break;
                } else if (wire == 0) {
                    reader.readVarint();
                    out.write(data, start, reader.getPos() - start);
                } else if (wire == 1) {
                    reader.skip(8);
                    out.write(data, start, reader.getPos() - start);
                } else if (wire == 5) {
                    reader.skip(4);
                    out.write(data, start, reader.getPos() - start);
                } else break;
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
            if (msgSeq == 0) msgSeq = extractGroupSeq(opBytes);
            if (groupCode.isEmpty()) return false;

            String cacheKey = "grp_" + groupCode + "_seq_" + msgSeq;
            if (msgSeq > 0 && !sRevokedCache.add(cacheKey)) return false;

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
                if (infoBytes != null) peerUid = Proto.getString(infoBytes, 1);
            }

            if (peerUid.isEmpty() || (selfUid != null && !selfUid.isEmpty() && peerUid.equals(selfUid))) {
                return true;
            }

            long msgSeq = extractC2CSeq(opBytes);
            if (msgSeq == 0) msgSeq = findSeqInOpBytes(opBytes);

            String cacheKey = "c2c_" + peerUid + "_seq_" + msgSeq;
            if (msgSeq > 0 && !sRevokedCache.add(cacheKey)) return false;

            String json = buildC2CClickableJson(msgSeq);
            Contact contact = new Contact(1, peerUid, "");
            JsonGrayElement grayElement = new JsonGrayElement(BUSI_ID_C2C, json, "", false, null);

            msgService.addLocalJsonGrayTipMsg(contact, grayElement, true, true, null);
        }
        return false;
    }

    private static long findSeqInOpBytes(byte[] data) {
        if (data == null || data.length == 0) return 0;
        ProtoReader reader = new ProtoReader(data);
        long foundTime = 0, foundSeq = 0;

        while (reader.hasMore()) {
            long tag = reader.readVarint();
            int wire = (int) (tag & 7);

            if (wire == 0) {
                long val = reader.readVarint();
                if (val >= 1577836800L && val <= 2051222400L) foundTime = val;
                else if (val > 0 && val < 100000000L && val != 732 && val != 528 && val != 17 && val != 138) foundSeq = val;
            } else if (wire == 1) {
                reader.skip(8);
            } else if (wire == 2) {
                byte[] sub = reader.readBytes();
                if (sub != null && sub.length > 0) {
                    long subSeq = findSeqInOpBytes(sub);
                    if (subSeq > 0) return subSeq;
                }
            } else if (wire == 5) {
                reader.skip(4);
            } else {
                reader.skip(1);
            }
        }
        return (foundTime > 0 && foundSeq > 0) ? foundSeq : 0;
    }

    private static String findFirstUidRecursively(byte[] data, String excludeUid) {
        if (data == null || data.length == 0) return "";
        ProtoReader reader = new ProtoReader(data);
        while (reader.hasMore()) {
            long tag = reader.readVarint();
            int wire = (int) (tag & 7);
            if (wire == 2) {
                byte[] sub = reader.readBytes();
                if (sub != null && sub.length > 0) {
                    int l = sub.length;
                    if (l >= 4 && l <= 40 && isAscii(sub)) {
                        String s = new String(sub, StandardCharsets.UTF_8);
                        if (s.startsWith("u_") && (excludeUid == null || !s.equals(excludeUid))) return s;
                    }
                    String inner = findFirstUidRecursively(sub, excludeUid);
                    if (!inner.isEmpty()) return inner;
                }
            } else if (wire == 0) {
                reader.readVarint();
            } else if (wire == 1) {
                reader.skip(8);
            } else if (wire == 5) {
                reader.skip(4);
            } else {
                reader.skip(1);
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
            if (msgInfoBytes != null) return Proto.getVarint(msgInfoBytes, 1);
        }
        return 0;
    }

    private static long extractC2CSeq(byte[] opBytes) {
        if (opBytes == null || opBytes.length == 0) return 0;
        byte[] infoBytes = Proto.getBytes(opBytes, 1);
        return infoBytes != null ? Proto.getVarint(infoBytes, 2) : 0;
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
        return (uin != null && !uin.isEmpty() && !uin.equals("0")) ? uin : "群成员";
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
        if (msgSeq > 0) sb.append("\"param\":{\"seq\":").append(msgSeq).append("},");
        else sb.append("\"param\":{},");
        sb.append("\"txt\":\"一条消息\",\"type\":\"url\"}");
        sb.append("]}");
        return sb.toString();
    }

    private static String buildC2CClickableJson(long msgSeq) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"align\":\"center\",\"items\":[");
        sb.append("{\"txt\":\"对方 尝试撤回 \",\"type\":\"nor\"},");
        sb.append("{\"col\":\"3\",\"local_jp\":58,");
        if (msgSeq > 0) sb.append("\"param\":{\"seq\":").append(msgSeq).append("},");
        else sb.append("\"param\":{},");
        sb.append("\"txt\":\"一条消息\",\"type\":\"url\"}");
        sb.append("]}");
        return sb.toString();
    }

    private static boolean isAscii(byte[] b) {
        if (b == null || b.length == 0) return false;
        for (byte v : b) if (v < 32 || v > 126) return false;
        return true;
    }

    private static String escapeJson(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /**
     * 局部流式读取器：无共享静态状态，绝对并发安全
     */
    private static class ProtoReader {
        private final byte[] data;
        private int pos;
        private final int limit;

        ProtoReader(byte[] data) {
            this.data = data;
            this.pos = 0;
            this.limit = data != null ? data.length : 0;
        }

        boolean hasMore() {
            return data != null && pos < limit;
        }

        int getPos() {
            return pos;
        }

        void skip(int n) {
            pos = Math.min(limit, pos + n);
        }

        long readVarint() {
            long result = 0;
            for (int shift = 0; shift < 64 && pos < limit; shift += 7) {
                byte b = data[pos++];
                result |= (long) (b & 0x7F) << shift;
                if ((b & 0x80) == 0) break;
            }
            return result;
        }

        byte[] readBytes() {
            int len = (int) readVarint();
            if (len < 0 || pos + len > limit) return null;
            byte[] dest = new byte[len];
            System.arraycopy(data, pos, dest, 0, len);
            pos += len;
            return dest;
        }
    }

    private static class Proto {
        static void writeVarint(ByteArrayOutputStream out, long value) {
            while ((value & ~0x7FL) != 0) {
                out.write((int) ((value & 0x7F) | 0x80));
                value >>>= 7;
            }
            out.write((int) (value & 0x7F));
        }

        static byte[] getBytes(byte[] data, int targetField) {
            if (data == null) return null;
            ProtoReader reader = new ProtoReader(data);
            while (reader.hasMore()) {
                long tag = reader.readVarint();
                int field = (int) (tag >>> 3);
                int wire = (int) (tag & 7);
                if (wire == 0) {
                    reader.readVarint();
                } else if (wire == 1) {
                    reader.skip(8);
                } else if (wire == 2) {
                    byte[] sub = reader.readBytes();
                    if (field == targetField) return sub;
                } else if (wire == 5) {
                    reader.skip(4);
                } else {
                    break;
                }
            }
            return null;
        }

        static String getString(byte[] data, int targetField) {
            byte[] b = getBytes(data, targetField);
            return b != null ? new String(b, StandardCharsets.UTF_8) : "";
        }

        static long getVarint(byte[] data, int targetField) {
            if (data == null) return 0;
            ProtoReader reader = new ProtoReader(data);
            while (reader.hasMore()) {
                long tag = reader.readVarint();
                int field = (int) (tag >>> 3);
                int wire = (int) (tag & 7);
                if (wire == 0) {
                    long val = reader.readVarint();
                    if (field == targetField) return val;
                } else if (wire == 1) {
                    reader.skip(8);
                } else if (wire == 2) {
                    reader.readBytes();
                } else if (wire == 5) {
                    reader.skip(4);
                } else {
                    break;
                }
            }
            return 0;
        }

        static byte[] subArray(byte[] src, int start, int length) {
            if (src == null || start < 0 || length <= 0 || start + length > src.length) return new byte[0];
            byte[] dest = new byte[length];
            System.arraycopy(src, start, dest, 0, length);
            return dest;
        }

        static byte[] subArray(byte[] src, int start) {
            return subArray(src, start, src.length - start);
        }
    }
}