#!/usr/bin/env python3
"""LAB55 live READ-ONLY Cloud smoke test for real historical songs.
Invokes the same public Worker endpoints used by Android; never writes D1,
stores credentials, or publishes personal Cloud record details. Network errors
mean NOT_VERIFIED, never zero catalog entries. The Android device UI still
requires a separate device test.
"""
import json, urllib.request, urllib.error, time, pathlib
HOST="https://musiclab-music-intelligence.brunoverlezza.workers.dev"
WORKS=[("Sapore di sale","Gino Paoli"),("Soli","Adriano Celentano")]
rows=[]
for title,artist in WORKS:
    for path,body in [
        ("/api/v1/archive/search",{"query":title,"limit":100}),
        ("/api/v1/memory/discover",{"title":title,"artist":artist,"mode":"cover","limit":150})
    ]:
        datum={"title":title,"referenceArtist":artist,"endpoint":path,
               "status":"NOT_VERIFIED","results":None,"uniquePerformers":None}
        req=urllib.request.Request(
            HOST+path,
            data=json.dumps(body).encode(),
            headers={"Content-Type":"application/json","x-musiclab-client":"android-lab39",
                     "User-Agent":"MusicLab-LAB55-ReadOnly-QA/1.0"},
            method="POST",
        )
        try:
            with urllib.request.urlopen(req,timeout=11) as resp:
                data=json.load(resp)
                key="items" if path.endswith("archive/search") else "versions"
                variants=data.get(key, [])
                if isinstance(variants,list):
                    distinct=set()
                    for v in variants:
                        if isinstance(v,dict):
                            distinct.add(((v.get("title") or "").casefold().strip(),
                                          (v.get("artist") or "").casefold().strip()))
                    datum.update(status="OK",results=len(variants),
                                 uniquePerformers=len(distinct),
                                 entriesWithWriterCredits=sum(
                                     bool((v.get("credits") or {}).get("composers") or
                                          (v.get("credits") or {}).get("songwriters") or
                                          v.get("creditDetails"))
                                     for v in variants if isinstance(v,dict)))
                else:
                    datum["status"]="UNRECOGNIZED_RESPONSE"
        except Exception as exc:
            datum["errorClass"]=type(exc).__name__
            if isinstance(exc,urllib.error.HTTPError):datum["httpCode"]=exc.code
        print(f"{title}: {path}: {datum['status']} rows={datum['results']}",flush=True)
        rows.append(datum)
        time.sleep(.2)
report={"scope":"public Cloud Worker read-only responses; NOT device-visible covers",
        "cloudDataWasNotModified":True,
        "songs":len(WORKS),"queries":len(rows),"verified":sum(x["status"]=="OK" for x in rows),
        "rows":rows,
        "cannotGuaranteeFiftyAuthenticInterpretations":True}
dest=pathlib.Path("dist/uab/LAB55_CLOUD_READONLY_REPORT.json")
dest.parent.mkdir(parents=True,exist_ok=True)
dest.write_text(json.dumps(report,indent=2,ensure_ascii=False),encoding="utf-8")
print("Report:",json.dumps({k:v for k,v in report.items() if k!="rows"},ensure_ascii=False))
