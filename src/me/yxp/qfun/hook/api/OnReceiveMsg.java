package me.yxp.qfun.hook.api;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class OnReceiveMsg {
    public static final OnReceiveMsg INSTANCE = new OnReceiveMsg();
    private final List<ReceiveMsgListener> listeners = new CopyOnWriteArrayList<>();

    public void addListener(ReceiveMsgListener l) {
        if (l != null && !listeners.contains(l)) listeners.add(l);
    }
    public void removeListener(ReceiveMsgListener l) {
        if (l != null) listeners.remove(l);
    }
    public void dispatch(Object msgData) {
        for (ReceiveMsgListener l : listeners) {
            try { l.onReceiveMsg(msgData); } catch (Throwable ignored) {}
        }
    }
}
