package org.pocketguard.lsp;

import android.util.Log;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * crDroid-style PocketMode is owned entirely by system_server policy.
 *
 * Do not attach any overlay to SystemUI: duplicate window owners, foreground
 * polling and cross-process readiness handshakes were removed.
 */
public final class PocketHook implements IXposedHookLoadPackage {
    @Override public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lp) {
        if (!"android".equals(lp.packageName) || !"android".equals(lp.processName))
            return;
        try {
            PocketPowerHook.install(lp);
        } catch (Throwable t) {
            Log.e("PocketModeCr", "Framework hook initialization failed", t);
        }
    }
}
