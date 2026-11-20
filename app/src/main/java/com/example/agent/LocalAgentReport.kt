package com.example.agent

import java.util.Locale

internal enum class PhoneStep(val label: String, val status: String) {
    STUDIO("Apri Studio", "Controllo telefono disattivato in Studio."),
    SETTINGS("Apri Accessibilità", "Servizio di Accessibilità non connesso."),
    RECENTS("Apri Recenti", "Servizio connesso. Puoi aprire i Recenti; non è un elenco dei processi attivi.")
}

internal fun phoneStep(enabled: Boolean, connected: Boolean) = when {
    !enabled -> PhoneStep.STUDIO
    !connected -> PhoneStep.SETTINGS
    else -> PhoneStep.RECENTS
}

internal fun formatInferenceStats(pass: String, first: Double, prefill: Double, decode: Double): String? {
    fun Double.metric(scale: Double = 1.0): String = if (isFinite() && this > 0) String.format(Locale.ITALY, "%.1f", this * scale) else "n/d"
    if (listOf(first, prefill, decode).none { it.isFinite() && it > 0 }) return null
    return "Ultimo passaggio: $pass\nPrimo token: ${first.metric(1000.0)} ms\nLettura prompt: ${prefill.metric()} tok/s\nGenerazione: ${decode.metric()} tok/s"
}
