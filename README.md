# PocketMode v1.8 — simple display-state detection

An **experimental**, signed LSPosed module for Android 17 PixelOS.
The original crDroid pocket service is architectural inspiration; this is not
the ROM's integrated pocket mode.

## One simple rule

* The framework `DisplayManager` reports the default physical display
  as `STATE_ON`, `STATE_DOZE` or `STATE_DOZE_SUSPEND`:
  register the proximity sensor and react to NEAR/FAR.
* The display reports `STATE_OFF`: remove the overlay, stop the native
  gesture input monitor, and **unregister the proximity sensor immediately**.
* The same rule handles physical POWER wake and AOD notification pulses.
  No extra notification hooks, wake-timing hooks, screen broadcast polling,
  cached NEAR, retry-on-FAR, delayed validation, or continuous monitoring
  when the display is truly OFF.
* Sensor NEAR for 120 ms while the display is lit: show a full-screen
  touch-blocking overlay. FAR: hide it immediately.
* The existing on-demand `InputMonitor` shield attempts to cancel system
  Back / Home / Recents input streams while the overlay is attached.
* Long-POWER dismisses the overlay until the next FAR sample. No change to
  emergency key combinations.

## LSPosed setup

**Scope: `android` only.** The old `com.android.systemui` scope is no
longer needed. Upgrade over v1.7 (matching release signature), enable the
module, and reboot fully.

## Important limitations

* This uses real display states, not perceived pixel brightness. If a
  vendor AOD pulse occurs without any `DisplayManager` state transition and
  the state remains OFF, it will not trigger the module.
* An always-visible AOD means the display is in DOZE for a long time; in that
  case the proximity sensor stays registered while AOD remains visible.
* Prior physical logs showed the device reporting FAR=5 despite a
  continuously covered sensor after power wake. This change waits until the
  display is active before registering, but **cannot guarantee** the vendor
  sensor values become correct. No artificial NEAR is inferred.
* The privileged overlay/gesture shield behavior needs on-device testing.
  If a sensor never produces FAR, the user can still exit via long POWER.
* Doze content may use a compositor path that does not render the overlay.
  This implementation detects AOD activity but cannot guarantee AOD will
  be visibly covered without device testing.

## Diagnostics

In Termux, after reproducing one display change and one proximity event:

```sh
su -c 'logcat -d -s PocketModeCr:V PocketModePower:V PocketModeInput:V | tail -n 80'
```

Key lines: `Display state=...`, `Sensor register=...`,
`Proximity NEAR/FAR` and `Pocket overlay attached`.

CrDroid references:
https://github.com/crdroidandroid/android_frameworks_base/tree/17.0
