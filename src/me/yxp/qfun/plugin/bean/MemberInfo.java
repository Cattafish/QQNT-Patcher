package me.yxp.qfun.plugin.bean;

public class MemberInfo {
    public long joinGroupTime;
    public long lastActiveTime;
    public String uin;
    public int uinLevel;
    public String uinName;
    public String role;
    public Object memberInfo;

    public MemberInfo(long joinGroupTime, long lastActiveTime, String uin, int uinLevel, String uinName, String role, Object memberInfo) {
        this.joinGroupTime = joinGroupTime;
        this.lastActiveTime = lastActiveTime;
        this.uin = uin != null ? uin : "";
        this.uinLevel = uinLevel;
        this.uinName = uinName != null ? uinName : "";
        this.role = role != null ? role : "";
        this.memberInfo = memberInfo;
    }
}
