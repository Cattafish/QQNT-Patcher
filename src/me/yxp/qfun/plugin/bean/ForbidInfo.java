package me.yxp.qfun.plugin.bean;

public class ForbidInfo {
    public String user;
    public long endTime;
    public long time;
    public String userName;

    public ForbidInfo(String user, long endTime, long time, String userName) {
        this.user = user != null ? user : "";
        this.endTime = endTime;
        this.time = time;
        this.userName = userName != null ? userName : "";
    }
}
