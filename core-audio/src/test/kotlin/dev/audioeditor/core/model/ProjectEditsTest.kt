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
    fun moveChangesOnlyTimelinePosition() {
        val moved = ProjectEdits.move(project, "t1", "c1", 7_500L)
        assertEquals(7_500L, moved.tracks[0].clips[0].timelineStartMs)
        assertEquals(clip.sourceInMs, moved.tracks[0].clips[0].sourceInMs)
        assertEquals(clip.sourceOutMs, moved.tracks[0].clips[0].sourceOutMs)

        assertEquals(0L, ProjectEdits.move(project, "t1", "c1", -100L).tracks[0].clips[0].timelineStartMs)
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
}
