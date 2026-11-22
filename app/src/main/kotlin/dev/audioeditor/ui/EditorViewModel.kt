package dev.audioeditor.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.audioeditor.R
import dev.audioeditor.core.dsp.NoiseProfile
import dev.audioeditor.core.model.AudioSourceRef
import dev.audioeditor.core.model.Clip
import dev.audioeditor.core.model.Project
import dev.audioeditor.core.model.ProjectEdits
import dev.audioeditor.core.model.ProjectOps
import dev.audioeditor.core.model.Track
import dev.audioeditor.core.model.UndoStack
import dev.audioeditor.core.model.newId
import dev.audioeditor.core.pcm.PeakPyramid
import dev.audioeditor.core.pcm.WavCodec
import dev.audioeditor.platform.audio.AudioExporter
import dev.audioeditor.platform.audio.AudioImporter
import dev.audioeditor.platform.audio.AudioRecorder
import dev.audioeditor.platform.audio.NoiseProcessor
import dev.audioeditor.platform.audio.ProjectPlayer
import dev.audioeditor.platform.storage.ProjectStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class EditorViewModel(application: Application) : AndroidViewModel(application) {

    private val store = ProjectStore(application)
    private val recorder = AudioRecorder()
    private val history = UndoStack(store.loadOrCreate())

    private val _project = MutableStateFlow(history.current)
    val project: StateFlow<Project> = _project.asStateFlow()

    private val _peaks = MutableStateFlow<Map<String, PeakPyramid>>(emptyMap())
    val peaks: StateFlow<Map<String, PeakPyramid>> = _peaks.asStateFlow()

    private val _selectedClipId = MutableStateFlow<String?>(null)
    val selectedClipId: StateFlow<String?> = _selectedClipId.asStateFlow()

    private val _status = MutableStateFlow(text(R.string.status_ready))
    val status: StateFlow<String> = _status.asStateFlow()

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _isWorking = MutableStateFlow(false)
    val isWorking: StateFlow<Boolean> = _isWorking.asStateFlow()

    val recordingWaveform: StateFlow<List<Float>> = recorder.waveform
    val recordingElapsedMs: StateFlow<Long> = recorder.elapsedMs

    val player = ProjectPlayer()
    val playheadMs: StateFlow<Long> = player.playheadMs
    val isPlaying: StateFlow<Boolean> = player.isPlaying

    private var gestureBase: Project? = null
    private var capturedNoise: CapturedNoise? = null
    private var sourceIdForRecording: String? = null

    enum class NoiseScope { CURRENT_TRACK, ALL_TRACKS }

    private data class CapturedNoise(
        val sourceId: String,
        val trackId: String,
        val profile: NoiseProfile,
    )

    init {
        val loaded = _peaks.value.toMutableMap()
        _project.value.sources.forEach { source ->
            val path = source.peaksPath ?: return@forEach
            val file = File(path)
            if (file.exists()) {
                runCatching { PeakPyramid.read(file.inputStream()) }.getOrNull()?.let {
                    loaded[source.id] = it
                }
            }
        }
        _peaks.value = loaded
    }

    fun canRecord(): Boolean = AudioRecorder.hasPermission(getApplication())

    fun import(uri: Uri) = viewModelScope.launch {
        _isWorking.value = true
        _status.value = text(R.string.status_importing)
        try {
            val projectId = _project.value.id
            val sourceId = newId()
            val wavFile = store.sourceFile(projectId, sourceId)
            val peaksFile = store.peaksFile(projectId, sourceId)
            val imported = AudioImporter.import(getApplication(), uri, wavFile, peaksFile)

            val current = _project.value
            val track = current.tracks.firstOrNull() ?: Track(newId(), "Track 1")
            val clip = Clip(
                id = newId(),
                sourceId = imported.source.id,
                sourceInMs = 0L,
                sourceOutMs = imported.source.durationMs,
                timelineStartMs = ProjectOps.durationMs(current),
            )
            val tracks = if (current.tracks.isEmpty()) {
                listOf(track.copy(clips = listOf(clip)))
            } else {
                current.tracks.mapIndexed { index, item ->
                    if (index == 0) item.copy(clips = item.clips + clip) else item
                }
            }
            commit(ProjectOps.addSource(current, imported.source).copy(tracks = tracks))
            _peaks.value = _peaks.value + (imported.source.id to imported.peaks)
            _selectedClipId.value = clip.id
            _status.value = text(R.string.status_imported, imported.source.name)
        } catch (e: Exception) {
            _status.value = text(
                R.string.status_import_failed,
                e.message ?: text(R.string.status_unknown_error),
            )
        } finally {
            _isWorking.value = false
        }
    }

    fun startRecording() {
        if (_isRecording.value) return
        val projectId = _project.value.id
        val sourceId = newId()
        val wavFile = store.sourceFile(projectId, sourceId)
        try {
            recorder.start(wavFile)
            sourceIdForRecording = sourceId
            _isRecording.value = true
            _status.value = text(R.string.status_recording, formatTime(0L))
        } catch (e: Exception) {
            _status.value = text(
                R.string.status_recording_start_failed,
                e.message ?: text(R.string.status_unknown_error),
            )
        }
    }

    fun stopRecording() = viewModelScope.launch {
        if (!_isRecording.value) return@launch
        _status.value = text(R.string.status_saving_recording)
        try {
            val result = recorder.stop() ?: return@launch
            _isRecording.value = false
            if (result.frameCount <= 0) {
                _status.value = text(R.string.status_nothing_recorded)
                return@launch
            }

            val projectId = _project.value.id
            val wavFile = store.sourceFile(projectId, sourceIdForRecording ?: return@launch)
            val peaksFile = store.peaksFile(projectId, wavFile.nameWithoutExtension)
            val decoded = withContext(Dispatchers.IO) { WavCodec.decode(wavFile) }
            val peaks = withContext(Dispatchers.IO) { PeakPyramid.build(decoded) }
            withContext(Dispatchers.IO) { peaksFile.outputStream().use { peaks.write(it) } }

            val source = AudioSourceRef(
                id = wavFile.nameWithoutExtension,
                name = "Recording",
                wavPath = wavFile.absolutePath,
                durationMs = result.durationMs,
                sampleRate = result.sampleRate,
                channels = result.channels,
                peaksPath = peaksFile.absolutePath,
            )
            val current = _project.value
            val track = current.tracks.firstOrNull() ?: Track(newId(), "Track 1")
            val clip = Clip(
                id = newId(),
                sourceId = source.id,
                sourceInMs = 0L,
                sourceOutMs = source.durationMs,
                timelineStartMs = ProjectOps.durationMs(current),
            )
            val tracks = if (current.tracks.isEmpty()) {
                listOf(track.copy(clips = listOf(clip)))
            } else {
                current.tracks.mapIndexed { index, item ->
                    if (index == 0) item.copy(clips = item.clips + clip) else item
                }
            }
            commit(ProjectOps.addSource(current, source).copy(tracks = tracks))
            _peaks.value = _peaks.value + (source.id to peaks)
            _selectedClipId.value = clip.id
            _status.value = text(R.string.status_recording_added)
        } catch (e: Exception) {
            _isRecording.value = false
            _status.value = text(
                R.string.status_recording_save_failed,
                e.message ?: text(R.string.status_unknown_error),
            )
        }
    }

    fun export(uri: Uri) = viewModelScope.launch {
        _isWorking.value = true
        _status.value = text(R.string.status_exporting)
        try {
            val written = AudioExporter.export(getApplication(), _project.value, uri)
            _status.value = text(R.string.status_exported, written / 1_000)
        } catch (e: Exception) {
            _status.value = text(
                R.string.status_export_failed,
                e.message ?: text(R.string.status_unknown_error),
            )
        } finally {
            _isWorking.value = false
        }
    }

    fun togglePlayback() {
        if (player.isPlaying.value) {
            player.pause()
        } else {
            val start = if (playheadMs.value >= ProjectOps.durationMs(_project.value)) 0L else playheadMs.value
            player.play(_project.value, start)
        }
    }

    fun seek(ms: Long) = player.seek(ms)

    fun selectClip(id: String?) {
        _selectedClipId.value = id
    }

    fun beginGesture() {
        gestureBase = _project.value
    }

    fun moveClip(
        trackId: String,
        clipId: String,
        targetTrackId: String,
        startMs: Long,
    ) {
        val next = ProjectEdits.moveToTrack(_project.value, trackId, clipId, targetTrackId, startMs)
        if (gestureBase != null) publish(next) else commit(next)
        if (gestureBase == null) _status.value = text(R.string.status_clip_moved)
    }

    fun trimLeft(trackId: String, clipId: String, sourceInMs: Long) {
        val next = ProjectEdits.trimLeft(_project.value, trackId, clipId, sourceInMs)
        if (gestureBase != null) publish(next) else commit(next)
    }

    fun trimRight(trackId: String, clipId: String, sourceOutMs: Long) {
        val next = ProjectEdits.trimRight(_project.value, trackId, clipId, sourceOutMs)
        if (gestureBase != null) publish(next) else commit(next)
    }

    fun endGesture() {
        val base = gestureBase
        gestureBase = null
        if (base != null && base != _project.value) commit(_project.value)
    }

    fun splitClip(trackId: String, clipId: String, atMs: Long) {
        commit(ProjectEdits.split(_project.value, trackId, clipId, atMs))
        _status.value = text(R.string.status_split_at, formatTime(atMs))
    }

    fun deleteClip(trackId: String, clipId: String) {
        if (_selectedClipId.value == clipId) _selectedClipId.value = null
        commit(ProjectEdits.deleteClip(_project.value, trackId, clipId))
        _status.value = text(R.string.status_clip_deleted)
    }

    fun captureNoiseProfile() {
        val selected = selectedClip() ?: return
        val clip = selected.second
        val source = ProjectOps.sourceById(_project.value, clip.sourceId) ?: return
        viewModelScope.launch {
            _isWorking.value = true
            _status.value = text(R.string.status_learning_noise)
            try {
                val buffer = withContext(Dispatchers.IO) { WavCodec.decode(File(source.wavPath)) }
                val profile = withContext(Dispatchers.IO) {
                    dev.audioeditor.core.dsp.NoiseReducer.captureProfile(
                        buffer,
                        (clip.sourceInMs * buffer.sampleRate / 1_000L).toInt(),
                        (clip.sourceOutMs * buffer.sampleRate / 1_000L).toInt(),
                    )
                }
                capturedNoise = CapturedNoise(source.id, selected.first.id, profile)
                _status.value = text(R.string.status_noise_captured, clip.durationMs)
            } catch (e: Exception) {
                _status.value = text(
                    R.string.status_noise_capture_failed,
                    e.message ?: text(R.string.status_unknown_error),
                )
            } finally {
                _isWorking.value = false
            }
        }
    }

    fun reduceNoise(scope: NoiseScope, trackId: String? = null) {
        val captured = capturedNoise ?: run {
            _status.value = text(R.string.status_noise_profile_missing)
            return
        }
        val targetTrackId = if (scope == NoiseScope.CURRENT_TRACK) {
            trackId ?: captured.trackId
        } else {
            null
        }
        viewModelScope.launch {
            _isWorking.value = true
            _status.value = text(R.string.status_reducing_noise)
            try {
                val current = _project.value
                val sourceIds = current.tracks
                    .filter { targetTrackId == null || it.id == targetTrackId }
                    .flatMap { track -> track.clips.map { it.sourceId } }
                    .distinct()
                val sources = sourceIds.mapNotNull { id ->
                    ProjectOps.sourceById(current, id)?.let { id to it }
                }
                if (sources.isEmpty()) {
                    _status.value = text(R.string.status_noise_no_audio)
                    return@launch
                }

                val replacements = mutableMapOf<String, AudioSourceRef>()
                val newPeaks = mutableMapOf<String, PeakPyramid>()
                sources.forEach { (oldId, source) ->
                    val denoised = NoiseProcessor.denoise(
                        source = source,
                        profile = captured.profile,
                        wavFile = store.sourceFile(current.id, newId()),
                        peaksFile = store.peaksFile(current.id, newId()),
                    )
                    replacements[oldId] = denoised
                    val peaksPath = denoised.peaksPath ?: return@forEach
                    newPeaks[denoised.id] = PeakPyramid.read(File(peaksPath).inputStream())
                }

                val tracks = current.tracks.map { track ->
                    if (targetTrackId != null && track.id != targetTrackId) {
                        track
                    } else {
                        track.copy(clips = track.clips.map { clip ->
                            replacements[clip.sourceId]?.let { clip.copy(sourceId = it.id) } ?: clip
                        })
                    }
                }
                val withSources = replacements.values.fold(current) { project, source ->
                    ProjectOps.addSource(project, source)
                }
                commit(withSources.copy(tracks = tracks))
                _peaks.value = _peaks.value + newPeaks
                capturedNoise = null
                _status.value = text(
                    if (scope == NoiseScope.CURRENT_TRACK) {
                        R.string.status_noise_reduced_current
                    } else {
                        R.string.status_noise_reduced_all
                    },
                )
            } catch (e: Exception) {
                _status.value = text(
                    R.string.status_noise_failed,
                    e.message ?: text(R.string.status_unknown_error),
                )
            } finally {
                _isWorking.value = false
            }
        }
    }

    fun undo() {
        if (history.undo()) {
            publish(history.current)
            _status.value = text(R.string.status_undo)
        }
    }

    fun redo() {
        if (history.redo()) {
            publish(history.current)
            _status.value = text(R.string.status_redo)
        }
    }

    val canUndo: Boolean get() = history.canUndo
    val canRedo: Boolean get() = history.canRedo

    private fun commit(next: Project) {
        history.commit(next)
        publish(history.current)
        store.save(history.current)
    }

    private fun publish(next: Project) {
        _project.value = next
    }

    private fun text(id: Int, vararg args: Any): String =
        getApplication<Application>().getString(id, *args)

    private fun selectedClip(): Pair<Track, Clip>? {
        val id = _selectedClipId.value ?: return null
        _project.value.tracks.forEach { track ->
            track.clips.firstOrNull { it.id == id }?.let { return track to it }
        }
        return null
    }

    override fun onCleared() {
        player.dispose()
        super.onCleared()
    }
}
