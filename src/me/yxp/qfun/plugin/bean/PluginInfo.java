package me.yxp.qfun.plugin.bean;

public class PluginInfo {
    public String id;
    public String name;
    public String version;
    public String author;
    public boolean isRunning;

    public PluginInfo() {}

    public PluginInfo(String id, String name, String version, String author, boolean isRunning) {
        this.id = id;
        this.name = name;
        this.version = version;
        this.author = author;
        this.isRunning = isRunning;
    }

    public String getId() {
        return id != null ? id : "";
    }

    public String getName() {
        return name != null ? name : "";
    }

    public String getVersion() {
        return version != null ? version : "";
    }

    public String getAuthor() {
        return author != null ? author : "";
    }

    public boolean isRunning() {
        return isRunning;
    }
}
