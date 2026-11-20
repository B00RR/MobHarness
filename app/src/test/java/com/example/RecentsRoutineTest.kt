package com.example

import com.example.agent.phoneuse.RecentsRoutine
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class RecentsRoutineTest {
    @Test fun observeWithoutRedispatchAndAdaptOnlyFromSuccess() = runTest {
        val delays = mutableListOf<Long>()
        val routine = RecentsRoutine { delays.add(it) }
        var sends = 0
        var observations = 0
        val accepted = { sends++; true }
        assertTrue(routine.run({ true }, accepted, { ++observations == 3 }).contains("visibile e verificato"))
        assertEquals(1, sends)
        assertEquals(listOf(180L, 160L, 160L), delays)
        delays.clear()
        assertTrue(routine.run({ true }, accepted, { false }).contains("non è verificabile"))
        assertEquals(listOf(440L, 160L, 160L, 160L), delays)
        delays.clear()
        routine.run({ true }, accepted, { true })
        assertEquals(listOf(440L), delays)
    }

    @Test fun revocationStopsObservationAndRejectedDispatchNeverReads() = runTest {
        var allowed = true
        val routine = RecentsRoutine { allowed = false }
        var reads = 0
        val observe = { reads++; true }
        assertTrue(routine.run({ allowed }, { true }, observe).contains("disconnesso"))
        assertTrue(routine.run({ true }, { false }, observe).contains("rifiutato"))
        assertEquals(0, reads)
    }
}
