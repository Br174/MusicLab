import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';

const browser = fs.readFileSync(
  'app/src/main/kotlin/com/metrolist/music/ui/component/DiscogsDirectVersionBrowser.kt',
  'utf8',
);

test('LAB54 normal playback no longer blocks Cover preparation', () => {
  assert.match(browser, /DIRECT_COVER_PLAYBACK_BATCH_SIZE = 1/);
  assert.match(browser, /fun playbackIsNormallyPlaying\(\): Boolean/);
  assert.match(browser, /fun playbackIsCritical\(\): Boolean/);
  assert.match(browser, /fun backgroundWorkBlocked\(\): Boolean = backgroundPausedForPlayback \|\| playbackIsCritical\(\)/);
  assert.match(browser, /if \(playbackIsNormallyPlaying\(\)\) \{\s*DIRECT_COVER_PLAYBACK_BATCH_SIZE/);
  const resume = browser.slice(
    browser.indexOf('fun resumeCoverBackgroundAfterPlaybackBurst'),
    browser.indexOf('fun saveDecision'),
  );
  assert.doesNotMatch(resume, /isEffectivelyPlaying/);
  assert.match(resume, /while \(playbackIsCritical\(\)\)/);
});

test('LAB54 ten-result foundation is preserved while LAB55 appends blocks vertically', () => {
  assert.match(browser, /DIRECT_COVER_PAGE_SIZE = 10/);
  assert.match(browser, /visibleMembershipPool[\s\S]*take\(visibleLimit\.coerceAtLeast\(pageSize\)\)/);
  assert.match(browser, /Text\("Carica altri \$pageSize"\)/);
  assert.doesNotMatch(browser, /Text\("‹ Precedenti"\)/);
  assert.doesNotMatch(browser, /Text\("Successivi ›"\)/);
});

test('LAB54 global ranking foundation remains available for score ordering', () => {
  assert.match(browser, /DirectVersionSort\.RELEVANCE/);
  assert.match(browser, /compareByDescending<DiscogsVersionSeed> \{ it\.confidenceScore \}/);
  assert.match(browser, /if \(mode == DiscogsDirectMode\.COVER\) return categoryFiltered/);
});

test('LAB54 Live and Mix Remix categories are derived from title or metadata without admission score gates', () => {
  assert.match(browser, /fun directDisplayCategory\(seed: DiscogsVersionSeed\)/);
  assert.match(browser, /live\|dal vivo\|concert\|concerto/);
  assert.match(browser, /remix\|mix\|extended mix\|radio mix\|club mix\|dance mix/);
  assert.match(browser, /DirectChip\("Mix\/Remix · \$remix"/);
  assert.match(browser, /showConfidence = mode != DiscogsDirectMode\.COVER \|\| category == DirectVersionCategory\.ALL/);
});

test('LAB54 main Cover card contains only essential musical metadata plus details action', () => {
  const card = browser.slice(browser.indexOf('private fun DiscogsVersionCard'));
  assert.match(card, /Fonte: \$\{primaryDiscoverySource\(seed\)\}/);
  assert.match(card, /Affidabilità: \$\{seed\.confidenceScore\}\/20/);
  assert.match(card, /Data pubblicazione: \$publicationDate/);
  assert.doesNotMatch(card, /Video pronto: \$videoTitle/);
  assert.doesNotMatch(card, /Lingua\/adattamento:/);
  assert.doesNotMatch(card, /seed\.formats\.firstOrNull/);
  assert.doesNotMatch(card, /seed\.labels\.firstOrNull/);
});

test('LAB54 details are reduced to title date author performer composer and foreign origin', () => {
  const start = browser.indexOf('private fun DiscogsVersionDetailsDialog');
  const end = browser.indexOf('private fun SourceDiagnosticsDialog');
  const details = browser.slice(start, end);
  assert.match(details, /Titolo: \$\{seed\.trackTitle\}/);
  assert.match(details, /Data pubblicazione: \$publicationDate/);
  assert.match(details, /Autore: \$authors/);
  assert.match(details, /Interprete: \$\{seed\.artist\}/);
  assert.match(details, /Compositore: \$composers/);
  assert.match(details, /Paese d'origine:/);
  assert.doesNotMatch(details, /Discogs Release:/);
  assert.doesNotMatch(details, /Formato:/);
  assert.doesNotMatch(details, /Etichetta:/);
  assert.doesNotMatch(details, /Fonte video:/);
  assert.doesNotMatch(details, /Motivi punteggio:/);
});

test('LAB54 source diagnostics uses top-right X instead of Chiudi', () => {
  const start = browser.indexOf('private fun SourceDiagnosticsDialog');
  const end = browser.indexOf('private fun DirectCategorySelector');
  const dialog = browser.slice(start, end);
  assert.match(dialog, /Text\("×", style = MaterialTheme\.typography\.titleLarge\)/);
  assert.doesNotMatch(dialog, /Text\("Chiudi"\)/);
});
