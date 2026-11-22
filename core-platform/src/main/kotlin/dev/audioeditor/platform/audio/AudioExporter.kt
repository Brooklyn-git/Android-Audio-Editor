package dev.audioeditor.platform.audio

import android.content.Context
import android.net.Uri
import dev.audioeditor.core.model.Project
import dev.audioeditor.core.pcm.FilePcmSource
import dev.audioeditor.core.pcm.Mixer
import dev.audioeditor.core.pcm.PcmSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Renders a project to a WAV document chosen through SAF. */
object AudioExporter {

    suspend fun export(
        context: Context,
        project: Project,
        uri: Uri,
        progress: (fraction: Float) -> Boolean = { true },
    ): Int = withContext(Dispatchers.IO) {
        val sources: Map<String, PcmSource> = project.sources.associate { source ->
            source.id to FilePcmSource(java.io.File(source.wavPath))
        }
        try {
            val output = context.contentResolver.openOutputStream(uri)
                ?: error("Could not open the export destination")
            output.use { Mixer.mixToWav(project, sources, it, progress) }
        } finally {
            sources.values.forEach { it.close() }
        }
    }
}
