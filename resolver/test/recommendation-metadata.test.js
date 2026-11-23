'use strict';
const {test}=require('node:test');const assert=require('node:assert/strict');
const {Store}=require('../recommendations/store');const {Metadata}=require('../recommendations/metadata');
test('hot metadata expires as well as its durable cache',async t=>{
  const store=new Store(':memory:');const originalFetch=global.fetch,originalNow=Date.now;
  t.after(()=>{global.fetch=originalFetch;Date.now=originalNow;store.close();});
  let clock=originalNow(),calls=0;Date.now=()=>clock;
  global.fetch=async()=>new Response(JSON.stringify({n:++calls}),{status:200});
  const m=new Metadata(store,'fixture');await m.get('/test');await m.get('/test');assert.equal(calls,1);
  clock+=8*86400000;await m.get('/test');assert.equal(calls,2);
});
test('metadata HTTP concurrency remains at most two',async t=>{
  const store=new Store(':memory:'),original=global.fetch;t.after(()=>{global.fetch=original;store.close();});
  let active=0,peak=0;global.fetch=async()=>{active++;peak=Math.max(peak,active);await new Promise(r=>setTimeout(r,5));active--;return new Response('{}');};
  const m=new Metadata(store,'fixture');await Promise.all(Array.from({length:8},(_,i)=>m.get('/test'+i)));assert.equal(peak,2);
});
