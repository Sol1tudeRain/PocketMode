# PocketMode — LSPosed SystemUI + Android framework

A pocket mode experiment for PixelOS Android 17 with a native SystemUI overlay and a power-key escape policy hook.

## Features

- Starts enabled by default on first installation. Open **PocketMode** from the launcher for an on/off switch, five-second UI test, and searchable foreground-app whitelist.
- When the proximity sensor reports NEAR, shows the crDroid-style pocket overlay in both locked and unlocked states, except while a whitelisted application is in the foreground.
- Touches are intercepted by the overlay and marked with **`WindowManager.LayoutParams.INPUT_FEATURE_DISABLE_USER_ACTIVITY`** (hidden platform input feature value 2). Thus touches delivered to the overlay should not reset the normal screen-off timer.
- When the distance sensor reports FAR, the overlay is removed. A regular short power press still turns off the display.
- **Long POWER while the overlay is active:** Android framework `PhoneWindowManager.powerLongPress` hook signals SystemUI to dismiss pocket protection and suppresses the normal software power menu for that key hold. Also intercepts `powerVeryLongPress` for the same hold. Detection remains bypassed until FAR.
- **No 15-second auto dismissal for a normal pocket overlay.** Only the manual five-second UI test auto-closes.
- Safety: normal indefinite overlay is not allowed until the SystemUI controller confirms that `system_server` has installed the long-power hook in this boot. Active overlays advertise an expiring status heartbeat. If the status is stale, the framework will not suppress normal power-key behavior.
- Attempts to suppress Doze notification screen pulses while in pocket.

## Installation and scope

1. Stop the older KernelSU PocketGuard module if installed.
2. Install the APK.
3. In LSPosed enable the module with **two scopes**:
   - **System UI**: `com.android.systemui`
   - **System framework / Android**: `android` (the `system_server` process)
4. Reboot the phone. The feature is enabled automatically; no Termux commands needed.
5. Open **PocketMode** in the launcher to use the master switch or edit the whitelist. The UI test button displays a removable five-second preview.

## Important caveats

- Experimental; neither `PhoneWindowManager` hooks nor privileged window permission have been verified on the target phone. On ROMs where the power hook cannot attach, normal pocket overlays stay disabled by design.
- The module does **not** disable hardware power-button emergency shutdown combinations.
- When locked or screen off, Android or vendor power behavior may vary.
- If SystemUI or system_server becomes unstable, disable the module in LSPosed and reboot; keep a recovery route available.
- Automatic screen-off follows the user's normal Android display timeout settings; the module does not modify their value.
- Foreground whitelist uses SystemUI's privileged `ActivityManager.getRunningTasks(1)` access, which can differ by ROM.
- Logs: `logcat -d -s PocketGuardLSP:V PocketGuardPower:V | tail -n 100`.

## Credits

Interface follows crDroid 17; vector derived from Paranoid Android (2024, Apache-2.0).
https://github.com/crdroidandroid/android_frameworks_base/tree/17.0
