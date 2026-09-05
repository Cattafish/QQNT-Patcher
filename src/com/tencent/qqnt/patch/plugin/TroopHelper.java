package com.tencent.qqnt.patch.plugin;

import com.tencent.qqnt.patch.AppContext;
import me.yxp.qfun.plugin.bean.ForbidInfo;
import me.yxp.qfun.plugin.bean.GroupInfo;
import me.yxp.qfun.plugin.bean.MemberInfo;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class TroopHelper {

    private static Object getKernelGroupService() {
        try {
            Object runtime = AppContext.getAppRuntime();
            if (runtime == null) return null;
            Class<?> kernelServiceClz = Class.forName("com.tencent.qqnt.kernel.api.IKernelService");
            Method getRuntimeService = runtime.getClass().getMethod("getRuntimeService", Class.class, String.class);
            Object kernelService = getRuntimeService.invoke(runtime, kernelServiceClz, "");
            if (kernelService != null) {
                Object groupServiceWrapper = kernelService.getClass().getMethod("getGroupService").invoke(kernelService);
                if (groupServiceWrapper != null) {
                    return groupServiceWrapper.getClass().getMethod("getService").invoke(groupServiceWrapper);
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    public static List<GroupInfo> getGroupList() {
        List<GroupInfo> result = new ArrayList<>();
        try {
            Class<?> qrouteClz = Class.forName("com.tencent.mobileqq.qroute.QRoute");
            Class<?> troopRepoClz = Class.forName("com.tencent.qqnt.troop.ITroopListRepoApi");
            Object repo = qrouteClz.getMethod("api", Class.class).invoke(null, troopRepoClz);
            if (repo != null) {
                List<?> list = (List<?>) repo.getClass().getMethod("getSortedJoinedTroopInfoFromCache").invoke(repo);
                if (list != null) {
                    for (Object tInfo : list) {
                        Class<?> c = tInfo.getClass();
                        Field fUin = c.getField("troopuin");
                        Field fName = c.getField("troopNameFromNT");
                        Field fOwner = c.getField("troopowneruin");
                        String uin = String.valueOf(fUin.get(tInfo));
                        String name = fName.get(tInfo) != null ? String.valueOf(fName.get(tInfo)) : uin;
                        String owner = String.valueOf(fOwner.get(tInfo));
                        result.add(new GroupInfo(uin, name, owner, tInfo));
                    }
                }
            }
        } catch (Throwable ignored) {}
        return result;
    }

    public static Object getGroupInfo(String troopUin) {
        try {
            Object runtime = AppContext.getAppRuntime();
            if (runtime == null) return null;
            Class<?> clz = Class.forName("com.tencent.mobileqq.troop.api.ITroopInfoService");
            Method getRuntimeService = runtime.getClass().getMethod("getRuntimeService", Class.class, String.class);
            Object service = getRuntimeService.invoke(runtime, clz, "");
            if (service != null) {
                Method getInfo = service.getClass().getMethod("getTroopInfo", String.class);
                return getInfo.invoke(service, troopUin);
            }
        } catch (Throwable ignored) {}
        return null;
    }

    public static void shutUp(String troopUin, String memberUin, long seconds) {
        try {
            Object groupService = getKernelGroupService();
            if (groupService == null) return;
            long gCode = Long.parseLong(troopUin);
            String uid = MsgSender.getUidFromUin(memberUin);

            Class<?> shutUpInfoClz = Class.forName("com.tencent.qqnt.kernel.nativeinterface.GroupMemberShutUpInfo");
            Object info = shutUpInfoClz.getDeclaredConstructor().newInstance();
            shutUpInfoClz.getField("uid").set(info, uid);
            shutUpInfoClz.getField("timeStamp").set(info, (int) seconds);

            ArrayList<Object> list = new ArrayList<>();
            list.add(info);

            for (Method m : groupService.getClass().getMethods()) {
                if ("setMemberShutUp".equals(m.getName()) && m.getParameterTypes().length >= 3) {
                    m.invoke(groupService, gCode, list, null);
                    break;
                }
            }
        } catch (Throwable ignored) {}
    }

    public static void shutUpAll(String troopUin, boolean enable) {
        try {
            Object groupService = getKernelGroupService();
            if (groupService == null) return;
            long gCode = Long.parseLong(troopUin);
            for (Method m : groupService.getClass().getMethods()) {
                if ("setGroupShutUp".equals(m.getName()) && m.getParameterTypes().length >= 3) {
                    m.invoke(groupService, gCode, enable, null);
                    break;
                }
            }
        } catch (Throwable ignored) {}
    }

    public static void kickGroup(String troopUin, String memberUin, boolean block) {
        try {
            Object groupService = getKernelGroupService();
            if (groupService == null) return;
            long gCode = Long.parseLong(troopUin);
            String uid = MsgSender.getUidFromUin(memberUin);
            ArrayList<String> uids = new ArrayList<>();
            uids.add(uid);

            for (Method m : groupService.getClass().getMethods()) {
                if ("kickMember".equals(m.getName()) && m.getParameterTypes().length >= 5) {
                    m.invoke(groupService, gCode, uids, block, "", null);
                    break;
                }
            }
        } catch (Throwable ignored) {}
    }

    public static void changeMemberName(String troopUin, String memberUin, String newCard) {
        try {
            Object groupService = getKernelGroupService();
            if (groupService == null) return;
            long gCode = Long.parseLong(troopUin);
            String uid = MsgSender.getUidFromUin(memberUin);
            for (Method m : groupService.getClass().getMethods()) {
                if ("modifyMemberCardName".equals(m.getName()) && m.getParameterTypes().length >= 4) {
                    m.invoke(groupService, gCode, uid, newCard, null);
                    break;
                }
            }
        } catch (Throwable ignored) {}
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public static void setGroupAdmin(String troopUin, String memberUin, boolean enable) {
        try {
            Object groupService = getKernelGroupService();
            if (groupService == null) return;
            long gCode = Long.parseLong(troopUin);
            String uid = MsgSender.getUidFromUin(memberUin);

            Class roleEnumClz = Class.forName("com.tencent.qqnt.kernelpublic.nativeinterface.MemberRole");
            Object role = Enum.valueOf(roleEnumClz, enable ? "ADMIN" : "MEMBER");

            for (Method m : groupService.getClass().getMethods()) {
                if ("modifyMemberRole".equals(m.getName()) && m.getParameterTypes().length >= 4) {
                    m.invoke(groupService, gCode, uid, role, null);
                    break;
                }
            }
        } catch (Throwable ignored) {}
    }

    public static void clockIn(String troopUin) {
        try {
            Object runtime = AppContext.getAppRuntime();
            if (runtime == null) return;
            Method getHandler = runtime.getClass().getMethod("getBusinessHandler", String.class);
            Object handler = getHandler.invoke(runtime, "com.tencent.mobileqq.troop.clockin.handler.TroopClockInHandler");
            if (handler != null) {
                for (Method m : handler.getClass().getMethods()) {
                    Class<?>[] pts = m.getParameterTypes();
                    if (pts.length == 2 && pts[0] == String.class && pts[1] == String.class) {
                        m.invoke(handler, troopUin, MsgSender.getMyUin());
                        break;
                    }
                }
            }
        } catch (Throwable ignored) {}
    }
}
