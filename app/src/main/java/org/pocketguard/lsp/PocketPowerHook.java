/*
 * Android 17 system_server policy integration, adapted from crDroid's
 * PhoneWindowManager pocket long-POWER behavior (Apache-2.0).
 *
 * Unlike the previous versions, pocket state and power interception
 * are in the SAME process. No Settings.Global heartbeats or challenge IPC.
 */
package org.pocketguard.lsp;

import android.content.Context;
import android.util.Log;

import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public final class PocketPowerHook {
    private static final String TAG = "PocketModePower";
    private static volatile CrPocketController controller;
    private static volatile boolean escapeInstalled;
    private static volatile boolean consumeVeryLongUntilKeyUp;

    private PocketPowerHook() { }

    static boolean isEscapeInstalled() { return escapeInstalled; }

    static void install(XC_LoadPackage.LoadPackageParam lp) {
        try {
            Class<?> policy = XposedHelpers.findClassIfExists(
                    "com.android.server.policy.PhoneWindowManager", lp.classLoader);
            if (policy == null) {
                Log.e(TAG, "PhoneWindowManager missing; feature disabled");
                return;
            }
            Set<XC_MethodHook.Unhook> longHooks = XposedBridge.hookAllMethods(
                    policy, "powerLongPress", new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam param) {
                            CrPocketController c = controller;
                            if (c == null || !c.isOverlayShowing()) return;
                            try {
                                XposedHelpers.setBooleanField(
                                        param.thisObject, "mPowerKeyHandled", true);
                                consumeVeryLongUntilKeyUp = true;
                                c.exitOnLongPower();
                                param.setResult(null);
                                Log.i(TAG, "Long POWER dismissed pocket overlay (no power menu)");
                            } catch (Throwable t) {
                                Log.e(TAG, "Power key override failed; retain stock behavior", t);
                            }
                        }
                    });
            if (longHooks.isEmpty()) {
                Log.e(TAG, "powerLongPress not present; refuse to enable pocket lock");
                return;
            }
            Set<XC_MethodHook.Unhook> veryLongHooks = XposedBridge.hookAllMethods(
                    policy, "powerVeryLongPress", new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam param) {
                            CrPocketController c = controller;
                            if (!consumeVeryLongUntilKeyUp &&
                                    (c == null || !c.isOverlayShowing())) return;
                            try {
                                XposedHelpers.setBooleanField(
                                        param.thisObject, "mPowerKeyHandled", true);
                                if (!consumeVeryLongUntilKeyUp && c != null)
                                    c.exitOnLongPower();
                                consumeVeryLongUntilKeyUp = true;
                                param.setResult(null);
                            } catch (Throwable t) {
                                Log.e(TAG, "Very-long POWER override failed", t);
                            }
                        }
                    });
            if (veryLongHooks.isEmpty()) {
                Log.e(TAG, "Cannot suppress very-long software power menu");
                return;
            }
            XposedBridge.hookAllMethods(policy, "interceptPowerKeyUp",
                    new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            consumeVeryLongUntilKeyUp = false;
                        }
                    });
            escapeInstalled = true;
            Set<XC_MethodHook.Unhook> readyHooks = XposedBridge.hookAllMethods(
                    policy, "systemReady", new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            if (!escapeInstalled || controller != null) return;
                            try {
                                Context context = (Context) XposedHelpers.getObjectField(
                                        param.thisObject, "mContext");
                                if (context == null) return;
                                CrPocketController instance = new CrPocketController(context);
                                instance.init();
                                controller = instance;
                                Log.i(TAG, "Framework-owned pocket controller started");
                            } catch (Throwable t) {
                                Log.e(TAG, "Pocket controller initialization failed", t);
                            }
                        }
                    });
            if (readyHooks.isEmpty()) {
                escapeInstalled = false;
                Log.e(TAG, "No framework readiness hook; feature disabled");
            }
            Log.i(TAG, "crDroid-style power-key hook installed=" + escapeInstalled);
        } catch (Throwable t) {
            escapeInstalled = false;
            Log.e(TAG, "Framework policy hook failed; feature disabled", t);
        }
    }
}
