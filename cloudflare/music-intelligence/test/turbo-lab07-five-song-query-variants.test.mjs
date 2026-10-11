import test,{before} from 'node:test';
import assert from 'node:assert/strict';
import {performance} from 'node:perf_hooks';
const songs=[
 {title:'Se mi lasci non vale',artist:'Julio Iglesias'},
 {title:'Thriller',artist:'Michael Jackson'},
 {title:'Yesterday',artist:'The Beatles'},
 {title:'Hallelujah',artist:'Leonard Cohen'},
 {title:'Hey!',artist:'Julio Iglesias'},
];
const table=[];
const normalize=x=>x.normalize('NFD').replace(/[\u0300-\u036f]/g,'').toLowerCase().replace(/[^a-z0-9]+/g,'-').replace(/^-|-$/g,'');
async function query(item,variant){
 const term=variant==='legacy'
   ? 'song="'+item.title+'" performer="'+item.artist+'"'
   : 'song:"'+item.title+'" artist:"'+item.artist+'"';
 const url='https://cover.info/en/search?'+new URLSearchParams({find:term,'per-page-songs':'200'});
 const start=performance.now();
 try{
  const res=await fetch(url,{signal:AbortSignal.timeout(4750),headers:{
   'User-Agent':'MusicLab-Catalog-Benchmark/1.0','Accept':'text/html',
  }});
  const html=await res.text();
  const title=normalize(item.title);
  const refs=[...html.matchAll(/href=["']([^"']*\/en\/song\/\d+\/[^"']+)/gi)].map(x=>x[1]);
  const matching=refs.filter(x=>normalize(x).includes(title));
  return {variant,status:res.status,ok:res.ok,ms:Math.round(performance.now()-start),
    songLinkCount:refs.length,matchingTitleLinks:matching.length,bytes:html.length};
 }catch(err){return {variant,ok:false,status:0,ms:Math.round(performance.now()-start),
    matchingTitleLinks:0,error:String(err)};}
}
before(async()=>{
 for(const song of songs){
  const [legacy,documented]=await Promise.all([query(song,'legacy'),query(song,'documented')]);
  table.push({song:song.title,artist:song.artist,legacy,documented});
 }
 console.log('REAL FIVE SONGS x TWO COVER.INFO QUERY STRATEGIES — GITHUB RUNNER',JSON.stringify(table));
});
for(let i=0;i<songs.length;i++){
 test('QUERY '+(i*2+1)+' / '+songs[i].title+': legacy pattern completes call within 5s',()=>{
  const x=table[i].legacy;
  assert.ok(x.ok && x.ms<=5000,JSON.stringify(x));
 });
 test('QUERY '+(i*2+2)+' / '+songs[i].title+': documented song:/artist: syntax yields a song entry',()=>{
  const x=table[i].documented;
  assert.ok(x.ok && x.ms<=5000 && x.matchingTitleLinks>0,JSON.stringify(x));
 });
}
