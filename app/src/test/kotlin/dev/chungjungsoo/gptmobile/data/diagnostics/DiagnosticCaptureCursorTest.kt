package dev.chungjungsoo.gptmobile.data.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticCaptureCursorTest {
    @Test fun repeatedEventsAndTheirContinuationsRetainIdentityAcrossCaptureSources() {
        val seen = mutableSetOf<String>()
        val critical = DiagnosticCaptureCursor("critical.log", seen)
        val current = DiagnosticCaptureCursor("current.log", seen)
        val root = "2026-11-02T12:00:00Z E/Runtime: eventId=event-1 · Failed"
        val child = "parentEventId=event-1 · sequence=1 · at runtime()"
        assertFalse(critical.accept(root, 0).duplicate)
        assertFalse(critical.accept(child, 1).duplicate)
        val duplicateRoot = current.accept(root, 10)
        val duplicateChild = current.accept(child, 11)
        assertTrue(duplicateRoot.duplicate)
        assertTrue(duplicateChild.duplicate)
        assertEquals("event-1:1", duplicateChild.identity)
        assertEquals("event-1", duplicateChild.parent)
    }

    @Test fun continuationAtNewSourceBoundaryCannotInheritUnrelatedParent() {
        val seen = mutableSetOf<String>()
        DiagnosticCaptureCursor("previous.log", seen).accept("2026-11-02T12:00:00Z E/Test: eventId=old · error", 0)
        val record = DiagnosticCaptureCursor("current.log", seen).accept(" at croppedStack()", 0)
        assertNull(record.parent)
        assertEquals("current.log:1", record.identity)
        assertFalse(record.duplicate)
    }
}
