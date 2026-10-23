#!/usr/bin/env python3
"""Candidate 01 is an independent TEST-ONLY Android family, not a LAB update."""
from pathlib import Path

p = Path("uab-project.env")
s = p.read_text(encoding="utf-8")
changes = {
 'UAB_FILE_VERSION="TURBO_LAB19"':'UAB_FILE_VERSION="CANDIDATA_01"',
 'UAB_OUTPUT_NAME="MusicLab-Turbo-LAB19"':'UAB_OUTPUT_NAME="MusicLab-Turbo-CANDIDATA01"',
 'UAB_UPDATE_FAMILY_MODE="lab"':'UAB_UPDATE_FAMILY_MODE="candidate-test"',
 'UAB_UPDATE_FAMILY_ID="turbo01"':'UAB_UPDATE_FAMILY_ID="candidate01"',
 'UAB_UPDATE_FAMILY_APPLICATION_ID="it.verlezza.musiclab.turbo01"':'UAB_UPDATE_FAMILY_APPLICATION_ID="it.verlezza.musiclab.candidate01"',
 'UAB_UPDATE_FAMILY_SIGNING_PROFILE="musiclab-turbo-family-02-test-reuses-lab-test-key"':'UAB_UPDATE_FAMILY_SIGNING_PROFILE="musiclab-candidate01-ephemeral-test-only-no-production"',
 'UAB_UPDATE_FAMILY_ORIGIN_SHA="8a458f8f4d1e560736d81f78de551664998619f9"':'UAB_UPDATE_FAMILY_ORIGIN_SHA="dbfed95eae0a40a13e4c103031ea75481054a638"',
 'UAB_UPDATE_FAMILY_VISIBLE_NAME="Turbo LAB 19"':'UAB_UPDATE_FAMILY_VISIBLE_NAME="CANDIDATA 01"',
 'UAB_UPDATE_FAMILY_VERSION_CODE="1019"':'UAB_UPDATE_FAMILY_VERSION_CODE="10001"',
 'UAB_UPDATE_FAMILY_CERT_SHA256="9A:2F:67:CF:B3:C1:99:83:68:13:AD:DB:F7:BD:FB:0F:A6:5E:DC:76:5F:FA:CE:4B:6D:F9:49:0B:B0:96:27:49"':'UAB_UPDATE_FAMILY_CERT_SHA256="GENERATED_EPHEMERAL_AT_CI"',
 'UAB_FAMILY_SIGNING_KEY_B64_FILE=".uab/signing/musiclab-lab-family-01.jks.b64"':'UAB_FAMILY_SIGNING_KEY_B64_FILE=".uab/signing/musiclab-candidate01-build-only.jks.b64"',
 'UAB_FAMILY_KEY_ALIAS="musiclab_lab_family_01"':'UAB_FAMILY_KEY_ALIAS="candidate_c01_test"',
 'export METROLIST_APPLICATION_ID="it.verlezza.musiclab.turbo01"':'export METROLIST_APPLICATION_ID="it.verlezza.musiclab.candidate01"',
 'export METROLIST_APP_NAME="MusicLab Turbo LAB 19"':'export METROLIST_APP_NAME="MusicLab CANDIDATA 01"',
 'export MUSICLAB_VERSION_CODE="1019"':'export MUSICLAB_VERSION_CODE="10001"',
 'export MUSICLAB_VERSION_NAME="0.9.14-turbo-lab19"':'export MUSICLAB_VERSION_NAME="0.9.14-turbo-candidata01-test"',
}
for old,new in changes.items():
 if s.count(old)!=1: raise SystemExit(f"Candidate source/UAB guard failed for {old.split('=')[0]}")
 s=s.replace(old,new)
assert 'it.verlezza.musiclab.turbo01' not in s
assert 'musiclab-lab-family-01.jks.b64' not in s
assert 'UAB_PARALLEL_INSTALL="off"' in s
p.write_text(s,encoding="utf-8")
print("CANDIDATA 01: distinct package, new test-only signing source and offline staging, no LAB key reuse")
