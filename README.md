# GG Audio

A private, offline-first native Android equalizer with an optional local audio test player. The goal is a polished Poweramp Equalizer-inspired experience with original UI and DSP implementation.

## Current implementation

**Phase 4 — Local DSP and Android app-session integration**

- Native Kotlin + Jetpack Compose app (no WebView).
- Android 8.0+ baseline (minSdk 26).
- Ten-band graphic equalizer with Balanced, Bass Boost, Vocal, Treble and Flat profiles.
- Peaking biquad software EQ and adjustable preamp in the PCM processing path.
- Software low-shelf Bass Boost (0 to +9 dB) and mid/side Stereo Width are wired into the same local PCM chain.
- Linked-channel PCM peak limiter after EQ/preamp, set to a -1 dBFS ceiling with an 80 ms gain-release time.
- Media3 ExoPlayer is hosted in a MediaSessionService, so playback can continue outside the Activity.
- System media-style notification and lock-screen transport controls are provided by Media3.
- Multi-file playback queue with track selection, previous/next, play/pause, stop and seek.
- Adding files appends unique tracks to the current queue without rebuilding or restarting the active song; queue can also be cleared.
- Embedded track title, artist, album and duration tags are read locally when available; filenames remain the fallback.
- The now-playing panel, queue and media-session metadata show embedded artist/album tags when available.
- Legacy Android global Equalizer route (session 0), retained only as a device-dependent fallback.
- Session-bound Equalizer, BassBoost and Virtualizer routing for compatible players that broadcast Android's standard audio-effect session open/close actions; active package names appear in Settings.
- Independent per-app EQ profiles persist each supported package's 10 bands, preamp, Bass Boost and Stereo Width in on-device DataStore and are selected by package name at session time.
- Custom equalizer presets can be saved, applied and deleted using on-device DataStore storage.
- Active EQ curve, preamp, and optional effect levels are restored and saved automatically on-device.
- The local playback queue, selected track and last position are restored from on-device storage; playback does not auto-start.
- Local presets and playback are offline; the app does not upload audio.

## Important audio scope

The ten-band EQ, preamp, software Bass Boost, Stereo Width and peak limiter are connected to audio played **inside GG Audio's local player**.

For external apps, GG Audio listens for Android's standard audio-effect session broadcasts and attempts to attach insert effects to the announced session ID. This works only when the player sends those broadcasts and the device grants effect control. External sessions currently use the active shared EQ/effects values; independent saved profiles per app are not implemented yet.

The legacy global AudioEffect adapter is best-effort only. Android's session-0 insert effects are deprecated and device vendors may reject them or restrict their control. No route is guaranteed on every device or player. Real behavior must be checked on the target phone.

The PCM adapter currently accepts 16-bit PCM. Supported file formats and codecs depend on Media3 and the Android device's installed decoders. The limiter is a sample-peak safety stage, not a look-ahead true-peak limiter.

## Verification

GitHub Actions builds the debug APK and runs JVM unit tests, including DSP and playback-state tests. Passing CI verifies source compilation and tested logic; it does not prove that audio has been listened to or validated on a physical phone.

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

- Test per-app profile switching with two compatible players on the target phone.
- Verify session-broadcast compatibility and control ownership on the target phone.
- Physical-device audio checks, then CPU, latency and battery tuning.
- Secondary local-player library improvements after the equalizer engine is stable.