package me.yxp.qfun.hook.api;

@FunctionalInterface
public interface ReceiveMsgListener {
    void onReceiveMsg(Object msgData);
}
