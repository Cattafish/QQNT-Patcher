package com.tencent.qqnt.patch.plugin;

import android.app.Activity;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import com.tencent.qqnt.kernelpublic.nativeinterface.Contact;
import me.yxp.qfun.plugin.bean.FriendInfo;
import me.yxp.qfun.plugin.bean.GroupInfo;
import me.yxp.qfun.plugin.bean.MemberInfo;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class PluginMethod {

    private static final String TAG = "QQ_DEBUG";
    private final Context mContext;
    private final File mPluginDir;
    private final File mConfigDir;
    private final Handler mMainHandler;
    private PluginCompiler mCompiler;
    private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault());

    public PluginMethod(Context context, File pluginDir) {
        this.mContext = context;
        this.mPluginDir = pluginDir;
        this.mConfigDir = new File(pluginDir, "config");
        if (!this.mConfigDir.exists()) this.mConfigDir.mkdirs();
        this.mMainHandler = new Handler(Looper.getMainLooper());
    }

    public void setCompiler(PluginCompiler compiler) {
        this.mCompiler = compiler;
    }

    // ================= 基础日志与 Toast =================
    public void log(Object msg) { log("log.txt", msg); }

    public synchronized void log(String fileName, Object msg) {
        try {
            String time = DATE_FORMAT.format(new Date());
            String line = "[" + time + "] " + String.valueOf(msg) + "\n";
            Log.i(TAG, "[" + mPluginDir.getName() + "][" + fileName + "] " + String.valueOf(msg));
            if (mPluginDir == null || !mPluginDir.exists()) return;
            File logFile = new File(mPluginDir, fileName);
            try (FileOutputStream fos = new FileOutputStream(logFile, true);
                 OutputStreamWriter osw = new OutputStreamWriter(fos, StandardCharsets.UTF_8)) {
                osw.write(line);
                osw.flush();
            }
        } catch (Throwable ignored) {}
    }

    public void toast(final Object msg) {
        if (mContext == null) return;
        mMainHandler.post(() -> {
            try {
                Toast.makeText(mContext, String.valueOf(msg), Toast.LENGTH_SHORT).show();
            } catch (Throwable ignored) {}
        });
    }

    public void qqToast(final int icon, final Object msg) {
        mMainHandler.post(() -> {
            try {
                Class<?> toastUtilClz = Class.forName("com.tencent.util.QQToastUtil");
                Method m = toastUtilClz.getMethod("showQQToastInUiThread", int.class, String.class);
                m.invoke(null, icon, String.valueOf(msg));
            } catch (Throwable t) {
                toast(msg);
            }
        });
    }

    public void loadJava(String path) {
        if (mCompiler != null) mCompiler.loadJava(path);
    }

    public void addItem(String name, String callbackMethod) {
        if (mCompiler != null) mCompiler.addMenuItem(name, callbackMethod);
    }

    public Activity getNowActivity() {
        return com.tencent.qqnt.patch.AppContext.getCurrentActivity();
    }

    // ================= 消息发送全家桶 =================
    public void sendMsg(String peerUin, String msg, int chatType) {
        MsgSender.sendMsg(peerUin, msg, chatType);
    }

    public void sendMsg(Object contactObj, String msg) {
        if (contactObj instanceof Contact) MsgSender.sendMsg((Contact) contactObj, msg);
    }

    public void sendPic(String peerUin, String path, int chatType) {
        sendMsg(peerUin, "[pic=" + path + "]", chatType);
    }

    public void sendPic(Object contactObj, String path) {
        sendMsg(contactObj, "[pic=" + path + "]");
    }

    public void sendPtt(String peerUin, String path, int chatType) {
        MsgSender.sendPtt(peerUin, path, chatType, 0);
    }

    public void sendPtt(String peerUin, String path, int chatType, int durationMs) {
        MsgSender.sendPtt(peerUin, path, chatType, durationMs);
    }

    public void sendPtt(Object contactObj, String path) {
        if (contactObj instanceof Contact) MsgSender.sendPtt((Contact) contactObj, path, 0);
    }

    public void sendPtt(Object contactObj, String path, int durationMs) {
        if (contactObj instanceof Contact) MsgSender.sendPtt((Contact) contactObj, path, durationMs);
    }

    public void sendReplyMsg(String peerUin, long replyMsgId, String msg, int chatType) {
        MsgSender.sendReplyMsg(peerUin, replyMsgId, msg, chatType);
    }

    public void sendReplyMsg(Object contactObj, long replyMsgId, String msg) {
        if (contactObj instanceof Contact) MsgSender.sendReplyMsg((Contact) contactObj, replyMsgId, msg);
    }

    public void sendCard(String peerUin, String json, int chatType) {
        MsgSender.sendCard(peerUin, json, chatType);
    }

    public void sendCard(Object contactObj, String json) {
        if (contactObj instanceof Contact) MsgSender.sendCard((Contact) contactObj, json);
    }

    public void sendVideo(String peerUin, String path, int chatType) {
        MsgSender.sendVideo(peerUin, path, chatType);
    }

    public void sendVideo(Object contactObj, String path) {
        if (contactObj instanceof Contact) MsgSender.sendVideo((Contact) contactObj, path);
    }

    public void sendFile(String peerUin, String path, int chatType) {
        MsgSender.sendFile(peerUin, path, chatType);
    }

    public void sendFile(Object contactObj, String path) {
        if (contactObj instanceof Contact) MsgSender.sendFile((Contact) contactObj, path);
    }

    public void sendPai(String toUin, String peerUin, int chatType) {
        MsgSender.sendPai(toUin, peerUin, chatType);
    }

    public void recallMsg(int chatType, String peerUin, long msgId) {
        MsgSender.recall(chatType, peerUin, msgId);
    }

    public void recallMsg(Object contactObj, long msgId) {
        if (contactObj instanceof Contact) MsgSender.recall((Contact) contactObj, msgId);
    }

    public String getUidFromUin(String uin) { return MsgSender.getUidFromUin(uin); }
    public String getUinFromUid(String uid) { return MsgSender.getUinFromUid(uid); }

    // ================= QFun 群管与群信息接口 =================
    public List<GroupInfo> getGroupList() { return TroopHelper.getGroupList(); }
    public Object getGroupInfo(String troopUin) { return TroopHelper.getGroupInfo(troopUin); }
    public void shutUp(String troopUin, String memberUin, long seconds) { TroopHelper.shutUp(troopUin, memberUin, seconds); }
    public void shutUpAll(String troopUin, boolean enable) { TroopHelper.shutUpAll(troopUin, enable); }
    public void kickGroup(String troopUin, String memberUin, boolean block) { TroopHelper.kickGroup(troopUin, memberUin, block); }
    public void changeMemberName(String troopUin, String memberUin, String newCard) { TroopHelper.changeMemberName(troopUin, memberUin, newCard); }
    public void setGroupAdmin(String troopUin, String memberUin, boolean enable) { TroopHelper.setGroupAdmin(troopUin, memberUin, enable); }
    public void clockIn(String troopUin) { TroopHelper.clockIn(troopUin); }

    // ================= QFun 好友与点赞接口 =================
    public List<FriendInfo> getAllFriend() { return FriendHelper.getAllFriend(); }
    public boolean isFriend(String uin) { return FriendHelper.isFriend(uin); }
    public void sendZan(String uin, int count) { FriendHelper.sendZan(uin, count); }

    // ================= QFun Token / Cookie 接口 =================
    public String getRealSkey() { return CookieHelper.getRealSkey(); }
    public String getSkey() { return CookieHelper.getSkey(); }
    public String getStweb() { return CookieHelper.getStweb(); }
    public String getPskey(String domain) { return CookieHelper.getPskey(domain); }
    public String getPt4Token(String domain) { return CookieHelper.getPt4Token(domain); }
    public long getBkn(String key) { return CookieHelper.getBkn(key); }
    public String getGTK(String domain) { return CookieHelper.getGTK(domain); }

    // ================= 数据持久化 =================
    public synchronized void putString(String configName, String key, String value) {
        try {
            org.json.JSONObject json = readConfigFile(configName);
            json.put(key, value);
            writeConfigFile(configName, json);
        } catch (Throwable ignored) {}
    }

    public synchronized void putInt(String configName, String key, int value) {
        try {
            org.json.JSONObject json = readConfigFile(configName);
            json.put(key, value);
            writeConfigFile(configName, json);
        } catch (Throwable ignored) {}
    }

    public synchronized void putLong(String configName, String key, long value) {
        try {
            org.json.JSONObject json = readConfigFile(configName);
            json.put(key, value);
            writeConfigFile(configName, json);
        } catch (Throwable ignored) {}
    }

    public synchronized void putBoolean(String configName, String key, boolean value) {
        try {
            org.json.JSONObject json = readConfigFile(configName);
            json.put(key, value);
            writeConfigFile(configName, json);
        } catch (Throwable ignored) {}
    }

    public synchronized String getString(String configName, String key, String defaultValue) {
        try {
            org.json.JSONObject json = readConfigFile(configName);
            return json.optString(key, defaultValue);
        } catch (Throwable ignored) {}
        return defaultValue;
    }

    public synchronized int getInt(String configName, String key, int defaultValue) {
        try {
            org.json.JSONObject json = readConfigFile(configName);
            return json.optInt(key, defaultValue);
        } catch (Throwable ignored) {}
        return defaultValue;
    }

    public synchronized long getLong(String configName, String key, long defaultValue) {
        try {
            org.json.JSONObject json = readConfigFile(configName);
            return json.optLong(key, defaultValue);
        } catch (Throwable ignored) {}
        return defaultValue;
    }

    public synchronized boolean getBoolean(String configName, String key, boolean defaultValue) {
        try {
            org.json.JSONObject json = readConfigFile(configName);
            return json.optBoolean(key, defaultValue);
        } catch (Throwable ignored) {}
        return defaultValue;
    }

    private org.json.JSONObject readConfigFile(String configName) {
        File file = new File(mConfigDir, configName + ".json");
        if (!file.exists()) return new org.json.JSONObject();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            return new org.json.JSONObject(sb.toString());
        } catch (Throwable ignored) {}
        return new org.json.JSONObject();
    }

    private void writeConfigFile(String configName, org.json.JSONObject json) {
        File file = new File(mConfigDir, configName + ".json");
        try (FileOutputStream fos = new FileOutputStream(file);
             OutputStreamWriter osw = new OutputStreamWriter(fos, StandardCharsets.UTF_8)) {
            osw.write(json.toString(2));
            osw.flush();
        } catch (Throwable ignored) {}
    }
}
