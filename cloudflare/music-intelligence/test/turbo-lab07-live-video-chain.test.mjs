import test,{before} from 'node:test';
import assert from 'node:assert/strict';
import {performance} from 'node:perf_hooks';

// REAL provider chain on the GitHub Actions network, never claimed as Android timing.
// The fixture is a public song with seven documented follow-up recordings.
const BASE='https://cover.info';
const SEARCH=BASE+'/en/search?'+new URLSearchParams({
 find:'song="Se mi lasci non vale" performer="Julio Iglesias"',
 'per-page-songs':'200',
});
const FIXTURE_IDS=['5S_55wVZjXk','7cw4ZkLJCwM','YutV9x8nc38','SsTgKjFYnH8','dss4BGdjZw0'];
const DEADLINE=5000;
const state={requestMs:[],videos:[],ids:[],rootUrl:null,error:null,totalMs:0};
async function fetchTimed(url,maxTime=2500){
 const started=performance.now();
 const r=await fetch(url,{
  signal:AbortSignal.timeout(Math.max(250,Math.floor(maxTime))),
  headers:{'Accept':'text/html,application/json','User-Agent':'MusicLab-Cover-Benchmark/1.0'},
 });
 const body=await r.text();
 state.requestMs.push({host:new URL(url).host,status:r.status,ms:Math.round(performance.now()-started)});
 return {status:r.status,ok:r.ok,body};
}
before(async()=>{
 const started=performance.now();
 try{
  const search=await fetchTimed(SEARCH,1800);
  state.searchOk=search.ok;
  const found=search.body.match(/\/en\/song\/393429\/Se-mi-lasci-non-vale\/Julio-Iglesias/i);
  state.rootUrl=found?BASE+found[0]:null;
  if(!state.rootUrl)throw Error('Original song link not found in public search');
  const details=await fetchTimed(state.rootUrl,1600);
  state.pageOk=details.ok;
  state.artistCount=['Luciano Rossi','Gil Ventura','Ajda Pekkan','Yeşim Salkım','Gennaro Cosmo Parlato']
    .filter(x=>details.body.includes(x)).length;
  state.ids=FIXTURE_IDS.filter(x=>details.body.includes(x));
  const remaining=DEADLINE-(performance.now()-started)-150;
  // Metadata probes have the SAME remaining wall-clock as the search
  // rather than separately allowing 5s per video.
  if(remaining>500&&state.ids.length){
    const checks=await Promise.all(state.ids.map(async id=>{
      const url='https://www.youtube.com/oembed?'+new URLSearchParams({
        url:'https://www.youtube.com/watch?v='+id,format:'json',
      });
      const t=performance.now();
      try{
        const r=await fetch(url,{signal:AbortSignal.timeout(Math.max(400,Math.floor(remaining))),headers:{'Accept':'application/json'}});
        const json=r.ok?await r.json():null;
        return {id,ok:r.ok&&!!json?.title,title:json?.title,author:json?.author_name,status:r.status,ms:Math.round(performance.now()-t)};
      }catch(e){return{id,ok:false,ms:Math.round(performance.now()-t),error:String(e)};}
    }));
    state.videos=checks;
  }
 }catch(e){state.error=String(e);}
 state.totalMs=Math.round(performance.now()-started);
 console.log('REAL FIVE-SECOND COVER VIDEO CHAIN - GITHUB RUNNER ONLY',JSON.stringify(state));
});
test('NETWORK 01 exact title and artist catalog search returns HTTP 200',()=>{
 assert.equal(state.searchOk,true,state.error);
});
test('NETWORK 02 exact song is discovered from a generic search link',()=>{
 assert.ok(state.rootUrl?.includes('/en/song/393429/'),state.error);
});
test('NETWORK 03 search stage remains under 1800ms',()=>{
 assert.ok(state.requestMs[0]?.ms<=1800,JSON.stringify(state.requestMs));
});
test('NETWORK 04 relation detail page returns HTTP 200',()=>{
 assert.equal(state.pageOk,true,state.error);
});
test('NETWORK 05 relation detail fetched within its own 1600ms sub-budget',()=>{
 assert.ok(state.requestMs[1]?.ms<=1600,JSON.stringify(state.requestMs));
});
test('NETWORK 06 at least five distinct documented video IDs are visible',()=>{
 assert.ok(new Set(state.ids).size>=5,'ids='+JSON.stringify(state.ids));
});
test('NETWORK 07 at least three distinct other performers exist',()=>{
 assert.ok(state.artistCount>=3,'artists='+state.artistCount);
});
test('NETWORK 08 YouTube validates at least one linked video metadata record',()=>{
 assert.ok(state.videos.some(x=>x.ok),JSON.stringify(state.videos));
});
test('NETWORK 09 at least three linked video IDs pass YouTube metadata validation',()=>{
 assert.ok(state.videos.filter(x=>x.ok).length>=3,JSON.stringify(state.videos));
});
test('NETWORK 10 complete catalog-to-metadata chain finishes within 5000ms',()=>{
 assert.ok(state.totalMs<=5000,'total='+state.totalMs+'ms');
});
