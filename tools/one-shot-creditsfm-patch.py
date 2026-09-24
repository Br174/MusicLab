from pathlib import Path

engine_path = Path('app/src/main/kotlin/com/metrolist/music/ui/component/CoverHubSearchEngine.kt')
screen_path = Path('app/src/main/kotlin/com/metrolist/music/ui/component/CoverSearchScreen.kt')


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if old not in text:
        raise SystemExit(f'Patch anchor missing: {label}')
    return text.replace(old, new, 1)


engine = engine_path.read_text(encoding='utf-8')
engine = replace_once(
    engine,
    '    val whoSampledStatus: WhoSampledStatus = WhoSampledStatus.NETWORK_ERROR,\n'
    '    val secondHandSongsStatus: SecondHandSongsStatus = SecondHandSongsStatus.NETWORK_ERROR,',
    '    val whoSampledStatus: WhoSampledStatus = WhoSampledStatus.NETWORK_ERROR,\n'
    '    val creditsFmStatus: CreditsFmStatus = CreditsFmStatus.NETWORK_ERROR,\n'
    '    val secondHandSongsStatus: SecondHandSongsStatus = SecondHandSongsStatus.NETWORK_ERROR,',
    'Credits.fm outcome status',
)
engine = replace_once(
    engine,
    '    val whoSampledStats: CoverSourceStats = CoverSourceStats(),\n'
    '    val secondHandSongsStats: CoverSourceStats = CoverSourceStats(),',
    '    val whoSampledStats: CoverSourceStats = CoverSourceStats(),\n'
    '    val creditsFmStats: CoverSourceStats = CoverSourceStats(),\n'
    '    val secondHandSongsStats: CoverSourceStats = CoverSourceStats(),',
    'Credits.fm outcome stats',
)

core_anchor = (
    '        val broad = broadDeferred.await()\n'
    '\n'
    '        val all = buildList {'
)
core_replacement = (
    '        val broad = broadDeferred.await()\n'
    '\n'
    '        // Credits.fm is deliberately additive: start it only after every\n'
    '        // Mother/core provider and its YouTube resolution have completed.\n'
    '        // A slow or unavailable Credits.fm therefore cannot regress them.\n'
    '        val credits = async(Dispatchers.IO) {\n'
    '            runCatching { CreditsFmCoverSource.lookup(cleanTitle, originalArtist) }\n'
    '                .getOrElse { CreditsFmLookup(emptyList(), CreditsFmStatus.NETWORK_ERROR) }\n'
    '        }.await()\n'
    '        val creditsResolved = resolveReferences(\n'
    '            references = credits.covers.map {\n'
    '                Ref(\n'
    '                    title = it.title,\n'
    '                    artist = it.artist,\n'
    '                    year = it.year,\n'
    '                    source = "Credits.fm",\n'
    '                    confirmed = true,\n'
    '                )\n'
    '            },\n'
    '            originalArtist = originalArtist,\n'
    '            durationSec = durationSec,\n'
    '            currentYouTubeId = currentYouTubeId,\n'
    '            maxRefs = 64,\n'
    '        )\n'
    '\n'
    '        val all = buildList {'
)
engine = replace_once(engine, core_anchor, core_replacement, 'additive Credits.fm lookup')
engine = replace_once(
    engine,
    '            addAll(broad)\n        }',
    '            addAll(broad)\n            addAll(creditsResolved)\n        }',
    'append Credits.fm results',
)
engine = replace_once(
    engine,
    '            whoSampledStatus = who.status,\n'
    '            secondHandSongsStatus = secondHandSongs.status,',
    '            whoSampledStatus = who.status,\n'
    '            creditsFmStatus = credits.status,\n'
    '            secondHandSongsStatus = secondHandSongs.status,',
    'Credits.fm status output',
)
stats_anchor = (
    '            whoSampledStats = CoverSourceStats(\n'
    '                found = who.covers.size,\n'
    '                resolved = whoResolved.size,\n'
    '                used = whoUsed,\n'
    '            ),\n'
    '            secondHandSongsStats = CoverSourceStats('
)
stats_replacement = (
    '            whoSampledStats = CoverSourceStats(\n'
    '                found = who.covers.size,\n'
    '                resolved = whoResolved.size,\n'
    '                used = whoUsed,\n'
    '            ),\n'
    '            creditsFmStats = CoverSourceStats(\n'
    '                found = credits.covers.size,\n'
    '                resolved = creditsResolved.size,\n'
    '                used = used("Credits.fm"),\n'
    '            ),\n'
    '            secondHandSongsStats = CoverSourceStats('
)
engine = replace_once(engine, stats_anchor, stats_replacement, 'Credits.fm stats output')
engine_path.write_text(engine, encoding='utf-8')

screen = screen_path.read_text(encoding='utf-8')
who_block = (
    '    fun whoState(status: WhoSampledStatus): String = when (status) {\n'
    '        WhoSampledStatus.OK -> "ok"\n'
    '        WhoSampledStatus.NO_MATCH -> "nessuna relazione"\n'
    '        WhoSampledStatus.BLOCKED -> "bloccato da verifica"\n'
    '        WhoSampledStatus.STRUCTURE_CHANGED -> "struttura non leggibile"\n'
    '        WhoSampledStatus.NETWORK_ERROR -> "non disponibile"\n'
    '    }\n'
    '\n'
    '    fun secondState'
)
credits_block = (
    '    fun whoState(status: WhoSampledStatus): String = when (status) {\n'
    '        WhoSampledStatus.OK -> "ok"\n'
    '        WhoSampledStatus.NO_MATCH -> "nessuna relazione"\n'
    '        WhoSampledStatus.BLOCKED -> "bloccato da verifica"\n'
    '        WhoSampledStatus.STRUCTURE_CHANGED -> "struttura non leggibile"\n'
    '        WhoSampledStatus.NETWORK_ERROR -> "non disponibile"\n'
    '    }\n'
    '\n'
    '    fun creditsState(status: CreditsFmStatus): String = when (status) {\n'
    '        CreditsFmStatus.OK -> "ok"\n'
    '        CreditsFmStatus.NO_MATCH -> "nessuna relazione"\n'
    '        CreditsFmStatus.AUTH_REQUIRED -> "autenticazione richiesta"\n'
    '        CreditsFmStatus.RATE_LIMITED -> "limite temporaneo"\n'
    '        CreditsFmStatus.NETWORK_ERROR -> "non disponibile"\n'
    '    }\n'
    '\n'
    '    fun secondState'
)
screen = replace_once(screen, who_block, credits_block, 'Credits.fm diagnostic state')

diagnostic_anchor = (
    '                        Text("WhoSampled: ${whoState(coverOutcome.whoSampledStatus)}", style = MaterialTheme.typography.bodyMedium)\n'
    '                        Text(statsText(coverOutcome.whoSampledStats), style = MaterialTheme.typography.bodySmall)\n'
    '                        Spacer(Modifier.height(6.dp))\n'
    '                        Text("SecondHandSongs:'
)
diagnostic_replacement = (
    '                        Text("WhoSampled: ${whoState(coverOutcome.whoSampledStatus)}", style = MaterialTheme.typography.bodyMedium)\n'
    '                        Text(statsText(coverOutcome.whoSampledStats), style = MaterialTheme.typography.bodySmall)\n'
    '                        Spacer(Modifier.height(6.dp))\n'
    '                        Text("Credits.fm: ${creditsState(coverOutcome.creditsFmStatus)}", style = MaterialTheme.typography.bodyMedium)\n'
    '                        Text(statsText(coverOutcome.creditsFmStats), style = MaterialTheme.typography.bodySmall)\n'
    '                        Spacer(Modifier.height(6.dp))\n'
    '                        Text("SecondHandSongs:'
)
screen = replace_once(screen, diagnostic_anchor, diagnostic_replacement, 'Credits.fm diagnostic row')
screen_path.write_text(screen, encoding='utf-8')

print('Credits.fm additive patch applied successfully')
