package me.yxp.qfun.hook.api;

import java.util.ArrayList;

@FunctionalInterface
public interface SendMsgListener {
    void onSendMsg(ArrayList<?> elements);
}
