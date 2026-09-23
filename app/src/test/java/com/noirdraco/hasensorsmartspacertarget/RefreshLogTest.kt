package com.noirdraco.hasensorsmartspacertarget

import org.junit.Assert.assertEquals
import org.junit.Test

/** Prüft den Ringpuffer von [RefreshLog] (reine Listenlogik, ohne Dateizugriff). */
class RefreshLogTest {

    @Test
    fun belowLimit_onlyAppends() {
        assertEquals(listOf("a", "b", "c"), RefreshLog.appendCapped(listOf("a", "b"), "c", 5))
    }

    @Test
    fun atLimit_dropsOldestLine() {
        assertEquals(listOf("b", "c", "d"), RefreshLog.appendCapped(listOf("a", "b", "c"), "d", 3))
    }

    @Test
    fun emptyLog_startsWithLine() {
        assertEquals(listOf("a"), RefreshLog.appendCapped(emptyList(), "a", 3))
    }

    @Test
    fun shortId_takesFirstEightChars() {
        assertEquals("1234abcd", RefreshLog.shortId("1234abcd-5678-90ef"))
        assertEquals("kurz", RefreshLog.shortId("kurz"))
    }
}
