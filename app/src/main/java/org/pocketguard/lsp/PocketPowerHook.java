package org.pocketguard.lsp;

import android.content.ContentResolver;
import android.content.Context;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

import java.util.Set;

/**
 * system_server hook: long POWER dismisses the active PocketMode overlay
 * before PhoneWindowManager can display Global Actions.
 *
 * Short POWER / normal emergency power combos remain untouched.
 */
public final class PocketPowerHook {
    private static final String TAG = "PocketGuardPower";
    static final String READY_BOOT = "pocketmode_power_hook_ready_boot";
    static final String READY_ELAPSED = "pocketmode_power_hook_ready_elapsed";
    static final String ACTIVE_HEARTBEAT = "pocketmode_active_heartbeat";
    static final String EXIT_REQUEST = "pocketmode_power_exit_request";
    private static final long MAX_HEARTBEAT_AGE_MS = 12500L;
    private static volatile boolean consumeVeryLongUntilKeyUp;

    private PocketPowerHook() { }

    static void install(XC_LoadPackage.LoadPackageParam lp) {
        try {
            Class<?> policy = XposedHelpers.findClassIfExists(
                    "com.android.server.policy.PhoneWindowManager", lp.classLoader);
            if (policy == null) {
                Log.e(TAG, "PhoneWindowManager class missing");
                return;
            }
            Set<XC_MethodHook.Unhook> longPress = XposedBridge.hookAllMethods(
                    policy, "powerLongPress", new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam p) {
                            Context context = getContext(p.thisObject);
                            if (context == null || !isOverlayRecentlyActive(context)) return;
                            if (requestExit(context)) {
                                try {
                                    XposedHelpers.setBooleanField(
                                            p.thisObject, "mPowerKeyHandled", true);
                                    consumeVeryLongUntilKeyUp = true;
                                    p.setResult(null); // stop power menu / assistant
                                    Log.i(TAG, "Long POWER: dismissed pocket mode; global actions blocked");
                                } catch (Throwable t) {
                                    Log.e(TAG, "Could not mark power key handled", t);
                                }
                            }
                        }
                    });
            if (longPress.isEmpty()) {
                Log.e(TAG, "powerLongPress not found, refusing handshake");
                return;
            }
            // Prevent the very-long-power software menu from appearing during
            // the same physical key hold, even after the overlay has vanished.
            XposedBridge.hookAllMethods(policy, "powerVeryLongPress",
                    new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam p) {
                            Context context = getContext(p.thisObject);
                            boolean active = context != null && isOverlayRecentlyActive(context);
                            if (!consumeVeryLongUntilKeyUp && !active) return;
                            if (active && !consumeVeryLongUntilKeyUp) {
                                if (!requestExit(context)) return;
                                consumeVeryLongUntilKeyUp = true;
                            }
                            try {
                                XposedHelpers.setBooleanField(p.thisObject, "mPowerKeyHandled", true);
                                p.setResult(null);
                                Log.i(TAG, "Power menu suppressed for same physical key hold");
                            } catch (Throwable t) {
                                Log.e(TAG, "Failed to suppress very-long power menu", t);
                            }
                        }
                    });
            XposedBridge.hookAllMethods(policy, "interceptPowerKeyUp",
                    new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam p) {
                            consumeVeryLongUntilKeyUp = false;
                        }
                    });
            XposedBridge.hookAllMethods(policy, "systemReady",
                    new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam p) {
                            Context context = getContext(p.thisObject);
                            if (context == null) {
                                Log.e(TAG, "No policy context; handshake unavailable");
                                return;
                            }
                            try {
                                ContentResolver cr = context.getContentResolver();
                                int boot = Settings.Global.getInt(
                                        cr, Settings.Global.BOOT_COUNT, -1);
                                long now = SystemClock.elapsedRealtime();
                                boolean ok = Settings.Global.putInt(cr, READY_BOOT, boot)
                                        && Settings.Global.putLong(cr, READY_ELAPSED, now);
                                Log.i(TAG, "Power escape ready=" + ok +
                                        " boot=" + boot + " elapsed=" + now);
                            } catch (Throwable t) {
                                Log.e(TAG, "Power escape handshake failed", t);
                            }
                        }
                    });
            Log.i(TAG, "PhoneWindowManager POWER interception installed");
        } catch (Throwable t) {
            Log.e(TAG, "Failed to attach PhoneWindowManager hooks", t);
        }
    }

    private static boolean requestExit(Context context) {
        try {
            long stamp = SystemClock.elapsedRealtime();
            return Settings.Global.putLong(
                    context.getContentResolver(), EXIT_REQUEST, stamp);
        } catch (Throwable t) {
            Log.e(TAG, "Power escape IPC failed; preserve original power behavior", t);
            return false;
        }
    }

    private static Context getContext(Object policy) {
        try {
            return (Context) XposedHelpers.getObjectField(policy, "mContext");
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean isOverlayRecentlyActive(Context context) {
        try {
            long now = SystemClock.elapsedRealtime();
            long heartbeat = Settings.Global.getLong(context.getContentResolver(),
                    ACTIVE_HEARTBEAT, 0);
            return heartbeat > 0 && heartbeat <= now &&
                    now - heartbeat < MAX_HEARTBEAT_AGE_MS;
        } catch (Throwable t) {
            return false;
        }
    }
}
