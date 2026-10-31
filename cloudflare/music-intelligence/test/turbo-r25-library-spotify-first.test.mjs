import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
const dir='app/src/main/kotlin/com/metrolist/music/ui/screens/library/';
const s=readFileSync(dir+'LibraryScreen.kt','utf8');
const p=readFileSync(dir+'LibraryPlaylistsScreen.kt','utf8');
const m=readFileSync(dir+'LibraryMixScreen.kt','utf8');
const r=readFileSync(dir+'PlaylistSourcePolicy.kt','utf8');
test('three adjacent filter chips and saved source state',()=>{
 assert.ok(s.indexOf('"PLAYLISTS" to')<s.indexOf('"SPOTIFY" to'));
 assert.ok(s.indexOf('"SPOTIFY" to')<s.indexOf('"YOUTUBE_MUSIC" to'));
 assert.match(s,/playlistSource by rememberSaveable/);
 assert.match(s,/sourceFilter = playlistSource/);
});
test('Spotify ranks first in both layouts of mixed and playlist views',()=>{
 for(const [src,condition,target] of [
 [p,'if (showSpotifyEntries && isSpotifyActive && spotifyPlaylists.isNotEmpty())','items = visibleResults,'],
 [m,'if (isSpotifyActive && spotifyPlaylists.isNotEmpty())','items = filteredItems,']]){
  assert.equal(src.split(condition).length-1,2);
  assert.ok(src.indexOf(condition)<src.indexOf(target));
  assert.ok(src.lastIndexOf(condition)<src.lastIndexOf(target));
 }
});
test('real source provenance, all mode and preserved data',()=>{
 assert.match(r,/!browseId\.isNullOrBlank\(\) && !isLocal/);
 assert.match(r,/PlaylistSourceFilter.SPOTIFY -> false/);
 assert.match(r,/PlaylistSourceFilter.ALL -> true/);
 assert.match(p,/showSpotifyEntries && needsSpotifyReLogin/);
 assert.match(p,/showEmptyPlaceholder/);
});
