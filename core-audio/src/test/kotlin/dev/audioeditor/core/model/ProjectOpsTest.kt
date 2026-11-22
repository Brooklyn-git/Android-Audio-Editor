package dev.audioeditor.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectOpsTest {

    private val source = AudioSourceRef("s1", "clip.wav", "sources/clip.wav", 10_000L, 44_100, 1)
    private val clip = Clip("c1", "s1", 1_000L, 4_000L, 2_000L)
    private val project = Project(
        id = "p1",
        sources = listOf(source),
        tracks = listOf(Track("t1", "Track 1", listOf(clip))),
    )

    @Test
    fun clipBoundsAndContainment() {
        assertEquals(3_000L, clip.durationMs)
        assertEquals(5_000L, clip.endsAtMs())
        assertFalse(clip.containsMs(1_999L))
        assertTrue(clip.containsMs(2_000L))
        assertTrue(clip.containsMs(4_999L))
        assertFalse(clip.containsMs(5_000L))
    }

    @Test
    fun projectDurationUsesLatestClipEnd() {
        assertEquals(5_000L, ProjectOps.durationMs(project))
        val longer = clip.copy(timelineStartMs = 20_000L)
        assertEquals(
            23_000L,
            ProjectOps.durationMs(project.copy(tracks = listOf(Track("t1", "T", listOf(clip, longer))))),
        )
    }

    @Test
    fun immutableEditsLeaveOriginalProjectUntouched() {
        val trimmed = ProjectOps.replaceClip(project, "t1", clip.copy(sourceInMs = 1_500L))
        assertEquals(1_000L, project.tracks[0].clips[0].sourceInMs)
        assertEquals(1_500L, trimmed.tracks[0].clips[0].sourceInMs)

        val removed = ProjectOps.removeClip(project, "t1", "c1")
        assertTrue(removed.tracks[0].clips.isEmpty())
        assertEquals(1, project.tracks[0].clips.size)
    }

    @Test
    fun muteWinsAndSoloIsolatesOtherTracks() {
        val muted = project.copy(tracks = listOf(project.tracks[0].copy(muted = true)))
        assertFalse(ProjectOps.isTrackAudible(project, muted.tracks[0]))

        val other = Track("t2", "Track 2")
        val solo = project.copy(tracks = listOf(project.tracks[0].copy(solo = true), other))
        assertTrue(ProjectOps.isTrackAudible(solo, solo.tracks[0]))
        assertFalse(ProjectOps.isTrackAudible(solo, solo.tracks[1]))

        val mutedSolo = solo.copy(tracks = listOf(solo.tracks[0].copy(muted = true), other))
        assertFalse(ProjectOps.isTrackAudible(mutedSolo, mutedSolo.tracks[0]))
    }

    @Test
    fun millisecondsConvertToFrames() {
        assertEquals(44_100L, ProjectOps.msToFrames(1_000L, 44_100))
        assertEquals(1_000L, ProjectOps.framesToMs(44_100L, 44_100))
    }
}
