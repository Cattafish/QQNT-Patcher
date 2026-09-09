package android.content;

import android.net.Uri;

public class Intent {
    public static final String ACTION_VIEW = "android.intent.action.VIEW";
    public static final int FLAG_ACTIVITY_NEW_TASK = 268435456;

    public Intent() {}
    public Intent(String action) {}
    public Intent(String action, Uri uri) {}
    public Intent(Context packageContext, Class<?> cls) {}

    public Intent putExtra(String name, boolean value) { return this; }
    public Intent putExtra(String name, String value) { return this; }
    public Intent putExtra(String name, int value) { return this; }
    public Intent putExtra(String name, long value) { return this; }
    public boolean getBooleanExtra(String name, boolean defaultValue) { return defaultValue; }
    public String getStringExtra(String name) { return null; }
    public int getIntExtra(String name, int defaultValue) { return defaultValue; }
    public long getLongExtra(String name, long defaultValue) { return defaultValue; }
    public boolean hasExtra(String name) { return false; }
    public Intent addFlags(int flags) { return this; }
    public Intent setClassName(String packageName, String className) { return this; }
}
