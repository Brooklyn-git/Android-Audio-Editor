package dev.audioeditor.core.pcm

import dev.audioeditor.core.model.AudioSourceRef
import dev.audioeditor.core.model.Clip
import dev.audioeditor.core.model.Project
import dev.audioeditor.core.model.Track
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class MixerTest {

    private val sourceRef = AudioSourceRef("s1", "source.wav", "sources/source.wav", 100L, 1_000, 1)

    private fun source(values: FloatArray) = ArrayPcmSource(PcmBuffer(1_000, 1, values))

    private fun project(vararg tracks: Track) = Project(
        sampleRate = 1_000,
        channels = 1,
        sources = listOf(sourceRef),
        tracks = tracks.toList(),
    )

    private fun track(vararg clips: Clip) = Track("t1", "Track 1", clips.toList())

    @Test
    fun mixesClipWithGain() {
        val p = project(track(Clip("c1", "s1", 0L, 100L, 0L, gain = 0.5f)))
        val out = Mixer.mix(p, mapOf("s1" to source(FloatArray(100) { 0.5f })), 0, 100)

        assertEquals(100, out.size)
        out.forEach { assertEquals(0.25f, it, 1e-6f) }
    }

    @Test
    fun cropAndDragSelectSourceRangeAndTimelinePosition() {
        val samples = FloatArray(100) { it / 100f }
        val clip = Clip("c1", "s1", 10L, 30L, 5L)
        val p = project(track(clip))
        val out = Mixer.mix(p, mapOf("s1" to source(samples)), 0, 40)

        assertEquals(0f, out[4], 0f)
        assertEquals(0.10f, out[5], 1e-6f)
        assertEquals(0.29f, out[24], 1e-6f)
        assertEquals(0f, out[25], 0f)
    }

    @Test
    fun overlappingClipsAreSummedAndClamped() {
        val samples = FloatArray(100) { 0.4f }
        val p = project(
            track(
                Clip("a", "s1", 0L, 100L, 0L, gain = 0.5f),
                Clip("b", "s1", 0L, 100L, 0L, gain = 0.5f),
            ),
        )
        val out = Mixer.mix(p, mapOf("s1" to source(samples)), 0, 10)
        out.forEach { assertEquals(0.4f, it, 1e-6f) }
    }

    @Test
    fun muteAndSoloAffectAudibility() {
        val samples = FloatArray(100) { 0.5f }
        val sources = mapOf("s1" to source(samples))
        val clip = Clip("c1", "s1", 0L, 20L, 0L)

        val muted = project(Track("t1", "T1", listOf(clip), muted = true))
        Mixer.mix(muted, sources, 0, 20).forEach { assertEquals(0f, it, 0f) }

        val soloed = project(
            Track("t1", "T1", listOf(clip), solo = true),
            Track("t2", "T2", listOf(clip.copy(id = "c2"))),
        )
        val out = Mixer.mix(soloed, sources, 0, 20)
        out.forEach { assertEquals(0.5f, it, 1e-6f) }
    }

    @Test
    fun fadesScaleSamplesAtClipEdges() {
        val clip = Clip("c1", "s1", 0L, 100L, 0L, fadeInMs = 100L)
        val p = project(track(clip))
        val out = Mixer.mix(p, mapOf("s1" to source(FloatArray(100) { 0.5f })), 0, 100)

        assertEquals(0f, out[0], 1e-6f)
        assertEquals(0.25f, out[50], 1e-6f)
        assertEquals(0.495f, out[99], 1e-6f)
    }

    @Test
    fun rendersProjectToWav() {
        val p = project(track(Clip("c1", "s1", 0L, 50L, 0L)))
        val bytes = ByteArrayOutputStream()
        val written = Mixer.mixToWav(p, mapOf("s1" to source(FloatArray(100) { 0.25f })), bytes)
        assertEquals(50, written)

        val decoded = WavCodec.decode(ByteArrayInputStream(bytes.toByteArray()))
        assertEquals(1_000, decoded.sampleRate)
        assertEquals(50, decoded.frameCount)
        assertEquals(0.25f, decoded[0, 0], 1e-4f)
    }

    @Test
    fun renderingCanBeCancelled() {
        val p = project(track(Clip("c1", "s1", 0L, 10_000L, 0L)))
        val bytes = ByteArrayOutputStream()
        var calls = 0
        val written = Mixer.mixToWav(p, mapOf("s1" to source(FloatArray(10_000))), bytes) {
            calls++
            false
        }
        assertEquals(4_096, written)
        assertEquals(1, calls)
    }
}
