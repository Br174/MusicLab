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

    private val CONTINUATION_WORDS = setOf(
        "che", "chi", "cui", "quando", "dove", "come", "perche",
        "that", "which", "who", "whom", "whose", "when", "where", "because",
        "que", "quien", "cuando", "donde", "como", "porque",
        "qui", "quand", "ou", "parce",
    )
}
