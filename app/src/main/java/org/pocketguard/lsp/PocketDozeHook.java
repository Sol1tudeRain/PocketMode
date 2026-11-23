/*
 * On-demand notification pulse proximity check for Android 17 SystemUI.
 * Adapted to the DozeTriggers / ProximityCheck APIs in crDroid 17 (Apache-2.0).
 * No sensor is registered by this hook while the phone is simply sleeping.
 */
package org.pocketguard.lsp;

import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

final class PocketDozeHook {
    private static final String TAG = "PocketModeDoze";
    private static final Uri CONFIG =
            Uri.parse("content://org.pocketguard.lsp.config/settings");
    private static final long PROX_TIMEOUT_MS = 500L;
    private static final long SAFETY_TIMEOUT_MS = 850L;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private PocketDozeHook() { }

    private static boolean isEnabled(Context ctx) {
        try {
            Bundle b = ctx.getContentResolver().call(CONFIG, "get", null, null);
            return b != null && b.getBoolean("enabled", true);
        } catch (Throwable t) {
            Log.w(TAG, "Cannot read PocketMode switch; keep stock notification pulse", t);
            return false; // fail open, never lose notifications due to module failures
        }
    }

    static void install(XC_LoadPackage.LoadPackageParam lp) {
        try {
            Class<?> triggers = XposedHelpers.findClassIfExists(
                    "com.android.systemui.doze.DozeTriggers", lp.classLoader);
            if (triggers == null) {
                Log.w(TAG, "DozeTriggers missing; AOD notification filtering unavailable");
                return;
            }

            if (XposedBridge.hookAllMethods(triggers, "onNotification",
                    new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    if (param.args.length != 1) return;
                    if (param.args[0] != null && !(param.args[0] instanceof Runnable))
                        return;
                    Context context;
                    Object check;
                    try {
                        context = (Context) XposedHelpers.getObjectField(
                                param.thisObject, "mContext");
                        if (context == null || !isEnabled(context)) return;
                        PowerManager pm = (PowerManager) context.getSystemService(
                                Context.POWER_SERVICE);
                        if (pm == null || pm.isInteractive()) return;
                        check = XposedHelpers.getObjectField(param.thisObject,
                                "mProxCheck");
                        if (check == null) return;
                    } catch (Throwable t) {
                        Log.w(TAG, "Doze setup unavailable, keep stock pulse", t);
                        return;
                    }

                    final Object originalThis = param.thisObject;
                    final Method originalMethod = (Method) param.method;
                    final Object[] args = param.args.clone();
                    final Runnable suppressed = (Runnable) param.args[0];
                    final PowerManager.WakeLock wakeLock;
                    try {
                        PowerManager pm = (PowerManager) context.getSystemService(
                                Context.POWER_SERVICE);
                        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                                "PocketMode:NotificationProximity");
                        wakeLock.acquire(1300L);
                    } catch (Throwable t) {
                        Log.w(TAG, "Cannot hold wake lock for one-shot prox, stock pulse", t);
                        return;
                    }

                    AtomicBoolean resolved = new AtomicBoolean(false);
                    final Runnable[] timeout = new Runnable[1];
                    Consumer<Boolean> callback = result -> MAIN.post(() -> {
                        if (!resolved.compareAndSet(false, true)) return;
                        MAIN.removeCallbacks(timeout[0]);
                        try {
                            if (Boolean.TRUE.equals(result)) {
                                Log.i(TAG, "NEAR: suppress AOD notification pulse");
                                if (suppressed != null) suppressed.run();
                            } else {
                                Log.i(TAG, "FAR/UNKNOWN: retain original AOD notification pulse");
                                XposedBridge.invokeOriginalMethod(originalMethod,
                                        originalThis, args);
                            }
                        } catch (Throwable t) {
                            Log.e(TAG, "Cannot dispatch Doze notification decision", t);
                        } finally {
                            if (wakeLock.isHeld()) wakeLock.release();
                        }
                    });
                    timeout[0] = () -> {
                        if (resolved.compareAndSet(false, true)) {
                            Log.w(TAG, "One-shot proximity check timed out; keep stock pulse");
                            try {
                                XposedBridge.invokeOriginalMethod(
                                        originalMethod, originalThis, args);
                            } catch (Throwable t) {
                                Log.e(TAG, "Cannot resume notification pulse", t);
                            } finally {
                                if (wakeLock.isHeld()) wakeLock.release();
                            }
                        }
                    };

                    try {
                        // Same SystemUI ProximityCheck used by the native Doze
                        // implementation; it temporarily registers the sensor
                        // for at most 500 ms and unregisters automatically.
                        XposedHelpers.callMethod(check, "check", PROX_TIMEOUT_MS, callback);
                        MAIN.postDelayed(timeout[0], SAFETY_TIMEOUT_MS);
                        param.setResult(null); // defer original until sensor result
                    } catch (Throwable t) {
                        resolved.set(true);
                        MAIN.removeCallbacks(timeout[0]);
                        if (wakeLock.isHeld()) wakeLock.release();
                        Log.w(TAG, "ProximityCheck unavailable; use stock AOD behavior", t);
                    }
                }
            }).isEmpty()) {
                Log.w(TAG, "No onNotification method; AOD filtering unavailable");
                return;
            }
            Log.i(TAG, "One-shot Doze notification proximity hook installed");
        } catch (Throwable t) {
            Log.e(TAG, "Failed to install Doze notification hook", t);
        }
    }
}
