import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
const src='app/src/main/kotlin/com/metrolist/music/';
const browser=readFileSync(src+'ui/component/DiscogsDirectVersionBrowser.kt','utf8');
const prefs=readFileSync(src+'constants/MusicIntelligenceKeys.kt','utf8');
const settings=readFileSync(src+'ui/screens/settings/SettingsScreen.kt','utf8');
const detail=readFileSync(src+'ui/screens/settings/CoverCompilationSettings.kt','utf8');
const archive=readFileSync(src+'ui/component/MusicLabArchiveScreen.kt','utf8');
const nav=readFileSync(src+'ui/screens/NavigationBuilder.kt','utf8');

test('LAB60 crash: do not feed nullable video IDs to ConcurrentHashMap.newKeySet.contains',()=>{
 assert.doesNotMatch(browser,/seed\.resolvedVideoId\?\.trim\(\) (?:!in|in) session\.playReadyVideoIds/);
 assert.ok((browser.match(/seed\.resolvedVideoId\?\.trim\(\)\.orEmpty\(\) in session\.playReadyVideoIds/g)||[]).length>=3);
 assert.ok(browser.includes('seed.resolvedVideoId?.trim().orEmpty() !in session.playReadyVideoIds'));
 assert.match(browser,/val playReadyVideoIds: MutableSet<String> = ConcurrentHashMap\.newKeySet\(\)/);
});
test('LAB60: one native page reachable from Settings, no legacy duplicates',()=>{
 assert.match(nav,/composable\("settings\/cover_compilation"\) \{\s*CoverCompilationSettings\(navController\)/);
 assert.match(settings,/title = \{ Text\("Cover e Compilation"\) \}/);
 assert.doesNotMatch(settings,/title = "Motore AI MusicLab"/);
 assert.doesNotMatch(settings,/title = \{ Text\("Ricerca toccando il titolo"\) \}/);
 for(const label of ['Archivio Cover','Cloud MusicLab','Cover AI','Originali AI','Live','Remix','Versioni straniere','Memoria cloud','Sincronizzazione Cloud (LAB46)','Ricerca toccando il titolo','Apri Compilation'])
   assert.ok(detail.includes(label),label);
 assert.ok(detail.includes('navController.popBackStack()'));
});
test('LAB60: two independent DataStore switches, default ON, old keys retained',()=>{
 assert.match(prefs,/MusicLabCoverArchiveEnabledKey = booleanPreferencesKey\("musicLabCoverArchiveEnabled"\)/);
 assert.match(prefs,/MusicLabCloudEnabledKey = booleanPreferencesKey\("musicLabCloudEnabled"\)/);
 assert.match(detail,/rememberPreference\(MusicLabCoverArchiveEnabledKey, true\)/);
 assert.match(detail,/rememberPreference\(MusicLabCloudEnabledKey, true\)/);
 assert.match(detail,/setCoverArchiveEnabled\(!coverArchiveEnabled\)/);
 assert.match(detail,/setCoverCloudEnabled\(!coverCloudEnabled\)/);
 assert.match(detail,/rememberPreference\(MusicAiCoverEnabledKey, true\)/);
});
test('LAB60: Cloud and archive switches gate actual providers without wiping local state',()=>{
 assert.match(browser,/val effectiveCloudEndpoint = if \(coverCloudEnabled\)/);
 assert.match(browser,/useCloudMemory = cloudMemoryEnabled && coverCloudEnabled/);
 assert.match(browser,/if \(coverArchiveEnabled && coverCloudEnabled\)\s+withTimeoutOrNull/);
 assert.match(browser,/if \(isTurbo && mode == DiscogsDirectMode\.COVER &&\s+coverArchiveEnabled && coverCloudEnabled\)/);
 assert.match(archive,/LaunchedEffect\(query, endpoint, archiveEnabled, cloudEnabled\)/);
 assert.match(archive,/CloudMusicDiscovery\.cancelArchiveRequests\(\)/);
 assert.doesNotMatch(detail,/CoverRoundStorage\.clear|deleteRecursively|clearApplicationUserData/);
});
test('LAB60: changing source preferences does not touch native audio nor Android manifest',()=>{
 assert.ok(detail.includes('musicAiBackground'));
 assert.ok(browser.includes('resolveVideoChunk(pending)'));
 assert.ok(browser.includes('verifiedVideoIds = session.playReadyVideoIds'));
});
