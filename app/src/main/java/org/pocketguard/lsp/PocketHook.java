package org.pocketguard.lsp;

import android.util.Log;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/** Power policy runs in system_server; ambient notification pulses originate in SystemUI. */
public final class PocketHook implements IXposedHookLoadPackage {
    @Override public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lp) {
        if ("com.android.settings".equals(lp.packageName) && lp.packageName.equals(lp.processName)) {
            GestureSettingsHook.install(lp.classLoader);
            return;
        }
        if ("com.android.systemui".equals(lp.packageName)
                && lp.packageName.equals(lp.processName)) {
            PocketDozeHook.install(lp.classLoader);
            return;
        }
        if (!"android".equals(lp.packageName) || !"android".equals(lp.processName))
            return;
        try {
            PocketPowerHook.install(lp);
        } catch (Throwable t) {
            Log.e("PocketModeCr", "Framework hook initialization failed", t);
        }
    }
}
