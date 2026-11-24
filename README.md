# PocketMode v2.5

Based on v1.9 for PixelOS. Select Android System Framework (`android`) and System UI (`com.android.systemui`) in
LSPosed and reboot after installing.

System UI samples proximity before a notification pulse. A covered sample suppresses the pulse.
While the display is off, physical POWER and supported tap-to-wake requests also trigger a proximity check.
At that point the controller checks proximity before resuming the normal
wake. When covered it prepares the opaque pocket window and gesture shield.
If rendering requires the display to start, it can delay the default
display screen-on callback until the first pocket frame commits. Both waits
are bounded so a failed sensor or renderer cannot leave the panel blocked.
Framework doze display requests stay OFF pending proximity. Covered requests keep the dream running with the panel off.
Hold POWER for 3 seconds while the pocket window is visible to dismiss it.

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

## Diagnostics

In Termux, after reproducing one display change and one proximity event:

```sh
su -c 'logcat -d -s PocketModeCr:V PocketModePower:V PocketModeInput:V | tail -n 80'
```

Key lines: `Display state=...`, `Sensor register=...`,
`Proximity NEAR/FAR` and `Pocket overlay attached`.

CrDroid references:
https://github.com/crdroidandroid/android_frameworks_base/tree/17.0
