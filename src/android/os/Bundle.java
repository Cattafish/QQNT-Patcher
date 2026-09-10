package android.os;

public class Bundle {
    public Bundle() {}
    public boolean getBoolean(String key, boolean defaultValue) { return defaultValue; }
    public String getString(String key) { return null; }
    public String getString(String key, String defaultValue) { return defaultValue; }
    public int getInt(String key, int defaultValue) { return defaultValue; }
    public CharSequence getCharSequence(String key) { return null; }
    public CharSequence[] getCharSequenceArray(String key) { return null; }
}
