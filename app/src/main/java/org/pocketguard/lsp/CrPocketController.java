/*
 * CrPocketController adapts the service/controller separation used by
 * crDroid / Paranoid Android PocketService and PocketLock (Apache-2.0).
 * Source inspiration:
 * https://github.com/crdroidandroid/android_frameworks_base/tree/17.0
 *
 * Unlike the ROM's native implementation, this is injected by LSPosed into
 * system_server and uses the module's own view resources. No framework
 * resource IDs or android.pocket classes are assumed to exist on PixelOS.
 */
package org.pocketguard.lsp;

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
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;

import de.robv.android.xposed.XposedHelpers;

/**
 * System policy-owned pocket protector. The sensor listener does not share the
 * UI thread, so a busy UI thread will not delay sensor sampling. UI changes
 * are serialized on the main looper. One overlay, one owner, no cross-process
 * power-key heartbeat, no 700-ms foreground-app polling.
 */
final class CrPocketController {
    private static final String TAG = "PocketModeCr";
    private static final Uri SETTINGS_URI =
            Uri.parse("content://org.pocketguard.lsp.config/settings");
    private static final long NEAR_CONFIRM_MS = 120;
    private static final int PROXIMITY_RATE_US = 400000; // crDroid PocketService
    private static final int TYPE_SECURE_SYSTEM_OVERLAY = 2015;
    private static final int DISABLE_USER_ACTIVITY_INPUT = 2;

    private final Context systemContext;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final SensorManager sensorManager;
    private final PowerManager powerManager;
    private final WindowManager windowManager;
    private final Sensor proximity;
    private final HandlerThread sensorThread = new HandlerThread("PocketModeSensors");
    private Handler sensorHandler;
    private boolean enabled;
    private boolean listening;
    private boolean near;
    private volatile boolean overrideUntilFar;
    private boolean interactive;
    private volatile boolean overlayShowing;
    private View overlay;
    private long lastSensorTimestampNanos;

    private final Runnable showIfStillNear = () -> {
        if (enabled && interactive && near && !overrideUntilFar
                && PocketPowerHook.isEscapeInstalled()) {
            showOverlay();
        }
    };

    private final SensorEventListener proximityListener = new SensorEventListener() {
        @Override public void onSensorChanged(SensorEvent event) {
            if (event.sensor.getType() != Sensor.TYPE_PROXIMITY || event.values == null
                    || event.values.length == 0) return;
            final boolean covered = event.values[0] < proximity.getMaximumRange();
            final long now = SystemClock.elapsedRealtime();
            final float distance = event.values[0];
            final long eventTimestamp = event.timestamp;
            main.post(() -> {
                // Keep sensor state fresh while the screen is OFF, exactly as
                // crDroid's PocketService does. Only the UI overlay is gated
                // by whether the device is interactive.
                if (!enabled || !listening) return;
                if (eventTimestamp > 0 && lastSensorTimestampNanos > eventTimestamp)
                    return;
                lastSensorTimestampNanos = eventTimestamp;
                // A fresh FAR sample also releases the long-POWER override if
                // registration reset the previous near state on wake.
                if (!covered) overrideUntilFar = false;
                if (covered == near) return;
                near = covered;
                Log.i(TAG, "Proximity " + (covered ? "NEAR" : "FAR")
                        + ", distance=" + distance + ", elapsed=" + now);
                if (covered) {
                    if (interactive) scheduleShow();
                } else {
                    overrideUntilFar = false;
                    main.removeCallbacks(showIfStillNear);
                    hideOverlay(); // FAR removes immediately, no timeout
                }
            });
        }
        @Override public void onAccuracyChanged(Sensor sensor, int accuracy) { }
    };

    CrPocketController(Context context) {
        systemContext = context;
        sensorManager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
        powerManager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        proximity = sensorManager == null ? null
                : sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY);
        interactive = powerManager != null && powerManager.isInteractive();
    }

    void init() {
        if (proximity == null || windowManager == null || powerManager == null) {
            Log.e(TAG, "No proximity sensor / WindowManager / PowerManager, fail open");
            return;
        }
        sensorThread.start();
        sensorHandler = new Handler(sensorThread.getLooper());

        systemContext.getContentResolver().registerContentObserver(
                SETTINGS_URI, false, new ContentObserver(main) {
                    @Override public void onChange(boolean selfChange) {
                        refreshEnabled();
                    }
                });

        IntentFilter screenFilter = new IntentFilter();
        screenFilter.addAction(Intent.ACTION_SCREEN_ON);
        screenFilter.addAction(Intent.ACTION_SCREEN_OFF);
        try {
            systemContext.registerReceiver(new BroadcastReceiver() {
                @Override public void onReceive(Context context, Intent intent) {
                    if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
                        interactive = false;
                        main.removeCallbacks(showIfStillNear);
                        hideOverlay();
                        // Deliberately do NOT unregister here: crDroid listens
                        // during screen-off, so pocket state is ready on wake.
                    } else if (Intent.ACTION_SCREEN_ON.equals(intent.getAction())) {
                        interactive = true;
                        if (!enabled) return;
                        if (!listening) registerSensor();
                        if (proximity.isWakeUpSensor()) {
                            // Wake-up proximity stays up to date during sleep.
                            if (near) scheduleShow();
                        } else {
                            // Non-wakeup sensor events can be stale across
                            // suspend. Re-arm ONCE per wake for a fresh sample,
                            // not every 1.5 seconds as the old implementation.
                            unregisterSensor(false);
                            registerSensor();
                        }
                    }
                }
            }, screenFilter, Context.RECEIVER_NOT_EXPORTED);
        } catch (Throwable e) {
            Log.e(TAG, "Screen receiver failed; disabling pocket protection", e);
            return;
        }
        refreshEnabled();
        // During early boot the user's package provider may not be ready yet.
        // A one-shot post-boot retry keeps first-install default ON without
        // introducing background polling.
        main.postDelayed(this::refreshEnabled, 15000);
        Log.i(TAG, "crDroid-style framework pocket controller ready, sensor="
                + proximity.getName() + ", wakeup=" + proximity.isWakeUpSensor());
    }

    private void refreshEnabled() {
        Bundle state;
        try {
            state = systemContext.getContentResolver()
                    .call(SETTINGS_URI, "get", null, null);
            if (state == null) throw new IllegalStateException("Null preferences");
        } catch (Throwable t) {
            Log.w(TAG, "Preferences provider unavailable; leave protection off", t);
            enabled = false;
            unregisterSensor();
            hideOverlay();
            return;
        }
        boolean wanted = state.getBoolean("enabled", true);
        if (wanted == enabled) return;
        enabled = wanted;
        if (enabled) {
            // CrDroid listens while the screen is OFF; extended to ON for
            // user-requested unlocked-screen pocket protection.
            registerSensor();
            if (interactive && near) scheduleShow();
        } else {
            unregisterSensor(true);
            hideOverlay();
        }
        Log.i(TAG, "PocketMode enabled=" + enabled);
    }

    private void registerSensor() {
        if (listening || sensorManager == null || sensorHandler == null) return;
        // The crDroid service runs sensors on a dedicated HandlerThread.
        // No repeated unregister/re-register in a timer.
        boolean ok = sensorManager.registerListener(proximityListener,
                proximity, PROXIMITY_RATE_US, sensorHandler);
        listening = ok;
        if (!ok) Log.e(TAG, "Cannot register proximity listener; overlay disabled");
    }

    private void unregisterSensor() {
        unregisterSensor(true);
    }

    private void unregisterSensor(boolean clearState) {
        if (listening && sensorManager != null) {
            listening = false;
            sensorManager.unregisterListener(proximityListener);
        }
        lastSensorTimestampNanos = 0;
        main.removeCallbacks(showIfStillNear);
        if (clearState) {
            near = false;
            overrideUntilFar = false;
        } else {
            // Discard stale near state until HAL supplies a new reading.
            near = false;
        }
    }

    private void scheduleShow() {
        if (overlayShowing || overrideUntilFar || !enabled || !interactive) return;
        main.removeCallbacks(showIfStillNear);
        main.postDelayed(showIfStillNear, NEAR_CONFIRM_MS);
    }

    boolean isOverlayShowing() { return overlayShowing; }

    void exitOnLongPower() {
        overrideUntilFar = true;
        main.post(() -> {
            Log.i(TAG, "Long power: dismiss and bypass until proximity FAR");
            main.removeCallbacks(showIfStillNear);
            hideOverlay();
        });
    }

    private void showOverlay() {
        if (overlayShowing || overlay != null || !interactive || !enabled
                || !near || overrideUntilFar) return;
        try {
            Context module = systemContext.createPackageContext("org.pocketguard.lsp",
                    Context.CONTEXT_IGNORE_SECURITY);
            View content = LayoutInflater.from(module).inflate(R.layout.pocket_overlay, null);
            FrameLayout blocker = new FrameLayout(systemContext) {
                @Override public boolean dispatchTouchEvent(MotionEvent event) {
                    // Consume all touchscreen gestures, including edge swipes.
                    return true;
                }
            };
            blocker.setClickable(true);
            blocker.addView(content, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT));
            blocker.setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION);
            WindowManager.LayoutParams params = new WindowManager.LayoutParams();
            params.width = WindowManager.LayoutParams.MATCH_PARENT;
            params.height = WindowManager.LayoutParams.MATCH_PARENT;
            params.gravity = Gravity.CENTER;
            params.format = PixelFormat.TRANSLUCENT;
            // Same topmost framework window type and flags as crDroid PocketLock.
            params.type = TYPE_SECURE_SYSTEM_OVERLAY;
            params.flags = WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
                    | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                    | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
                    | WindowManager.LayoutParams.FLAG_FULLSCREEN
                    | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
            params.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            params.setTitle("PocketMode:Framework");
            try {
                // Touches on this window must not extend the normal display timeout.
                XposedHelpers.setIntField(params, "inputFeatures",
                        DISABLE_USER_ACTIVITY_INPUT);
            } catch (Throwable t) {
                Log.e(TAG, "No inputFeatures support; fail open", t);
                return;
            }
            windowManager.addView(blocker, params);
            overlay = blocker;
            overlayShowing = true;
            Log.i(TAG, "Framework pocket overlay attached with secure system layer");
        } catch (Throwable t) {
            Log.e(TAG, "Framework overlay creation failed; fail open", t);
            // Do not keep showing/retrying on a failed or permission-denied ROM.
            overrideUntilFar = true;
        }
    }

    private void hideOverlay() {
        View existing = overlay;
        if (existing == null) return;
        try {
            windowManager.removeViewImmediate(existing);
            overlay = null;
            overlayShowing = false;
            Log.i(TAG, "Framework pocket overlay removed");
        } catch (Throwable t) {
            // Retain the visibility flag so long POWER can retry dismissal
            // rather than leaving a touch-blocking window with no escape.
            Log.e(TAG, "Could not remove framework overlay; escape still armed", t);
        }
    }
}
