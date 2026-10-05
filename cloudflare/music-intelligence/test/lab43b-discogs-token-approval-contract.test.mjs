import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';

const read = (p) => fs.readFileSync(p, 'utf8');

const client = read('app/src/main/kotlin/com/metrolist/music/discogs/DiscogsClient.kt');
const source = read('app/src/main/kotlin/com/metrolist/music/ui/component/DiscogsVersionSource.kt');
const browser = read('app/src/main/kotlin/com/metrolist/music/ui/component/DiscogsDirectVersionBrowser.kt');
const account = read('app/src/main/kotlin/com/metrolist/music/ui/screens/settings/AccountSettings.kt');
const cover = read('app/src/main/kotlin/com/metrolist/music/ui/component/CoverDiscoverySources.kt');
const player = read('app/src/main/kotlin/com/metrolist/music/utils/InnerTubeXPlayer.kt');
const uab = read('uab-project.env');

test('LAB43B keeps Discogs usable with authenticated-to-public fallback', () => {
  assert.doesNotMatch(client, /require\(token\.isNotBlank\(\)\).*Token Discogs mancante/);
  assert.match(client, /var useAuthentication = token\.isNotBlank\(\)/);
  assert.match(client, /if \(useAuthentication\) \{[\s\S]*Authorization/);
  assert.match(client, /response\.code == 401 \|\| response\.code == 403/);
  assert.match(client, /retryWithoutAuthentication = true/);
  assert.match(client, /DISCOGS_PUBLIC_MIN_REQUEST_INTERVAL_MS = 2_500L/);
  assert.match(client, /DISCOGS_TRANSIENT_HTTP_CODES = setOf\(408, 425, 500, 502, 503, 504\)/);
  assert.doesNotMatch(browser, /discogsToken\.isBlank\(\)/);
  assert.doesNotMatch(source, /require\(token\.isNotBlank\(\)\)/);
});

test('LAB43B always exposes Discogs diagnostic on request failure', () => {
  assert.match(browser, /name = "Discogs",[\s\S]*available = false,[\s\S]*note = message/);
  assert.match(browser, /sourceDiagnostics\.filterNot \{ it\.name == "Discogs" \}/);
});

test('LAB43B credentials can be revealed and cleared individually', () => {
  assert.match(account, /var showLastFmApiKey/);
  assert.match(account, /var showLastFmSecret/);
  assert.match(account, /var showDiscogsToken/);
  assert.ok((account.match(/R\.drawable\.visibility/g) || []).length >= 3);
  assert.ok((account.match(/R\.drawable\.delete/g) || []).length >= 3);
  assert.match(account, /tempLastFmApiKey = ""/);
  assert.match(account, /tempLastFmSecret = ""/);
  assert.match(account, /tempDiscogsToken = ""/);
  assert.ok((account.match(/VisualTransformation\.None/g) || []).length >= 3);
});

test('LAB43B remembers approved songs and prevents duplicate approval', () => {
  assert.match(source, /val manuallyApproved: Boolean = false/);
  assert.match(source, /manuallyApproved = existing\.manuallyApproved \|\| incoming\.manuallyApproved/);
  assert.match(browser, /val approvedKeys: MutableSet<String>/);
  assert.match(browser, /candidate\.brainStatus == AiBrainDecisionStatus\.APPROVED/);
  assert.match(browser, /session\.approvedKeys \+= rejectionKey\(seed\)/);
  assert.match(browser, /if \(approved\) "Stato: Approvata" else "Stato: Non approvata"/);
  assert.match(browser, /enabled = !saving && !approved/);
  assert.match(browser, /approved -> "Approvata"/);
});

test('LAB43B preserves LAB43 breadth, faster verification and Player buono', () => {
  assert.match(cover, /val searchDocuments = mutableListOf/);
  assert.match(cover, /addQueryParameter\("per-page-songs", "200"\)/);
  assert.match(browser, /DIRECT_VIDEO_BATCH_SIZE = 24/);
  assert.match(browser, /DIRECT_VIDEO_PARALLELISM = 6/);
  assert.match(player, /LAB07_FAST_LANE_TIMEOUT_MS = 1_800L/);
  assert.match(player, /extractionBundle\.fastExtractor\.extract/);
});

test('LAB43B APK identity is isolated', () => {
  assert.match(uab, /UAB_FILE_VERSION="LAB43B"/);
  assert.match(uab, /UAB_OUTPUT_NAME="MusicLab-LAB43B"/);
  assert.match(uab, /UAB_APPLICATION_ID_BASE="it\.verlezza\.musiclab\.lab43b"/);
  assert.match(uab, /UAB_APP_NAME_BASE="MusicLab LAB43B"/);
});
