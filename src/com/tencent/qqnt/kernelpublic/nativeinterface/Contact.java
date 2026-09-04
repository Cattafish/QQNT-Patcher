package com.tencent.qqnt.kernelpublic.nativeinterface;

public class Contact {
    public int chatType;
    public String peerUid;
    public String guildId;

    public Contact() {}

    public Contact(int chatType, String peerUid, String guildId) {
        this.chatType = chatType;
        this.peerUid = peerUid;
        this.guildId = guildId;
    }

    public int getChatType() {
        return chatType;
    }

    public String getPeerUid() {
        return peerUid;
    }

    public String getGuildId() {
        return guildId;
    }
}
