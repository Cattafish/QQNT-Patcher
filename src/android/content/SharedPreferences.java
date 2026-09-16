package android.content;

import java.util.Map;
import java.util.Set;

public interface SharedPreferences {
    Map<String, ?> getAll();
    boolean getBoolean(String key, boolean defValue);
    String getString(String key, String defValue);
    int getInt(String key, int defValue);
    long getLong(String key, long defValue);
    float getFloat(String key, float defValue);
    Set<String> getStringSet(String key, Set<String> defValues);
    boolean contains(String key);

    Editor edit();

    interface Editor {
        Editor putBoolean(String key, boolean value);
        Editor putString(String key, String value);
        Editor putInt(String key, int value);
        Editor putLong(String key, long value);
        Editor putFloat(String key, float value);
        Editor putStringSet(String key, Set<String> values);
        Editor remove(String key);
        Editor clear();
        void apply();
        boolean commit();
    }
}