package com.metrolist.music.ui.component

import java.text.Normalizer

/**
 * Converts structured external sources into positive/neutral evidence for the
 * AI-first cover pipeline. External sources never become vetoes: NO_MATCH and
 * NETWORK_ERROR are deliberately neutral, while positive same-work findings
 * can strengthen candidates that the AI has already decided to keep.
 */
internal data class AiCoverSourceEvidence(
    val status: MusicBrainzStatus,
    val hints: List<MusicBrainzCover>,
    val promptContext: String,
) {
    fun attachToAiAccepted(candidates: List<AiCoverCandidate>): List<AiCoverCandidate> {
        if (hints.isEmpty() || candidates.isEmpty()) return candidates

        return candidates.map { candidate ->
            val matchingHint = hints.firstOrNull { hint ->
                sameText(hint.title, candidate.title) && sameText(hint.artist, candidate.artist)
            } ?: return@map candidate

            val signal = AiBrainSignal(
                kind = "musicbrainz_work_match",
                strength = "very_strong",
                direction = "positive",
            )
            candidate.copy(
                sameWorkScore = maxOf(candidate.sameWorkScore ?: 0, MUSICBRAINZ_WORK_SCORE),
                brainSignals = (candidate.brainSignals + signal).distinctBy {
                    "${it.kind}|${it.strength}|${it.direction}"
                },
                brainAdmission = candidate.brainAdmission ?: "musicbrainz_evidence:${matchingHint.workId}",
            )
        }
    }

    companion object {
        fun fromMusicBrainz(lookup: MusicBrainzLookup): AiCoverSourceEvidence {
            val hints = if (lookup.status == MusicBrainzStatus.OK) {
                lookup.covers
                    .distinctBy { "${canonical(it.title)}|${canonical(it.artist)}|${it.workId}" }
                    .take(MAX_PROMPT_HINTS)
            } else {
                emptyList()
            }

            val promptContext = if (hints.isEmpty()) {
                ""
            } else {
                buildString {
                    appendLine("MusicBrainz ha fornito questi INDIZI strutturati di registrazioni collegate alla stessa opera:")
                    hints.forEach { hint ->
                        append("- ")
                        append(hint.artist)
                        append(" — ")
                        append(hint.title)
                        hint.year?.let { append(" ($it)") }
                        append(" [work=${hint.workId}]")
                        appendLine()
                    }
                    append(
                        "Usali come evidenze positive, NON come verdetto obbligatorio. " +
                            "L'assenza di un nome in MusicBrainz non deve eliminare un candidato: " +
                            "la decisione editoriale finale resta all'AI.",
                    )
                }.trim()
            }

            return AiCoverSourceEvidence(
                status = lookup.status,
                hints = hints,
                promptContext = promptContext,
            )
        }

        private const val MUSICBRAINZ_WORK_SCORE = 94
        private const val MAX_PROMPT_HINTS = 24
    }
}

private fun sameText(a: String, b: String): Boolean {
    val left = canonical(a)
    val right = canonical(b)
    return left.isNotBlank() && left == right
}

private fun canonical(value: String): String =
    Normalizer.normalize(value, Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
        .lowercase()
        .replace(Regex("[^a-z0-9]+"), " ")
        .trim()
        .replace(Regex("\\s+"), " ")
