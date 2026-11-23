# PocketMode for PixelOS — LSPosed SystemUI module

A SystemUI-based pocket protection experiment for Android 17, with an **app settings screen** and a **live foreground-app whitelist**.

## Features

- **Enabled by default** after installing the APK, enabling LSPosed scope and restarting.
- Tap the **PocketMode** app icon for an ON/OFF switch; no Termux commands needed.
- Search installed launcher applications and check apps that should be exempt from pocket protection.
- While the proximity sensor reports NEAR and the display is interactive, show a fullscreen SystemUI overlay after a 120 ms confirmation; works both locked and unlocked.
- Foreground whitelist is rechecked approximately every 700 ms while NEAR and interactive. Excluded foreground apps bypass the overlay.
- Original Doze notification pulse suppression when NEAR and not excluded.
- Uncover the sensor to remove the overlay immediately; screen off also removes it.
- 15-second fail-open watchdog until sensor reports FAR; five-second manual overlay test button.
- No separate Activity is launched when the pocket overlay appears.

## Install

1. If you used the older KernelSU PocketGuard implementation, stop it or disable that module before using the LSPosed variant.
2. Install the APK and enable the module in LSPosed with **SystemUI / com.android.systemui** scope.
3. **Restart your phone once** so SystemUI loads the new hook.
4. Open the **PocketMode** app from the launcher to toggle protection, edit the app whitelist, or display a five-second UI test.
5. It starts enabled without a shell command. To turn it off, use the switch inside the app.

Configuration is stored in the app's device-protected private preferences. SystemUI reads it through a provider restricted to the SystemUI UID and updates as the preferences change.

## Caveats

- Prototype: not yet verified on PixelOS Android 17 hardware.
- The whitelist relies on the privileged SystemUI process being able to call `ActivityManager.getRunningTasks(1)`. Check `PocketGuardLSP` logcat output if exclusions do not work; some ROMs may restrict this API.
- Only ordinary touch input is blocked by the overlay; buttons and hardware touch behavior are not disabled.
- The 15-second watchdog prioritizes safety over permanent pocket locking.
- Previous `settings global pocketguard_lsp_enabled` is deliberately **not used** by this version; use the app UI.
- If SystemUI stops working, disable this LSPosed module and reboot.

## Licenses / credits

The interface follows crDroid 17's pocket UI. The vector phone graphic is derived from Paranoid Android 2024 (Apache-2.0). See https://github.com/crdroidandroid/android_frameworks_base/tree/17.0 .
