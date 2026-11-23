# PocketMode — LSPosed for PixelOS Android 17

Pocket protection using a native SystemUI overlay plus a power-key escape hook in Android framework.

## Settings

Open the **PocketMode** app from the launcher. There is exactly **one master ON/OFF switch**, enabled by default. There is **no application whitelist** and **no foreground-app polling**.

## Behavior (test branch v1.1-prearm-power3s-test)

- When proximity reports NEAR, SystemUI **pre-attaches** the full-screen pocket window even while the display is OFF. There is no TURN_SCREEN_ON or KEEP_SCREEN_ON flag, so pre-arming itself is not intended to wake the panel.
- An explicit short POWER press wakes into the already-prepared pocket UI, avoiding the lockscreen-first animation.
- If the window was not ready yet, system_server asks SystemUI to attach it, then wakes on the first heartbeat, or fails open after 700ms.
- Holding the **physical POWER key for 3000ms** exits pocket protection until proximity FAR and wakes the display if needed. Emergency and very-long power combinations are deliberately not hooked.
- Normal Doze notification pulse suppression remains; other OEM / third-party wake sources are not yet proven to be covered.
- A display that was already interactive and then becomes near is covered by the same window; the new code no longer forces it to sleep.
- The proximity logic prefers the device's vendor TCS3720ALSPRX two-channel RAW sensor while the panel is active; the wake-up standard proximity sensor handles sleep and the RAW source has priority after wake.
- The overlay consumes touches, covers the system navigation bar, and temporarily restricts status bar/quick settings expansion.
- Touching the overlay does not refresh the normal screen-off timer.
- Proximity FAR (confirmed after 100 ms) removes the overlay.
- A 3-second POWER hold exits pocket protection; usual long-press menu is deferred while the pocket hold is active.
- Short POWER from screen OFF should wake directly into the pre-attached pocket UI, not the lockscreen.
- No automatic 15-second dismissal on the normal overlay.
- Doze pulse suppression is attempted when pocket mode is active. It does not guarantee blocking every OEM-specific automatic screen wake.
- Unavailable power-key escape Hook prevents an indefinite overlay from activating, as a fail-safe.

## Install

1. Install the signed APK.
2. In LSPosed enable both **com.android.systemui** (System UI) and **android** (Android system framework / system_server) scopes.
3. Fully reboot the phone.
4. Open the PocketMode app to use the master switch.

v1.0 is signed with the same certificate as v0.7–v0.9 **fixed-signature** releases, allowing an in-place update from those versions. GitHub Actions uploads unsigned release APKs; they require private signing with the stable key, which is never published in this repository.

## Limitations

Experimental Android framework hooking, not fully validated on the target PixelOS Android 17 device. If a SystemUI or system_server issue occurs, disable PocketMode in LSPosed and reboot. Don't use old transient debug-signed APKs as updates to stable-signed releases.

## Credits

Pocket UI follows crDroid 17. Phone illustration derived from Paranoid Android (2024, Apache-2.0).

## Device verification needed

1. Fully reboot with LSPosed scopes **com.android.systemui** and **android**.
2. Cover proximity while screen OFF, without pressing POWER. Wait for notifications; check if screen stays dark.
3. While still covered, short-press POWER. The first visible frame should already be the PocketMode illustration, with no lockscreen flash.
4. Release POWER before three seconds: pocket UI should remain visible. Hold POWER for **three seconds**: overlay exits.
5. Uncover the sensor: protection releases; after uncovering/recovering it arms again.
6. Inspect errors with `su -c 'logcat -d -s PocketGuardLSP:I PocketGuardPower:I PocketGuardLSP:E PocketGuardPower:E'`.
7. If the phone becomes unusable, disable the LSPosed module and reboot from recovery. This PR is a **draft** and is not device-tested.

**Signing note**: the CI release output is intentionally unsigned. To update a fixed-signature installation, use the original private signing key. A CI debug APK has a different signature.
