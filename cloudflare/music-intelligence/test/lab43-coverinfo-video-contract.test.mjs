import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';

const read = (p) => fs.readFileSync(p, 'utf8');

const providers = read('app/src/main/kotlin/com/metrolist/music/ui/component/CoverDiscoverySources.kt');
const versions = read('app/src/main/kotlin/com/metrolist/music/ui/component/DiscogsVersionSource.kt');
const browser = read('app/src/main/kotlin/com/metrolist/music/ui/component/DiscogsDirectVersionBrowser.kt');
const player = read('app/src/main/kotlin/com/metrolist/music/utils/InnerTubeXPlayer.kt');
const uab = read('uab-project.env');

test('LAB43 merges all COVER.INFO query lanes instead of stopping at first hit', () => {
  assert.match(providers, /val searchDocuments = mutableListOf/);
  assert.match(providers, /addQueryParameter\("per-page-songs", "200"\)/);
  assert.match(providers, /searchDocuments \+= term to parsed/);
  assert.doesNotMatch(providers, /searchDocument = parsed[\s\S]{0,80}break/);
  assert.match(providers, /flatMap \{ \(_, document\) -> parseCoverInfoDocument\(document\) \}/);
  assert.match(providers, /COVER_INFO_RELATION_ROOT_LIMIT = 12/);
});

test('LAB43 imports only row-scoped COVER.INFO YouTube ids', () => {
  assert.match(providers, /parent\.hasClass\("youtube-parent"\)/);
  assert.match(providers, /row\?\.selectFirst\("\.youtube-id"\)/);
  assert.match(providers, /COVER_INFO_YOUTUBE_ID = Regex/);
  assert.match(providers, /playbackVideoSource = seed\.playbackVideoId\?\.let \{ "COVER\.INFO" \}/);
  assert.match(versions, /resolvedVideoId = candidate\.playbackVideoId/);
  assert.match(versions, /videoResolutionChecked = !candidate\.playbackVideoId\.isNullOrBlank\(\)/);
});

test('LAB43 accelerates unresolved video verification without changing player', () => {
  assert.match(browser, /DIRECT_VIDEO_BATCH_SIZE = 24/);
  assert.match(browser, /DIRECT_VIDEO_PARALLELISM = 6/);
  assert.match(player, /LAB07_FAST_LANE_TIMEOUT_MS = 1_800L/);
  assert.match(player, /extractionBundle\.fastExtractor\.extract/);
  assert.match(player, /providers = setOf\(PoTokenProviderKind\.WEB_BOTGUARD\)/);
});

test('LAB43 APK identity is isolated from MADRE LAB42', () => {
  assert.match(uab, /UAB_FILE_VERSION="LAB43"/);
  assert.match(uab, /UAB_OUTPUT_NAME="MusicLab-LAB43"/);
  assert.match(uab, /UAB_APPLICATION_ID_BASE="it\.verlezza\.musiclab\.lab43"/);
  assert.match(uab, /UAB_APP_NAME_BASE="MusicLab LAB43"/);
});
