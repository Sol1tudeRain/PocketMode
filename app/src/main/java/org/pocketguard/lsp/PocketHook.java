package org.pocketguard.lsp;

import android.util.Log;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/** The entire module runs only inside Android framework (system_server). */
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
