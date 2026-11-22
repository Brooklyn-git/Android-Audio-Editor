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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.audioeditor.core.model.ProjectOps
import dev.audioeditor.ui.EditorViewModel
import dev.audioeditor.ui.Timeline

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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Audio Editor") },
                actions = {
                    TextButton(onClick = { importLauncher.launch(arrayOf("audio/*")) }) {
                        Text("Import")
                    }
                    TextButton(
                        onClick = { exportLauncher.launch("audio-editor.wav") },
                        enabled = durationMs > 0 && !isWorking,
                    ) {
                        Text("Export")
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
                    Text(if (isPlaying) "Pause" else "Play")
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
                    Text(if (isRecording) "Stop" else "Record")
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                OutlinedButton(onClick = { viewModel.undo() }) { Text("Undo") }
                OutlinedButton(onClick = { viewModel.redo() }) { Text("Redo") }
                OutlinedButton(
                    onClick = { viewModel.splitAtPlayhead() },
                    enabled = selectedClip != null,
                ) { Text("Split") }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                OutlinedButton(
                    onClick = { viewModel.captureNoiseProfile() },
                    enabled = selectedClip != null && !isWorking,
                ) { Text("Get noise") }
                OutlinedButton(
                    onClick = { viewModel.reduceNoise() },
                    enabled = selectedClip != null && !isWorking,
                ) { Text("Reduce noise") }
            }

            Text(
                text = if (isWorking) "$status (working...)" else status,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = "${formatTime(playheadMs)} / ${formatTime(durationMs)}" +
                    if (selectedClip != null) "  •  selected: ${formatTime(selectedClip.sourceInMs)}–${formatTime(selectedClip.sourceOutMs)}" else "",
                style = MaterialTheme.typography.labelLarge,
            )

            Timeline(
                project = project,
                peaks = peaks,
                selectedClipId = selectedId,
                playheadMs = playheadMs,
                onSelect = { viewModel.selectClip(it) },
                onSeek = { viewModel.seek(it) },
                onGestureStart = { viewModel.beginGesture() },
                onMove = { trackId, clipId, start -> viewModel.moveClip(trackId, clipId, start) },
                onCrop = { trackId, clipId, from, to -> viewModel.cropClip(trackId, clipId, from, to) },
                onGestureEnd = { viewModel.endGesture() },
            )

            Text(
                text = "Tip: tap a clip to select it, drag its body to move it, or drag either edge to crop. " +
                    "Tap the ruler to move the playhead. Crop to a noise-only passage, tap Get noise, then Reduce noise.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

private fun formatTime(ms: Long): String {
    val totalSeconds = ms / 1_000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    val tenths = (ms % 1_000) / 100
    return "%d:%02d.%d".format(minutes, seconds, tenths)
}
