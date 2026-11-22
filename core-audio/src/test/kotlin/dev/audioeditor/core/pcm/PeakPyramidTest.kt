package dev.audioeditor.core.pcm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class PeakPyramidTest {

    private fun samples(): FloatArray = FloatArray(1_000) { 0f }.also {
        it[10] = -0.75f
        it[20] = 0.5f
        it[300] = -0.25f
        it[400] = 0.9f
    }

    @Test
    fun buildsMultipleLevelsWithCorrectExtremes() {
        val peaks = PeakPyramid.build(PcmBuffer(1_000, 1, samples()), baseBucket = 64)

        assertEquals(2, peaks.levels.size)
        assertEquals(64, peaks.levels[0].samplesPerBucket)
        assertEquals(256, peaks.levels[1].samplesPerBucket)
        assertEquals(1_024, peaks.frameCount)

        assertEquals(-0.75f, peaks.levels[0].min[0], 1e-6f)
        assertEquals(0.5f, peaks.levels[0].max[0], 1e-6f)
        assertEquals(0.9f, peaks.levels[1].max[1], 1e-6f)
        assertEquals(0.5f, peaks.levels[1].max[0], 1e-6f)
    }

    @Test
    fun selectsLevelMatchingZoom() {
        val peaks = PeakPyramid.build(PcmBuffer(1_000, 1, samples()), baseBucket = 64)
        assertEquals(64, peaks.levelFor(10).samplesPerBucket)
        assertEquals(64, peaks.levelFor(100).samplesPerBucket)
        assertEquals(256, peaks.levelFor(500).samplesPerBucket)
        assertEquals(256, peaks.levelFor(10_000).samplesPerBucket)
    }

    @Test
    fun serializesAndRoundTrips() {
        val peaks = PeakPyramid.build(PcmBuffer(44_100, 2, samples()), baseBucket = 64)
        val bytes = ByteArrayOutputStream()
        peaks.write(bytes)

        val restored = PeakPyramid.read(ByteArrayInputStream(bytes.toByteArray()))
        assertEquals(44_100, restored.sampleRate)
        assertEquals(2, restored.channels)
        assertEquals(peaks.levels.size, restored.levels.size)
        peaks.levels.indices.forEach { level ->
            assertEquals(peaks.levels[level].samplesPerBucket, restored.levels[level].samplesPerBucket)
            assertTrue(peaks.levels[level].min.contentEquals(restored.levels[level].min))
            assertTrue(peaks.levels[level].max.contentEquals(restored.levels[level].max))
        }
    }

    @Test
    fun handlesSilentAndEmptyBuffers() {
        val silent = PeakPyramid.build(PcmBuffer(1_000, 1, FloatArray(100)), baseBucket = 256)
        assertEquals(0f, silent.levels[0].min[0], 0f)
        assertEquals(0f, silent.levels[0].max[0], 0f)

        val empty = PeakPyramid.build(PcmBuffer(1_000, 1, FloatArray(0)))
        assertEquals(0, empty.frameCount)
        assertEquals(0, empty.levels[0].min.size)
    }
}
