# GG Audio

A private, offline-first Android equalizer project. The goal is a polished native audio-control app with a Poweramp-inspired level of control, while keeping the implementation original and maintainable.

## Current phase

**Phase 1 — Native foundation and UI shell**

- Kotlin + Jetpack Compose native Android app
- Android 8.0+ baseline (minSdk 26)
- Offline-first; no account, analytics, cloud sync, or audio upload
- 10-band EQ state model and preset defaults
- Live system-audio DSP is not connected yet. The first UI shell must not claim that processing is active.

## Audio-engine constraints

Android audio effects are device-, player-, and session-dependent. The app will probe supported effects and report the actual result. It must not pretend that a global effect is available when the device rejects it. The DSP architecture will keep the control/UI layer separate from the processing backend so that supported system effects and an optional native PCM engine can be tested independently.

## Build

Use JDK 17, Android SDK 36, Gradle 8.13, and Android Gradle Plugin 8.13.2.

```bash
gradle assembleDebug
gradle testDebugUnitTest
```

## Privacy

The app is designed for local/private use. No network permission is declared in the Android manifest. Presets and settings will remain on-device.
