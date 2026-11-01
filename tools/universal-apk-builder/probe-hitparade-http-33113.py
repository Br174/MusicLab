#!/usr/bin/env python3
"""Probe the exact URLs and HTTP headers that MusicLab uses on Android.
Read-only, no build or source mutation, no requests to user/private data.
"""
import urllib.request, urllib.error, re, html.parser

base="https://www.hitparadeitalia.it/hp_weeks"
patterns=[
 (1959,"1959/index.html","1959/hps19590801.html"),
 (1980,"1980/index.html","1980/hps19800802.html"),
 (1995,"hpw_1995.htm","95/hp950930.htm"),
 (2018,"2018/index.html","2018/hps20180804.html"),
 (2020,"2020/index.html","2020/hps20200801.html"),
 (2026,"2026/index.html","2026/hps20260801.html")
]
uas=[
 ("app","MusicLab-HitParade/1.1"),
 ("browser","Mozilla/5.0 (Linux; Android 11; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/123.0.0.0 Mobile Safari/537.36")
]
class Links(html.parser.HTMLParser):
 def __init__(self): super().__init__(); self.hrefs=[]
 def handle_starttag(self,tag,attrs):
  if tag=="a":
   for key,val in attrs:
    if key=="href" and val:self.hrefs.append(val)
for year,idx,week in patterns:
 for kind,path in (("index",idx),("week",week)):
  for ua_name,ua in uas:
   u=base+"/"+path
   req=urllib.request.Request(u,headers={"User-Agent":ua,"Accept":"text/html,application/xhtml+xml;q=0.9,*/*;q=0.8"})
   try:
    with urllib.request.urlopen(req,timeout=16) as f:
     raw=f.read()
     ct=f.headers.get("Content-Type","")
     ch=re.search(r"charset=([\w-]+)",ct,re.I)
     enc=ch.group(1) if ch else "utf-8"
     payload=raw.decode(enc,errors="replace")
     links=Links();links.feed(payload)
     links_into_year=[x for x in links.hrefs if re.search(r"hps\d{8}\.html|hp\d{6}\.htm",x,re.I)]
     h5=len(re.findall(r"<h5\b",payload,re.I))
     print(f"[HTTP] year={year} kind={kind} agent={ua_name} status={f.status} final={f.url} bytes={len(raw)} charset={enc} links={len(links.hrefs)} weekLinks={len(links_into_year)} h5={h5}")
     print("[HTTP] firsthref="+str(links_into_year[:2]))
     print("[HTTP] head="+repr(payload[:90]))
     if kind=="week" and ua_name=="app" and year in (1959,1980,2018,2026):
      first=re.search(r"<h5[^>]*>\\s*1\\s*</h5>",payload,re.I)
      # Rank may be wrapped by attributes or extra inline tags.
      anyh=re.search(r"<h5\\b[^>]*>",payload,re.I)
      pos=first.start() if first else (anyh.start() if anyh else -1)
      print("[HTML_SHAPE] year="+str(year)+" exactRank="+str(bool(first))+
        " firstRankContext="+repr(payload[max(0,pos-180):pos+620]) if pos>=0 else "[HTML_SHAPE] no h5")
      print("[HTML_SHAPE] li="+str(len(re.findall(r"<li\\b",payload,re.I)))+
        " nextSnippet="+repr(payload[max(0,pos-50):pos+180]))
   except Exception as e:
    print(f"[HTTP] year={year} kind={kind} agent={ua_name} FAILED {type(e).__name__} {str(e)[:200]}")
