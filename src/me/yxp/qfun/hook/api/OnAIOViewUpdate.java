package me.yxp.qfun.hook.api;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class OnAIOViewUpdate {
    public static final OnAIOViewUpdate INSTANCE = new OnAIOViewUpdate();
    private final List<AIOViewUpdateListener> listeners = new CopyOnWriteArrayList<>();

    public void addListener(AIOViewUpdateListener l) {
        if (l != null && !listeners.contains(l)) listeners.add(l);
    }
    public void removeListener(AIOViewUpdateListener l) {
        if (l != null) listeners.remove(l);
    }
}
