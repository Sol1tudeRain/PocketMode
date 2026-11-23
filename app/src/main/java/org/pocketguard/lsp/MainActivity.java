package org.pocketguard.lsp;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

/** One-switch settings UI, all apps receive the same pocket protection. */
public final class MainActivity extends Activity {
    private static final int BG = Color.rgb(11, 15, 22);
    private static final int SURFACE = Color.rgb(25, 31, 43);
    private static final int TEXT = Color.WHITE;
    private static final int MUTED = Color.rgb(178, 190, 208);
    private SharedPreferences prefs;

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + .5f);
    }

    private TextView label(String txt, int sp, int color, boolean bold) {
        TextView out = new TextView(this);
        out.setText(txt);
        out.setTextSize(sp);
        out.setTextColor(color);
        if (bold) out.setTypeface(null, Typeface.BOLD);
        return out;
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = ConfigProvider.preferences(this);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setPadding(dp(20), dp(22), dp(20), dp(12));
        if (Build.VERSION.SDK_INT >= 30) {
            root.setOnApplyWindowInsetsListener((view, insets) -> {
                android.graphics.Insets bars = insets.getInsets(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                view.setPadding(dp(20) + bars.left, dp(22) + bars.top,
                        dp(20) + bars.right, dp(12) + bars.bottom);
                return insets;
            });
        }
        setContentView(root);

        root.addView(label("口袋模式", 27, TEXT, true));
        TextView description = label("默认开启 · 全应用生效", 13, MUTED, false);
        description.setPadding(0, dp(6), 0, dp(18));
        root.addView(description);

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(16), dp(18), dp(16), dp(18));
        panel.setBackgroundColor(SURFACE);
        root.addView(panel, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        Switch enabled = new Switch(this);
        enabled.setText("启用口袋保护");
        enabled.setTextColor(TEXT);
        enabled.setTextSize(17);
        enabled.setChecked(prefs.getBoolean(ConfigProvider.KEY_ENABLED, true));
        panel.addView(enabled);
        TextView hint = label("遮挡距离传感器时拦截触控。移开遮挡或长按电源键 " + PocketPowerHook.HOLD_SECONDS + " 秒退出。", 13, MUTED, false);
        hint.setPadding(0, dp(12), 0, 0);
        panel.addView(hint);
        enabled.setOnCheckedChangeListener((view, value) -> {
            prefs.edit().putBoolean(ConfigProvider.KEY_ENABLED, value).apply();
            ConfigProvider.notifyUpdated(this);
        });

        TextView footer = label("遮挡时短按电源键显示口袋模式。LSPosed 仅勾选 android，安装后重启。", 12, MUTED, false);
        footer.setPadding(0, dp(22), 0, 0);
        root.addView(footer);
    }
}
