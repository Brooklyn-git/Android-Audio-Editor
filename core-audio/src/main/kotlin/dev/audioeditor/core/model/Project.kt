package dev.audioeditor.core.model

import kotlinx.serialization.Serializable
import java.util.UUID

/** A decoded audio file owned by a project. Stored as 16-bit PCM WAV on disk. */
@Serializable
data class AudioSourceRef(
    val id: String,
    val name: String,
    val wavPath: String,
    val durationMs: Long,
    val sampleRate: Int,
    val channels: Int,
    val peaksPath: String? = null,
)

/** A view onto a range of a source placed at a position on the timeline. */
@Serializable
data class Clip(
    val id: String,
    val sourceId: String,
    val sourceInMs: Long,
    val sourceOutMs: Long,
    val timelineStartMs: Long,
    val gain: Float = 1f,
    val fadeInMs: Long = 0L,
    val fadeOutMs: Long = 0L,
) {
    val durationMs: Long get() = (sourceOutMs - sourceInMs).coerceAtLeast(0L)

    fun endsAtMs(): Long = timelineStartMs + durationMs

    fun containsMs(timeMs: Long): Boolean =
        timeMs >= timelineStartMs && timeMs < endsAtMs()
}

@Serializable
data class Track(
    val id: String,
    val name: String,
    val clips: List<Clip> = emptyList(),
    val gain: Float = 1f,
    val muted: Boolean = false,
    val solo: Boolean = false,
)

@Serializable
data class Project(
    val id: String = newId(),
    val name: String = "Untitled",
    val sampleRate: Int = 44_100,
    val channels: Int = 1,
    val sources: List<AudioSourceRef> = emptyList(),
    val tracks: List<Track> = emptyList(),
)

fun newId(): String = UUID.randomUUID().toString()
