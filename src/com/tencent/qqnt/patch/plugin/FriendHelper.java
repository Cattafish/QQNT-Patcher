package com.tencent.qqnt.patch.plugin;

import com.tencent.qqnt.patch.AppContext;
import me.yxp.qfun.plugin.bean.FriendInfo;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

public class FriendHelper {

    private static Object getFriendsInfoService() {
        try {
            Class<?> qrouteClz = Class.forName("com.tencent.mobileqq.qroute.QRoute");
            Class<?> apiClz = Class.forName("com.tencent.qqnt.ntrelation.friendsinfo.api.IFriendsInfoService");
            return qrouteClz.getMethod("api", Class.class).invoke(null, apiClz);
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static List<FriendInfo> getAllFriend() {
        List<FriendInfo> list = new ArrayList<>();
        try {
            Object service = getFriendsInfoService();
            if (service == null) return list;

            Method getAll = service.getClass().getMethod("getAllFriend", String.class);
            List<?> all = (List<?>) getAll.invoke(service, "");
            if (all == null) return list;

            Method getNick = service.getClass().getMethod("getNickWithUid", String.class, String.class);
            Method getRemark = service.getClass().getMethod("getRemarkWithUid", String.class, String.class);

            for (Object obj : all) {
                String s = String.valueOf(obj);
                String[] parts = s.split(" ");
                if (parts.length >= 5) {
                    String uin = parts[2];
                    String uid = parts[4];
                    String nick = (String) getNick.invoke(service, uid, "");
                    String remark = (String) getRemark.invoke(service, uid, "");
                    list.add(new FriendInfo(uin, uid, nick, remark));
                }
            }
        } catch (Throwable ignored) {}
        return list;
    }

    public static boolean isFriend(String uin) {
        try {
            String uid = MsgSender.getUidFromUin(uin);
            Object service = getFriendsInfoService();
            if (service == null) return false;
            Method isFriendM = service.getClass().getMethod("isFriend", String.class, String.class);
            return (Boolean) isFriendM.invoke(service, uid, "");
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static void sendZan(String targetUin, int count) {
        try {
            Object runtime = AppContext.getAppRuntime();
            if (runtime == null) return;
            Method getHandler = runtime.getClass().getMethod("getBusinessHandler", String.class);
            Object cardHandler = getHandler.invoke(runtime, "com.tencent.mobileqq.app.CardHandler");
            if (cardHandler == null) return;

            long selfUin = Long.parseLong(MsgSender.getMyUin());
            long toUin = Long.parseLong(targetUin);
            boolean friend = isFriend(targetUin);

            byte[] reqData = new byte[]{12, 24, 0, 1, 6, 1, 49, 22, 1, (byte) (friend ? 49 : 53)};

            for (Method m : cardHandler.getClass().getMethods()) {
                Class<?>[] p = m.getParameterTypes();
                if (p.length == 6 && p[0] == long.class && p[1] == long.class && p[2] == byte[].class) {
                    m.setAccessible(true);
                    m.invoke(cardHandler, selfUin, toUin, reqData, friend ? 1 : 5, count, 0);
                    break;
                }
            }
        } catch (Throwable ignored) {}
    }
}
