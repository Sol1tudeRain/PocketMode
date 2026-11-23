package org.pocketguard.sensorprobe;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.SystemClock;
import android.view.Display;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Standalone, user-controlled diagnostics only. No LSPosed, root, network,
 * or external storage. Sensors are registered only when the physical panel
 * reports ON / DOZE / DOZE_SUSPEND and are unregistered when fully OFF.
 *
 * A foreground service is needed because lockscreen/AOD transitions happen
 * while the activity is not resumed. It stops on the user's request.
 */
public final class SensorProbeService extends Service {
    public static final String ACTION_START = "org.pocketguard.sensorprobe.START";
    public static final String ACTION_STOP = "org.pocketguard.sensorprobe.STOP";
    public static final String ACTION_MARK = "org.pocketguard.sensorprobe.MARK";
    public static final String ACTION_CLEAR = "org.pocketguard.sensorprobe.CLEAR";
    public static final String EXTRA_MARK = "mark";

    private static final String CHANNEL = "sensorprobe_session";
    private static final int NOTIFICATION_ID = 51;
    private static final int VENDOR_RAW_TYPE = 33171005;
    private static final int SAMPLE_INTERVAL_US = 200000; // request 5Hz
    private static final int MAX_LOG_CHARS = 220000;
    private static final Object DATA_LOCK = new Object();
    private static final StringBuilder LOG = new StringBuilder();
    private static final Map<String, String> LAST = new LinkedHashMap<>();
    private static final Map<String, Integer> COUNTS = new LinkedHashMap<>();
    private static volatile boolean running;
    private static volatile int displayState = -1;
    private static volatile String status = "未开始";

    private HandlerThread worker;
    private Handler sensorHandler;
    private SensorManager sensorManager;
    private DisplayManager displayManager;
    private final List<Sensor> probes = new ArrayList<>();
    private boolean listening;
    private int currentState = -1;

    private final DisplayManager.DisplayListener displayListener =
            new DisplayManager.DisplayListener() {
                @Override public void onDisplayAdded(int id) {
                    if (id == Display.DEFAULT_DISPLAY) checkDisplay();
                }
                @Override public void onDisplayRemoved(int id) {
                    if (id == Display.DEFAULT_DISPLAY) setDisplay(Display.STATE_OFF);
                }
                @Override public void onDisplayChanged(int id) {
                    if (id == Display.DEFAULT_DISPLAY) checkDisplay();
                }
            };

    private final SensorEventListener listener = new SensorEventListener() {
        @Override public void onSensorChanged(SensorEvent event) {
            if (!listening) return;
            final String name = event.sensor.getName();
            final String key = shortName(event.sensor);
            final float[] values = event.values == null ? new float[0]
                    : Arrays.copyOf(event.values, event.values.length);
            final long age = (SystemClock.elapsedRealtimeNanos() - event.timestamp)
                    / 1000000L;
            final String val = Arrays.toString(values);
            synchronized (DATA_LOCK) {
                COUNTS.put(key, COUNTS.getOrDefault(key, 0) + 1);
                LAST.put(key, val + " | 事件延迟 " + age + "ms");
            }
            log("SAMPLE " + key + " " + val + " eventAge=" + age
                    + "ms display=" + stateName(currentState)
                    + " source=" + name);
        }
        @Override public void onAccuracyChanged(Sensor sensor, int accuracy) { }
    };

    public static boolean isRunning() { return running; }

    public static String snapshot() {
        StringBuilder out = new StringBuilder();
        out.append("记录状态：").append(status).append("\n");
        out.append("显示屏状态：").append(stateName(displayState)).append("\n");
        synchronized (DATA_LOCK) {
            if (LAST.isEmpty()) {
                out.append("\n等待传感器数据...\n");
            } else {
                for (Map.Entry<String, String> e : LAST.entrySet()) {
                    out.append("\n").append(e.getKey()).append(" (")
                            .append(COUNTS.getOrDefault(e.getKey(), 0))
                            .append(" 次)\n").append(e.getValue()).append("\n");
                }
            }
            out.append("\n最近记录：\n");
            String text = LOG.toString();
            int pos = Math.max(0, text.length() - 3500);
            if (pos != 0) {
                int next = text.indexOf('\n', pos);
                if (next >= 0) pos = next + 1;
            }
            out.append(text.substring(pos));
        }
        return out.toString();
    }

    public static String fullLog() {
        synchronized (DATA_LOCK) {
            return LOG.toString();
        }
    }

    private static String stateName(int state) {
        switch (state) {
            case Display.STATE_ON: return "ON(2)";
            case Display.STATE_OFF: return "OFF(1)";
            case Display.STATE_DOZE: return "DOZE/AOD(3)";
            case Display.STATE_DOZE_SUSPEND: return "DOZE_SUSPEND/AOD(4)";
            case Display.STATE_ON_SUSPEND: return "ON_SUSPEND(6)";
            default: return "UNKNOWN(" + state + ")";
        }
    }

    private static String shortName(Sensor s) {
        if (s.getType() == VENDOR_RAW_TYPE
                || s.getName().contains("Proximity Sensor Strm")) return "RAW 0x41b";
        if (s.getType() == Sensor.TYPE_PROXIMITY) {
            return s.isWakeUpSensor() ? "标准 Wakeup 0x52"
                    : "标准 Non-wakeup 0x51";
        }
        return s.getName();
    }

    private static void log(String value) {
        String time = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
                .format(new Date());
        synchronized (DATA_LOCK) {
            LOG.append(time).append(" ").append(value).append('\n');
            if (LOG.length() > MAX_LOG_CHARS) {
                LOG.delete(0, LOG.length() - 170000);
                int next = LOG.indexOf("\n");
                if (next >= 0) LOG.delete(0, next + 1);
            }
        }
    }

    @Override public void onCreate() {
        super.onCreate();
        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        displayManager = (DisplayManager) getSystemService(Context.DISPLAY_SERVICE);
        worker = new HandlerThread("SensorProbeWorker");
        worker.start();
        sensorHandler = new Handler(worker.getLooper());
        NotificationManager mgr = getSystemService(NotificationManager.class);
        if (mgr != null) {
            NotificationChannel channel = new NotificationChannel(CHANNEL,
                    "距离传感器诊断", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("只有主动测试时才持续显示");
            mgr.createNotificationChannel(channel);
        }

        if (sensorManager != null) {
            for (Sensor s : sensorManager.getSensorList(Sensor.TYPE_ALL)) {
                if (s.getType() == Sensor.TYPE_PROXIMITY
                        || s.getType() == VENDOR_RAW_TYPE
                        || s.getName().contains("TCS3720ALSPRX Proximity Sensor Strm")) {
                    probes.add(s);
                }
            }
        }
        synchronized (DATA_LOCK) {
            LOG.setLength(0);
            LAST.clear();
            COUNTS.clear();
            for (Sensor s : probes) {
                String key = shortName(s);
                LAST.put(key, "尚未收到读数");
                COUNTS.put(key, 0);
                log("FOUND " + key + " sensorName=" + s.getName()
                        + " sensorType=" + s.getType()
                        + " wakeup=" + s.isWakeUpSensor()
                        + " maxRange=" + s.getMaximumRange());
            }
        }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            log("USER STOP");
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_CLEAR.equals(action)) {
            synchronized (DATA_LOCK) {
                LOG.setLength(0);
                LAST.replaceAll((k, v) -> "已清空日志；等待新读数");
                COUNTS.replaceAll((k, v) -> 0);
            }
            log("USER RESET LOG");
            return START_NOT_STICKY;
        }
        if (ACTION_MARK.equals(action)) {
            String mark = intent.getStringExtra(EXTRA_MARK);
            log("========== USER MARK: " + (mark == null ? "marker" : mark) + " ==========");
            return START_NOT_STICKY;
        }
        startForeground(NOTIFICATION_ID, makeNotification());
        if (!running) {
            running = true;
            status = "运行中（息屏自动停止传感器，亮屏/AOD恢复）";
            log("START diagnostic session");
            if (displayManager != null) {
                displayManager.registerDisplayListener(displayListener, sensorHandler);
                sensorHandler.post(this::checkDisplay);
            } else {
                log("ERROR DisplayManager unavailable");
            }
        }
        return START_NOT_STICKY;
    }

    private Notification makeNotification() {
        Intent open = new Intent(this, SensorProbeActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this, CHANNEL)
                .setContentTitle("距离传感器诊断正在运行")
                .setContentText("亮屏/AOD 记录三条传感器，完全熄屏时停止采样")
                .setSmallIcon(android.R.drawable.ic_menu_info_details)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
    }

    private void checkDisplay() {
        if (displayManager == null) return;
        Display display = displayManager.getDisplay(Display.DEFAULT_DISPLAY);
        setDisplay(display == null ? Display.STATE_OFF : display.getState());
    }

    private void setDisplay(int newState) {
        if (newState == currentState) return;
        currentState = newState;
        displayState = newState;
        log("DISPLAY " + stateName(newState));
        boolean lit = newState == Display.STATE_ON || newState == Display.STATE_DOZE
                || newState == Display.STATE_DOZE_SUSPEND;
        if (lit) {
            startSensors();
        } else {
            stopSensors();
        }
    }

    private void startSensors() {
        if (listening || sensorManager == null) return;
        listening = true;
        for (Sensor s : probes) {
            try {
                boolean ok = sensorManager.registerListener(listener, s,
                        SAMPLE_INTERVAL_US, sensorHandler);
                log("REGISTER " + shortName(s) + " ok=" + ok);
            } catch (Throwable t) {
                log("REGISTER ERROR " + shortName(s) + ": " + t);
            }
        }
    }

    private void stopSensors() {
        if (!listening || sensorManager == null) return;
        listening = false;
        try {
            sensorManager.unregisterListener(listener);
            log("UNREGISTER all sensors on display OFF");
        } catch (Throwable t) {
            log("UNREGISTER ERROR: " + t);
        }
    }

    @Override public void onDestroy() {
        running = false;
        status = "已停止";
        if (displayManager != null) {
            try { displayManager.unregisterDisplayListener(displayListener); }
            catch (Throwable ignored) { }
        }
        stopSensors();
        if (worker != null) worker.quitSafely();
        log("END diagnostic session");
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
