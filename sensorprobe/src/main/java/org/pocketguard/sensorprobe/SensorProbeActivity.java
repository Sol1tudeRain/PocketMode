package org.pocketguard.sensorprobe;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public final class SensorProbeActivity extends Activity {
    private static final int BG = Color.rgb(15, 20, 31);
    private static final int TEXT = Color.rgb(241, 245, 249);
    private static final int MUTED = Color.rgb(173, 192, 210);
    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView result;
    private TextView running;
    private Button startButton;
    private Button stopButton;

    private final Runnable update = new Runnable() {
        @Override public void run() {
            if (result != null) result.setText(SensorProbeService.snapshot());
            boolean active = SensorProbeService.isRunning();
            if (running != null)
                running.setText(active ? "● 正在采集（可锁屏 / 切换 AOD）" : "○ 未在采集");
            if (startButton != null) startButton.setEnabled(!active);
            if (stopButton != null) stopButton.setEnabled(active);
            handler.postDelayed(this, 700L);
        }
    };

    private int dp(int px) {
        return (int) (getResources().getDisplayMetrics().density * px + .5f);
    }

    private TextView text(String value, int size, int color) {
        TextView v = new TextView(this);
        v.setText(value);
        v.setTextSize(size);
        v.setTextColor(color);
        return v;
    }

    private LinearLayout row(LinearLayout parent) {
        LinearLayout line = new LinearLayout(this);
        line.setOrientation(LinearLayout.HORIZONTAL);
        parent.addView(line, new LinearLayout.LayoutParams(-1, -2));
        return line;
    }

    private Button button(LinearLayout parent, String title, View.OnClickListener click) {
        Button b = new Button(this);
        b.setText(title);
        b.setTextAllCaps(false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(52), 1f);
        lp.setMargins(dp(2), dp(3), dp(2), dp(3));
        parent.addView(b, lp);
        b.setOnClickListener(click);
        return b;
    }

    private void call(String action) {
        Intent cmd = new Intent(this, SensorProbeService.class);
        cmd.setAction(action);
        try {
            if (SensorProbeService.ACTION_START.equals(action) && Build.VERSION.SDK_INT >= 26) {
                startForegroundService(cmd);
            } else {
                startService(cmd);
            }
        } catch (Throwable t) {
            Toast.makeText(this, "操作失败: " + t, Toast.LENGTH_LONG).show();
        }
    }

    private void mark(String label) {
        if (!SensorProbeService.isRunning()) {
            Toast.makeText(this, "请先开始记录", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent cmd = new Intent(this, SensorProbeService.class);
        cmd.setAction(SensorProbeService.ACTION_MARK);
        cmd.putExtra(SensorProbeService.EXTRA_MARK, label);
        startService(cmd);
        Toast.makeText(this, "已添加标记: " + label, Toast.LENGTH_SHORT).show();
    }

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        LinearLayout all = new LinearLayout(this);
        all.setOrientation(LinearLayout.VERTICAL);
        all.setBackgroundColor(BG);
        all.setPadding(dp(14), dp(14), dp(14), dp(12));
        setContentView(all);

        TextView title = text("距离传感器诊断", 24, TEXT);
        title.setTypeface(null, Typeface.BOLD);
        all.addView(title);
        TextView explain = text(
                "同时记录标准距离传感器 Wakeup、Non-wakeup 与"
                + " TCS3720ALSPRX 厂商连续采样通道。无需 Root 或 LSPosed。\n"
                + "开启记录后，在遮挡状态下熄屏、按电源键亮屏。"
                + "采样会在显示屏 OFF 时停止、ON/AOD 时恢复；前台服务持续保存日志。",
                13, MUTED);
        explain.setPadding(0, dp(10), 0, dp(12));
        all.addView(explain);

        running = text("○ 未在采集", 15, TEXT);
        running.setPadding(0, dp(8), 0, dp(8));
        all.addView(running);

        LinearLayout control = row(all);
        startButton = button(control, "开始记录", v -> {
            if (Build.VERSION.SDK_INT >= 33
                    && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[] {Manifest.permission.POST_NOTIFICATIONS},
                        110);
            }
            call(SensorProbeService.ACTION_START);
        });
        stopButton = button(control, "停止记录", v -> call(SensorProbeService.ACTION_STOP));

        LinearLayout markers = row(all);
        button(markers, "标记：无遮挡", v -> mark("UNCOVERED"));
        button(markers, "标记：遮挡", v -> mark("COVERED"));

        LinearLayout actions = row(all);
        button(actions, "复制全部日志", v -> {
            String data = SensorProbeService.fullLog();
            if (data.length() == 0) {
                Toast.makeText(this, "还没有日志", Toast.LENGTH_SHORT).show();
                return;
            }
            ClipboardManager cb = (ClipboardManager)
                    getSystemService(Context.CLIPBOARD_SERVICE);
            cb.setPrimaryClip(ClipData.newPlainText("SensorProbe", data));
            Toast.makeText(this, "日志已复制，共 " + data.length() + " 字符",
                    Toast.LENGTH_LONG).show();
        });
        button(actions, "清空日志", v -> {
            if (SensorProbeService.isRunning()) {
                call(SensorProbeService.ACTION_CLEAR);
            } else {
                Toast.makeText(this, "请先开始记录", Toast.LENGTH_SHORT).show();
            }
        });

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        all.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));
        result = text("等待开始记录...", 12, TEXT);
        result.setTypeface(Typeface.MONOSPACE);
        result.setTextIsSelectable(true);
        result.setPadding(dp(8), dp(12), dp(8), dp(18));
        scroll.addView(result);
        // Intentionally do not set FLAG_KEEP_SCREEN_ON: screen-off/wake is
        // precisely what we're trying to measure.
    }

    @Override public void onResume() {
        super.onResume();
        handler.removeCallbacks(update);
        handler.post(update);
    }

    @Override public void onPause() {
        handler.removeCallbacks(update);
        super.onPause();
    }
}
