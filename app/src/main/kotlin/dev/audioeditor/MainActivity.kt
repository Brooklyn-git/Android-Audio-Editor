package dev.audioeditor

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FilterAlt
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.audioeditor.R
import dev.audioeditor.core.model.ProjectOps
import dev.audioeditor.ui.EditorViewModel
import dev.audioeditor.ui.Timeline
import dev.audioeditor.ui.formatTime

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                EditorScreen()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(viewModel: EditorViewModel = viewModel()) {
    val project by viewModel.project.collectAsState()
    val peaks by viewModel.peaks.collectAsState()
    val selectedId by viewModel.selectedClipId.collectAsState()
    val playheadMs by viewModel.playheadMs.collectAsState()
    val isPlaying by viewModel.isPlaying.collectAsState()
    val isRecording by viewModel.isRecording.collectAsState()
    val isWorking by viewModel.isWorking.collectAsState()
    val status by viewModel.status.collectAsState()
    val recordingWaveform by viewModel.recordingWaveform.collectAsState()
    val recordingElapsedMs by viewModel.recordingElapsedMs.collectAsState()

    var showNoiseScopeDialog by remember { mutableStateOf(false) }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.import(uri)
    }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("audio/wav")) { uri ->
        if (uri != null) viewModel.export(uri)
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) viewModel.startRecording()
    }

    val durationMs = ProjectOps.durationMs(project)
    val selectedClip = project.tracks.firstNotNullOfOrNull { track ->
        track.clips.firstOrNull { it.id == selectedId }
    }
    val statusText = when {
        isRecording -> stringResource(R.string.status_recording, formatTime(recordingElapsedMs))
        isWorking -> stringResource(R.string.status_working, status)
        else -> status
    }
    val timeText = stringResource(
        R.string.time_current_of_total,
        formatTime(playheadMs),
        formatTime(durationMs),
    )
    val selectedText = selectedClip?.let {
        stringResource(R.string.selected_range, formatTime(it.sourceInMs), formatTime(it.sourceOutMs))
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    TextButton(onClick = { importLauncher.launch(arrayOf("audio/*")) }) {
                        IconedLabel(Icons.Filled.Upload, stringResource(R.string.action_import))
                    }
                    TextButton(
                        onClick = { exportLauncher.launch("audio-editor.wav") },
                        enabled = durationMs > 0 && !isWorking,
                    ) {
                        IconedLabel(Icons.Filled.Download, stringResource(R.string.action_export))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(onClick = { viewModel.togglePlayback() }, enabled = durationMs > 0) {
                    val icon = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow
                    val label = stringResource(
                        if (isPlaying) R.string.action_pause else R.string.action_play,
                    )
                    IconedLabel(icon, label)
                }
                Button(
                    onClick = {
                        if (isRecording) {
                            viewModel.stopRecording()
                        } else if (viewModel.canRecord()) {
                            viewModel.startRecording()
                        } else {
                            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    },
                    enabled = !isWorking,
                ) {
                    val icon = if (isRecording) Icons.Filled.Stop else Icons.Filled.Mic
                    val label = stringResource(
                        if (isRecording) R.string.action_stop else R.string.action_record,
                    )
                    IconedLabel(icon, label)
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                OutlinedButton(onClick = { viewModel.undo() }) {
                    IconedLabel(Icons.AutoMirrored.Filled.Undo, stringResource(R.string.action_undo))
                }
                OutlinedButton(onClick = { viewModel.redo() }) {
                    IconedLabel(Icons.AutoMirrored.Filled.Redo, stringResource(R.string.action_redo))
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                OutlinedButton(
                    onClick = { viewModel.captureNoiseProfile() },
                    enabled = selectedClip != null && !isWorking,
                ) {
                    IconedLabel(Icons.Filled.GraphicEq, stringResource(R.string.action_get_noise))
                }
                OutlinedButton(
                    onClick = { showNoiseScopeDialog = true },
                    enabled = selectedClip != null && !isWorking,
                ) {
                    IconedLabel(Icons.Filled.FilterAlt, stringResource(R.string.action_reduce_noise))
                }
            }

            Text(text = statusText, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = listOfNotNull(timeText, selectedText).joinToString("  •  "),
                style = MaterialTheme.typography.labelLarge,
            )

            Timeline(
                project = project,
                peaks = peaks,
                selectedClipId = selectedId,
                playheadMs = playheadMs,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(360.dp),
                isRecording = isRecording,
                recordingWaveform = recordingWaveform,
                recordingElapsedMs = recordingElapsedMs,
                onSelect = { viewModel.selectClip(it) },
                onSeek = { viewModel.seek(it) },
                onDeleteClip = { trackId, clipId -> viewModel.deleteClip(trackId, clipId) },
                onSplitClip = { trackId, clipId, atMs -> viewModel.splitClip(trackId, clipId, atMs) },
                onReduceNoiseForTrack = { trackId ->
                    viewModel.reduceNoise(EditorViewModel.NoiseScope.CURRENT_TRACK, trackId)
                },
                onGestureStart = { viewModel.beginGesture() },
                onMoveClip = { trackId, clipId, targetTrackId, start ->
                    viewModel.moveClip(trackId, clipId, targetTrackId, start)
                },
                onTrimLeft = { trackId, clipId, sourceIn -> viewModel.trimLeft(trackId, clipId, sourceIn) },
                onTrimRight = { trackId, clipId, sourceOut -> viewModel.trimRight(trackId, clipId, sourceOut) },
                onGestureEnd = { viewModel.endGesture() },
            )

            Text(
                text = stringResource(R.string.tip_timeline),
                style = MaterialTheme.typography.bodySmall,
            )
        }

        if (showNoiseScopeDialog) {
            AlertDialog(
                onDismissRequest = { showNoiseScopeDialog = false },
                title = { Text(stringResource(R.string.dialog_noise_scope_title)) },
                text = { Text(stringResource(R.string.dialog_noise_scope_message)) },
                confirmButton = {
                    Row {
                        TextButton(
                            onClick = {
                                viewModel.reduceNoise(EditorViewModel.NoiseScope.CURRENT_TRACK)
                                showNoiseScopeDialog = false
                            },
                        ) {
                            Text(stringResource(R.string.dialog_noise_scope_current))
                        }
                        TextButton(
                            onClick = {
                                viewModel.reduceNoise(EditorViewModel.NoiseScope.ALL_TRACKS)
                                showNoiseScopeDialog = false
                            },
                        ) {
                            Text(stringResource(R.string.dialog_noise_scope_all))
                        }
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showNoiseScopeDialog = false }) {
                        Text(stringResource(R.string.action_cancel))
                    }
                },
            )
        }
    }
}

@Composable
private fun IconedLabel(icon: ImageVector, label: String) {
    Icon(icon, contentDescription = null)
    Spacer(Modifier.width(8.dp))
    Text(label)
}
