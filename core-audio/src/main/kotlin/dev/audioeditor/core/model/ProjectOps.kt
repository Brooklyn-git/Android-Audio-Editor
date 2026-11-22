package dev.audioeditor.core.model

/** Pure helpers for querying and immutably editing a [Project]. */
object ProjectOps {

    fun msToFrames(ms: Long, sampleRate: Int): Long =
        (ms * sampleRate) / 1_000L

    fun framesToMs(frames: Long, sampleRate: Int): Long =
        (frames * 1_000L) / sampleRate

    fun durationMs(project: Project): Long =
        project.tracks.maxOfOrNull { track ->
            track.clips.maxOfOrNull { it.endsAtMs() } ?: 0L
        } ?: 0L

    fun sourceById(project: Project, sourceId: String): AudioSourceRef? =
        project.sources.firstOrNull { it.id == sourceId }

    fun trackById(project: Project, trackId: String): Track? =
        project.tracks.firstOrNull { it.id == trackId }

    fun withTracks(project: Project, tracks: List<Track>): Project =
        project.copy(tracks = tracks)

    fun replaceTrack(project: Project, track: Track): Project =
        project.copy(tracks = project.tracks.map { if (it.id == track.id) track else it })

    fun replaceClip(project: Project, trackId: String, clip: Clip): Project =
        replaceTrack(project, trackById(project, trackId)?.let { track ->
            track.copy(clips = track.clips.map { if (it.id == clip.id) clip else it })
        } ?: return project)

    fun removeClip(project: Project, trackId: String, clipId: String): Project =
        replaceTrack(project, trackById(project, trackId)?.let { track ->
            track.copy(clips = track.clips.filterNot { it.id == clipId })
        } ?: return project)

    fun addSource(project: Project, source: AudioSourceRef): Project =
        project.copy(sources = project.sources.filterNot { it.id == source.id } + source)

    /** True when at least one track is soloed; soloed tracks then take precedence. */
    fun hasSolo(project: Project): Boolean = project.tracks.any { it.solo }

    fun isTrackAudible(project: Project, track: Track): Boolean =
        !track.muted && (!hasSolo(project) || track.solo)
}
