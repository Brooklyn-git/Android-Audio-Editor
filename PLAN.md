# PLAN.md — Audio Editor for Android

Developer plan for a greenfield Android audio editor: record, import, export, crop,
drag/clip-arrange tracks, and reduce noise. Inspired by Audacity/Tenacity in spirit,
but intentionally scoped to a usable, non-professional MVP with a clean path to grow.

---

## 1. Product scope

### MVP goal (v1)

A phone app that can do the following end to end:

1. **Record** audio from the microphone (live level/waveform, save as a source).
2. **Import** audio files from device storage.
3. **Export** the edited result as a WAV file.
4. **Crop/trim** audio by selecting a region on a waveform.
5. **Drag** clips along a multitrack timeline (and reorder tracks).
6. **Reduce noise** Audacity-style: select a noise-only passage, learn its spectral
   profile, then reduce that noise across a clip with preview.

Editing should feel non-destructive wherever practical: cropping, splitting and
moving never destroy the original source audio.

### Locked decisions

| Decision | Choice |
| --- | --- |
| First usable release | All six features, shallow but coherent |
| Language / UI | Kotlin + Jetpack Compose (Material 3) |
| Minimum Android version | `minSdk 26` (Android 8.0), phone-first, tablet-friendly |
| Audio engine | Pure Kotlin + AOSP APIs; no native code in v1 |
| Software policy | Free/open-source first; avoid proprietary SDKs and GMS |
| Internal audio format | 16-bit PCM WAV on disk, interleaved `FloatArray` in memory, 44.1 kHz, 1–2 channels |
| Export format (MVP) | WAV only |
| Noise feature | Audacity-style profile + spectral reduction with A/B preview |

### Explicit non-goals for v1

- Professional-grade editing, plugin support, automation curves, effects chains.
- MP3/AAC/FLAC/Opus export (patent/licensing and device-codec concerns; see §10).
- Cross-track (vertical) clip drag, per-channel waveform display, time-stretch,
  pitch shift, AI/transient noise removal.
- Cloud sync, accounts, analytics, crash reporting, ads, or any Google Play Services.

---

## 2. Environment notes (this machine)

Observed local toolchain:

- Working directory: `/home/brooklyn/Documents/Programming/Android/Audio-Editor`
- Git repo on `main`, no commits yet (greenfield).
- Java: OpenJDK 27.0.1.1
- Gradle: 9.9.0 (system install)
- `adb` available; `sdkmanager` not on PATH
- `ANDROID_HOME=/home/brooklyn/Android/sdk`
- SDK platforms: 31, 34, 35, 36, 37.0
- Build tools: 34.0.0, 35.0.0, 36.0.0
- NDK, CMake, emulator and at least one system image present

Implications for the scaffold:

- **Do not rely on system Gradle.** Generate a Gradle wrapper pinned to a version
  compatible with the chosen Android Gradle Plugin (check the AGP/Gradle
  compatibility matrix when picking versions; AGP 8.x pairs with Gradle 8.x).
- **JDK 27 may be too new for AGP/Gradle.** If sync fails, install/select JDK 17
  (or whatever the pinned AGP requires) and point `JAVA_HOME`/Gradle toolchains at it.
- Set `compileSdk` to an installed platform (e.g. 35 or 36), `targetSdk` to a
  current installed platform, `minSdk = 26`.
- Build with `./gradlew assembleDebug`; run on the emulator or a connected device.

---

## 3. Dependencies and rationale

Allowed: AOSP/AndroidX APIs and open-source libraries under permissive licenses.

| Need | Choice | License / rationale |
| --- | --- | --- |
| UI | Jetpack Compose, Material 3 | Apache-2.0, modern declarative UI, excellent custom Canvas + gestures |
| Async | Kotlin Coroutines + Flow | Apache-2.0 |
| Serialization | kotlinx.serialization | Apache-2.0, small and pure Kotlin |
| DI | Manual at first (optional Koin later) | Avoid extra framework weight in MVP |
| Testing | JUnit, androidx test rules | Standard |

Libraries deliberately **not** used:

- **FFmpegKit** — original project retired in 2025. A community LGPL fork exists,
  but adds native binaries, APK size and LGPL obligations. Not needed for WAV-only
  MVP. Revisit only if compressed formats become a requirement.
- **TarsosDSP** — dropped Android packaging in v2.5; its core DSP is usable but
  the FFT we need is small enough to own directly.
- **Recording/analysis SDKs, GMS, Firebase, ads, analytics** — conflict with the
  free-software-first policy.

Platform APIs used (AOSP, no proprietary vendor SDKs):

`AudioRecord`, `AudioTrack`, `MediaExtractor`, `MediaCodec`, `MediaMuxer` (only if
compressed export is added later), `Storage Access Framework`, `NotificationManager`,
`MediaProjection` not needed.

> Note: compressed codecs inside `MediaCodec` are vendor implementations on the
> device. WAV export avoids that entirely in the MVP.

---

## 4. Architecture

### 4.1 Modules

```
/settings.gradle.kts
/app                     # Compose UI, ViewModels, navigation, Android entry points
/core-audio              # pure Kotlin, JVM-unit-testable; no Android imports
/core-platform           # Android implementations of core-audio interfaces
```

Dependency direction: `app -> core-platform -> core-audio`. `core-audio` depends on
nothing Android-specific.

### 4.2 Proposed file layout

```
core-audio/src/main/kotlin/<pkg>/core/
  model/
    Project.kt  Track.kt  Clip.kt  AudioSourceRef.kt  NoiseProfile.kt
    ProjectCommand.kt  UndoStack.kt
  pcm/
    PcmBuffer.kt  WavCodec.kt  Resampler.kt  Mixer.kt  PeakPyramid.kt  Fades.kt
  dsp/
    Fft.kt  WindowFunctions.kt  NoiseReducer.kt  NoiseGate.kt
  export/
    WavExporter.kt

core-platform/src/main/kotlin/<pkg>/platform/
  record/  RecorderService.kt  AudioRecordSource.kt
  decode/  MediaCodecDecoder.kt
  io/      ProjectStore.kt  SafFilePicker.kt  PeakCache.kt
  playback/ StreamingMixerPlayer.kt

app/src/main/kotlin/<pkg>/
  ui/  editor/  record/  projects/  components/  theme/
  vm/  EditorViewModel.kt  RecordViewModel.kt  ProjectListViewModel.kt
  MainActivity.kt  AudioEditorApp.kt
```

### 4.3 Data model

Keep the model tiny and value-like so undo is cheap (no PCM copied into snapshots).

```kotlin
data class Project(
    val id: String,
    val name: String,
    val sampleRate: Int = 44_100,
    val channels: Int = 1,
    val tracks: List<Track> = emptyList()
)

data class Track(
    val id: String,
    val name: String,
    val clips: List<Clip> = emptyList(),
    val gain: Float = 1f,
    val muted: Boolean = false,
    val solo: Boolean = false
)

data class Clip(
    val id: String,
    val sourceId: String,
    val sourceInMs: Long,
    val sourceOutMs: Long,
    val timelineStartMs: Long,
    val gain: Float = 1f,
    val fadeInMs: Long = 0,
    val fadeOutMs: Long = 0
)

data class AudioSourceRef(
    val id: String,
    val wavPath: String,     // relative to the project directory
    val durationMs: Long,
    val sampleRate: Int,
    val channels: Int,
    val peaksPath: String?
)
```

Semantics:

- **Crop / trim** → change `sourceInMs` / `sourceOutMs`.
- **Drag** → change `timelineStartMs`.
- **Split** → two `Clip`s with adjacent boundaries.
- **Delete** → remove a `Clip` from `Track.clips`.
- **Undo/redo** → `UndoStack` of `Project` snapshots (cap ~100); no audio data in snapshots.
- **Denoise** → the only destructive op: write a *new* WAV source, point the clip at
  it, keep the old file until the project is closed (so undo can still switch back).

### 4.4 Key subsystem designs

#### Recording

- `RECORD_AUDIO` runtime permission with a clear rationale dialog.
- `AudioRecord`: 44.1 kHz, mono, PCM 16-bit, voice/mic source; read buffers on a
  background thread; write directly to a WAV file in the project's `sources/` dir.
- Foreground service with a persistent notification for background recording
  (`POST_NOTIFICATIONS` on API 33+).
- Live UI: RMS/peak level meter + scrolling waveform fed by short-window amplitude
  values; drive smooth animation from frame time, not 100 ms UI ticks.
- Optional later: attach `NoiseSuppressor`/AGC to the `AudioRecord` session (device
  dependent; not required for the Audacity-style post-processing MVP).

#### Import / decode

- SAF `ACTION_OPEN_DOCUMENT` with audio MIME filter — no storage permission needed.
- Copy the document into app-internal storage, then decode with `MediaExtractor` +
  `MediaCodec` into 16-bit PCM WAV at the project sample rate.
- If a source format can't be decoded by the device, report a clear error instead
  of silently failing. A broad-format fallback via FFmpegKit is a later option.
- After decoding: compute the peak pyramid and write the peak cache.

#### Waveform peaks

- For each source, compute min/max pairs per bucket at multiple resolutions
  (e.g. 256, 1024, 4096 samples/bucket) — a small peak pyramid.
- Persist as a compact binary file (`peaks/<sourceId>.bin`) next to the WAV.
- Timeline selects the resolution from current zoom; Canvas draws vertical min/max
  lines. Zooming never requires re-reading full PCM from disk.

#### Playback / mixing

- `AudioTrack` in streaming mode, 20–40 ms buffers.
- `StreamingMixerPlayer` walks active clips for the requested time range, reads the
  relevant PCM from each source, applies clip gain + fades + track gain/mute/solo,
  resamples if needed, and sums into a `FloatArray`, then converts to PCM16.
- Playhead position comes from `AudioTrack.playbackHeadPosition` for accurate UI
  sync; scrubbing stops/restarts playback at the new position.
- Long-file strategy: sources live on disk; read only the needed ranges (with a
  small cache) rather than holding all PCM in memory.

#### Editing gestures (Compose Canvas)

- Pointer input on each track row:
  - drag clip body → move `timelineStartMs`, with snapping to playhead, clip edges
    and grid;
  - drag left/right handles → adjust `sourceInMs` / `sourceOutMs` with a minimum
    duration;
  - drag on empty area or drag with selection tool → time selection region;
  - tap ruler → seek.
- Track reorder via a side list with drag handles (or up/down controls in MVP).
- Zoom controls (pinch or buttons) and horizontal pan.

#### Denoise DSP (pure Kotlin)

1. User selects a noise-only region → "Get noise profile".
2. Analyze with Hann-windowed FFT frames (2048 or 4096-point radix-2 FFT, ~50%
   overlap); average magnitude per frequency bin into `NoiseProfile`.
3. Reduction pass: for each frame, compute magnitude spectrum; per-bin gain
   `g = clamp(1 - (noiseMagnitude[bin] * strength) / max(magnitude[bin], eps), 0, 1)`;
   smooth gains across frequency and time to avoid "musical noise"; apply gain,
   inverse FFT, overlap-add back into PCM.
4. UI: reduction amount (dB) and smoothing controls, A/B preview on the selection
   or whole clip, progress + cancellation for long files.
5. Apply writes a new WAV source. Keep the original for undo.

#### Export

- `WavExporter` renders the full timeline (all tracks, clips, gains, fades) through
  the same mixer used for playback, then writes a standard 16-bit PCM WAV.
- SAF `ACTION_CREATE_DOCUMENT` chooses destination and filename.
- Show progress and allow cancellation for long renders.

#### Persistence

- `filesDir/projects/<projectId>/`
  - `project.json` — serialized `Project` + source metadata
  - `sources/<sourceId>.wav`
  - `peaks/<sourceId>.bin`
- Autosave with debounce after edits; explicit save not required.
- Project list screen: create, rename, duplicate (optional), delete with cleanup.

#### Permissions summary

| Permission | When | Notes |
| --- | --- | --- |
| `RECORD_AUDIO` | Before recording | Runtime request with rationale |
| `POST_NOTIFICATIONS` | API 33+, for foreground service | Runtime request |
| Foreground service | During recording | Persistent notification |
| SAF intents | Import/export | No storage permission needed |

---

## 5. UI structure

- `ProjectListScreen` — create/open/delete projects.
- `EditorScreen` — top bar (project name, undo/redo, export), transport controls
  (play/pause, stop, loop optional), multitrack timeline (ruler + track rows),
  track list, selection tools, bottom sheet with clip/track properties.
- `RecordScreen` — level meter, live waveform, timer, start/pause/stop, save.
- `NoiseProfileDialog` — select noise region, capture profile, adjust reduction,
  preview, apply.
- `Import/Export` — SAF intents plus progress dialogs.

Design rules: large touch targets for timeline handles, clear active/inactive states,
dark theme support, content descriptions for accessibility, no destructive action
without undo.

---

## 6. Build phases

Each phase should end with a runnable app and a committed checkpoint.

### Phase 1 — Scaffold and single-track editing

- [ ] Gradle wrapper + AGP/Gradle compatible versions; three modules; Compose theme
- [ ] Project list + empty editor screen + navigation
- [ ] SAF import → decode to WAV → source metadata
- [ ] Peak computation + cache; waveform Canvas
- [ ] AudioTrack playback, play/pause/stop, seek/scrub
- [ ] Time selection, crop/trim, split, delete
- [ ] WAV export via SAF
- [ ] Undo/redo stack
- [ ] Unit tests: WAV round-trip, peaks, mixer boundaries, undo

### Phase 2 — Recording

- [ ] Permission flow + rationale
- [ ] AudioRecord capture to WAV + foreground service
- [ ] Live level meter / scrolling waveform
- [ ] Save recording as a source and append to timeline
- [ ] Instrumentation test: record → stop → source exists with expected duration

### Phase 3 — Multitrack

- [ ] Add/remove/reorder tracks; mute/solo/gain
- [ ] Clip drag with snapping; trim handles; multi-clip tracks
- [ ] Mixed playback across tracks
- [ ] Project JSON persistence + autosave + load
- [ ] UI tests for drag/crop on emulator

### Phase 4 — Noise reduction

- [ ] FFT + Hann window + overlap-add in `core-audio`
- [ ] Noise-profile capture from a selection
- [ ] Spectral reduction with strength/smoothing controls + A/B preview
- [ ] Apply as new WAV source; undo restores original
- [ ] DSP tests: attenuate injected stationary noise, preserve sine tone

### Phase 5 — Polish and hardening

- [ ] Performance: disk-backed PCM reads, peak pyramid validation, large-file tests
- [ ] Cancellation/progress for decode, denoise and export
- [ ] Accessibility pass, edge cases (empty project, tiny clips, permission denial)
- [ ] Instrumentation flow test: record → import → edit → denoise → export
- [ ] README with build/run instructions and architecture overview

---

## 7. Testing strategy

- **Unit (core-audio, JVM):** FFT correctness (sine peak in expected bin), WAV header
  and round-trip, resampler, mixer clipping/gain behavior, crop/split model ops,
  undo/redo, denoise SNR improvement on synthetic noisy audio.
- **Instrumentation (app/device):** permissions, AudioRecord → WAV, MediaCodec decode
  of a bundled sample, SAF import/export (where testable), AudioTrack playback,
  project save/load.
- **Compose UI:** navigation, toolbar actions, basic gesture interactions, dialogs.
- **Manual device checklist:** latency while scrubbing, background recording,
  long files, low storage, rotation, doze, different sample rates/formats.

---

## 8. Risks and mitigations

| Risk | Mitigation |
| --- | --- |
| JDK/Gradle/AGP incompatibility on this machine | Pin wrapper + compatible JDK (likely 17); verify early in Phase 1 |
| Device codec gaps on import | Clear errors; keep WAV path guaranteed; optional FFmpeg fallback later |
| Denoise artifacts ("musical noise") | Frequency/time smoothing, conservative defaults, A/B preview |
| Memory pressure on long recordings | Disk-backed PCM, range reads, peak pyramid, no full-project buffers |
| Timeline gesture complexity | Start with horizontal drag + handles; vertical drag later |
| Audio glitches during playback | Streaming mixer with adequate buffer, position from `playbackHeadPosition` |
| Foreground service restrictions | Follow API 26+ rules; notification permission on 33+ |

---

## 9. Conventions

- Kotlin official style; `kts` build scripts; feature packages under each module.
- Keep `core-audio` free of Android imports so DSP stays JVM-testable.
- Use `StateFlow`/`Compose State` for UI state; long-running audio work in coroutines
  with `Dispatchers.IO` or dedicated threads; never block the main thread.
- Every destructive user action must be undoable.
- Commit per phase with a short, descriptive message; keep the app buildable at HEAD.

---

## 10. Future upgrades (after MVP)

- Compressed export: FLAC first (open, lossless), then Opus/OGG where supported;
  AAC/M4A only if the licensing trade-off is accepted; MP3 via LGPL FFmpegKit fork
  only with explicit decision.
- Stereo-aware editing UI and per-channel waveforms.
- Vertical cross-track clip drag, loop editing, fades UI, normalization, silence trim.
- Improved denoise (stronger models / WebRTC APM / on-device ML) as optional modules.
- Tablet-optimized large timeline, keyboard/mouse shortcuts.
- Effects chain architecture (plugins operating on `PcmBuffer`) once the core is stable.

---

## 11. Definition of done for v1

An installed debug build can: create a project, record a clip, import a file, arrange
and crop multiple clips on a timeline, capture a noise profile and reduce noise with
preview, save/reopen the project, and export a WAV — with undo/redo working throughout
and no proprietary dependencies.
