package dev.audioeditor.platform.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import dev.audioeditor.core.model.Project
import dev.audioeditor.core.pcm.FilePcmSource
import dev.audioeditor.core.pcm.Mixer
import dev.audioeditor.core.pcm.Pcm16
import dev.audioeditor.core.pcm.PcmSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Streaming playback of a project through platform AudioTrack. */
class ProjectPlayer {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    private var audioTrack: AudioTrack? = null

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _playheadMs = MutableStateFlow(0L)
    val playheadMs: StateFlow<Long> = _playheadMs.asStateFlow()

    fun play(project: Project, fromMs: Long = 0L) {
        stop()
        val totalFrames = Mixer.durationFrames(project)
        if (totalFrames <= 0) return

        val startFrame = (fromMs * project.sampleRate / 1_000L)
            .coerceIn(0L, totalFrames.toLong()).toInt()
        val channelMask = if (project.channels == 1) {
            AudioFormat.CHANNEL_OUT_MONO
        } else {
            AudioFormat.CHANNEL_OUT_STEREO
        }
        val minBuffer = AudioTrack.getMinBufferSize(
            project.sampleRate,
            channelMask,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        check(minBuffer > 0) { "Project sample rate is not supported for playback" }

        val bytesPerFrame = project.channels * 2
        val bufferFrames = 2_048
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(project.sampleRate)
                    .setChannelMask(channelMask)
                    .build(),
            )
            .setBufferSizeInBytes(maxOf(minBuffer, bufferFrames * bytesPerFrame))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        val sources: Map<String, PcmSource> = project.sources.associate { source ->
            source.id to FilePcmSource(java.io.File(source.wavPath))
        }
        audioTrack = track
        _isPlaying.value = true
        _playheadMs.value = startFrame * 1_000L / project.sampleRate

        job = scope.launch {
            withContext(Dispatchers.IO) {
                try {
                    track.play()
                    var frame = startFrame
                    while (isActive && frame < totalFrames) {
                        val count = minOf(bufferFrames, totalFrames - frame)
                        val pcm = Mixer.mix(project, sources, frame, count)
                        // AudioTrack is configured for PCM16, so write bytes, not floats.
                        val bytes = Pcm16.toBytes(pcm)
                        val written = track.write(bytes, 0, bytes.size, AudioTrack.WRITE_NON_BLOCKING)
                        if (written <= 0) {
                            delay(10)
                            continue
                        }
                        frame += written / bytesPerFrame

                        val position = (startFrame + track.playbackHeadPosition)
                            .coerceAtMost(totalFrames)
                        _playheadMs.value = position * 1_000L / project.sampleRate
                    }

                    // Let queued buffers drain so the stripe reaches the end.
                    while (isActive && startFrame + track.playbackHeadPosition < totalFrames) {
                        delay(20)
                    }
                } finally {
                    sources.values.forEach { it.close() }
                    withContext(Dispatchers.Main) {
                        if (audioTrack === track) {
                            _playheadMs.value = totalFrames * 1_000L / project.sampleRate
                            finish()
                        }
                    }
                }
            }
        }
    }

    fun pause() = stop()

    fun stop() {
        job?.let { activeJob ->
            job = null
            activeJob.cancel()
        }
        val track = audioTrack
        audioTrack = null
        if (track != null) {
            try {
                track.stop()
            } finally {
                track.release()
            }
        }
        _isPlaying.value = false
    }

    fun seek(ms: Long) {
        _playheadMs.value = maxOf(0L, ms)
    }

    private fun finish() {
        _isPlaying.value = false
    }

    fun dispose() {
        stop()
        scope.cancel()
    }
}
