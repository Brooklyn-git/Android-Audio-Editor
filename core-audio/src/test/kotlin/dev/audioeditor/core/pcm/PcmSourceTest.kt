package dev.audioeditor.core.pcm

import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Files

class PcmSourceTest {

    @Test
    fun arraySourceReadsInterleavedRange() {
        val source = ArrayPcmSource(PcmBuffer(44_100, 2, floatArrayOf(1f, 2f, 3f, 4f, 5f, 6f)))
        val out = FloatArray(8)

        assertEquals(2, source.readFrames(1, 2, out, 1))
        assertEquals(0f, out[0], 0f)
        assertEquals(3f, out[1], 0f)
        assertEquals(4f, out[2], 0f)
        assertEquals(5f, out[3], 0f)
        assertEquals(6f, out[4], 0f)
    }

    @Test
    fun fileSourceReadsRangeAndStopsAtEnd() {
        val file = Files.createTempFile("audio-editor-source", ".wav").toFile()
        try {
            WavCodec.encode(file, 44_100, 1, FloatArray(100) { it / 100f })
            FilePcmSource(file).use { source ->
                val out = FloatArray(16)
                assertEquals(100, source.frameCount)

                assertEquals(4, source.readFrames(10, 4, out))
                assertEquals(0.10f, out[0], 1e-4f)
                assertEquals(0.13f, out[3], 1e-4f)

                assertEquals(2, source.readFrames(98, 10, out))
                assertEquals(0.98f, out[0], 1e-4f)
                assertEquals(0.99f, out[1], 1e-4f)
            }
        } finally {
            file.delete()
        }
    }
}
