package org.pocketguard.lsp;

import android.content.ContentResolver;
import android.content.Context;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.Looper;
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
    static final String READY_CHALLENGE = "pocketmode_power_hook_challenge";
    static final String READY_ACK = "pocketmode_power_hook_ack";
    static final String ACTIVE_HEARTBEAT = "pocketmode_active_heartbeat";
    static final String EXIT_REQUEST = "pocketmode_power_exit_request";
    private static final long MAX_HEARTBEAT_AGE_MS = 12500L;
    private static volatile boolean consumeVeryLongUntilKeyUp;
    private static ContentObserver challengeObserver;
    private static long lastAcknowledgedChallenge;

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
                            registerChallengeObserver(context);
                        }
                    });
            Log.i(TAG, "PhoneWindowManager POWER interception installed");
        } catch (Throwable t) {
            Log.e(TAG, "Failed to attach PhoneWindowManager hooks", t);
        }
    }

    /**
     * Responds only to a fresh request made by the current SystemUI controller.
     * This avoids the boot_count race: systemReady may run before BOOT_COUNT
     * increments, making a startup boot number look stale immediately.
     */
    private static synchronized void registerChallengeObserver(Context context) {
        if (challengeObserver != null) return;
        try {
            final ContentResolver resolver = context.getContentResolver();
            final Handler handler = new Handler(Looper.getMainLooper());
            challengeObserver = new ContentObserver(handler) {
                @Override public void onChange(boolean selfChange) {
                    answerChallenge(resolver);
                }
            };
            resolver.registerContentObserver(
                    Settings.Global.getUriFor(READY_CHALLENGE), false, challengeObserver);
            answerChallenge(resolver);
            Log.i(TAG, "Live power-escape challenge listener installed");
        } catch (Throwable t) {
            challengeObserver = null;
            Log.e(TAG, "Could not install power-escape challenge listener", t);
        }
    }

    private static void answerChallenge(ContentResolver resolver) {
        try {
            long challenge = Settings.Global.getLong(resolver, READY_CHALLENGE, 0);
            if (challenge == 0 || challenge == lastAcknowledgedChallenge) return;
            if (Settings.Global.putLong(resolver, READY_ACK, challenge)) {
                lastAcknowledgedChallenge = challenge;
                Log.i(TAG, "Power-escape challenge acknowledged");
            } else {
                Log.e(TAG, "Power-escape challenge acknowledgement failed");
            }
        } catch (Throwable t) {
            Log.e(TAG, "Cannot answer power-escape challenge", t);
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
