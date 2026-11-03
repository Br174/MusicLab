import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';

const read = p => fs.readFileSync(p, 'utf8');
const root = 'app/src/main/kotlin/com/metrolist/music/ui/screens/search/';
const weekly = read(root + 'MusicLabHistoricalHitParade.kt');
const extra = read(root + 'MusicLabHistoricalExtraCharts.kt');
const resolver = read(root + 'MusicLabHitParadeVideoResolver.kt');
const menu = read(root + 'MusicLabHitParadeVideoMenu.kt');

test('R48 attempts additional verified video searches after original FastPath and recovery', () => {
  assert.match(resolver, /MusicLabFastPath\.find\(title, artist, compatible\)/);
  assert.match(resolver, /MusicLabTrackRecovery\.find\(title, artist, compatible\)/);
  assert.match(resolver, /MusicLabChartArtworkResolver\.resolveSong/);
  assert.match(resolver, /YouTube\.SearchFilter\.FILTER_VIDEO/);
  assert.match(resolver, /MusicLabChartArtworkResolver\.sameRecordingLabel/);
  assert.match(resolver, /withTimeoutOrNull\(3_500L\)/);
});

test('R48 saves both automatic and user-chosen videos under song and artist, not chart date', () => {
  assert.match(resolver, /SECTION = "hitparade-video-choice-r48"/);
  assert.match(resolver, /normalize\(title\) \+ "\|" \+ normalize\(artist\)/);
  assert.match(resolver, /MusicLabSectionMemory\.writeState/);
  assert.match(resolver, /MusicLabSectionMemory\.readState/);
  assert.match(resolver, /existing\?\.manual != true/);
  assert.match(resolver, /if \(pinned\.manual\) return null/);
  assert.doesNotMatch(resolver, /rank\s*\+\s*date|chartDate\s*\+\s*rank/);
});

test('R48 action menu supports result selection, validated URL and removal', () => {
  for (const label of ['Cerca video corrispondente', 'Inserisci link manualmente',
      'Rimuovi collegamento salvato']) assert.ok(menu.includes(label), label);
  assert.match(menu, /YouTubeMatchDialog\(/);
  assert.match(menu, /MusicLabHitParadeVideoResolver\.userSelected\(/);
  assert.match(menu, /MusicLabHitParadeVideoResolver\.removeSelection\(/);
});

test('R48 weekly and annual singles share saved binding for play and automatic queue', () => {
  assert.match(weekly, /MusicLabHitParadeVideoResolver\.find\(/);
  assert.match(extra, /MusicLabHitParadeVideoResolver\.find\(/);
  assert.match(weekly, /MusicLabHitParadeVideoMenu\(song\.title, song\.artist\)/);
  assert.match(extra, /if \(!mode\.isAlbum\) MusicLabHitParadeVideoMenu/);
  assert.match(extra, /if \(mode\.isAlbum\) \{/);
  assert.match(weekly, /MusicLabSectionPlayback\.select\(/);
  assert.match(extra, /MusicLabSectionPlayback\.select\(/);
  assert.doesNotMatch(menu + resolver, /\bplayNext\(|\bplayNow\(/);
});
