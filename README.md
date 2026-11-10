# Deckhand

Android BLE console for a Furrion DV3050 head unit (`DV3050-4.0-523C`, service `A032`). Scan, connect, read the GATT table, and send hex or text on any characteristic. Face keys stay inert until you long-press one and map a payload.

Requires Android 12 or newer. This is a sideload build, not a Play Store app.

## Install

The signed release APK is attached to the [v1.0.0 release](https://github.com/dkraft/FurrionRadio/releases/tag/v1.0.0) and also committed at `dist/deckhand-1.0.0.apk`.

1. Download `deckhand-1.0.0.apk` on the phone.
2. Open it and allow install from that source if Android asks.
3. Turn Bluetooth on. Deckhand asks for scan and connect only.
4. Scan, tap the DV3050, then Bond if the radio wants a pair. Manual PIN is `0000` when the system dialog asks.

## Rebuild

```
./gradlew assembleRelease
```

The APK in `dist/` is already signed. A fresh `assembleRelease` on a machine without the release keystore produces an unsigned APK, which will not update an install of this signed build. Android SDK 34 and JDK 17.
