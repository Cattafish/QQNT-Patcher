package android.content;
public interface SharedPreferences {
    boolean getBoolean(String key, boolean defValue);
    String getString(String key, String defValue);
    Editor edit();
    interface Editor {
        Editor putBoolean(String key, boolean value);
        Editor putString(String key, String value);
        void apply();
    }
}
