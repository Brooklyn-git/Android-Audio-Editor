package dev.audioeditor.platform.audio

import dev.audioeditor.core.dsp.NoiseProfile
import dev.audioeditor.core.dsp.NoiseReducer
import dev.audioeditor.core.model.AudioSourceRef
import dev.audioeditor.core.model.newId
import dev.audioeditor.core.pcm.PeakPyramid
import dev.audioeditor.core.pcm.WavCodec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Learns a noise profile from a selection and writes a denoised copy of a source. */
object NoiseProcessor {

    suspend fun denoise(
        source: AudioSourceRef,
        profile: NoiseProfile,
        reductionDb: Float = 12f,
        wavFile: File,
        peaksFile: File,
    ): AudioSourceRef = withContext(Dispatchers.IO) {
        val buffer = WavCodec.decode(File(source.wavPath))
        val reduced = NoiseReducer.reduce(buffer, profile, reductionDb)

        WavCodec.encode(wavFile, reduced.sampleRate, reduced.channels, reduced.samples)
        val peaks = PeakPyramid.build(reduced)
        peaksFile.outputStream().use { peaks.write(it) }

        AudioSourceRef(
            id = newId(),
            name = "${source.name} denoised",
            wavPath = wavFile.absolutePath,
            durationMs = reduced.frameCount * 1_000L / reduced.sampleRate,
            sampleRate = reduced.sampleRate,
            channels = reduced.channels,
            peaksPath = peaksFile.absolutePath,
        )
    }
}
