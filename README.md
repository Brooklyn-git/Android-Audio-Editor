# Audio Editor

A small, free-software Android audio editor built with Kotlin, Jetpack Compose and
AOSP media APIs. No FFmpeg or proprietary SDKs are used.

## Features (MVP)

- Record from the microphone to WAV
- Import audio through SAF (`ACTION_OPEN_DOCUMENT`), decoded with `MediaCodec`
- Export the mixed project to WAV through SAF (`ACTION_CREATE_DOCUMENT`)
- Trim clips by dragging either edge; trimmed audio can be restored up to the
  source duration, and selected clips show resize stripes
- Drag clips along the timeline or onto another track, undo/redo
- Clips on the same track are pushed apart so they never overlap; place audio on
  different tracks when overlap is wanted
- Fixed timeline zoom with horizontal/vertical panning by dragging empty space,
  so many tracks keep a readable fixed height
- Hold a clip for a menu with **Delete**, **Split**, and **Noise Reduction**
  (current track only)
- Audacity-style noise reduction: select a noise-only passage, tap **Get noise**,
  then **Reduce noise** and choose current track or all tracks
- Live waveform and elapsed time while recording; moving red playhead during playback
- All UI text lives in `app/src/main/res/values/strings.xml` for easy translation

The timeline shows cached min/max waveform peaks. Drag empty space to scroll
(no long press needed), drag the ruler to scrub, tap a clip to select it, drag
its body to move it horizontally or between tracks, and drag an edge to trim or
extend it. A brief hold starts a move; a longer hold (about 0.7 s) opens the
clip menu.

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
