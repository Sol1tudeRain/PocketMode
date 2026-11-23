package org.pocketguard.lsp;

import android.app.ActivityManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
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
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import de.robv.android.xposed.XposedHelpers;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** SystemUI-side controller. Reads default-on settings from the module APK. */
final class PocketController implements SensorEventListener {
    private static final String TAG = "PocketGuardLSP";
    private static final Uri CONFIG_URI =
            Uri.parse("content://org.pocketguard.lsp.config/settings");
    private static final long CONFIRM_MS = 120;
    private static final long FOREGROUND_POLL_MS = 700;
    private static final long HEARTBEAT_MS = 3000;
    private static final int INPUT_FEATURE_DISABLE_USER_ACTIVITY = 2;
    private static final long TEST_MS = 5000;
    private static final int TYPE_SECURE_SYSTEM_OVERLAY = 2015;
    private static final int TYPE_STATUS_BAR_SUB_PANEL = 2017;
    private static final int TYPE_APPLICATION_OVERLAY = 2038;

    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final SensorManager sm;
    private final Sensor prox;
    private final WindowManager wm;
    private final PowerManager pm;
    private final ActivityManager am;

    private volatile boolean enabled;
    private volatile boolean near;
    private volatile boolean bypass;
    private volatile boolean foregroundWhitelisted;
    private final Set<String> whitelist = new HashSet<>();
    private boolean pending;
    private boolean testWindow;
    private View overlay;
    private int lastTestSequence = -1;
    private String lastTopPackage = "";
    private boolean warnedTaskFailure;

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

    private final Runnable foregroundPoll = new Runnable() {
        @Override public void run() {
            if (!enabled || !near) return;
            updateForeground();
            if (foregroundWhitelisted) {
                if (!testWindow) hide();
            } else {
                schedule();
            }
            main.postDelayed(this, FOREGROUND_POLL_MS);
        }
    };

    PocketController(Context ctx) {
        context = ctx;
        sm = (SensorManager) ctx.getSystemService(Context.SENSOR_SERVICE);
        prox = sm == null ? null : sm.getDefaultSensor(Sensor.TYPE_PROXIMITY);
        wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
        pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
        am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
    }

    void init() {
        Log.i(TAG, "Controller initializing; proximity=" +
                (prox == null ? "ABSENT" : prox.getName()));

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
                        if (!testWindow) hide();
                    } else if (Intent.ACTION_USER_UNLOCKED.equals(action)) {
                        refreshConfig();
                    } else if (Intent.ACTION_SCREEN_ON.equals(action)
                            || Intent.ACTION_USER_PRESENT.equals(action)) {
                        if (near) {
                            updateForeground();
                            schedule();
                        }
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
        ArrayListHolder holder = new ArrayListHolder(state.getStringArrayList("whitelist"));
        whitelist.clear();
        whitelist.addAll(holder.values);

        int seq = state.getInt("test_sequence", 0);
        boolean manualTest = lastTestSequence >= 0 && seq != lastTestSequence;
        lastTestSequence = seq;

        if (wanted != enabled) {
            if (!wanted) {
                enabled = false;
                if (sm != null) sm.unregisterListener(this);
                near = false;
                bypass = false;
                main.removeCallbacks(foregroundPoll);
                if (!testWindow) hide();
                Log.i(TAG, "Pocket protection disabled via UI");
            } else if (sm == null || prox == null) {
                Log.e(TAG, "No proximity sensor available");
            } else {
                enabled = sm.registerListener(
                        this, prox, SensorManager.SENSOR_DELAY_UI, main);
                Log.i(TAG, "Pocket protection enabled=" + enabled + " (default ON)");
            }
        }

        if (enabled && near) {
            updateForeground();
            if (foregroundWhitelisted && !testWindow) hide();
            else schedule();
            startForegroundPoll();
        }

        Log.i(TAG, "Settings: enabled=" + enabled +
                ", whitelistCount=" + whitelist.size() +
                ", foreground=" + lastTopPackage +
                ", excluded=" + foregroundWhitelisted);

        if (manualTest) {
            Log.i(TAG, "Manual UI test requested");
            hide();
            showOverlay(true);
        }
    }

    private void onPowerExitRequest() {
        if (overlay == null || testWindow) return;
        try {
            long now = SystemClock.elapsedRealtime();
            long exit = Settings.Global.getLong(context.getContentResolver(),
                    PocketPowerHook.EXIT_REQUEST, 0);
            if (exit > 0 && exit <= now && now - exit < 8000) {
                Log.i(TAG, "Long POWER exit acknowledged: bypass until proximity FAR");
                bypass = true;
                hide();
            }
        } catch (Throwable t) {
            Log.e(TAG, "Power escape message failed", t);
        }
    }

    private boolean powerEscapeReady() {
        try {
            int boot = Settings.Global.getInt(
                    context.getContentResolver(), Settings.Global.BOOT_COUNT, -1);
            int readyBoot = Settings.Global.getInt(
                    context.getContentResolver(), PocketPowerHook.READY_BOOT, -2);
            long ready = Settings.Global.getLong(
                    context.getContentResolver(), PocketPowerHook.READY_ELAPSED, 0);
            long now = SystemClock.elapsedRealtime();
            boolean ok = boot >= 0 && readyBoot == boot && ready > 0 && ready <= now;
            if (!ok) {
                Log.e(TAG, "No confirmed system_server POWER escape, refusing indefinite overlay");
            }
            return ok;
        } catch (Throwable t) {
            Log.e(TAG, "POWER escape readiness check failed", t);
            return false;
        }
    }

    // Keep the provider's returned list independent from Bundle internals.
    private static final class ArrayListHolder {
        final Set<String> values = new HashSet<>();
        ArrayListHolder(java.util.ArrayList<String> list) {
            if (list != null) values.addAll(list);
        }
    }

    private void startForegroundPoll() {
        main.removeCallbacks(foregroundPoll);
        if (enabled && near) main.post(foregroundPoll);
    }

    /**
     * SystemUI is privileged and can generally inspect the resumed top task.
     * If the ROM denies task inspection, log it and continue normal protection.
     */
    private void updateForeground() {
        String top = null;
        try {
            if (am != null) {
                List<ActivityManager.RunningTaskInfo> tasks = am.getRunningTasks(1);
                if (tasks != null && !tasks.isEmpty()) {
                    ComponentName activity = tasks.get(0).topActivity;
                    if (activity != null) top = activity.getPackageName();
                }
            }
        } catch (Throwable e) {
            if (!warnedTaskFailure) Log.e(TAG, "Foreground task lookup failed", e);
            warnedTaskFailure = true;
        }

        if (top == null) {
            if (!warnedTaskFailure) {
                Log.w(TAG, "Foreground task unavailable; whitelist detection pending");
                warnedTaskFailure = true;
            }
            // Avoid reclassifying unknown foreground activity as whitelisted.
            foregroundWhitelisted = false;
            return;
        }

        boolean excluded = "org.pocketguard.lsp".equals(top) || whitelist.contains(top);
        if (!top.equals(lastTopPackage) || excluded != foregroundWhitelisted) {
            Log.i(TAG, "Foreground=" + top + ", whitelisted=" + excluded);
            lastTopPackage = top;
        }
        foregroundWhitelisted = excluded;
    }

    @Override public void onSensorChanged(SensorEvent event) {
        if (!enabled || event.sensor.getType() != Sensor.TYPE_PROXIMITY
                || event.values == null || event.values.length == 0) return;
        boolean newNear = event.values[0] < event.sensor.getMaximumRange();
        if (newNear == near) return;
        near = newNear;
        Log.i(TAG, "Proximity NEAR=" + near + " distance=" + event.values[0]);

        if (!near) {
            bypass = false;
            foregroundWhitelisted = false;
            main.removeCallbacks(foregroundPoll);
            if (!testWindow) hide();
        } else {
            startForegroundPoll();
        }
    }
    @Override public void onAccuracyChanged(Sensor sensor, int accuracy) {}

    boolean suppressPulse() {
        return enabled && near && !bypass && !foregroundWhitelisted;
    }

    private void schedule() {
        if (!enabled || !near || bypass || foregroundWhitelisted ||
                pending || overlay != null) return;
        pending = true;
        main.postDelayed(showRunnable, CONFIRM_MS);
    }

    private void maybeShow() {
        if (!enabled || !near || bypass || overlay != null) return;
        updateForeground();
        if (foregroundWhitelisted || pm == null || !pm.isInteractive()) return;
        if (!powerEscapeReady()) return;
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
                    TYPE_APPLICATION_OVERLAY}) {
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
                    testWindow = test;
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
