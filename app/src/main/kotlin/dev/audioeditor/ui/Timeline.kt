package dev.audioeditor.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.audioeditor.core.model.Clip
import dev.audioeditor.core.model.Project
import dev.audioeditor.core.model.ProjectOps
import dev.audioeditor.core.model.Track
import dev.audioeditor.core.pcm.PeakLevel
import dev.audioeditor.core.pcm.PeakPyramid
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

private data class ClipHit(val track: Track, val clip: Clip, val edge: Edge)
private enum class Edge { BODY, LEFT, RIGHT }

@Composable
fun Timeline(
    project: Project,
    peaks: Map<String, PeakPyramid>,
    selectedClipId: String?,
    playheadMs: Long,
    trackHeight: Dp = 96.dp,
    onSelect: (String?) -> Unit,
    onSeek: (Long) -> Unit,
    onGestureStart: () -> Unit,
    onMove: (trackId: String, clipId: String, startMs: Long) -> Unit,
    onCrop: (trackId: String, clipId: String, sourceInMs: Long, sourceOutMs: Long) -> Unit,
    onGestureEnd: () -> Unit,
) {
    val density = LocalDensity.current
    val durationMs = maxOf(5_000L, ProjectOps.durationMs(project))
    val colors = MaterialTheme.colorScheme
    var dragState by remember { mutableStateOf<ClipHit?>(null) }

    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val pxPerMs = with(density) { maxWidth.toPx() } / durationMs
        val totalHeight = trackHeight * maxOf(1, project.tracks.size)

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(totalHeight)
                .background(colors.surface)
                .pointerInput(project, pxPerMs) {
                    detectTapGestures { offset ->
                        val timeMs = (offset.x / pxPerMs).toLong().coerceIn(0L, durationMs)
                        onSeek(timeMs)
                        onSelect(hitClip(project, offset, pxPerMs, with(density) { trackHeight.toPx() })?.clip?.id)
                    }
                }
                .pointerInput(project, pxPerMs) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            val hit = hitClip(project, offset, pxPerMs, with(density) { trackHeight.toPx() })
                            if (hit != null) {
                                dragState = hit
                                onSelect(hit.clip.id)
                                onGestureStart()
                            }
                        },
                        onDrag = { change, dragAmount ->
                            val hit = dragState ?: return@detectDragGestures
                            change.consume()
                            val deltaMs = (dragAmount.x / pxPerMs).toLong()
                            when (hit.edge) {
                                Edge.BODY -> onMove(
                                    hit.track.id,
                                    hit.clip.id,
                                    hit.clip.timelineStartMs + deltaMs,
                                )
                                Edge.LEFT -> onCrop(
                                    hit.track.id,
                                    hit.clip.id,
                                    minOf(hit.clip.sourceOutMs - 1, hit.clip.sourceInMs + deltaMs)
                                        .coerceAtLeast(0L),
                                    hit.clip.sourceOutMs,
                                )
                                Edge.RIGHT -> onCrop(
                                    hit.track.id,
                                    hit.clip.id,
                                    hit.clip.sourceInMs,
                                    maxOf(hit.clip.sourceInMs + 1, hit.clip.sourceOutMs + deltaMs),
                                )
                            }
                        },
                        onDragEnd = {
                            dragState = null
                            onGestureEnd()
                        },
                        onDragCancel = {
                            dragState = null
                            onGestureEnd()
                        },
                    )
                },
        ) {
            val heightPx = size.height
            val trackPx = with(density) { trackHeight.toPx() }

            // Time ruler.
            val stepMs = when {
                pxPerMs * 1_000 < 40 -> 10_000L
                pxPerMs * 1_000 < 90 -> 5_000L
                pxPerMs * 1_000 < 180 -> 2_000L
                else -> 1_000L
            }
            var tick = 0L
            while (tick <= durationMs) {
                val x = tick * pxPerMs
                drawLine(
                    color = colors.outlineVariant.copy(alpha = 0.5f),
                    start = Offset(x, 0f),
                    end = Offset(x, heightPx),
                    strokeWidth = 1f,
                )
                tick += stepMs
            }

            project.tracks.forEachIndexed { index, track ->
                val top = index * trackPx
                drawLine(
                    color = colors.outlineVariant,
                    start = Offset(0f, top),
                    end = Offset(size.width, top),
                    strokeWidth = 1f,
                )
                track.clips.forEach { clip ->
                    val left = clip.timelineStartMs * pxPerMs
                    val width = clip.durationMs * pxPerMs
                    val selected = clip.id == selectedClipId
                    drawRect(
                        color = if (selected) colors.primaryContainer else colors.surfaceVariant,
                        topLeft = Offset(left, top + 4f),
                        size = Size(width, trackPx - 8f),
                    )
                    drawRect(
                        color = if (selected) colors.primary else colors.outline,
                        topLeft = Offset(left, top + 4f),
                        size = Size(width, trackPx - 8f),
                        style = androidx.compose.ui.graphics.drawscope.Stroke(width = if (selected) 2f else 1f),
                    )

                    val peak = peaks[clip.sourceId]
                    if (peak != null) {
                        drawWaveform(peak, clip, width.toInt(), left, top, trackPx, colors.primary)
                    }
                }
            }

            val playheadX = playheadMs * pxPerMs
            drawLine(
                color = colors.error,
                start = Offset(playheadX, 0f),
                end = Offset(playheadX, heightPx),
                strokeWidth = 2f,
            )
        }
    }
}

private fun hitClip(project: Project, offset: Offset, pxPerMs: Float, trackPx: Float): ClipHit? {
    val trackIndex = (offset.y / trackPx).toInt()
    if (trackIndex !in project.tracks.indices) return null
    val track = project.tracks[trackIndex]
    val edgeWidth = 16f

    track.clips.forEach { clip ->
        val left = clip.timelineStartMs * pxPerMs
        val right = left + clip.durationMs * pxPerMs
        if (offset.x in left..right) {
            val edge = when {
                offset.x - left < edgeWidth -> Edge.LEFT
                right - offset.x < edgeWidth -> Edge.RIGHT
                else -> Edge.BODY
            }
            return ClipHit(track, clip, edge)
        }
    }
    return null
}

private fun DrawScope.drawWaveform(
    peaks: PeakPyramid,
    clip: Clip,
    barCount: Int,
    left: Float,
    trackTop: Float,
    trackPx: Float,
    color: Color,
) {
    val count = barCount.coerceAtLeast(1)
    val sourceFrames = peaks.frameCount
    if (sourceFrames <= 0) return
    val fromFrame = (clip.sourceInMs * peaks.sampleRate / 1_000L)
        .coerceIn(0L, sourceFrames.toLong()).toInt()
    val toFrame = (clip.sourceOutMs * peaks.sampleRate / 1_000L)
        .coerceIn(fromFrame.toLong(), sourceFrames.toLong()).toInt()
    if (toFrame <= fromFrame) return

    val level = peaks.levelFor(max(1, (toFrame - fromFrame) / count))
    val center = trackTop + trackPx / 2f
    val amplitude = (trackPx - 12f) / 2f

    for (x in 0 until count) {
        val frameStart = fromFrame + ((toFrame - fromFrame).toLong() * x / count).toInt()
        val frameEnd = fromFrame + ((toFrame - fromFrame).toLong() * (x + 1) / count).toInt()
        val bucketStart = frameStart / level.samplesPerBucket
        val bucketEnd = minOf(level.min.lastIndex, max(bucketStart, (frameEnd - 1) / level.samplesPerBucket))
        val (minValue, maxValue) = extremes(level, bucketStart, bucketEnd)
        if (abs(maxValue) < 1e-5f && abs(minValue) < 1e-5f) continue
        drawLine(
            color = color,
            start = Offset(left + x, center - maxValue * amplitude),
            end = Offset(left + x, center - minValue * amplitude),
            strokeWidth = 1f,
        )
    }
}

private fun extremes(level: PeakLevel, from: Int, to: Int): Pair<Float, Float> {
    if (from > to || level.min.isEmpty()) return 0f to 0f
    var minimum = 1f
    var maximum = -1f
    for (i in from..to) {
        minimum = min(minimum, level.min[i])
        maximum = max(maximum, level.max[i])
    }
    return minimum to maximum
}
