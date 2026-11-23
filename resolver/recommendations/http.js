'use strict';
const {ApiError,integer}=require('./store');
async function body(req){
  if(Number(req.headers['content-length']||0)>262144){req.resume();throw new ApiError(413,'request too large');}
  const chunks=[];let size=0;
  for await(const chunk of req){size+=chunk.length;if(size>262144){req.resume();throw new ApiError(413,'request too large');}chunks.push(chunk);}
  try{const value=JSON.parse(Buffer.concat(chunks).toString('utf8'));if(!value||typeof value!=='object'||Array.isArray(value))throw Error();return value;}catch{throw new ApiError(400,'invalid JSON object');}
}
function createRecommendationHandler({store,service,json}){
  return async(req,res,url,path)=>{
    if(!path.startsWith('/v1/'))return false;
    try{
      const method=req.method;
      const expected=path==='/v1/devices/register'||path==='/v1/devices/profiles/link'||path==='/v1/watch/batch'?'POST':/^\/v1\/profiles\/[^/]+\/watch$/.test(path)?'DELETE':'GET';
      if(method!==expected)throw new ApiError(405,'method not allowed');
      if(path==='/v1/devices/register'){const b=await body(req);json(res,200,{mappings:store.register(b.deviceId,b.profiles)});}
      else if(path==='/v1/devices/profiles/link'){const b=await body(req);json(res,200,store.link(b.deviceId,b.localId,b.profileId));}
      else if(path==='/v1/profiles')json(res,200,{profiles:store.profiles()});
      else if(path==='/v1/watch/batch'){const b=await body(req);json(res,200,{accepted:store.batch(b.deviceId,b.updates)});}
      else if(path==='/v1/recommendations'){
        const limit=url.searchParams.has('limit')?Number(url.searchParams.get('limit')):20;integer(limit,'limit',20);if(limit<1)throw new ApiError(400,'invalid limit');
        json(res,200,service.page(url.searchParams.get('profileId'),url.searchParams.get('cursor'),limit));
      }else if(/^\/v1\/profiles\/[^/]+\/watch$/.test(path)){const id=decodeURIComponent(path.split('/')[3]);json(res,200,{profileId:id,epoch:store.reset(id)});}
      else throw new ApiError(404,'not found');
    }catch(e){json(res,e.status||500,{error:e.status?e.message:'recommendation service error',...(e.epoch===undefined?{}:{epoch:e.epoch,profileId:e.profileId})});}
    return true;
  };
}
module.exports={createRecommendationHandler};
