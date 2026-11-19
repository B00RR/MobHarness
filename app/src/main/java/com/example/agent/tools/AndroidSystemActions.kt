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
import com.example.agent.ThermalAndMemoryGuard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.ConnectionPool
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
    val networkStatus: String,
    val thermalLabel: String = "Fresco",
    val appHeapMb: Int = 0,
    val nativeHeapMb: Int = 0
)

class AndroidSystemActions(private val context: Context) {

    private var httpClientInitialized = false

    // Lazy compact HTTP client: allocates zero threads/sockets until web_search or fetch_webpage is actually used
    private val httpClient: OkHttpClient by lazy {
        httpClientInitialized = true
        OkHttpClient.Builder()
            .connectionPool(ConnectionPool(2, 20, TimeUnit.SECONDS))
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    fun evictIdleConnections() {
        if (httpClientInitialized) {
            runCatching { httpClient.connectionPool.evictAll() }
        }
    }

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

        val thermalSnap = ThermalAndMemoryGuard.readSnapshot(context)

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
            networkStatus = netStatus,
            thermalLabel = thermalSnap.thermalLevel.labelIt,
            appHeapMb = thermalSnap.appHeapMb,
            nativeHeapMb = thermalSnap.nativeHeapMb
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
        try {
            targetTypes.forEach { (type, _) ->
                sm.getDefaultSensor(type)?.let { sensor ->
                    sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
                    registered.add(sensor)
                }
            }
            // Exit as soon as all registered sensors emit 1 reading (max 350ms) to minimize sensor power draw
            var waitedMs = 0L
            while (waitedMs < 350L && readings.size < registered.size) {
                delay(50L)
                waitedMs += 50L
            }
        } finally {
            sm.unregisterListener(listener)
        }

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
            putExtra(AlarmClock.EXTRA_MESSAGE, message.ifBlank { "Promemoria MobHarn" })
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
            putExtra(CalendarContract.Events.TITLE, title.ifBlank { "Evento MobHarn" })
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
        val rawQuery = appNameQuery.trim().lowercase()
        if (rawQuery.isBlank()) return "Specifica il nome dell'app da aprire."

        val aliases = mapOf(
            "fotocamera" to listOf("fotocamera", "camera"),
            "camera" to listOf("camera", "fotocamera"),
            "impostazioni" to listOf("impostazioni", "settings"),
            "orologio" to listOf("orologio", "clock", "deskclock"),
            "calendario" to listOf("calendario", "calendar"),
            "messaggi" to listOf("messaggi", "messages", "messaging"),
            "telefono" to listOf("telefono", "phone", "dialer"),
            "calcolatrice" to listOf("calcolatrice", "calculator"),
            "galleria" to listOf("foto", "photos", "galleria", "gallery"),
            "mappe" to listOf("maps", "mappe"),
            "rubrica" to listOf("contatti", "contacts"),
            "posta" to listOf("gmail", "mail", "email"),
            "browser" to listOf("chrome", "browser")
        )
        val searchTerms = aliases[rawQuery] ?: listOf(rawQuery)

        val pm = context.packageManager
        val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val apps = pm.queryIntentActivities(mainIntent, 0)
        val scored = apps.mapNotNull { info ->
            val label = info.loadLabel(pm).toString().lowercase()
            val pkg = info.activityInfo.packageName.lowercase()
            val score = searchTerms.maxOf { term ->
                when {
                    label == term -> 100
                    label.startsWith(term) -> 80
                    label.contains(term) -> 60
                    pkg.endsWith(".$term") -> 50
                    pkg.contains(term) -> 30
                    else -> 0
                }
            }
            if (score > 0) info to score else null
        }.maxByOrNull { it.second }?.first
            ?: return "Nessuna app installata trovata per '$appNameQuery'."

        val launch = pm.getLaunchIntentForPackage(scored.activityInfo.packageName)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        } ?: return "Impossibile avviare ${scored.loadLabel(pm)}."

        return launchIntentSafely(launch, "App avviata: ${scored.loadLabel(pm)} (${scored.activityInfo.packageName})")
    }

    fun setTimer(seconds: Int, message: String): String {
        val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
            putExtra(AlarmClock.EXTRA_LENGTH, seconds.coerceIn(1, 86400))
            putExtra(AlarmClock.EXTRA_MESSAGE, message.ifBlank { "Timer MobHarn" })
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

    fun shareText(text: String, title: String = "Condividi da MobHarn"): String {
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
        val cleanQuery = query.trim()
        if (cleanQuery.isBlank()) return@withContext "Query di ricerca vuota."

        val encoded = URLEncoder.encode(cleanQuery, "UTF-8")
        val wikiUa = "MobHarnAndroid/1.1 (https://mobharn.local; on-device assistant)"
        val browserUa = "Mozilla/5.0 (Linux; Android 15; Pixel 10 Pro XL) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Mobile Safari/537.36"

        fun fetchWikiLeadExtract(lang: String, title: String): String? {
            val encTitle = URLEncoder.encode(title, "UTF-8")
            val url = "https://$lang.wikipedia.org/w/api.php?action=query&prop=extracts&exintro=1&explaintext=1&exchars=680&redirects=1&titles=$encTitle&format=json"
            val req = Request.Builder().url(url).header("User-Agent", wikiUa).build()
            return httpClient.newCall(req).execute().use { resp ->
                if (resp.code != 200) return@use null
                val json = org.json.JSONObject(resp.body?.string().orEmpty())
                val pages = json.optJSONObject("query")?.optJSONObject("pages") ?: return@use null
                val firstKey = pages.keys().asSequence().firstOrNull() ?: return@use null
                if (firstKey == "-1") return@use null
                val page = pages.optJSONObject(firstKey) ?: return@use null
                val pageTitle = page.optString("title").trim()
                val extract = page.optString("extract").replace(Regex("\\s+"), " ").trim()
                if (extract.length < 30 || WebSearchParser.isWikipediaDisambiguation(pageTitle, extract)) {
                    null
                } else {
                    "$pageTitle: ${extract.take(620)}"
                }
            }
        }

        fun searchWikiBest(lang: String, q: String): List<String> {
            if (q.isBlank()) return emptyList()
            val encQ = URLEncoder.encode(q, "UTF-8")
            val req = Request.Builder()
                .url("https://$lang.wikipedia.org/w/api.php?action=query&list=search&srsearch=$encQ&utf8=&format=json&srlimit=3")
                .header("User-Agent", wikiUa)
                .build()
            return httpClient.newCall(req).execute().use { resp ->
                if (resp.code != 200) return@use emptyList()
                val json = org.json.JSONObject(resp.body?.string().orEmpty())
                val arr = json.optJSONObject("query")?.optJSONArray("search") ?: return@use emptyList()
                val out = mutableListOf<String>()
                for (idx in 0 until arr.length()) {
                    val o = arr.optJSONObject(idx) ?: continue
                    val title = o.optString("title").trim()
                    if (title.isBlank()) continue
                    if (out.isEmpty()) {
                        val lead = runCatching { fetchWikiLeadExtract(lang, title) }.getOrNull()
                        if (!lead.isNullOrBlank()) {
                            out.add("- $lead")
                            continue
                        }
                    }
                    val snippet = WebSearchParser.stripHtml(o.optString("snippet"))
                    if (snippet.isNotBlank() && !WebSearchParser.isWikipediaDisambiguation(title, snippet)) {
                        out.add("- $title: ${snippet.take(200)}")
                    }
                }
                out
            }
        }

        // Run Wikipedia and DuckDuckGo Web Search in parallel to halve tool latency
        val (wikiSection, webSection) = kotlinx.coroutines.coroutineScope {
            val wikiJob = kotlinx.coroutines.async {
                runCatching {
                    val coreEntity = WebSearchParser.extractCoreEntity(cleanQuery)
                    val wikiHits = buildList {
                        if (coreEntity.isNotBlank() && !coreEntity.equals(cleanQuery, ignoreCase = true)) {
                            val directLead = runCatching { fetchWikiLeadExtract("it", coreEntity) }.getOrNull()
                            if (!directLead.isNullOrBlank()) {
                                add("- $directLead")
                            } else {
                                addAll(searchWikiBest("it", coreEntity).take(2))
                            }
                        }
                        if (isEmpty()) addAll(searchWikiBest("it", cleanQuery).take(2))
                        if (isEmpty() && coreEntity.isNotBlank()) addAll(searchWikiBest("en", coreEntity).take(2))
                        if (isEmpty()) addAll(searchWikiBest("en", cleanQuery).take(2))
                    }.distinct().take(2)
                    if (wikiHits.isNotEmpty()) "### Enciclopedia (Wikipedia)\n" + wikiHits.joinToString("\n") else null
                }.getOrNull()
            }

            val ddgJob = kotlinx.coroutines.async {
                runCatching {
                    val formBody = okhttp3.FormBody.Builder()
                        .add("q", cleanQuery)
                        .add("kl", "it-it")
                        .build()
                    val req = Request.Builder()
                        .url("https://html.duckduckgo.com/html/")
                        .post(formBody)
                        .header("User-Agent", browserUa)
                        .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                        .header("Accept-Language", "it-IT,it;q=0.9,en;q=0.8")
                        .header("Referer", "https://html.duckduckgo.com/")
                        .build()

                    // Require HTTP 200 (HTTP 202 is DuckDuckGo's anti-bot challenge page)
                    val hits = httpClient.newCall(req).execute().use { resp ->
                        if (resp.code != 200) emptyList()
                        else WebSearchParser.parseDuckDuckGoHtml(resp.body?.string().orEmpty(), maxResults = 4)
                    }.ifEmpty {
                        val liteBody = okhttp3.FormBody.Builder().add("q", cleanQuery).build()
                        val liteReq = Request.Builder()
                            .url("https://lite.duckduckgo.com/lite/")
                            .post(liteBody)
                            .header("User-Agent", browserUa)
                            .header("Accept", "text/html,application/xhtml+xml;q=0.9,*/*;q=0.8")
                            .header("Accept-Language", "it-IT,it;q=0.9,en;q=0.8")
                            .header("Referer", "https://lite.duckduckgo.com/")
                            .build()
                        httpClient.newCall(liteReq).execute().use { resp ->
                            if (resp.code != 200) emptyList()
                            else WebSearchParser.parseDuckDuckGoLite(resp.body?.string().orEmpty(), maxResults = 4)
                        }
                    }

                    if (hits.isNotEmpty()) {
                        val lines = hits.map { hit ->
                            val domain = runCatching { Uri.parse(hit.url).host?.removePrefix("www.") }.getOrNull().orEmpty()
                            val domainSuffix = if (domain.isNotBlank()) " ($domain)" else ""
                            "- ${hit.title}$domainSuffix: ${hit.snippet}"
                        }
                        "### Risultati Web\n" + lines.joinToString("\n")
                    } else null
                }.getOrNull()
            }

            wikiJob.await() to ddgJob.await()
        }

        val sections = mutableListOf<String>()
        if (!wikiSection.isNullOrBlank()) sections.add(wikiSection)
        if (!webSection.isNullOrBlank()) sections.add(webSection)

        // 3. DuckDuckGo Instant Answer JSON API (fast direct answers/definitions if primary sources returned empty)
        if (sections.isEmpty()) {
            runCatching {
                val req = Request.Builder()
                    .url("https://api.duckduckgo.com/?q=$encoded&format=json&no_html=1&skip_disambig=1")
                    .header("User-Agent", wikiUa)
                    .build()
                httpClient.newCall(req).execute().use { resp ->
                    if (resp.code == 200) {
                        val json = org.json.JSONObject(resp.body?.string().orEmpty())
                        val answer = WebSearchParser.decodeHtmlEntities(json.optString("Answer").trim())
                        val abstractText = WebSearchParser.decodeHtmlEntities(json.optString("AbstractText").trim())
                        val lines = mutableListOf<String>()
                        if (answer.isNotBlank()) lines.add("- Risposta rapida: $answer")
                        if (abstractText.isNotBlank()) lines.add("- ${abstractText.take(360)}")
                        if (lines.isNotEmpty()) {
                            sections.add("### Risposta Diretta\n" + lines.joinToString("\n"))
                        }
                    }
                }
            }
        }

        // 4. Google News RSS — ONLY when user asks for recent news OR as last-resort fallback;
        // never include 250-char Google News redirect URLs that waste model context.
        val lowerQ = cleanQuery.lowercase()
        val wantsNews = listOf("notizie", "news", "ultime", "oggi", "novità", "novita", "aggiornamenti", "meteo", "partita", "risultato")
            .any { lowerQ.contains(it) }
        if (wantsNews || sections.isEmpty()) {
            runCatching {
                val req = Request.Builder()
                    .url("https://news.google.com/rss/search?q=$encoded&hl=it&gl=IT&ceid=IT:it")
                    .header("User-Agent", browserUa)
                    .header("Accept-Language", "it-IT,it;q=0.9,en;q=0.8")
                    .build()
                httpClient.newCall(req).execute().use { resp ->
                    if (resp.code == 200) {
                        val newsLines = WebSearchParser.parseGoogleNewsRss(resp.body?.string().orEmpty(), maxResults = 3)
                        if (newsLines.isNotEmpty()) {
                            sections.add("### Notizie Recenti\n" + newsLines.joinToString("\n"))
                        }
                    }
                }
            }
        }

        if (sections.isEmpty()) {
            "Nessun risultato web trovato per '$cleanQuery'. Verifica la connessione Internet del dispositivo."
        } else {
            buildString {
                appendLine("Risultati per \"$cleanQuery\":")
                append(sections.joinToString("\n\n"))
            }.take(1450).trimEnd()
        }
    }

    suspend fun fetchWebpage(url: String, maxChars: Int = 3500): String = withContext(Dispatchers.IO) {
        val rawUrl = url.trim().trim('<', '>', '"', '\'')
        if (rawUrl.isBlank()) return@withContext "URL non valido o vuoto."
        val clean = if (rawUrl.startsWith("http://", true) || rawUrl.startsWith("https://", true)) {
            rawUrl
        } else {
            "https://$rawUrl"
        }
        runCatching {
            val request = Request.Builder()
                .url(clean)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 15; Pixel 10 Pro XL) AppleWebKit/537.36 Chrome/134.0.0.0 Mobile Safari/537.36")
                .header("Accept", "text/html,application/xhtml+xml,text/plain,application/json;q=0.9,*/*;q=0.5")
                .header("Accept-Language", "it-IT,it;q=0.9,en;q=0.8")
                .build()
            httpClient.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return@use "Errore HTTP ${resp.code} su $clean"
                val contentType = resp.header("Content-Type").orEmpty().lowercase()
                val isReadableText = contentType.isBlank() ||
                    contentType.startsWith("text/") ||
                    contentType.contains("json") ||
                    contentType.contains("xml") ||
                    contentType.contains("xhtml")
                if (!isReadableText) {
                    return@use "Contenuto non testuale ($contentType) su $clean: impossibile estrarre testo."
                }
                // Cap download to 256 KB so multi-megabyte pages never spike device RAM
                val bodyBytes = resp.body?.source()?.use { source ->
                    source.request(262_144L)
                    val buf = source.buffer
                    val toRead = minOf(buf.size, 262_144L)
                    buf.readByteArray(toRead)
                } ?: ByteArray(0)
                val raw = bodyBytes.toString(Charsets.UTF_8)
                val cleaned = if (contentType.contains("json") || contentType.startsWith("text/plain")) {
                    raw.take(maxChars).trim()
                } else {
                    WebSearchParser.extractMainWebpageText(raw, maxChars)
                }
                if (cleaned.isBlank()) {
                    "Pagina scaricata ($clean), ma nessun testo leggibile estratto."
                } else {
                    "Contenuto estratto da $clean:\n$cleaned"
                }
            }
        }.getOrElse { e ->
            "Impossibile scaricare $clean: ${e.message}"
        }
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
