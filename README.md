# GG Audio

A private, offline-first native Android audio player and equalizer. The goal is a polished Poweramp-inspired experience with original UI and DSP implementation.

## Current implementation

**Phase 3 — Local playback DSP safety and quality**

- Native Kotlin + Jetpack Compose app (no WebView).
- Android 8.0+ baseline (minSdk 26).
- Ten-band graphic equalizer with Balanced, Bass Boost, Vocal, Treble and Flat profiles.
- Peaking biquad software EQ and adjustable preamp in the PCM processing path.
- Linked-channel PCM peak limiter after EQ/preamp, set to a -1 dBFS ceiling with an 80 ms gain-release time.
- Media3 ExoPlayer is hosted in a MediaSessionService, so playback can continue outside the Activity.
- System media-style notification and lock-screen transport controls are provided by Media3.
- Multi-file playback queue with track selection, previous/next, play/pause, stop and seek.
- Adding files appends unique tracks to the current queue without rebuilding or restarting the active song; queue can also be cleared.
- Embedded track title, artist, album and duration tags are read locally when available; filenames remain the fallback.
- The now-playing panel, queue and media-session metadata show embedded artist/album tags when available.
- Optional Android global Equalizer, BassBoost and Virtualizer adapter with device capability checks.
- Custom equalizer presets can be saved, applied and deleted using on-device DataStore storage.
- Active EQ curve, preamp, and optional effect levels are restored and saved automatically on-device.
- The local playback queue, selected track and last position are restored from on-device storage; playback does not auto-start.
- Local presets and playback are offline; the app does not upload audio.

## Important audio scope

The software DSP is connected to audio played **inside GG Audio's local player**. It does not capture audio from unrelated apps.

The global AudioEffect adapter is best-effort only. Android's session-0 insert effects are deprecated and device vendors may reject them or restrict their control. A positive connection result is not a promise that every third-party app's output is processed. Real behavior must be checked on the target phone.

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

- Searchable music library with artist/album grouping and queue removal/reordering.
- Audit Media3 decoder compatibility and clearer playback error recovery across local file types.
- Physical-device audio checks, then CPU, latency and battery tuning.
- Decide whether limiter controls belong in the UI after listening tests.