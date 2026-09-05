package me.yxp.qfun.plugin.bean;

public class FriendInfo {
    public String uin;
    public String uid;
    public String name;
    public String remark;

    public FriendInfo(String uin, String uid, String name, String remark) {
        this.uin = uin != null ? uin : "";
        this.uid = uid != null ? uid : "";
        this.name = name != null ? name : "";
        this.remark = remark != null ? remark : "";
    }
}
