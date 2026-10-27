package com.metrolist.music.ui.component

/**
 * Only used from round FOUR onwards.
 * Requires the complete, independent requested song title. Named technical
 * decorations may accompany it, but unrelated lyrical title continuations
 * (e.g. "Felicità puttana") must never turn into a match.
 *
 * This is a broader positive title match, NOT a new filter applied to
 * rounds 1-3. All previous safe TitleMeaningResolver matches stay accepted.
 */
internal object CoverLab76BroadTitle {
    private val qualifiers = setOf(
        "cover", "version", "versione", "live", "concert", "concerto", "festival",
        "stadio", "stadium", "band", "coverband", "tribute", "guitar",
        "chitarra", "piano", "keyboard", "strumental", "instrumental",
        "acoustic", "acustico", "acustica", "unplugged", "orchestra",
        "orchestral", "choir", "coro", "sax", "saxophone", "violin",
        "drums", "bass", "singer", "singers", "vocal", "vocals", "solo",
        "studio", "television", "tv", "talent", "show", "the", "voice",
        "xfactor", "sanremo", "eurovision", "amici", "rai", "bbc",
        "remix", "mix", "rework", "radio", "session", "performance",
        "official", "audio", "music", "video", "hd", "hq", "ft", "feat",
        "duet", "duetto", "with", "featuring", "dal", "vivo",
        "contest", "audition", "final", "finale", "semifinale",
        "semifinal", "international", "foreign", "italian", "english",
        "italiano", "inglese", "translation", "adaptation"
    )

    private fun qualifierOnly(fragment: String): Boolean {
        val tokens = fragment.split(" ").filter(String::isNotBlank)
        return tokens.isNotEmpty() && tokens.all {
            it in qualifiers || it.matches(Regex("""(?:18|19|20)\d\d"""))
        }
    }

    internal fun matches(
        targetTitle: String, title: String, artistAliases: Set<String> = emptySet()
    ): Boolean {
        if (TitleMeaningResolver.matchesBaseTitle(targetTitle, title, artistAliases)) return true
        val target = TitleMeaningResolver.canonical(targetTitle)
        var text = TitleMeaningResolver.canonical(title)
        if (target.isBlank() || text.isBlank()) return false
        // A full performer credit before/after the *entire* song title is
        // metadata, never a lyrical extension of that title.
        for (alias in artistAliases) {
            val name = TitleMeaningResolver.canonical(alias)
            if (name.isBlank() || name == target) continue
            if (text.startsWith("$name ")) text = text.removePrefix("$name ").trim()
            if (text.endsWith(" $name")) text = text.removeSuffix(" $name").trim()
        }
        if (text == target) return true
        if (text.startsWith("$target ") &&
            qualifierOnly(text.removePrefix("$target "))) return true
        if (text.endsWith(" $target") &&
            qualifierOnly(text.removeSuffix(" $target"))) return true
        return false
    }
}
