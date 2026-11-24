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
        installSliderBinding(loader);
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

    private static void installSliderBinding(ClassLoader loader) {
        try {
            Class<?> holder = XposedHelpers.findClass("androidx.preference.PreferenceViewHolder", loader);
            XposedHelpers.findAndHookMethod("androidx.preference.Preference", loader,
                    "onBindViewHolder", holder, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    android.view.View item = (android.view.View) XposedHelpers.getObjectField(p.args[0], "itemView");
                    boolean pocket = KEY.equals(XposedHelpers.callMethod(p.thisObject, "getKey"));
                    PocketDelaySlider existing = item.findViewWithTag(PocketDelaySlider.TAG);
                    if (!pocket) {
                        if (existing != null) existing.detach();
                        return;
                    }
                    android.widget.TextView summary = item.findViewById(android.R.id.summary);
                    if (summary == null) return;
                    PocketDelaySlider slider = existing != null ? existing : PocketDelaySlider.attach(summary);
                    Bundle extras = (Bundle) XposedHelpers.callMethod(p.thisObject, "getExtras");
                    slider.bind((boolean) XposedHelpers.callMethod(p.thisObject, "isChecked"),
                            extras.getInt(ConfigProvider.KEY_DELAY, ConfigProvider.DEFAULT_DELAY));
                }
            });
        } catch (Throwable e) { Log.e(TAG, "Pocket slider binding unavailable", e); }
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
            bindChange(ctx, loader, row);
            XposedHelpers.callMethod(screen, "addPreference", row);
        }
        final Object pref = row;
        if (XposedHelpers.getAdditionalInstanceField(page, WATCH) == null) {
            ContentObserver observer = new ContentObserver(new Handler(Looper.getMainLooper())) {
                @Override public void onChange(boolean selfChange) { refresh(ctx, pref); }
            };
            ctx.getContentResolver().registerContentObserver(ConfigProvider.URI, false, observer);
            XposedHelpers.setAdditionalInstanceField(page, WATCH, observer);
        }
        refresh(ctx, row);
        Bundle args = (Bundle) XposedHelpers.callMethod(page, "getArguments");
        if (args != null && args.getBoolean(MainActivity.OPEN_GESTURES)) {
            args.remove(MainActivity.OPEN_GESTURES);
            XposedHelpers.callMethod(page, "scrollToPreference", KEY);
        }
    }

    private static void bindChange(Context ctx, ClassLoader loader, Object row) {
        Class<?> listener = XposedHelpers.findClass(
                "androidx.preference.Preference$OnPreferenceChangeListener", loader);
        Object change = Proxy.newProxyInstance(loader, new Class<?>[]{listener}, (proxy, m, args) -> {
            if (m.getName().equals("onPreferenceChange")) {
                Bundle values = new Bundle();
                values.putBoolean(ConfigProvider.KEY_ENABLED, (boolean) args[1]);
                return ctx.getContentResolver().call(ConfigProvider.URI,
                        "set", null, values) != null;
            }
            if (m.getName().equals("hashCode")) return System.identityHashCode(proxy);
            if (m.getName().equals("equals")) return proxy == args[0];
            return "PocketMode preference listener";
        });
        XposedHelpers.callMethod(row, "setOnPreferenceChangeListener", change);
    }

    private static void refresh(Context ctx, Object row) {
        try {
            Bundle b = ctx.getContentResolver().call(ConfigProvider.URI, "get", null, null);
            if (b == null) return;
            boolean enabled = b.getBoolean(ConfigProvider.KEY_ENABLED, true);
            XposedHelpers.callMethod(row, "setChecked", enabled);
            int seconds = b.getInt(ConfigProvider.KEY_DELAY, ConfigProvider.DEFAULT_DELAY);
            Bundle extras = (Bundle) XposedHelpers.callMethod(row, "getExtras");
            extras.putInt(ConfigProvider.KEY_DELAY, seconds);
            XposedHelpers.callMethod(row, "setSummary", PocketDelaySlider.label(seconds));
        } catch (RuntimeException e) { Log.e(TAG, "Cannot read pocket setting", e); }
    }
}
