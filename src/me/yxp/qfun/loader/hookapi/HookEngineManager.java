package me.yxp.qfun.loader.hookapi;

public class HookEngineManager {
    public static final HookEngineManager INSTANCE = new HookEngineManager();

    public static class Engine {
        public String getFrameworkName() { return "QFun-Patcher"; }
        public int getApiLevel() { return 100; }
    }

    public Engine getEngine() {
        return new Engine();
    }
}
