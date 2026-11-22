'use strict';
const {test,before,after}=require('node:test');
const assert=require('node:assert/strict');
const {fork}=require('node:child_process');
const {mkdtempSync,rmSync}=require('node:fs');
const {join}=require('node:path');const {tmpdir}=require('node:os');const {randomUUID}=require('node:crypto');
let child,root,dir;const deviceId=randomUUID();let profile;
before(async()=>{
  dir=mkdtempSync(join(tmpdir(),'marquee-http-'));
  child=fork(join(__dirname,'../server.js'),[],{silent:true,env:{...process.env,RESOLVER_PORT:'0',RESOLVER_HOST:'127.0.0.1',RESOLVER_TOKEN:'test-key',MARQUEE_DATA_DIR:dir,TMDB_READ_TOKEN:''}});
  await new Promise((resolve,reject)=>{const timer=setTimeout(()=>reject(new Error('server startup timed out')),10000);child.once('message',m=>{clearTimeout(timer);root=`http://127.0.0.1:${m.port}`;resolve();});child.once('exit',code=>reject(new Error('server exited '+code)));});
});
after(async()=>{if(child?.exitCode===null){const done=new Promise(r=>child.once('exit',r));child.kill();await done;}if(dir)rmSync(dir,{recursive:true,force:true});});
async function call(path,method='GET',body){const r=await fetch(root+path,{method,headers:{Authorization:'Bearer test-key','Content-Type':'application/json'},body:body===undefined?undefined:JSON.stringify(body)});return {status:r.status,body:await r.json()};}
test('API stays behind existing authentication gate',async()=>{const r=await fetch(root+'/v1/profiles');assert.equal(r.status,401);});
test('registration, explicit link and profile listing',async()=>{
  const r=await call('/v1/devices/register','POST',{deviceId,profiles:[{localId:'me',name:'Theo'}]});
  assert.equal(r.status,200);profile=r.body.mappings[0];assert.equal((await call('/v1/profiles')).body.profiles.length,1);
  const other=randomUUID();await call('/v1/devices/register','POST',{deviceId:other,profiles:[]});
  assert.equal((await call('/v1/devices/profiles/link','POST',{deviceId:other,localId:'desktop',profileId:profile.profileId})).status,200);
});
test('watch, missing metadata and reset epoch contract',async()=>{
  const u={profileId:profile.profileId,epoch:0,sessionId:'test',revision:1,sourceId:'123',kind:'tv',title:'Boston Legal',year:2004,playedMs:1200000,durationMs:2400000,updatedAt:Date.now()};
  assert.equal((await call('/v1/watch/batch','POST',{deviceId,updates:[u]})).status,200);
  assert.equal((await call('/v1/recommendations?profileId='+profile.profileId)).body.state,'unavailable');
  assert.equal((await call('/v1/profiles/'+profile.profileId+'/watch','DELETE')).body.epoch,1);
  const stale=await call('/v1/watch/batch','POST',{deviceId,updates:[u]});assert.equal(stale.status,409);assert.equal(stale.body.epoch,1);
});
test('invalid JSON, oversized JSON, invalid method and invalid limits',async()=>{
  const r=await fetch(root+'/v1/devices/register',{method:'POST',headers:{Authorization:'Bearer test-key'},body:'{'});assert.equal(r.status,400);
  assert.equal((await call('/v1/devices/register','POST',{padding:'x'.repeat(270000)})).status,413);
  assert.equal((await call('/v1/profiles','POST',{})).status,405);
  assert.equal((await call('/v1/recommendations?profileId='+profile.profileId+'&limit=no')).status,400);
});
