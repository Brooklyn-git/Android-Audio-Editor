package dev.audioeditor.ui

/** Compact m:ss.t timestamp shared by the UI and status messages. */
internal fun formatTime(ms: Long): String {
    val totalSeconds = ms / 1_000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    val tenths = (ms % 1_000) / 100
    return "%d:%02d.%d".format(minutes, seconds, tenths)
}
