'use strict';
function normalizeTitle(title) {
  return String(title).normalize('NFKD').replace(/\p{M}/gu,'').replace(/^[A-Z]{2,3}\|\s*/,'')
    .replace(/\[(?:MULTI-SUB|MULTI-LANG|MULTI-AUDIO|4K|FHD|HD|SD)\]/gi,'')
    .replace(/\s+-\s+\d{4}\s*$/,'').toLowerCase().replace(/[^\p{L}\p{N}]+/gu,' ').trim();
}
function chooseIdentity(kind,title,year,rows) {
  const matches=rows.filter(r=>normalizeTitle(kind==='tv'?r.name:r.title)===normalizeTitle(title)
    && (!year || Number((kind==='tv'?r.first_air_date:r.release_date)?.slice(0,4))===year));
  return matches.length===1?matches[0]:null;
}
const key=x=>`${x.kind}:${x.tmdbId}`;
function overlap(a=[],b=[]) { if(!a.length||!b.length)return null; const s=new Set(a);return b.filter(x=>s.has(x)).length/Math.sqrt(s.size*new Set(b).size); }
function rank(seeds,candidates,now=Date.now()) {
  const watched=new Set(seeds.map(key));const unique=new Map();
  for(const c of candidates.slice(0,240)) {
    if(watched.has(key(c)))continue;
    let total=0,best=0,reason='';
    for(const s of seeds.slice(0,12)) {
      const rel=c.related?.[key(s)];const kw=overlap(s.keywords,c.keywords);const genre=overlap(s.genres,c.genres);
      let n=0,w=0;
      for(const [value,weight] of [[rel,.45],[kw,.30],[genre,.20]])if(value!==undefined&&value!==null){n+=value*weight;w+=weight;}
      if(!w)continue;
      const strength=s.explicitWatched?1:(s.approximate ? .35:(s.completed?1.5:(s.playedMs>=1200000 || (s.durationMs>0&&s.playedMs/s.durationMs>=.2)?1:.35)));
      const recency=Math.max(.25,Math.pow(.5,Math.max(0,now-s.updatedAt)/(90*86400000)));
      const evidence=n/w*strength*recency;total+=evidence;
      if(evidence>best){best=evidence;reason=`Because you watched ${s.title}`;
        const shared=(c.keywords||[]).filter(k=>(s.keywords||[]).includes(k));
        if(shared.length)reason+=` · ${shared.slice(0,2).join(', ')}`;}
    }
    if(total<=0)continue;
    const quality=(c.rating||0)/10*Math.min(1,(c.votes||0)/500);
    const item={...c,score:total*.95+quality*.05,reason};
    if(!unique.has(key(c))||unique.get(key(c)).score<item.score)unique.set(key(c),item);
  }
  const ordered=[...unique.values()].sort((a,b)=>b.score-a.score||key(a).localeCompare(key(b)));
  const first=[],rest=[],counts=new Map();
  for(const c of ordered){const g=c.genres?.[0]??c.kind;if(first.length<20&&(counts.get(g)||0)<12){first.push(c);counts.set(g,(counts.get(g)||0)+1);}else rest.push(c);}
  while(first.length<20&&rest.length)first.push(rest.shift());
  return [...first,...rest];
}
module.exports={rank,key,normalizeTitle,chooseIdentity};
