package com.metrolist.music.ui.component

import java.text.Normalizer

internal enum class TitleMeaningMatch {
    EXACT,
    DECORATED,
    DIFFERENT,
}

/**
 * Fast title-boundary resolver used before playback localization.
 *
 * The canonical title must remain an autonomous phrase. Case, accents and
 * punctuation are ignored. Extra metadata/artist text and separated aliases
 * are accepted, while lexical continuations such as "Il mondo che vorrei"
 * are rejected for the base title "Il mondo".
 */
internal object TitleMeaningResolver {
    fun matchesBaseTitle(
        targetTitle: String,
        value: String,
        artistAliases: Set<String> = emptySet(),
    ): Boolean = classify(targetTitle, value, artistAliases) != TitleMeaningMatch.DIFFERENT

    /**
     * Extracts a conservative trailing performer hint such as
     * "Balla balla ballerino (Lucio Dalla)". Technical/version labels are ignored.
     */
    fun trailingArtistHint(value: String): String? {
        val match = TRAILING_PARENTHESIS.find(value.trim()) ?: return null
        val raw = match.groupValues[1].trim()
        val normalized = canonical(raw)
        if (normalized.isBlank()) return null
        val tokens = normalized.split(' ').filter(String::isNotBlank)
        if (tokens.size !in 1..6) return null
        if (tokens.any { it in ARTIST_HINT_BLOCKLIST || it.matches(Regex("(?:18|19|20)\\d{2}")) }) return null
        if (raw.none(Char::isLetter)) return null
        return raw
    }

    fun stripTrailingArtistHint(value: String): String =
        if (trailingArtistHint(value) != null) {
            value.replace(TRAILING_PARENTHESIS, "").trim()
        } else {
            value.trim()
        }
    /**
     * Returns a conservative work-title anchor for fallback discovery only.
     * The original user title must remain available for variant searches.
     */
    fun workAnchorTitle(value: String): String {
        var result = stripTrailingArtistHint(value).trim()
        if (result.isBlank()) return result

        // Remove only trailing bracketed/version segments that are clearly technical.
        var changed = true
        while (changed) {
            changed = false
            val match = TRAILING_PARENTHESIS.find(result)
            if (match != null && isTechnicalSegment(match.groupValues[1])) {
                result = result.removeRange(match.range).trim()
                changed = true
            }
        }

        val separator = TRAILING_TECHNICAL_SUFFIX.find(result)
        if (separator != null && isTechnicalSegment(separator.groupValues[1])) {
            result = result.substring(0, separator.range.first).trim()
        }
        result = stripTrailingArtistHint(result)

        // Some YouTube/provider metadata arrives as "Performer - Song" inside the
        // title field while the separate artist/channel field points elsewhere.
        // Keep the original decorated title for the first search lane, but expose
        // a conservative work anchor for the fallback lane. Requiring a multi-word
        // leading credit avoids rewriting ordinary one-word hyphenated song titles.
        LEADING_ARTIST_CREDIT.matchEntire(result)?.let { match ->
            val possibleArtist = match.groupValues[1].trim()
            val possibleTitle = match.groupValues[2].trim()
            if (looksLikeLeadingArtistCredit(possibleArtist, possibleTitle)) {
                result = possibleTitle
            }
        }

        return result.ifBlank { stripTrailingArtistHint(value).trim() }
    }

    fun versionDescriptors(value: String): Set<String> {
        val normalized = canonical(value)
        return VERSION_DESCRIPTOR_GROUPS.mapNotNullTo(linkedSetOf()) { (name, tokens) ->
            name.takeIf { tokens.any { token ->
                normalized == token || normalized.startsWith("$token ") ||
                    normalized.endsWith(" $token") || normalized.contains(" $token ")
            } }
        }
    }

    fun sameArtist(left: String, right: String): Boolean {
        val a = canonical(left).removePrefix("the ")
        val b = canonical(right).removePrefix("the ")
        if (a.isBlank() || b.isBlank()) return false
        return a == b ||
            a.startsWith("$b ") ||
            a.endsWith(" $b") ||
            b.startsWith("$a ") ||
            b.endsWith(" $a")
    }

    /**
     * Lightweight relevance score used only for ordering search results.
     * It never filters: exact/decorated matches stay on top, lexical similarities
     * remain available below them for the normal endless-search experience.
     */
    fun qualityScore(
        targetTitle: String,
        value: String,
        artistAliases: Set<String> = emptySet(),
    ): Int {
        return when (classify(targetTitle, value, artistAliases)) {
            TitleMeaningMatch.EXACT -> 10
            TitleMeaningMatch.DECORATED -> 9
            TitleMeaningMatch.DIFFERENT -> {
                val target = canonical(targetTitle)
                val candidate = canonical(value)
                if (target.isBlank() || candidate.isBlank()) return 0

                val targetTokens = target.split(' ').filter(String::isNotBlank).toSet()
                val candidateTokens = candidate.split(' ').filter(String::isNotBlank).toSet()
                if (targetTokens.isEmpty() || candidateTokens.isEmpty()) return 0

                val overlap = targetTokens.count { it in candidateTokens }
                val ratio = overlap.toDouble() / targetTokens.size.toDouble()
                when {
                    candidate.startsWith("$target ") || candidate.endsWith(" $target") -> 6
                    target.startsWith("$candidate ") || target.endsWith(" $candidate") -> 5
                    ratio >= 1.0 -> 5
                    ratio >= 0.75 -> 4
                    ratio >= 0.50 -> 3
                    ratio > 0.0 -> 1
                    else -> 0
                }
            }
        }
    }

    fun classify(
        targetTitle: String,
        value: String,
        artistAliases: Set<String> = emptySet(),
    ): TitleMeaningMatch {
        val target = canonical(targetTitle)
        val candidate = canonical(value)
        if (target.isBlank() || candidate.isBlank()) return TitleMeaningMatch.DIFFERENT
        if (candidate == target) return TitleMeaningMatch.EXACT

        val aliases = artistAliases.map(::canonical).filter(String::isNotBlank).toSet()
        val withoutArtist = stripEdgeAliases(candidate, aliases)
        if (withoutArtist == target) return TitleMeaningMatch.DECORATED

        if (hasAllowedInlineContext(target, withoutArtist, aliases)) {
            return TitleMeaningMatch.DECORATED
        }

        val segments = semanticSegments(value)
        if (segments.isNotEmpty()) {
            val targetPositions = segments.indices.filter { canonical(segments[it]) == target }
            if (targetPositions.isNotEmpty()) {
                val unsafeContinuation = segments.withIndex().any { (index, segment) ->
                    index !in targetPositions && looksLikeLexicalContinuation(segment)
                }
                if (!unsafeContinuation) return TitleMeaningMatch.DECORATED
            }
        }

        return TitleMeaningMatch.DIFFERENT
    }

    internal fun canonical(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .lowercase()
            .replace(Regex("[’'´]"), "")
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()
            .replace(Regex("\\s+"), " ")

    private fun semanticSegments(value: String): List<String> =
        value
            .replace(Regex("[\\(\\[\\{]"), " | ")
            .replace(Regex("[\\)\\]\\}]"), " | ")
            .replace(Regex("\\s*[-–—:|·]\\s*"), " | ")
            .split('|')
            .map(String::trim)
            .filter(String::isNotBlank)

    private fun stripEdgeAliases(value: String, aliases: Set<String>): String {
        var result = value
        var changed = true
        while (changed) {
            changed = false
            aliases.forEach { alias ->
                when {
                    result.startsWith("$alias ") -> {
                        result = result.removePrefix("$alias ").trim()
                        changed = true
                    }
                    result.endsWith(" $alias") -> {
                        result = result.removeSuffix(" $alias").trim()
                        changed = true
                    }
                }
            }
        }
        return result
    }

    private fun hasAllowedInlineContext(
        target: String,
        candidate: String,
        aliases: Set<String>,
    ): Boolean {
        if (candidate.startsWith("$target ")) {
            val residual = candidate.removePrefix("$target ").trim()
            return allowedResidual(residual, aliases)
        }
        if (candidate.endsWith(" $target")) {
            val residual = candidate.removeSuffix(" $target").trim()
            return allowedResidual(residual, aliases)
        }
        return false
    }

    private fun allowedResidual(residual: String, aliases: Set<String>): Boolean {
        if (residual.isBlank()) return true
        if (aliases.any { residual == it }) return true
        val tokens = residual.split(' ').filter(String::isNotBlank)
        return tokens.isNotEmpty() && tokens.all { token ->
            token.matches(Regex("(?:18|19|20)\\d{2}")) ||
                token in TECHNICAL_TOKENS ||
                aliases.any { alias -> token in alias.split(' ') }
        }
    }

    private fun looksLikeLeadingArtistCredit(
        possibleArtist: String,
        possibleTitle: String,
    ): Boolean {
        val artist = canonical(possibleArtist)
        val title = canonical(possibleTitle)
        if (artist.isBlank() || title.isBlank()) return false

        val artistTokens = artist.split(' ').filter(String::isNotBlank)
        val titleTokens = title.split(' ').filter(String::isNotBlank)
        if (artistTokens.size !in 2..6 || titleTokens.size !in 1..12) return false
        if (artistTokens.any { it in TECHNICAL_TOKENS || it.matches(Regex("(?:18|19|20)\\d{2}")) }) return false
        if (titleTokens.all { it in TECHNICAL_TOKENS }) return false
        return possibleArtist.any(Char::isLetter) && possibleTitle.any(Char::isLetter)
    }

    private fun isTechnicalSegment(value: String): Boolean {
        val normalized = canonical(value)
        if (normalized.isBlank()) return false
        val tokens = normalized.split(' ').filter(String::isNotBlank)
        return tokens.isNotEmpty() && tokens.all { token ->
            token in TECHNICAL_TOKENS || token.matches(Regex("(?:18|19|20)\\d{2}"))
        }
    }

    private fun looksLikeLexicalContinuation(value: String): Boolean {
        val normalized = canonical(value)
        if (normalized.isBlank()) return false
        val first = normalized.substringBefore(' ')
        return first in CONTINUATION_WORDS
    }

    private val TECHNICAL_TOKENS = setOf(
        "official", "music", "video", "audio", "lyrics", "lyric", "visualizer",
        "remaster", "remastered", "version", "versione", "cover", "live", "dal",
        "vivo", "concert", "concerto", "performance", "session", "festival",
        "remix", "mix", "rework", "radio", "edit", "extended", "club", "acoustic",
        "unplugged", "studio", "mono", "stereo", "hd", "hq", "4k", "feat", "ft",
        "featuring", "duet", "duetto",
    )

    private val LEADING_ARTIST_CREDIT = Regex("""^\s*(.{2,80}?)\s+[-–—]\s+(.{1,180})\s*$""")
    private val TRAILING_PARENTHESIS = Regex("""\s*\(([^()]*)\)\s*$""")
    private val TRAILING_TECHNICAL_SUFFIX = Regex("""\s*[-–—:|·]\s*([^\n]+)$""")
    private val VERSION_DESCRIPTOR_GROUPS = listOf(
        "live" to setOf("live", "dal vivo", "concert", "concerto", "performance", "session", "festival"),
        "remix" to setOf("remix", "mix", "rework", "club mix", "extended mix", "radio mix", "edit"),
        "acoustic" to setOf("acoustic", "unplugged", "acustico", "acustica"),
        "remaster" to setOf("remaster", "remastered", "rimasterizzato", "rimasterizzata"),
        "duet" to setOf("duet", "duetto", "with", "feat", "featuring"),
    )
    private val ARTIST_HINT_BLOCKLIST = setOf(
        "live", "remix", "mix", "version", "versione", "cover", "official", "audio",
        "video", "lyrics", "lyric", "remaster", "remastered", "acoustic", "unplugged",
        "radio", "edit", "extended", "mono", "stereo", "instrumental", "karaoke",
    )

    private val CONTINUATION_WORDS = setOf(
        "che", "chi", "cui", "quando", "dove", "come", "perche",
        "that", "which", "who", "whom", "whose", "when", "where", "because",
        "que", "quien", "cuando", "donde", "como", "porque",
        "qui", "quand", "ou", "parce",
    )
}
