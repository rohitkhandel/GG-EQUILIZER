# GG Audio — Architecture

## Product principles

- Native Android UI; no WebView-based shell.
- Offline-first and private by default.
- UI state is never presented as proof that audio processing is active.
- Each audio backend reports capability and runtime status explicitly.
- DSP math is isolated from Android effect APIs so it can be tested without a device.
- The local software-DSP route and optional system-effect route are separate.

## Implemented layers

1. **Presentation** — Jetpack Compose screens for Equalizer, Player, Effects, Presets and Settings.
2. **Domain** — ten equalizer bands, built-in profiles, preamp values and playback queue state.
3. **Local playback** — Media3 ExoPlayer with Android's document picker and a queue of selected local audio files.
4. **PCM adapter** — Media3 DspAudioProcessor accepts PCM16 buffers and applies GG Audio's software processor before playback.
5. **Preset persistence** — AndroidX Preferences DataStore stores custom 10-band curves and preamp values locally; a version-tolerant JSON codec validates saved profiles.
6. **DSP core** — ParametricEqualizerProcessor builds peaking biquad filters and processes interleaved floating-point samples per channel.
7. **Android effects** — GlobalAudioEffectsController probes optional Android Equalizer, BassBoost and Virtualizer effects and reports whether the device granted control.
8. **Tests** — JVM tests cover EQ defaults, gain bounds, PCM round-trip, tone boost/cut, channel separation, preamp and playback-time formatting.

## Local audio data flow

System document picker -> LocalAudioFile queue -> Media3 ExoPlayer -> DspAudioProcessor (PCM16) -> ParametricEqualizerProcessor -> Android audio output.

The processor applies only to audio played by GG Audio. It is not an audio-capture API and cannot modify audio from unrelated apps.

## DSP model

- Ten nominal center frequencies: 31, 62, 125, 250, 500, 1k, 2k, 4k, 8k and 16k Hz.
- Peaking biquad sections are cascaded with Q = 1.0.
- Per-band UI gain is bounded to -12 dB through +12 dB.
- Software preamp is bounded to -24 dB through +12 dB.
- The PCM adapter currently configures a 16-bit PCM path; unsupported input encodings are rejected by the processor.

## System-effect limitations

Android global insert effects on session 0 are deprecated and device-dependent. The global adapter is therefore best-effort. It must report unavailable or failed control rather than pretending system-wide processing is active.

A device that rejects session 0 will still be able to use the software DSP in GG Audio's own local player. A successful system-effect connection must not be interpreted as guaranteed processing of every third-party player.

## Quality gates

- GitHub Actions builds the debug APK and runs JVM unit tests on source changes.
- Emulator-based CI was removed; the project no longer provisions or boots an emulator.
- Physical-device behavior, including the Moto G82 target, remains unverified until tested on the actual phone.
- Audio processing tests verify numerical PCM behavior; they do not substitute for listening tests or device compatibility checks.

## Planned work

- Persistent active EQ settings, preset import and export.
- Background playback service and MediaSession notification / lock-screen controls.
- Media library browsing, metadata, album art and queue management improvements.
- Additional DSP effects such as a tested limiter and optional dynamics controls.
- Physical-device latency, battery, thermal and audio-route tests.