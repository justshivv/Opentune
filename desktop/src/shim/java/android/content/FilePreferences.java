package android.content;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

/** [SharedPreferences] kept in a properties file, written whole on every change. */
final class FilePreferences implements SharedPreferences {
    private final File file;
    private final Properties values = new Properties();

    FilePreferences(File file) {
        this.file = file;
        if (file.exists()) {
            try (InputStream in = new FileInputStream(file)) {
                values.load(in);
            } catch (IOException ignored) {
                // An unreadable file reads as empty, as a fresh install would.
            }
        }
    }

    private synchronized String raw(String key) {
        return values.getProperty(key);
    }

    @Override public String getString(String key, String defValue) {
        String v = raw(key);
        return v != null ? v : defValue;
    }

    @Override public int getInt(String key, int defValue) {
        try { String v = raw(key); return v != null ? Integer.parseInt(v) : defValue; } catch (NumberFormatException e) { return defValue; }
    }

    @Override public long getLong(String key, long defValue) {
        try { String v = raw(key); return v != null ? Long.parseLong(v) : defValue; } catch (NumberFormatException e) { return defValue; }
    }

    @Override public float getFloat(String key, float defValue) {
        try { String v = raw(key); return v != null ? Float.parseFloat(v) : defValue; } catch (NumberFormatException e) { return defValue; }
    }

    @Override public boolean getBoolean(String key, boolean defValue) {
        String v = raw(key);
        return v != null ? Boolean.parseBoolean(v) : defValue;
    }

    /** A set is kept as one value, its items joined by the unit separator, which no id holds. */
    private static final String JOIN = "\u001f";

    @Override public java.util.Set<String> getStringSet(String key, java.util.Set<String> defValues) {
        String v = raw(key);
        if (v == null) return defValues;
        java.util.Set<String> out = new java.util.LinkedHashSet<>();
        if (!v.isEmpty()) out.addAll(java.util.Arrays.asList(v.split(JOIN, -1)));
        return out;
    }

    @Override public synchronized boolean contains(String key) {
        return values.containsKey(key);
    }

    @Override public Editor edit() {
        return new Editor() {
            private final Map<String, String> puts = new HashMap<>();
            private final java.util.Set<String> removes = new java.util.HashSet<>();
            private boolean clear;

            @Override public Editor putString(String key, String value) {
                if (value == null) return remove(key);
                puts.put(key, value);
                removes.remove(key);
                return this;
            }
            @Override public Editor putInt(String key, int value) { return putString(key, Integer.toString(value)); }
            @Override public Editor putLong(String key, long value) { return putString(key, Long.toString(value)); }
            @Override public Editor putFloat(String key, float value) { return putString(key, Float.toString(value)); }
            @Override public Editor putBoolean(String key, boolean value) { return putString(key, Boolean.toString(value)); }
            @Override public Editor putStringSet(String key, java.util.Set<String> set) {
                return set == null ? remove(key) : putString(key, String.join(JOIN, set));
            }
            @Override public Editor remove(String key) { removes.add(key); puts.remove(key); return this; }
            @Override public Editor clear() { clear = true; return this; }

            @Override public boolean commit() {
                synchronized (FilePreferences.this) {
                    if (clear) values.clear();
                    for (String k : removes) values.remove(k);
                    values.putAll(puts);
                    return save();
                }
            }

            @Override public void apply() { commit(); }
        };
    }

    private boolean save() {
        file.getParentFile().mkdirs();
        File tmp = new File(file.getParentFile(), file.getName() + ".tmp");
        try (OutputStream out = new FileOutputStream(tmp)) {
            values.store(out, null);
        } catch (IOException e) {
            return false;
        }
        if (!tmp.renameTo(file)) {
            file.delete();
            return tmp.renameTo(file);
        }
        return true;
    }
}
