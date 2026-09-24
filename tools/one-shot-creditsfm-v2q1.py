from pathlib import Path

source_path = Path('app/src/main/kotlin/com/metrolist/music/ui/component/CreditsFmCoverSource.kt')
engine_path = Path('app/src/main/kotlin/com/metrolist/music/ui/component/CoverHubSearchEngine.kt')

source = source_path.read_text(encoding='utf-8')
replacements = {
    'private const val MAX_RECORDING_CANDIDATES = 3': 'private const val MAX_RECORDING_CANDIDATES = 12',
    'private const val MAX_RELATED_ISRCS = 60': 'private const val MAX_RELATED_ISRCS = 240',
    'private const val MAX_METADATA_FETCHES = 18': 'private const val MAX_METADATA_FETCHES = 80',
    'private const val MAX_COVERS = 80': 'private const val MAX_COVERS = 120',
}
for old, new in replacements.items():
    if old not in source:
        raise SystemExit(f'Missing Credits.fm patch anchor: {old}')
    source = source.replace(old, new, 1)
source_path.write_text(source, encoding='utf-8')

engine = engine_path.read_text(encoding='utf-8')
old = '''            currentYouTubeId = currentYouTubeId,\n            maxRefs = 64,\n        )'''
new = '''            currentYouTubeId = currentYouTubeId,\n            maxRefs = 120,\n        )'''
if old not in engine:
    raise SystemExit('Missing CoverHub Credits.fm maxRefs anchor')
engine = engine.replace(old, new, 1)
engine_path.write_text(engine, encoding='utf-8')

print('Credits.fm V2-Q1 quantity patch applied')
