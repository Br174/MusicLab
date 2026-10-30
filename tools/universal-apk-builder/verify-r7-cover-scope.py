#!/usr/bin/env python3
"""R7 changes only packet priority/publication and modern Cover card metadata."""
from pathlib import Path
import subprocess
import sys

base = sys.argv[1] if len(sys.argv)>1 else '71b7b4fdf945232f3f424ad84062ba82fa37684c'
root='app/src/main/kotlin/com/metrolist/music/ui/component/'
allowed={root+n for n in ('CoverPriorityOrder.kt','CoverRoundPresentation.kt',
    'DiscogsDirectVersionBrowser.kt','CoverCardMetadata.kt')} | {
    'app/src/main/res/values/metrolist_strings.xml',
    'app/src/test/kotlin/com/metrolist/music/ui/component/CoverLab82PacketPriorityTest.kt',
    'app/src/test/kotlin/com/metrolist/music/ui/component/CoverLab82CardMetadataTest.kt',
    'tools/universal-apk-builder/verify-r7-cover-scope.py',
    'tools/universal-apk-builder/prepare-turbo-lab79-preparation-progress-env.py',
    '.github/workflows/musiclab-turbo-lab79-preparation-progress-apk.yml',
    '.motorlab/R7_COVER_PACKET_CARD_PLAN_20261028.txt','behaviors/musiclab-cover.json',
}
changed=set(subprocess.check_output(['git','diff','--name-only',base],text=True).splitlines())
assert changed<=allowed,changed-allowed
subprocess.run(['git','diff','--check',base],check=True)
p=root+'DiscogsDirectVersionBrowser.kt'
a=subprocess.check_output(['git','show',base+':'+p],text=True)
b=Path(p).read_text()
start='    fun runSearch('
tail='        // A further Cover round never clears or reorders already committed'
card='private fun DiscogsVersionCard('
prefix=b.split(start,1)[0].replace(
    'else -> if (!modernCover ||\n'
    '            CoverCardMetadata.isStudio(seed)) DirectVersionCategory.STUDIO else DirectVersionCategory.ALL',
    'else -> DirectVersionCategory.STUDIO').replace(
    'seed: DiscogsVersionSeed, modernCover: Boolean = false', 'seed: DiscogsVersionSeed').replace(
    'directDisplayCategory(seed, modernCover = isTurbo && mode == DiscogsDirectMode.COVER)',
    'directDisplayCategory(seed)')
assert a.split(start,1)[0]==prefix, 'Player/header/lifecycle changed'
middle=b.split(tail,1)[1].split(card,1)[0].replace(
    'directDisplayCategory(it, modernCover = isTurbo)', 'directDisplayCategory(it)').replace(
    'directDisplayCategory(it, modernCover = twoLine)', 'directDisplayCategory(it)')
assert a.split(tail,1)[1].split(card,1)[0]==middle, 'Legacy discovery/footer changed'
legacy='        return\n    }\n    Surface('
ac,bc=a.split(card,1)[1],b.split(card,1)[1]
assert ac.split(legacy,1)[1]==bc.split(legacy,1)[1], 'Legacy cards or unrelated UI changed'
print('PASS: R7 scope, player, lifecycle, other search modes and legacy cards preserved')
