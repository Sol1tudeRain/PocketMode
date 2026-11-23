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

/** Fail-open SystemUI pocket overlay. Scope: com.android.systemui only. */
final class PocketController implements SensorEventListener {
    private static final String TAG = "PocketGuardLSP";
    private static final String ENABLE_KEY = "pocketguard_lsp_enabled";
    private static final String TEST_KEY = "pocketguard_lsp_test";
    private static final long CONFIRM_MS = 120;
    private static final long WATCHDOG_MS = 15000;
    private static final long TEST_MS = 5000;
    private static final int TYPE_SECURE_SYSTEM_OVERLAY = 2015;
    private static final int TYPE_STATUS_BAR_SUB_PANEL = 2017;
    private static final int TYPE_APPLICATION_OVERLAY = 2038;

    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final SensorManager sm;
    private final Sensor prox;
    private final WindowManager wm;
    private final KeyguardManager km;
    private final PowerManager pm;

    private boolean enabled;
    private boolean near;
    private boolean bypass;
    private boolean pending;
    private boolean testWindow;
    private View overlay;

    private final Runnable showRunnable = () -> {
        pending = false;
        maybeShow();
    };
    private final Runnable safety = () -> {
        if (overlay != null) {
            if (!testWindow) bypass = true;
            Log.w(TAG, "Overlay auto-released by watchdog");
            hide();
        }
    };

    PocketController(Context ctx) {
        context = ctx;
        sm = (SensorManager) ctx.getSystemService(Context.SENSOR_SERVICE);
        prox = sm == null ? null : sm.getDefaultSensor(Sensor.TYPE_PROXIMITY);
        wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
        km = (KeyguardManager) ctx.getSystemService(Context.KEYGUARD_SERVICE);
        pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
    }

    void init() {
        Log.i(TAG, "Controller initializing, proximity=" +
                (prox == null ? "ABSENT" : prox.getName()) +
                ", windowManager=" + (wm != null));
        context.getContentResolver().registerContentObserver(
                Settings.Global.getUriFor(ENABLE_KEY), false,
                new ContentObserver(main) {
                    @Override public void onChange(boolean selfChange) { refresh(); }
                });
        context.getContentResolver().registerContentObserver(
                Settings.Global.getUriFor(TEST_KEY), false,
                new ContentObserver(main) {
                    @Override public void onChange(boolean selfChange) { checkTest(); }
                });
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_USER_PRESENT);
        try {
            context.registerReceiver(new BroadcastReceiver() {
                @Override public void onReceive(Context c, Intent i) {
                    String action = i.getAction();
                    if (Intent.ACTION_SCREEN_ON.equals(action)) schedule();
                    else if (Intent.ACTION_SCREEN_OFF.equals(action)
                            || Intent.ACTION_USER_PRESENT.equals(action)) {
                        if (!testWindow) hide();
                    }
                }
            }, filter, Context.RECEIVER_NOT_EXPORTED);
        } catch (Throwable e) {
            Log.w(TAG, "Screen broadcast registration failed; proximity and watchdog remain", e);
        }
        refresh();
        checkTest();
    }

    private void refresh() {
        boolean wanted = Settings.Global.getInt(
                context.getContentResolver(), ENABLE_KEY, 0) == 1;
        if (wanted == enabled) return;
        if (!wanted) {
            enabled = false;
            if (sm != null) sm.unregisterListener(this);
            near = false;
            bypass = false;
            if (!testWindow) hide();
            Log.i(TAG, "Pocket mode OFF");
            return;
        }
        if (sm == null || prox == null) {
            Log.e(TAG, "No proximity sensor; cannot enable");
            return;
        }
        enabled = sm.registerListener(this, prox, SensorManager.SENSOR_DELAY_UI, main);
        Log.i(TAG, "Pocket mode ON=" + enabled + "; near event required");
    }

    private void checkTest() {
        if (Settings.Global.getInt(context.getContentResolver(), TEST_KEY, 0) != 1) return;
        // Clearing the trigger avoids repeated test windows across restarts.
        try { Settings.Global.putInt(context.getContentResolver(), TEST_KEY, 0); }
        catch (Throwable t) { Log.w(TAG, "Could not clear test setting", t); }
        Log.i(TAG, "Manual 5s UI test requested");
        hide();
        showOverlay(true);
    }

    @Override public void onSensorChanged(SensorEvent event) {
        if (!enabled || event.sensor.getType() != Sensor.TYPE_PROXIMITY
                || event.values == null || event.values.length == 0) return;
        boolean nextNear = event.values[0] < event.sensor.getMaximumRange();
        if (nextNear == near) return;
        near = nextNear;
        Log.i(TAG, "Proximity NEAR=" + near + " distance=" + event.values[0]);
        if (!near) {
            bypass = false;
            if (!testWindow) hide();
        } else {
            schedule();
        }
    }
    @Override public void onAccuracyChanged(Sensor s, int accuracy) { }

    boolean suppressPulse() { return enabled && near && !bypass; }

    /** Do NOT reset the entry deadline for periodic sensor events. */
    private void schedule() {
        if (!enabled || !near || bypass || pending || overlay != null) return;
        pending = true;
        main.postDelayed(showRunnable, CONFIRM_MS);
    }

    private void maybeShow() {
        if (!enabled || !near || bypass || overlay != null) return;
        if (wm == null || km == null || pm == null) return;
        boolean interactive = pm.isInteractive();
        boolean locked = km.isKeyguardLocked();
        if (!interactive || !locked) {
            Log.i(TAG, "Pocket sensed, overlay not shown: interactive=" + interactive +
                    " keyguardLocked=" + locked + " (lock screen only)");
            return;
        }
        showOverlay(false);
    }

    private void showOverlay(boolean test) {
        if (overlay != null || wm == null) return;
        try {
            Context resources = context.createPackageContext(
                    "org.pocketguard.lsp", Context.CONTEXT_IGNORE_SECURITY);
            View view = LayoutInflater.from(resources).inflate(R.layout.pocket_overlay, null);
            view.setClickable(true);
            view.setOnTouchListener((v, e) -> true);
            view.setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
            int flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                    | WindowManager.LayoutParams.FLAG_FULLSCREEN
                    | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED;

            Throwable last = null;
            for (int type : new int[]{
                    TYPE_SECURE_SYSTEM_OVERLAY, TYPE_STATUS_BAR_SUB_PANEL,
                    TYPE_APPLICATION_OVERLAY }) {
                WindowManager.LayoutParams p = new WindowManager.LayoutParams(
                        WindowManager.LayoutParams.MATCH_PARENT,
                        WindowManager.LayoutParams.MATCH_PARENT,
                        type, flags, PixelFormat.TRANSLUCENT);
                p.setTitle("PocketMode:SystemUI");
                p.layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
                try {
                    wm.addView(view, p);
                    overlay = view;
                    testWindow = test;
                    main.removeCallbacks(safety);
                    main.postDelayed(safety, test ? TEST_MS : WATCHDOG_MS);
                    Log.i(TAG, "Overlay shown (type=" + type + ", test=" + test + ")");
                    return;
                } catch (Throwable e) {
                    last = e;
                    Log.w(TAG, "Window type " + type + " rejected: " + e);
                    // A failed addView can leave a View parent; never reuse it blindly.
                    if (view.getParent() != null) break;
                }
            }
            if (!test) bypass = true; // fail open
            Log.e(TAG, "No compatible overlay window available", last);
        } catch (Throwable e) {
            if (!test) bypass = true;
            Log.e(TAG, "Overlay creation failed", e);
        }
    }

    private void hide() {
        pending = false;
        main.removeCallbacks(showRunnable);
        main.removeCallbacks(safety);
        if (overlay != null) {
            try { wm.removeViewImmediate(overlay); }
            catch (Throwable e) { Log.e(TAG, "Overlay removal failed", e); }
            overlay = null;
        }
        testWindow = false;
    }
}
