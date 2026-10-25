#!/usr/bin/env python3
"""LAB54: independent live 'Soli' source evidence, NOT Android on-device UI.
No source candidate is called a published cover without original-work proof.
MusicBrainz public Work API may throttle; errors are explicitly NOT_VERIFIED.
"""
import importlib.util, json, time
from pathlib import Path
p=Path(__file__).with_name("musiclab-lab51-live-work-probe.py")
sp=importlib.util.spec_from_file_location("mb_probe",p)
base=importlib.util.module_from_spec(sp);sp.loader.exec_module(base)
rows=[]
for artist in ("Adriano Celentano","Toto Cutugno"):
    try:
        row=base.probe("IT","Soli",artist)
        row["status"]="OK"
    except Exception as exc:
        row={"title":"Soli","reference_performer":artist,"status":"NOT_VERIFIED",
             "error":type(exc).__name__,"work_anchored":False,"documented_variants":0}
    rows.append(row)
    print(f"Soli / {artist}: {row['status']} work={row['work_anchored']} "
          f"source_performances={row['documented_variants']}",flush=True)
    time.sleep(1.2)
report={
 "case":"Soli - Adriano Celentano",
 "context":"The 68 candidate count is a screenshot observation, not 68 certified covers",
 "independent_reference":"MusicBrainz Work identity & credited alternate performances",
 "original_song_credit_context":["Toto Cutugno","Cristiano Minellono"],
 "sample_count":len(rows),"samples":rows,
 "apk_runtime_UI_cards":"NOT_VERIFIED (GitHub runner cannot access user's Android device)",
 "certified_50_available":"NOT_VERIFIED",
 "must_not_fabricate_covers":True,
}
dest=Path("dist/uab/LAB54_SOLI_LIVE_WORK_PROBE.json")
dest.parent.mkdir(parents=True,exist_ok=True)
dest.write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding="utf-8")
print("Wrote LAB54_SOLI_LIVE_WORK_PROBE.json; no device-visible cover count claimed",flush=True)
