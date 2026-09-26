package com.tencent.qqnt.patch.modules;

import com.tencent.mobileqq.qroute.QRoute;
import com.tencent.qqnt.kernel.nativeinterface.IKernelMsgService;
import com.tencent.qqnt.kernel.nativeinterface.IQQNTWrapperSession;
import com.tencent.qqnt.kernel.nativeinterface.MsgElement;
import com.tencent.qqnt.kernel.nativeinterface.MsgRecord;
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
import java.util.List;
import java.util.Map;
import java.util.Set;

public class AntiRevokeModule implements IPatchModule {

    private static final String TAG = "AntiRevoke";
    private static final String CMD_MSG_PUSH = "trpc.msg.olpush.OlPushService.MsgPush";
    private static final String CMD_SYNC_PUSH = "trpc.msg.register_proxy.RegisterProxy.InfoSyncPush";

    private static final long BUSI_ID_C2C = 2021L;
    private static final long BUSI_ID_GROUP = 2022L;

    @Override public String getId() { return "anti_revoke"; }
    @Override public String getName() { return "消息防撤回"; }
    @Override public boolean defaultEnabled() { return false; }

    private static final Set<String> sRevokedCache = Collections.synchronizedSet(
            Collections.newSetFromMap(new LinkedHashMap<String, Boolean>(100, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                    return size() > 300;
                }
            })
    );

    // 日志去重缓存，杜绝重复刷屏
    private static final Set<Long> sPrintedMsgIds = Collections.synchronizedSet(
            Collections.newSetFromMap(new LinkedHashMap<Long, Boolean>(50, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Long, Boolean> eldest) {
                    return size() > 100;
                }
            })
    );

    @Override
    public void onRecvMsg(List<MsgRecord> msgList) {
        if (msgList == null || msgList.isEmpty()) return;
        for (MsgRecord r : msgList) {
            if (r == null || r.msgId == 0) continue;
            if (!sPrintedMsgIds.add(r.msgId)) continue; // ★ 去重过滤，相同消息只打一次

            String text = "";
            if (r.elements != null) {
                for (MsgElement elem : r.elements) {
                    if (elem != null && elem.textElement != null && elem.textElement.content != null) {
                        text = elem.textElement.content;
                        break;
                    }
                }
            }
            if (text.length() > 15) text = text.substring(0, 15) + "...";
            PLog.d("AIO-Msg", "收到消息: seq=" + r.msgSeq + ", msgId=" + r.msgId + ", chatType=" + r.chatType + (text.isEmpty() ? "" : ", txt=" + text));
        }
    }

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
                    PLog.e(TAG, "处理撤回灰条异常", t);
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

    private static class GroupRecallPayload {
        String groupCode = "";
        String operatorUid = "";
        String senderUid = "";
        long msgSeq = 0L;
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
        if (opBytes == null || opBytes.length == 0) return false;

        String selfUid = Proto.getString(headerBytes, 6);

        if (revokeType == 1) {
            GroupRecallPayload payload = parseGroupRecall(headerBytes, opBytes);

            // 本人主动撤回自己的消息，直接放行
            if (selfUid != null && !selfUid.isEmpty() && selfUid.equals(payload.operatorUid) && (payload.senderUid.isEmpty() || payload.senderUid.equals(selfUid))) {
                PLog.d(TAG, "本人主动撤回自己的消息，放行生效");
                return true;
            }

            if (payload.groupCode.isEmpty()) return false;

            String cacheKey = "grp_" + payload.groupCode + "_seq_" + payload.msgSeq;
            if (payload.msgSeq > 0 && !sRevokedCache.add(cacheKey)) {
                return false;
            }

            String opUin = getUin(payload.operatorUid);
            String opNick = getUserNickName(payload.operatorUid, opUin);

            String json;
            if (!payload.senderUid.isEmpty() && !payload.senderUid.equals(payload.operatorUid)) {
                // 管理员代撤回
                String senderUin = getUin(payload.senderUid);
                String senderNick = getUserNickName(payload.senderUid, senderUin);
                PLog.i(TAG, "拦截[管理代撤回]: 群=" + payload.groupCode + ", 管理=" + opNick + ", 成员=" + senderNick + ", seq=" + payload.msgSeq);
                json = buildGroupAdminRecallJson(payload.operatorUid, opUin, opNick, payload.senderUid, senderUin, senderNick, payload.msgSeq);
            } else {
                // 成员正常撤回
                PLog.i(TAG, "拦截[群成员撤回]: 群=" + payload.groupCode + ", 人员=" + opNick + ", seq=" + payload.msgSeq);
                json = buildGroupClickableJson(payload.operatorUid, opUin, opNick, payload.msgSeq);
            }

            Contact contact = new Contact(2, payload.groupCode, "");
            JsonGrayElement grayElement = new JsonGrayElement(BUSI_ID_GROUP, json, "", false, null);
            msgService.addLocalJsonGrayTipMsg(contact, grayElement, true, true, null);

        } else if (revokeType == 2) {
            byte[] infoBytes = Proto.getBytes(opBytes, 1);
            String operatorUid = infoBytes != null ? Proto.getString(infoBytes, 1) : "";
            if (operatorUid.isEmpty()) operatorUid = Proto.getString(headerBytes, 2);

            if (selfUid != null && !selfUid.isEmpty() && selfUid.equals(operatorUid)) {
                return true;
            }

            // 私聊提取 Tag 20 (444)
            long msgSeq = infoBytes != null ? Proto.getVarint(infoBytes, 20) : 0L;
            if (msgSeq == 0 && infoBytes != null) {
                msgSeq = Proto.getVarint(infoBytes, 2);
            }
            if (msgSeq == 0) {
                msgSeq = findC2CSeqByContext(opBytes);
            }

            if (operatorUid.isEmpty()) return false;

            String cacheKey = "c2c_" + operatorUid + "_seq_" + msgSeq;
            if (msgSeq > 0 && !sRevokedCache.add(cacheKey)) {
                return false;
            }

            PLog.i(TAG, "拦截[私聊撤回]: 对方=" + operatorUid + ", seq=" + msgSeq);
            String json = buildC2CClickableJson(msgSeq);

            Contact contact = new Contact(1, operatorUid, "");
            JsonGrayElement grayElement = new JsonGrayElement(BUSI_ID_C2C, json, "", false, null);
            msgService.addLocalJsonGrayTipMsg(contact, grayElement, true, true, null);
        }
        return false;
    }

    private static GroupRecallPayload parseGroupRecall(byte[] headerBytes, byte[] opBytes) {
        GroupRecallPayload result = new GroupRecallPayload();

        // 1. 群号优先提取
        long groupUin = Proto.getVarint(opBytes, 4);
        if (groupUin > 0) {
            result.groupCode = String.valueOf(groupUin);
        } else {
            String gStr = Proto.getString(headerBytes, 2);
            if (gStr.isEmpty()) {
                long gVal = Proto.getVarint(headerBytes, 1);
                if (gVal > 0) gStr = String.valueOf(gVal);
            }
            result.groupCode = gStr;
        }

        // 2. 真实可定位的群聊 seq 是 4715 那一路！
        long candidateSeq = Proto.getVarint(opBytes, 48);
        if (candidateSeq > 0) {
            result.msgSeq = candidateSeq;
        }

        // 3. 稳健上下文递归提取所有 UID 与 seq
        findRecallInfoBySmartScan(opBytes, result);

        if (result.operatorUid.isEmpty()) {
            result.operatorUid = findFirstUidSafe(opBytes);
        }

        return result;
    }

    private static void findRecallInfoBySmartScan(byte[] data, GroupRecallPayload out) {
        if (data == null || data.length < 10) return;
        ProtoReader reader = new ProtoReader(data);
        long lastSeqCandidate = 0L;

        while (reader.hasMore()) {
            long tag = reader.readVarint();
            int wire = (int) (tag & 7);

            if (wire == 0) {
                long val = reader.readVarint();
                if (val >= 1500000000L && val <= 2050000000L) {
                    if (out.msgSeq == 0 && lastSeqCandidate > 0 && lastSeqCandidate < 500000000L) {
                        out.msgSeq = lastSeqCandidate;
                    }
                } else if (val > 0 && val < 500000000L && val != 732 && val != 528 && val != 17 && val != 138) {
                    lastSeqCandidate = val;
                }
            } else if (wire == 1) {
                reader.skip(8);
            } else if (wire == 2) {
                byte[] sub = reader.readBytes();
                if (sub != null && sub.length > 0) {
                    String str = tryAsciiString(sub);
                    if (str != null && str.startsWith("u_")) {
                        if (out.operatorUid.isEmpty()) {
                            out.operatorUid = str; // 第一个出现的必是操作人
                        } else if (!str.equals(out.operatorUid) && out.senderUid.isEmpty()) {
                            out.senderUid = str;   // 第二个出现的必是被撤回原作者
                        }
                    }
                    findRecallInfoBySmartScan(sub, out);
                }
            } else if (wire == 5) {
                reader.skip(4);
            } else {
                break;
            }
        }
    }

    private static long findC2CSeqByContext(byte[] data) {
        if (data == null || data.length < 5) return 0L;
        ProtoReader reader = new ProtoReader(data);
        long lastVal = 0L;
        while (reader.hasMore()) {
            long tag = reader.readVarint();
            int wire = (int) (tag & 7);
            if (wire == 0) {
                long val = reader.readVarint();
                if (val >= 1500000000L && val <= 2050000000L) {
                    if (lastVal > 0 && lastVal < 500000000L) return lastVal;
                } else if (val > 0 && val < 500000000L) {
                    lastVal = val;
                }
            } else if (wire == 1) {
                reader.skip(8);
            } else if (wire == 2) {
                byte[] sub = reader.readBytes();
                if (sub != null && sub.length > 0) {
                    long s = findC2CSeqByContext(sub);
                    if (s > 0) return s;
                }
            } else if (wire == 5) {
                reader.skip(4);
            } else {
                break;
            }
        }
        return 0L;
    }

    private static String findFirstUidSafe(byte[] data) {
        if (data == null) return "";
        ProtoReader reader = new ProtoReader(data);
        while (reader.hasMore()) {
            long tag = reader.readVarint();
            int wire = (int) (tag & 7);
            if (wire == 2) {
                byte[] sub = reader.readBytes();
                if (sub != null) {
                    String s = tryAsciiString(sub);
                    if (s != null && s.startsWith("u_")) return s;
                    String inner = findFirstUidSafe(sub);
                    if (!inner.isEmpty()) return inner;
                }
            } else if (wire == 0) reader.readVarint();
            else if (wire == 1) reader.skip(8);
            else if (wire == 5) reader.skip(4);
            else break;
        }
        return "";
    }

    private static String tryAsciiString(byte[] b) {
        if (b == null || b.length < 4 || b.length > 50) return null;
        for (byte v : b) {
            if (v < 32 || v > 126) return null;
        }
        return new String(b, StandardCharsets.UTF_8);
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
        String uinVal = (uin != null && !uin.isEmpty()) ? uin : "";
        String jpVal = (!uinVal.isEmpty()) ? uinVal : operatorUid;

        sb.append("{\"col\":\"3\",\"jp\":\"").append(jpVal).append("\",\"nm\":\"").append(displayNick)
          .append("\",\"tp\":\"1\",\"type\":\"qq\",\"uid\":\"").append(operatorUid).append("\",\"uin\":\"").append(uinVal).append("\"},");
        sb.append("{\"txt\":\" 尝试撤回 \",\"type\":\"nor\"},");
        sb.append("{\"col\":\"3\",\"local_jp\":58,");
        if (msgSeq > 0) sb.append("\"param\":{\"seq\":\"").append(msgSeq).append("\"},");
        else sb.append("\"param\":{},");
        sb.append("\"txt\":\"一条消息\",\"type\":\"url\"}");
        sb.append("]}");
        return sb.toString();
    }

    private static String buildGroupAdminRecallJson(String opUid, String opUin, String opNick,
                                                    String senderUid, String senderUin, String senderNick, long msgSeq) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"align\":\"center\",\"items\":[");

        String opUinVal = (opUin != null && !opUin.isEmpty()) ? opUin : "";
        String opJp = (!opUinVal.isEmpty()) ? opUinVal : opUid;

        String senderUinVal = (senderUin != null && !senderUin.isEmpty()) ? senderUin : "";
        String senderJp = (!senderUinVal.isEmpty()) ? senderUinVal : senderUid;

        // 1. 操作人 (管理员/群主) -> tp: "1" 群名片点击
        sb.append("{\"col\":\"3\",\"jp\":\"").append(opJp).append("\",\"nm\":\"").append(escapeJson(opNick))
          .append("\",\"tp\":\"1\",\"type\":\"qq\",\"uid\":\"").append(opUid).append("\",\"uin\":\"").append(opUinVal).append("\"},");
        sb.append("{\"txt\":\" 尝试撤回 \",\"type\":\"nor\"},");

        // 2. 原发送者 (被撤回成员) -> tp: "1" 群名片点击
        sb.append("{\"col\":\"3\",\"jp\":\"").append(senderJp).append("\",\"nm\":\"").append(escapeJson(senderNick))
          .append("\",\"tp\":\"1\",\"type\":\"qq\",\"uid\":\"").append(senderUid).append("\",\"uin\":\"").append(senderUinVal).append("\"},");
        sb.append("{\"txt\":\" 的 \",\"type\":\"nor\"},");

        // 3. 一条消息 (跳转高亮)
        sb.append("{\"col\":\"3\",\"local_jp\":58,");
        if (msgSeq > 0) sb.append("\"param\":{\"seq\":\"").append(msgSeq).append("\"},");
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
        if (msgSeq > 0) sb.append("\"param\":{\"seq\":\"").append(msgSeq).append("\"},");
        else sb.append("\"param\":{},");
        sb.append("\"txt\":\"一条消息\",\"type\":\"url\"}");
        sb.append("]}");
        return sb.toString();
    }

    private static String escapeJson(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

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

        static byte[] subArray(byte[] src, int start) {
            if (src == null || start < 0 || start >= src.length) return new byte[0];
            int length = src.length - start;
            byte[] dest = new byte[length];
            System.arraycopy(src, start, dest, 0, length);
            return dest;
        }

        static byte[] subArray(byte[] src, int start, int length) {
            if (src == null || start < 0 || length <= 0 || start + length > src.length) return new byte[0];
            byte[] dest = new byte[length];
            System.arraycopy(src, start, dest, 0, length);
            return dest;
        }
    }
}
