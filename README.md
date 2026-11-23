# PocketMode v1.1 — experimental crDroid framework port

**This is an experimental branch. Do not replace a working v1.0 without
a recovery route.** The code has not been tested on the target PixelOS 17
system_server. Injecting code there can destabilize Android if a ROM policy
is incompatible.

## What changed

This implementation is adapted from the architecture of crDroid 17 /
Paranoid Android:

- `services/core/java/com/android/server/pocket/PocketService.java`
  uses a `HandlerThread` for sensor state. Our
  `CrPocketController.java` likewise separates sensor delivery from
  the window UI thread and requests proximity events at 400,000 µs.
- `services/core/java/com/android/server/policy/pocket/PocketLock.java`
  uses `TYPE_SECURE_SYSTEM_OVERLAY`, but Android 17
  `DisplayPolicy.adjustWindowParamsLw` **forcibly makes that type
  `FLAG_NOT_TOUCHABLE`**. Therefore this LSPosed port deliberately uses
  privileged, touchable `TYPE_SYSTEM_ERROR` with full-screen layout to
  achieve the requested touch interception. This is a necessary deviation
  from crDroid's view code.
- `PhoneWindowManager.java` handles pocket visibility and long POWER.
  The new `PocketPowerHook.java` handles POWER inside the same process as
  the overlay, without any `Settings.Global` handshake or heartbeat.
- Customizations: PocketMode also activates when unlocked (crDroid normally
  limits the interactive display case to the keyguard), uses no UI fade
  animation, and keeps the single default-on master switch.
- Touches are consumed by the full-screen window and the hidden
  `INPUT_FEATURE_DISABLE_USER_ACTIVITY` field is set so touch input
  should not reset the user's screen timeout.

There is no foreground-app whitelist, no per-700ms polling, no repeated
sensor re-registration, and no 15-second auto-dismissal of the normal lock.
Distance FAR removes the overlay immediately; long POWER exits while blocked,
bypassing until proximity FAR.

## Installation

1. Keep your current stable APK for recovery.
2. Install an APK built from this experimental branch.
3. Set LSPosed scope **only to Android / `android` (system_server)**.
4. Reboot completely; a SystemUI-only restart cannot load this architecture.
5. Open the PocketMode app and use its single toggle.

Debug logs: `logcat -d -s PocketModeCr:V PocketModePower:V | tail -n 80`.

## Safety notes

- This module is not a patch to the ROM's full `android.pocket` framework:
  PixelOS has no crDroid-specific `PocketService`, manager APIs or internal
  `pocket_lock_view` resource. It is an LSPosed port of the relevant logic.
- The original test version mistakenly stopped monitoring proximity on
  SCREEN_OFF. Fixed: the controller continues monitoring when screen is off
  and only shows the overlay while interactive. A non-wakeup proximity sensor
  is rearmed ONCE upon wake to request an up-to-date initial reading.
- If the `PhoneWindowManager` power hook cannot be installed, the overlay
  will not activate. If the privileged `TYPE_SYSTEM_ERROR` window type is
  rejected, the module fails open instead of showing a touch-through overlay.
- Android 17 framework behavior is verified against crDroid sources, but
  actual PixelOS system_server compatibility and input delivery still
  require physical device testing.
- System emergency hardware key paths are not modified.
- If boot issues occur, disable the module via LSPosed safe mode / recovery.

## Attribution / license

The original pocket service and view code derives from Paranoid Android
and crDroid framework components under the **Apache License 2.0**.
Adapted architecture and window policy in this project are labeled in source.

https://github.com/crdroidandroid/android_frameworks_base/tree/17.0
