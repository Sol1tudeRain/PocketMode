package org.pocketguard.lsp;

import android.os.IBinder;
import android.util.Log;
import android.view.Display;

import java.lang.reflect.Method;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/** Mask only the dream's display override. Leave the device wakefulness policy intact. */
final class PocketDreamHook {
    private static final String TAG = "PocketModeDream";
    // DreamManagerService is a singleton. All session state is guarded by its mLock.
    private static Object token;
    private static Object[] latest;
    private static Method latestMethod;
    private static Object generation;
    private static Boolean covered;

    static void install(ClassLoader loader) {
        try {
            Class<?> service = XposedHelpers.findClass("com.android.server.dreams.DreamManagerService", loader);
            XposedHelpers.findAndHookMethod(service, "stopDozingInternal", IBinder.class,
                    new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    synchronized (XposedHelpers.getObjectField(p.thisObject, "mLock")) {
                        if (token == p.args[0]) clear();
                    }
                }
            });
            int hooks = 0;
            for (Method method : service.getDeclaredMethods()) {
                Class<?>[] types = method.getParameterTypes();
                if (!method.getName().equals("startDozingInternal") || types.length < 2
                        || types[0] != IBinder.class || types[1] != int.class
                        || method.getReturnType() != void.class) continue;
                XposedBridge.hookMethod(method, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) throws Throwable {
                        Object owner = p.thisObject;
                        Object lock = XposedHelpers.getObjectField(owner, "mLock");
                        synchronized (lock) {
                            Object record = XposedHelpers.getObjectField(owner, "mCurrentDream");
                            if (record == null || XposedHelpers.getObjectField(record, "token") != p.args[0]) return;
                            CrPocketController c = PocketPowerHook.controller();
                            int state = (int) p.args[1];
                            boolean lit = state == Display.STATE_ON || state == Display.STATE_DOZE
                                    || state == Display.STATE_DOZE_SUSPEND;
                            if (!lit || c == null || !c.canCheckPowerWake() || c.isManualWake()) {
                                clear();
                                return;
                            }
                            boolean first = token != p.args[0] || latest == null;
                            latest = p.args.clone();
                            latestMethod = method;
                            token = latest[0];
                            if (first) {
                                covered = null;
                                final Object session = new Object();
                                generation = session;
                                c.setDreamProbe(near -> {
                                    synchronized (lock) {
                                        if (session != generation || near.equals(covered)) return;
                                        covered = near;
                                        Log.i(TAG, near ? "Covered: hold dream display OFF" : "Clear: allow dream display");
                                        try {
                                            apply(owner);
                                        } catch (Throwable e) {
                                            Log.e(TAG, "Could not apply dream display decision", e);
                                        }
                                    }
                                });
                                Log.i(TAG, "Checking proximity before dream display state=" + state);
                            }
                            apply(owner);
                            p.setResult(null);
                        }
                    }
                });
                hooks++;
            }
            Log.i(TAG, "Dream display hooks installed=" + hooks);
        } catch (Throwable e) {
            Log.e(TAG, "Dream display hook unavailable", e);
        }
    }

    private static void apply(Object owner) throws Throwable {
        Object[] args = latest.clone();
        if (!Boolean.FALSE.equals(covered)) args[1] = Display.STATE_OFF;
        XposedBridge.invokeOriginalMethod(latestMethod, owner, args);
    }

    private static void clear() {
        if (latest == null) return;
        token = null;
        latest = null;
        latestMethod = null;
        generation = null;
        covered = null;
        CrPocketController c = PocketPowerHook.controller();
        if (c != null) c.setDreamProbe(null);
    }
}
