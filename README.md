# PocketMode — LSPosed for PixelOS Android 17

Pocket protection using a native SystemUI overlay plus a power-key escape hook in Android framework.

## Settings

Open the **PocketMode** app from the launcher. There is exactly **one master ON/OFF switch**, enabled by default. There is **no application whitelist** and **no foreground-app polling**.

## Behavior

- Proximity NEAR triggers a full-screen SystemUI overlay, on both the lock screen and unlocked apps.
- The overlay consumes touches, covers the system navigation bar, and temporarily restricts status bar/quick settings expansion.
- Touching the overlay does not refresh the normal screen-off timer.
- Proximity FAR (confirmed after 100 ms) removes the overlay.
- Long POWER exits the overlay without opening the power menu for that hold.
- Ordinary short POWER can switch the screen off.
- No automatic 15-second dismissal on the normal overlay.
- Doze pulse suppression is attempted when pocket mode is active.
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
