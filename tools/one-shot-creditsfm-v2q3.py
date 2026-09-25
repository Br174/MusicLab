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
    'import org.json.JSONObject\nimport org.jsoup.Jsoup',
    'import org.json.JSONObject\nimport org.jsoup.Connection\nimport org.jsoup.Jsoup',
    'jsoup Connection import',
)
source = replace_once(
    source,
    '    val titleMatchedRecordings: Int = 0,\n)',
    '    val titleMatchedRecordings: Int = 0,\n    val titleBatchChecked: Int = 0,\n)',
    'lookup batch diagnostic field',
)
source = replace_once(
    source,
    '    private const val MAX_COVERS = 120\n',
    '    private const val MAX_COVERS = 120\n    private const val MAX_TITLE_SEARCH_CANDIDATES = 500\n',
    'title search candidate ceiling',
)
source = replace_once(
    source,
    '''    private data class RecordingRef(\n        val isrc: String,\n        val title: String,\n        val artist: String,\n        val year: Int? = null,\n    )\n''',
    '''    private data class RecordingRef(\n        val isrc: String,\n        val title: String,\n        val artist: String,\n        val year: Int? = null,\n    )\n\n    private data class BatchRecording(\n        val ref: RecordingRef,\n        val works: Set<String>,\n    )\n''',
    'batch recording model',
)

start = source.index('        // Q2 quantity expansion:')
end = source.index('        if (related.isEmpty()) {', start)
q3 = '''        // Q3 quantity expansion: search finds candidate ISRCs; the public batch API\n        // then resolves up to 100 at a time to authoritative ISWC/title/artist metadata.\n        // Only recordings tied to an already trusted source ISWC are admitted.\n        var titleMatchedRecordings = 0\n        var titleBatchChecked = 0\n        if (worksSeen.isNotEmpty()) {\n            val sourceIsrcs = candidates.mapTo(linkedSetOf()) { it.isrc }\n            val titleCandidates = linkedMapOf<String, RecordingRef>()\n\n            fun absorbSearchCandidates(root: Any) {\n                collectRecordingRefs(root).forEach { ref ->\n                    if (ref.isrc in sourceIsrcs) return@forEach\n                    if (similarity(title, ref.title) < 0.42) return@forEach\n                    titleCandidates[ref.isrc] = mergeRecordingRef(titleCandidates[ref.isrc], ref)\n                }\n            }\n\n            absorbSearchCandidates(searchRoot)\n            var offset = 100\n            var hasMore = searchHasMore(searchRoot) != false\n            while (\n                hasMore &&\n                offset < 1000 &&\n                titleCandidates.size < MAX_TITLE_SEARCH_CANDIDATES\n            ) {\n                val pageUrl = "$BASE_URL/search?q=$encoded&type=isrc&match=recording_title&exclude_lyrics=true&limit=100&offset=$offset&nocache=1"\n                lastUrl = pageUrl\n                when (val response = fetchJson(pageUrl)) {\n                    is FetchJson.Error -> {\n                        terminalStatus = response.status\n                        hasMore = false\n                    }\n                    is FetchJson.Ok -> {\n                        val root = parseJson(response.body)\n                        if (root == null) {\n                            hasMore = false\n                        } else {\n                            val before = titleCandidates.size\n                            absorbSearchCandidates(root)\n                            val pageRecordingCount = collectRecordingRefs(root).size\n                            hasMore = searchHasMore(root) ?: (pageRecordingCount > 0 && titleCandidates.size > before)\n                            offset += 100\n                        }\n                    }\n                }\n            }\n\n            val batchUrl = "$BASE_URL/batch"\n            val toCheck = titleCandidates.values\n                .take(MAX_TITLE_SEARCH_CANDIDATES)\n\n            for (chunk in toCheck.chunked(100)) {\n                if (related.size >= MAX_RELATED_ISRCS) break\n\n                val ids = JSONArray()\n                chunk.forEach { ids.put(it.isrc) }\n                val body = JSONObject()\n                    .put("isrcs", ids)\n                    .put("contribute", false)\n\n                titleBatchChecked += chunk.size\n                lastUrl = batchUrl\n                when (val response = postJson(batchUrl, body)) {\n                    is FetchJson.Error -> terminalStatus = response.status\n                    is FetchJson.Ok -> parseJson(response.body)?.let { root ->\n                        val requested = chunk.mapTo(linkedSetOf()) { it.isrc }\n                        val batchRecords = collectBatchRecordings(root, requested)\n                        for ((isrc, batch) in batchRecords) {\n                            if (batch.works.none { it in worksSeen }) continue\n\n                            val searchRef = titleCandidates[isrc]\n                            val resolved = if (searchRef == null) {\n                                batch.ref\n                            } else {\n                                mergeRecordingRef(searchRef, batch.ref)\n                            }\n                            if (similarity(title, resolved.title) < 0.42) continue\n\n                            val previous = related[isrc]\n                            related[isrc] = mergeRecordingRef(previous, resolved)\n                            if (previous == null) titleMatchedRecordings++\n                            if (related.size >= MAX_RELATED_ISRCS) break\n                        }\n                    }\n                }\n            }\n        }\n\n'''
source = source[:start] + q3 + source[end:]

source = replace_once(
    source,
    '''                linkedRecordings = 0,\n                titleMatchedRecordings = titleMatchedRecordings,\n            )''',
    '''                linkedRecordings = 0,\n                titleMatchedRecordings = titleMatchedRecordings,\n                titleBatchChecked = titleBatchChecked,\n            )''',
    'empty-related lookup diagnostics',
)
source = source.replace(
    '''                linkedRecordings = related.size,\n                titleMatchedRecordings = titleMatchedRecordings,\n            )''',
    '''                linkedRecordings = related.size,\n                titleMatchedRecordings = titleMatchedRecordings,\n                titleBatchChecked = titleBatchChecked,\n            )''',
)

fetch_anchor = '''    private fun parseJson(body: String): Any? = runCatching {\n'''
post_helper = '''    private fun postJson(url: String, body: JSONObject): FetchJson = runCatching {\n        val response = Jsoup.connect(url)\n            .userAgent(USER_AGENT)\n            .header("Accept", "application/json")\n            .header("Content-Type", "application/json")\n            .requestBody(body.toString())\n            .method(Connection.Method.POST)\n            .timeout(REQUEST_TIMEOUT_MS)\n            .ignoreContentType(true)\n            .ignoreHttpErrors(true)\n            .execute()\n\n        val responseBody = response.body()\n        when (response.statusCode()) {\n            in 200..299 -> if (responseBody.trim().startsWith("{") || responseBody.trim().startsWith("[")) {\n                FetchJson.Ok(responseBody, response.url().toString())\n            } else {\n                FetchJson.Error(CreditsFmStatus.NETWORK_ERROR, response.url().toString())\n            }\n            401, 403 -> FetchJson.Error(CreditsFmStatus.AUTH_REQUIRED, response.url().toString())\n            404 -> FetchJson.Error(CreditsFmStatus.NO_MATCH, response.url().toString())\n            429 -> FetchJson.Error(CreditsFmStatus.RATE_LIMITED, response.url().toString())\n            else -> FetchJson.Error(CreditsFmStatus.NETWORK_ERROR, response.url().toString())\n        }\n    }.getOrElse {\n        FetchJson.Error(CreditsFmStatus.NETWORK_ERROR, url)\n    }\n\n'''
source = replace_once(source, fetch_anchor, post_helper + fetch_anchor, 'POST JSON helper')

collect_anchor = '''    private fun collectRecordingRefsForWorks(root: Any, allowedWorks: Set<String>): List<RecordingRef> {\n'''
batch_helper = '''    private fun collectBatchRecordings(\n        root: Any,\n        requested: Set<String>,\n    ): Map<String, BatchRecording> {\n        val result = linkedMapOf<String, BatchRecording>()\n\n        fun visit(value: Any?, keyHint: String = "") {\n            when (value) {\n                is JSONObject -> {\n                    val direct = recordingFromObject(value)\n                    val keyIsrc = normalizeIsrc(keyHint)\n                    val isrc = (direct?.isrc ?: keyIsrc)?.takeIf { it in requested }\n                    if (isrc != null) {\n                        val ref = direct ?: RecordingRef(\n                            isrc = isrc,\n                            title = firstText(value, "recording_title", "song_title", "track_title", "title", "name"),\n                            artist = artistText(value),\n                            year = firstYear(value),\n                        )\n                        val works = collectIswcs(value).toSet()\n                        val previous = result[isrc]\n                        result[isrc] = if (previous == null) {\n                            BatchRecording(ref, works)\n                        } else {\n                            BatchRecording(\n                                ref = mergeRecordingRef(previous.ref, ref),\n                                works = previous.works + works,\n                            )\n                        }\n                    }\n                    value.keys().forEach { key -> visit(value.opt(key), key) }\n                }\n                is JSONArray -> for (index in 0 until value.length()) visit(value.opt(index), keyHint)\n            }\n        }\n\n        visit(root)\n        return result\n    }\n\n'''
source = replace_once(source, collect_anchor, batch_helper + collect_anchor, 'batch response parser')
source_path.write_text(source, encoding='utf-8')

engine = engine_path.read_text(encoding='utf-8')
engine = replace_once(
    engine,
    '    val creditsFmTitleMatchedRecordings: Int = 0,\n',
    '    val creditsFmTitleMatchedRecordings: Int = 0,\n    val creditsFmTitleBatchChecked: Int = 0,\n',
    'CoverHubOutcome batch diagnostic field',
)
engine = replace_once(
    engine,
    '            creditsFmTitleMatchedRecordings = credits.titleMatchedRecordings,\n',
    '            creditsFmTitleMatchedRecordings = credits.titleMatchedRecordings,\n            creditsFmTitleBatchChecked = credits.titleBatchChecked,\n',
    'CoverHub outcome mapping',
)
engine_path.write_text(engine, encoding='utf-8')

screen = screen_path.read_text(encoding='utf-8')
screen = replace_once(
    screen,
    '''                                "${coverOutcome.creditsFmLinkedRecordings} registrazioni collegate · " +\n                                "${coverOutcome.creditsFmTitleMatchedRecordings} da ricerca titolo",''',
    '''                                "${coverOutcome.creditsFmLinkedRecordings} registrazioni collegate · " +\n                                "${coverOutcome.creditsFmTitleBatchChecked} controllati batch · " +\n                                "${coverOutcome.creditsFmTitleMatchedRecordings} aggiunti da ricerca titolo",''',
    'diagnostic text',
)
screen_path.write_text(screen, encoding='utf-8')

print('Credits.fm V2-Q3 patch applied')
