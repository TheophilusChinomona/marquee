'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const { randomUUID } = require('node:crypto');
const { Store } = require('../recommendations/store');
const { RecommendationService } = require('../recommendations/service');
function setup(t, metadata) {
  const store=new Store(':memory:'); t.after(()=>store.close());
  const deviceId=randomUUID(), p=store.register(deviceId,[{localId:'theo',name:'Theo'}])[0];
  store.batch(deviceId,[{profileId:p.profileId,epoch:0,sessionId:'seed',revision:1,kind:'tv',sourceId:'provider:1',title:'Boston Legal',year:2004,playedMs:1800000,durationMs:2400000,updatedAt:Date.now()}]);
  let calls=0;
  const service=new RecommendationService(store,metadata,async q=>{calls++;return [{id:'/t'+q.length,name:q,year:2020,kind:'tv'}];});
  return {store,service,p,count:()=>calls};
}
test('missing credentials give explicit unavailable state',t=>{
  const {service,p}=setup(t,{enabled:false}); assert.equal(service.page(p.profileId).state,'unavailable');
});
test('refresh is single-flight, bounds source checks and persists results',async t=>{
  const m={enabled:true,identify:async s=>({...s,tmdbId:1,genres:[18],keywords:['lawyer']}),
    related:async()=>Array.from({length:300},(_,i)=>({kind:'tv',tmdbId:i+2,title:'Legal '+i,year:2020,genres:[18],keywords:[],related:{}})),
    details:async s=>({...s,keywords:['lawyer']})};
  const {service,store,p,count}=setup(t,m);
  const a=service.refresh(p.profileId); assert.equal(service.refresh(p.profileId),a); await a;
  assert.equal(count(),8); assert.equal(store.ranking(p.profileId).items.length,240);
  assert.equal(service.page(p.profileId).items.length,20);
});
test('reset while metadata is loading prevents stale job publishing',async t=>{
  let resume; const gate=new Promise(r=>resume=r);
  const {service,store,p}=setup(t,{enabled:true,identify:async s=>{await gate;return {...s,tmdbId:1}},related:async()=>[],details:async s=>s});
  const job=service.refresh(p.profileId);store.reset(p.profileId);resume();await job;
  assert.equal(store.ranking(p.profileId),null);
});
test('metadata outage preserves last good ranking; pages exclude newly watched titles',async t=>{
  const {store,service,p}=setup(t,{enabled:true,identify:async()=>{throw Error('offline')}});
  store.saveRanking(p.profileId,0,[{kind:'tv',tmdbId:2,title:'Boston Legal',year:2004,genres:[],keywords:[],reason:'fixture'}]);
  const revision=store.ranking(p.profileId).revision;
  await service.refresh(p.profileId);
  assert.equal(store.ranking(p.profileId).revision,revision);
  assert.equal(service.page(p.profileId).items.length,0);
});
test('canonical seed updates never overwrite another media kind with the same provider ID',async t=>{
  const {store,service,p}=setup(t,{enabled:true,identify:async s=>({...s,tmdbId:77}),related:async()=>[],details:async s=>s});
  store.db.prepare("INSERT INTO sessions SELECT profile,'movie',1,'movie',source,'Other',2004,0,10000,2400000,0,0,updated,0,'other' FROM sessions LIMIT 1").run();
  await service.refresh(p.profileId);
  assert.equal(store.db.prepare("SELECT tmdb FROM sessions WHERE session='movie'").get().tmdb,0);
});
test('invalidated ranking stays refreshing during cooldown so foreground polling continues',t=>{
  const {store,service,p}=setup(t,{enabled:true});
  store.saveRanking(p.profileId,0,[{kind:'tv',tmdbId:2,title:'Other',year:2020}]);
  store.db.prepare('UPDATE rankings SET generated=0 WHERE profile=?').run(p.profileId);
  service.lastAttempt.set(p.profileId,Date.now());
  const page=service.page(p.profileId);assert.equal(page.state,'refreshing');assert(page.retryAfterS>=15);
});
