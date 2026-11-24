package org.pocketguard.lsp;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.database.ContentObserver;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

final class GestureSettingsHook {
    private static final String TAG = "PocketModeSettings";
    private static final String PAGE = "com.android.settings.gestures.GestureSettings";
    private static final String KEY = "org.pocketguard.lsp.enabled";
    private static final String WATCH = KEY + ".observer";

    static void install(ClassLoader loader) {
        try {
            Class<?> page = XposedHelpers.findClass(PAGE, loader);
            hookLifecycle(page, "onResume", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (!page.isInstance(p.thisObject)) return;
                    try { showSwitch(p.thisObject, loader); }
                    catch (Throwable t) { Log.e(TAG, "Cannot add pocket switch", t); }
                }
            });
            hookLifecycle(page, "onPause", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (!page.isInstance(p.thisObject)) return;
                    ContentObserver observer = (ContentObserver)
                            XposedHelpers.removeAdditionalInstanceField(p.thisObject, WATCH);
                    if (observer != null) context(p.thisObject).getContentResolver()
                            .unregisterContentObserver(observer);
                }
            });
            Class<?> home = XposedHelpers.findClass(
                    "com.android.settings.homepage.SettingsHomepageActivity", loader);
            hookLifecycle(home, "onResume", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (!home.isInstance(p.thisObject)) return;
                    Activity a = (Activity) p.thisObject;
                    Intent intent = a.getIntent();
                    if (intent == null || !intent.getBooleanExtra(MainActivity.OPEN_GESTURES, false)) return;
                    intent.removeExtra(MainActivity.OPEN_GESTURES);
                    Bundle args = new Bundle();
                    args.putString(":settings:fragment_args_key", KEY);
                    args.putBoolean(MainActivity.OPEN_GESTURES, true);
                    Intent gestures = new Intent().setClassName(a, "com.android.settings.SubSettings")
                            .putExtra(":settings:show_fragment", PAGE)
                            .putExtra(":settings:show_fragment_args", args);
                    try {
                        a.startActivity(gestures);
                    } catch (RuntimeException e) { Log.e(TAG, "Cannot open gestures", e); }
                }
            });
        } catch (Throwable t) { Log.e(TAG, "Gestures integration unavailable", t); }
    }

    private static void hookLifecycle(Class<?> type, String name, XC_MethodHook hook)
            throws NoSuchMethodException {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            try {
                Method m = c.getDeclaredMethod(name);
                XposedBridge.hookMethod(m, hook);
                return;
            } catch (NoSuchMethodException ignored) { }
        }
        throw new NoSuchMethodException(name);
    }

    private static Context context(Object page) {
        return (Context) XposedHelpers.callMethod(page, "requireContext");
    }

    private static void showSwitch(Object page, ClassLoader loader) {
        Object screen = XposedHelpers.callMethod(page, "getPreferenceScreen");
        if (screen == null) return;
        Context ctx = context(page);
        Object row = XposedHelpers.callMethod(screen, "findPreference", KEY);
        if (row == null) {
            Class<?> type = XposedHelpers.findClass("androidx.preference.SwitchPreferenceCompat", loader);
            Context themed = (Context) XposedHelpers.callMethod(screen, "getContext");
            row = XposedHelpers.newInstance(type, themed);
            XposedHelpers.callMethod(row, "setKey", KEY);
            XposedHelpers.callMethod(row, "setTitle", "口袋模式");
            XposedHelpers.callMethod(row, "setSummary", "遮挡距离传感器时启用防误触保护");
            XposedHelpers.callMethod(row, "setPersistent", false);
            bindChange(ctx, loader, row, false);
            XposedHelpers.callMethod(screen, "addPreference", row);
        }
        String delayKey = KEY + ".cover_delay";
        Object slider = XposedHelpers.callMethod(screen, "findPreference", delayKey);
        if (slider == null) {
            Context themed = (Context) XposedHelpers.callMethod(screen, "getContext");
            slider = XposedHelpers.newInstance(XposedHelpers.findClass(
                    "androidx.preference.SeekBarPreference", loader), themed);
            XposedHelpers.callMethod(slider, "setKey", delayKey);
            XposedHelpers.callMethod(slider, "setTitle", "连续遮挡时间（秒）");
            XposedHelpers.callMethod(slider, "setSummary", "仅在亮屏且已解锁时生效");
            XposedHelpers.callMethod(slider, "setPersistent", false);
            XposedHelpers.callMethod(slider, "setMin", 1);
            XposedHelpers.callMethod(slider, "setMax", ConfigProvider.MAX_DELAY);
            XposedHelpers.callMethod(slider, "setSeekBarIncrement", 1);
            XposedHelpers.callMethod(slider, "setShowSeekBarValue", true);
            bindChange(ctx, loader, slider, true);
            XposedHelpers.callMethod(screen, "addPreference", slider);
        }
        final Object delaySlider = slider;
        final Object pref = row;
        if (XposedHelpers.getAdditionalInstanceField(page, WATCH) == null) {
            ContentObserver observer = new ContentObserver(new Handler(Looper.getMainLooper())) {
                @Override public void onChange(boolean selfChange) { refresh(ctx, pref, delaySlider); }
            };
            ctx.getContentResolver().registerContentObserver(ConfigProvider.URI, false, observer);
            XposedHelpers.setAdditionalInstanceField(page, WATCH, observer);
        }
        refresh(ctx, row, delaySlider);
        Bundle args = (Bundle) XposedHelpers.callMethod(page, "getArguments");
        if (args != null && args.getBoolean(MainActivity.OPEN_GESTURES)) {
            args.remove(MainActivity.OPEN_GESTURES);
            XposedHelpers.callMethod(page, "scrollToPreference", KEY);
        }
    }

    private static void bindChange(Context ctx, ClassLoader loader, Object row, boolean delay) {
        Class<?> listener = XposedHelpers.findClass(
                "androidx.preference.Preference$OnPreferenceChangeListener", loader);
        Object change = Proxy.newProxyInstance(loader, new Class<?>[]{listener}, (proxy, m, args) -> {
            if (m.getName().equals("onPreferenceChange")) {
                Bundle values = new Bundle();
                if (delay) values.putInt(ConfigProvider.KEY_DELAY, (int) args[1]);
                else values.putBoolean(ConfigProvider.KEY_ENABLED, (boolean) args[1]);
                return ctx.getContentResolver().call(ConfigProvider.URI,
                        delay ? "setDelay" : "set", null, values) != null;
            }
            if (m.getName().equals("hashCode")) return System.identityHashCode(proxy);
            if (m.getName().equals("equals")) return proxy == args[0];
            return "PocketMode preference listener";
        });
        XposedHelpers.callMethod(row, "setOnPreferenceChangeListener", change);
    }

    private static void refresh(Context ctx, Object row, Object slider) {
        try {
            Bundle b = ctx.getContentResolver().call(ConfigProvider.URI, "get", null, null);
            if (b == null) return;
            boolean enabled = b.getBoolean(ConfigProvider.KEY_ENABLED, true);
            XposedHelpers.callMethod(row, "setChecked", enabled);
            XposedHelpers.callMethod(slider, "setValue", b.getInt(ConfigProvider.KEY_DELAY, ConfigProvider.DEFAULT_DELAY));
            XposedHelpers.callMethod(slider, "setEnabled", enabled);
        } catch (RuntimeException e) { Log.e(TAG, "Cannot read pocket setting", e); }
    }
}
