package org.pocketguard.lsp;

import android.content.SharedPreferences;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

public final class PocketTile extends TileService
        implements SharedPreferences.OnSharedPreferenceChangeListener {
    @Override public void onStartListening() {
        super.onStartListening();
        ConfigProvider.preferences(this).registerOnSharedPreferenceChangeListener(this);
        refresh();
    }

    @Override public void onStopListening() {
        ConfigProvider.preferences(this).unregisterOnSharedPreferenceChangeListener(this);
        super.onStopListening();
    }

    @Override public void onClick() {
        super.onClick();
        ConfigProvider.setEnabled(this,
                !ConfigProvider.preferences(this).getBoolean(ConfigProvider.KEY_ENABLED, true));
        refresh();
    }

    @Override public void onSharedPreferenceChanged(SharedPreferences prefs, String key) {
        if (ConfigProvider.KEY_ENABLED.equals(key)) refresh();
    }

    private void refresh() {
        Tile tile = getQsTile();
        if (tile == null) return;
        boolean enabled = ConfigProvider.preferences(this).getBoolean(ConfigProvider.KEY_ENABLED, true);
        tile.setState(enabled ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        tile.updateTile();
    }
}
