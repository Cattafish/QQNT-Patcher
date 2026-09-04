package com.tencent.qqnt.patch.plugin;

import android.util.Log;
import com.tencent.mobileqq.qroute.QRoute;
import com.tencent.qqnt.kernel.nativeinterface.IKernelMsgService;
import com.tencent.qqnt.kernel.nativeinterface.IQQNTWrapperSession;
import com.tencent.qqnt.kernel.nativeinterface.MsgElement;
import com.tencent.qqnt.kernel.nativeinterface.TextElement;
import com.tencent.qqnt.kernelpublic.nativeinterface.Contact;
import com.tencent.relation.common.api.IRelationNTUinAndUidApi;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MsgSender {

    private static final String TAG = "QQ_DEBUG";
    private static volatile IQQNTWrapperSession sSession = null;
    private static volatile IKernelMsgService sMsgService = null;

    public static void setSession(IQQNTWrapperSession session) {
        if (session != null) {
            sSession = session;
            try {
                sMsgService = session.getMsgService();
            } catch (Throwable ignored) {}
        }
    }

    public static IKernelMsgService getMsgService() {
        if (sMsgService != null) return sMsgService;
        if (sSession != null) {
            try {
                sMsgService = sSession.getMsgService();
                if (sMsgService != null) return sMsgService;
            } catch (Throwable ignored) {}
        }
        try {
            Class<?> qrouteClz = Class.forName("com.tencent.mobileqq.qroute.QRoute");
            Class<?> kernelServiceClz = Class.forName("com.tencent.qqnt.kernel.api.IKernelService");
            Object kernelService = qrouteClz.getMethod("api", Class.class).invoke(null, kernelServiceClz);
            if (kernelService != null) {
                Object wrapper = kernelService.getClass().getMethod("getWrapperSession").invoke(kernelService);
                if (wrapper instanceof IQQNTWrapperSession) {
                    sSession = (IQQNTWrapperSession) wrapper;
                    sMsgService = sSession.getMsgService();
                }
            }
        } catch (Throwable ignored) {}
        return sMsgService;
    }

    public static String getMyUin() {
        try {
            Class<?> mobileQQClz = Class.forName("mqq.app.MobileQQ");
            Object mobileQQ = mobileQQClz.getMethod("getMobileQQ").invoke(null);
            if (mobileQQ != null) {
                Object runtime = mobileQQ.getClass().getMethod("peekAppRuntime").invoke(mobileQQ);
                if (runtime != null) {
                    String uin = (String) runtime.getClass().getMethod("getCurrentAccountUin").invoke(runtime);
                    if (uin != null && !uin.isEmpty()) return uin;
                }
            }
        } catch (Throwable ignored) {}

        try {
            Class<?> baseAppClz = Class.forName("com.tencent.common.app.BaseApplicationImpl");
            Object appImpl = baseAppClz.getMethod("getApplication").invoke(null);
            if (appImpl != null) {
                Object appRuntime = appImpl.getClass().getMethod("peekAppRuntime").invoke(appImpl);
                if (appRuntime != null) {
                    String uin = (String) appRuntime.getClass().getMethod("getCurrentAccountUin").invoke(appRuntime);
                    if (uin != null && !uin.isEmpty()) return uin;
                }
            }
        } catch (Throwable ignored) {}
        return "";
    }

    public static void sendMsg(String peerUin, String content, int chatType) {
        if (peerUin == null || content == null) return;
        Contact contact = makeContact(peerUin, chatType);
        sendMsg(contact, content);
    }

    public static void sendMsg(Contact contact, String content) {
        if (contact == null || content == null) return;
        IKernelMsgService service = getMsgService();
        if (service == null) {
            Log.e(TAG, "[MsgSender] 未找到 IKernelMsgService");
            return;
        }

        try {
            ArrayList<MsgElement> elements = buildElements(contact, content);
            if (elements.isEmpty()) return;

            long msgId = 0L;
            try {
                Method genMethod = service.getClass().getMethod("generateMsgUniqueId", int.class, long.class);
                msgId = (Long) genMethod.invoke(service, contact.getChatType(), System.currentTimeMillis());
            } catch (Throwable ignored) {}

            for (Method m : service.getClass().getMethods()) {
                if ("sendMsg".equals(m.getName()) && m.getParameterTypes().length >= 4) {
                    m.setAccessible(true);
                    if (m.getParameterTypes().length == 5) {
                        m.invoke(service, msgId, contact, elements, new HashMap(), null);
                    } else {
                        m.invoke(service, msgId, contact, elements, new HashMap());
                    }
                    Log.i(TAG, "[MsgSender] 消息发送已投递: " + contact.getPeerUid() + " -> " + content);
                    return;
                }
            }
        } catch (Throwable t) {
            Log.e(TAG, "[MsgSender] 发送消息异常: ", t);
        }
    }

    public static void recall(int chatType, String peerUin, long msgId) {
        recall(makeContact(peerUin, chatType), msgId);
    }

    public static void recall(Contact contact, long msgId) {
        IKernelMsgService service = getMsgService();
        if (service == null || contact == null) return;
        try {
            ArrayList<Long> ids = new ArrayList<>();
            ids.add(msgId);
            for (Method m : service.getClass().getMethods()) {
                if ("recallMsg".equals(m.getName()) && m.getParameterTypes().length >= 2) {
                    m.setAccessible(true);
                    if (m.getParameterTypes().length == 3) {
                        m.invoke(service, contact, ids, null);
                    } else {
                        m.invoke(service, contact, ids);
                    }
                    Log.i(TAG, "[MsgSender] 撤回指令提交: " + msgId);
                    return;
                }
            }
        } catch (Throwable t) {
            Log.e(TAG, "[MsgSender] 撤回异常: ", t);
        }
    }

    private static ArrayList<MsgElement> buildElements(Contact contact, String content) {
        ArrayList<MsgElement> elements = new ArrayList<>();
        Pattern pattern = Pattern.compile("\\[(atUin|pic)=([^\\]]+)\\]");
        Matcher matcher = pattern.matcher(content);
        int lastIndex = 0;

        while (matcher.find()) {
            if (matcher.start() > lastIndex) {
                String plain = content.substring(lastIndex, matcher.start());
                elements.add(createTextElement(plain));
            }
            String tag = matcher.group(1);
            String val = matcher.group(2);

            if ("atUin".equals(tag) && contact.getChatType() == 2) {
                elements.add(createAtElement(val));
            } else if ("pic".equals(tag)) {
                MsgElement picElem = createPicElement(val);
                if (picElem != null) elements.add(picElem);
            }
            lastIndex = matcher.end();
        }

        if (lastIndex < content.length()) {
            elements.add(createTextElement(content.substring(lastIndex)));
        }

        return elements;
    }

    private static MsgElement createTextElement(String text) {
        try {
            Class<?> qrouteClz = Class.forName("com.tencent.mobileqq.qroute.QRoute");
            Class<?> msgUtilClz = Class.forName("com.tencent.qqnt.msg.api.IMsgUtilApi");
            Object msgUtil = qrouteClz.getMethod("api", Class.class).invoke(null, msgUtilClz);
            if (msgUtil != null) {
                Method m = msgUtil.getClass().getMethod("createTextElement", String.class);
                return (MsgElement) m.invoke(msgUtil, text);
            }
        } catch (Throwable ignored) {}

        MsgElement elem = new MsgElement();
        elem.elementType = 1;
        elem.textElement = new TextElement();
        elem.textElement.content = text;
        return elem;
    }

    private static MsgElement createAtElement(String uin) {
        String uid = getUidFromUin(uin);
        int atType = "0".equals(uin) ? 1 : 2;
        String display = "0".equals(uin) ? "@全体成员" : ("@" + uin + " ");
        try {
            Class<?> qrouteClz = Class.forName("com.tencent.mobileqq.qroute.QRoute");
            Class<?> msgUtilClz = Class.forName("com.tencent.qqnt.msg.api.IMsgUtilApi");
            Object msgUtil = qrouteClz.getMethod("api", Class.class).invoke(null, msgUtilClz);
            if (msgUtil != null) {
                Method m = msgUtil.getClass().getMethod("createAtTextElement", String.class, String.class, int.class);
                return (MsgElement) m.invoke(msgUtil, display, uid, atType);
            }
        } catch (Throwable ignored) {}

        MsgElement elem = new MsgElement();
        elem.elementType = 1;
        elem.textElement = new TextElement();
        elem.textElement.content = display;
        elem.textElement.atType = atType;
        elem.textElement.atNtUid = uid;
        return elem;
    }

    private static MsgElement createPicElement(String path) {
        try {
            Class<?> qrouteClz = Class.forName("com.tencent.mobileqq.qroute.QRoute");
            Class<?> msgUtilClz = Class.forName("com.tencent.qqnt.msg.api.IMsgUtilApi");
            Object msgUtil = qrouteClz.getMethod("api", Class.class).invoke(null, msgUtilClz);
            if (msgUtil != null) {
                Method m = msgUtil.getClass().getMethod("createPicElement", String.class, boolean.class, int.class);
                return (MsgElement) m.invoke(msgUtil, path, true, 0);
            }
        } catch (Throwable ignored) {}
        return null;
    }

    public static Contact makeContact(String target, int chatType) {
        String peerUid = target;
        if ((chatType == 1 || chatType == 100) && !target.startsWith("u_")) {
            try {
                IRelationNTUinAndUidApi api = QRoute.api(IRelationNTUinAndUidApi.class);
                if (api != null) {
                    String u = api.getUidFromUin(target);
                    if (u != null && !u.isEmpty()) peerUid = u;
                }
            } catch (Throwable ignored) {}
        }
        return new Contact(chatType, peerUid, "");
    }

    public static String getUidFromUin(String uin) {
        try {
            IRelationNTUinAndUidApi api = QRoute.api(IRelationNTUinAndUidApi.class);
            if (api != null) {
                return api.getUidFromUin(uin);
            }
        } catch (Throwable ignored) {}
        return uin;
    }

    public static String getUinFromUid(String uid) {
        try {
            IRelationNTUinAndUidApi api = QRoute.api(IRelationNTUinAndUidApi.class);
            if (api != null) {
                return api.getUinFromUid(uid);
            }
        } catch (Throwable ignored) {}
        return uid;
    }
}
