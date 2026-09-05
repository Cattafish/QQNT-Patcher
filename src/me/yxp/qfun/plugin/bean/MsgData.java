package me.yxp.qfun.plugin.bean;

import com.tencent.mobileqq.qroute.QRoute;
import com.tencent.qqnt.kernel.nativeinterface.MsgElement;
import com.tencent.qqnt.kernel.nativeinterface.MsgRecord;
import com.tencent.qqnt.kernel.nativeinterface.PicElement;
import com.tencent.qqnt.kernelpublic.nativeinterface.Contact;
import com.tencent.qqnt.patch.plugin.CookieHelper;
import com.tencent.qqnt.patch.plugin.MsgSender;
import com.tencent.relation.common.api.IRelationNTUinAndUidApi;

import java.util.ArrayList;
import java.util.HashMap;

public class MsgData {
    public int type;
    public int msgType;
    public String peerUin;
    public String peerUid;
    public String userUin;
    public String userUid;
    public long time;
    public long msgId;
    public long msgSeq;
    public String msg = "";
    public String path = "";
    public ArrayList<String> atList = new ArrayList<>();
    public HashMap<String, String> atMap = new HashMap<>();
    public String senderName = "";
    public MsgRecord data;
    public Contact contact;
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
        this.contact = new Contact(this.type, this.peerUid, "");

        if (record.sendMemberName != null && !record.sendMemberName.isEmpty()) {
            this.senderName = record.sendMemberName;
        } else if (record.sendNickName != null) {
            this.senderName = record.sendNickName;
        }

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

        if (record.elements != null) {
            StringBuilder sb = new StringBuilder();
            String rkey = this.isGroup ? CookieHelper.getGroupRKey() : CookieHelper.getFriendRKey();
            for (MsgElement elem : record.elements) {
                if (elem == null) continue;
                if (elem.textElement != null && elem.textElement.content != null) {
                    sb.append(elem.textElement.content);
                    if (elem.textElement.atType != 0) {
                        String atUin = getUinFromUid(elem.textElement.atNtUid);
                        if (atUin.isEmpty() && elem.textElement.atUid > 0) atUin = String.valueOf(elem.textElement.atUid);
                        if (!atUin.isEmpty()) {
                            this.atList.add(atUin);
                            this.atMap.put(atUin, elem.textElement.content);
                        }
                    }
                } else if (elem.picElement != null) {
                    PicElement pic = elem.picElement;
                    String url = pic.originImageUrl != null ? pic.originImageUrl : pic.sourcePath;
                    if (url != null && !url.startsWith("http")) {
                        sb.append("[pic=https://multimedia.nt.qq.com.cn").append(url).append(rkey).append("]");
                    } else {
                        sb.append("[pic=").append(url).append("]");
                    }
                    if (pic.sourcePath != null && !pic.sourcePath.isEmpty()) this.path = pic.sourcePath;
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
