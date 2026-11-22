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

    /**
     * Trims the left edge to a source time while keeping the clip's right edge
     * on the timeline fixed. Dragging past the available source is clamped.
     */
    fun trimLeft(project: Project, trackId: String, clipId: String, sourceInMs: Long): Project {
        val clip = requireClip(project, trackId, clipId)
        val maxIn = minOf(sourceDuration(project, clip) - 1L, clip.sourceOutMs - 1L)
        if (maxIn < 0L) return project
        val minIn = maxOf(0L, clip.sourceInMs - clip.timelineStartMs).coerceAtMost(maxIn)
        val newIn = sourceInMs.coerceIn(minIn, maxIn)
        if (newIn == clip.sourceInMs) return project
        val newStart = clip.timelineStartMs + newIn - clip.sourceInMs
        val track = ProjectOps.trackById(project, trackId) ?: return project
        val trimmed = clip.copy(sourceInMs = newIn, timelineStartMs = newStart)
        return ProjectOps.replaceTrack(project, track.copy(clips = placeWithoutOverlap(track.clips, trimmed)))
    }

    /** Trims the right edge to a source time, clamped to the source bounds. */
    fun trimRight(project: Project, trackId: String, clipId: String, sourceOutMs: Long): Project {
        val clip = requireClip(project, trackId, clipId)
        val newOut = sourceOutMs.coerceIn(
            clip.sourceInMs + 1L,
            sourceDuration(project, clip),
        )
        if (newOut == clip.sourceOutMs) return project
        val track = ProjectOps.trackById(project, trackId) ?: return project
        val trimmed = clip.copy(sourceOutMs = newOut)
        return ProjectOps.replaceTrack(project, track.copy(clips = placeWithoutOverlap(track.clips, trimmed)))
    }

    /** Removes a clip from its track without touching the underlying source file. */
    fun deleteClip(project: Project, trackId: String, clipId: String): Project {
        requireClip(project, trackId, clipId)
        return ProjectOps.removeClip(project, trackId, clipId)
    }

    /** Moves a clip along the timeline. Negative starts are clamped to zero. */
    fun move(project: Project, trackId: String, clipId: String, timelineStartMs: Long): Project =
        moveToTrack(project, trackId, clipId, trackId, timelineStartMs)

    /**
     * Moves a clip to a timeline position, possibly on another track.
     * The source track keeps its other clips; the target track receives this clip
     * sorted by timeline position.
     */
    fun moveToTrack(
        project: Project,
        trackId: String,
        clipId: String,
        targetTrackId: String,
        timelineStartMs: Long,
    ): Project {
        if (ProjectOps.trackById(project, targetTrackId) == null) return project
        // During a drag the clip may already have migrated, making the original
        // track id stale. Locating it by id keeps repeated drag events valid.
        val (actualTrackId, clip) = project.tracks.firstNotNullOfOrNull { track ->
            track.clips.firstOrNull { it.id == clipId }?.let { track.id to it }
        } ?: return project
        val moved = clip.copy(timelineStartMs = timelineStartMs.coerceAtLeast(0L))
        val withoutClip = ProjectOps.removeClip(project, actualTrackId, clipId)
        val targetAfterRemoval = ProjectOps.trackById(withoutClip, targetTrackId) ?: return project
        val updatedTarget = targetAfterRemoval.copy(
            clips = placeWithoutOverlap(targetAfterRemoval.clips, moved),
        )
        return ProjectOps.replaceTrack(withoutClip, updatedTarget)
    }

    /** Moves a track to a new vertical position in the project. */
    fun moveTrack(project: Project, trackId: String, targetIndex: Int): Project {
        val fromIndex = project.tracks.indexOfFirst { it.id == trackId }
        if (fromIndex < 0 || targetIndex !in project.tracks.indices || fromIndex == targetIndex) {
            return project
        }
        val tracks = project.tracks.toMutableList()
        val track = tracks.removeAt(fromIndex)
        tracks.add(targetIndex, track)
        return project.copy(tracks = tracks)
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

    /**
     * Puts [moving] at its requested timeline position and shifts stationary
     * clips that overlap it (or are pushed into by them) to the right.
     * Clips before the moving clip keep their positions unless they already
     * overlapped each other.
     */
    private fun placeWithoutOverlap(clips: List<Clip>, moving: Clip): List<Clip> {
        val stationary = clips.filterNot { it.id == moving.id }
            .sortedBy { it.timelineStartMs }
        val result = mutableListOf<Clip>()

        var leftCursor = 0L
        stationary.filter { it.endsAtMs() <= moving.timelineStartMs }
            .forEach { clip ->
                val start = maxOf(clip.timelineStartMs, leftCursor)
                result += clip.copy(timelineStartMs = start)
                leftCursor = start + clip.durationMs
            }

        result += moving

        var rightCursor = moving.endsAtMs()
        stationary.filter { it.endsAtMs() > moving.timelineStartMs }
            .forEach { clip ->
                val start = maxOf(clip.timelineStartMs, rightCursor)
                result += clip.copy(timelineStartMs = start)
                rightCursor = start + clip.durationMs
            }
        return result
    }

    private fun requireClip(project: Project, trackId: String, clipId: String): Clip =
        ProjectOps.trackById(project, trackId)?.clips?.firstOrNull { it.id == clipId }
            ?: error("Clip $clipId not found on track $trackId")

    private fun sourceDuration(project: Project, clip: Clip): Long =
        ProjectOps.sourceById(project, clip.sourceId)?.durationMs
            ?: error("Source ${clip.sourceId} is missing")
}
