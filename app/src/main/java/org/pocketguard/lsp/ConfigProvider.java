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
 * Device-protected, read-only IPC bridge from the module app to SystemUI.
 * Config writes happen only inside the module app and notify observers.
 */
public final class ConfigProvider extends ContentProvider {
    public static final String AUTHORITY = "org.pocketguard.lsp.config";
    public static final Uri URI = Uri.parse("content://" + AUTHORITY + "/settings");
    public static final String PREF_FILE = "pocketmode";
    public static final String KEY_ENABLED = "enabled";

    public static SharedPreferences preferences(Context c) {
        return c.createDeviceProtectedStorageContext()
                .getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE);
    }

    public static void notifyUpdated(Context c) {
        c.getContentResolver().notifyChange(URI, null);
    }

    @Override public boolean onCreate() { return true; }

    private void enforceReader() {
        int uid = Binder.getCallingUid();
        if (uid == Process.myUid()) return;
        PackageManager pm = getContext().getPackageManager();
        String[] packages = pm.getPackagesForUid(uid);
        if (packages != null) {
            for (String pkg : packages) {
                if ("com.android.systemui".equals(pkg)) return;
            }
        }
        throw new SecurityException("PocketMode config may only be read by SystemUI");
    }

    @Override public Bundle call(String method, String arg, Bundle extras) {
        enforceReader();
        if (!"get".equals(method)) throw new IllegalArgumentException("Unknown method");
        SharedPreferences prefs = preferences(getContext());
        Bundle out = new Bundle();
        // First-install default = ON. The user can turn it off from the launcher UI.
        out.putBoolean(KEY_ENABLED, prefs.getBoolean(KEY_ENABLED, true));
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
