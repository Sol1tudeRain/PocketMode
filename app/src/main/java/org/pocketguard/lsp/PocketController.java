package org.pocketguard.lsp;

import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.graphics.PixelFormat;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;

/** Run exclusively inside SystemUI. Disabled until explicitly opted in. */
final class PocketController implements SensorEventListener {
    private static final String TAG = "PocketGuardLSP";
    private static final String SETTING = "pocketguard_lsp_enabled";
    private static final int SECURE_OVERLAY = 2015; // hidden TYPE_SECURE_SYSTEM_OVERLAY
    private static final long CONFIRM_MS = 120, WATCHDOG_MS = 15000;
    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final SensorManager sm;
    private final Sensor prox, light;
    private final WindowManager wm;
    private final KeyguardManager km;
    private final PowerManager pm;
    private boolean enabled, near, bypass, hasLux;
    private float lux;
    private View overlay;
    private final Runnable showRunnable = this::maybeShow;
    private final Runnable safety = () -> {
        if (overlay != null) {
            Log.w(TAG, "Watchdog: releasing overlay until proximity FAR");
            bypass = true;
            hide();
        }
    };

    PocketController(Context ctx) {
        context = ctx;
        sm = (SensorManager) ctx.getSystemService(Context.SENSOR_SERVICE);
        prox = sm == null ? null : sm.getDefaultSensor(Sensor.TYPE_PROXIMITY);
        light = sm == null ? null : sm.getDefaultSensor(Sensor.TYPE_LIGHT);
        wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
        km = (KeyguardManager) ctx.getSystemService(Context.KEYGUARD_SERVICE);
        pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
    }

    void init() {
        context.getContentResolver().registerContentObserver(
            Settings.Global.getUriFor(SETTING), false, new ContentObserver(main) {
                @Override public void onChange(boolean selfChange) { refresh(); }
            });
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_USER_PRESENT);
        context.registerReceiver(new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) {
                String a = i.getAction();
                if (Intent.ACTION_SCREEN_ON.equals(a)) schedule();
                if (Intent.ACTION_SCREEN_OFF.equals(a) ||
                    Intent.ACTION_USER_PRESENT.equals(a)) hide();
            }
        }, filter);
        refresh();
    }

    private void refresh() {
        boolean wanted = Settings.Global.getInt(
            context.getContentResolver(), SETTING, 0) == 1 && prox != null;
        if (enabled == wanted) return;
        enabled = wanted;
        if (!wanted) {
            hide();
            if (sm != null) sm.unregisterListener(this);
            near = false; bypass = false; hasLux = false;
        } else {
            boolean registered = sm.registerListener(this, prox, SensorManager.SENSOR_DELAY_UI, main);
            if (!registered) {
                enabled = false;
                Log.e(TAG, "Proximity registration failed");
                return;
            }
            if (light != null) sm.registerListener(this, light, SensorManager.SENSOR_DELAY_UI, main);
        }
        Log.i(TAG, "PocketMode enabled=" + enabled);
    }

    @Override public void onSensorChanged(SensorEvent event) {
        if (!enabled || event.values == null || event.values.length == 0) return;
        if (event.sensor.getType() == Sensor.TYPE_PROXIMITY) {
            boolean current = event.values[0] < event.sensor.getMaximumRange();
            if (current != near) {
                near = current;
                if (!near) { bypass = false; hide(); }
                else schedule();
            }
        } else if (event.sensor.getType() == Sensor.TYPE_LIGHT) {
            lux = event.values[0];
            hasLux = true;
            if (lux > 12f) hide(); else schedule();
        }
    }
    @Override public void onAccuracyChanged(Sensor s, int accuracy) {}

    boolean suppressPulse() { return enabled && near && !bypass; }

    private void schedule() {
        main.removeCallbacks(showRunnable);
        if (enabled && near && !bypass) main.postDelayed(showRunnable, CONFIRM_MS);
    }
    private void maybeShow() {
        if (!enabled || !near || bypass || overlay != null) return;
        if (hasLux && lux > 12f) return;
        if (wm == null || km == null || pm == null || !pm.isInteractive()
                || !km.isKeyguardLocked()) return;
        try {
            Context res = context.createPackageContext("org.pocketguard.lsp",
                    Context.CONTEXT_IGNORE_SECURITY);
            View view = LayoutInflater.from(res).inflate(R.layout.pocket_overlay, null);
            view.setOnTouchListener((v,e) -> true);
            int flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                    | WindowManager.LayoutParams.FLAG_FULLSCREEN
                    | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED;
            WindowManager.LayoutParams p = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    SECURE_OVERLAY, flags, PixelFormat.TRANSLUCENT);
            p.setTitle("PocketMode:SystemUI");
            p.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            view.setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN |
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
            wm.addView(view, p);
            overlay = view;
            main.postDelayed(safety, WATCHDOG_MS);
            Log.i(TAG, "Pocket overlay shown");
        } catch (Throwable t) {
            bypass = true; // Fail open, never grab /dev/input
            Log.e(TAG, "Window failed; bypass until sensor FAR", t);
        }
    }
    private void hide() {
        main.removeCallbacks(showRunnable);
        main.removeCallbacks(safety);
        if (overlay != null) {
            try { wm.removeViewImmediate(overlay); }
            catch (Throwable t) { Log.e(TAG, "Overlay removal error", t); }
            finally { overlay = null; }
        }
    }
}
