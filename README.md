# GG Audio

A private, offline-first native Android audio player and equalizer. The goal is a polished Poweramp-inspired experience with original UI and DSP implementation.

## Current implementation

**Phase 2 — Software DSP and local playback**

- Native Kotlin + Jetpack Compose app (no WebView).
- Android 8.0+ baseline (minSdk 26).
- Ten-band graphic equalizer with Balanced, Bass Boost, Vocal, Treble and Flat profiles.
- Peaking biquad software EQ and adjustable preamp in the PCM processing path.
- Media3 ExoPlayer is hosted in a MediaSessionService, so playback can continue outside the Activity.
- System media-style notification and lock-screen transport controls are provided by Media3.
- Multi-file playback queue with track selection, previous/next, play/pause, stop and seek.
- Track titles are read from the selected document provider.
- Optional Android global Equalizer, BassBoost and Virtualizer adapter with device capability checks.
- Custom equalizer presets can be saved, applied and deleted using on-device DataStore storage.
- Active EQ curve, preamp, and optional effect levels are restored and saved automatically on-device.
- Local presets and playback are offline; the app does not upload audio.

## Important audio scope

The software DSP is connected to audio played **inside GG Audio's local player**. It does not capture audio from unrelated apps.

The global AudioEffect adapter is best-effort only. Android's session-0 insert effects are deprecated and device vendors may reject them or restrict their control. A positive connection result is not a promise that every third-party app's output is processed. Real behavior must be checked on the target phone.

The PCM adapter currently accepts 16-bit PCM. Supported file formats and codecs depend on Media3 and the Android device's installed decoders.

## Verification

The latest source commit with the multi-track queue is built by GitHub Actions. The assembleDebug and testDebugUnitTest steps passed for that commit. This is source/build verification, not a claim that playback has already been tested on a physical phone.

There is no emulator workflow in this repository. Physical-device verification remains a separate step.

## Build

Use JDK 17, Android SDK 36, Gradle 8.13, and Android Gradle Plugin 8.13.2.

Run gradle --no-daemon assembleDebug and gradle --no-daemon testDebugUnitTest from the project root.

## Privacy

- No INTERNET permission is declared.
- Audio files are selected through Android's system document picker.
- Audio data is processed locally and is not uploaded.
- No account, analytics, cloud sync or remote audio service is included.

## Planned next work

- Custom preset import/export and playback state restoration.
- Background playback, notification and lock-screen media controls.
- More detailed now-playing metadata and library browsing.
- Physical-device compatibility checks and performance tuning.