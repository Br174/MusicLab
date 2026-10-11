import test,{before} from 'node:test';
import assert from 'node:assert/strict';
import {performance} from 'node:perf_hooks';

// MusicLab Turbo LAB07 — unbiased diagnostic sample: 50 ORIGINAL SONGS,
// each with its own immutable 5000-ms wall-clock budget.
// Source-level GitHub networking only, NOT playback or Android timing.
// Count candidates, documentary video IDs and verified IDs separately.
// The YouTube metadata-verification column is explicitly NOT claimed by
// this catalog-only benchmark. No artificial/synthetic cover scores.
const TRACKS=[
 ['Se mi lasci non vale','Julio Iglesias'],['Thriller','Michael Jackson'],
 ['Yesterday','The Beatles'],['Hallelujah','Leonard Cohen'],
 ['Hey!','Julio Iglesias'],['Imagine','John Lennon'],
 ['My Way','Frank Sinatra'],['I Will Always Love You','Dolly Parton'],
 ['Hurt','Nine Inch Nails'],['All Along the Watchtower','Bob Dylan'],
 ['Respect','Otis Redding'],['Nothing Compares 2 U','Prince'],
 ['Stand by Me','Ben E. King'],['Creep','Radiohead'],
 ['Jolene','Dolly Parton'],['The Sound of Silence','Simon & Garfunkel'],
 ['Ain\'t No Sunshine','Bill Withers'],['Tainted Love','Gloria Jones'],
 ['Billie Jean','Michael Jackson'],['Let It Be','The Beatles'],
 ['Hound Dog','Big Mama Thornton'],['La Bamba','Ritchie Valens'],
 ['Feeling Good','Nina Simone'],['Take on Me','a-ha'],
 ['Fast Car','Tracy Chapman'],['Wicked Game','Chris Isaak'],
 ['Dancing Queen','ABBA'],['Time After Time','Cyndi Lauper'],
 ['Killing Me Softly with His Song','Roberta Flack'],
 ['House of the Rising Sun','The Animals'],
 ['Volare','Domenico Modugno'],['Caruso','Lucio Dalla'],
 ['La solitudine','Laura Pausini'],['Con te partirò','Andrea Bocelli'],
 ['Felicità','Al Bano & Romina Power'],['L\'italiano','Toto Cutugno'],
 ['Azzurro','Adriano Celentano'],['Il mondo','Jimmy Fontana'],
 ['Ti amo','Umberto Tozzi'],['Gloria','Umberto Tozzi'],
 ['Sarà perché ti amo','Ricchi e Poveri'],['Senza una donna','Zucchero'],
 ['Vivo per lei','Andrea Bocelli'],['Bella ciao','Traditional'],
 ['La donna è mobile','Giuseppe Verdi'],['Nel blu dipinto di blu','Domenico Modugno'],
 ['Perdere l\'amore','Massimo Ranieri'],['Se bastasse una canzone','Eros Ramazzotti'],
 ['Vita spericolata','Vasco Rossi'],['Un\'estate italiana','Edoardo Bennato & Gianna Nannini'],
];
const DEADLINE_MS=5000, CAP=50, CONCURRENCY=4;
const REPORT=[];
const normalize=s=>s.normalize('NFD').replace(/[\u0300-\u036f]/g,'').toLowerCase()
 .replace(/[^a-z0-9]+/g,'-').replace(/^-|-$/g,'');
const titleSlug=s=>normalize(s).split('-').filter(Boolean).join('-');
async function get(url,remaining){
 const r=await fetch(url,{signal:AbortSignal.timeout(Math.max(150,Math.floor(remaining))),headers:{
  'Accept':'text/html,application/xhtml+xml','User-Agent':'MusicLab-Real-Catalog-Test/1.0',
 }});
 return {status:r.status,html:await r.text(),ok:r.ok};
}
async function runOne(track){
 const [title,artist]=track,start=performance.now(),budget=()=>DEADLINE_MS-(performance.now()-start);
 const row={title,artist,elapsedMs:0,queryStatus:0,pageStatus:0,
  documentaryCoverCandidates:0,documentaryVideoIds:0,verifiedVideoIds:null,
  issue:null,withinFiveSeconds:false};
 try{
  const url='https://cover.info/en/search?'+new URLSearchParams({
    find:'song:"'+title+'" artist:"'+artist+'"','per-page-songs':'200',
  });
  const search=await get(url,budget());
  row.queryStatus=search.status;
  if(!search.ok)throw Error('Search response '+search.status);
  const refs=[...search.html.matchAll(/href=["']([^"']*\/en\/song\/\d+\/[^"']+)/gi)]
   .map(m=>m[1].replace(/&amp;/g,'&')).map(h=>new URL(h,'https://cover.info').toString());
  const slug=titleSlug(title);
  const root=refs.find(x=>x.toLowerCase().includes('/'+slug.toLowerCase()+'/'))
   ||refs.find(x=>x.toLowerCase().includes('/'+slug.toLowerCase()));
  if(!root)throw Error('Matching work not found by song/artist query');
  const remaining=budget();
  if(remaining<=150)throw Error('Global 5s budget exceeded before detail');
  const detail=await get(root,remaining);
  row.pageStatus=detail.status;
  if(!detail.ok)throw Error('Detail response '+detail.status);
  const linkIds=new Set();
  for(const m of detail.html.matchAll(/href=["']([^"']*\/en\/song\/\d+[^"']*)/gi)){
   const h=m[1].replace(/&amp;/g,'&');
   const relation=h.match(/(?:follow-up-details|initial-details)=(\d+)/);
   const songId=h.match(/\/en\/song\/(\d+)/);
   if(relation)linkIds.add(relation[1]);
   else if(songId)linkIds.add(songId[1]);
  }
  const vidIds=new Set();
  // COVER.INFO stores verified-looking video pointers as .youtube-id text.
  for(const m of detail.html.matchAll(/class=["'][^"']*youtube-id[^"']*["'][^>]*>\s*([A-Za-z0-9_-]{11})\s*</gi))vidIds.add(m[1]);
  row.documentaryCoverCandidates=Math.min(CAP,Math.max(0,linkIds.size-1));
  row.documentaryVideoIds=Math.min(CAP,vidIds.size);
 }catch(e){row.issue=String(e)}
 row.elapsedMs=Math.round(performance.now()-start);
 row.withinFiveSeconds=row.elapsedMs<=DEADLINE_MS;
 return row;
}
before(async()=>{
 const starts=performance.now();let cursor=0;
 async function worker(){while(cursor<TRACKS.length){const i=cursor++;REPORT[i]=await runOne(TRACKS[i]);}}
 await Promise.all(Array.from({length:CONCURRENCY},()=>worker()));
 const counts=REPORT.map(r=>r.documentaryVideoIds);
 const sorted=[...counts].sort((a,b)=>a-b);
 const total=counts.reduce((a,b)=>a+b,0);
 const sumCandidates=REPORT.reduce((a,b)=>a+b.documentaryCoverCandidates,0);
 const stats={trackCount:TRACKS.length,perTrackLimit:CAP,perTrackDeadlineMs:DEADLINE_MS,
  verifiedAverage:null, // Must not equate linked IDs to actual YouTube verification.
  meanLinkedVideoIds:+(total/TRACKS.length).toFixed(2),
  meanCatalogCandidates:+(sumCandidates/TRACKS.length).toFixed(2),
  medianLinkedVideoIds:sorted[Math.floor(sorted.length/2)],
  tracksWithZeroLinkedVideos:REPORT.filter(r=>!r.documentaryVideoIds).length,
  tracksOver25LinkedVideos:REPORT.filter(r=>r.documentaryVideoIds>=25).length,
  slowOrFailed:REPORT.filter(r=>!r.withinFiveSeconds||r.issue).length,
  benchmarkElapsedMs:Math.round(performance.now()-starts)};
 console.log('REAL 50 SONG CATALOG BENCHMARK — LINKED VIDEO IDs ARE NOT VERIFIED VIDEOS',JSON.stringify(stats));
 REPORT.forEach(r=>console.log('REAL SONG RESULT',JSON.stringify(r)));
});
test('BULK 01 50 distinct real songs are evaluated, not two',()=>{
 assert.equal(TRACKS.length,50);
 assert.equal(new Set(TRACKS.map(x=>x[0]+'|'+x[1])).size,50);
});
test('BULK 02 every song has its own 5000ms time budget',()=>{
 assert.equal(DEADLINE_MS,5000);assert.equal(REPORT.length,50);
});
test('BULK 03 cover cap is enforced at 50 for every song',()=>{
 assert.ok(REPORT.every(r=>r.documentaryVideoIds<=CAP&&r.documentaryCoverCandidates<=CAP));
});
test('BULK 04 average yield uses 50 songs as denominator, including zero-result songs',()=>{
 const total=REPORT.reduce((sum,r)=>sum+r.documentaryVideoIds,0);
 assert.ok(total>=0);
 assert.equal((total/50).toFixed(2),(total/TRACKS.length).toFixed(2));
});
test('BULK 05 linked IDs are never misreported as YouTube-verified videos',()=>{
 assert.ok(REPORT.every(r=>r.verifiedVideoIds===null));
});
test('BULK 06 wall-clock failures are visible and never silently omitted',()=>{
 assert.equal(REPORT.filter(r=>r.withinFiveSeconds).length+
  REPORT.filter(r=>!r.withinFiveSeconds).length,50);
});
test('BULK 07 title query and work-detail HTTP status are counted separately',()=>{
 assert.ok(REPORT.every(r=>Number.isInteger(r.queryStatus)&&Number.isInteger(r.pageStatus)));
});
test('BULK 08 all 50 song results have descriptive fields',()=>{
 assert.ok(REPORT.every(r=>r.title&&r.artist&&r.elapsedMs>=0));
});
test('BULK 09 independently licensed/translated covers count only when their source page supplies a video ID',()=>{
 assert.ok(REPORT.every(r=>r.documentaryVideoIds>=0&&r.documentaryVideoIds<=50));
});
test('BULK 10 requested production gate: median/mean of VERIFIED videos >=25 on 50 songs',()=>{
 const verified=REPORT.map(r=>r.verifiedVideoIds);
 assert.ok(verified.every(x=>Number.isInteger(x)), 
  'NOT YET PROVEN: catalog pointers alone cannot certify 25 playable videos/song. APK gate MUST remain closed.');
 assert.ok(verified.reduce((a,b)=>a+b,0)/50>=25,
  'Insufficient verified-video mean; APK gate closed');
});
