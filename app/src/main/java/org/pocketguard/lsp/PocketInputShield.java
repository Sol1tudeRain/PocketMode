/*
 * System gesture shield for Android 17.
 *
 * InputManager.monitorGestureInput + InputMonitor.pilferPointers are existing
 * Android framework APIs (hidden from SDK, not from system_server). When a
 * pocket overlay is visible, grab touchscreen streams at ACTION_DOWN so
 * separate SystemUI edge-back and Quickstep HOME/RECENTS input monitors get
 * CANCEL, rather than merely swallowing an app View's touch callbacks.
 *
 * The monitor exists ONLY while the overlay is being shown. All blocking
 * operations and event callbacks run on a dedicated input thread.
 */
package org.pocketguard.lsp;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Log;
import android.view.InputDevice;
import android.view.MotionEvent;

import java.util.function.BooleanSupplier;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

final class PocketInputShield {
    private static final String TAG = "PocketModeInput";
    private final Context context;
    private final Handler ui;
    private final BooleanSupplier overlayVisible;
    private final HandlerThread inputThread = new HandlerThread("PocketModeGestureShield");
    private Handler input;
    private volatile Object monitor;
    private volatile Object receiver;
    private boolean hooked;

    PocketInputShield(Context context, Handler ui, BooleanSupplier overlayVisible) {
        this.context = context;
        this.ui = ui;
        this.overlayVisible = overlayVisible;
    }

    void init() {
        inputThread.start();
        input = new Handler(inputThread.getLooper());
        try {
            // Hook the base class once. Our receiver is an exact instance of
            // InputEventReceiver, whose default onInputEvent finishes events.
            // Other input receivers are never modified by this callback.
            Class<?> cls = XposedHelpers.findClass(
                    "android.view.InputEventReceiver", null);
            if (XposedBridge.hookAllMethods(cls, "onInputEvent", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (p.thisObject != receiver || !overlayVisible.getAsBoolean()
                            || p.args.length == 0 || !(p.args[0] instanceof MotionEvent))
                        return;
                    MotionEvent event = (MotionEvent) p.args[0];
                    if ((event.getSource() & InputDevice.SOURCE_TOUCHSCREEN)
                            != InputDevice.SOURCE_TOUCHSCREEN) return;
                    if (event.getActionMasked() != MotionEvent.ACTION_DOWN) return;
                    Object currentMonitor = monitor;
                    if (currentMonitor == null) return;
                    try {
                        XposedHelpers.callMethod(currentMonitor, "pilferPointers");
                        // Original InputEventReceiver.onInputEvent() must run:
                        // it acknowledges and releases the native input event.
                    } catch (Throwable t) {
                        Log.e(TAG, "Could not pilfer navigation gesture", t);
                    }
                }
            }).isEmpty()) {
                Log.e(TAG, "InputEventReceiver hook unavailable");
                return;
            }
            hooked = true;
            Log.i(TAG, "Global gesture monitor hook installed");
        } catch (Throwable t) {
            Log.e(TAG, "Cannot hook input event receiver", t);
        }
    }

    boolean isInstalled() { return hooked; }

    /** Callback is always posted to the controller's UI thread. */
    void prepare(Runnable onReady, Runnable onFailure) {
        if (!hooked || input == null) {
            ui.post(onFailure);
            return;
        }
        input.post(() -> {
            try {
                if (receiver == null || monitor == null) {
                    Object manager = context.getSystemService(Context.INPUT_SERVICE);
                    if (manager == null)
                        throw new IllegalStateException("InputManager missing");
                    Object created = XposedHelpers.callMethod(manager,
                            "monitorGestureInput", "PocketModeGestureShield", 0);
                    if (created == null)
                        throw new IllegalStateException("monitorGestureInput returned null");
                    Object createdReceiver = null;
                    try {
                        Object channel = XposedHelpers.callMethod(created, "getInputChannel");
                        Class<?> receiverClass = XposedHelpers.findClass(
                                "android.view.InputEventReceiver", null);
                        createdReceiver = XposedHelpers.newInstance(
                                receiverClass, channel, Looper.myLooper());
                        monitor = created;
                        receiver = createdReceiver;
                        Log.i(TAG, "Native gesture input monitor armed");
                    } catch (Throwable t) {
                        if (createdReceiver != null) {
                            try { XposedHelpers.callMethod(createdReceiver, "dispose"); }
                            catch (Throwable ignored) { }
                        }
                        try { XposedHelpers.callMethod(created, "dispose"); }
                        catch (Throwable ignored) { }
                        throw t;
                    }
                }
                ui.post(onReady);
            } catch (Throwable t) {
                Log.e(TAG, "Gesture monitor registration failed; do not show overlay", t);
                closeOnInputThread();
                ui.post(onFailure);
            }
        });
    }

    void stop() {
        if (input != null) input.post(this::closeOnInputThread);
    }

    private void closeOnInputThread() {
        Object oldReceiver = receiver;
        Object oldMonitor = monitor;
        receiver = null;
        monitor = null;
        if (oldReceiver != null) {
            try { XposedHelpers.callMethod(oldReceiver, "dispose"); }
            catch (Throwable t) { Log.w(TAG, "Receiver dispose failed", t); }
        }
        if (oldMonitor != null) {
            try { XposedHelpers.callMethod(oldMonitor, "dispose"); }
            catch (Throwable t) { Log.w(TAG, "Monitor dispose failed", t); }
        }
    }
}
