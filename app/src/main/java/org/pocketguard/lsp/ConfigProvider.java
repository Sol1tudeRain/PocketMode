package org.pocketguard.lsp;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Process;

/**
 * Device-protected configuration shared by the module, system Settings and the hooks.
 * Config writes happen only inside the module app and notify observers.
 */
public final class ConfigProvider extends ContentProvider {
    public static final String AUTHORITY = "org.pocketguard.lsp.config";
    public static final Uri URI = Uri.parse("content://" + AUTHORITY + "/settings");
    public static final String PREF_FILE = "pocketmode";
    public static final String KEY_ENABLED = "enabled";

    static final String KEY_DELAY = "unlocked_cover_seconds";
    static final int DEFAULT_DELAY = 1;
    static final int MAX_DELAY = 5;

    public static SharedPreferences preferences(Context c) {
        return c.createDeviceProtectedStorageContext()
                .getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE);
    }

    static void setEnabled(Context c, boolean enabled) {
        preferences(c).edit().putBoolean(KEY_ENABLED, enabled).apply();
        notifyUpdated(c);
    }

    public static void notifyUpdated(Context c) {
        c.getContentResolver().notifyChange(URI, null);
    }

    @Override public boolean onCreate() { return true; }

    private void enforceReader() {
        int uid = Binder.getCallingUid();
        if (uid == Process.myUid() || uid == Process.SYSTEM_UID) return;
        PackageManager pm = getContext().getPackageManager();
        String[] packages = pm.getPackagesForUid(uid);
        if (packages != null) {
            for (String pkg : packages) {
                if ("com.android.systemui".equals(pkg) || "com.android.settings".equals(pkg)) return;
            }
        }
        throw new SecurityException("PocketMode config may only be read by Android framework");
    }

    @Override public Bundle call(String method, String arg, Bundle extras) {
        if ("set".equals(method) || "setDelay".equals(method)) {
            try {
                int settingsUid = getContext().getPackageManager().getPackageUid("com.android.settings", 0);
                if (Binder.getCallingUid() != settingsUid)
                    throw new SecurityException("Only system Settings may change pocket protection");
            } catch (PackageManager.NameNotFoundException e) {
                throw new SecurityException("Settings unavailable", e);
            }
            boolean delay = "setDelay".equals(method);
            if (extras == null || !extras.containsKey(delay ? KEY_DELAY : KEY_ENABLED))
                throw new IllegalArgumentException("Missing preference value");
            long identity = Binder.clearCallingIdentity();
            try {
                if (delay) {
                    int seconds = extras.getInt(KEY_DELAY);
                    if (seconds < 1 || seconds > MAX_DELAY) throw new IllegalArgumentException("Invalid delay");
                    preferences(getContext()).edit().putInt(KEY_DELAY, seconds).apply();
                    notifyUpdated(getContext());
                } else {
                    setEnabled(getContext(), extras.getBoolean(KEY_ENABLED));
                }
            }
            finally { Binder.restoreCallingIdentity(identity); }
            return new Bundle();
        }
        enforceReader();
        if (!"get".equals(method)) throw new IllegalArgumentException("Unknown method");
        SharedPreferences prefs = preferences(getContext());
        Bundle out = new Bundle();
        // Preserve the enabled-by-default behavior for existing installations.
        out.putBoolean(KEY_ENABLED, prefs.getBoolean(KEY_ENABLED, true));
        out.putInt(KEY_DELAY, prefs.getInt(KEY_DELAY, DEFAULT_DELAY));
        return out;
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection,
            String[] selectionArgs, String sortOrder) {
        throw new UnsupportedOperationException("Use call(get)");
    }
    @Override public String getType(Uri uri) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException();
    }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException();
    }
    @Override public int update(Uri uri, ContentValues values, String selection,
            String[] selectionArgs) {
        throw new UnsupportedOperationException();
    }
}
