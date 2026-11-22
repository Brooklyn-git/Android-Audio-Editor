package dev.audioeditor.platform.audio

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import dev.audioeditor.core.model.AudioSourceRef
import dev.audioeditor.core.model.newId
import dev.audioeditor.core.pcm.PeakPyramid
import dev.audioeditor.core.pcm.WavCodec
import dev.audioeditor.core.pcm.WavFileWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Decodes any audio format Android supports into a 16-bit PCM WAV owned by the project. */
object AudioImporter {

    data class ImportedAudio(
        val source: AudioSourceRef,
        val peaks: PeakPyramid,
    )

    suspend fun import(
        context: Context,
        uri: Uri,
        wavFile: File,
        peaksFile: File,
    ): ImportedAudio = withContext(Dispatchers.IO) {
        val extractor = MediaExtractor().apply { setDataSource(context, uri, null) }
        try {
            val trackIndex = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index)
                    .getString(MediaFormat.KEY_MIME)
                    ?.startsWith("audio/") == true
            } ?: error("Selected document contains no audio track")

            val format = extractor.getTrackFormat(trackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME)!!
            extractor.selectTrack(trackIndex)

            val codec = MediaCodec.createDecoderByType(mime)
            var sampleRate = format.intOr(MediaFormat.KEY_SAMPLE_RATE, 44_100)
            var channels = format.intOr(MediaFormat.KEY_CHANNEL_COUNT, 1)
            var writer: WavFileWriter? = null
            try {
                codec.configure(format, null, null, 0)
                codec.start()

                val info = MediaCodec.BufferInfo()
                val timeoutUs = 10_000L
                var inputEos = false
                var outputEos = false

                while (!outputEos) {
                    if (!inputEos) {
                        val inputIndex = codec.dequeueInputBuffer(timeoutUs)
                        if (inputIndex >= 0) {
                            val inputBuffer = codec.getInputBuffer(inputIndex)!!
                            inputBuffer.clear()
                            val size = extractor.readSampleData(inputBuffer, 0)
                            if (size < 0) {
                                codec.queueInputBuffer(
                                    inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                                )
                                inputEos = true
                            } else {
                                val pts = extractor.sampleTime
                                codec.queueInputBuffer(inputIndex, 0, size, maxOf(0L, pts), 0)
                                extractor.advance()
                            }
                        }
                    }

                    when (val outputIndex = codec.dequeueOutputBuffer(info, timeoutUs)) {
                        MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                        MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> Unit
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val outputFormat = codec.outputFormat
                            sampleRate = outputFormat.intOr(MediaFormat.KEY_SAMPLE_RATE, sampleRate)
                            channels = outputFormat.intOr(MediaFormat.KEY_CHANNEL_COUNT, channels)
                            val encoding = outputFormat.intOr(
                                MediaFormat.KEY_PCM_ENCODING,
                                AudioFormat.ENCODING_PCM_16BIT,
                            )
                            require(encoding == AudioFormat.ENCODING_PCM_16BIT) {
                                "Only 16-bit PCM decoder output is supported"
                            }
                            val existing = writer
                            if (existing == null) {
                                writer = WavFileWriter(wavFile, sampleRate, channels)
                            } else {
                                require(existing.sampleRate == sampleRate && existing.channels == channels) {
                                    "Decoder changed its output format mid-stream"
                                }
                            }
                        }
                        else -> {
                            if (outputIndex >= 0) {
                                if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                                    val outputBuffer = codec.getOutputBuffer(outputIndex)!!
                                    val currentWriter = writer ?: WavFileWriter(wavFile, sampleRate, channels)
                                        .also { writer = it }
                                    val bytes = ByteArray(info.size)
                                    outputBuffer.position(info.offset)
                                    outputBuffer.limit(info.offset + info.size)
                                    outputBuffer.get(bytes)
                                    currentWriter.write(pcm16ToFloat(bytes))
                                }
                                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                                    outputEos = true
                                }
                                codec.releaseOutputBuffer(outputIndex, false)
                            }
                        }
                    }
                }
            } finally {
                codec.stop()
                codec.release()
            }

            val activeWriter = writer ?: error("Decoder produced no audio")
            activeWriter.close()

            val decoded = WavCodec.decode(wavFile)
            val peaks = PeakPyramid.build(decoded)
            peaksFile.outputStream().use { peaks.write(it) }

            val durationMs = decoded.frameCount * 1_000L / decoded.sampleRate
            ImportedAudio(
                source = AudioSourceRef(
                    id = newId(),
                    name = wavFile.nameWithoutExtension,
                    wavPath = wavFile.absolutePath,
                    durationMs = durationMs,
                    sampleRate = decoded.sampleRate,
                    channels = decoded.channels,
                    peaksPath = peaksFile.absolutePath,
                ),
                peaks = peaks,
            )
        } finally {
            extractor.release()
        }
    }

    private fun MediaFormat.intOr(key: String, fallback: Int): Int =
        if (containsKey(key)) getInteger(key) else fallback

    private fun pcm16ToFloat(bytes: ByteArray): FloatArray {
        require(bytes.size % 2 == 0) { "Truncated PCM output" }
        return FloatArray(bytes.size / 2) { i ->
            val low = bytes[i * 2].toInt() and 0xff
            val high = bytes[i * 2 + 1].toInt() and 0xff
            ((low or (high shl 8)).toShort()) / 32768f
        }
    }
}
