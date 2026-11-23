package org.pocketguard.lsp;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.hardware.display.DisplayManager;
import android.view.Display;
import java.util.List;
import android.graphics.PixelFormat;
import android.view.MotionEvent;
import android.widget.FrameLayout;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import de.robv.android.xposed.XposedHelpers;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;


/** SystemUI-side controller. Reads default-on settings from the module APK. */
final class PocketController implements SensorEventListener {
    private static final String TAG = "PocketGuardLSP";
    private static final Uri CONFIG_URI =
            Uri.parse("content://org.pocketguard.lsp.config/settings");
    private static final long CONFIRM_MS = 120;
    private static final long HEARTBEAT_MS = 3000;
    private static final long FAR_CONFIRM_MS = 700;
    private static final int RAW_TYPE = 33171005;
    private static final long RAW_PRIORITY_MS = 2500;
    private static final String BOOT_COUNT = "boot_count";
    private static final int INPUT_FEATURE_DISABLE_USER_ACTIVITY = 2;
    private static final long TEST_MS = 5000;
    // Navigation-bar panel is above the normal status/notification and navigation
    // bar layers in AOSP 17. SYSTEM_ERROR is a privileged fallback.
    private static final int TYPE_NAVIGATION_BAR_PANEL = 2024;
    private static final int TYPE_SYSTEM_ERROR = 2010;
    private static final int DISABLE_STATUS_BAR_EXPAND = 0x00010000;
    private static final int DISABLE2_SHADE_AND_QS = 0x5;

    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final SensorManager sm;
    private final Sensor prox;
    private final Sensor wakeupProx;
    private final Sensor rawProx;
    private final DisplayManager dm;
    private int displayState = Display.STATE_UNKNOWN;
    private boolean rawListening;
    private boolean wakeListening;
    private boolean fallbackListening;
    private long lastRawSample;
    private long lastGuardPublished = Long.MIN_VALUE;
    private final WindowManager wm;
    private final PowerManager pm;

    private volatile boolean enabled;
    private volatile boolean near;
    private volatile boolean bypass;
    private boolean pending;
    private boolean testWindow;
    private View overlay;
    private int lastTestSequence = -1;
    private long lastSensorEventElapsed;
    private long overlayShownElapsed;
    private int blockedTouchEvents;
    private Object statusBarManager;
    private boolean statusBarRestricted;
    private long farCandidateAt;
    private final Runnable confirmFar = () -> {
        if (!near || !enabled || farCandidateAt == 0) return;
        long delay = SystemClock.elapsedRealtime() - farCandidateAt;
        if (delay < FAR_CONFIRM_MS) return;
        near = false;
        farCandidateAt = 0;
        Log.i(TAG, "Confirmed proximity FAR after " + delay + "ms");
        releaseNearState();
    };

    private void restrictSystemBars() {
        try {
            if (statusBarManager == null) {
                statusBarManager = context.getSystemService("statusbar");
            }
            if (statusBarManager == null) {
                Log.w(TAG, "StatusBarManager unavailable; high window remains active");
                return;
            }
            XposedHelpers.callMethod(statusBarManager,
                    "disable", DISABLE_STATUS_BAR_EXPAND);
            XposedHelpers.callMethod(statusBarManager,
                    "disable2", DISABLE2_SHADE_AND_QS);
            statusBarRestricted = true;
            Log.i(TAG, "Status bar shade and QS disabled during pocket overlay");
        } catch (Throwable t) {
            Log.e(TAG, "Status bar policy restriction failed", t);
            // Undo any partial change immediately.
            restoreSystemBars();
        }
    }

    private void restoreSystemBars() {
        if (statusBarManager == null) return;
        try {
            XposedHelpers.callMethod(statusBarManager, "disable", 0);
            XposedHelpers.callMethod(statusBarManager, "disable2", 0);
            statusBarRestricted = false;
        } catch (Throwable t) {
            Log.e(TAG, "Could not restore status bar controls", t);
        }
    }

    private void releaseNearState() {
        bypass = false;
        main.removeCallbacks(confirmFar);
        pendingPowerChallenge = 0;
        verifiedPowerChallenge = 0;
        if (!testWindow) hide();
        publishNearGuard();
    }

    private final class TouchBlocker extends FrameLayout {
        TouchBlocker(Context c) {
            super(c);
            setClickable(true);
            setFocusable(true);
        }
        @Override public boolean dispatchTouchEvent(MotionEvent event) {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                blockedTouchEvents++;
                if (blockedTouchEvents <= 3 || blockedTouchEvents % 20 == 0) {
                    Log.i(TAG, "Pocket overlay consumed touch #" + blockedTouchEvents);
                }
            }
            // Never send the event to children or underlying apps.
            return true;
        }
    }
    private long pendingPowerChallenge;
    private long verifiedPowerChallenge;
    private long lastPowerChallengeAt;
    private long lastPowerProofAt;
    private static final long CHALLENGE_RETRY_MS = 4000;
    private static final long PROOF_TTL_MS = 10000;

    private final Runnable showRunnable = () -> {
        pending = false;
        maybeShow();
    };

    private final Runnable safety = () -> {
        if (overlay != null && testWindow) {
            Log.i(TAG, "Five-second UI test finished");
            hide();
        }
    };

    // Announce active overlay only while it is on screen. The system_server
    // power hook uses this to intercept long POWER, never a stale global value.
    private final Runnable heartbeat = new Runnable() {
        @Override public void run() {
            if (overlay == null || testWindow) return;
            try {
                Settings.Global.putLong(context.getContentResolver(),
                        PocketPowerHook.ACTIVE_HEARTBEAT, SystemClock.elapsedRealtime());
            } catch (Throwable t) {
                Log.e(TAG, "Could not publish pocket heartbeat", t);
                // No trusted power-key escape: fail open.
                hide();
                bypass = true;
                return;
            }
            main.postDelayed(this, HEARTBEAT_MS);
        }
    };

    PocketController(Context ctx) {
        context = ctx;
        sm = (SensorManager) ctx.getSystemService(Context.SENSOR_SERVICE);
        prox = sm == null ? null : sm.getDefaultSensor(Sensor.TYPE_PROXIMITY);
        wakeupProx = sm == null ? null : sm.getDefaultSensor(Sensor.TYPE_PROXIMITY, true);
        rawProx = findRawProximity(sm);
        dm = (DisplayManager) ctx.getSystemService(Context.DISPLAY_SERVICE);
        wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
        pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
    }

    void init() {
        Log.i(TAG, "Controller initializing; standard=" +
                (prox == null ? "ABSENT" : prox.getName()) +
                " wakeup=" + (wakeupProx == null ? "ABSENT" : wakeupProx.getName()) +
                " raw=" + (rawProx == null ? "ABSENT" : rawProx.getName()));
        if (dm != null) {
            dm.registerDisplayListener(new DisplayManager.DisplayListener() {
                @Override public void onDisplayAdded(int id) { }
                @Override public void onDisplayRemoved(int id) { }
                @Override public void onDisplayChanged(int id) {
                    if (id == Display.DEFAULT_DISPLAY) syncDisplayState();
                }
            }, main);
        }
        syncDisplayState();

        context.getContentResolver().registerContentObserver(
                CONFIG_URI, false, new ContentObserver(main) {
                    @Override public void onChange(boolean selfChange) { refreshConfig(); }
                });

        context.getContentResolver().registerContentObserver(
                Settings.Global.getUriFor(PocketPowerHook.EXIT_REQUEST), false,
                new ContentObserver(main) {
                    @Override public void onChange(boolean selfChange) {
                        onPowerExitRequest();
                    }
                });

        context.getContentResolver().registerContentObserver(
                Settings.Global.getUriFor(PocketPowerHook.READY_ACK), false,
                new ContentObserver(main) {
                    @Override public void onChange(boolean selfChange) {
                        onPowerProofReceived();
                    }
                });

        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_USER_PRESENT);
        filter.addAction(Intent.ACTION_USER_UNLOCKED);
        try {
            context.registerReceiver(new BroadcastReceiver() {
                @Override public void onReceive(Context c, Intent i) {
                    String action = i.getAction();
                    if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                        // Keep the already-added overlay in the WindowManager
                        // so the next physical POWER wake draws it immediately.
                        syncDisplayState();
                        if (near && !bypass) schedule();
                        main.postDelayed(PocketController.this::syncDisplayState, 100);
                    } else if (Intent.ACTION_USER_UNLOCKED.equals(action)) {
                        refreshConfig();
                    } else if (Intent.ACTION_SCREEN_ON.equals(action)
                            || Intent.ACTION_USER_PRESENT.equals(action)) {
                        syncDisplayState();
                        main.postDelayed(PocketController.this::syncDisplayState, 100);
                        if (near) schedule();
                    }
                }
            }, filter, Context.RECEIVER_NOT_EXPORTED);
        } catch (Throwable e) {
            Log.w(TAG, "Screen event receiver unavailable", e);
        }
        refreshConfig();
    }

    /**
     * The module APK's device-protected ContentProvider is the single config source.
     * First install defaults to enabled=true. A failed read preserves the last known
     * state rather than guessing over a deliberate OFF setting.
     */
    private void refreshConfig() {
        Bundle state;
        try {
            state = context.getContentResolver().call(CONFIG_URI, "get", null, null);
            if (state == null) throw new IllegalStateException("Null config");
        } catch (Throwable e) {
            Log.e(TAG, "Could not read module settings (module APK installed?)", e);
            return;
        }

        boolean wanted = state.getBoolean("enabled", true);
        int seq = state.getInt("test_sequence", 0);
        boolean manualTest = lastTestSequence >= 0 && seq != lastTestSequence;
        lastTestSequence = seq;

        if (wanted != enabled) {
            if (!wanted) {
                enabled = false;
                if (sm != null) sm.unregisterListener(this);
                rawListening = wakeListening = fallbackListening = false;
                near = false;
                bypass = false;
                pendingPowerChallenge = 0;
                verifiedPowerChallenge = 0;
                if (!testWindow) hide();
                publishNearGuard();
                Log.i(TAG, "Pocket protection disabled via UI");
            } else if (sm == null || (prox == null && rawProx == null && wakeupProx == null)) {
                Log.e(TAG, "No proximity sensor available");
            } else {
                enabled = true;
                syncDisplayState();
                updateSensors();
                publishNearGuard();
                Log.i(TAG, "Pocket protection enabled (RAW preferred)");
            }
        }

        if (enabled && near) schedule();

        Log.i(TAG, "Settings: enabled=" + enabled);

        if (manualTest) {
            Log.i(TAG, "Manual UI test requested");
            hide();
            showOverlay(true);
        }
    }

    private void onPowerExitRequest() {
        if (!enabled || !near || testWindow) return;
        try {
            long now = SystemClock.elapsedRealtime();
            long exit = Settings.Global.getLong(context.getContentResolver(),
                    PocketPowerHook.EXIT_REQUEST, 0);
            if (exit > 0 && exit <= now && now - exit < 8000) {
                Log.i(TAG, "Long POWER exit acknowledged: bypass until proximity FAR");
                bypass = true;
                publishNearGuard();
                hide();
            }
        } catch (Throwable t) {
            Log.e(TAG, "Power escape message failed", t);
        }
    }

    /** A fresh challenge proves the POWER hook is running in this system_server. */
    private boolean powerEscapeReady() {
        long now = SystemClock.elapsedRealtime();
        if (verifiedPowerChallenge != 0 && verifiedPowerChallenge == pendingPowerChallenge
                && now >= lastPowerProofAt && now - lastPowerProofAt < PROOF_TTL_MS) {
            return true;
        }
        if (pendingPowerChallenge != 0 && now >= lastPowerChallengeAt
                && now - lastPowerChallengeAt < CHALLENGE_RETRY_MS) return false;
        try {
            long challenge = (SystemClock.elapsedRealtimeNanos() ^ System.nanoTime()
                    ^ ((long) android.os.Process.myPid() << 32)) & Long.MAX_VALUE;
            if (challenge == 0) challenge = 1;
            pendingPowerChallenge = challenge;
            verifiedPowerChallenge = 0;
            lastPowerChallengeAt = now;
            if (!Settings.Global.putLong(context.getContentResolver(),
                    PocketPowerHook.READY_CHALLENGE, challenge)) {
                Log.e(TAG, "Could not send power escape challenge");
                return false;
            }
            Log.i(TAG, "Requested live system_server POWER escape proof");
            return false;
        } catch (Throwable t) {
            Log.e(TAG, "Power escape proof request failed; fail open", t);
            return false;
        }
    }

    private void onPowerProofReceived() {
        if (pendingPowerChallenge == 0) return;
        try {
            long response = Settings.Global.getLong(context.getContentResolver(),
                    PocketPowerHook.READY_ACK, 0);
            if (response != pendingPowerChallenge) return;
            verifiedPowerChallenge = response;
            lastPowerProofAt = SystemClock.elapsedRealtime();
            Log.i(TAG, "Live system_server POWER escape verified");
            if (enabled && near && !bypass) {
                publishNearGuard();
                schedule();
            }
        } catch (Throwable t) {
            Log.e(TAG, "Power escape acknowledgement invalid", t);
        }
    }

    /** Locate the device's TCS3720ALSPRX streaming sensor; 0x41b is a handle, not its type. */
    private static Sensor findRawProximity(SensorManager manager) {
        if (manager == null) return null;
        List<Sensor> sensors = manager.getSensorList(Sensor.TYPE_ALL);
        for (Sensor s : sensors) {
            if (s.getType() == RAW_TYPE
                    && s.getName().contains("TCS3720ALSPRX")
                    && !s.isWakeUpSensor()) return s;
        }
        return null;
    }

    private void syncDisplayState() {
        Display d = dm == null ? null : dm.getDisplay(Display.DEFAULT_DISPLAY);
        int state = d == null ? Display.STATE_UNKNOWN : d.getState();
        if (displayState == state) return;
        displayState = state;
        Log.i(TAG, "DISPLAY=" + state);
        updateSensors();
        if (near && !bypass) {
            // A preattached overlay does NOT itself turn on the panel.
            // Without this, SystemUI adds it after wake and lockscreen flashes.
            schedule();
        } else if (!testWindow) {
            hide();
        }
    }

    private void updateSensors() {
        if (sm == null) return;
        if (!enabled) {
            sm.unregisterListener(this);
            rawListening = wakeListening = fallbackListening = false;
            return;
        }
        boolean rawWanted = rawProx != null && displayState != Display.STATE_OFF
                && displayState != Display.STATE_DOZE_SUSPEND;
        if (rawListening && !rawWanted) {
            sm.unregisterListener(this, rawProx);
            rawListening = false;
            lastRawSample = 0;
        }
        if (rawWanted && !rawListening) {
            rawListening = sm.registerListener(this, rawProx, 200000, main);
        }
        // Wake-up proximity remains registered when fully OFF, allowing
        // movement in and out of a pocket without a continuously active CPU.
        if (wakeupProx != null && !wakeListening) {
            wakeListening = sm.registerListener(this, wakeupProx,
                    SensorManager.SENSOR_DELAY_NORMAL, main);
        }
        if (!rawListening && !wakeListening && prox != null && !fallbackListening) {
            fallbackListening = sm.registerListener(this, prox,
                    SensorManager.SENSOR_DELAY_NORMAL, main);
        } else if ((rawListening || wakeListening) && fallbackListening) {
            sm.unregisterListener(this, prox);
            fallbackListening = false;
        }
        Log.i(TAG, "SENSORS raw=" + rawListening + " wake=" + wakeListening
                + " fallback=" + fallbackListening);
    }

    /** Publish only a boot-scoped state, never a stale heartbeat across reboot. */
    private void publishNearGuard() {
        try {
            int boot = Settings.Global.getInt(context.getContentResolver(), BOOT_COUNT, -1);
            if (boot < 0) return;
            // A missing live power hook MUST NOT cause a persistent black screen.
            // Do not wait for the asynchronous challenge response before
            // publishing NEAR. system_server itself checks its hook readiness.
            boolean armed = enabled && near && !bypass;
            long encoded = ((long)boot << 1) | (armed ? 1L : 0L);
            if (encoded == lastGuardPublished) return;
            if (Settings.Global.putLong(context.getContentResolver(),
                    PocketPowerHook.PROX_GUARD, encoded)) {
                lastGuardPublished = encoded;
                Log.i(TAG, "Wake guard=" + armed + " boot=" + boot);
            }
        } catch (Throwable t) {
            Log.w(TAG, "Cannot publish pocket wake guard; fail open", t);
        }
    }

    @Override public void onSensorChanged(SensorEvent event) {
        if (!enabled || event.values == null || event.values.length == 0) return;
        boolean nextNear;
        boolean authoritativeRaw = false;
        if (rawListening && event.sensor == rawProx) {
            if (event.values.length < 2) return;
            float a = event.values[0], b = event.values[1];
            if (!Float.isFinite(a) || !Float.isFinite(b)) return;
            if (a >= 800f && b >= 800f) nextNear = true;
            else if (a <= 200f && b <= 200f) nextNear = false;
            else return; // hysteresis between thresholds
            lastRawSample = SystemClock.elapsedRealtime();
            authoritativeRaw = true;
        } else if ((wakeListening && event.sensor == wakeupProx)
                || (fallbackListening && event.sensor == prox)) {
            // Standard proximity may falsely return FAR immediately on wake.
            // Prefer a recent two-channel RAW reading whenever available.
            if (rawListening && SystemClock.elapsedRealtime() - lastRawSample
                    <= RAW_PRIORITY_MS) return;
            nextNear = event.values[0] < event.sensor.getMaximumRange();
        } else return;
        lastSensorEventElapsed = SystemClock.elapsedRealtime();
        if (nextNear) {
            main.removeCallbacks(confirmFar);
            farCandidateAt = 0;
            if (!near) {
                near = true;
                Log.i(TAG, "Proximity NEAR via " +
                        (authoritativeRaw ? "RAW" : "wakeup/fallback"));
                // Gate POWER wake immediately; handshake creates overlay safely.
                publishNearGuard();
                powerEscapeReady();
                schedule();
            }
        } else if (near && farCandidateAt == 0) {
            farCandidateAt = SystemClock.elapsedRealtime();
            main.postDelayed(confirmFar, FAR_CONFIRM_MS);
        }
    }
    @Override public void onAccuracyChanged(Sensor sensor, int accuracy) {}

    boolean suppressPulse() {
        return enabled && near && !bypass;
    }

    private void schedule() {
        if (!enabled || !near || bypass || pending || overlay != null) return;
        pending = true;
        main.postDelayed(showRunnable, CONFIRM_MS);
    }

    private void maybeShow() {
        if (!enabled || !near || bypass || overlay != null) return;
        // Pre-arm while the panel is OFF. The overlay has no TURN_SCREEN_ON
        // or KEEP_SCREEN_ON flags, so it remains invisible until the user
        // explicitly presses POWER. Its first visible frame is the pocket UI.
        if (!powerEscapeReady()) return;
        publishNearGuard();
        showOverlay(false);
    }

    private void showOverlay(boolean test) {
        if (overlay != null || wm == null) return;
        try {
            Context resources = context.createPackageContext(
                    "org.pocketguard.lsp", Context.CONTEXT_IGNORE_SECURITY);
            View content = LayoutInflater.from(resources).inflate(R.layout.pocket_overlay, null);
            TouchBlocker view = new TouchBlocker(context);
            view.addView(content, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT));
            view.setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
            blockedTouchEvents = 0;
            int flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                    | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                    | WindowManager.LayoutParams.FLAG_FULLSCREEN
                    | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                    // The power key must still be handled by system_server.
                    | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;

            Throwable last = null;
            for (int type : new int[]{
                    TYPE_NAVIGATION_BAR_PANEL, TYPE_SYSTEM_ERROR}) {
                WindowManager.LayoutParams p = new WindowManager.LayoutParams(
                        WindowManager.LayoutParams.MATCH_PARENT,
                        WindowManager.LayoutParams.MATCH_PARENT,
                        type, flags, PixelFormat.TRANSLUCENT);
                p.setTitle("PocketMode:SystemUI");
                // Treat touches on this full-screen pocket overlay as NOT user activity,
                // so the device's existing screen-off timer keeps counting down.
                // This is a hidden but public-field WindowManager.LayoutParams member;
                // writing the field is done inside the privileged SystemUI process.
                try {
                    XposedHelpers.setIntField(p, "inputFeatures",
                            INPUT_FEATURE_DISABLE_USER_ACTIVITY);
                } catch (Throwable e) {
                    Log.e(TAG, "Cannot disable touch user-activity; fail open", e);
                    if (!test) bypass = true;
                    return;
                }
                p.layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
                try {
                    wm.addView(view, p);
                    overlay = view;
                    overlayShownElapsed = SystemClock.elapsedRealtime();
                    testWindow = test;
                    // These window layers sit above the navigation bar and
                    // notification shade. Independently disable shade expansion.
                    restrictSystemBars();
                    main.removeCallbacks(safety);
                    if (test) {
                        main.postDelayed(safety, TEST_MS);
                    } else {
                        heartbeat.run();  // no automatic exit after 15 seconds
                    }
                    Log.i(TAG, "Overlay shown; type=" + type + ", test=" + test +
                            ", touchDoesNotResetScreenTimeout=true");
                    return;
                } catch (Throwable e) {
                    last = e;
                    Log.w(TAG, "Window type " + type + " rejected: " + e);
                    if (view.getParent() != null) break;
                }
            }
            if (!test) bypass = true;
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
        main.removeCallbacks(heartbeat);
        main.removeCallbacks(confirmFar);
        restoreSystemBars();
        try {
            Settings.Global.putLong(context.getContentResolver(),
                    PocketPowerHook.ACTIVE_HEARTBEAT, 0L);
        } catch (Throwable t) {
            Log.w(TAG, "Could not clear pocket heartbeat", t);
        }
        if (overlay != null) {
            try { wm.removeViewImmediate(overlay); }
            catch (Throwable e) { Log.e(TAG, "Overlay removal failed", e); }
            overlay = null;
        }
        testWindow = false;
    }
}
