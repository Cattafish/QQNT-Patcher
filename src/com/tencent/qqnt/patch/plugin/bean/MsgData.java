package com.tencent.qqnt.patch.plugin.bean;

import com.tencent.mobileqq.qroute.QRoute;
import com.tencent.qqnt.kernel.nativeinterface.MsgElement;
import com.tencent.qqnt.kernel.nativeinterface.MsgRecord;
import com.tencent.qqnt.kernel.nativeinterface.PicElement;
import com.tencent.qqnt.kernelpublic.nativeinterface.Contact;
import com.tencent.qqnt.patch.plugin.MsgSender;
import com.tencent.relation.common.api.IRelationNTUinAndUidApi;

import java.util.ArrayList;
import java.util.HashMap;

public class MsgData {

    // === 对标 QFun 核心字段 ===
    public int type;                                        // 1: 好友, 2: 群聊, 100: 临时
    public int msgType;                                     // 消息类型
    public String peerUin;                                  // 群号或好友QQ号
    public String peerUid;                                  // 群号或好友UID
    public String userUin;                                  // 发送者QQ号
    public String userUid;                                  // 发送者UID
    public long time;                                       // 发送时间戳 (秒)
    public long msgId;                                      // 消息ID
    public long msgSeq;                                     // 消息Seq
    public String msg = "";                                 // 消息文本 (解析后的图文BBCode)
    public String path = "";                                // 文件/多媒体路径
    public ArrayList<String> atList = new ArrayList<>();    // 艾特的QQ列表
    public HashMap<String, String> atMap = new HashMap<>(); // 艾特映射表 (UIN -> 显示文本)
    public String senderName = "";                          // 发送者群名片/昵称
    public MsgRecord data;                                  // ★ QFun 原名：原始 MsgRecord 对象
    public Contact contact;                                 // ★ QFun 原名：原始 Contact 对象

    // === 扩展增强标志 ===
    public boolean isGroup;
    public boolean isPrivate;
    public boolean isSelf;

    public MsgData(MsgRecord record) {
        if (record == null) return;
        this.data = record;
        this.type = record.chatType;
        this.msgType = record.msgType;
        this.msgId = record.msgId;
        this.msgSeq = record.msgSeq;
        this.time = record.msgTime;
        this.isGroup = (this.type == 2);
        this.isPrivate = (this.type == 1);
        this.isSelf = (record.sendType == 1);

        this.peerUid = record.peerUid != null ? record.peerUid : "";
        this.userUid = record.senderUid != null ? record.senderUid : "";

        // 构造 QFun 兼容的 Contact 对象
        this.contact = new Contact(this.type, this.peerUid, "");

        // 解析名字
        if (record.sendMemberName != null && !record.sendMemberName.isEmpty()) {
            this.senderName = record.sendMemberName;
        } else if (record.sendNickName != null) {
            this.senderName = record.sendNickName;
        }

        // 解析群号 / QQ号
        if (record.peerUin > 0) {
            this.peerUin = String.valueOf(record.peerUin);
        } else if (this.isGroup) {
            this.peerUin = this.peerUid;
        } else {
            this.peerUin = getUinFromUid(this.peerUid);
        }

        if (record.senderUin > 0) {
            this.userUin = String.valueOf(record.senderUin);
        } else {
            this.userUin = getUinFromUid(this.userUid);
        }

        // 解析元素列表
        if (record.elements != null) {
            StringBuilder sb = new StringBuilder();
            for (MsgElement elem : record.elements) {
                if (elem == null) continue;
                if (elem.textElement != null && elem.textElement.content != null) {
                    sb.append(elem.textElement.content);
                    if (elem.textElement.atType != 0) {
                        String atUin = getUinFromUid(elem.textElement.atNtUid);
                        if (atUin.isEmpty() && elem.textElement.atUid > 0) {
                            atUin = String.valueOf(elem.textElement.atUid);
                        }
                        if (!atUin.isEmpty()) {
                            this.atList.add(atUin);
                            this.atMap.put(atUin, elem.textElement.content);
                        }
                    }
                } else if (elem.picElement != null) {
                    PicElement pic = elem.picElement;
                    String url = pic.originImageUrl != null ? pic.originImageUrl : pic.sourcePath;
                    sb.append("[pic=").append(url).append("]");
                    if (pic.sourcePath != null && !pic.sourcePath.isEmpty()) {
                        this.path = pic.sourcePath;
                    }
                }
            }
            this.msg = sb.toString();
        }
    }

    public void reply(String text) {
        MsgSender.sendMsg(this.contact, text);
    }

    private static String getUinFromUid(String uid) {
        if (uid == null || uid.isEmpty()) return "";
        try {
            IRelationNTUinAndUidApi api = QRoute.api(IRelationNTUinAndUidApi.class);
            if (api != null) {
                String uin = api.getUinFromUid(uid);
                if (uin != null && !uin.isEmpty() && !uin.equals("0")) return uin;
            }
        } catch (Throwable ignored) {}
        return uid;
    }
}
