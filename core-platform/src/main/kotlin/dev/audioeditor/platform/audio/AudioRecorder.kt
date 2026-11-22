package dev.audioeditor.platform.audio

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import androidx.core.content.ContextCompat
import dev.audioeditor.core.pcm.Pcm16
import dev.audioeditor.core.pcm.WavFileWriter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.abs
import kotlin.math.max

/** Records microphone input directly to a 16-bit PCM WAV file. */
class AudioRecorder {

    data class Result(
        val sampleRate: Int,
        val channels: Int,
        val frameCount: Long,
        val durationMs: Long,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var record: AudioRecord? = null
    private var writer: WavFileWriter? = null
    private var recording = false
    private var startRealtimeMs = 0L
    private val waveformHistory = ArrayDeque<Float>()

    private val _waveform = MutableStateFlow<List<Float>>(emptyList())
    val waveform: StateFlow<List<Float>> = _waveform.asStateFlow()

    private val _elapsedMs = MutableStateFlow(0L)
    val elapsedMs: StateFlow<Long> = _elapsedMs.asStateFlow()

    val isRecording: Boolean get() = recording

    fun start(file: File, sampleRate: Int = 44_100, channels: Int = 1) {
        check(job == null) { "Recording is already in progress" }
        check(channels == 1 || channels == 2) { "Only mono and stereo are supported" }
        require(sampleRate > 0)

        val channelMask = if (channels == 1) AudioFormat.CHANNEL_IN_MONO else AudioFormat.CHANNEL_IN_STEREO
        val minBuffer = AudioRecord.getMinBufferSize(sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT)
        check(minBuffer > 0) { "Sample rate $sampleRate is not supported by the device" }
        val bufferBytes = maxOf(minBuffer, sampleRate * channels * 2 / 10)

        val audioRecord = AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.MIC)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(channelMask)
                    .build(),
            )
            .setBufferSizeInBytes(bufferBytes)
            .build()
        check(audioRecord.state == AudioRecord.STATE_INITIALIZED) { "Could not initialize microphone" }

        val activeWriter = WavFileWriter(file, sampleRate, channels)
        waveformHistory.clear()
        _waveform.value = emptyList()
        _elapsedMs.value = 0L
        startRealtimeMs = SystemClock.elapsedRealtime()
        record = audioRecord
        writer = activeWriter
        recording = true
        audioRecord.startRecording()

        job = scope.launch {
            val buffer = java.nio.ByteBuffer.allocateDirect(bufferBytes)
            try {
                while (isActive && recording) {
                    buffer.clear()
                    val read = audioRecord.read(buffer, bufferBytes)
                    if (read > 0) {
                        buffer.position(0)
                        buffer.limit(read)
                        val bytes = ByteArray(read)
                        buffer.get(bytes)
                        val samples = Pcm16.toFloats(bytes)
                        activeWriter.write(samples)
                        appendWaveformPeaks(samples)
                        _elapsedMs.value = SystemClock.elapsedRealtime() - startRealtimeMs
                    } else if (read < 0) {
                        break
                    }
                }
            } finally {
                activeWriter.close()
            }
        }
    }

    suspend fun stop(): Result? {
        val activeJob = job ?: return null
        recording = false
        activeJob.cancelAndJoin()

        val audioRecord = record ?: error("Recorder was not initialized")
        try {
            audioRecord.stop()
        } finally {
            audioRecord.release()
        }

        val activeWriter = writer ?: error("WAV writer was not initialized")
        val frames = activeWriter.frameCount
        job = null
        record = null
        writer = null

        return Result(
            sampleRate = activeWriter.sampleRate,
            channels = activeWriter.channels,
            frameCount = frames,
            durationMs = frames * 1_000L / activeWriter.sampleRate,
        )
    }

    private fun appendWaveformPeaks(samples: FloatArray) {
        if (samples.isEmpty()) return
        val bucketSize = max(1, samples.size / WAVEFORM_BUCKETS)
        var offset = 0
        while (offset < samples.size) {
            val end = minOf(samples.size, offset + bucketSize)
            var peak = 0f
            for (i in offset until end) peak = max(peak, abs(samples[i]))
            waveformHistory.addLast(peak)
            while (waveformHistory.size > WAVEFORM_BUCKETS) waveformHistory.removeFirst()
            offset = end
        }
        _waveform.value = waveformHistory.toList()
    }

    companion object {
        fun hasPermission(androidContext: android.content.Context): Boolean =
            ContextCompat.checkSelfPermission(
                androidContext,
                Manifest.permission.RECORD_AUDIO,
            ) == PackageManager.PERMISSION_GRANTED

        private const val WAVEFORM_BUCKETS = 96
    }
}
