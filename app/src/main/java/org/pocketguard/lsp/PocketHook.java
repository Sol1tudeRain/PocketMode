package org.pocketguard.lsp;

import android.util.Log;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * android (system_server) owns the pocket overlay, sensors and physical POWER.
 * com.android.systemui owns only a one-shot proximity check before AOD
 * notification pulses. No overlay or continuously running listener in SystemUI.
 */
public final class PocketHook implements IXposedHookLoadPackage {
    @Override public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lp) {
        try {
            if ("android".equals(lp.packageName)
                    && "android".equals(lp.processName)) {
                PocketPowerHook.install(lp);
            } else if ("com.android.systemui".equals(lp.packageName)
                    && "com.android.systemui".equals(lp.processName)) {
                PocketDozeHook.install(lp);
            }
        } catch (Throwable t) {
            Log.e("PocketModeCr", "PocketMode injection failed in " + lp.packageName, t);
        }
    }
}
