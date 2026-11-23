package org.pocketguard.lsp;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

final class PocketDozeHook implements SensorEventListener {
    private static final String TAG = "PocketModeDoze";
    private static final long TIMEOUT_MS = 700;
    private static PocketDozeHook active; // SystemUI main thread only
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final SensorManager sensors;
    private final PowerManager.WakeLock cpu;
    private final Sensor raw;
    private final Sensor standard;
    private final List<XC_MethodHook.MethodHookParam> requests = new ArrayList<>();
    private final long since = SystemClock.elapsedRealtimeNanos();
    private boolean samplingRaw;
    private Boolean standardNear;
    private final Runnable timeout = () -> finish(Boolean.TRUE.equals(standardNear), "timeout");

    private PocketDozeHook(Context context) {
        sensors = context.getSystemService(SensorManager.class);
        raw = CrPocketController.findRaw(sensors);
        standard = sensors.getDefaultSensor(Sensor.TYPE_PROXIMITY);
        cpu = context.getSystemService(PowerManager.class).newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK, "PocketMode:notification-check");
    }

    static void install(ClassLoader loader) {
        try {
            XposedHelpers.findAndHookMethod("com.android.systemui.doze.DozeTriggers", loader,
                    "onNotification", Runnable.class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (Looper.myLooper() != Looper.getMainLooper()) return;
                    try {
                        Context c = (Context) XposedHelpers.getObjectField(p.thisObject, "mContext");
                        Bundle config = c.getContentResolver().call(ConfigProvider.URI, "get", null, null);
                        if (config == null || !config.getBoolean(ConfigProvider.KEY_ENABLED, true)) return;
                        if (active == null) {
                            PocketDozeHook probe = new PocketDozeHook(c);
                            active = probe;
                            if (!probe.start()) return;
                        }
                        active.requests.add(p);
                        p.setResult(null);
                    } catch (RuntimeException e) {
                        Log.w(TAG, "Could not check proximity; keeping notification pulse", e);
                    }
                }
            });
            Log.i(TAG, "Notification pulse hook installed");
        } catch (Throwable e) {
            Log.e(TAG, "Notification pulse hook unavailable", e);
        }
    }

    private boolean start() {
        try {
            cpu.acquire(TIMEOUT_MS + 500);
            samplingRaw = raw != null && sensors.registerListener(this, raw, 50000, handler);
            boolean fallback = standard != null && sensors.registerListener(this, standard,
                    SensorManager.SENSOR_DELAY_NORMAL, handler);
            if (samplingRaw || fallback) {
                handler.postDelayed(timeout, TIMEOUT_MS);
                return true;
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "Notification sensor registration failed", e);
        }
        finish(false, "unavailable");
        return false;
    }

    @Override public void onSensorChanged(SensorEvent e) {
        if (active != this || e.values.length == 0) return;
        if (e.sensor == raw && e.timestamp >= since && e.values.length >= 2) {
            Boolean near = CrPocketController.classifyRaw(e.values[0], e.values[1]);
            if (near != null) finish(near, "RAW");
        } else if (e.sensor == standard && Float.isFinite(e.values[0])) {
            standardNear = e.values[0] < standard.getMaximumRange();
            if (!samplingRaw) finish(standardNear, "standard");
        }
    }

    @Override public void onAccuracyChanged(Sensor sensor, int accuracy) { }

    private void finish(boolean covered, String source) {
        if (active != this) return;
        active = null;
        handler.removeCallbacks(timeout);
        sensors.unregisterListener(this);
        try {
            Log.i(TAG, "Notification pulse " + (covered ? "suppressed" : "allowed") + " via " + source);
            for (XC_MethodHook.MethodHookParam p : requests) {
                try {
                    if (covered) {
                        Runnable suppressed = (Runnable) p.args[0];
                        if (suppressed != null) suppressed.run();
                    } else {
                        XposedBridge.invokeOriginalMethod((Method) p.method, p.thisObject, p.args);
                    }
                } catch (Throwable t) {
                    Log.e(TAG, "Could not deliver notification pulse decision", t);
                }
            }
        } finally {
            if (cpu.isHeld()) cpu.release();
        }
    }
}
