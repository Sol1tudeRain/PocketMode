package org.pocketguard.lsp;

import android.content.Context;
import android.os.Bundle;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

/** Wrap the existing summary so the switch, title and native Settings styling stay intact. */
final class PocketDelaySlider extends LinearLayout implements SeekBar.OnSeekBarChangeListener {
    static final String TAG = "org.pocketguard.lsp.inlineDelay";
    private final TextView summary;
    private final SeekBar bar;
    private final ViewGroup.LayoutParams originalParams;
    private boolean dragging;

    static String label(int seconds) { return "亮屏连续遮挡时间：" + seconds + " 秒"; }

    static PocketDelaySlider attach(TextView summary) {
        ViewGroup parent = (ViewGroup) summary.getParent();
        int index = parent.indexOfChild(summary);
        PocketDelaySlider slider = new PocketDelaySlider(summary);
        parent.removeViewAt(index);
        parent.addView(slider, index, slider.originalParams);
        slider.addView(summary, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        slider.addView(slider.bar, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        return slider;
    }

    private PocketDelaySlider(TextView summary) {
        super(summary.getContext());
        this.summary = summary;
        originalParams = summary.getLayoutParams();
        setTag(TAG);
        setOrientation(VERTICAL);
        Context c = getContext();
        bar = new SeekBar(c);
        bar.setMin(1);
        bar.setMax(ConfigProvider.MAX_DELAY);
        bar.setKeyProgressIncrement(1);
        bar.setMinimumHeight(Math.round(48 * getResources().getDisplayMetrics().density));
        bar.setContentDescription("亮屏连续遮挡时间（秒）");
        bar.setOnSeekBarChangeListener(this);
    }

    void bind(boolean enabled, int seconds) {
        bar.setEnabled(enabled);
        if (!dragging) bar.setProgress(seconds);
        summary.setText(label(bar.getProgress()));
        summary.setVisibility(VISIBLE);
    }

    void detach() {
        ViewGroup parent = (ViewGroup) getParent();
        int index = parent.indexOfChild(this);
        removeView(summary);
        parent.removeViewAt(index);
        parent.addView(summary, index, originalParams);
    }

    @Override public void onProgressChanged(SeekBar source, int value, boolean fromUser) {
        if (!fromUser) return;
        summary.setText(label(value));
        if (!dragging) save(value); // accessibility or keyboard adjustment
    }

    @Override public void onStartTrackingTouch(SeekBar source) { dragging = true; }

    @Override public void onStopTrackingTouch(SeekBar source) {
        dragging = false;
        save(source.getProgress());
    }

    private void save(int seconds) {
        Bundle b = new Bundle();
        b.putInt(ConfigProvider.KEY_DELAY, seconds);
        getContext().getContentResolver().call(ConfigProvider.URI, "setDelay", null, b);
    }
}
