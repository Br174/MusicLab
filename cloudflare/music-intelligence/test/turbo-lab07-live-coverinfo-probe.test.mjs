import test, {before} from 'node:test';
import assert from 'node:assert/strict';
import {performance} from 'node:perf_hooks';

// This test performs REAL network requests to publicly visible COVER.INFO.
// It measures GitHub-hosted connectivity, NOT Android player timing.
const root='https://cover.info/en/song/393429/Se-mi-lasci-non-vale/Julio-Iglesias';
const search='https://cover.info/en/search?'+new URLSearchParams({
  find:'song="Se mi lasci non vale" performer="Julio Iglesias"',
  'per-page-songs':'200',
});
const results={};
async function probe(url,key){
  const start=performance.now();
  try{
    const response=await fetch(url,{signal:AbortSignal.timeout(4800),headers:{
      'User-Agent':'MusicLab-Live-Cover-Probe/1.0 (+read-only regression test)',
      'Accept':'text/html,application/xhtml+xml',
    }});
    const html=await response.text();
    return results[key]={ok:response.ok,status:response.status,ms:Math.round(performance.now()-start),html};
  }catch(e){
    return results[key]={ok:false,status:0,ms:Math.round(performance.now()-start),html:'',error:String(e)};
  }
}
before(async()=>{
  await Promise.all([probe(root,'page'),probe(search,'search')]);
  console.log('REAL COVER.INFO PROBE (GITHUB RUNNER, NOT ANDROID)',JSON.stringify(
    Object.fromEntries(Object.entries(results).map(([k,v])=>[k,{ok:v.ok,status:v.status,ms:v.ms,bytes:v.html.length,error:v.error}]))));
});
test('LIVE 01 reference song page is publicly reachable',()=>{
 assert.ok(results.page.ok,JSON.stringify(results.page).slice(0,300));
});
test('LIVE 02 song detail HTTP response is received in 5 seconds',()=>{
 assert.ok(results.page.ms<=5000,'real elapsed '+results.page.ms+'ms');
});
test('LIVE 03 returned page identifies Se mi lasci non vale',()=>{
 assert.match(results.page.html,/Se mi lasci non vale/i);
});
test('LIVE 04 returned page identifies Julio Iglesias',()=>{
 assert.match(results.page.html,/Julio Iglesias/i);
});
test('LIVE 05 Luciano Rossi recorded follow-up is present',()=>{
 assert.match(results.page.html,/Luciano Rossi/i);
});
test('LIVE 06 Gil Ventura recorded follow-up is present',()=>{
 assert.match(results.page.html,/Gil Ventura/i);
});
test('LIVE 07 foreign-language adaptation is present',()=>{
 assert.match(results.page.html,/Ajda Pekkan/i);
 assert.match(results.page.html,/Yeniden başlasın|Yeniden ba&#351;las&#305;n|Yeniden baslasin/i);
});
test('LIVE 08 at least five documented 11-char YouTube video IDs are exposed in markup',()=>{
 const ids=['5S_55wVZjXk','7cw4ZkLJCwM','YutV9x8nc38','SsTgKjFYnH8','dss4BGdjZw0'];
 for(const id of ids)assert.ok(results.page.html.includes(id),'missing '+id);
});
test('LIVE 09 application-style exact title+artist search is publicly reachable',()=>{
 assert.ok(results.search.ok,'search status '+results.search.status+': '+results.search.error);
 assert.ok(results.search.ms<=5000,'search took '+results.search.ms+'ms');
});
test('LIVE 10 search endpoint returns a song relation link in its HTML',()=>{
 assert.match(results.search.html,/\/en\/song\/[0-9]+\/Se-mi-lasci-non-vale/i);
});
