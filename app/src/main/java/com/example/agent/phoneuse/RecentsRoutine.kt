package com.example.agent.phoneuse

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex

/** One dispatch; acceptance by Android is not proof that Overview is visible. */
internal class RecentsRoutine(private val pause: suspend (Long) -> Unit = { delay(it) }) {
    private val lock = Mutex()
    private var firstProbeMs = 180L

    suspend fun run(connected: () -> Boolean, open: () -> Boolean, visible: () -> Boolean): String {
        if (!lock.tryLock()) return "Verifica Recenti già in corso."
        try {
            if (!connected()) return "Servizio disconnesso."
            if (!open()) return "Android ha rifiutato l'apertura dei Recenti."
            var elapsed = 0L
            repeat(4) { index ->
                val wait = if (index == 0) firstProbeMs else 160L
                pause(wait)
                elapsed += wait
                if (!connected()) return "Servizio disconnesso: esito non verificato."
                if (visible()) {
                    // Probe slightly earlier next time; never increase the overall observation budget indefinitely.
                    firstProbeMs = (elapsed - 60L).coerceIn(100L, 500L)
                    return com.example.agent.LocalLearning.verifiedRecents
                }
            }
            return "Android ha accettato la richiesta, ma il pannello Recenti non è verificabile."
        } finally {
            lock.unlock()
        }
    }
}
