# GG Audio — Architecture

## Product principles
- Native Android UI; no WebView-based shell.
- Offline-first and private by default.
- UI state must never be presented as proof that audio processing is active.
- Each audio backend reports capability and runtime status explicitly.
- DSP math is isolated from Android effect APIs so it can be tested without a device.

## Planned layers
1. Presentation — Jetpack Compose screens, navigation, visual state, accessibility.
2. Domain — equalizer bands, presets, preamp, effect configuration, capability state.
3. Audio platform — Android AudioEffect wrappers, session lifecycle, permission and device probing.
4. DSP core — filter coefficient generation, biquad processing, limiter, sample-rate/channel handling.
5. Persistence — local preset/settings storage only.

## Audio integration strategy
The first implementation must probe the device instead of assuming that a global session is available. Android effect support and session behavior can vary by device, Android version, and player. A rejected or unavailable global effect must be shown as unavailable; the app must not display a false active state.

The native PCM DSP core is a separate later milestone. It is not considered integrated until a real audio stream is processed and verified with tests.

## Quality gates
- Every DSP formula has JVM unit tests.
- Compose controls expose accessible labels and bounded values.
- CI builds the debug APK and runs unit tests.
- Device/emulator tests verify screen navigation and lifecycle behavior.
- Audio tests distinguish requested settings from confirmed effect state.
