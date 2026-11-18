package android.content;

/** Android's key-value store, as the shared code uses it. */
public interface SharedPreferences {
    String getString(String key, String defValue);
    int getInt(String key, int defValue);
    long getLong(String key, long defValue);
    float getFloat(String key, float defValue);
    boolean getBoolean(String key, boolean defValue);
    java.util.Set<String> getStringSet(String key, java.util.Set<String> defValues);
    boolean contains(String key);
    Editor edit();

    interface Editor {
        Editor putString(String key, String value);
        Editor putInt(String key, int value);
        Editor putLong(String key, long value);
        Editor putFloat(String key, float value);
        Editor putBoolean(String key, boolean value);
        Editor putStringSet(String key, java.util.Set<String> values);
        Editor remove(String key);
        Editor clear();
        boolean commit();
        void apply();
    }
}
