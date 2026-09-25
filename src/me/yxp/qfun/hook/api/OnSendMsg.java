package me.yxp.qfun.hook.api;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class OnSendMsg {
    public static final OnSendMsg INSTANCE = new OnSendMsg();
    private final List<SendMsgListener> listeners = new CopyOnWriteArrayList<>();

    public void addListener(SendMsgListener listener) {
        if (listener != null && !listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    public void removeListener(SendMsgListener listener) {
        if (listener != null) {
            listeners.remove(listener);
        }
    }

    public void dispatch(ArrayList<?> elements) {
        for (SendMsgListener l : listeners) {
            try {
                l.onSendMsg(elements);
            } catch (Throwable ignored) {}
        }
    }
}
