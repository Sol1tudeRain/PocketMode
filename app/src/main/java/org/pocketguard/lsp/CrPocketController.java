/*
 * PocketMode for Android 17 / PixelOS.
 *
 * One trigger: physical display state. ON and Doze/AOD mean check proximity;
 * OFF means stop proximity. No power-key wake heuristics, broadcast timing,
 * cached NEAR, notification intercept, or repeated sensor re-registration.
 */
package org.pocketguard.lsp;

import android.content.Context;
import android.database.ContentObserver;
import android.graphics.PixelFormat;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.display.DisplayManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Log;
import android.view.Display;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;

import de.robv.android.xposed.XposedHelpers;

final class CrPocketController {
    private static final String TAG = "PocketModeCr";
    private static final Uri SETTINGS_URI =
            Uri.parse("content://org.pocketguard.lsp.config/settings");
    private static final long NEAR_CONFIRM_MS = 120L;
    private static final int PROXIMITY_RATE_US = 400000;
    private static final int TYPE_SYSTEM_ERROR = 2010;
    private static final int DISABLE_USER_ACTIVITY_INPUT = 2;
    private static final int DISABLE_EXPAND = 0x00010000;
    private static final int DISABLE2_QS_AND_SHADE = 0x5;

    private final Context systemContext;
    private final SensorManager sensorManager;
    private final DisplayManager displayManager;
    private final WindowManager windowManager;
    private final Sensor proximity;
    private final HandlerThread windowThread = new HandlerThread("PocketModeUi");
    private final HandlerThread sensorThread = new HandlerThread("PocketModeSensor");
    private Handler main;
    private Handler sensorHandler;
    private PocketInputShield inputShield;
    private boolean enabled;
    private boolean displayLit;
    private int displayState = Display.STATE_UNKNOWN;
    private volatile boolean listening;
    private volatile int sensorEpoch;
    private boolean near;
    private volatile boolean overlayShowing;
    private volatile boolean bypassUntilFar;
    private boolean preparing;
    private int preparationEpoch;
    private View overlay;
    private Object statusBarManager;

    private final Runnable showIfStillNear = () -> {
        if (enabled && displayLit && near && !bypassUntilFar
                && PocketPowerHook.isEscapeInstalled()) {
            requestOverlay();
        }
    };

    private final DisplayManager.DisplayListener displayListener =
            new DisplayManager.DisplayListener() {
                @Override public void onDisplayAdded(int id) {
                    if (id == Display.DEFAULT_DISPLAY) syncDisplayState();
                }
                @Override public void onDisplayRemoved(int id) {
                    if (id == Display.DEFAULT_DISPLAY) setDisplayState(Display.STATE_OFF);
                }
                @Override public void onDisplayChanged(int id) {
                    if (id == Display.DEFAULT_DISPLAY) syncDisplayState();
                }
            };

    private final SensorEventListener sensorListener = new SensorEventListener() {
        @Override public void onSensorChanged(SensorEvent event) {
            if (event.sensor.getType() != Sensor.TYPE_PROXIMITY
                    || event.values == null || event.values.length == 0) return;
            final int epoch = sensorEpoch;
            final float distance = event.values[0];
            final boolean covered = distance < proximity.getMaximumRange();
            main.post(() -> {
                if (!enabled || !displayLit || !listening || epoch != sensorEpoch)
                    return;
                if (covered == near) return;
                near = covered;
                Log.i(TAG, "Proximity " + (near ? "NEAR" : "FAR")
                        + " distance=" + distance + " displayState=" + displayState);
                if (near) {
                    main.removeCallbacks(showIfStillNear);
                    main.postDelayed(showIfStillNear, NEAR_CONFIRM_MS);
                } else {
                    bypassUntilFar = false;
                    main.removeCallbacks(showIfStillNear);
                    hideOverlay();
                }
            });
        }
        @Override public void onAccuracyChanged(Sensor sensor, int accuracy) { }
    };

    CrPocketController(Context context) {
        systemContext = context;
        sensorManager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
        displayManager = (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
        windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        Sensor nonWakeup = sensorManager == null ? null :
                sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY, false);
        Sensor standard = sensorManager == null ? null :
                sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY);
        proximity = nonWakeup != null ? nonWakeup : standard;
    }

    void init() {
        if (proximity == null || displayManager == null || windowManager == null) {
            Log.e(TAG, "Missing sensor/display/window service; disabling protection");
            return;
        }
        windowThread.start();
        sensorThread.start();
        main = new Handler(windowThread.getLooper());
        sensorHandler = new Handler(sensorThread.getLooper());
        inputShield = new PocketInputShield(systemContext, main, () -> overlayShowing);
        inputShield.init();

        systemContext.getContentResolver().registerContentObserver(
                SETTINGS_URI, false, new ContentObserver(main) {
                    @Override public void onChange(boolean selfChange) {
                        readSwitch();
                    }
                });
        displayManager.registerDisplayListener(displayListener, main);
        main.post(() -> {
            readSwitch();
            syncDisplayState();
        });
        // Retry once after system boot, when the module's provider becomes ready.
        main.postDelayed(this::readSwitch, 15000);
        Log.i(TAG, "Simple display-driven controller: sensor=" + proximity.getName());
    }

    private void readSwitch() {
        try {
            Bundle state = systemContext.getContentResolver()
                    .call(SETTINGS_URI, "get", null, null);
            if (state == null) throw new IllegalStateException("Null settings");
            boolean requested = state.getBoolean("enabled", true);
            if (requested == enabled) return;
            enabled = requested;
            Log.i(TAG, "Module enabled=" + enabled);
            applySensorState();
        } catch (Throwable t) {
            if (enabled) {
                enabled = false;
                applySensorState();
            }
            Log.e(TAG, "Settings unavailable: disabled until retry", t);
        }
    }

    private void syncDisplayState() {
        Display current = displayManager.getDisplay(Display.DEFAULT_DISPLAY);
        setDisplayState(current == null ? Display.STATE_OFF : current.getState());
    }

    private static boolean isLit(int state) {
        // Doze is AOD. In DOZE_SUSPEND pixels may remain visible while the
        // panel is driven by a low-power controller.
        return state == Display.STATE_ON || state == Display.STATE_DOZE
                || state == Display.STATE_DOZE_SUSPEND;
    }

    private void setDisplayState(int nextState) {
        if (nextState == displayState) return;
        displayState = nextState;
        displayLit = isLit(nextState);
        Log.i(TAG, "Display state=" + nextState + " lit=" + displayLit);
        applySensorState();
    }

    private void applySensorState() {
        if (!enabled || !displayLit) {
            main.removeCallbacks(showIfStillNear);
            hideOverlay();
            stopSensor();
            return;
        }
        startSensor();
    }

    private void startSensor() {
        if (listening) return;
        near = false;
        bypassUntilFar = false;
        final int epoch = ++sensorEpoch;
        try {
            listening = sensorManager.registerListener(sensorListener, proximity,
                    PROXIMITY_RATE_US, sensorHandler);
            Log.i(TAG, "Sensor register=" + listening + " epoch=" + epoch
                    + " state=" + displayState);
        } catch (Throwable t) {
            listening = false;
            Log.e(TAG, "Sensor registration failed", t);
        }
    }

    private void stopSensor() {
        ++sensorEpoch;
        if (listening) {
            listening = false;
            try {
                sensorManager.unregisterListener(sensorListener);
            } catch (Throwable t) {
                Log.e(TAG, "Sensor unregister failed", t);
            }
        }
        near = false;
        bypassUntilFar = false;
    }

    boolean isOverlayShowing() { return overlayShowing; }

    void exitOnLongPower() {
        bypassUntilFar = true;
        Handler h = main;
        if (h != null) h.post(() -> {
            main.removeCallbacks(showIfStillNear);
            hideOverlay();
        });
    }

    private void requestOverlay() {
        if (overlayShowing || overlay != null || preparing || !enabled
                || !displayLit || !near || bypassUntilFar) return;
        if (inputShield == null || !inputShield.isInstalled()) {
            Log.e(TAG, "Input shield not installed; cannot show pocket lock");
            return;
        }
        preparing = true;
        final int epoch = ++preparationEpoch;
        inputShield.prepare(() -> {
            if (epoch != preparationEpoch) return;
            preparing = false;
            if (enabled && displayLit && near && !bypassUntilFar && overlay == null) {
                showOverlay();
            } else {
                inputShield.stop();
            }
        }, () -> {
            if (epoch != preparationEpoch) return;
            preparing = false;
            Log.e(TAG, "Gesture shield unavailable; no overlay");
        });
    }

    private void showOverlay() {
        try {
            Context module = systemContext.createPackageContext("org.pocketguard.lsp",
                    Context.CONTEXT_IGNORE_SECURITY);
            View content = LayoutInflater.from(module).inflate(R.layout.pocket_overlay, null);
            FrameLayout blocker = new FrameLayout(systemContext) {
                @Override public boolean dispatchTouchEvent(MotionEvent e) {
                    return true;
                }
            };
            blocker.setClickable(true);
            blocker.addView(content, new FrameLayout.LayoutParams(-1, -1));
            blocker.setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);

            WindowManager.LayoutParams params = new WindowManager.LayoutParams();
            params.width = -1;
            params.height = -1;
            params.gravity = Gravity.CENTER;
            params.format = PixelFormat.TRANSLUCENT;
            // TYPE_SECURE_SYSTEM_OVERLAY is forcibly NOT_TOUCHABLE on A17.
            params.type = TYPE_SYSTEM_ERROR;
            params.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                    | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                    | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
                    | WindowManager.LayoutParams.FLAG_FULLSCREEN
                    | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
            params.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            params.setTitle("PocketMode:Framework");
            XposedHelpers.setIntField(params, "inputFeatures",
                    DISABLE_USER_ACTIVITY_INPUT);
            windowManager.addView(blocker, params);
            overlay = blocker;
            overlayShowing = true;
            disableShade();
            Log.i(TAG, "Pocket overlay attached displayState=" + displayState);
        } catch (Throwable t) {
            Log.e(TAG, "Could not create pocket overlay", t);
            if (inputShield != null) inputShield.stop();
            restoreShade();
        }
    }

    private void hideOverlay() {
        ++preparationEpoch;
        preparing = false;
        overlayShowing = false;
        if (inputShield != null) inputShield.stop();
        if (overlay != null) {
            try {
                windowManager.removeViewImmediate(overlay);
                overlay = null;
            } catch (Throwable t) {
                overlayShowing = true; // keep POWER escape available
                Log.e(TAG, "Cannot remove pocket overlay", t);
            }
        }
        restoreShade();
    }

    private void disableShade() {
        try {
            if (statusBarManager == null)
                statusBarManager = systemContext.getSystemService("statusbar");
            if (statusBarManager == null) return;
            XposedHelpers.callMethod(statusBarManager, "disable", DISABLE_EXPAND);
            XposedHelpers.callMethod(statusBarManager, "disable2", DISABLE2_QS_AND_SHADE);
        } catch (Throwable t) {
            Log.w(TAG, "Cannot disable notification shade", t);
            restoreShade();
        }
    }

    private void restoreShade() {
        if (statusBarManager == null) return;
        try {
            XposedHelpers.callMethod(statusBarManager, "disable", 0);
            XposedHelpers.callMethod(statusBarManager, "disable2", 0);
        } catch (Throwable t) {
            Log.w(TAG, "Cannot restore notification shade", t);
        }
    }
}
