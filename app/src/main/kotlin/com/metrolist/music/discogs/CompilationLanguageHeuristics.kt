package com.metrolist.music.discogs

import java.text.Normalizer

internal object CompilationLanguageHeuristics {
    fun matchesOrUnknown(language: String?, tracks: List<DiscogsTrack>): Boolean {
        val requested = language?.trim()?.takeIf { it.isNotBlank() && !it.equals("Tutte", true) }
            ?: return true
        val detected = detect(tracks) ?: return true
        return detected.equals(requested, ignoreCase = true)
    }

    internal fun detect(tracks: List<DiscogsTrack>): String? {
        val text = tracks.joinToString(" ") { it.title }.lowercase()
        if (text.isBlank()) return null

        val normalized = Normalizer.normalize(text, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
        val words = normalized
            .replace(Regex("[^a-z0-9àèéìòùáéíóúñçäöüß]+"), " ")
            .split(" ")
            .filter { it.length > 1 }

        val scores = linkedMapOf(
            "Italiano" to score(words, setOf("amore", "cuore", "vita", "notte", "giorno", "mondo", "cielo", "mare", "io", "tu", "noi", "sei", "sono", "con", "senza", "per")),
            "Inglese" to score(words, setOf("love", "you", "me", "night", "day", "heart", "world", "baby", "girl", "boy", "the", "and", "with", "without", "for")),
            "Francese" to score(words, setOf("amour", "toi", "moi", "nuit", "jour", "coeur", "monde", "avec", "sans", "pour", "une", "les", "des")),
            "Spagnolo" to score(words, setOf("amor", "corazon", "noche", "dia", "mundo", "cielo", "vida", "con", "sin", "para", "una", "los", "las")),
            "Tedesco" to score(words, setOf("liebe", "nacht", "tag", "herz", "welt", "leben", "mit", "ohne", "für", "ich", "du", "und", "der", "die")),
            "Portoghese" to score(words, setOf("amor", "coração", "noite", "dia", "mundo", "vida", "com", "sem", "para", "uma", "não", "meu")),
        )

        val best = scores.maxByOrNull { it.value } ?: return null
        val second = scores.values.sortedDescending().getOrNull(1) ?: 0
        return best.key.takeIf { best.value >= 3 && best.value >= second + 1 }
    }

    private fun score(words: List<String>, markers: Set<String>): Int =
        words.count { it in markers }
}
