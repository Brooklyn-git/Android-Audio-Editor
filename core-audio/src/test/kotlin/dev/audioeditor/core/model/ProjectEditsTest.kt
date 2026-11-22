package dev.audioeditor.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectEditsTest {

    private val source = AudioSourceRef("s1", "a.wav", "a.wav", 10_000L, 44_100, 1)
    private val clip = Clip("c1", "s1", 1_000L, 5_000L, 2_000L)
    private val project = Project(
        sources = listOf(source),
        tracks = listOf(Track("t1", "Track 1", listOf(clip))),
    )

    @Test
    fun cropClampsToSourceAndKeepsNonEmptyRange() {
        val cropped = ProjectEdits.crop(project, "t1", "c1", -500L, 20_000L)
        val result = cropped.tracks[0].clips[0]
        assertEquals(0L, result.sourceInMs)
        assertEquals(10_000L, result.sourceOutMs)
        assertEquals(2_000L, result.timelineStartMs)

        val inverted = ProjectEdits.crop(project, "t1", "c1", 8_000L, 2_000L)
        assertEquals(8_000L, inverted.tracks[0].clips[0].sourceInMs)
        assertEquals(8_001L, inverted.tracks[0].clips[0].sourceOutMs)
    }

    @Test
    fun trimLeftKeepsRightEdgeFixedAndCanRestoreSource() {
        val trimmed = ProjectEdits.trimLeft(project, "t1", "c1", 3_000L)
        val trimmedClip = trimmed.tracks[0].clips[0]
        assertEquals(3_000L, trimmedClip.sourceInMs)
        assertEquals(4_000L, trimmedClip.timelineStartMs)
        assertEquals(clip.endsAtMs(), trimmedClip.endsAtMs())

        val restored = ProjectEdits.trimLeft(project, "t1", "c1", 0L)
        val restoredClip = restored.tracks[0].clips[0]
        assertEquals(0L, restoredClip.sourceInMs)
        assertEquals(1_000L, restoredClip.timelineStartMs)
        assertEquals(clip.endsAtMs(), restoredClip.endsAtMs())

        val clamped = ProjectEdits.trimLeft(project, "t1", "c1", 20_000L)
        assertEquals(clip.sourceOutMs - 1L, clamped.tracks[0].clips[0].sourceInMs)
    }

    @Test
    fun trimLeftCannotMoveClipBeforeTimelineZero() {
        val nearStart = clip.copy(sourceInMs = 1_000L, timelineStartMs = 500L)
        val withNearStart = project.copy(
            tracks = listOf(project.tracks[0].copy(clips = listOf(nearStart))),
        )

        val trimmed = ProjectEdits.trimLeft(withNearStart, "t1", "c1", 0L)
        val result = trimmed.tracks[0].clips[0]

        assertEquals(500L, result.sourceInMs)
        assertEquals(0L, result.timelineStartMs)
        assertEquals(nearStart.endsAtMs(), result.endsAtMs())
    }

    @Test
    fun trimRightCanExtendUpToSourceDuration() {
        val extended = ProjectEdits.trimRight(project, "t1", "c1", 8_000L)
        val result = extended.tracks[0].clips[0]
        assertEquals(1_000L, result.sourceInMs)
        assertEquals(8_000L, result.sourceOutMs)
        assertEquals(clip.timelineStartMs, result.timelineStartMs)

        val clamped = ProjectEdits.trimRight(project, "t1", "c1", 20_000L)
        assertEquals(source.durationMs, clamped.tracks[0].clips[0].sourceOutMs)
        val collapsed = ProjectEdits.trimRight(project, "t1", "c1", 0L)
        assertEquals(clip.sourceInMs + 1L, collapsed.tracks[0].clips[0].sourceOutMs)
    }

    @Test
    fun moveChangesOnlyTimelinePosition() {
        val moved = ProjectEdits.move(project, "t1", "c1", 7_500L)
        assertEquals(7_500L, moved.tracks[0].clips[0].timelineStartMs)
        assertEquals(clip.sourceInMs, moved.tracks[0].clips[0].sourceInMs)
        assertEquals(clip.sourceOutMs, moved.tracks[0].clips[0].sourceOutMs)

        assertEquals(0L, ProjectEdits.move(project, "t1", "c1", -100L).tracks[0].clips[0].timelineStartMs)
    }

    @Test
    fun moveToTrackReassignsClipAndSortsTarget() {
        val otherClip = clip.copy(id = "c2", timelineStartMs = 8_000L)
        val target = Track("t2", "Track 2", listOf(otherClip))
        val withTwoTracks = project.copy(tracks = listOf(project.tracks[0], target))

        val moved = ProjectEdits.moveToTrack(withTwoTracks, "t1", "c1", "t2", 9_000L)

        assertEquals(0, moved.tracks[0].clips.size)
        assertEquals(listOf("c1", "c2"), moved.tracks[1].clips.map { it.id })
        assertEquals(9_000L, moved.tracks[1].clips[0].timelineStartMs)
        assertEquals(13_000L, moved.tracks[1].clips[1].timelineStartMs)
        assertEquals(1, withTwoTracks.tracks[0].clips.size)
    }

    @Test
    fun moveToTrackStillWorksAfterClipMigrated() {
        val t2 = Track("t2", "Track 2")
        val t3 = Track("t3", "Track 3")
        val threeTracks = project.copy(tracks = listOf(project.tracks[0], t2, t3))

        val first = ProjectEdits.moveToTrack(threeTracks, "t1", "c1", "t2", 6_000L)
        val second = ProjectEdits.moveToTrack(first, "t1", "c1", "t3", 8_000L)

        assertEquals(0, second.tracks[0].clips.size)
        assertEquals(0, second.tracks[1].clips.size)
        assertEquals(listOf("c1"), second.tracks[2].clips.map { it.id })
        assertEquals(8_000L, second.tracks[2].clips[0].timelineStartMs)
    }

    @Test
    fun movePushesOverlappingClipsToTheRight() {
        val (project, _) = adjacentClips()

        val moved = ProjectEdits.moveToTrack(project, "t1", "a", "t1", 2_000L)
        val clips = moved.tracks[0].clips.sortedBy { it.timelineStartMs }

        assertEquals(listOf("a", "b"), clips.map { it.id })
        assertEquals(2_000L, clips[0].timelineStartMs)
        assertEquals(6_000L, clips[1].timelineStartMs)
    }

    @Test
    fun moveLeftPushesEarlierClipToTheRight() {
        val (project, _) = adjacentClips()

        val moved = ProjectEdits.moveToTrack(project, "t1", "b", "t1", 1_000L)
        val clips = moved.tracks[0].clips.sortedBy { it.timelineStartMs }

        assertEquals(listOf("b", "a"), clips.map { it.id })
        assertEquals(1_000L, clips[0].timelineStartMs)
        assertEquals(5_000L, clips[1].timelineStartMs)
    }

    @Test
    fun movePushesAllFollowingClipsWhenTheyCascade() {
        val longSource = source.copy(durationMs = 20_000L)
        val clips = listOf(
            Clip("a", "s1", 0L, 4_000L, 0L),
            Clip("b", "s1", 4_000L, 8_000L, 4_000L),
            Clip("c", "s1", 8_000L, 12_000L, 8_000L),
        )
        val threeClips = Project(
            sources = listOf(longSource),
            tracks = listOf(Track("t1", "Track 1", clips)),
        )

        val moved = ProjectEdits.moveToTrack(threeClips, "t1", "a", "t1", 3_000L)
        val result = moved.tracks[0].clips.sortedBy { it.timelineStartMs }

        assertEquals(listOf(3_000L, 7_000L, 11_000L), result.map { it.timelineStartMs })
    }

    @Test
    fun movingBackToOriginalPositionRestoresTheTrack() {
        val (project, _) = adjacentClips()

        val away = ProjectEdits.moveToTrack(project, "t1", "a", "t1", 2_000L)
        val back = ProjectEdits.moveToTrack(project, "t1", "a", "t1", 0L)

        assertTrue(away != project)
        assertEquals(project, back)
    }

    @Test
    fun moveToAnotherTrackPushesItsOverlappingClip() {
        val (project, clips) = adjacentClips()
        val twoTracks = project.copy(
            tracks = listOf(
                Track("t1", "Track 1", listOf(clips[0])),
                Track("t2", "Track 2", listOf(clips[1])),
            ),
        )

        val moved = ProjectEdits.moveToTrack(twoTracks, "t1", "a", "t2", 2_000L)

        assertEquals(0, moved.tracks[0].clips.size)
        assertEquals(listOf("a", "b"), moved.tracks[1].clips.map { it.id })
        assertEquals(listOf(2_000L, 6_000L), moved.tracks[1].clips.map { it.timelineStartMs })
    }

    @Test
    fun trimRightPushesTheFollowingClip() {
        val (project, _) = adjacentClips()

        val trimmed = ProjectEdits.trimRight(project, "t1", "a", 6_000L)
        val clips = trimmed.tracks[0].clips.sortedBy { it.timelineStartMs }

        assertEquals(listOf(0L, 6_000L), clips.map { it.timelineStartMs })
        assertEquals(6_000L, clips[0].sourceOutMs)
    }

    @Test
    fun splitCreatesTwoAdjacentClips() {
        val split = ProjectEdits.split(project, "t1", "c1", 3_500L)
        val clips = split.tracks[0].clips
        assertEquals(2, clips.size)
        assertEquals(1_000L, clips[0].sourceInMs)
        assertEquals(2_500L, clips[0].sourceOutMs)
        assertEquals(2_000L, clips[0].timelineStartMs)
        assertEquals(2_500L, clips[1].sourceInMs)
        assertEquals(5_000L, clips[1].sourceOutMs)
        assertEquals(3_500L, clips[1].timelineStartMs)
        assertTrue(clips[0].id != clips[1].id)
    }

    @Test
    fun splitOutsideClipIsIgnored() {
        assertEquals(project, ProjectEdits.split(project, "t1", "c1", 1_000L))
        assertEquals(project, ProjectEdits.split(project, "t1", "c1", 8_000L))
    }

    @Test
    fun deleteClipRemovesOnlyTheRequestedClip() {
        val other = clip.copy(id = "c2", timelineStartMs = 6_000L)
        val withTwo = project.copy(tracks = listOf(project.tracks[0].copy(clips = listOf(clip, other))))

        val deleted = ProjectEdits.deleteClip(withTwo, "t1", "c1")

        assertEquals(listOf("c2"), deleted.tracks[0].clips.map { it.id })
        assertEquals(2, withTwo.tracks[0].clips.size)
    }

    @Test(expected = IllegalStateException::class)
    fun deleteClipRejectsUnknownClip() {
        ProjectEdits.deleteClip(project, "t1", "missing")
    }

    private fun adjacentClips(): Pair<Project, List<Clip>> {
        val first = Clip("a", "s1", 0L, 4_000L, 0L)
        val second = Clip("b", "s1", 4_000L, 8_000L, 4_000L)
        val project = Project(
            sources = listOf(source),
            tracks = listOf(Track("t1", "Track 1", listOf(first, second))),
        )
        return project to listOf(first, second)
    }
}
