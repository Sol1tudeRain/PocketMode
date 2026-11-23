package org.pocketguard.lsp;

import android.content.Context;
import android.util.Log;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/** Hook only com.android.systemui, not system_server. */
public final class PocketHook implements IXposedHookLoadPackage {
    private static volatile PocketController controller;
    private static final String TAG = "PocketGuardLSP";

    @Override public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lp) {
        if (!"com.android.systemui".equals(lp.packageName) ||
            !"com.android.systemui".equals(lp.processName)) return;

        XC_MethodHook lifecycle = new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                if (!(p.thisObject instanceof Context)) return;
                try {
                    synchronized (PocketHook.class) {
                        if (controller == null) {
                            PocketController c = new PocketController((Context)p.thisObject);
                            c.init();
                            controller = c;
                            Log.i(TAG, "Attached to SystemUI");
                        }
                    }
                } catch (Throwable e) { Log.e(TAG, "Controller init failed", e); }
            }
        };
        for (String name : new String[] {
             "com.android.systemui.SystemUIApplication",
             "com.android.systemui.SystemUIService"
        }) {
            try {
                Class<?> c = XposedHelpers.findClassIfExists(name, lp.classLoader);
                if (c != null) XposedBridge.hookAllMethods(c, "onCreate", lifecycle);
            } catch (Throwable e) { Log.w(TAG, "Lifecycle hook unavailable", e); }
        }
        try {
            Class<?> c = XposedHelpers.findClassIfExists(
                "android.hardware.display.AmbientDisplayConfiguration", lp.classLoader);
            if (c != null) XposedBridge.hookAllMethods(c, "pulseOnNotificationEnabled",
                new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) {
                        PocketController c = controller;
                        if (c != null && c.suppressPulse()) p.setResult(false);
                    }
                });
        } catch (Throwable e) { Log.w(TAG, "Doze config hook failed", e); }
        try {
            Class<?> c = XposedHelpers.findClassIfExists(
                "com.android.systemui.doze.DozeTriggers", lp.classLoader);
            if (c != null) XposedBridge.hookAllMethods(c, "onNotification",
                new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) {
                        PocketController c = controller;
                        if (c == null || !c.suppressPulse()) return;
                        if (p.args.length > 0 && p.args[0] instanceof Runnable) {
                            try { ((Runnable)p.args[0]).run(); }
                            catch (Throwable e) { Log.w(TAG,"Pulse callback failed",e); }
                        }
                        p.setResult(null);
                    }
                });
        } catch (Throwable e) { Log.w(TAG, "Doze trigger hook failed", e); }
    }
}
