package dev.audioeditor.core.pcm

import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Files

class WavFileWriterTest {

    @Test
    fun patchesHeaderSizesAfterStreaming() {
        val file = Files.createTempFile("wav-writer", ".wav").toFile()
        try {
            WavFileWriter(file, 44_100, 1).use { writer ->
                writer.write(FloatArray(100) { 0.25f })
                writer.write(FloatArray(50) { -0.5f })
            }

            val decoded = WavCodec.decode(file)
            assertEquals(44_100, decoded.sampleRate)
            assertEquals(150, decoded.frameCount)
            assertEquals(0.25f, decoded[0, 0], 1e-4f)
            assertEquals(-0.5f, decoded[149, 0], 1e-4f)
        } finally {
            file.delete()
        }
    }
}
