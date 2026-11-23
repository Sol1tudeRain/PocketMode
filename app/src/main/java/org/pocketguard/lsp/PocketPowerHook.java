package org.pocketguard.lsp;

import android.content.ContentResolver;
import android.content.Context;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.PowerManager;
import android.view.KeyEvent;
import android.provider.Settings;
import android.util.Log;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

import java.util.Set;
import java.lang.reflect.Method;

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
    /** Shared near flag, encoded with Settings.Global boot_count to reject stale boots. */
    static final String PROX_GUARD = "pocketmode_proximity_guard_boot";
    static final String WAKE_REQUEST = "pocketmode_power_prearm_request";
    private static final long PREARM_TIMEOUT_MS = 700L;
    static final long POWER_EXIT_HOLD_MS = 3000L;
    private static final String BOOT_COUNT = "boot_count";
    private static volatile boolean escapeHooksInstalled;
    private static volatile boolean wakeDeferred;
    private static Handler wakeHandler;
    private static Runnable wakeTimeout;
    private static Context deferredContext;
    private static ContentObserver overlayReadyObserver;
    private static volatile boolean held;
    private static volatile boolean completed;
    private static Context heldContext;
    private static Handler keyHandler;
    private static Runnable keyEscape;
    private static long keyDownAt;
    private static ContentObserver challengeObserver;
    private static volatile Context systemContext;
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
                            if (!held) return;
                            // Hardware emergency power hold is not modified.
                            try {
                                XposedHelpers.setBooleanField(p.thisObject, "mPowerKeyHandled", true);
                                p.setResult(null);
                                Log.i(TAG, "Power menu deferred to 3-second pocket escape");
                            } catch (Throwable t) {
                                Log.e(TAG, "Power long-press hook failed", t);
                            }
                        }
                    });
            if (longPress.isEmpty()) {
                Log.e(TAG, "powerLongPress missing; no wake gating or handshake");
                return;
            }
            // AOSP 16+ normally wakes from PhoneWindowManager.wakeUpFromWakeKey.
            // Some older ROMs use wakeUpFromPowerKey. Hook both when present,
            // rather than canceling the entire power-key input path.
            XC_MethodHook gate = new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    // This callback only handles explicit hardware POWER wake.
                    if (!isPowerWake(p.args)) return;
                    Context context = getContext(p.thisObject);
                    if (context == null || !isProximityGuarded(context)) return;
                    PowerManager pm = (PowerManager)
                            context.getSystemService(Context.POWER_SERVICE);
                    if (pm == null || pm.isInteractive() || completed) return;
                    if (isOverlayRecentlyActive(context)) {
                        Log.i(TAG, "POWER: pocket window already pre-armed; wake normally");
                        return; // The first visible frame will be the pocket UI.
                    }
                    // Overlay is not ready yet. Do NOT wake to the lock screen
                    // while waiting for asynchronous SystemUI window creation.
                    Class<?> resultType = p.method instanceof Method
                            ? ((Method)p.method).getReturnType() : Void.TYPE;
                    // Some OEM policy helpers return boolean rather than void.
                    // Returning null for boolean would crash unboxing in system_server.
                    if (resultType == Boolean.TYPE) p.setResult(false);
                    else if (resultType == Integer.TYPE) p.setResult(0);
                    else p.setResult(null);
                    deferWakeUntilOverlayReady(context);
                }
            };
            boolean wakeHookOk = false;
            for (String name : new String[]{"wakeUpFromWakeKey", "wakeUpFromPowerKey"}) {
                try {
                    Set<XC_MethodHook.Unhook> s =
                            XposedBridge.hookAllMethods(policy, name, gate);
                    wakeHookOk |= !s.isEmpty();
                    if (!s.isEmpty()) Log.i(TAG, "POWER wake gate: " + name);
                } catch (Throwable t) {
                    Log.w(TAG, "No policy POWER wake method " + name, t);
                }
            }
            // AOSP 16+ delegates to WindowWakeUpPolicy on many ROMs.
            try {
                Class<?> wakePolicy = XposedHelpers.findClassIfExists(
                        "com.android.server.policy.WindowWakeUpPolicy", lp.classLoader);
                if (wakePolicy != null) {
                    Set<XC_MethodHook.Unhook> s = XposedBridge.hookAllMethods(
                            wakePolicy, "wakeUpFromPowerKey", gate);
                    wakeHookOk |= !s.isEmpty();
                    if (!s.isEmpty()) Log.i(TAG, "POWER wake gate: WindowWakeUpPolicy");
                }
            } catch (Throwable t) {
                Log.w(TAG, "No WindowWakeUpPolicy POWER wake gate", t);
            }
            Set<XC_MethodHook.Unhook> down = XposedBridge.hookAllMethods(
                    policy, "interceptPowerKeyDown", new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam p) {
                            if (p.args.length == 0 || !(p.args[0] instanceof KeyEvent)) return;
                            KeyEvent event = (KeyEvent) p.args[0];
                            if (event.getKeyCode() == KeyEvent.KEYCODE_POWER
                                    && event.getRepeatCount() == 0) {
                                startHold(getContext(p.thisObject), event);
                            }
                        }
                    });
            Set<XC_MethodHook.Unhook> up = XposedBridge.hookAllMethods(
                    policy, "interceptPowerKeyUp", new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam p) {
                            cancelHold();
                        }
                    });
            // Deliberately leave powerVeryLongPress and hardware emergency
            // combinations unmodified. The 3-second exit happens earlier.
            escapeHooksInstalled = !down.isEmpty() && !up.isEmpty();
            if (!wakeHookOk) {
                Log.w(TAG, "No pre-wake gate found; pre-attached overlay can still prevent flash");
            }
            if (!escapeHooksInstalled) {
                Log.e(TAG, "Incomplete wake/hold hooks; protection handshake disabled");
                return;
            }
            XposedBridge.hookAllMethods(policy, "systemReady",
                    new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam p) {
                            Context context = getContext(p.thisObject);
                            if (context == null) {
                                Log.e(TAG, "No policy context; handshake unavailable");
                                return;
                            }
                            systemContext = context;
                            registerChallengeObserver(context);
                            registerOverlayReadyObserver(context);
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
            if (!escapeHooksInstalled) return;
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

    /**
     * Only explicit POWER wake requests are blocked; alarms, calls, boot and
     * other system wake reasons keep their original policy.
     */
    private static boolean isPowerWake(Object[] args) {
        if (args == null || args.length == 0) return false;
        if (args[0] instanceof KeyEvent) {
            KeyEvent e = (KeyEvent) args[0];
            return e.getKeyCode() == KeyEvent.KEYCODE_POWER
                    && e.getAction() == KeyEvent.ACTION_DOWN;
        }
        // wakeUpFromWakeKey(long eventTime, int keyCode, boolean isDown)
        if (args.length >= 3 && args[1] instanceof Integer
                && args[2] instanceof Boolean) {
            return ((Integer) args[1]) == KeyEvent.KEYCODE_POWER
                    && ((Boolean) args[2]);
        }
        // wakeUpFromPowerKey(long eventTime) in older PhoneWindowManager
        // or modern WindowWakeUpPolicy; this method ONLY handles POWER.
        return args.length == 1 && args[0] instanceof Long;
    }

    /**
     * Wait for the SystemUI surface to exist before waking the panel.
     * Fail open after 700ms if SystemUI/LSPosed is unhealthy.
     */
    private static synchronized void deferWakeUntilOverlayReady(Context c) {
        if (wakeDeferred) return;
        wakeDeferred = true;
        deferredContext = c;
        if (wakeHandler == null) wakeHandler = new Handler(Looper.getMainLooper());
        try {
            Settings.Global.putLong(c.getContentResolver(), WAKE_REQUEST,
                    SystemClock.elapsedRealtime());
        } catch (Throwable t) {
            Log.w(TAG, "Cannot request SystemUI pre-arm", t);
        }
        wakeTimeout = () -> finishDeferredWake("fail-open timeout");
        wakeHandler.postDelayed(wakeTimeout, PREARM_TIMEOUT_MS);
        Log.i(TAG, "POWER: waiting up to 700ms for pocket window");
    }

    private static synchronized void finishDeferredWake(String reason) {
        if (!wakeDeferred) return;
        Context c = deferredContext;
        wakeDeferred = false;
        deferredContext = null;
        if (wakeHandler != null && wakeTimeout != null)
            wakeHandler.removeCallbacks(wakeTimeout);
        wakeTimeout = null;
        if (c == null || completed) return;
        Log.i(TAG, "POWER: display wake " + reason);
        wakeDisplay(c);
    }

    private static void wakeDisplay(Context c) {
        try {
            PowerManager p = (PowerManager)
                    c.getSystemService(Context.POWER_SERVICE);
            if (p == null || p.isInteractive()) return;
            long now = SystemClock.uptimeMillis();
            try {
                XposedHelpers.callMethod(p, "wakeUp", now, 1,
                        "PocketMode:physical-power");
            } catch (Throwable newer) {
                XposedHelpers.callMethod(p, "wakeUp", now);
            }
        } catch (Throwable t) {
            Log.e(TAG, "Cannot resume deferred wake; next power press can retry", t);
        }
    }

    private static synchronized void registerOverlayReadyObserver(Context c) {
        if (overlayReadyObserver != null) return;
        if (wakeHandler == null) wakeHandler = new Handler(Looper.getMainLooper());
        try {
            overlayReadyObserver = new ContentObserver(wakeHandler) {
                @Override public void onChange(boolean selfChange) {
                    if (wakeDeferred && isOverlayRecentlyActive(c))
                        finishDeferredWake("pocket overlay ready");
                }
            };
            c.getContentResolver().registerContentObserver(
                    Settings.Global.getUriFor(ACTIVE_HEARTBEAT), false,
                    overlayReadyObserver);
            Log.i(TAG, "POWER wake waits for SystemUI pocket surface readiness");
        } catch (Throwable t) {
            overlayReadyObserver = null;
            Log.w(TAG, "Overlay readiness observer unavailable; timeout fallback", t);
        }
    }

    private static boolean isProximityGuarded(Context context) {
        if (!escapeHooksInstalled) return false;
        try {
            ContentResolver r = context.getContentResolver();
            int boot = Settings.Global.getInt(r, BOOT_COUNT, -1);
            if (boot < 0) return false; // fail open if boot marker is unavailable
            long state = Settings.Global.getLong(r, PROX_GUARD, 0);
            return (state & 1L) == 1L && (state >>> 1) == (long)boot;
        } catch (Throwable t) {
            Log.w(TAG, "Cannot read proximity guard; allow normal wake", t);
            return false;
        }
    }

    private static synchronized void startHold(Context context, KeyEvent event) {
        if (context == null || !escapeHooksInstalled || held || completed) return;
        if (!isOverlayRecentlyActive(context) && !isProximityGuarded(context)) return;
        held = true;
        completed = false;
        heldContext = context;
        keyDownAt = event.getDownTime();
        if (keyHandler == null) keyHandler = new Handler(Looper.getMainLooper());
        keyEscape = () -> {
            synchronized (PocketPowerHook.class) {
                if (!held || completed) return;
                long duration = SystemClock.uptimeMillis() - keyDownAt;
                if (duration < POWER_EXIT_HOLD_MS) return;
                Context c = heldContext;
                if (c == null || !requestExit(c)) return;
                completed = true;
                try {
                    ContentResolver r = c.getContentResolver();
                    int boot = Settings.Global.getInt(r, BOOT_COUNT, -1);
                    if (boot >= 0) Settings.Global.putLong(r, PROX_GUARD, (long) boot << 1);
                } catch (Throwable t) { Log.w(TAG, "Cannot clear wake guard", t); }
                if (wakeDeferred) {
                    wakeDeferred = false;
                    if (wakeHandler != null && wakeTimeout != null)
                        wakeHandler.removeCallbacks(wakeTimeout);
                    deferredContext = null;
                }
                wakeDisplay(c);
                Log.i(TAG, "Physical POWER held >=3s: bypass pocket until FAR");
            }
        };
        long remaining = Math.max(0L, POWER_EXIT_HOLD_MS
                - (SystemClock.uptimeMillis() - keyDownAt));
        keyHandler.postDelayed(keyEscape, remaining);
        Log.i(TAG, "Pocket POWER hold started; exit at 3000ms");
    }

    private static synchronized void cancelHold() {
        held = false;
        completed = false;
        if (keyHandler != null && keyEscape != null) keyHandler.removeCallbacks(keyEscape);
        keyEscape = null;
        heldContext = null;
    }

    private static Context getContext(Object policy) {
        try {
            Context local = (Context) XposedHelpers.getObjectField(policy, "mContext");
            if (local != null) return local;
        } catch (Throwable ignored) { }
        return systemContext; // WindowWakeUpPolicy sometimes stores no mContext.
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
