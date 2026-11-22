package dev.audioeditor.core.model

/** Non-destructive timeline edits. All operations return a new project. */
object ProjectEdits {

    /** Changes the visible range of a clip without touching its source WAV. */
    fun crop(
        project: Project,
        trackId: String,
        clipId: String,
        sourceInMs: Long,
        sourceOutMs: Long,
    ): Project {
        val clip = requireClip(project, trackId, clipId)
        val duration = ProjectOps.sourceById(project, clip.sourceId)?.durationMs
            ?: error("Source ${clip.sourceId} is missing")
        val from = sourceInMs.coerceIn(0L, duration)
        val to = sourceOutMs.coerceIn(from + 1, duration)
        return ProjectOps.replaceClip(project, trackId, clip.copy(sourceInMs = from, sourceOutMs = to))
    }

    /** Removes a clip from its track without touching the underlying source file. */
    fun deleteClip(project: Project, trackId: String, clipId: String): Project {
        requireClip(project, trackId, clipId)
        return ProjectOps.removeClip(project, trackId, clipId)
    }

    /** Moves a clip along the timeline. Negative starts are clamped to zero. */
    fun move(project: Project, trackId: String, clipId: String, timelineStartMs: Long): Project {
        val clip = requireClip(project, trackId, clipId)
        return ProjectOps.replaceClip(
            project,
            trackId,
            clip.copy(timelineStartMs = timelineStartMs.coerceAtLeast(0L)),
        )
    }

    /** Splits a clip at a timeline position into two adjacent clips. */
    fun split(project: Project, trackId: String, clipId: String, atTimelineMs: Long): Project {
        val clip = requireClip(project, trackId, clipId)
        val localMs = atTimelineMs - clip.timelineStartMs
        if (localMs <= 0 || localMs >= clip.durationMs) return project

        val left = clip.copy(sourceOutMs = clip.sourceInMs + localMs)
        val right = clip.copy(
            id = newId(),
            sourceInMs = left.sourceOutMs,
            sourceOutMs = clip.sourceOutMs,
            timelineStartMs = atTimelineMs,
            fadeOutMs = 0L,
        )
        val track = ProjectOps.trackById(project, trackId) ?: return project
        val index = track.clips.indexOfFirst { it.id == clipId }
        val clips = track.clips.toMutableList()
        clips[index] = left.copy(fadeOutMs = 0L)
        clips.add(index + 1, right)
        return ProjectOps.replaceTrack(project, track.copy(clips = clips))
    }

    private fun requireClip(project: Project, trackId: String, clipId: String): Clip =
        ProjectOps.trackById(project, trackId)?.clips?.firstOrNull { it.id == clipId }
            ?: error("Clip $clipId not found on track $trackId")
}
