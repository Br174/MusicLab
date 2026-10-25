#!/usr/bin/env python3
"""LAB53 live 60-song source-availability experiment.

It is not a claim of 50 on-device covers. It measures documentary coverage
using the same provider/sample as LAB52, then persists the observable source
funnel and explains the difference between catalog and app UI evidence.
"""
import runpy
import json
from pathlib import Path

runpy.run_path("scripts/musiclab-lab52-live-60-work-probe.py",run_name="__main__")
path=Path("dist/uab/LAB52_LIVE_60_WORK_REPORT.json")
if not path.is_file(): raise SystemExit("LAB52 compatible live probe not produced")
report=json.loads(path.read_text(encoding="utf-8"))
report["comparison"]="LAB53 publication logic tested separately by JVM against LAB52 quota"
report["device_ui_results"]="NOT_VERIFIED_NO_ANDROID_DEVICE"
report["catalog_recordings_are_not_verified_covers"]=True
report["no_fabricated_covers_policy"]=True
out=Path("dist/uab/LAB53_LIVE_60_SOURCE_COMPARISON.json")
out.write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding="utf-8")
print(f"LAB53: {report['total']} source queries, {report['artist_anchored_works']} anchored works, {report['not_verified']} unverified requests")
