package dev.audioeditor.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FilterAlt
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import dev.audioeditor.R
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
private data class ClipDrag(val hit: ClipHit, val startOffset: Offset)
private data class ClipMenuRequest(
    val trackId: String,
    val clipId: String,
    val atMs: Long,
    val position: Offset,
)

private enum class Edge { BODY, LEFT, RIGHT }

private const val LONG_PRESS_TIMEOUT_MS = 900L

@Composable
fun Timeline(
    project: Project,
    peaks: Map<String, PeakPyramid>,
    selectedClipId: String?,
    playheadMs: Long,
    isRecording: Boolean = false,
    recordingWaveform: List<Float> = emptyList(),
    recordingElapsedMs: Long = 0L,
    trackHeight: Dp = 96.dp,
    onSelect: (String?) -> Unit,
    onSeek: (Long) -> Unit,
    onDeleteClip: (trackId: String, clipId: String) -> Unit,
    onSplitClip: (trackId: String, clipId: String, atMs: Long) -> Unit,
    onReduceNoiseForTrack: (trackId: String) -> Unit,
    onGestureStart: () -> Unit,
    onMove: (trackId: String, clipId: String, startMs: Long) -> Unit,
    onCrop: (trackId: String, clipId: String, sourceInMs: Long, sourceOutMs: Long) -> Unit,
    onGestureEnd: () -> Unit,
) {
    val density = LocalDensity.current
    val colors = MaterialTheme.colorScheme
    var dragState by remember { mutableStateOf<ClipDrag?>(null) }
    var menuRequest by remember { mutableStateOf<ClipMenuRequest?>(null) }
    val currentProject = remember { mutableStateOf(project) }
    currentProject.value = project

    val projectDurationMs = ProjectOps.durationMs(project)
    val durationMs = maxOf(
        5_000L,
        projectDurationMs,
        if (isRecording) recordingElapsedMs + 2_000L else 0L,
    )
    val viewConfiguration = LocalViewConfiguration.current

    CompositionLocalProvider(
        LocalViewConfiguration provides SlowLongPressViewConfiguration(viewConfiguration),
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val pxPerMs = with(density) { maxWidth.toPx() } / durationMs
            val trackPx = with(density) { trackHeight.toPx() }
            val liveOffset = if (isRecording) trackPx else 0f
            val trackCount = maxOf(1, project.tracks.size)
            val totalHeight = trackHeight * (trackCount + if (isRecording) 1 else 0)

            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(totalHeight)
                    .background(colors.surface)
                    .pointerInput(pxPerMs, isRecording, liveOffset) {
                        detectTapGestures(
                            onTap = { offset ->
                                menuRequest = null
                                val timeMs = (offset.x / pxPerMs).toLong().coerceIn(0L, durationMs)
                                onSeek(timeMs)
                                val hit = hitClip(currentProject.value, offset, pxPerMs, trackPx, liveOffset)
                                onSelect(hit?.clip?.id)
                            },
                            onLongPress = { offset ->
                                val hit = hitClip(currentProject.value, offset, pxPerMs, trackPx, liveOffset)
                                if (hit != null) {
                                    val atMs = (offset.x / pxPerMs).toLong().coerceIn(
                                        hit.clip.timelineStartMs,
                                        hit.clip.endsAtMs(),
                                    )
                                    onSelect(hit.clip.id)
                                    menuRequest = ClipMenuRequest(
                                        trackId = hit.track.id,
                                        clipId = hit.clip.id,
                                        atMs = atMs,
                                        position = offset,
                                    )
                                }
                            },
                        )
                    }
                    .pointerInput(pxPerMs, isRecording, liveOffset) {
                        detectDragGestures(
                            onDragStart = { offset ->
                                menuRequest = null
                                val hit = hitClip(currentProject.value, offset, pxPerMs, trackPx, liveOffset)
                                if (hit != null) {
                                    dragState = ClipDrag(hit, offset)
                                    onSelect(hit.clip.id)
                                    onGestureStart()
                                }
                            },
                            onDrag = { change, _ ->
                                val drag = dragState ?: return@detectDragGestures
                                change.consume()
                                val hit = drag.hit
                                val deltaMs = ((change.position.x - drag.startOffset.x) / pxPerMs).toLong()
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

                if (isRecording) {
                    drawRect(
                        color = colors.errorContainer.copy(alpha = 0.35f),
                        topLeft = Offset(0f, 0f),
                        size = Size(size.width, trackPx),
                    )
                    drawLiveWaveform(recordingWaveform, size.width, trackPx, colors.error)
                    val recordX = (recordingElapsedMs * pxPerMs).coerceIn(0f, size.width)
                    drawLine(
                        color = colors.error,
                        start = Offset(recordX, 0f),
                        end = Offset(recordX, heightPx),
                        strokeWidth = 2f,
                    )
                }

                project.tracks.forEachIndexed { index, track ->
                    val top = liveOffset + index * trackPx
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
                            style = Stroke(width = if (selected) 2f else 1f),
                        )
                        if (selected) {
                            val handleWidth = with(density) { 4.dp.toPx() }
                            val handleTop = top + 12f
                            val handleBottom = top + trackPx - 12f
                            drawLine(
                                color = colors.primary,
                                start = Offset(left + handleWidth / 2f, handleTop),
                                end = Offset(left + handleWidth / 2f, handleBottom),
                                strokeWidth = handleWidth,
                            )
                            drawLine(
                                color = colors.primary,
                                start = Offset(left + width - handleWidth / 2f, handleTop),
                                end = Offset(left + width - handleWidth / 2f, handleBottom),
                                strokeWidth = handleWidth,
                            )
                        }

                        val peak = peaks[clip.sourceId]
                        if (peak != null) {
                            drawWaveform(peak, clip, width.toInt(), left, top, trackPx, colors.primary)
                        }
                    }
                }

                val playheadX = playheadMs * pxPerMs
                drawLine(
                    color = colors.error,
                    start = Offset(playheadX, liveOffset),
                    end = Offset(playheadX, heightPx),
                    strokeWidth = if (isRecording) 1f else 2f,
                )
            }

            menuRequest?.let { request ->
                val menuWidth = 220.dp
                val menuHeight = 168.dp
                val x = with(density) { request.position.x.toDp() }
                    .coerceAtMost(maxWidth - menuWidth - 8.dp)
                    .coerceAtLeast(8.dp)
                val y = with(density) { request.position.y.toDp() }
                    .coerceAtMost(maxHeight - menuHeight - 8.dp)
                    .coerceAtLeast(8.dp)

                DropdownMenu(
                    expanded = true,
                    onDismissRequest = { menuRequest = null },
                    offset = DpOffset(x, y),
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, colors.outlineVariant),
                ) {
                    ClipMenuItem(
                        icon = Icons.Filled.Delete,
                        label = stringResource(R.string.menu_delete),
                        onClick = {
                            onDeleteClip(request.trackId, request.clipId)
                            menuRequest = null
                        },
                    )
                    ClipMenuItem(
                        icon = Icons.Filled.ContentCut,
                        label = stringResource(R.string.menu_split),
                        onClick = {
                            onSplitClip(request.trackId, request.clipId, request.atMs)
                            menuRequest = null
                        },
                    )
                    ClipMenuItem(
                        icon = Icons.Filled.FilterAlt,
                        label = stringResource(R.string.menu_noise_reduction),
                        onClick = {
                            onReduceNoiseForTrack(request.trackId)
                            menuRequest = null
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ClipMenuItem(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    DropdownMenuItem(
        text = { Text(label) },
        leadingIcon = { Icon(icon, contentDescription = null) },
        onClick = onClick,
    )
}

private class SlowLongPressViewConfiguration(
    delegate: ViewConfiguration,
    override val longPressTimeoutMillis: Long = LONG_PRESS_TIMEOUT_MS,
) : ViewConfiguration by delegate

private fun hitClip(
    project: Project,
    offset: Offset,
    pxPerMs: Float,
    trackPx: Float,
    topOffset: Float,
): ClipHit? {
    val trackIndex = ((offset.y - topOffset) / trackPx).toInt()
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

private fun DrawScope.drawLiveWaveform(
    values: List<Float>,
    width: Float,
    height: Float,
    color: Color,
) {
    val center = height / 2f
    val amplitude = (height - 12f) / 2f
    if (values.isEmpty()) {
        drawLine(
            color = color.copy(alpha = 0.4f),
            start = Offset(0f, center),
            end = Offset(width, center),
            strokeWidth = 1f,
        )
        return
    }
    val barWidth = width / values.size
    values.forEachIndexed { index, value ->
        val x = index * barWidth + barWidth / 2f
        val peak = value.coerceIn(0f, 1f) * amplitude
        drawLine(
            color = color,
            start = Offset(x, center - peak),
            end = Offset(x, center + peak),
            strokeWidth = max(1f, barWidth * 0.7f),
        )
    }
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
