package com.byd.dashcast.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClusterImeRelaySessionTest {

    @Test
    fun `a session accepts only its active display and package`() {
        val session = ClusterImeRelaySession()
        session.bind(3, "com.example.navigation")

        assertTrue(session.accepts(3, "com.example.navigation"))
        assertFalse(session.accepts(4, "com.example.navigation"))
        assertFalse(session.accepts(3, "com.example.messaging"))
    }

    @Test
    fun `invalid targets and session end fail closed`() {
        val session = ClusterImeRelaySession()
        session.bind(0, "com.example.navigation")
        assertFalse(session.hasTargetOn(0))

        session.bind(3, "com.example.navigation")
        session.clear()
        assertFalse(session.hasTargetOn(3))
        assertNull(session.packageOn(3))
    }

    @Test
    fun `rebinding replaces the complete target identity`() {
        val session = ClusterImeRelaySession()
        session.bind(2, "com.example.first")
        session.bind(5, "com.example.second")

        assertFalse(session.accepts(2, "com.example.first"))
        assertEquals("com.example.second", session.packageOn(5))
    }
}