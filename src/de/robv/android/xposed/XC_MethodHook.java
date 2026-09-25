package de.robv.android.xposed;

public abstract class XC_MethodHook {
    public XC_MethodHook() {}
    public XC_MethodHook(int priority) {}

    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {}
    protected void afterHookedMethod(MethodHookParam param) throws Throwable {}

    public static class MethodHookParam {
        public Object thisObject;
        public Object[] args;
        private Object result = null;
        private Throwable throwable = null;
        public boolean returnEarly = false;

        public Object getResult() { return result; }
        public void setResult(Object result) {
            this.result = result;
            this.returnEarly = true;
        }
        public Throwable getThrowable() { return throwable; }
        public void setThrowable(Throwable throwable) {
            this.throwable = throwable;
            this.returnEarly = true;
        }
        public Object getResultOrThrowable() throws Throwable {
            if (throwable != null) throw throwable;
            return result;
        }
        public Object getThisObject() { return thisObject; }
        public Object[] getArgs() { return args; }
    }

    public static class Unhook {
        private final Object hook;
        public Unhook(Object hook) { this.hook = hook; }
        public void unhook() {}
    }
}
