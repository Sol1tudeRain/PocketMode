# PocketMode v1.9 — raw-ADC-backed display-state detection

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

## v1.9: recovered raw proximity data after covered power wake

SensorProbe test on this specific Xiaomi handset showed:

- Uncovered: factory `0x41b` values `[~40-60, ~175-190, 2, ...]`,
  standard proximity 5.0 (FAR).
- Covered: factory values `[1023, 1158, 1, ...]`,
  standard proximity 0.0 (NEAR).
- **Covered across screen OFF -> ON**: factory values
  `[1023, 1023, 2, ...]` while **both standard channels
  wrongly report 5.0 (FAR)**. The raw sensor is still seeing saturation;
  the factory status field `raw[2]` is wrong on wake.

While ON/AOD, PocketMode subscribes to the available continuous factory
sensor `TCS3720ALSPRX Proximity Sensor Strm Non-wakeup` at 5Hz,
and uses ONLY its first two ADC values. A pair >= 600 is NEAR;
a pair with ADC0 <= 250 and ADC1 <= 500 is FAR.
Intermediate pairs retain the last decision. Standard proximity is only
used as fallback before a decisive raw reading or when the factory
sensor is absent or cannot register. There is no inferred state cache
across screen-off and no repeated sensor registration.

**Limits**: These thresholds are empirical, validated on the user's
logs, not Xiaomi's documented calibration. Extreme lighting or
unexpected sensor behavior could cause false positives; test before
depending on them. If raw readings stop while a mask is active,
the long-POWER dismissal remains available. While always-on AOD
is displayed, 5Hz factory sampling consumes more power than
only registering the standard on-change proximity sensor.

## Important limitations

* This uses real display states, not perceived pixel brightness. If a
  vendor AOD pulse occurs without any `DisplayManager` state transition and
  the state remains OFF, it will not trigger the module.
* An always-visible AOD means the display is in DOZE for a long time; in that
  case the sensor stays registered while AOD remains visible. The 5Hz raw
  stream may materially increase AOD power consumption.
* Prior physical logs showed standard FAR=5 despite continuous cover after
  power wake. v1.9 now prefers the factory raw ADC classification rather than
  waiting for standard proximity HAL to correct its status.
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
