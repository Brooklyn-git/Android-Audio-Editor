package dev.audioeditor.core.pcm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import kotlin.math.sin

class WavCodecTest {

    @Test
    fun encodesAndDecodesMonoPcm16() {
        val file = Files.createTempFile("audio-editor", ".wav").toFile()
        try {
            val samples = FloatArray(1_000) { sin(it / 20.0).toFloat() * 0.5f }
            WavCodec.encode(file, 44_100, 1, samples)

            val decoded = WavCodec.decode(file)
            assertEquals(44_100, decoded.sampleRate)
            assertEquals(1, decoded.channels)
            assertEquals(samples.size, decoded.frameCount)
            samples.indices.forEach { i ->
                assertEquals(samples[i], decoded.samples[i], 1e-4f)
            }
        } finally {
            file.delete()
        }
    }

    @Test
    fun preservesStereoInterleaving() {
        val file = Files.createTempFile("audio-editor-stereo", ".wav").toFile()
        try {
            val samples = floatArrayOf(0.1f, -0.2f, 0.3f, -0.4f, 0.5f, -0.6f)
            WavCodec.encode(file, 48_000, 2, samples)

            val decoded = WavCodec.decode(file)
            assertEquals(48_000, decoded.sampleRate)
            assertEquals(2, decoded.channels)
            assertEquals(3, decoded.frameCount)
            assertEquals(0.3f, decoded[1, 0], 1e-4f)
            assertEquals(-0.4f, decoded[1, 1], 1e-4f)
        } finally {
            file.delete()
        }
    }

    @Test
    fun rejectsCompressedFormats() {
        val file = Files.createTempFile("audio-editor-bad", ".wav").toFile()
        try {
            // Build a minimal WAV header advertising a non-PCM format.
            val bad = byteArrayOf(
                'R'.code.toByte(), 'I'.code.toByte(), 'F'.code.toByte(), 'F'.code.toByte(),
                36, 0, 0, 0,
                'W'.code.toByte(), 'A'.code.toByte(), 'V'.code.toByte(), 'E'.code.toByte(),
                'f'.code.toByte(), 'm'.code.toByte(), 't'.code.toByte(), ' '.code.toByte(),
                16, 0, 0, 0,
                3, 0, 1, 0, 0x44.toByte(), 0xAC.toByte(), 0, 0,
                0, 0, 0, 0, 2, 0, 16, 0,
            )
            Files.write(file.toPath(), bad)
            try {
                WavCodec.decode(file)
                org.junit.Assert.fail("Expected non-PCM WAV to be rejected")
            } catch (expected: IllegalArgumentException) {
                assertTrue(expected.message!!.contains("PCM"))
            }
        } finally {
            file.delete()
        }
    }
}
