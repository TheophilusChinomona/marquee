'use strict';
const { chooseIdentity, normalizeTitle } = require('./rank');
class Metadata {
  constructor(store,token=process.env.TMDB_READ_TOKEN||'') {this.store=store;this.token=token;this.enabled=!!token;this.memory=new Map();this.running=0;this.waiters=[];this.blockedUntil=0;}
  async get(path) {
    const key='tmdb:'+path;const mem=this.memory.get(key);
    const hit=mem&&mem.expiresAt>Date.now()?mem:this.store.cached(key,true);
    if(hit){this.memory.delete(key);this.memory.set(key,hit);while(this.memory.size>200)this.memory.delete(this.memory.keys().next().value);return hit.value;}
    this.memory.delete(key);
    if(!this.enabled)throw new Error('metadata is not configured');
    if(Date.now()<this.blockedUntil)throw new Error('metadata throttled');
    if(this.running>=2)await new Promise(r=>this.waiters.push(r));else this.running++;
    try {
      const r=await fetch('https://api.themoviedb.org/3'+path,{headers:{Authorization:`Bearer ${this.token}`,Accept:'application/json'},signal:AbortSignal.timeout(15000),redirect:'error'});
      if(r.status===429){this.blockedUntil=Date.now()+Math.max(15000,Number(r.headers.get('retry-after')||30)*1000);throw new Error('metadata throttled');}
      if(!r.ok)throw new Error(`metadata HTTP ${r.status}`);
      const chunks=[];let size=0;for await(const chunk of r.body){size+=chunk.length;if(size>1048576)throw new Error('metadata response too large');chunks.push(chunk);}
      const data=JSON.parse(Buffer.concat(chunks).toString('utf8'));this.store.cache(key,data,7*86400000);
      this.memory.set(key,{value:data,expiresAt:Date.now()+7*86400000});while(this.memory.size>200)this.memory.delete(this.memory.keys().next().value);return data;
    } finally {const next=this.waiters.shift();if(next)next();else this.running--;}
  }
  async identify(seed) {
    let id=seed.tmdbId;
    if(!id&&/^fx:(tv|movie):\d+$/.test(seed.sourceId))id=Number(seed.sourceId.split(':')[2]);
    if(!id){const clean=normalizeTitle(seed.title);const r=await this.get(`/search/${seed.kind}?query=${encodeURIComponent(clean)}&include_adult=false`);
      id=chooseIdentity(seed.kind,clean,seed.year,r.results||[])?.id;}
    return id?this.details({...seed,tmdbId:id}):null;
  }
  shape(kind,r) {
    return {kind,tmdbId:r.id,title:kind==='tv'?r.name:r.title,year:Number((kind==='tv'?r.first_air_date:r.release_date)?.slice(0,4))||0,
      poster:r.poster_path?`https://image.tmdb.org/t/p/w342${r.poster_path}`:'',genres:r.genre_ids||(r.genres||[]).map(g=>g.id),
      keywords:(r.keywords?.keywords||r.keywords?.results||[]).map(k=>k.name),rating:r.vote_average||0,votes:r.vote_count||0};
  }
  async details(item){const r=await this.get(`/${item.kind}/${item.tmdbId}?append_to_response=keywords`);return {...item,...this.shape(item.kind,r)};}
  async related(seed){const r=await this.get(`/${seed.kind}/${seed.tmdbId}/recommendations`);return (r.results||[]).filter(x=>!x.adult).slice(0,20).map(x=>this.shape(seed.kind,x));}
}
module.exports={Metadata};
