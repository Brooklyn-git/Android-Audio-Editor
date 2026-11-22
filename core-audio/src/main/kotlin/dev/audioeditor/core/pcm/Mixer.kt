package dev.audioeditor.core.pcm

import dev.audioeditor.core.model.Clip
import dev.audioeditor.core.model.Project
import dev.audioeditor.core.model.ProjectOps
import dev.audioeditor.core.model.Track
import java.io.OutputStream
import kotlin.math.floor

/** Sums every audible clip into a single PCM stream. */
object Mixer {

    private const val CHUNK_FRAMES = 4_096

    fun durationFrames(project: Project): Int =
        ProjectOps.msToFrames(ProjectOps.durationMs(project), project.sampleRate).toInt()

    /** Mixes a range of timeline frames into interleaved PCM. */
    fun mix(
        project: Project,
        sources: Map<String, PcmSource>,
        startFrame: Int,
        frames: Int,
    ): FloatArray {
        require(startFrame >= 0 && frames >= 0)
        val output = FloatArray(frames * project.channels)
        if (frames == 0) return output

        val endFrame = startFrame + frames
        for (track in project.tracks) {
            if (!ProjectOps.isTrackAudible(project, track)) continue
            for (clip in track.clips) {
                val source = sources[clip.sourceId] ?: continue
                mixClip(project, track, clip, source, startFrame, endFrame, output)
            }
        }
        return output
    }

    private fun mixClip(
        project: Project,
        track: Track,
        clip: Clip,
        source: PcmSource,
        startFrame: Int,
        endFrame: Int,
        output: FloatArray,
    ) {
        val clipStart = ProjectOps.msToFrames(clip.timelineStartMs, project.sampleRate)
        val clipFrames = ProjectOps.msToFrames(clip.durationMs, project.sampleRate).toInt()
        val localStart = maxOf(0L, startFrame - clipStart)
        val localEnd = minOf(clipFrames.toLong(), endFrame - clipStart)
        if (localStart >= localEnd) return

        val ratio = source.sampleRate.toDouble() / project.sampleRate
        val sourceInFrames = ProjectOps.msToFrames(clip.sourceInMs, project.sampleRate)
        val sourceOffset = floor(sourceInFrames * ratio).toInt()
        val base = floor(localStart * ratio).toInt()
        val sourceStart = (sourceOffset + base).coerceIn(0, maxOf(0, source.frameCount - 1))
        val framesToRead = minOf(
            source.frameCount - sourceStart,
            floor((localEnd - localStart) * ratio).toInt() + 1,
        )
        if (framesToRead <= 0) return

        val buffer = FloatArray(framesToRead * source.channels)
        if (source.readFrames(sourceStart, framesToRead, buffer) <= 0) return

        val count = (localEnd - localStart).toInt()
        val outStart = (clipStart + localStart).toInt()
        for (local in 0 until count) {
            val localFrame = localStart + local
            val sourceFrame = floor(localFrame * ratio).toInt() + sourceOffset - sourceStart
            if (sourceFrame !in 0 until framesToRead) continue

            val localMs = ProjectOps.framesToMs(localFrame, project.sampleRate)
            var gain = clip.gain * track.gain
            if (clip.fadeInMs > 0) {
                gain *= (localMs.toFloat() / clip.fadeInMs).coerceIn(0f, 1f)
            }
            if (clip.fadeOutMs > 0) {
                gain *= ((clip.durationMs - localMs).toFloat() / clip.fadeOutMs).coerceIn(0f, 1f)
            }

            val outputIndex = (outStart + local - startFrame) * project.channels
            for (channel in 0 until project.channels) {
                val value = sampleAt(buffer, sourceFrame, source.channels, channel, project.channels)
                val index = outputIndex + channel
                output[index] = (output[index] + value * gain).coerceIn(-1f, 1f)
            }
        }
    }

    private fun sampleAt(
        buffer: FloatArray,
        frame: Int,
        sourceChannels: Int,
        channel: Int,
        projectChannels: Int,
    ): Float {
        val base = frame * sourceChannels
        return when {
            sourceChannels == projectChannels -> buffer[base + channel]
            sourceChannels == 1 -> buffer[base]
            projectChannels == 1 -> (buffer[base] + buffer[base + 1]) * 0.5f
            else -> buffer[base + minOf(channel, sourceChannels - 1)]
        }
    }

    /** Renders the whole project to a WAV stream; return false from [progress] to cancel. */
    fun mixToWav(
        project: Project,
        sources: Map<String, PcmSource>,
        output: OutputStream,
        progress: (fraction: Float) -> Boolean = { true },
    ): Int {
        val totalFrames = durationFrames(project)
        WavCodec.WavStreamWriter(output, project.sampleRate, project.channels, totalFrames).use { writer ->
            var frame = 0
            while (frame < totalFrames) {
                val count = minOf(CHUNK_FRAMES, totalFrames - frame)
                writer.write(mix(project, sources, frame, count))
                frame += count
                if (!progress(frame.toFloat() / totalFrames)) break
            }
            return frame
        }
    }
}
