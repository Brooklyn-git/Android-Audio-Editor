package dev.audioeditor.core.model

/**
 * Snapshot-based undo/redo. States should be small immutable values; audio data
 * is referenced by id and is never copied into the history.
 */
class UndoStack<T>(initial: T, private val limit: Int = 100) {
    private var currentState: T = initial
    private val past = ArrayDeque<T>()
    private val future = ArrayDeque<T>()

    val current: T get() = currentState
    val canUndo: Boolean get() = past.isNotEmpty()
    val canRedo: Boolean get() = future.isNotEmpty()

    fun commit(next: T) {
        if (next == currentState) return
        past.addLast(currentState)
        while (past.size > limit) past.removeFirst()
        future.clear()
        currentState = next
    }

    fun undo(): Boolean {
        if (!canUndo) return false
        future.addLast(currentState)
        currentState = past.removeLast()
        return true
    }

    fun redo(): Boolean {
        if (!canRedo) return false
        past.addLast(currentState)
        currentState = future.removeLast()
        return true
    }

    fun reset(state: T) {
        currentState = state
        past.clear()
        future.clear()
    }
}
