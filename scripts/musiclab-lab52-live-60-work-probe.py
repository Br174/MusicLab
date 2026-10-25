#!/usr/bin/env python3
"""LAB52: real external-source coverage on 60 songs, not on-device cover acceptance.
Uses the existing LAB51 verified-work MusicBrainz probe and respects its
per-request limiter. HTTP failures remain NOT_VERIFIED, never pass.
No tokens, secrets, paid providers or arbitrary video-title certification.
"""
import importlib.util
import json
from datetime import datetime, timezone
from pathlib import Path

base=Path(__file__).with_name("musiclab-lab51-live-work-probe.py")
spec=importlib.util.spec_from_file_location("lab51_work_probe",base)
lab51=importlib.util.module_from_spec(spec)
spec.loader.exec_module(lab51)

MORE=[
    ("IT","La solitudine","Laura Pausini"),
    ("IT","Vita spericolata","Vasco Rossi"),
    ("IT","Felicità","Al Bano"),
    ("IT","L'italiano","Toto Cutugno"),
    ("IT","Sally","Vasco Rossi"),
    ("IT","Ti amo","Umberto Tozzi"),
    ("IT","Bella ciao",""),
    ("IT","Se telefonando","Mina"),
    ("IT","Il mio canto libero","Lucio Battisti"),
    ("IT","Emozioni","Lucio Battisti"),
    ("IT","La leva calcistica della classe '68","Francesco De Gregori"),
    ("IT","Luce (Tramonti a nord est)","Elisa"),
    ("IT","Quando","Pino Daniele"),
    ("IT","Quello che le donne non dicono","Fiorella Mannoia"),
    ("IT","Una carezza in un pugno","Adriano Celentano"),
    ("WORLD","The Sound of Silence","Simon & Garfunkel"),
    ("WORLD","Let It Be","The Beatles"),
    ("WORLD","Creep","Radiohead"),
    ("WORLD","Wonderwall","Oasis"),
    ("WORLD","Hotel California","Eagles"),
    ("WORLD","Blackbird","The Beatles"),
    ("WORLD","Landslide","Fleetwood Mac"),
    ("WORLD","Fast Car","Tracy Chapman"),
    ("WORLD","Tainted Love","Gloria Jones"),
    ("WORLD","I Love Rock 'n' Roll","The Arrows"),
    ("WORLD","Girls Just Want to Have Fun","Robert Hazard"),
    ("WORLD","Valerie","The Zutons"),
    ("WORLD","Torn","Ednaswap"),
    ("WORLD","One","U2"),
    ("WORLD","Bridge over Troubled Water","Simon & Garfunkel"),
]
SONGS=lab51.SONGS+MORE
assert len(SONGS)==60
assert sum(x[0]=="IT" for x in SONGS)==30
assert len({(x[1].casefold(),x[2].casefold()) for x in SONGS})==60

def main():
    rows=[]
    for idx,(region,title,artist) in enumerate(SONGS,1):
        try:
            x=lab51.probe(region,title,artist)
            x["status"]="OK"
        except Exception as error:
            x=dict(region=region,title=title,reference_performer=artist,
                   status="NOT_VERIFIED",error=type(error).__name__,
                   work_anchored=False,documented_variants=0,
                   further_50_potential=0)
        rows.append(x)
        print(f"{idx}/60 {region} {title}: {x['status']} "
              f"work={x['work_anchored']} catalog_variants={x['documented_variants']}",flush=True)

    report=dict(
        probe="LIVE MusicBrainz work/recording source potential; NOT device LAB51-vs-LAB52 output",
        captured_at=datetime.now(timezone.utc).isoformat(),
        total=len(rows),italian=30,international=30,
        verified_network_calls=sum(r["status"]=="OK" for r in rows),
        not_verified=sum(r["status"]!="OK" for r in rows),
        artist_anchored_works=sum(bool(r["work_anchored"]) for r in rows),
        catalog_recordings=sum(r["documented_variants"] for r in rows),
        potential_beyond_first_50=sum(r["further_50_potential"] for r in rows),
        rows=rows,
    )
    out=Path("dist/uab/LAB52_LIVE_60_WORK_REPORT.json")
    out.parent.mkdir(parents=True,exist_ok=True)
    out.write_text(json.dumps(report,indent=2,ensure_ascii=False),encoding="utf-8")
    print(json.dumps({k:v for k,v in report.items() if k!="rows"},ensure_ascii=False))
if __name__=="__main__":main()
