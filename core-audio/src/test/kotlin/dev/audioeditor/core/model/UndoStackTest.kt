package dev.audioeditor.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UndoStackTest {

    @Test
    fun commitUndoRedoRestoresStates() {
        val history = UndoStack("empty")
        history.commit("one")
        history.commit("two")

        assertEquals("two", history.current)
        assertTrue(history.undo())
        assertEquals("one", history.current)
        assertTrue(history.undo())
        assertEquals("empty", history.current)
        assertFalse(history.undo())

        assertTrue(history.redo())
        assertEquals("one", history.current)
        assertTrue(history.redo())
        assertEquals("two", history.current)
        assertFalse(history.redo())
    }

    @Test
    fun duplicateCommitIsIgnoredAndRedoBranchIsDiscarded() {
        val history = UndoStack(0)
        history.commit(1)
        history.commit(1)
        assertEquals(1, history.current)

        assertTrue(history.undo())
        history.commit(2)
        assertFalse(history.canRedo)
        assertEquals(2, history.current)
    }

    @Test
    fun historyIsCapped() {
        val history = UndoStack(0, limit = 2)
        history.commit(1)
        history.commit(2)
        history.commit(3)

        assertTrue(history.undo())
        assertTrue(history.undo())
        assertEquals(1, history.current)
        assertFalse(history.undo())
    }
}
