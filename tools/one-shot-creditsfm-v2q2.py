from pathlib import Path

source_path = Path('app/src/main/kotlin/com/metrolist/music/ui/component/CreditsFmCoverSource.kt')
engine_path = Path('app/src/main/kotlin/com/metrolist/music/ui/component/CoverHubSearchEngine.kt')
screen_path = Path('app/src/main/kotlin/com/metrolist/music/ui/component/CoverSearchScreen.kt')


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if old not in text:
        raise SystemExit(f'Missing patch anchor: {label}')
    return text.replace(old, new, 1)

source = source_path.read_text(encoding='utf-8')
source = replace_once(
    source,
    '''    val sourceCandidates: Int = 0,\n    val worksFound: Int = 0,\n    val linkedRecordings: Int = 0,\n)''',
    '''    val sourceCandidates: Int = 0,\n    val worksFound: Int = 0,\n    val linkedRecordings: Int = 0,\n    val titleMatchedRecordings: Int = 0,\n)''',
    'CreditsFmLookup titleMatchedRecordings',
)

anchor = '''            if (related.size >= MAX_RELATED_ISRCS) break\n        }\n\n        if (related.isEmpty()) {'''
insert = '''            if (related.size >= MAX_RELATED_ISRCS) break\n        }\n\n        // Q2 quantity expansion: the title-focused Credits.fm search is paginated up to\n        // 1000 rows. Keep only recordings explicitly tied to one of the ISWCs already\n        // discovered from the trusted source candidates. This broadens coverage without\n        // admitting unrelated same-title songs.\n        var titleMatchedRecordings = 0\n        if (worksSeen.isNotEmpty()) {\n            val sourceIsrcs = candidates.mapTo(linkedSetOf()) { it.isrc }\n\n            fun absorbTitleMatches(root: Any): Int {\n                var added = 0\n                collectRecordingRefsForWorks(root, worksSeen).forEach { ref ->\n                    if (ref.isrc in sourceIsrcs) return@forEach\n                    if (similarity(title, ref.title) < 0.42) return@forEach\n                    val previous = related[ref.isrc]\n                    related[ref.isrc] = mergeRecordingRef(previous, ref)\n                    if (previous == null) added++\n                }\n                return added\n            }\n\n            titleMatchedRecordings += absorbTitleMatches(searchRoot)\n            var offset = 100\n            var hasMore = searchHasMore(searchRoot) != false\n            while (hasMore && offset < 1000 && related.size < MAX_RELATED_ISRCS) {\n                val pageUrl = "$BASE_URL/search?q=$encoded&type=isrc&match=recording_title&exclude_lyrics=true&limit=100&offset=$offset&nocache=1"\n                lastUrl = pageUrl\n                when (val response = fetchJson(pageUrl)) {\n                    is FetchJson.Error -> {\n                        terminalStatus = response.status\n                        hasMore = false\n                    }\n                    is FetchJson.Ok -> {\n                        val root = parseJson(response.body)\n                        if (root == null) {\n                            hasMore = false\n                        } else {\n                            val pageRecordingCount = collectRecordingRefs(root).size\n                            titleMatchedRecordings += absorbTitleMatches(root)\n                            hasMore = searchHasMore(root) ?: (pageRecordingCount > 0)\n                            offset += 100\n                        }\n                    }\n                }\n            }\n        }\n\n        if (related.isEmpty()) {'''
source = replace_once(source, anchor, insert, 'Q2 title paging block')

source = replace_once(
    source,
    '''                worksFound = worksSeen.size,\n                linkedRecordings = 0,\n            )''',
    '''                worksFound = worksSeen.size,\n                linkedRecordings = 0,\n                titleMatchedRecordings = titleMatchedRecordings,\n            )''',
    'empty diagnostics title matches',
)
source = replace_once(
    source,
    '''                worksFound = worksSeen.size,\n                linkedRecordings = related.size,\n            )\n        } else {''',
    '''                worksFound = worksSeen.size,\n                linkedRecordings = related.size,\n                titleMatchedRecordings = titleMatchedRecordings,\n            )\n        } else {''',
    'success diagnostics title matches',
)
source = replace_once(
    source,
    '''                worksFound = worksSeen.size,\n                linkedRecordings = related.size,\n            )\n        }\n    }\n\n    private fun fetchJson''',
    '''                worksFound = worksSeen.size,\n                linkedRecordings = related.size,\n                titleMatchedRecordings = titleMatchedRecordings,\n            )\n        }\n    }\n\n    private fun fetchJson''',
    'no-cover diagnostics title matches',
)

source = replace_once(
    source,
    '''    private fun collectIswcs(root: Any): List<String> {''',
    '''    private fun collectRecordingRefsForWorks(root: Any, allowedWorks: Set<String>): List<RecordingRef> {\n        val result = linkedMapOf<String, RecordingRef>()\n\n        fun visit(value: Any?) {\n            when (value) {\n                is JSONObject -> {\n                    val ref = recordingFromObject(value)\n                    if (ref != null) {\n                        val objectWorks = collectIswcs(value)\n                        if (objectWorks.any { it in allowedWorks }) {\n                            result[ref.isrc] = mergeRecordingRef(result[ref.isrc], ref)\n                        }\n                    }\n                    value.keys().forEach { key -> visit(value.opt(key)) }\n                }\n                is JSONArray -> for (index in 0 until value.length()) visit(value.opt(index))\n            }\n        }\n\n        visit(root)\n        return result.values.toList()\n    }\n\n    private fun searchHasMore(root: Any): Boolean? {\n        var result: Boolean? = null\n\n        fun visit(value: Any?) {\n            if (result != null) return\n            when (value) {\n                is JSONObject -> value.keys().forEach { key ->\n                    if (key.equals("has_more", ignoreCase = true)) {\n                        result = when (val raw = value.opt(key)) {\n                            is Boolean -> raw\n                            is String -> raw.equals("true", ignoreCase = true)\n                            else -> null\n                        }\n                    } else {\n                        visit(value.opt(key))\n                    }\n                }\n                is JSONArray -> for (index in 0 until value.length()) visit(value.opt(index))\n            }\n        }\n\n        visit(root)\n        return result\n    }\n\n    private fun mergeRecordingRef(existing: RecordingRef?, candidate: RecordingRef): RecordingRef {\n        if (existing == null) return candidate\n        return RecordingRef(\n            isrc = candidate.isrc,\n            title = candidate.title.ifBlank { existing.title },\n            artist = candidate.artist.ifBlank { existing.artist },\n            year = candidate.year ?: existing.year,\n        )\n    }\n\n    private fun collectIswcs(root: Any): List<String> {''',
    'Q2 helpers',
)
source_path.write_text(source, encoding='utf-8')

engine = engine_path.read_text(encoding='utf-8')
engine = replace_once(
    engine,
    '''    val creditsFmWorksFound: Int = 0,\n    val creditsFmLinkedRecordings: Int = 0,\n    val secondHandSongsStats: CoverSourceStats = CoverSourceStats(),''',
    '''    val creditsFmWorksFound: Int = 0,\n    val creditsFmLinkedRecordings: Int = 0,\n    val creditsFmTitleMatchedRecordings: Int = 0,\n    val secondHandSongsStats: CoverSourceStats = CoverSourceStats(),''',
    'CoverHub Q2 outcome field',
)
engine = replace_once(
    engine,
    '''            creditsFmWorksFound = credits.worksFound,\n            creditsFmLinkedRecordings = credits.linkedRecordings,\n            secondHandSongsStats = CoverSourceStats(''',
    '''            creditsFmWorksFound = credits.worksFound,\n            creditsFmLinkedRecordings = credits.linkedRecordings,\n            creditsFmTitleMatchedRecordings = credits.titleMatchedRecordings,\n            secondHandSongsStats = CoverSourceStats(''',
    'CoverHub Q2 outcome mapping',
)
engine_path.write_text(engine, encoding='utf-8')

screen = screen_path.read_text(encoding='utf-8')
screen = replace_once(
    screen,
    '''                                "${coverOutcome.creditsFmWorksFound} ISWC · " +\n                                "${coverOutcome.creditsFmLinkedRecordings} registrazioni collegate",''',
    '''                                "${coverOutcome.creditsFmWorksFound} ISWC · " +\n                                "${coverOutcome.creditsFmLinkedRecordings} registrazioni collegate · " +\n                                "${coverOutcome.creditsFmTitleMatchedRecordings} da ricerca titolo",''',
    'Q2 diagnostics UI',
)
screen_path.write_text(screen, encoding='utf-8')

print('Credits.fm V2-Q2 title paging patch applied')
