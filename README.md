# PocketMode v1.3 — Android 17 framework gesture-shield experiment

An experimental LSPosed port adapting the *architecture* of crDroid / Paranoid Android's pocket protection to PixelOS Android 17.

## Requirements and setup

- PixelOS / Android 17, working proximity sensor, working LSPosed in `android` (system_server) process.
- Enable **only `android`** in LSPosed; this implementation does not inject into SystemUI or Pixel Launcher.
- Reboot the entire device after installing.
- Open PocketMode: only one master enable/disable switch, default ON; there is no per-app whitelist.
- **Keep a working recovery path.** This is not device-tested code. If system_server has trouble, disable the LSPosed module in recovery or using LSPosed's safe-mode mechanism.

## How it works

**Proximity:** `CrPocketController` uses crDroid's sensor-service pattern: a dedicated `HandlerThread`, an on-change proximity sensor, a 400,000µs requested sample interval, and continued sensor listening during screen-off. It also activates while unlocked, as requested. Screen wake re-arms non-wakeup sensors once. FAR requests immediate removal.

**Independent UI thread:** A separate `PocketModeWindowUi` HandlerThread runs overlay lifecycle operations; a long-running system_server main-loop / navigation animation should no longer delay FAR dismissal. If a proximity sensor stops reporting while the overlay is active, one rearm requests a fresh HAL sample after 6.5 seconds; this is not an automatic overlay timeout.

**Overlay:** Privileged `TYPE_SYSTEM_ERROR` window (rather than crDroid's `TYPE_SECURE_SYSTEM_OVERLAY`, which Android 17 forces `FLAG_NOT_TOUCHABLE`). A full-screen touch-absorbing View blocks app taps. The window uses `INPUT_FEATURE_DISABLE_USER_ACTIVITY` so touching does not prolong the screen-off timeout. Status bar/QS expansion is temporarily disabled and restored on dismiss.

**System navigation gestures:** `PocketInputShield` starts a native Android `InputManager.monitorGestureInput` observer only when the overlay is prepared. On touchscreen `ACTION_DOWN`, `InputMonitor.pilferPointers()` takes the stream away from other input channels (including SystemUI edge-back and Quickstep home/recents gesture recognizers). It disposes the monitor immediately when the overlay is hidden; the receiver event is always finished normally. Android channels marked `DO_NOT_PILFER` cannot be canceled by this API, so **actual PixelOS behavior must be tested**.

**Long POWER:** An LSPosed `PhoneWindowManager.powerLongPress` hook in the same system_server process cancels the global-actions menu and hides the overlay. Override persists until the proximity sensor reports FAR. Emergency key combinations are not modified.

## Debugging

Use Termux:

```sh
su -c 'logcat -d -s PocketModeCr:V PocketModeInput:V PocketModePower:V | tail -n 80'
```

`su -c` runs the log command with root privileges, `-s` selects the three module log tags, and `tail -n 80` keeps the newest 80 lines.

If native input monitoring cannot be registered, the overlay is **not** displayed; the module fails open rather than letting navigation gestures trigger behind an opaque touch blocker. This experimental port has passed APK compilation, not an end-to-end PixelOS device test.

## Attribution and origin

The original pocket service/controller inspiration comes from Paranoid Android and crDroid (Apache-2.0); Android's `InputManager`, `InputMonitor` and native `InputDispatcher` gesture-pilfer APIs provide the separate navigation gesture interception:
- https://github.com/crdroidandroid/android_frameworks_base/tree/17.0
- https://github.com/crdroidandroid/android_frameworks_native/tree/17.0

This project does not install crDroid's ROM-specific `android.pocket` system service into PixelOS.
