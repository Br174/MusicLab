#!/usr/bin/env python3
"""Fail if LAB79 changes anything beyond the approved preparation paths."""
from pathlib import Path
import subprocess
import sys

base = sys.argv[1] if len(sys.argv) > 1 else 'b30334651ec0a304f9291a3fa0ba3770096299a2'
root = 'app/src/main/kotlin/com/metrolist/music/ui/component/'
allowed = {
    root + name for name in ('CoverDiscoverySources.kt', 'CoverPreparationPolicy.kt',
                             'CoverRoundPresentation.kt', 'DiscogsDirectVersionBrowser.kt')
} | {
    'app/src/main/res/values/metrolist_strings.xml',
    'app/src/test/kotlin/com/metrolist/music/ui/component/CoverLab79PreparationProgressTest.kt',
    'app/src/test/kotlin/com/metrolist/music/ui/component/CoverLab78PresentationTest.kt',
    'app/src/test/kotlin/com/metrolist/music/ui/component/CoverLab79DocumentarySourcesTest.kt',
    'tools/universal-apk-builder/prepare-turbo-lab79-preparation-progress-env.py',
    'tools/universal-apk-builder/verify-lab79-preparation-scope.py',
    '.github/workflows/musiclab-turbo-lab79-preparation-progress-apk.yml',
    '.motorlab/LAB79_RESUME_READY.txt', 'behaviors/musiclab-cover.json',
}
def git(*args):
    return subprocess.check_output(['git', *args], text=True)
def before(path):
    return git('show', base + ':' + path)
changed = set(git('diff', '--name-only', base).splitlines()) | set(git('ls-files', '--others', '--exclude-standard').splitlines())
assert changed <= allowed, changed - allowed
subprocess.run(['git', 'diff', '--check', base], check=True)

path = root + 'CoverDiscoverySources.kt'
current = Path(path).read_text()
replacements = [
    ('        sourceCooldowns: Map<String, CoverSourceDiagnostic> = emptyMap(),\n', ''),
    ('reportResult(documentaryProgress?.lanes?.get(name) ?: sourceCooldowns[name]?.let {\n'
     '                emptyList<CoverSourceCandidate>() to it\n'
     '            } ?: sourceLane(block))',
     'reportResult(documentaryProgress?.lanes?.get(name) ?: sourceLane(block))'),
    ('val lane = reportResult(sourceCooldowns["COVER.INFO"]?.let { emptyList<CoverSourceCandidate>() to it }\n'
     '                ?: discoverCoverInfo(', 'val lane = reportResult(discoverCoverInfo('),
    ('val lookup = if ("MusicBrainz" in sourceCooldowns) MusicBrainzLookup(emptyList(), MusicBrainzStatus.NETWORK_ERROR) else sourceLane {',
     'val lookup = sourceLane {'),
    ('reportResult(sourceCooldowns["SecondHandSongs"]?.let { emptyList<CoverSourceCandidate>() to it }\n'
     '                    ?: sourceLane { discoverSecondHandSongs(', 'reportResult(sourceLane { discoverSecondHandSongs('),
]
for new, old in replacements:
    assert current.count(new) == 1, new
    current = current.replace(new, old, 1)
assert current == before(path), 'Source queries/parsing/matching/scoring changed outside cooldown routing'

path = root + 'DiscogsDirectVersionBrowser.kt'
old, new = before(path), Path(path).read_text()
header_end = '    fun runSearch('
old_header, new_header = old.split(header_end, 1)[0], new.split(header_end, 1)[0]
for addition in (
    '    val sourceRetries: CoverPreparationPolicy.SourceRetries = CoverPreparationPolicy.SourceRetries(),\n',
    '    val documentarySourceStates: MutableMap<String, List<CoverSourceDiagnostic>> = linkedMapOf(),\n',
    '    var preparationAudioPaused by remember(sessionKey) { mutableStateOf(false) }\n',
    '    var preparationWaitingSources by remember(sessionKey) { mutableStateOf("") }\n',
):
    assert new_header.count(addition) == 1
    new_header = new_header.replace(addition, '', 1)
assert old_header == new_header, 'Layout, manual controls or playback/lifecycle changed'
tail_start = '        // A further Cover round never clears or reorders already committed'
old_tail, new_tail = old.split(tail_start, 1)[1], new.split(tail_start, 1)[1]
for line in new_tail.splitlines(keepends=True):
    if 'preparationAudioPaused ->' in line or 'preparationWaitingSources.isNotBlank() ->' in line:
        new_tail = new_tail.replace(line, '', 1)
assert old_tail == new_tail, 'Poster layout or legacy search/player/manual behavior changed'
def fragment(text, start, end):
    return text.split(start, 1)[1].split(end, 1)[0]
for start, end in (
    ('            fun offerQueries()', '            offerQueries()'),
    ('            fun titleAllowed(', '            if (publicationBoundary'),
    ('                fun fromVideo(', '                suspend fun publish('),
    ('                    val verdict = CoverLabTurboRoundAdmission.evaluate(candidate, workTitle()', '                try {'),
):
    assert fragment(old, start, end) == fragment(new, start, end), start

path = 'app/src/main/res/values/metrolist_strings.xml'
current = Path(path).read_text()
for line in current.splitlines(keepends=True):
    if 'name="musiclab_cover_wait_audio"' in line or 'name="musiclab_cover_wait_named_sources"' in line:
        current = current.replace(line, '', 1)
assert current == before(path), 'Unrelated UI text changed'
print('PASS: result algorithms, queries, title/work admission, ranking, player and poster layout preserved; preparation-only delta')
