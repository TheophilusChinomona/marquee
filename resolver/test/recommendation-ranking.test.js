'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const { rank, normalizeTitle, chooseIdentity } = require('../recommendations/rank');
const now = Date.now();
const seed = (tmdbId, genres, keywords, title) => ({kind:'tv',tmdbId,title,genres,keywords,playedMs:1800000,durationMs:2400000,updatedAt:now});
test('legal-drama evidence ranks Suits and preserves a separate romance signal', () => {
  const seeds=[seed(1,[18],['lawyer','courtroom'],'The Good Wife'),seed(2,[18,35],['lawyer','courtroom'],'Boston Legal'),
    {...seed(3,[10749,35],['romance','ghost'],'Just Like Heaven'),kind:'movie'}];
  const candidates=[{kind:'tv',tmdbId:10,title:'Suits',genres:[18],keywords:['lawyer','courtroom'],related:{'tv:1':.9,'tv:2':.9}},
    {kind:'movie',tmdbId:11,title:'Romance',genres:[10749,35],keywords:['romance'],related:{'movie:3':.8}},
    {kind:'movie',tmdbId:12,title:'Action',genres:[28],keywords:['explosion'],related:{}},
    {kind:'tv',tmdbId:1,title:'The Good Wife',genres:[18],keywords:['lawyer']}];
  const out=rank(seeds,candidates,now);
  assert.equal(out[0].title,'Suits'); assert.equal(out[1].title,'Romance');
  assert.match(out[0].reason,/The Good Wife|Boston Legal/);
  assert(!out.some(x=>x.tmdbId===1));
  assert.equal(rank(seeds,[...candidates,candidates[0]],now).length,out.length);
});
test('missing metadata cannot generate invented theme reasons',()=>{
  const out=rank([seed(1,[],[],'Seed')],[{kind:'tv',tmdbId:2,title:'Other',genres:[],keywords:[],related:{'tv:1':.5}}],now);
  assert.match(out[0].reason,/Seed/); assert(!out[0].reason.includes('courtroom'));
});
test('identity rejects remakes/homonyms and accepts Unicode or decorated titles',()=>{
  const rows=[{id:1,title:'Just Like Heaven',release_date:'2005-01-01'},{id:2,title:'Just Like Heaven',release_date:'2024-01-01'}];
  assert.equal(chooseIdentity('movie','Just Like Heaven',2005,rows).id,1);
  assert.equal(chooseIdentity('movie','Just Like Heaven',0,rows),null);
  assert.equal(normalizeTitle('EN| Boston Legal [HD] - 2004'),'boston legal');
  assert.equal(chooseIdentity('tv','東京',0,[{id:3,name:'東京',first_air_date:'2020-01-01'}]).id,3);
  assert.equal(chooseIdentity('tv','Just Like Heaven',0,rows),null);
});
