package org.pocketguard.lsp;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.Switch;
import android.widget.TextView;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Launcher activity: local switch, searchable app whitelist, and a 5s overlay test. */
public final class MainActivity extends Activity {
    private static final int BG = Color.rgb(11, 15, 22);
    private static final int SURFACE = Color.rgb(25, 31, 43);
    private static final int TEXT = Color.WHITE;
    private static final int MUTED = Color.rgb(178, 190, 208);
    private static final int ACCENT = Color.rgb(146, 213, 236);

    private SharedPreferences prefs;
    private PackageManager pm;
    private final Set<String> whitelist = new HashSet<>();
    private final List<AppEntry> apps = new ArrayList<>();
    private final List<AppEntry> visibleApps = new ArrayList<>();
    private AppAdapter adapter;
    private TextView count;
    private String filter = "";

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + .5f);
    }

    private TextView text(String value, int sp, int color, boolean bold) {
        TextView tv = new TextView(this);
        tv.setText(value);
        tv.setTextSize(sp);
        tv.setTextColor(color);
        if (bold) tv.setTypeface(null, Typeface.BOLD);
        return tv;
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = ConfigProvider.preferences(this);
        pm = getPackageManager();
        whitelist.addAll(prefs.getStringSet(
                ConfigProvider.KEY_WHITELIST, Collections.emptySet()));
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        buildScreen();
        loadApps();
    }

    private void buildScreen() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setPadding(dp(20), dp(22), dp(20), dp(12));
        setContentView(root);

        TextView heading = text("口袋模式", 27, TEXT, true);
        root.addView(heading);
        TextView description = text("SystemUI 原生遮罩 · 无应用切换动画", 13, MUTED, false);
        description.setPadding(0, dp(5), 0, dp(17));
        root.addView(description);

        LinearLayout control = new LinearLayout(this);
        control.setBackgroundColor(SURFACE);
        control.setPadding(dp(16), dp(16), dp(16), dp(16));
        control.setOrientation(LinearLayout.VERTICAL);
        root.addView(control, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        Switch enabled = new Switch(this);
        enabled.setText("启用口袋保护");
        enabled.setTextColor(TEXT);
        enabled.setTextSize(17);
        enabled.setChecked(prefs.getBoolean(ConfigProvider.KEY_ENABLED, true));
        control.addView(enabled, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView hint = text("默认开启。遮挡距离传感器时自动拦截触摸，移开即恢复。", 12, MUTED, false);
        hint.setPadding(0, dp(12), 0, 0);
        control.addView(hint);
        enabled.setOnCheckedChangeListener((button, checked) -> {
            prefs.edit().putBoolean(ConfigProvider.KEY_ENABLED, checked).apply();
            ConfigProvider.notifyUpdated(this);
        });

        Button test = new Button(this);
        test.setAllCaps(false);
        test.setText("测试遮罩（5 秒后自动关闭）");
        LinearLayout.LayoutParams testLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        testLp.topMargin = dp(10);
        root.addView(test, testLp);
        test.setOnClickListener(v -> {
            int seq = prefs.getInt(ConfigProvider.KEY_TEST_SEQUENCE, 0);
            prefs.edit().putInt(ConfigProvider.KEY_TEST_SEQUENCE, seq + 1).apply();
            ConfigProvider.notifyUpdated(this);
        });

        TextView section = text("应用白名单", 20, TEXT, true);
        section.setPadding(0, dp(17), 0, dp(6));
        root.addView(section);
        TextView info = text("勾选后，这些应用位于前台时不启动口袋遮罩。", 13, MUTED, false);
        root.addView(info);
        count = text("", 12, ACCENT, false);
        count.setPadding(0, dp(10), 0, dp(4));
        root.addView(count);

        EditText search = new EditText(this);
        search.setSingleLine(true);
        search.setTextColor(TEXT);
        search.setHintTextColor(MUTED);
        search.setHint("搜索应用名称或包名");
        search.setTextSize(15);
        LinearLayout.LayoutParams searchLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        searchLp.topMargin = dp(4);
        root.addView(search, searchLp);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                filter = s.toString().trim().toLowerCase(Locale.ROOT);
                applyFilter();
            }
            @Override public void afterTextChanged(Editable e) {}
        });

        ListView list = new ListView(this);
        list.setDividerHeight(dp(1));
        list.setCacheColorHint(Color.TRANSPARENT);
        adapter = new AppAdapter();
        list.setAdapter(adapter);
        LinearLayout.LayoutParams listLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1);
        listLp.topMargin = dp(6);
        root.addView(list, listLp);

        TextView footer = text("LSPosed 作用域：系统界面 (com.android.systemui)。首次安装或升级后重启手机。", 11, MUTED, false);
        footer.setPadding(0, dp(9), 0, dp(4));
        root.addView(footer);
    }

    private void loadApps() {
        Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> found = pm.queryIntentActivities(intent, 0);
        Set<String> added = new HashSet<>();
        for (ResolveInfo r : found) {
            if (r.activityInfo == null) continue;
            String pkg = r.activityInfo.packageName;
            if (pkg.equals(getPackageName()) || !added.add(pkg)) continue;
            CharSequence label = r.loadLabel(pm);
            apps.add(new AppEntry(pkg, label == null ? pkg : label.toString(), r));
        }
        Collator c = Collator.getInstance(Locale.getDefault());
        apps.sort((a, b) -> c.compare(a.name, b.name));
        applyFilter();
    }

    private void applyFilter() {
        visibleApps.clear();
        for (AppEntry app : apps) {
            if (filter.isEmpty() ||
                    app.name.toLowerCase(Locale.ROOT).contains(filter) ||
                    app.packageName.toLowerCase(Locale.ROOT).contains(filter)) {
                visibleApps.add(app);
            }
        }
        if (adapter != null) adapter.notifyDataSetChanged();
        if (count != null) {
            count.setText("已添加 " + whitelist.size() + " 个应用 · 可选择 " + apps.size() + " 个应用");
        }
    }

    private void toggle(AppEntry app) {
        if (!whitelist.add(app.packageName)) whitelist.remove(app.packageName);
        prefs.edit().putStringSet(
                ConfigProvider.KEY_WHITELIST, new HashSet<>(whitelist)).apply();
        ConfigProvider.notifyUpdated(this);
        applyFilter();
    }

    private static final class AppEntry {
        final String packageName;
        final String name;
        final ResolveInfo info;
        AppEntry(String p, String n, ResolveInfo r) {
            packageName = p; name = n; info = r;
        }
    }

    private final class AppAdapter extends BaseAdapter {
        @Override public int getCount() { return visibleApps.size(); }
        @Override public Object getItem(int position) { return visibleApps.get(position); }
        @Override public long getItemId(int position) { return position; }
        @Override public View getView(int position, View reusable, ViewGroup parent) {
            AppEntry entry = visibleApps.get(position);
            LinearLayout row = new LinearLayout(MainActivity.this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(4), dp(10), dp(4), dp(10));
            row.setOrientation(LinearLayout.HORIZONTAL);

            ImageView icon = new ImageView(MainActivity.this);
            try { icon.setImageDrawable(entry.info.loadIcon(pm)); }
            catch (Throwable ignored) { }
            row.addView(icon, new LinearLayout.LayoutParams(dp(38), dp(38)));

            LinearLayout labels = new LinearLayout(MainActivity.this);
            labels.setOrientation(LinearLayout.VERTICAL);
            labels.setPadding(dp(12), 0, dp(8), 0);
            labels.addView(text(entry.name, 15, TEXT, false));
            TextView pkg = text(entry.packageName, 10, MUTED, false);
            pkg.setSingleLine(true);
            labels.addView(pkg);
            row.addView(labels, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1));

            CheckBox selected = new CheckBox(MainActivity.this);
            selected.setChecked(whitelist.contains(entry.packageName));
            selected.setFocusable(false);
            selected.setOnClickListener(v -> toggle(entry));
            row.addView(selected);
            row.setOnClickListener(v -> toggle(entry));
            return row;
        }
    }
}
