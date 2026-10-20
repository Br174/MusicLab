import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
const src=fs.readFileSync('app/src/main/kotlin/com/metrolist/music/ui/component/DiscogsDirectVersionBrowser.kt','utf8');
const renderer=src.split('if (mode == DiscogsDirectMode.COVER) {\n                if (visibleOriginalVersions.isNotEmpty())')[1].split('if (\n                mode == DiscogsDirectMode.COVER &&')[0];
const card=src.split('private fun DiscogsVersionCard(')[1];
const publisher=src.split('fun sealPostersOnce() {')[1].split('fun stopAtFullQuota()')[0];

test('regression crash: Compose LazyColumn uses physical index, not colliding fingerprint keys',()=>{
  assert.match(src,/import androidx\.compose\.foundation\.lazy\.itemsIndexed/);
  assert.match(renderer,/itemsIndexed\(\s*items = visibleOriginalVersions/);
  assert.match(renderer,/key = \{ index, _ -> "discogs_direct_original_slot_\$index" \}/);
  assert.match(renderer,/itemsIndexed\(\s*items = visibleTrueCovers/);
  assert.match(renderer,/key = \{ index, _ -> "discogs_direct_cover_slot_\$index" \}/);
  assert.doesNotMatch(renderer,/key = \{ seed -> if \(isTurbo\)/);
});

test('regression crash scenario: two different records share fingerprint but not a slot key',()=>{
  const records=[
    {fingerprint:'external|lanno che verra|sameartist|studio',version:'studio'},
    {fingerprint:'external|lanno che verra|sameartist|studio',version:'remaster'},
    {fingerprint:'external|lanno che verra|other|live',version:'live'}
  ];
  const old=records.map(r=>'discogs_direct_cover_'+r.fingerprint);
  const safe=records.map((_,i)=>'discogs_direct_cover_slot_'+i);
  assert.notEqual(new Set(old).size,old.length);
  assert.equal(new Set(safe).size,safe.length);
  assert.deepEqual(records.map((r,i)=>({key:safe[i],version:r.version})).map(x=>x.key),safe);
});

test('different visual sections retain disjoint slot namespaces',()=>{
  const originals=Array.from({length:10},(_,i)=>'discogs_direct_original_slot_'+i);
  const covers=Array.from({length:40},(_,i)=>'discogs_direct_cover_slot_'+i);
  assert.equal(new Set([...originals,...covers]).size,50);
});

test('regular originals list also has index keys instead of duplicate fingerprints',()=>{
  assert.match(src,/itemsIndexed\(\s*items = visibleResults,\s*key = \{ index, _ -> "discogs_direct_originals_slot_\$index" \}/);
});

test('dedupes fingerprints in first, second and fallback waves independently of recording identity',()=>{
  assert.match(publisher,/ranked\.distinctBy \{ it\.fingerprint \}/);
  assert.match(publisher,/it\.fingerprint in committedFingerprints/);
  assert.match(publisher,/\.distinctBy \{ it\.fingerprint \}/);
  assert.match(src,/val finalRanked = turboRankedPool\(\)\s*\.filter\(::modeAcceptsSeed\)\s*\.distinctBy \{ it\.fingerprint \}/);
});

test('LAB27 video-first result uses own verified thumbnail, never stale editorial image',()=>{
  const art=src.split('fun stableArtworkFor(seed: DiscogsVersionSeed): String? =')[1].split('suspend fun playResolvedContext')[0];
  assert.match(art,/"MusicLab LAB27 verified video" in seed\.sourceNames/);
  assert.match(art,/"MusicLab LAB28 verified video" in seed\.sourceNames/);
  assert.match(art,/seed\.coverUrl/);
  assert.match(src,/"MusicLab LAB28 verified video"\)\.distinct\(\)/);
});

test('card does not display stale indefinite 5+5+5 when job is inactive',()=>{
  assert.doesNotMatch(card,/automaticVideoManaged -> "Ricerca automatica 5\+5\+5/);
  assert.match(card,/automaticVideoManaged && automaticVideoSearching/);
  assert.match(card,/Video non disponibile · Verifica conclusa o sospesa/);
  assert.match(src,/automaticVideoSearching = isTurbo && turboAutoVideoJob\?\.isActive == true/);
});

test('10+40 in two whole batches retained, with original performer section',()=>{
  assert.match(src,/TURBO_MIN_EARLY_POSTER_BLOCK = 10/);
  assert.match(src,/TURBO_POSTER_SECOND_BLOCK = 40/);
  assert.match(publisher,/publishedCoverSnapshots = publishedCoverSnapshots \+ next/);
  assert.match(renderer,/visibleOriginalVersions/);
});

test('never rewrite native playback, Android application ID or Cloud worker',()=>{
  const stamp=fs.readFileSync('tools/universal-apk-builder/prepare-turbo-lab28-env.py','utf8');
  const workflow=fs.readFileSync('.github/workflows/turbo-lab28-crash-state-poster-rollback-apk.yml','utf8');
  assert.match(stamp,/UAB_UPDATE_FAMILY_VERSION_CODE="1028"/);
  assert.match(stamp,/it\.verlezza\.musiclab\.turbo01/);
  assert.match(workflow,/application-label:'MusicLab Turbo LAB 28'/);
  assert.match(workflow,/9A:2F:67:CF:B3:C1:99:83:68:13:AD:DB:F7:BD:FB:0F:A6:5E:DC:76:5F:FA:CE:4B:6D:F9:49:0B:B0:96:27:49/);
});
