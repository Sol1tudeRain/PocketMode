# PocketMode v1.7 — wake proximity verification and AOD notification screening

Experimental LSPosed module for Android 17 PixelOS, inspired by crDroid's
pocket service architecture (original upstream Apache-2.0). This is not an
Android ROM patch and **has not been confirmed on the target device**.

## LSPosed setup

**Required scopes:** `android` (system_server) **and**
`com.android.systemui`. Fully reboot after updating. The module app has
one ON/OFF switch, default ON.

Keep the previous APK available for recovery. Changes to system_server hooks
are experimental; if problems occur disable the module from LSPosed/recovery.

## Screen-on and screen-off behavior

- When the display goes to sleep, `CrPocketController` immediately removes
  its overlay, shuts down its gesture monitor and unregisters proximity.
  **No continuous proximity listener while the display is completely asleep.**
- On actual wake, `PhoneWindowManager.startedWakingUp` posts a sensor
  registration early; the `ACTION_SCREEN_ON` broadcast is a fallback and
  does not redundantly unregister/re-register the same sensor.
- Proximity `NEAR` while interactive requests a full-screen input-blocking
  overlay. Normally a fresh `FAR` immediately removes it. The POWER long
  press escape stays in system_server, and normal auto screen-off is preserved.
- **Covered wake-up regression:** on the user's PixelOS, the wake-up proximity
  sensor returned `NEAR=0` before screen-off but repeatedly returned
  `FAR=5` after screen wake while still physically covered. v1.7 prefers a
  non-wake-up proximity sensor *if the vendor provides one*, since it only
  monitors while awake. Additionally it caches a genuine last-known NEAR
  across sleep without monitoring the sensor in standby. For the first 3.4s
  after a covered wake, it shows a temporary overlay, triggers one re-register
  after 900ms, and ignores the initial contradictory FAR. If no fresh NEAR
  ever arrives and FAR persists, it **fails open** after 3.4s to avoid an
  unremovable blocker. This is mitigation/diagnostic, not proof the HAL is
  repaired. Physical testing is essential.
- A locked-screen notification might generate only an ambient Doze/AOD pulse,
  **not** `SCREEN_ON`. In SystemUI, `PocketDozeHook` intercepts
  `DozeTriggers.onNotification` and uses its existing `ProximityCheck`
  to sample only when a notification pulse is requested (500 ms maximum
  sensor check, 850 ms safety fallback). If **NEAR**, it suppresses that
  Doze pulse; **FAR/UNKNOWN/error** retains the original pulse.
  The push notification itself is not discarded or silenced.
- Continuous AOD that's already on is not forcibly turned off by this hook;
  that requires separate AOD lifecycle handling.
- The full system gesture shield uses an Android `InputMonitor` and
  `pilferPointers()` while the pocket overlay is visible. Android channels
  marked `DO_NOT_PILFER` may still be able to receive system gestures.

## Debugging

In Termux after a power wake and a notification pulse:

```sh
su -c 'logcat -d -s PocketModeCr:V PocketModePower:V PocketModeInput:V PocketModeDoze:V | tail -n 100'
```

The logs show whether the framework received `startedWakingUp`, whether
proximity registration and an initial sensor reading succeeded, whether
the gesture monitor attached, and whether SystemUI's one-shot AOD check
succeeded or fell back.

## Limits

Only Doze notification pulses that pass through
`com.android.systemui.doze.DozeTriggers.onNotification` are intercepted.
Vendor-specific display-wake paths might require their own integration.
No mechanism here intercepts unrelated FCM messages or monitors sensors
continuously while asleep.

Original crDroid framework reference:
https://github.com/crdroidandroid/android_frameworks_base/tree/17.0
