'use strict';
const {rank,key,normalizeTitle}=require('./rank');
const {ApiError}=require('./store');
class RecommendationService {
  constructor(store,metadata,search) {this.store=store;this.metadata=metadata;this.search=search;this.jobs=new Map();this.lastAttempt=new Map();this.tail=Promise.resolve();}
  refresh(profileId) {
    if(this.jobs.has(profileId))return this.jobs.get(profileId);
    if(this.jobs.size>=20)return Promise.resolve();
    const epoch=this.store.profile(profileId).epoch;
    this.lastAttempt.set(profileId,Date.now());while(this.lastAttempt.size>100)this.lastAttempt.delete(this.lastAttempt.keys().next().value);
    const job=this.tail.then(()=>this.build(profileId,epoch)).catch(()=>{/* Keep last-good page; never log provider secrets. */})
      .finally(()=>this.jobs.delete(profileId));
    this.tail=job;this.jobs.set(profileId,job);return job;
  }
  async build(profileId,epoch) {
    if(!this.metadata.enabled)return;
    const raw=this.store.seeds(profileId);if(!raw.length)return;
    const seeds=[];const seedKeys=new Set();
    for(const s of raw){const resolved=await this.metadata.identify(s);if(resolved){seeds.push({...s,...resolved});
      if(this.store.profile(profileId).epoch!==epoch)return;
      this.store.db.prepare('UPDATE sessions SET tmdb=? WHERE profile=? AND source=? AND kind=?').run(resolved.tmdbId,profileId,s.sourceId,s.kind);}}
    for(let i=seeds.length-1;i>=0;i--){if(seedKeys.has(key(seeds[i])))seeds.splice(i,1);else seedKeys.add(key(seeds[i]));}
    if(!seeds.length)return;
    const all=new Map();
    for(const seed of seeds){const related=await this.metadata.related(seed);for(let i=0;i<related.length;i++){
      const r=related[i],k=key(r);if(!all.has(k)&&all.size>=240)continue;
      const c=all.get(k)||{...r,related:{}};c.related[key(seed)]=1/Math.sqrt(i+1);all.set(k,c);
    }}
    const candidates=[...all.values()];
    // Enrich bounded high-priority candidates; others still have honest genre/related evidence.
    for(let i=0;i<Math.min(60,candidates.length);i+=2){const enriched=await Promise.all(candidates.slice(i,i+2).map(c=>this.metadata.details(c)));enriched.forEach((c,j)=>candidates[i+j]=c);}
    const ranked=rank(seeds,candidates).filter(c=>!this.store.seen(profileId,c.kind,c.tmdbId,c.title,c.year));
    let searches=0;
    for(const c of ranked){let online=this.store.cached('listing:'+key(c));
      if(online===undefined&&searches<8){searches++;const rows=await this.search(c.title);const matches=rows.filter(r=>r.kind===c.kind&&normalizeTitle(r.name)===normalizeTitle(c.title)&&(!c.year||!r.year||Number(r.year)===c.year));
        const match=matches.length===1?matches[0]:null;online=match?{id:`gd:${c.kind}:${match.id}`} : null;
        this.store.cache('listing:'+key(c),online,online?86400000:3600000);}
      c.onlineId=online?.id||null; delete c.related;
    }
    this.store.saveRanking(profileId,epoch,ranked);
  }
  page(profileId,cursor,limit=20) {
    const p=this.store.profile(profileId);const saved=this.store.ranking(profileId);
    if(!this.metadata.enabled)return {state:'unavailable',reason:'metadata_not_configured',epoch:p.epoch,items:[],retryAfterS:60};
    if(!this.store.seeds(profileId).length)return {state:'cold_start',epoch:p.epoch,items:[],retryAfterS:60};
    let offset=0;
    if(cursor){try{const c=JSON.parse(Buffer.from(cursor,'base64url').toString());if(!saved||c.profileId!==profileId||c.epoch!==p.epoch||c.revision!==saved.revision||!Number.isSafeInteger(c.offset)||c.offset<0||c.offset>240)throw Error();offset=c.offset;}catch{throw new ApiError(409,'stale recommendation cursor');}}
    if(!cursor&&(!saved||saved.generatedAt<Date.now()-6*3600000)&&Date.now()-(this.lastAttempt.get(profileId)||0)>60000)void this.refresh(profileId);
    if(!saved)return {state:'pending',epoch:p.epoch,items:[],retryAfterS:15};
    const end=offset+Math.max(1,Math.min(20,limit));
    const invalidated=saved.generatedAt===0;
    return {state:this.jobs.has(profileId)||invalidated?'refreshing':'ready',epoch:p.epoch,generatedAt:saved.generatedAt,revision:saved.revision,
      retryAfterS:invalidated?Math.max(15,Math.ceil((60000-(Date.now()-(this.lastAttempt.get(profileId)||0)))/1000)):15,
      items:saved.items.slice(offset,end).filter(c=>!this.store.seen(profileId,c.kind,c.tmdbId,c.title,c.year)),nextCursor:end<saved.items.length?Buffer.from(JSON.stringify({profileId,epoch:p.epoch,revision:saved.revision,offset:end})).toString('base64url'):null};
  }
}
module.exports={RecommendationService};
