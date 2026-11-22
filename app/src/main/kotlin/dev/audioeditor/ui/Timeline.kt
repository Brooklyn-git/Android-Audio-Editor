package dev.audioeditor.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FilterAlt
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalViewConfiguration
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

private data class ClipHit(
    val track: Track,
    val clip: Clip,
    val edge: Edge,
)

private data class ClipMenuRequest(
    val trackId: String,
    val clipId: String,
    val atMs: Long,
    val position: Offset,
)

private enum class Edge { BODY, LEFT, RIGHT }

private enum class GestureMode { PENDING, MOVE, TRIM_LEFT, TRIM_RIGHT, PAN, SCRUB, MENU, REORDER_TRACK }

/** Fixed zoom: the timeline scrolls instead of squeezing audio to fit the screen. */
private const val PX_PER_MS = 0.12f
private const val MENU_HOLD_MS = 500L
private const val AUTO_SCROLL_PX_PER_EVENT = 14f

@Composable
fun Timeline(
    project: Project,
    peaks: Map<String, PeakPyramid>,
    selectedClipId: String?,
    playheadMs: Long,
    modifier: Modifier = Modifier,
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
    onMoveClip: (trackId: String, clipId: String, targetTrackId: String, startMs: Long) -> Unit,
    onTrimLeft: (trackId: String, clipId: String, sourceInMs: Long) -> Unit,
    onTrimRight: (trackId: String, clipId: String, sourceOutMs: Long) -> Unit,
    onReorderTrack: (trackId: String, targetIndex: Int) -> Unit,
    onGestureEnd: () -> Unit,
) {
    val density = LocalDensity.current
    val colors = MaterialTheme.colorScheme
    val haptic = LocalHapticFeedback.current
    val touchSlop = LocalViewConfiguration.current.touchSlop

    var panX by remember { mutableFloatStateOf(0f) }
    var panY by remember { mutableFloatStateOf(0f) }
    var menuRequest by remember { mutableStateOf<ClipMenuRequest?>(null) }
    var reorderTargetIndex by remember { mutableIntStateOf(-1) }

    val currentProject = rememberUpdatedState(project)
    val currentOnSelect = rememberUpdatedState(onSelect)
    val currentOnSeek = rememberUpdatedState(onSeek)
    val currentOnMoveClip = rememberUpdatedState(onMoveClip)
    val currentOnTrimLeft = rememberUpdatedState(onTrimLeft)
    val currentOnTrimRight = rememberUpdatedState(onTrimRight)
    val currentOnReorderTrack = rememberUpdatedState(onReorderTrack)
    val currentOnGestureStart = rememberUpdatedState(onGestureStart)
    val currentOnGestureEnd = rememberUpdatedState(onGestureEnd)

    val projectDurationMs = ProjectOps.durationMs(project)

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .clipToBounds()
            .background(colors.surface),
    ) {
        val viewportWidthPx = with(density) { maxWidth.toPx() }
        val viewportHeightPx = with(density) { maxHeight.toPx() }
        val trackPx = with(density) { trackHeight.toPx() }
        val rulerPx = with(density) { 32.dp.toPx() }
        val edgeTouchPx = with(density) { 24.dp.toPx() }
        val handleWidthPx = with(density) { 6.dp.toPx() }
        val trackHeaderPx = with(density) { 44.dp.toPx() }
        val autoScrollEdgePx = with(density) { 40.dp.toPx() }
        val liveOffsetPx = if (isRecording) trackPx else 0f
        val trackTopOffset = rulerPx + liveOffsetPx

        val contentWidthPx = maxOf(
            viewportWidthPx,
            (projectDurationMs + 4_000L) * PX_PER_MS,
        )
        val contentDurationMs = contentWidthPx / PX_PER_MS
        val contentHeightPx = maxOf(
            viewportHeightPx,
            trackTopOffset + maxOf(1, project.tracks.size) * trackPx,
        )
        val maxPanX = max(0f, contentWidthPx - viewportWidthPx)
        val maxPanY = max(0f, contentHeightPx - viewportHeightPx)

        val currentMaxPanX = rememberUpdatedState(maxPanX)
        val currentMaxPanY = rememberUpdatedState(maxPanY)
        val currentViewportWidth = rememberUpdatedState(viewportWidthPx)
        val currentViewportHeight = rememberUpdatedState(viewportHeightPx)
        val currentContentDurationMs = rememberUpdatedState(contentDurationMs)

        LaunchedEffect(maxPanX, maxPanY) {
            panX = panX.coerceIn(0f, maxPanX)
            panY = panY.coerceIn(0f, maxPanY)
        }

        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    val timerScope = CoroutineScope(coroutineContext + SupervisorJob())
                    try {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            menuRequest = null
                            reorderTargetIndex = -1
                            val start = down.position
                            val startPan = Offset(panX, panY)
                            val startContent = start + startPan

                            fun trackIndexAt(screenY: Float): Int? {
                                val contentY = screenY + panY
                                if (contentY < trackTopOffset) return null
                                val index = ((contentY - trackTopOffset) / trackPx).toInt()
                                return if (index in currentProject.value.tracks.indices) index else null
                            }

                            val startTrackIndex = trackIndexAt(start.y)
                            val onHeader = start.x < trackHeaderPx && startTrackIndex != null
                            val hit = if (onHeader) null else hitClip(
                                project = currentProject.value,
                                contentOffset = startContent,
                                pxPerMs = PX_PER_MS,
                                trackPx = trackPx,
                                trackTopOffset = trackTopOffset,
                                edgeTouchPx = edgeTouchPx,
                            )

                            var mode = when {
                                onHeader -> GestureMode.REORDER_TRACK
                                start.y < rulerPx -> GestureMode.SCRUB
                                hit?.edge == Edge.LEFT -> GestureMode.TRIM_LEFT
                                hit?.edge == Edge.RIGHT -> GestureMode.TRIM_RIGHT
                                hit != null -> GestureMode.PENDING
                                else -> GestureMode.PAN
                            }
                            var activeTrackId = hit?.track?.id
                            val reorderTrackId = if (onHeader) {
                                currentProject.value.tracks[startTrackIndex].id
                            } else null
                            var gestureStarted = false
                            var menuShown = false
                            var moved = false
                            var menuJob: Job? = null

                            fun beginTrackGesture(nextMode: GestureMode) {
                                if (gestureStarted || menuShown) return
                                gestureStarted = true
                                mode = nextMode
                                menuJob?.cancel()
                                currentOnGestureStart.value()
                            }

                            fun beginClipGesture(nextMode: GestureMode) {
                                if (gestureStarted || menuShown || hit == null) return
                                beginTrackGesture(nextMode)
                                currentOnSelect.value(hit.clip.id)
                            }

                            fun showMenu() {
                                if (gestureStarted || menuShown || hit == null) return
                                menuShown = true
                                mode = GestureMode.MENU
                                menuJob?.cancel()
                                currentOnSelect.value(hit.clip.id)
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                val atMs = (startContent.x / PX_PER_MS).toLong().coerceIn(
                                    hit.clip.timelineStartMs,
                                    hit.clip.endsAtMs(),
                                )
                                menuRequest = ClipMenuRequest(
                                    trackId = activeTrackId ?: hit.track.id,
                                    clipId = hit.clip.id,
                                    atMs = atMs,
                                    position = start,
                                )
                            }

                            fun autoScroll(pointer: Offset) {
                                if (pointer.x < autoScrollEdgePx) {
                                    panX = (panX - AUTO_SCROLL_PX_PER_EVENT).coerceAtLeast(0f)
                                } else if (pointer.x > currentViewportWidth.value - autoScrollEdgePx) {
                                    panX = (panX + AUTO_SCROLL_PX_PER_EVENT)
                                        .coerceAtMost(currentMaxPanX.value)
                                }
                                if (pointer.y < rulerPx + autoScrollEdgePx) {
                                    panY = (panY - AUTO_SCROLL_PX_PER_EVENT).coerceAtLeast(0f)
                                } else if (pointer.y > currentViewportHeight.value - autoScrollEdgePx) {
                                    panY = (panY + AUTO_SCROLL_PX_PER_EVENT)
                                        .coerceAtMost(currentMaxPanY.value)
                                }
                            }

                            when (mode) {
                                GestureMode.PENDING -> {
                                    currentOnSelect.value(hit?.clip?.id)
                                    menuJob = timerScope.launch {
                                        delay(MENU_HOLD_MS)
                                        showMenu()
                                    }
                                }
                                GestureMode.REORDER_TRACK -> {
                                    beginTrackGesture(GestureMode.REORDER_TRACK)
                                    reorderTargetIndex = startTrackIndex ?: -1
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                }
                                GestureMode.SCRUB -> {
                                    currentOnSeek.value((startContent.x / PX_PER_MS).toLong())
                                }
                                GestureMode.TRIM_LEFT,
                                GestureMode.TRIM_RIGHT,
                                -> beginClipGesture(mode)
                                else -> Unit
                            }

                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Main)
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (!change.pressed) break

                                if ((change.position - start).getDistance() > touchSlop) moved = true
                                if (mode == GestureMode.PENDING && moved) {
                                    beginClipGesture(GestureMode.MOVE)
                                }

                                when (mode) {
                                    GestureMode.SCRUB -> {
                                        change.consume()
                                        val contentX = change.position.x + panX
                                        currentOnSeek.value(
                                            (contentX / PX_PER_MS).toLong().coerceIn(
                                                0L,
                                                currentContentDurationMs.value.toLong(),
                                            ),
                                        )
                                    }
                                    GestureMode.PAN -> {
                                        change.consume()
                                        panX = (startPan.x - (change.position.x - start.x))
                                            .coerceIn(0f, currentMaxPanX.value)
                                        panY = (startPan.y - (change.position.y - start.y))
                                            .coerceIn(0f, currentMaxPanY.value)
                                    }
                                    GestureMode.REORDER_TRACK -> {
                                        change.consume()
                                        if (change.position.y < rulerPx + autoScrollEdgePx) {
                                            panY = (panY - AUTO_SCROLL_PX_PER_EVENT).coerceAtLeast(0f)
                                        } else if (change.position.y >
                                            currentViewportHeight.value - autoScrollEdgePx
                                        ) {
                                            panY = (panY + AUTO_SCROLL_PX_PER_EVENT)
                                                .coerceAtMost(currentMaxPanY.value)
                                        }
                                        val target = trackIndexAt(change.position.y)
                                        if (target != null && target != reorderTargetIndex) {
                                            reorderTargetIndex = target
                                            if (reorderTrackId != null) {
                                                currentOnReorderTrack.value(reorderTrackId, target)
                                                haptic.performHapticFeedback(
                                                    HapticFeedbackType.TextHandleMove,
                                                )
                                            }
                                        }
                                    }
                                    GestureMode.MOVE,
                                    GestureMode.TRIM_LEFT,
                                    GestureMode.TRIM_RIGHT,
                                    -> {
                                        change.consume()
                                        autoScroll(change.position)
                                        val deltaMs =
                                            ((change.position.x + panX - startContent.x) / PX_PER_MS).toLong()
                                        if (hit != null) {
                                            val sourceTrackId = activeTrackId ?: hit.track.id
                                            when (mode) {
                                                GestureMode.MOVE -> {
                                                    val targetY =
                                                        change.position.y + panY - trackTopOffset
                                                    val lastIndex =
                                                        currentProject.value.tracks.lastIndex.coerceAtLeast(0)
                                                    val targetIndex = (targetY / trackPx)
                                                        .toInt()
                                                        .coerceIn(0, lastIndex)
                                                    val targetTrack =
                                                        currentProject.value.tracks.getOrNull(targetIndex)
                                                    if (targetTrack != null) {
                                                        currentOnMoveClip.value(
                                                            sourceTrackId,
                                                            hit.clip.id,
                                                            targetTrack.id,
                                                            hit.clip.timelineStartMs + deltaMs,
                                                        )
                                                        activeTrackId = targetTrack.id
                                                    }
                                                }
                                                GestureMode.TRIM_LEFT -> currentOnTrimLeft.value(
                                                    sourceTrackId,
                                                    hit.clip.id,
                                                    hit.clip.sourceInMs + deltaMs,
                                                )
                                                GestureMode.TRIM_RIGHT -> currentOnTrimRight.value(
                                                    sourceTrackId,
                                                    hit.clip.id,
                                                    hit.clip.sourceOutMs + deltaMs,
                                                )
                                                else -> Unit
                                            }
                                        }
                                    }
                                    GestureMode.MENU -> change.consume()
                                    GestureMode.PENDING -> Unit
                                }
                            }

                            menuJob?.cancel()
                            if (gestureStarted) currentOnGestureEnd.value()
                            reorderTargetIndex = -1
                            if (!moved && !menuShown && mode == GestureMode.PENDING) {
                                currentOnSelect.value(hit?.clip?.id)
                            }
                        }
                    } finally {
                        timerScope.cancel()
                    }
                },
        ) {
            translate(left = -panX, top = -panY) {
                drawRuler(
                    colors = colors,
                    rulerPx = rulerPx,
                    width = contentWidthPx,
                    height = contentHeightPx,
                    pxPerMs = PX_PER_MS,
                    visibleLeft = panX,
                    visibleRight = panX + viewportWidthPx,
                )

                if (isRecording) {
                    drawRect(
                        color = colors.errorContainer.copy(alpha = 0.35f),
                        topLeft = Offset(0f, rulerPx),
                        size = Size(contentWidthPx, trackPx),
                    )
                    drawLiveWaveform(
                        values = recordingWaveform,
                        width = contentWidthPx,
                        top = rulerPx,
                        height = trackPx,
                        color = colors.error,
                    )
                    val recordX = (recordingElapsedMs * PX_PER_MS).coerceIn(0f, contentWidthPx)
                    drawLine(
                        color = colors.error,
                        start = Offset(recordX, rulerPx),
                        end = Offset(recordX, contentHeightPx),
                        strokeWidth = 2f,
                    )
                }

                project.tracks.forEachIndexed { index, track ->
                    val top = trackTopOffset + index * trackPx
                    drawLine(
                        color = colors.outlineVariant,
                        start = Offset(0f, top),
                        end = Offset(contentWidthPx, top),
                        strokeWidth = 1f,
                    )
                    track.clips.forEach { clip ->
                        val left = clip.timelineStartMs * PX_PER_MS
                        val width = clip.durationMs * PX_PER_MS
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
                            val handleTop = top + 10f
                            val handleBottom = top + trackPx - 10f
                            drawLine(
                                color = colors.primary,
                                start = Offset(left + handleWidthPx / 2f, handleTop),
                                end = Offset(left + handleWidthPx / 2f, handleBottom),
                                strokeWidth = handleWidthPx,
                            )
                            drawLine(
                                color = colors.primary,
                                start = Offset(left + width - handleWidthPx / 2f, handleTop),
                                end = Offset(left + width - handleWidthPx / 2f, handleBottom),
                                strokeWidth = handleWidthPx,
                            )
                        }

                        val peak = peaks[clip.sourceId]
                        if (peak != null && width > 0f) {
                            drawWaveform(
                                peaks = peak,
                                clip = clip,
                                barCount = max(1, width.toInt()),
                                left = left,
                                trackTop = top,
                                trackPx = trackPx,
                                color = colors.primary,
                                visibleLeft = panX,
                                visibleRight = panX + viewportWidthPx,
                            )
                        }
                    }
                }

                val playheadX = playheadMs * PX_PER_MS
                drawLine(
                    color = colors.error,
                    start = Offset(playheadX, rulerPx),
                    end = Offset(playheadX, contentHeightPx),
                    strokeWidth = if (isRecording) 1f else 2f,
                )
            }

            drawTrackHandles(
                colors = colors,
                trackCount = project.tracks.size,
                trackPx = trackPx,
                trackTopOffset = trackTopOffset - panY,
                rulerPx = rulerPx,
                headerWidth = trackHeaderPx,
                targetIndex = reorderTargetIndex,
            )

            drawScrollIndicators(
                colors = colors,
                panX = panX,
                panY = panY,
                viewportWidth = viewportWidthPx,
                viewportHeight = viewportHeightPx,
                contentWidth = contentWidthPx,
                contentHeight = contentHeightPx,
                maxPanX = maxPanX,
                maxPanY = maxPanY,
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

private fun DrawScope.drawRuler(
    colors: ColorScheme,
    rulerPx: Float,
    width: Float,
    height: Float,
    pxPerMs: Float,
    visibleLeft: Float,
    visibleRight: Float,
) {
    drawRect(
        color = colors.surfaceVariant,
        topLeft = Offset.Zero,
        size = Size(width, rulerPx),
    )
    val stepMs = when {
        pxPerMs * 1_000 < 40 -> 10_000L
        pxPerMs * 1_000 < 90 -> 5_000L
        pxPerMs * 1_000 < 180 -> 2_000L
        else -> 1_000L
    }
    var tick = maxOf(0L, ((visibleLeft / pxPerMs).toLong() / stepMs) * stepMs)
    while (tick * pxPerMs <= visibleRight) {
        val x = tick * pxPerMs
        drawLine(
            color = colors.outlineVariant.copy(alpha = 0.5f),
            start = Offset(x, 0f),
            end = Offset(x, height),
            strokeWidth = 1f,
        )
        drawLine(
            color = colors.outline,
            start = Offset(x, rulerPx - 8f),
            end = Offset(x, rulerPx),
            strokeWidth = 1f,
        )
        tick += stepMs
    }
    drawLine(
        color = colors.outline,
        start = Offset(0f, rulerPx),
        end = Offset(width, rulerPx),
        strokeWidth = 1f,
    )
}

private fun DrawScope.drawTrackHandles(
    colors: ColorScheme,
    trackCount: Int,
    trackPx: Float,
    trackTopOffset: Float,
    rulerPx: Float,
    headerWidth: Float,
    targetIndex: Int,
) {
    clipRect(top = rulerPx) {
        for (index in 0 until trackCount) {
            val top = trackTopOffset + index * trackPx
            drawRect(
                color = colors.surfaceVariant.copy(alpha = 0.95f),
                topLeft = Offset(0f, top),
                size = Size(headerWidth, trackPx),
            )
            drawLine(
                color = colors.outlineVariant,
                start = Offset(headerWidth, top),
                end = Offset(headerWidth, top + trackPx),
                strokeWidth = 1f,
            )
            val cx = headerWidth / 2f
            val cy = top + trackPx / 2f
            for (row in -1..1) {
                drawLine(
                    color = colors.onSurfaceVariant.copy(alpha = 0.75f),
                    start = Offset(cx - 8f, cy + row * 6f),
                    end = Offset(cx + 8f, cy + row * 6f),
                    strokeWidth = 2f,
                )
            }
        }
        if (targetIndex in 0 until trackCount) {
            val y = trackTopOffset + targetIndex * trackPx
            drawLine(
                color = colors.primary,
                start = Offset(0f, y),
                end = Offset(headerWidth, y),
                strokeWidth = 3f,
            )
        }
    }
}

private fun DrawScope.drawScrollIndicators(
    colors: ColorScheme,
    panX: Float,
    panY: Float,
    viewportWidth: Float,
    viewportHeight: Float,
    contentWidth: Float,
    contentHeight: Float,
    maxPanX: Float,
    maxPanY: Float,
) {
    val thumbColor = colors.onSurface.copy(alpha = 0.24f)
    if (maxPanX > 0f) {
        val thumbWidth = max(32f, viewportWidth * viewportWidth / contentWidth)
        val thumbX = (panX / maxPanX) * (viewportWidth - thumbWidth)
        drawRoundRect(
            color = thumbColor,
            topLeft = Offset(thumbX, viewportHeight - 5f),
            size = Size(thumbWidth, 3f),
            cornerRadius = CornerRadius(2f),
        )
    }
    if (maxPanY > 0f) {
        val thumbHeight = max(32f, viewportHeight * viewportHeight / contentHeight)
        val thumbY = (panY / maxPanY) * (viewportHeight - thumbHeight)
        drawRoundRect(
            color = thumbColor,
            topLeft = Offset(viewportWidth - 5f, thumbY),
            size = Size(3f, thumbHeight),
            cornerRadius = CornerRadius(2f),
        )
    }
}

private fun hitClip(
    project: Project,
    contentOffset: Offset,
    pxPerMs: Float,
    trackPx: Float,
    trackTopOffset: Float,
    edgeTouchPx: Float,
): ClipHit? {
    if (contentOffset.y < trackTopOffset ||
        contentOffset.y >= trackTopOffset + project.tracks.size * trackPx
    ) {
        return null
    }
    val trackIndex = ((contentOffset.y - trackTopOffset) / trackPx).toInt()
    val track = project.tracks.getOrNull(trackIndex) ?: return null

    track.clips.forEach { clip ->
        val left = clip.timelineStartMs * pxPerMs
        val right = left + clip.durationMs * pxPerMs
        val width = right - left
        val tolerance = min(edgeTouchPx, max(12f, width / 2f))
        val nearLeft = abs(contentOffset.x - left) <= tolerance
        val nearRight = abs(contentOffset.x - right) <= tolerance
        val inside = contentOffset.x in left..right
        if (!inside && !nearLeft && !nearRight) return@forEach

        val edge = when {
            nearLeft && (!nearRight ||
                abs(contentOffset.x - left) <= abs(contentOffset.x - right)) -> Edge.LEFT
            nearRight -> Edge.RIGHT
            inside -> Edge.BODY
            else -> return@forEach
        }
        return ClipHit(track, clip, edge)
    }
    return null
}

private fun DrawScope.drawLiveWaveform(
    values: List<Float>,
    width: Float,
    top: Float,
    height: Float,
    color: Color,
) {
    val center = top + height / 2f
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
    visibleLeft: Float,
    visibleRight: Float,
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
    val width = (clip.durationMs * PX_PER_MS).coerceAtLeast(1f)
    val firstBar = max(0, ((visibleLeft - left) * count / width).toInt())
    val lastBar = min(count - 1, ((visibleRight - left) * count / width).toInt())

    for (x in firstBar..lastBar) {
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
