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
import android.os.Handler;
import android.os.PowerManager;
import android.view.Display;
import android.view.KeyEvent;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public final class PocketPowerHook {
    private static final String TAG = "PocketModePower";
    private static volatile CrPocketController controller;
    private static volatile boolean escapeInstalled;
    static final int HOLD_SECONDS = 3;
    private static long downTime = -1;
    private static boolean consumed;
    private static Handler handler;
    private static Runnable escape;

    private static synchronized void beginHold(Object policy, KeyEvent event) {
        if (event.getRepeatCount() != 0 || handler == null) return;
        endHold();
        downTime = event.getDownTime();
        final long press = downTime;
        escape = () -> {
            synchronized (PocketPowerHook.class) {
                CrPocketController c = controller;
                if (press != downTime || c == null || !c.isOverlayShowing()) return;
                XposedHelpers.setBooleanField(policy, "mPowerKeyHandled", true);
                consumed = true;
                c.exitOnLongPower();
            }
        };
        handler.postAtTime(escape, press + HOLD_SECONDS * 1000L);
    }

    private static synchronized void endHold() {
        if (escape != null) handler.removeCallbacks(escape);
        escape = null;
        downTime = -1;
        consumed = false;
    }

    private static synchronized boolean suppressMenu() {
        return consumed || (downTime >= 0 && controller != null && controller.isOverlayShowing());
    }

    private PocketPowerHook() { }

    static boolean isEscapeInstalled() { return escapeInstalled; }

    private static void installTouchTimeoutHook(ClassLoader loader) {
        try {
            Class<?> service = XposedHelpers.findClass(
                    "com.android.server.power.PowerManagerService", loader);
            Class<?> group = XposedHelpers.findClass("com.android.server.power.PowerGroup", loader);
            Method groupId = XposedHelpers.findMethodExact(group, "getGroupId");
            // PowerManager.USER_ACTIVITY_EVENT_TOUCH and Display.DEFAULT_DISPLAY_GROUP
            // are hidden SDK constants. The service updates the idle deadline after this point.
            final int touch = 2;
            final int defaultGroup = 0;
            XposedHelpers.findAndHookMethod(service, "userActivityNoUpdateLocked", group,
                    long.class, int.class, int.class, int.class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) throws Throwable {
                    CrPocketController c = controller;
                    if (c == null || !c.isOverlayShowing() || (int) p.args[2] != touch) return;
                    if ((int) groupId.invoke(p.args[0]) == defaultGroup) p.setResult(false);
                }
            });
            Log.i(TAG, "Pocket touch timeout hook installed");
        } catch (Throwable t) {
            Log.w(TAG, "Touch timeout hook unavailable; using pocket window input flag", t);
        }
    }

    static void install(XC_LoadPackage.LoadPackageParam lp) {
        installTouchTimeoutHook(lp.classLoader);
        try {
            Class<?> policy = XposedHelpers.findClassIfExists(
                    "com.android.server.policy.PhoneWindowManager", lp.classLoader);
            if (policy == null) {
                Log.e(TAG, "PhoneWindowManager missing; feature disabled");
                return;
            }
            XC_MethodHook menu = new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (suppressMenu()) {
                        XposedHelpers.setBooleanField(p.thisObject, "mPowerKeyHandled", true);
                        p.setResult(null);
                    }
                }
            };
            if (XposedBridge.hookAllMethods(policy, "powerLongPress", menu).isEmpty()) return;
            XposedBridge.hookAllMethods(policy, "powerVeryLongPress", menu);
            if (XposedBridge.hookAllMethods(policy, "interceptPowerKeyDown", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (p.args.length > 0 && p.args[0] instanceof KeyEvent)
                        beginHold(p.thisObject, (KeyEvent) p.args[0]);
                }
            }).isEmpty()) return;
            if (XposedBridge.hookAllMethods(policy, "interceptPowerKeyUp", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) { endHold(); }
            }).isEmpty()) return;

            // Hook the final primitive overload so both key-down and ROMs
            // that wake on key-up pass through the same check exactly once.
            Method wakeMethod = null;
            for (Method method : policy.getDeclaredMethods()) {
                if (!method.getName().equals("wakeUpFromWakeKey")
                        || method.getReturnType() != void.class) continue;
                Class<?>[] args = method.getParameterTypes();
                boolean legacy = java.util.Arrays.equals(args,
                        new Class<?>[]{long.class, int.class, boolean.class});
                boolean modern = java.util.Arrays.equals(args,
                        new Class<?>[]{int.class, long.class, int.class, boolean.class, int.class})
                        || java.util.Arrays.equals(args, new Class<?>[]{int.class, long.class,
                                int.class, boolean.class, int.class, boolean.class});
                if ((modern || legacy) && (wakeMethod == null
                        || args.length > wakeMethod.getParameterCount())) wakeMethod = method;
            }
            if (wakeMethod == null) {
                Log.e(TAG, "Unsupported wakeUpFromWakeKey signature");
                return;
            }
            final int keyIndex = wakeMethod.getParameterCount() == 3 ? 1 : 2;
            XposedBridge.hookMethod(wakeMethod, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    CrPocketController c = controller;
                    if (c == null || !c.canCheckPowerWake()
                            || (int) p.args[keyIndex] != KeyEvent.KEYCODE_POWER) return;
                    Context ctx = (Context) XposedHelpers.getObjectField(p.thisObject, "mContext");
                    if (ctx.getSystemService(PowerManager.class).isInteractive()) return;
                    Object policyObject = p.thisObject;
                    Object[] args = p.args.clone();
                    Method method = (Method) p.method;
                    c.preparePowerWake(() -> handler.post(() -> {
                        try {
                            XposedBridge.invokeOriginalMethod(method, policyObject, args);
                        } catch (Throwable t) {
                            Log.e(TAG, "Could not resume power wake", t);
                        }
                    }));
                    p.setResult(null);
                }
            });

            Class<?> listenerType = XposedHelpers.findClass(
                    "com.android.server.policy.WindowManagerPolicy$ScreenOnListener", lp.classLoader);
            XposedHelpers.findAndHookMethod(policy, "screenTurningOn", int.class, listenerType,
                    new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    CrPocketController c = controller;
                    Object original = p.args[1];
                    if ((int) p.args[0] != Display.DEFAULT_DISPLAY || c == null
                            || !c.canCheckPowerWake() || original == null) return;
                    boolean manual = c.isManualWake();
                    Context ctx = (Context) XposedHelpers.getObjectField(p.thisObject, "mContext");
                    if (!manual && ctx.getSystemService(PowerManager.class).isInteractive()) return;
                    p.args[1] = Proxy.newProxyInstance(listenerType.getClassLoader(),
                            new Class<?>[]{listenerType}, (proxy, method, args) -> {
                                if (!method.getName().equals("onScreenOn"))
                                    return method.invoke(original, args);
                                Runnable unblock = () -> handler.post(() ->
                                        XposedHelpers.callMethod(original, "onScreenOn"));
                                if (manual) c.whenScreenReady(unblock);
                                else c.whenAmbientReady(unblock);
                                return null;
                            });
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
                                handler = (Handler) XposedHelpers.getObjectField(param.thisObject, "mHandler");
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
