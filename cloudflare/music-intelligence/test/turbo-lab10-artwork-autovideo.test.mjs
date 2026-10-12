import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';

const screen = fs.readFileSync('app/src/main/kotlin/com/metrolist/music/ui/component/DiscogsDirectVersionBrowser.kt', 'utf8');
const memory = fs.readFileSync('app/src/main/kotlin/com/metrolist/music/playback/CoverPlaybackMemory.kt', 'utf8');
const env = fs.readFileSync('uab-project.env', 'utf8');

test('LAB10: 50 editorial posters publish independently of video IDs within 10s', () => {
  assert.match(screen, /TURBO_BURST_DEADLINE_MS = 10_000L/);
  assert.match(screen, /val finalRanked = sortGroup\([\s\S]*?coverCandidatePool\(\)\.filter\(::modeAcceptsSeed\)/);
  assert.match(screen, /publishedCoverSnapshots = finalRanked/);
  assert.match(screen, /TURBO_RESULT_QUOTA = 50/);
  assert.match(screen, /editorialArtwork\(context, seed\.fingerprint, seed\.coverUrl\)/);
  assert.doesNotMatch(screen.slice(screen.indexOf('fun stableArtworkFor('), screen.indexOf('suspend fun playResolvedContext(')), /ytimg\.com|hqdefault/);
  assert.match(memory, /"poster:" \+ key\(fingerprint\)/);
});

test('LAB10: automatic, bounded, per-card video matching resumes after poster seal', () => {
  assert.match(screen, /TURBO_AUTO_VIDEO_PRIORITY_WINDOW_MS = 20_000L/);
  assert.match(screen, /fun startTurboAutomaticVideoLookup\(\)/);
  assert.match(screen, /session\.turboFinished = true[\s\S]*?startTurboAutomaticVideoLookup\(\)/);
  assert.match(screen, /resolveVideoChunk\(batch\)/);
  assert.match(screen, /publishedCoverSnapshots = publishedCoverSnapshots\.map \{ poster ->/);
  assert.match(screen, /LaunchedEffect\(sessionKey, publishedCoverSnapshots\.size, loading\)/);
  assert.match(screen, /if \(backgroundPausedForPlayback \|\| playbackIsCritical\(\)/);
});

test('LAB10: mini and expanded player use editorial artwork, not the video thumbnail', () => {
  assert.match(screen, /pinEditorialVideoArtwork\(context, selectedId, selectedArtwork\)/);
  assert.match(screen, /copy\(thumbnailUrl = selectedArtwork\)/);
  assert.doesNotMatch(screen, /selectedArtwork \?: selectedSong\?\.thumbnail/);
  assert.match(memory, /fun pinEditorialVideoArtwork\(/);
});

test('LAB10: missing video keeps card and manual recovery remains exceptional', () => {
  assert.match(screen, /var manualVideoSearchJob by remember\(sessionKey\)/);
  assert.match(screen, /while \(isActive && activeCriteria != null &&/);
  assert.match(screen, /fun retryMissingVideo\(seed: DiscogsVersionSeed\)/);
  assert.match(screen, /videoReadyVerified = hasPublishableVideo\(seed\)/);
  assert.match(screen, /Ricerca video automatica/);
});

test('LAB10: signed Turbo update stays in the same family', () => {
  assert.match(env, /UAB_UPDATE_FAMILY_APPLICATION_ID="it\.verlezza\.musiclab\.turbo01"/);
  assert.match(env, /UAB_UPDATE_FAMILY_VERSION_CODE="1010"/);
  assert.match(env, /export MUSICLAB_VERSION_CODE="1010"/);
});
