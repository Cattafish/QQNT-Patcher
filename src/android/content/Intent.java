package android.content;
public class Intent {
    public static final int FLAG_ACTIVITY_NEW_TASK = 268435456;
    public Intent() {}
    public Intent(Context packageContext, Class<?> cls) {}
    public Intent putExtra(String name, boolean value) { return this; }
    public boolean getBooleanExtra(String name, boolean defaultValue) { return defaultValue; }
    public Intent addFlags(int flags) { return this; }
    public Intent setClassName(String packageName, String className) { return this; }
}
