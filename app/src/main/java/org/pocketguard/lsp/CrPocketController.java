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
    private static final int RAW_TYPE = 33171005;
    private static final int RAW_RATE_US = 200000; // 5 Hz while panel displays pixels
    // Calibrated from SensorProbe on this exact handset: uncovered raw[0]
    // ~40-60 and raw[1] ~175-190; covered raw[0] ~1023 and raw[1]
    // ~1023-1158. The factory HAL's raw[2] is unreliable after wake.
    private static final float RAW_NEAR_ADC = 600f;
    private static final float RAW_FAR_ADC = 250f;
    private static final float RAW_FAR_REFERENCE = 500f;
    private static final int TYPE_SYSTEM_ERROR = 2010;
    private static final int DISABLE_USER_ACTIVITY_INPUT = 2;
    private static final int DISABLE_EXPAND = 0x00010000;
    private static final int DISABLE2_QS_AND_SHADE = 0x5;

    private final Context systemContext;
    private final SensorManager sensorManager;
    private final DisplayManager displayManager;
    private final WindowManager windowManager;
    private final Sensor proximity;
    private final Sensor rawProximity;
    private final HandlerThread windowThread = new HandlerThread("PocketModeUi");
    private final HandlerThread sensorThread = new HandlerThread("PocketModeSensor");
    private Handler main;
    private Handler sensorHandler;
    private PocketInputShield inputShield;
    private volatile boolean enabled;
    private Runnable pendingWake;
    private PowerManager.WakeLock probeLock;
    private boolean waitingForFrame;
    private Runnable screenReady;
    private java.util.function.Consumer<Boolean> dreamDecision;
    private PowerManager.WakeLock dreamProbeLock;
    private Runnable wakeTimeout;
    private volatile boolean manualWake;
    private boolean frameReady;
    private boolean standardCovered;
    private boolean standardSample;
    private final Runnable expireManualWake = () -> {
        manualWake = false;
        resumeWake();
        releaseScreen();
        applySensorState();
    };
    private boolean displayLit;
    private int displayState = Display.STATE_UNKNOWN;
    private volatile boolean listening;
    private volatile int sensorEpoch;
    private boolean rawListening;
    private boolean rawDecisive;
    private boolean near;
    private volatile boolean overlayShowing;
    private volatile boolean bypassUntilFar;
    private boolean preparing;
    private int preparationEpoch;
    private View overlay;
    private Object statusBarManager;

    private final Runnable showIfStillNear = () -> {
        if (enabled && canShow() && near && !bypassUntilFar
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

    private void updateNear(boolean covered, String source) {
        if (covered == near) return;
        near = covered;
        Log.i(TAG, "Proximity " + (near ? "NEAR" : "FAR")
                + " source=" + source + " displayState=" + displayState);
        if (near) {
            if (isAmbient() && !manualWake) {
                return;
            }
            main.removeCallbacks(showIfStillNear);
            main.postDelayed(showIfStillNear, NEAR_CONFIRM_MS);
        } else {
            bypassUntilFar = false;
            main.removeCallbacks(showIfStillNear);
            hideOverlay();
        }
    }

    private final SensorEventListener sensorListener = new SensorEventListener() {
        @Override public void onSensorChanged(SensorEvent event) {
            if (event.values == null || event.values.length == 0) return;
            final int type = event.sensor.getType();
            if (type != Sensor.TYPE_PROXIMITY && type != RAW_TYPE) return;
            final int epoch = sensorEpoch;
            final float a = event.values[0];
            final float b = event.values.length > 1 ? event.values[1] : Float.NaN;
            main.post(() -> {
                if (!enabled || (!displayLit && !manualWake && dreamDecision == null) || !listening || epoch != sensorEpoch)
                    return;
                if (type == RAW_TYPE) {
                    if (!rawListening || Float.isNaN(b)) return;
                    // Uncovered: roughly (50,180); covered: (1023,1158)
                    // or (1023,1023) immediately after power wake. Trust
                    // actual ADC readings, NOT raw[2] nor standard FAR=5.
                    Boolean rawNear = classifyRaw(a, b);
                    if (rawNear != null) {
                        if (!rawDecisive) {
                            Log.i(TAG, "Raw proximity first decision=" + rawNear
                                    + " adc0=" + a + " adc1=" + b
                                    + " epoch=" + epoch);
                        }
                        rawDecisive = true;
                        updateNear(rawNear, "RAW adc0=" + a + " adc1=" + b);
                        if (manualWake) decidePowerWake(rawNear);
                        else if (dreamDecision != null) dreamDecision.accept(rawNear);
                    }
                } else if (!rawListening || !rawDecisive) {
                    // Fallback only until the factory ADC provides a
                    // decisive reading, or if the factory sensor is absent.
                    standardCovered = a < proximity.getMaximumRange();
                    standardSample = true;
                    if (!manualWake || !rawListening) {
                        updateNear(standardCovered, "STANDARD distance=" + a);
                        if (manualWake) decidePowerWake(standardCovered);
                        else if (!rawListening && dreamDecision != null) dreamDecision.accept(standardCovered);
                    }
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
        rawProximity = findRaw(sensorManager);
    }

    static Sensor findRaw(SensorManager manager) {
        if (manager != null) {
            for (Sensor candidate : manager.getSensorList(Sensor.TYPE_ALL)) {
                if (candidate.getType() == RAW_TYPE
                        && candidate.getName().contains("TCS3720ALSPRX Proximity Sensor Strm")) {
                    return candidate;
                }
            }
        }
        return null;
    }

    static Boolean classifyRaw(float a, float b) {
        if (a >= RAW_NEAR_ADC && b >= RAW_NEAR_ADC) return Boolean.TRUE;
        if (a <= RAW_FAR_ADC && b <= RAW_FAR_REFERENCE) return Boolean.FALSE;
        return null;
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
        Log.i(TAG, "Display-driven controller: standard=" + proximity.getName()
                + " raw=" + (rawProximity == null ? "unavailable" : rawProximity.getName()));
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
        if (displayState == Display.STATE_ON && nextState != Display.STATE_ON && manualWake) {
            manualWake = false;
            pendingWake = null;
            if (wakeTimeout != null) main.removeCallbacks(wakeTimeout);
            releaseScreen();
        }
        displayState = nextState;
        displayLit = isLit(nextState);
        Log.i(TAG, "Display state=" + nextState + " lit=" + displayLit);
        applySensorState();
    }

    private void applySensorState() {
        if (!enabled || (!displayLit && !manualWake && dreamDecision == null)) {
            main.removeCallbacks(showIfStillNear);
            hideOverlay();
            stopSensor();
            if (!enabled) {
                if (dreamDecision != null) dreamDecision.accept(false);
                clearDreamProbe();
                manualWake = false;
                resumeWake();
                releaseScreen();
            }
            return;
        }
        startSensor();
        if (isAmbient() && !manualWake) {
            hideOverlay();
        } else if (canShow() && near && !bypassUntilFar) {
            main.post(showIfStillNear);
        }
    }

    private boolean isAmbient() {
        PowerManager pm = systemContext.getSystemService(PowerManager.class);
        return displayState == Display.STATE_DOZE || displayState == Display.STATE_DOZE_SUSPEND
                || (displayState == Display.STATE_ON && pm != null && !pm.isInteractive());
    }

    private boolean canShow() {
        return manualWake || (displayState == Display.STATE_ON && !isAmbient());
    }

    boolean canCheckPowerWake() {
        return enabled && main != null && PocketPowerHook.isEscapeInstalled()
                && !bypassUntilFar;
    }

    void preparePowerWake(Runnable wake) {
        main.post(() -> {
            if (!canCheckPowerWake()) { wake.run(); return; }
            if (manualWake) {
                Runnable earlier = pendingWake;
                if (earlier == null) wake.run();
                else pendingWake = () -> {
                    earlier.run();
                    wake.run();
                };
                return;
            }
            manualWake = true;
            clearDreamProbe();
            pendingWake = wake;
            // POWER-up may release the policy wake lock before the RAW sample arrives.
            // A partial lock runs the probe without lighting the panel.
            if (probeLock == null) {
                PowerManager pm = systemContext.getSystemService(PowerManager.class);
                probeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PocketMode:probe");
                probeLock.setReferenceCounted(false);
            }
            probeLock.acquire(1500);
            waitingForFrame = false;
            main.removeCallbacks(expireManualWake);
            main.postDelayed(expireManualWake, 2500);
            startSensor();
            if (rawDecisive || (!rawListening && standardSample)) {
                decidePowerWake(near);
            } else {
                wakeTimeout = () -> decidePowerWake(standardSample && standardCovered);
                main.postDelayed(wakeTimeout, 450);
            }
        });
    }

    private void decidePowerWake(boolean covered) {
        if (!covered || bypassUntilFar || !enabled) {
            manualWake = false;
            resumeWake();
            releaseScreen();
            return;
        }
        if (pendingWake == null) return;
        if (waitingForFrame) return;
        waitingForFrame = true;
        if (wakeTimeout != null) main.removeCallbacks(wakeTimeout);
        updateNear(true, "power wake");
        requestOverlay();
        if (frameReady) resumeWake();
        else {
            // Some panels only schedule rendering after wake starts. In that case
            // let the framework wake, keeping its backlight unblock callback gated.
            wakeTimeout = this::resumeWake;
            main.postDelayed(wakeTimeout, 180);
        }
    }

    private void resumeWake() {
        if (wakeTimeout != null) main.removeCallbacks(wakeTimeout);
        wakeTimeout = null;
        Runnable wake = pendingWake;
        pendingWake = null;
        if (wake != null) wake.run();
        // The bounded lock bridges the queued policy callback as well as sampling.

    }

    // Process probe start/stop in the same order as DreamManager requests.
    void setDreamProbe(java.util.function.Consumer<Boolean> decision) {
        main.post(() -> {
            clearDreamProbe();
            if (decision == null || !enabled || manualWake || bypassUntilFar) {
                if (decision != null) decision.accept(false);
                applySensorState();
                return;
            }
            dreamDecision = decision;
            if (dreamProbeLock == null) {
                dreamProbeLock = systemContext.getSystemService(PowerManager.class)
                        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PocketMode:dream-probe");
                dreamProbeLock.setReferenceCounted(false);
            }
            dreamProbeLock.acquire(1000);
            startSensor();
            if (rawDecisive || (!rawListening && standardSample)) decision.accept(near);
            else main.postDelayed(dreamTimeout, 700);
        });
    }

    private final Runnable dreamTimeout = this::resolveDreamTimeout;

    private void resolveDreamTimeout() {
        if (dreamDecision != null && !rawDecisive) {
            dreamDecision.accept(standardSample && standardCovered);
        }
    }

    private void clearDreamProbe() {
        main.removeCallbacks(dreamTimeout);
        dreamDecision = null;
        if (dreamProbeLock != null && dreamProbeLock.isHeld()) dreamProbeLock.release();
    }

    void whenScreenReady(Runnable unblock) {
        main.post(() -> {
            releaseScreen();
            if (!manualWake || frameReady || !enabled || bypassUntilFar) unblock.run();
            else {
                screenReady = unblock;
                main.postDelayed(screenDeadline, 900);
            }
        });
    }

    private final Runnable screenDeadline = this::releaseScreen;

    private void releaseScreen() {
        main.removeCallbacks(screenDeadline);
        Runnable callback = screenReady;
        screenReady = null;
        if (callback != null) callback.run();
    }


    private void startSensor() {
        if (listening) return;
        near = false;
        bypassUntilFar = false;
        rawDecisive = false;
        standardSample = false;
        final int epoch = ++sensorEpoch;
        boolean standardOk = false;
        boolean factoryOk = false;
        try {
            standardOk = sensorManager.registerListener(sensorListener, proximity,
                    PROXIMITY_RATE_US, sensorHandler);
        } catch (Throwable t) {
            Log.e(TAG, "Standard proximity registration failed", t);
        }
        if (rawProximity != null) {
            try {
                factoryOk = sensorManager.registerListener(sensorListener, rawProximity,
                        RAW_RATE_US, sensorHandler);
            } catch (Throwable t) {
                Log.e(TAG, "Factory proximity registration failed", t);
            }
        }
        rawListening = factoryOk;
        listening = standardOk || factoryOk;
        Log.i(TAG, "Sensor register standard=" + standardOk + " raw=" + factoryOk
                + " epoch=" + epoch + " state=" + displayState);
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
        rawListening = false;
        rawDecisive = false;
        near = false;
        bypassUntilFar = false;
    }

    boolean isOverlayShowing() { return overlayShowing; }

    boolean isManualWake() { return manualWake; }

    void exitOnLongPower() {
        bypassUntilFar = true;
        Handler h = main;
        if (h != null) h.post(() -> {
            main.removeCallbacks(showIfStillNear);
            hideOverlay();
            resumeWake();
            releaseScreen();
        });
    }

    private void requestOverlay() {
        if (overlayShowing || overlay != null || preparing || !enabled
                || !canShow() || !near || bypassUntilFar) return;
        if (inputShield == null || !inputShield.isInstalled()) {
            Log.e(TAG, "Input shield not installed; cannot show pocket lock");
            return;
        }
        preparing = true;
        final int epoch = ++preparationEpoch;
        inputShield.prepare(() -> {
            if (epoch != preparationEpoch) return;
            preparing = false;
            if (enabled && canShow() && near && !bypassUntilFar && overlay == null) {
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
            android.widget.TextView hint = content.findViewById(R.id.power_hint);
            hint.setText(module.getString(R.string.pocket_mode_instruction_step2,
                    PocketPowerHook.HOLD_SECONDS));
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
            params.format = PixelFormat.OPAQUE;
            params.windowAnimations = 0;
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
            blocker.getViewTreeObserver().registerFrameCommitCallback(() -> {
                if (overlay != blocker) return;
                frameReady = true;
                resumeWake();
                releaseScreen();
            });
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
        frameReady = false;
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
