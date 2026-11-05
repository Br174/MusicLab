import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';

const path='app/src/main/kotlin/com/metrolist/music/';
const art=readFileSync(path+'ui/screens/search/MusicLabChartArtwork.kt','utf8');
const chart=readFileSync(path+'ui/screens/search/MusicLabChartScreen.kt','utf8');
const items=readFileSync(path+'ui/component/Items.kt','utf8');
const online=readFileSync(path+'ui/screens/search/OnlineSearchResult.kt','utf8');
const env=readFileSync('tools/universal-apk-builder/prepare-turbo-r27-artwork-33107-env.py','utf8');

test('R27 cover ownership is track-specific and survives playback changes',()=>{
    assert.match(art,/private fun key\(track: MusicLabChartTrack\)/);
    assert.match(art,/getSharedPreferences\(PREFS/);
    assert.match(art,/putString\("art:" \+ id/);
    assert.match(art,/rememberArtwork\(context, track, nowPlayingArtwork\)/);
    assert.match(art,/initialValue = track.artwork/);
    assert.doesNotMatch(art,/initialValue = nowPlayingArtwork/);
    assert.doesNotMatch(art,/value = nowPlayingArtwork\?\.takeIf/);
});
test('R27 missing covers resolve without a tap and retry safely',()=>{
    assert.match(art,/produceState<String\?>/);
    assert.match(art,/MusicLabChartArtworkResolver.resolve\(context, track/);
    // R67: retry quickly after audio startup, not a blind 20-second timer.
    assert.match(art,/repeat\(3\)/);
    assert.match(art,/delay\(2_500L\)/);
    assert.match(art,/MusicLabArtworkAudioGate\.mayStartOptionalArtwork\(\)/);
    assert.match(art,/MusicLabArtworkAudioGate\.optionalLookup/);
    assert.match(art,/forgetBrokenArtwork/);
    assert.match(art,/Semaphore\(3\)/);
});
test('R27 chart playback reuses the same independently verified song lookup',()=>{
    assert.match(chart,/MusicLabChartArtworkResolver.resolveSong\(context, track\)/);
    assert.match(chart,/MusicLabChartArtworkResolver.rememberedVideoId\(context, track\)/);
    assert.doesNotMatch(chart,/matches.firstOrNull \{ candidate/);
    assert.match(art,/YouTube.searchSummary\(query, incognito = true\)/);
    assert.match(art,/YouTube.SearchFilter.FILTER_SONG/);
    assert.match(art,/sameRecordingLabel\(track.title, track.artist, it\)/);
    assert.match(art,/karaoke\|instrumental\|cover\|reaction/);
});
test('R27 genre results use per-song art without changing general searches',()=>{
    assert.match(online,/MusicLabSearchExploreCatalog.genres.any/);
    assert.match(online,/resolveGenreArtwork = isMusicLabGenreQuery/);
    assert.match(items,/resolveGenreArtwork: Boolean = false/);
    assert.match(items,/MusicLabChartArtwork\(/);
    assert.match(items,/thumbnailUrl = item.thumbnail/);
});
test('R27 preserves installed package and only advances version',()=>{
    assert.match(env,/runpy.run_path\("tools\/universal-apk-builder\/prepare-turbo-r26/);
    assert.match(env,/33106", "33107"/);
    assert.match(env,/it.verlezza.musiclab.turbo01/);
});
