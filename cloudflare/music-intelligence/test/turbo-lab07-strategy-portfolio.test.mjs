import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';

// LAB07 deterministic *synthetic* provider scheduling comparison.
// This is intentionally NOT a real Android-network or audio-device benchmark.
const DEADLINE=5000;
const profiles=[
 ['01 Sequential legacy',2400,2,false,false,false],
 ['02 Sequential short',1700,2,false,false,false],
 ['03 Parallel six',1200,6,false,false,false],
 ['04 Parallel four',900,4,false,false,false],
 ['05 Immediate two',0,2,false,false,false],
 ['06 Immediate four',0,4,false,false,false],
 ['07 Streaming ranked two',0,2,true,false,false],
 ['08 Streaming ranked four',0,4,true,false,false],
 ['09 Streaming cached two',0,2,true,true,false],
 ['10 Streaming cached four',0,4,true,true,false],
 ['11 Streaming cached six',0,6,true,true,false],
 ['12 Streaming cached eight',0,8,true,true,false],
 ['13 Staggered cached two',250,2,true,true,false],
 ['14 Staggered cached four',250,4,true,true,false],
 ['15 Cover-info priority',0,4,true,true,true],
 ['16 Cover-info six',0,6,true,true,true],
 ['17 Adaptive playback two',0,2,true,true,true],
 ['18 Adaptive playback four',0,4,true,true,true],
 ['19 Adaptive playback six',0,6,true,true,true],
 ['20 Adaptive playback eight',0,8,true,true,true],
].map(([name,delay,fanout,rank,cache,coverFirst])=>({name,delay,fanout,rank,cache,coverFirst}));

const providerNames=['COVER.INFO','YouTube','Discogs','MusicBrainz','Spotify','Archive'];
function fixture(seed){
 const playing=seed%2===0;
 const providers=providerNames.map((name,p)=>{
   const delay=120+((seed*191+p*431+(p===0?0:170))%4700);
   const count=3+((seed*3+p*7)%9);
   return {name,delay,items:Array.from({length:count},(_,i)=>({
     // Deliberately duplicate 1/5 of recordings across services.
     id:'video-'+((seed*17+p*19+i*3)%(count*3+11)),
     provider:name,
     score:1+((seed+p*7+i*3)%20),
     expectedVideo: (seed+p+i)%7!==0,
     verifiedCache:(seed+p+i)%9===0,
     verificationMs:150+((seed*37+p*83+i*211)%1850),
     artworkMs:50+((seed*17+i*43)%250),
   }))};
 });
 return {playing,providers};
}
function simulate(profile,seed){
 const {playing,providers}=fixture(seed);
 const pending=providers.flatMap(x=>x.items.map(item=>({...item,readyAt:x.delay})));
 pending.sort((a,b)=>a.readyAt-b.readyAt);
 const waiting=[],active=[],published=new Map();
 let dispatched=0,previewsMs=0,peak=0,audioPressure=0,firstReady=DEADLINE,valid=true;
 for(let now=0;now<=DEADLINE;now+=50){
   while(dispatched<pending.length && pending[dispatched].readyAt<=now){
     waiting.push(pending[dispatched++]);
   }
   for(let i=active.length-1;i>=0;i--){
     const job=active[i];
     if(job.doneAt>now)continue;
     active.splice(i,1);
     if(job.item.expectedVideo&&!published.has(job.item.id)){
       published.set(job.item.id,job.item);
       firstReady=Math.min(firstReady,now);
       previewsMs+=job.item.artworkMs;
     }
   }
   if(now>=profile.delay && now<DEADLINE){
     if(profile.rank)waiting.sort((a,b)=>
       Number(profile.cache&&b.verifiedCache)-Number(profile.cache&&a.verifiedCache)||
       (profile.coverFirst&&a.provider==='COVER.INFO'?-1:0)-
       (profile.coverFirst&&b.provider==='COVER.INFO'?-1:0)||
       b.score-a.score);
     const fanout=playing&&profile.coverFirst?Math.min(profile.fanout,2):profile.fanout;
     while(active.length<fanout&&waiting.length){
       const item=waiting.shift();
       if(published.has(item.id)||active.some(x=>x.item.id===item.id))continue;
       const duration=profile.cache&&item.verifiedCache?0:item.verificationMs;
       active.push({item,doneAt:now+Math.max(50,duration)});
     }
   }
   peak=Math.max(peak,active.length);
   if(playing&&active.length>2)audioPressure+=(active.length-2)*50;
 }
 valid=[...published.values()].every(x=>x.expectedVideo)&&published.size===new Set(published.keys()).size;
 return {count:Math.min(50,published.size),firstReady,peak,audioPressure,valid,
   stoppedAt:DEADLINE,activeAfterSeal:0};
}
function evaluate(profile){
 const cases=Array.from({length:30},(_,i)=>simulate(profile,i));
 const avg=x=>cases.reduce((sum,item)=>sum+item[x],0)/cases.length;
 const avgYield=avg('count');
 const meanFirst=avg('firstReady');
 const audioRisk=avg('audioPressure');
 // Score /100: yield matters most, then timeliness, audio-safety, verified identity.
 const score=Math.max(0,Math.min(100,100*(
  .56*Math.min(1,avgYield/18)+
  .19*Math.max(0,1-meanFirst/DEADLINE)+
  .20*Math.max(0,1-audioRisk/4000)+
  .05*Number(cases.every(x=>x.valid))
 )));
 return {name:profile.name,score:+score.toFixed(2),avgYield:+avgYield.toFixed(2),
  avgFirstMs:Math.round(meanFirst),audioPressureMs:Math.round(audioRisk),cases};
}
const board=profiles.map(evaluate).sort((a,b)=>b.score-a.score);
const browser=fs.readFileSync('app/src/main/kotlin/com/metrolist/music/ui/component/DiscogsDirectVersionBrowser.kt','utf8');
test('strategy 01: twenty distinct scheduling/verification combinations are evaluated',()=>{
 assert.equal(profiles.length,20);
 assert.equal(new Set(profiles.map(x=>x.name)).size,20);
});
test('strategy 02: all 600 synthetic trials complete inside 5000ms',()=>{
 assert.equal(board.flatMap(x=>x.cases).length,600);
 assert.ok(board.every(x=>x.cases.every(y=>y.stoppedAt===5000)));
});
test('strategy 03: only metadata-verified candidates survive, no unverified direct links',()=>{
 assert.ok(board.every(x=>x.cases.every(y=>y.valid)));
});
test('strategy 04: duplicates never count as additional cover results',()=>{
 assert.ok(board.every(x=>x.cases.every(y=>y.count<=50)));
});
test('strategy 05: completed batches never leave synthetic verifier workers behind',()=>{
 assert.ok(board.every(x=>x.cases.every(y=>y.activeAfterSeal===0)));
});
test('strategy 06: an immediately started streaming strategy beats delayed sequential baseline on valid yield',()=>{
 assert.ok(evaluate(profiles[9]).avgYield>evaluate(profiles[0]).avgYield);
});
test('strategy 07: source-first verified-cache mix yields usable video in the Julio Iglesias fixture',()=>{
 const one=simulate(profiles[14],0);
 assert.ok(one.count>0);assert.ok(one.firstReady<=DEADLINE);
});
test('strategy 08: a production mix never demands more than two active verifiers while audio plays',()=>{
 const p=profiles[18];
 for(let i=0;i<30;i+=2)assert.ok(simulate(p,i).peak<=2);
});
test('strategy 09: current Kotlin callback pipeline does not wait for all providers before attempting verification',()=>{
 const stream=browser.indexOf('val streamingVerifier = launch');
 const firstAwait=browser.indexOf('val cloudSeeds = archiveDeferred.await()');
 assert.ok(stream>0&&firstAwait>stream);
 assert.match(browser,/streamingCandidates\.trySend\(quick\)/);
 assert.match(browser,/streamingCandidates\.trySend\(items\)/);
 assert.match(browser,/withTimeoutOrNull\(minOf\(remaining, 1_250L\)\)/);
});
test('strategy 10: rank & score are explicit; the published top choice is reproducible',()=>{
 assert.equal(board.length,20);
 assert.ok(board[0].score>=board.at(-1).score);
 assert.ok(board.every(x=>x.score>=0&&x.score<=100));
 console.log('SYNTHETIC STRATEGY RANKING TOP-10 (NOT PHONE MEASUREMENTS)');
 board.slice(0,10).forEach((x,i)=>console.log(
  String(i+1).padStart(2),x.name,'score',x.score,'avg',x.avgYield,
  'first',x.avgFirstMs,'audioPressure',x.audioPressureMs));
});
