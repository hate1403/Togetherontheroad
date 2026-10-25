import {randomUUID,randomBytes} from 'node:crypto';
import {validPoint,metersBetween} from './routing.mjs';

const AVATARS=['🚗','🚙','🚐','🚕','🚘'];
const STATES=['🚗','☕','⏳','✅'];
// Container metadata is authoritative for short voice clips; do not trust a claimed duration.
function mediaDuration(bytes,mime){
  try{
    if(mime==='audio/wav'){
      let rate=0,length=0;for(let at=12;at+8<=bytes.length;){const size=bytes.readUInt32LE(at+4),type=bytes.toString('ascii',at,at+4);if(at+8+size>bytes.length)return null;if(type==='fmt '&&size>=16)rate=bytes.readUInt32LE(at+16);if(type==='data')length=size;at+=8+size+(size%2);}return rate>0&&length>0?length/rate*1000:null;
    }
    if(mime==='audio/mp4'){
      function boxes(start,end,level=0){for(let at=start;at+8<=end;){let size=bytes.readUInt32BE(at),header=8;const type=bytes.toString('ascii',at+4,at+8);if(size===1){if(at+16>end)return null;size=Number(bytes.readBigUInt64BE(at+8));header=16;}if(size===0)size=end-at;if(size<header||at+size>end)return null;const p=at+header;if(type==='mvhd'){const version=bytes[p];const scale=bytes.readUInt32BE(p+(version===1?20:12));const duration=version===1?Number(bytes.readBigUInt64BE(p+24)):bytes.readUInt32BE(p+16);return scale>0?duration/scale*1000:null;}if(type==='moov'&&level<2){const d=boxes(p,at+size,level+1);if(d)return d;}at+=size;}return null;}return boxes(0,bytes.length);
    }
  }catch{}return null;
}
export class Experience {
  constructor(store,ErrorType){this.store=store;this.ErrorType=ErrorType;}
  require(ok,status,message){if(!ok)throw new this.ErrorType(status,message);}
  data(s){return s.experience??={assets:new Map(),waves:[],voices:[],drops:[],music:null,pairs:[]};}
  trim(s){const d=this.data(s),now=this.store.now();if(d.music?.state==='PLAYING'&&now>=d.music.startAt+d.music.durationMs-d.music.positionMs){d.music.state='ENDED';d.music.positionMs=d.music.durationMs;s.revision++;}d.pairs=d.pairs.filter(x=>x.expiresAt>now);d.waves=d.waves.filter(x=>now-x.createdAt<15000);d.voices=d.voices.filter(x=>now-x.createdAt<600000);
    const used=new Set([d.music?.assetId,...d.voices.map(x=>x.assetId),...d.drops.map(x=>x.assetId)]);
    for(const [id,a]of d.assets)if(!used.has(id)&&now-a.createdAt>600000)d.assets.delete(id);
    return d;
  }
  upload(id,token,bytes,mime,name,use,durationMs){
    const{s,m}=this.store.auth(id,token),d=this.trim(s);
    this.require(['MUSIC','VOICE','MEMORY'].includes(use),400,'Media purpose required');
    const image=['image/jpeg','image/png','image/webp'].includes(mime);
    const audio=['audio/mpeg','audio/mp4','audio/wav'].includes(mime);
    this.require(image||audio,415,'JPEG PNG WebP MP3 M4A WAV only');
    this.require(use==='MEMORY'||audio,415,'Audio required');
    const good=mime==='image/jpeg'?bytes[0]===255&&bytes[1]===216:
      mime==='image/png'?bytes.subarray(0,8).equals(Buffer.from([137,80,78,71,13,10,26,10])):
      mime==='image/webp'?bytes.toString('ascii',0,4)==='RIFF'&&bytes.toString('ascii',8,12)==='WEBP':
      mime==='audio/wav'?bytes.toString('ascii',0,4)==='RIFF'&&bytes.toString('ascii',8,12)==='WAVE':
      mime==='audio/mp4'?bytes.toString('ascii',4,8)==='ftyp':bytes.toString('ascii',0,3)==='ID3'||(bytes[0]===255&&(bytes[1]&224)===224);
    this.require(good,415,'Media signature does not match type');
    this.require(bytes.length>12&&bytes.length<=(use==='MUSIC'?20:5)*1024*1024,413,'Media size limit exceeded');
    if(audio)this.require(Number.isFinite(durationMs)&&durationMs>0&&durationMs<=(use==='VOICE'?20000:1800000),400,'Valid measured duration required');
    if(audio){const actual=mediaDuration(bytes,mime);if(use==='VOICE')this.require(actual&&actual<=20000,400,'Voice must have valid container metadata and be at most twenty seconds');if(actual){this.require(Math.abs(actual-durationMs)<=1000,400,'Audio duration does not match its container');durationMs=Math.round(actual);}}

    let total=0;for(const session of this.store.sessions.values())for(const a of this.data(session).assets.values())total+=a.bytes.length;
    const local=[...d.assets.values()].reduce((n,a)=>n+a.bytes.length,0);
    this.require(total+bytes.length<=128*1024*1024&&local+bytes.length<=64*1024*1024&&d.assets.size<128,507,'Temporary media capacity reached');
    const a={id:randomUUID(),ownerId:m.id,bytes:Buffer.from(bytes),mime,name:String(name||'미디어').replace(/[\u0000-\u001f]/g,'').slice(0,70),use,durationMs:audio?durationMs:null,createdAt:this.store.now()};
    d.assets.set(a.id,a);return{assetId:a.id,name:a.name,mime:a.mime,durationMs:a.durationMs};
  }
  asset(id,token,assetId){const{s,m}=this.store.auth(id,token),d=this.trim(s),a=d.assets.get(assetId);this.require(a,404,'Media unavailable');
    const allowed=a.ownerId===m.id||d.music?.assetId===a.id||d.voices.some(v=>v.assetId===a.id)||d.drops.some(v=>v.assetId===a.id&&v.unlocked.has(m.id));
    this.require(allowed,403,'Arrive at the memory location to unlock media');return a;
  }
  telemetry(s,m,t){const d=this.data(s);
    if(m.trailConsent){m.trail??=[];const last=m.trail.at(-1);if(!last||t.observedAt-last.at>=5000&&metersBetween(last,t)>=15){m.trail.push({latitude:t.latitude,longitude:t.longitude,at:t.observedAt});if(m.trail.length>2000)m.trail.shift();}}
    for(const drop of d.drops){if(drop.unlocked.has(m.id))continue;
      if((t.accuracyMeters===undefined||t.accuracyMeters<=100)&&metersBetween(t,drop.place)<=100){const first=drop.near.get(m.id);if(first&&t.observedAt-first>=2000)drop.unlocked.add(m.id);else if(!first)drop.near.set(m.id,t.observedAt);}
      else drop.near.delete(m.id);
    }
  }
  roles(s){const now=this.store.now(),all=[...s.members.values()],valid=all.filter(m=>m.route&&m.route.target&&now-m.route.computedAt<90000&&m.telemetry&&now-m.telemetry.observedAt<=30000&&s.destination&&metersBetween(m.route.target,s.destination)<10);
    const roles=Object.fromEntries(all.map(m=>[m.id,'비교 대기']));
    if(valid.length===1){roles[valid[0].id]=all.length===1?'단독':'비교 대기';return roles;}
    valid.sort((a,b)=>a.route.distanceMeters-b.route.distanceMeters);
    valid.forEach((m,i)=>{roles[m.id]=valid.at(-1).route.distanceMeters-valid[0].route.distanceMeters<100?'동행':i===0?'선두':i===valid.length-1?'후미':'중간';});return roles;
  }
  snapshot(s,m){const d=this.trim(s),roles=this.roles(s);
    return{serverNow:this.store.now(),experience:{uploads:d.pairs.filter(x=>x.memberId===m.id).map(x=>({id:x.id,code:x.code,use:x.use,used:x.used,expiresAt:x.expiresAt})),music:d.music,waves:d.waves,voices:d.voices,
      profiles:[...s.members.values()].map(x=>({memberId:x.id,avatar:x.avatar||'🚗',status:x.emojiStatus||'🚗',convoy:roles[x.id],trailConsent:!!x.trailConsent,followDestination:x.followDestination||null})),
      trails:[...s.members.values()].filter(x=>x.trailConsent).map(x=>({memberId:x.id,name:x.name,points:x.trail||[]})),
      drops:d.drops.map(x=>({id:x.id,ownerId:x.ownerId,name:x.name,place:x.place,createdAt:x.createdAt,unlocked:x.ownerId===m.id||x.unlocked.has(m.id),...(x.ownerId===m.id||x.unlocked.has(m.id)?{assetId:x.assetId,mime:d.assets.get(x.assetId)?.mime}:{} )}))}};
  }
  async action(id,token,action,b){const{s,m}=this.store.auth(id,token),d=this.trim(s),now=this.store.now();
    const ownAsset=(assetId,use)=>{const a=d.assets.get(assetId);this.require(a&&a.ownerId===m.id&&a.use===use,400,'Upload your own media for this purpose');return a;};
    if(action==='pair-upload'){
      this.require(['MUSIC','MEMORY'].includes(b.use),400,'Music or memory upload required');
      if(b.use==='MEMORY')this.require(validPoint(b.place)&&typeof b.name==='string'&&b.name.length>0&&b.name.length<=70,400,'Choose a memory location');
      d.pairs=d.pairs.filter(x=>x.memberId!==m.id);this.require(d.pairs.length<8,409,'Upload capacity');
      d.pairs.push({id:randomUUID(),memberId:m.id,code:randomBytes(4).toString('hex').toUpperCase(),use:b.use,place:b.place,name:b.name,expiresAt:now+300000,ticket:null,used:false});
    }
    else if(action==='profile'){this.require(AVATARS.includes(b.avatar)&&STATES.includes(b.status),400,'Choose a supported avatar and self-reported state');m.avatar=b.avatar;m.emojiStatus=b.status;}
    else if(action==='wave'){this.require(!m.lastWaveAt||now-m.lastWaveAt>=3000,429,'Wave is cooling down');m.lastWaveAt=now;d.waves.push({id:randomUUID(),memberId:m.id,name:m.name,emoji:'👋',createdAt:now});}
    else if(action==='trail-consent'){this.require(typeof b.enabled==='boolean',400,'Explicit journey recording consent required');m.trailConsent=b.enabled;if(!b.enabled)m.trail=[];}
    else if(action==='voice'){const a=ownAsset(b.assetId,'VOICE');d.voices.push({id:randomUUID(),memberId:m.id,name:m.name,assetId:a.id,durationMs:a.durationMs,createdAt:now});d.voices=d.voices.slice(-20);}
    else if(action==='music-start'){const a=ownAsset(b.assetId,'MUSIC');d.music={id:randomUUID(),assetId:a.id,ownerId:m.id,name:a.name,durationMs:a.durationMs,state:'PLAYING',positionMs:0,startAt:now+5000};}
    else if(action==='music-pause'||action==='music-resume'){const x=d.music;this.require(x,409,'No synchronized track');this.require(x.ownerId===m.id||s.ownerId===m.id,403,'Track sender or group owner controls playback');
      if(action==='music-pause'){x.positionMs=Math.min(x.durationMs,x.positionMs+(x.state==='PLAYING'?Math.max(0,now-x.startAt):0));x.state='PAUSED';}
      else if(x.state!=='PLAYING'){if(x.positionMs>=x.durationMs)x.positionMs=0;x.state='PLAYING';x.startAt=now+3000;}
    }
    else if(action==='drop'){const a=ownAsset(b.assetId,'MEMORY');this.require(validPoint(b.place)&&typeof b.name==='string'&&b.name.trim().length>0&&b.name.length<=70,400,'Select a real memory location and title');this.require(d.drops.length<50,409,'Up to fifty memories per session');d.drops.push({id:randomUUID(),ownerId:m.id,name:b.name.trim(),place:{latitude:b.place.latitude,longitude:b.place.longitude},assetId:a.id,createdAt:now,unlocked:new Set([m.id]),near:new Map()});}
    else if(action==='drop-delete'){const drop=d.drops.find(x=>x.id===b.dropId);this.require(drop&&drop.ownerId===m.id,403,'Only the author can remove a memory');d.drops=d.drops.filter(x=>x!==drop);d.assets.delete(drop.assetId);}
    else if(action==='follow-share'){this.require(validPoint(b.destination)&&typeof b.destination.name==='string'&&b.destination.name.length>0&&b.destination.name.length<=70,400,'A chosen destination is required');m.followDestination={name:b.destination.name,latitude:b.destination.latitude,longitude:b.destination.longitude};}
    else if(action==='follow'||action==='personal-destination'){
      const leader=action==='follow'?s.members.get(b.memberId):null;const target=action==='follow'?leader?.followDestination:b.destination;
      this.require(target&&validPoint(target)&&typeof target.name==='string'&&target.name.length>0&&target.name.length<=70,409,'Friend has not shared a destination');
      this.require(m.telemetry&&now-m.telemetry.observedAt<=30000&&this.store.router,409,'Current location and road routing required');
      const version=s.destinationVersion;const before=m.routeTarget;
      const route=await this.store.router.route(m.telemetry,target);
      this.require(this.store.sessions.get(id)===s&&s.members.get(m.id)===m&&s.destinationVersion===version&&m.routeTarget===before&&(action!=='follow'||s.members.get(leader.id)===leader&&leader.followDestination===target),409,'Destination or membership changed');
      m.routeTarget={...target};m.waypoint=null;m.route={...route,origin:{...m.telemetry},target:m.routeTarget};m.baselineMeters=route.distanceMeters;m.baselineArrivalAt=route.computedAt+route.durationSeconds*1000;
      return{destination:m.routeTarget,route:m.route};
    }
    else this.require(false,404,'Unknown journey action');
    s.revision++;return{ok:true,...this.snapshot(s,m)};
  }
  pair(code,pairCode){
    this.store.prune();for(const s of this.store.sessions.values()){
      if(s.code!==String(code).toUpperCase())continue;const p=this.trim(s).pairs.find(x=>x.code===String(pairCode).toUpperCase()&&!x.ticket&&!x.used);
      this.require(p&&s.members.has(p.memberId),403,'Invalid or expired one-time code');p.ticket=randomBytes(32).toString('hex');return{ticket:p.ticket,use:p.use,expiresAt:p.expiresAt};
    }
    this.require(false,403,'Invalid or expired one-time code');
  }
  pairMember(ticket){
    this.require(typeof ticket==='string'&&ticket.length===64,401,'Upload ticket required');
    for(const s of this.store.sessions.values()){
      if(s.expiresAt<=this.store.now())continue;const p=this.trim(s).pairs.find(x=>x.ticket===ticket&&!x.used);const m=p&&s.members.get(p.memberId);if(m)return{s,p,m};
    }
    this.require(false,401,'Upload ticket expired or revoked');
  }
  async pairedUpload(ticket,bytes,mime,name,duration){
    const{s,p,m}=this.pairMember(ticket);this.require(!p.uploading,409,'Upload already in progress');p.uploading=true;
    try{const a=this.upload(s.id,m.token,bytes,mime,name,p.use,duration);
      const result=await this.action(s.id,m.token,p.use==='MUSIC'?'music-start':'drop',{assetId:a.assetId,name:p.name,place:p.place});
      p.used=true;return{ok:result.ok};
    }finally{p.uploading=false;}
  }
  leave(s,m){const d=this.data(s);d.pairs=d.pairs.filter(x=>x.memberId!==m.id);for(const[id,a]of d.assets)if(a.ownerId===m.id)d.assets.delete(id);d.waves=d.waves.filter(x=>x.memberId!==m.id);d.voices=d.voices.filter(x=>x.memberId!==m.id);d.drops=d.drops.filter(x=>x.ownerId!==m.id);for(const x of d.drops){x.unlocked.delete(m.id);x.near.delete(m.id);}if(d.music?.ownerId===m.id)d.music=null;m.trail=[];m.followDestination=null;}
}
