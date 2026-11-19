package com.example.agent.tools

import android.Manifest
import android.app.ActivityManager
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.AlarmClock
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

data class DeviceHardwareSummary(
    val modelName: String,
    val androidVersion: String,
    val cpuCores: Int,
    val totalRamGb: Double,
    val availableRamGb: Double,
    val freeStorageGb: Double,
    val batteryPercent: Int,
    val isCharging: Boolean,
    val batteryTempC: Float,
    val networkStatus: String
)

class AndroidSystemActions(private val context: Context) {

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    fun getHardwareSummary(): DeviceHardwareSummary {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        am.getMemoryInfo(memInfo)

        val statFs = StatFs(Environment.getDataDirectory().path)
        val freeBytes = statFs.availableBlocksLong * statFs.blockSizeLong

        val batteryIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val pct = if (level >= 0 && scale > 0) (level * 100 / scale) else -1
        val status = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        val tempTenths = batteryIntent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0

        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        val netStatus = when {
            caps == null -> "Offline"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Rete Mobile (5G/LTE)"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            else -> "Connesso"
        }

        return DeviceHardwareSummary(
            modelName = "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}",
            androidVersion = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            cpuCores = Runtime.getRuntime().availableProcessors(),
            totalRamGb = memInfo.totalMem / (1024.0 * 1024.0 * 1024.0),
            availableRamGb = memInfo.availMem / (1024.0 * 1024.0 * 1024.0),
            freeStorageGb = freeBytes / (1024.0 * 1024.0 * 1024.0),
            batteryPercent = pct,
            isCharging = charging,
            batteryTempC = tempTenths / 10f,
            networkStatus = netStatus
        )
    }

    fun getTelemetryReport(): String {
        val hw = getHardwareSummary()
        return buildString {
            appendLine("Dispositivo: ${hw.modelName} (${hw.androidVersion})")
            appendLine("CPU: ${hw.cpuCores} core")
            appendLine(String.format(Locale.US, "RAM: %.2f GB liberi / %.2f GB totali", hw.availableRamGb, hw.totalRamGb))
            appendLine(String.format(Locale.US, "Storage libero: %.2f GB", hw.freeStorageGb))
            appendLine("Batteria: ${hw.batteryPercent}% (${if (hw.isCharging) "in carica" else "a batteria"}, ${hw.batteryTempC}°C)")
            appendLine("Rete: ${hw.networkStatus}")
        }.trimEnd()
    }

    fun readClipboardText(): String {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = cm.primaryClip
        if (clip == null || clip.itemCount == 0) return "(Appunti vuoti o non accessibili in background)"
        val text = clip.getItemAt(0).coerceToText(context)?.toString().orEmpty()
        return if (text.isBlank()) "(Appunti vuoti)" else text.take(1500)
    }

    suspend fun getSensorSnapshot(): String = withContext(Dispatchers.Default) {
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val readings = ConcurrentHashMap<String, String>()
        val targetTypes = listOf(
            Sensor.TYPE_LIGHT to "Luce ambientale",
            Sensor.TYPE_ACCELEROMETER to "Accelerometro",
            Sensor.TYPE_PRESSURE to "Barometro",
            Sensor.TYPE_MAGNETIC_FIELD to "Magnetometro"
        )
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                when (event.sensor.type) {
                    Sensor.TYPE_LIGHT -> readings["Luce ambientale"] = String.format(Locale.US, "%.1f lux", event.values[0])
                    Sensor.TYPE_PRESSURE -> readings["Barometro"] = String.format(Locale.US, "%.1f hPa", event.values[0])
                    Sensor.TYPE_ACCELEROMETER -> readings["Accelerometro"] = String.format(
                        Locale.US, "x=%.2f y=%.2f z=%.2f m/s²", event.values[0], event.values[1], event.values[2]
                    )
                    Sensor.TYPE_MAGNETIC_FIELD -> readings["Magnetometro"] = String.format(
                        Locale.US, "x=%.1f y=%.1f z=%.1f µT", event.values[0], event.values[1], event.values[2]
                    )
                }
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }

        val registered = mutableListOf<Sensor>()
        targetTypes.forEach { (type, _) ->
            sm.getDefaultSensor(type)?.let { sensor ->
                sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
                registered.add(sensor)
            }
        }
        delay(450)
        sm.unregisterListener(listener)

        if (readings.isEmpty()) {
            "Sensori rilevati: ${registered.size}, nessun campione immediato disponibile."
        } else {
            readings.entries.sortedBy { it.key }.joinToString("\n") { "${it.key}: ${it.value}" }
        }
    }

    @Suppress("MissingPermission")
    fun getApproxLocation(): String {
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            return "Permesso posizione non concesso. Abilitalo nella schermata Strumenti per leggere la posizione approssimativa."
        }
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val providers = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER, LocationManager.GPS_PROVIDER)
        val loc = providers.firstNotNullOfOrNull { p -> runCatching { lm.getLastKnownLocation(p) }.getOrNull() }
            ?: return "Posizione recente non ancora disponibile dal provider di sistema."
        return String.format(
            Locale.US,
            "Posizione approssimativa: lat=%.4f, lon=%.4f (precisione ~%.0fm, provider=%s)",
            loc.latitude,
            loc.longitude,
            loc.accuracy,
            loc.provider
        )
    }

    fun setAlarm(hour: Int, minute: Int, message: String): String {
        val h = hour.coerceIn(0, 23)
        val m = minute.coerceIn(0, 59)
        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, h)
            putExtra(AlarmClock.EXTRA_MINUTES, m)
            putExtra(AlarmClock.EXTRA_MESSAGE, message.ifBlank { "Promemoria Hermes Edge" })
            putExtra(AlarmClock.EXTRA_SKIP_UI, false)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return launchIntentSafely(intent, "Sveglia configurata per %02d:%02d (%s)".format(h, m, message))
    }

    fun createCalendarEvent(
        title: String,
        description: String,
        startOffsetMinutes: Long = 60,
        durationMinutes: Long = 60
    ): String {
        val startMs = System.currentTimeMillis() + startOffsetMinutes * 60_000L
        val endMs = startMs + durationMinutes.coerceAtLeast(15) * 60_000L
        val intent = Intent(Intent.ACTION_INSERT).apply {
            data = CalendarContract.Events.CONTENT_URI
            putExtra(CalendarContract.Events.TITLE, title.ifBlank { "Evento Hermes Edge" })
            putExtra(CalendarContract.Events.DESCRIPTION, description)
            putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, startMs)
            putExtra(CalendarContract.EXTRA_EVENT_END_TIME, endMs)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return launchIntentSafely(intent, "Scheda evento calendario aperta: '$title'")
    }

    fun openMaps(query: String): String {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val intent = Intent(Intent.ACTION_VIEW, "geo:0,0?q=$encoded".toUri()).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return launchIntentSafely(intent, "Maps aperto per: $query")
    }

    fun openUrl(url: String): String {
        val clean = if (url.startsWith("http://") || url.startsWith("https://")) url else "https://$url"
        val intent = Intent(Intent.ACTION_VIEW, clean.toUri()).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return launchIntentSafely(intent, "URL aperto nel browser: $clean")
    }

    fun launchApp(appNameQuery: String): String {
        val pm = context.packageManager
        val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val apps = pm.queryIntentActivities(mainIntent, 0)
        val match = apps.firstOrNull { info ->
            val label = info.loadLabel(pm).toString()
            label.contains(appNameQuery.trim(), ignoreCase = true) ||
                info.activityInfo.packageName.contains(appNameQuery.trim(), ignoreCase = true)
        } ?: return "Nessuna app installata trovata per '$appNameQuery'."

        val launch = pm.getLaunchIntentForPackage(match.activityInfo.packageName)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        } ?: return "Impossibile avviare ${match.loadLabel(pm)}."

        return launchIntentSafely(launch, "App avviata: ${match.loadLabel(pm)} (${match.activityInfo.packageName})")
    }

    fun setTimer(seconds: Int, message: String): String {
        val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
            putExtra(AlarmClock.EXTRA_LENGTH, seconds.coerceIn(1, 86400))
            putExtra(AlarmClock.EXTRA_MESSAGE, message.ifBlank { "Timer Hermes Edge" })
            putExtra(AlarmClock.EXTRA_SKIP_UI, false)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return launchIntentSafely(intent, "Timer avviato: ${seconds}s ($message)")
    }

    fun composeEmail(to: String, subject: String, body: String): String {
        val intent = Intent(Intent.ACTION_SENDTO, "mailto:${Uri.encode(to.trim())}".toUri()).apply {
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, body)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return launchIntentSafely(intent, "Bozza email aperta per $to ($subject)")
    }

    fun composeSms(phoneNumber: String, message: String): String {
        val intent = Intent(Intent.ACTION_SENDTO, "smsto:${Uri.encode(phoneNumber.trim())}".toUri()).apply {
            putExtra("sms_body", message)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return launchIntentSafely(intent, "Bozza messaggio aperta per $phoneNumber")
    }

    fun dialNumber(phoneNumber: String): String {
        val intent = Intent(Intent.ACTION_DIAL, "tel:${Uri.encode(phoneNumber.trim())}".toUri()).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return launchIntentSafely(intent, "Tastierino aperto con $phoneNumber")
    }

    fun shareText(text: String, title: String = "Condividi da Hermes Edge"): String {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        val chooser = Intent.createChooser(send, title).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return launchIntentSafely(chooser, "Menu condivisione Android aperto.")
    }

    fun openSystemSettings(section: String): String {
        val action = when (section.lowercase().trim()) {
            "wifi" -> android.provider.Settings.ACTION_WIFI_SETTINGS
            "bluetooth" -> android.provider.Settings.ACTION_BLUETOOTH_SETTINGS
            "battery", "batteria" -> Intent.ACTION_POWER_USAGE_SUMMARY
            "accessibility", "accessibilita" -> android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS
            "display", "schermo" -> android.provider.Settings.ACTION_DISPLAY_SETTINGS
            else -> android.provider.Settings.ACTION_SETTINGS
        }
        val intent = Intent(action).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        return launchIntentSafely(intent, "Impostazioni Android aperte ($section).")
    }

    suspend fun webSearch(query: String): String = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext "Query di ricerca vuota."
        runCatching {
            val encoded = URLEncoder.encode(query.trim(), "UTF-8")
            val request = Request.Builder()
                .url("https://html.duckduckgo.com/html/?q=$encoded")
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 15; Pixel) AppleWebKit/537.36")
                .build()
            httpClient.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return@use "Ricerca web fallita (HTTP ${resp.code})."
                }
                val html = resp.body?.string().orEmpty()
                val titleRegex = Regex("""class="result__a"[^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)
                val snippetRegex = Regex("""class="result__snippet"[^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)
                val titles = titleRegex.findAll(html).map { stripHtml(it.groupValues[1]) }.take(5).toList()
                val snippets = snippetRegex.findAll(html).map { stripHtml(it.groupValues[1]) }.take(5).toList()
                if (titles.isEmpty()) {
                    "Nessun risultato web estratto per '$query'."
                } else {
                    buildString {
                        appendLine("Risultati web per \"$query\":")
                        titles.forEachIndexed { idx, t ->
                            appendLine("${idx + 1}. $t")
                            snippets.getOrNull(idx)?.takeIf { it.isNotBlank() }?.let { s ->
                                appendLine("   $s")
                            }
                        }
                    }.trimEnd()
                }
            }
        }.getOrElse { e ->
            "Errore rete durante la ricerca web: ${e.message}"
        }
    }

    suspend fun fetchWebpage(url: String, maxChars: Int = 3500): String = withContext(Dispatchers.IO) {
        val clean = if (url.startsWith("http://") || url.startsWith("https://")) url.trim() else "https://${url.trim()}"
        runCatching {
            val request = Request.Builder()
                .url(clean)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 15; Pixel) AppleWebKit/537.36")
                .build()
            httpClient.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return@use "Errore HTTP ${resp.code} su $clean"
                val raw = resp.body?.string().orEmpty()
                val cleaned = stripHtml(raw)
                "Contenuto estratto da $clean:\n${cleaned.take(maxChars)}"
            }
        }.getOrElse { e ->
            "Impossibile scaricare $clean: ${e.message}"
        }
    }

    private fun stripHtml(html: String): String {
        return html
            .replace(Regex("<script[^>]*>.*?</script>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)), " ")
            .replace(Regex("<style[^>]*>.*?</style>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)), " ")
            .replace(Regex("<[^>]+>"), " ")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun launchIntentSafely(intent: Intent, successMessage: String): String {
        return runCatching {
            context.startActivity(intent)
            successMessage
        }.getOrElse { e ->
            "Nessuna app compatibile trovata sul dispositivo per eseguire l'azione (${e.message})."
        }
    }
}
