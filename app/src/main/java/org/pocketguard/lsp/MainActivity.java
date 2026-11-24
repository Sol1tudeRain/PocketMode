package org.pocketguard.lsp;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;

/** Long-press target for SystemUI's custom tile. */
public final class MainActivity extends Activity {
    static final String OPEN_GESTURES = "org.pocketguard.lsp.OPEN_GESTURES";

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        startActivity(new Intent(Settings.ACTION_SETTINGS)
                .setPackage("com.android.settings")
                .putExtra(OPEN_GESTURES, true));
        finish();
    }
}
