# PocketMode — LSPosed SystemUI hook

A prototype pocket-mode module for PixelOS Android 17, based on crDroid's pocket-mode UX. **Use at your own risk.**

- Hooks only `com.android.systemui` (not system_server).
- Draws a fullscreen lockscreen overlay inside SystemUI without launching an Activity.
- Detects the proximity sensor with 120 ms confirmation.
- Attempts to suppress standard Doze notification pulses in-pocket.
- Uses a 15-second fail-safe and re-arms only after the proximity sensor reports FAR.
- **Disabled by default**. No touchscreen EVIOCGRAB.

## Build

GitHub Actions automatically runs when the initial commit is pushed and uploads the debug APK as an artifact. The first workflow imports the full, self-contained source bundle and commits the expanded files to this repository for review; subsequent workflows build from the expanded sources.

## Install & test safely

1. Stop the old KernelSU PocketGuard before using this LSPosed version:
   `su -c 'sh /data/adb/modules/pocketguard_aosp/pocketctl off'`
2. Install `app-debug.apk` from GitHub Actions.
3. Enable this module in LSPosed. Scope only `com.android.systemui`, then restart SystemUI/reboot.
4. Enable explicitly from Termux: `su -c 'settings put global pocketguard_lsp_enabled 1'`
5. Test on the lockscreen. Remove proximity obstruction to dismiss; a 15-second watchdog releases overlay touch.
6. Disable: `su -c 'settings put global pocketguard_lsp_enabled 0'`

**Caution:** PixelOS window authorization and Doze hooks are not yet tested on hardware. An LSPosed injection failure may crash SystemUI; verify you have a recovery path before enabling. The fallback volume-up escape is not guaranteed to receive keys.

## Credits

Screen layout follows crDroid 17's `pocket_lock_view.xml`, and the vector phone illustration adapts code from Paranoid Android (2024), Apache-2.0. crDroid framework references: https://github.com/crdroidandroid/android_frameworks_base/tree/17.0 .
