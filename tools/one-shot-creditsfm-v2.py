from pathlib import Path

source_path = Path('app/src/main/kotlin/com/metrolist/music/ui/component/CreditsFmCoverSource.kt')
engine_path = Path('app/src/main/kotlin/com/metrolist/music/ui/component/CoverHubSearchEngine.kt')
screen_path = Path('app/src/main/kotlin/com/metrolist/music/ui/component/CoverSearchScreen.kt')


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if old not in text:
        raise SystemExit(f'Patch anchor missing: {label}')
    return text.replace(old, new, 1)


source = source_path.read_text(encoding='utf-8')
source = replace_once(
    source,
    '''internal data class CreditsFmLookup(
    val covers: List<CreditsFmCover>,
    val status: CreditsFmStatus,
    val sourceUrl: String? = null,
)''',
    '''internal data class CreditsFmLookup(
    val covers: List<CreditsFmCover>,
    val status: CreditsFmStatus,
    val sourceUrl: String? = null,
    val sourceCandidates: Int = 0,
    val worksFound: Int = 0,
    val linkedRecordings: Int = 0,
)''',
    'Credits.fm diagnostics model',
)
source = replace_once(
    source,
    '''        val query = listOf(title, artist).filter { it.isNotBlank() }.joinToString(" ")
        val encoded = URLEncoder.encode(query, "UTF-8")
        val searchUrl = "$BASE_URL/search?q=$encoded&type=isrc&match=recording_title&exclude_lyrics=true&limit=100&nocache=1"''',
    '''        // `match=recording_title` searches title/song fields only. Keep the artist
        // out of q, otherwise exact-title recordings by other performers (covers) can
        // disappear before we ever reach their shared ISWC.
        val encoded = URLEncoder.encode(title, "UTF-8")
        val searchUrl = "$BASE_URL/search?q=$encoded&type=isrc&match=recording_title&exclude_lyrics=true&limit=100&nocache=1"''',
    'title-only Credits.fm search',
)
source = replace_once(
    source,
    '''        val related = linkedMapOf<String, RecordingRef>()
        var lastUrl = searchUrl
        var terminalStatus: CreditsFmStatus? = null''',
    '''        val related = linkedMapOf<String, RecordingRef>()
        val worksSeen = linkedSetOf<String>()
        val sourceCandidateCount = candidates.size
        var lastUrl = searchUrl
        var terminalStatus: CreditsFmStatus? = null''',
    'Credits.fm diagnostics counters',
)
source = replace_once(
    source,
    '''            for (iswc in works) {
                val workUrl = "$BASE_URL/iswc/${URLEncoder.encode(iswc, "UTF-8")}?include=recordings&limit=100&contribute=false"
                lastUrl = workUrl
                when (val response = fetchJson(workUrl)) {
                    is FetchJson.Error -> terminalStatus = response.status
                    is FetchJson.Ok -> parseJson(response.body)?.let { root ->
                        collectRecordingRefs(root)
                            .filter { it.isrc != candidate.isrc }
                            .forEach { related.putIfAbsent(it.isrc, it) }
                    }
                }
                if (related.size >= MAX_RELATED_ISRCS) break
            }''',
    '''            for (iswc in works) {
                worksSeen += iswc
                // Credits.fm identifier graph supports relationship expansion on the
                // canonical ISWC endpoint. depth=2 asks for enriched recording objects;
                // direct ISRC strings are also collected below as a defensive fallback.
                val workUrl = "$BASE_URL/iswc/${URLEncoder.encode(iswc, "UTF-8")}?include=recordings&depth=2&limit=-1&contribute=false"
                lastUrl = workUrl
                when (val response = fetchJson(workUrl)) {
                    is FetchJson.Error -> terminalStatus = response.status
                    is FetchJson.Ok -> parseJson(response.body)?.let { root ->
                        collectRecordingRefs(root)
                            .filter { it.isrc != candidate.isrc }
                            .forEach { related.putIfAbsent(it.isrc, it) }
                        collectDirectIsrcs(root)
                            .filter { it != candidate.isrc }
                            .forEach { isrc -> related.putIfAbsent(isrc, RecordingRef(isrc, "", "")) }
                    }
                }
                if (related.size >= MAX_RELATED_ISRCS) break
            }''',
    'ISWC recording expansion',
)
source = replace_once(
    source,
    '''        if (related.isEmpty()) {
            return CreditsFmLookup(emptyList(), terminalStatus ?: CreditsFmStatus.NO_MATCH, lastUrl)
        }''',
    '''        if (related.isEmpty()) {
            return CreditsFmLookup(
                covers = emptyList(),
                status = terminalStatus ?: CreditsFmStatus.NO_MATCH,
                sourceUrl = lastUrl,
                sourceCandidates = sourceCandidateCount,
                worksFound = worksSeen.size,
                linkedRecordings = 0,
            )
        }''',
    'empty related diagnostics',
)
source = replace_once(
    source,
    '''        return if (covers.isNotEmpty()) {
            CreditsFmLookup(covers.values.toList(), CreditsFmStatus.OK, lastUrl)
        } else {
            CreditsFmLookup(emptyList(), terminalStatus ?: CreditsFmStatus.NO_MATCH, lastUrl)
        }''',
    '''        return if (covers.isNotEmpty()) {
            CreditsFmLookup(
                covers = covers.values.toList(),
                status = CreditsFmStatus.OK,
                sourceUrl = lastUrl,
                sourceCandidates = sourceCandidateCount,
                worksFound = worksSeen.size,
                linkedRecordings = related.size,
            )
        } else {
            CreditsFmLookup(
                covers = emptyList(),
                status = terminalStatus ?: CreditsFmStatus.NO_MATCH,
                sourceUrl = lastUrl,
                sourceCandidates = sourceCandidateCount,
                worksFound = worksSeen.size,
                linkedRecordings = related.size,
            )
        }''',
    'final Credits.fm diagnostics',
)
source = replace_once(
    source,
    '''    private fun collectDirectIsrcs(obj: JSONObject): Set<String> {
        val result = linkedSetOf<String>()

        fun visit(value: Any?, keyHint: String = "") {
            when (value) {
                is JSONObject -> value.keys().forEach { key -> visit(value.opt(key), key) }
                is JSONArray -> for (index in 0 until value.length()) visit(value.opt(index), keyHint)
                is String -> if (keyHint.contains("isrc", ignoreCase = true)) {
                    normalizeIsrc(value)?.let(result::add)
                }
            }
        }

        visit(obj)
        return result
    }''',
    '''    private fun collectDirectIsrcs(root: Any): Set<String> {
        val result = linkedSetOf<String>()

        fun visit(value: Any?) {
            when (value) {
                is JSONObject -> value.keys().forEach { key -> visit(value.opt(key)) }
                is JSONArray -> for (index in 0 until value.length()) visit(value.opt(index))
                // Graph depth=1 may expose recording relationships as plain ISRC
                // strings under a recordings array. ISRC format is strict enough to
                // safely recognize them regardless of their JSON key name.
                is String -> normalizeIsrc(value)?.let(result::add)
            }
        }

        visit(root)
        return result
    }''',
    'direct ISRC parser',
)
source_path.write_text(source, encoding='utf-8')

engine = engine_path.read_text(encoding='utf-8')
engine = replace_once(
    engine,
    '''    val creditsFmStats: CoverSourceStats = CoverSourceStats(),
    val secondHandSongsStats: CoverSourceStats = CoverSourceStats(),''',
    '''    val creditsFmStats: CoverSourceStats = CoverSourceStats(),
    val creditsFmSourceCandidates: Int = 0,
    val creditsFmWorksFound: Int = 0,
    val creditsFmLinkedRecordings: Int = 0,
    val secondHandSongsStats: CoverSourceStats = CoverSourceStats(),''',
    'Credits.fm outcome diagnostics',
)
engine = replace_once(
    engine,
    '''            creditsFmStats = CoverSourceStats(
                found = credits.covers.size,
                resolved = creditsResolved.size,
                used = used("Credits.fm"),
            ),
            secondHandSongsStats = CoverSourceStats(''',
    '''            creditsFmStats = CoverSourceStats(
                found = credits.covers.size,
                resolved = creditsResolved.size,
                used = used("Credits.fm"),
            ),
            creditsFmSourceCandidates = credits.sourceCandidates,
            creditsFmWorksFound = credits.worksFound,
            creditsFmLinkedRecordings = credits.linkedRecordings,
            secondHandSongsStats = CoverSourceStats(''',
    'Credits.fm diagnostic values',
)
engine_path.write_text(engine, encoding='utf-8')

screen = screen_path.read_text(encoding='utf-8')
screen = replace_once(
    screen,
    '''                        Text("Credits.fm: ${creditsState(coverOutcome.creditsFmStatus)}", style = MaterialTheme.typography.bodyMedium)
                        Text(statsText(coverOutcome.creditsFmStats), style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(6.dp))''',
    '''                        Text("Credits.fm: ${creditsState(coverOutcome.creditsFmStatus)}", style = MaterialTheme.typography.bodyMedium)
                        Text(statsText(coverOutcome.creditsFmStats), style = MaterialTheme.typography.bodySmall)
                        Text(
                            "percorso: ${coverOutcome.creditsFmSourceCandidates} ISRC sorgente · " +
                                "${coverOutcome.creditsFmWorksFound} ISWC · " +
                                "${coverOutcome.creditsFmLinkedRecordings} registrazioni collegate",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Spacer(Modifier.height(6.dp))''',
    'Credits.fm diagnostic pipeline row',
)
screen_path.write_text(screen, encoding='utf-8')

print('Credits.fm parser v2 patch applied successfully')
