import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';

const read = (p) => fs.readFileSync(p, 'utf8');

const providers = read('app/src/main/kotlin/com/metrolist/music/ui/component/CoverDiscoverySources.kt');
const player = read('app/src/main/kotlin/com/metrolist/music/utils/InnerTubeXPlayer.kt');
const uab = read('uab-project.env');

test('LAB42 distinguishes COVER.INFO reachable no-match from true unavailability', () => {
  assert.match(providers, /var publicSearchReachable = false/);
  assert.match(providers, /publicSearchReachable = true/);
  assert.match(providers, /available = publicSearchReachable/);
  assert.match(providers, /raggiungibile · nessuna corrispondenza/);
  assert.match(providers, /ricerca pubblica non disponibile/);
});

test('LAB42 keeps current COVER.INFO precise query and song parser', () => {
  assert.match(providers, /song="\$cleanTitle" performer="\$cleanArtist"/);
  assert.match(providers, /COVER_INFO_SONG_SELECTOR/);
  assert.match(providers, /parseCoverInfoDocument/);
  assert.match(providers, /seed\.directRelation -> 10/);
});

test('LAB42 preserves Player buono fast-first lane and modern fallback', () => {
  assert.match(player, /LAB07_FAST_LANE_TIMEOUT_MS = 1_800L/);
  const fast = player.indexOf('extractionBundle.fastExtractor.extract');
  const full = player.indexOf('extractionBundle.extractor.extract', fast + 1);
  assert.ok(fast >= 0 && full > fast);
  const fastTokenStart = player.indexOf('private val fastTokenProvider');
  const fullTokenStart = player.indexOf('private val tokenProvider', fastTokenStart);
  const fastToken = player.slice(fastTokenStart, fullTokenStart);
  assert.match(fastToken, /providers = emptySet\(\)/);
  assert.match(fastToken, /usesWebView = false/);
  assert.match(player, /providers = setOf\(PoTokenProviderKind\.WEB_BOTGUARD\)/);
  assert.match(player, /usesWebView = true/);
});

test('LAB42 APK identity is isolated from MADRE LAB41', () => {
  assert.match(uab, /UAB_FILE_VERSION="LAB42"/);
  assert.match(uab, /UAB_OUTPUT_NAME="MusicLab-LAB42"/);
  assert.match(uab, /UAB_APPLICATION_ID_BASE="it\.verlezza\.musiclab\.lab42"/);
  assert.match(uab, /UAB_APP_NAME_BASE="MusicLab LAB42"/);
});
