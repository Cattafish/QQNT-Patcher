package me.yxp.qfun.plugin.bean;

public class GroupInfo {
    public String group;
    public String groupName;
    public String groupOwner;
    public Object groupInfo;

    public GroupInfo(String group, String groupName, String groupOwner, Object groupInfo) {
        this.group = group != null ? group : "";
        this.groupName = groupName != null ? groupName : "";
        this.groupOwner = groupOwner != null ? groupOwner : "";
        this.groupInfo = groupInfo;
    }
}
