export const validPoint = p => p && Number.isFinite(p.latitude) && Math.abs(p.latitude)<=85 && Number.isFinite(p.longitude) && Math.abs(p.longitude)<=180;
export function metersBetween(a,b) {
  const rad=x=>x*Math.PI/180,dlat=rad(b.latitude-a.latitude),dlon=rad(b.longitude-a.longitude);
  const h=Math.sin(dlat/2)**2+Math.cos(rad(a.latitude))*Math.cos(rad(b.latitude))*Math.sin(dlon/2)**2;
  return 6371000*2*Math.asin(Math.sqrt(Math.min(1,h)));
}
/** Actual OSRM road routing; never substitutes fabricated distances or ETAs. */
export class RoadRouter {
  constructor({baseUrl=process.env.ROUTING_URL || 'https://router.project-osrm.org',fetcher=fetch,now=Date.now}={}) {
    this.base=baseUrl.replace(/\/$/,'');this.fetcher=fetcher;this.now=now;this.cache=new Map();this.tail=Promise.resolve();this.last=0;this.generation=0;
  }
  prune(){for(const[k,v]of this.cache)if(this.now()-v.computedAt>=60000)this.cache.delete(k);}
  clear(){this.cache.clear();this.generation++;}
  async route(origin,destination,waypoint=null) {
    if(!validPoint(origin)||!validPoint(destination)||(waypoint&&!validPoint(waypoint)))throw new Error('Invalid route coordinates');
    const points=[origin,...(waypoint?[waypoint]:[]),destination];
    const coordinates=points.map(p=>`${p.longitude.toFixed(5)},${p.latitude.toFixed(5)}`).join(';');
    this.prune();const generation=this.generation;
    const key=coordinates,cached=this.cache.get(key);
    if(cached && this.now()-cached.computedAt<60000)return cached;
    const job=this.tail.catch(()=>{}).then(async()=>{
      const wait=Math.max(0,1000-(this.now()-this.last));if(wait)await new Promise(r=>setTimeout(r,wait));this.last=this.now();
      const response=await this.fetcher(`${this.base}/route/v1/driving/${coordinates}?overview=full&geometries=geojson&steps=true`,{
        signal:AbortSignal.timeout(12000),headers:{'User-Agent':'TogetherOnTheRoad/0.3 (+https://github.com/hate1403/Togetherontheroad)'}});
      if(!response.ok)throw new Error(`Routing HTTP ${response.status}`);
      const json=await response.json(),r=json.routes?.[0];
      if(json.code!=='Ok'||!r||!Number.isFinite(r.distance)||!Number.isFinite(r.duration)||r.distance<0||r.duration<0||r.geometry?.type!=='LineString'||!r.geometry.coordinates?.length)throw new Error('No routable road path');
      const result={distanceMeters:r.distance,durationSeconds:r.duration,geometry:r.geometry,
        steps:(r.legs||[]).flatMap(l=>(l.steps||[]).map(s=>({name:s.name||'',distanceMeters:s.distance,durationSeconds:s.duration,type:s.maneuver?.type,modifier:s.maneuver?.modifier,location:s.maneuver?.location}))),
        computedAt:this.now(),provider:'OSRM',trafficAware:false};
      if(generation===this.generation)this.cache.set(key,result);if(this.cache.size>250)this.cache.delete(this.cache.keys().next().value);return result;
    });
    this.tail=job.then(()=>{},()=>{});return job;
  }
}
