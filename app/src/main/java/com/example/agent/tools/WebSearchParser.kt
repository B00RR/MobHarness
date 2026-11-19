package com.example.agent.tools

import java.net.URLDecoder

data class WebSearchHit(
    val title: String,
    val snippet: String,
    val url: String = ""
)

/**
 * Pure-Kotlin parsing and normalization utilities for web search and webpage extraction.
 * Kept Android-free so every parser, entity extractor, and HTML decoder is directly JVM-testable.
 */
object WebSearchParser {

    private val QUERY_NOISE_WORDS = setOf(
        "popolazione", "abitanti", "quanti", "quante", "quanto", "qual", "quale", "cosa",
        "chi", "dove", "quando", "come", "perche", "perché", "storia", "superficie",
        "sindaco", "capitale", "altezza", "meteo", "notizie", "ultime", "oggi", "attuale",
        "attuali", "numero", "comune", "citta", "città", "provincia", "regione", "italia",
        "population", "inhabitants", "what", "who", "where", "when", "how", "many",
        "del", "della", "dello", "dei", "degli", "delle", "di", "da", "in", "su", "per",
        "con", "tra", "fra", "nel", "nella", "nello", "nei", "negli", "nelle",
        "al", "alla", "allo", "ai", "agli", "alle", "sul", "sulla", "sullo",
        "ha", "hanno", "sono", "è", "e", "ed", "o", "il", "lo", "la", "le", "gli", "i",
        "un", "uno", "una", "suo", "sua", "cerca", "ricerca", "internet", "web", "wikipedia",
        "novita", "novità", "info", "informazioni", "dettagli", "significato"
    )

    private val ITALIAN_PROVINCE_CODES = setOf(
        "AG", "AL", "AN", "AO", "AR", "AP", "AT", "AV", "BA", "BT", "BL", "BN", "BG", "BI",
        "BO", "BZ", "BS", "BR", "CA", "CL", "CB", "CE", "CT", "CZ", "CH", "CO", "CS", "CR",
        "KR", "CN", "EN", "FM", "FE", "FI", "FG", "FC", "FR", "GE", "GO", "GR", "IM", "IS",
        "SP", "AQ", "LT", "LE", "LC", "LI", "LO", "LU", "MC", "MN", "MS", "MT", "ME", "MI",
        "MO", "MB", "NA", "NO", "NU", "OR", "PD", "PA", "PR", "PV", "PG", "PU", "PE", "PC",
        "PI", "PT", "PN", "PZ", "PO", "RG", "RA", "RC", "RE", "RI", "RN", "RM", "RO", "SA",
        "SS", "SV", "SI", "SR", "SO", "SU", "TA", "TE", "TR", "TO", "TP", "TN", "TV", "TS",
        "UD", "VA", "VE", "VB", "VC", "VR", "VV", "VI", "VT"
    )

    private val ELIDED_ARTICLE_REGEX = Regex(
        """^(?:dell|dall|nell|sull|all|un|l|d)['’](.+)$""",
        RegexOption.IGNORE_CASE
    )

    private val NAMED_HTML_ENTITIES = mapOf(
        "nbsp" to " ",
        "amp" to "&",
        "quot" to "\"",
        "apos" to "'",
        "lt" to "<",
        "gt" to ">",
        "rsquo" to "'",
        "lsquo" to "'",
        "ldquo" to "\"",
        "rdquo" to "\"",
        "laquo" to "«",
        "raquo" to "»",
        "ndash" to "–",
        "mdash" to "—",
        "hellip" to "…",
        "euro" to "€",
        "deg" to "°",
        "middot" to "·",
        "bull" to "•",
        "agrave" to "à", "Agrave" to "À",
        "egrave" to "è", "Egrave" to "È",
        "eacute" to "é", "Eacute" to "É",
        "igrave" to "ì", "Igrave" to "Ì",
        "ograve" to "ò", "Ograve" to "Ò",
        "ugrave" to "ù", "Ugrave" to "Ù"
    )

    private val NUMERIC_HEX_ENTITY = Regex("""&#x([0-9a-fA-F]{1,6});""")
    private val NUMERIC_DEC_ENTITY = Regex("""&#(\d{1,7});""")
    private val NAMED_ENTITY = Regex("""&([a-zA-Z]{2,12});""")

    private val SCRIPT_STYLE_REGEX = Regex(
        """<(script|style|noscript|svg|template)\b[^>]*>.*?</\1>""",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
    )
    private val CHROME_BLOCK_REGEX = Regex(
        """<(nav|footer|aside|header)\b[^>]*>.*?</\1>""",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
    )
    private val TITLE_TAG_REGEX = Regex("""<title[^>]*>(.*?)</title>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
    private val ARTICLE_TAG_REGEX = Regex("""<article\b[^>]*>(.*?)</article>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
    private val MAIN_TAG_REGEX = Regex("""<main\b[^>]*>(.*?)</main>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))

    /**
     * Isolates the core search entity from an Italian/English natural-language query for Wikipedia lookup.
     * Examples:
     *  - "popolazione Cardito (NA)" -> "Cardito"
     *  - "quanti abitanti ha l'Aquila?" -> "Aquila"
     *  - "storia dell'Italia" -> "Italia"
     *  - "novità su F1 e IA" -> "F1 IA"
     */
    fun extractCoreEntity(raw: String): String {
        val withoutParensProv = raw
            .replace(Regex("""\(\s*[A-Za-z]{2}\s*\)"""), " ")
            .replace(Regex("""[^\p{L}\p{N}\s'’-]"""), " ")

        val rawTokens = withoutParensProv.split(Regex("\\s+")).filter { it.isNotBlank() }
        val processed = mutableListOf<String>()

        for (tok in rawTokens) {
            val elided = ELIDED_ARTICLE_REGEX.matchEntire(tok)
            val candidate = (elided?.groupValues?.getOrNull(1) ?: tok).trim('\'', '’', '-')
            if (candidate.length < 2) continue
            val low = candidate.lowercase()
            if (low in QUERY_NOISE_WORDS) continue
            processed.add(candidate)
        }

        // Only drop 2-letter Italian province codes (NA, RM, MI...) if another entity token exists,
        // so non-province short acronyms like "IA", "F1", "5G", "UK", "US" are always preserved.
        val filtered = if (processed.size > 1) {
            processed.filterNot { tok ->
                tok.length == 2 && tok == tok.uppercase() && tok in ITALIAN_PROVINCE_CODES
            }
        } else {
            processed
        }

        return (if (filtered.isNotEmpty()) filtered else processed).joinToString(" ").trim()
    }

    fun isWikipediaDisambiguation(title: String, extract: String): Boolean {
        val lowTitle = title.lowercase()
        val lowExtract = extract.lowercase()
        return lowTitle.contains("(disambigua)") ||
            lowTitle.contains("(disambiguation)") ||
            lowExtract.contains("può riferirsi a:") ||
            lowExtract.contains("puo riferirsi a:") ||
            lowExtract.contains("may refer to:") ||
            lowExtract.contains("can refer to:")
    }

    fun decodeHtmlEntities(raw: String): String {
        if (!raw.contains('&')) return raw
        var out = NUMERIC_HEX_ENTITY.replace(raw) { m ->
            val code = m.groupValues[1].toIntOrNull(16)
            if (code != null && code in 9..0x10FFFF) {
                runCatching { String(Character.toChars(code)) }.getOrDefault(" ")
            } else " "
        }
        out = NUMERIC_DEC_ENTITY.replace(out) { m ->
            val code = m.groupValues[1].toIntOrNull()
            if (code != null && code in 9..0x10FFFF) {
                runCatching { String(Character.toChars(code)) }.getOrDefault(" ")
            } else " "
        }
        out = NAMED_ENTITY.replace(out) { m ->
            NAMED_HTML_ENTITIES[m.groupValues[1]] ?: m.value
        }
        return out
    }

    fun stripHtml(html: String): String {
        if (html.isBlank()) return ""
        val noScripts = SCRIPT_STYLE_REGEX.replace(html, " ")
        val noTags = noScripts.replace(Regex("<[^>]+>"), " ")
        return decodeHtmlEntities(noTags)
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    fun extractMainWebpageText(html: String, maxChars: Int = 3500): String {
        if (html.isBlank()) return ""
        val pageTitle = TITLE_TAG_REGEX.find(html)?.groupValues?.getOrNull(1)
            ?.let { stripHtml(it) }
            .orEmpty()

        val cleanedDom = SCRIPT_STYLE_REGEX.replace(html, " ")
        val preferredRegion = ARTICLE_TAG_REGEX.find(cleanedDom)?.groupValues?.getOrNull(1)
            ?.takeIf { stripHtml(it).length >= 160 }
            ?: MAIN_TAG_REGEX.find(cleanedDom)?.groupValues?.getOrNull(1)
                ?.takeIf { stripHtml(it).length >= 160 }
            ?: CHROME_BLOCK_REGEX.replace(cleanedDom, " ")

        val bodyText = stripHtml(preferredRegion)
        val combined = if (pageTitle.isNotBlank() && !bodyText.startsWith(pageTitle, ignoreCase = true)) {
            "$pageTitle\n\n$bodyText"
        } else {
            bodyText
        }
        return combined.take(maxChars).trim()
    }

    fun parseDuckDuckGoHtml(html: String, maxResults: Int = 4): List<WebSearchHit> {
        if (html.isBlank()) return emptyList()
        val hits = mutableListOf<WebSearchHit>()

        // Split by result cards so we can skip sponsored ad cards (`result--ad`) reliably
        val blockRegex = Regex(
            """<div[^>]*class=['"][^'"]*\bresult\b[^'"]*['"][^>]*>(.*?)(?=<div[^>]*class=['"][^'"]*\bresult\b|\z)""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
        )
        val linkRegex = Regex(
            """<a[^>]*class=['"][^'"]*result__a[^'"]*['"][^>]*href=['"]([^'"]*)['"][^>]*>(.*?)</a>|<a[^>]*href=['"]([^'"]*)['"][^>]*class=['"][^'"]*result__a[^'"]*['"][^>]*>(.*?)</a>""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
        )
        val snippetRegex = Regex(
            """class=['"][^'"]*result__snippet[^'"]*['"][^>]*>(.*?)</(?:a|div|span|td)>""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
        )

        val blocks = blockRegex.findAll(html).toList()
        for (block in blocks) {
            val fullTag = block.value
            if (fullTag.contains("result--ad", ignoreCase = true) ||
                fullTag.contains("badge--ad", ignoreCase = true) ||
                fullTag.contains("duckduckgo.com/y.js", ignoreCase = true)
            ) {
                continue
            }
            val linkMatch = linkRegex.find(block.groupValues[1]) ?: continue
            val rawHref = linkMatch.groupValues[1].ifBlank { linkMatch.groupValues[3] }
            val rawTitle = linkMatch.groupValues[2].ifBlank { linkMatch.groupValues[4] }
            val title = stripHtml(rawTitle)
            val snippet = snippetRegex.find(block.groupValues[1])?.groupValues?.getOrNull(1)
                ?.let { stripHtml(it) }
                .orEmpty()
            val cleanUrl = unwrapDuckDuckGoRedirect(rawHref)
            if (title.isNotBlank() || snippet.isNotBlank()) {
                hits.add(WebSearchHit(title = title, snippet = snippet.take(210), url = cleanUrl))
                if (hits.size >= maxResults) break
            }
        }

        // Fallback if markup structure changed slightly and block split missed results
        if (hits.isEmpty()) {
            val simpleTitleRegex = Regex("""class=['"][^'"]*result__a[^'"]*['"][^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)
            val titles = simpleTitleRegex.findAll(html).map { stripHtml(it.groupValues[1]) }.take(maxResults).toList()
            val snippets = snippetRegex.findAll(html).map { stripHtml(it.groupValues[1]) }.take(maxResults).toList()
            titles.forEachIndexed { idx, t ->
                val s = snippets.getOrNull(idx).orEmpty()
                if (t.isNotBlank() || s.isNotBlank()) {
                    hits.add(WebSearchHit(title = t, snippet = s.take(210)))
                }
            }
        }
        return hits
    }

    fun parseDuckDuckGoLite(html: String, maxResults: Int = 4): List<WebSearchHit> {
        if (html.isBlank()) return emptyList()
        val linkRegex = Regex(
            """<a[^>]*href=['"]([^'"]*)['"][^>]*class=['"][^'"]*result-link[^'"]*['"][^>]*>(.*?)</a>|<a[^>]*class=['"][^'"]*result-link[^'"]*['"][^>]*href=['"]([^'"]*)['"][^>]*>(.*?)</a>""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
        )
        val snippetRegex = Regex(
            """<td[^>]*class=['"][^'"]*result-snippet[^'"]*['"][^>]*>(.*?)</td>""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
        )
        val links = linkRegex.findAll(html).take(maxResults + 2).toList()
        val snippets = snippetRegex.findAll(html).map { stripHtml(it.groupValues[1]) }.take(maxResults + 2).toList()
        val hits = mutableListOf<WebSearchHit>()
        links.forEachIndexed { idx, m ->
            val rawHref = m.groupValues[1].ifBlank { m.groupValues[3] }
            if (rawHref.contains("duckduckgo.com/y.js", ignoreCase = true)) return@forEachIndexed
            val title = stripHtml(m.groupValues[2].ifBlank { m.groupValues[4] })
            val snippet = snippets.getOrNull(idx).orEmpty()
            val cleanUrl = unwrapDuckDuckGoRedirect(rawHref)
            if (title.isNotBlank() || snippet.isNotBlank()) {
                hits.add(WebSearchHit(title = title, snippet = snippet.take(210), url = cleanUrl))
            }
        }
        return hits.take(maxResults)
    }

    fun parseGoogleNewsRss(xml: String, maxResults: Int = 3): List<String> {
        if (xml.isBlank()) return emptyList()
        val itemRegex = Regex("<item>(.*?)</item>", RegexOption.DOT_MATCHES_ALL)
        val titleRegex = Regex("<title>(.*?)</title>", RegexOption.DOT_MATCHES_ALL)
        val pubDateRegex = Regex("<pubDate>(.*?)</pubDate>", RegexOption.DOT_MATCHES_ALL)
        return itemRegex.findAll(xml).take(maxResults).mapNotNull { m ->
            val block = m.groupValues[1]
            val title = stripHtml(
                titleRegex.find(block)?.groupValues?.get(1).orEmpty()
                    .removePrefix("<![CDATA[").removeSuffix("]]>")
            )
            val date = stripHtml(pubDateRegex.find(block)?.groupValues?.get(1).orEmpty())
                .substringBeforeLast(" GMT").take(17)
            if (title.isBlank()) null
            else "- $title" + if (date.isNotBlank()) " [$date]" else ""
        }.toList()
    }

    fun unwrapDuckDuckGoRedirect(rawUrl: String): String {
        val decodedHref = decodeHtmlEntities(rawUrl.trim())
        val uddgMatch = Regex("""[?&]uddg=([^&]+)""").find(decodedHref)
        val candidate = if (uddgMatch != null) {
            runCatching { URLDecoder.decode(uddgMatch.groupValues[1], "UTF-8") }.getOrDefault(decodedHref)
        } else {
            decodedHref
        }
        return when {
            candidate.startsWith("http://") || candidate.startsWith("https://") -> candidate
            candidate.startsWith("//") -> "https:$candidate"
            else -> ""
        }
    }
}
