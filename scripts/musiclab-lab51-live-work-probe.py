#!/usr/bin/env python3
"""LAB51: independent live MusicBrainz work/recording sample, not APK on-device proof.
Compares discoverable documented performances within first 50 vs later 50
using the same live Work API data; a hit is NOT automatically a verified cover.
No keys, paid services, private user data or YouTube names are queried.
"""
import json
import re
import time
import unicodedata
import urllib.parse
import urllib.request
from pathlib import Path

SONGS = [
    ("IT", "Azzurro", "Adriano Celentano"),
    ("IT", "Caruso", "Lucio Dalla"),
    ("IT", "Volare", "Domenico Modugno"),
    ("IT", "Il cielo in una stanza", "Gino Paoli"),
    ("IT", "La canzone del sole", "Lucio Battisti"),
    ("IT", "La donna cannone", "Francesco De Gregori"),
    ("IT", "Albachiara", "Vasco Rossi"),
    ("IT", "Sapore di sale", "Gino Paoli"),
    ("IT", "Meraviglioso", "Domenico Modugno"),
    ("IT", "La cura", "Franco Battiato"),
    ("IT", "Perdere l'amore", "Massimo Ranieri"),
    ("IT", "Con te partirò", "Andrea Bocelli"),
    ("IT", "Io vagabondo", "Nomadi"),
    ("IT", "Una lunga storia d'amore", "Gino Paoli"),
    ("IT", "L'emozione non ha voce", "Adriano Celentano"),
    ("WORLD", "Yesterday", "The Beatles"),
    ("WORLD", "Imagine", "John Lennon"),
    ("WORLD", "Hallelujah", "Leonard Cohen"),
    ("WORLD", "Jolene", "Dolly Parton"),
    ("WORLD", "Hurt", "Nine Inch Nails"),
    ("WORLD", "Nothing Else Matters", "Metallica"),
    ("WORLD", "I Will Always Love You", "Dolly Parton"),
    ("WORLD", "All Along the Watchtower", "Bob Dylan"),
    ("WORLD", "Respect", "Otis Redding"),
    ("WORLD", "Feeling Good", "Nina Simone"),
    ("WORLD", "My Way", "Frank Sinatra"),
    ("WORLD", "Knockin' on Heaven's Door", "Bob Dylan"),
    ("WORLD", "Stand by Me", "Ben E. King"),
    ("WORLD", "Mad World", "Tears for Fears"),
    ("WORLD", "House of the Rising Sun", "The Animals"),
]
assert len(SONGS) == 30
LAST = [0.0]


def canonical(text):
    folded = unicodedata.normalize("NFKD", text)
    folded = "".join(c for c in folded if not unicodedata.combining(c))
    return re.sub(r"[^a-z0-9 ]+", " ", folded.lower()).strip()


def get_json(path, params):
    interval = time.monotonic() - LAST[0]
    if interval < 1.15:
        time.sleep(1.15 - interval)
    url = "https://musicbrainz.org/ws/2/" + path + "?" + urllib.parse.urlencode(params)
    request = urllib.request.Request(
        url, headers={"User-Agent": "MusicLab-LAB51-Live-Work-QA/1.0 (https://github.com/Br174/MusicLab)", "Accept": "application/json"}
    )
    LAST[0] = time.monotonic()
    with urllib.request.urlopen(request, timeout=12) as response:
        return json.load(response)


def performer_name(recording):
    out = []
    for credit in recording.get("artist-credit", []):
        if isinstance(credit, dict):
            out.append(credit.get("artist", {}).get("name", "") or credit.get("name", ""))
    return " ".join(out)


def probe(country, title, performer):
    row = dict(region=country, title=title, reference_performer=performer,
               work_anchored=False, documented_variants=0, further_50_potential=0)
    query = "work:\"" + title.replace('"', "") + "\""
    works = get_json("work/", {"query": query, "limit": 5, "fmt": "json"}).get("works", [])
    row["search_candidates"] = len(works)
    seen = set()
    for work in works[:3]:
        if canonical(work.get("title", "")) != canonical(title):
            continue
        recordings = get_json("recording", {"work": work["id"], "limit": 100, "offset": 0, "inc": "artist-credits", "fmt": "json"}).get("recordings", [])
        performer_key = canonical(performer)
        anchored = any(performer_key in canonical(performer_name(x)) or
                       canonical(performer_name(x)) in performer_key
                       for x in recordings if canonical(performer_name(x)))
        if not anchored:
            continue
        row["work_anchored"] = True
        row["work_id"] = work["id"]
        for item in recordings:
            if item.get("id"):
                seen.add((canonical(item.get("title", "")), canonical(performer_name(item))))
        if len(recordings) == 100:
            more = get_json("recording", {"work": work["id"], "limit": 100, "offset": 100, "inc": "artist-credits", "fmt": "json"}).get("recordings", [])
            for item in more:
                if item.get("id"):
                    seen.add((canonical(item.get("title", "")), canonical(performer_name(item))))
        break
    # The independent baseline measure is *source potential*, never app output:
    # app candidates still require documentary identity and deduplication gates.
    row["documented_variants"] = len(seen)
    row["first_50_source_window"] = min(50, len(seen))
    row["further_50_potential"] = min(50, max(0, len(seen) - 50))
    return row


def main():
    rows = []
    for index, (region, title, performer) in enumerate(SONGS, 1):
        try:
            result = probe(region, title, performer)
            result["network_status"] = "OK"
        except Exception as exc:
            result = dict(region=region, title=title, reference_performer=performer,
                          network_status="NOT_VERIFIED", error=type(exc).__name__,
                          work_anchored=False, documented_variants=0, further_50_potential=0)
        rows.append(result)
        print(f"{index:02d}/30 {region} {title}: {result['network_status']} "
              f"anchor={result['work_anchored']} variants={result['documented_variants']}", flush=True)
    report = {
        "scope": "live MusicBrainz external source potential, NOT on-device LAB50/LAB51 cover yield",
        "sample_total": len(rows),
        "sample_italian": sum(x["region"] == "IT" for x in rows),
        "sample_international": sum(x["region"] == "WORLD" for x in rows),
        "network_verified": sum(x["network_status"] == "OK" for x in rows),
        "work_anchored": sum(bool(x["work_anchored"]) for x in rows),
        "total_unique_source_performances": sum(x["documented_variants"] for x in rows),
        "later_window_potential": sum(x["further_50_potential"] for x in rows),
        "rows": rows,
    }
    Path("dist/uab").mkdir(parents=True, exist_ok=True)
    Path("dist/uab/LAB51_LIVE_WORK_PROBE.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps({k: v for k, v in report.items() if k != "rows"}, ensure_ascii=False))
    # Provider outages do not invalidate local correctness or the signed APK.
    # NOT_VERIFIED is explicit in the persisted independent source report.


if __name__ == "__main__":
    main()
