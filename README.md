# Audio Editor

A small, free-software Android audio editor built with Kotlin, Jetpack Compose and
AOSP media APIs. No FFmpeg or proprietary SDKs are used.

## Features (MVP)

- Record from the microphone to WAV
- Import audio through SAF (`ACTION_OPEN_DOCUMENT`), decoded with `MediaCodec`
- Export the mixed project to WAV through SAF (`ACTION_CREATE_DOCUMENT`)
- Crop clips by dragging either edge
- Drag clips along the timeline, split at the playhead, undo/redo
- Audacity-style noise reduction: select a noise-only passage, tap **Get noise**,
  then **Reduce noise**

The timeline shows cached min/max waveform peaks. Tap the ruler to move the
playhead, tap a clip to select it, drag its body to move it, or drag an edge to crop.

## Build

Use a JDK supported by the pinned Gradle wrapper. In this environment:

```sh
JAVA_HOME=/usr/lib/jvm/java-27-openjdk ./gradlew :app:assembleDebug
JAVA_HOME=/usr/lib/jvm/java-27-openjdk ./gradlew test
```

`minSdk` is 26. Phone-first, tablet-friendly.

## Modules

- `:core-audio` — pure Kotlin project model, WAV codec, mixer, peaks, FFT denoiser
- `:core-platform` — Android implementations (SAF, `AudioRecord`, `AudioTrack`, `MediaCodec`)
- `:app` — Compose UI and editor state

See `PLAN.md` for the full development plan.
