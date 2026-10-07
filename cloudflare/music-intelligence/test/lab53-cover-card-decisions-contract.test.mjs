import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';

const browser = fs.readFileSync(
  'app/src/main/kotlin/com/metrolist/music/ui/component/DiscogsDirectVersionBrowser.kt',
  'utf8',
);
const worker = fs.readFileSync(
  'cloudflare/music-intelligence/src/worker-v20.js',
  'utf8',
);

test('LAB53 Cover video thumbnail is large square and has no play overlay or play hint', () => {
  const card = browser.slice(browser.indexOf('private fun DiscogsVersionCard'));
  assert.match(card, /showVideoPreview/);
  assert.match(card, /\.size\(144\.dp\)/);
  assert.match(card, /https:\/\/i\.ytimg\.com\/vi\/\$it\/hqdefault\.jpg/);
  assert.doesNotMatch(card, /Text\(\s*"▶"/);
  assert.doesNotMatch(card, /Tocca per riprodurre/);
  assert.match(card, /\.clickable\(onClick = onPlay\)/);
});

test('LAB53 rejected Cover is hidden only from the current Cover work and no video work is wasted on it', () => {
  assert.match(browser, /fun isHiddenForCurrentCover\(seed: DiscogsVersionSeed\)/);
  assert.match(browser, /mode == DiscogsDirectMode\.COVER && isRejected\(seed\)/);
  assert.match(browser, /!isHiddenForCurrentCover\(seed\) && !seed\.resolvedVideoId\.isNullOrBlank\(\)/);
  assert.match(browser, /session\.rejectedKeys \+= rejectionKey\(seed\)/);
  assert.match(browser, /Disapprovata: non comparirà più tra le cover di questa canzone\./);
  assert.match(browser, /scheduleVideoPreload\(\)/);
});

test('LAB53 cloud rejection identity is scoped by original work, while approval feeds approved archive', () => {
  assert.match(worker, /workSearchKey: originalTitle && originalArtist \? searchKey\(originalTitle, originalArtist\) : ''/);
  assert.match(worker, /SELECT id FROM versions WHERE work_id=\?1 AND version_key=\?2 LIMIT 1/);
  assert.match(worker, /const userVerified = status === 'APPROVED' \? 1 : 0/);
  assert.match(worker, /const userRejected = status === 'REJECTED' \? 1 : 0/);
  assert.match(worker, /v\.user_rejected=0/);
  assert.match(worker, /v\.user_verified=1 OR v\.decision_status='APPROVED'/);
});

test('LAB53 uses X close controls and removes Chiudi from the version-details dialog', () => {
  const dialog = browser.slice(
    browser.indexOf('private fun DiscogsVersionDetailsDialog'),
    browser.indexOf('@Composable\nprivate fun SourceDiagnosticsDialog'),
  );
  assert.match(dialog, /Text\("×", style = MaterialTheme\.typography\.titleLarge\)/);
  assert.doesNotMatch(dialog, /Text\("Chiudi"\)/);
  assert.match(dialog, /else -> "Approva"/);
  assert.match(dialog, /Text\("Disapprova"\)/);
});

test('LAB53 first result starts at the top and every new search scrolls to item zero', () => {
  assert.match(browser, /contentPadding = PaddingValues\(\s*top = 0\.dp,/);
  assert.match(browser, /session\.listIndex = 0/);
  assert.match(browser, /session\.listOffset = 0/);
  assert.match(browser, /listState\.scrollToItem\(0\)/);
});
