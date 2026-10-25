import { randomBytes, randomUUID } from 'node:crypto';
import { validPoint,metersBetween } from './routing.mjs';
import { Experience } from './experience.mjs';
export class ApiError extends Error { constructor(status,message){super(message);this.status=status;} }
const must=(ok,status,message)=>{if(!ok)throw new ApiError(status,message)};
const label=s=>typeof s==='string'&&s.trim().length>0&&s.length<=70&&!/[\u0000-\u001f]/.test(s);
const place=p=>validPoint(p)&&label(p.name);
const types=new Set(['REST','LATE','READY','ARRIVED']);
export class Store {
  constructor({now=Date.now,ttlMs=4*3600000,router=null}={}){this.now=now;this.ttlMs=ttlMs;this.router=router;this.sessions=new Map();this.experience=new Experience(this,ApiError);}
  prune(){this.router?.prune?.();for(const[id,s]of this.sessions)if(s.expiresAt<=this.now())this.sessions.delete(id);}
  get(id){this.prune();const s=this.sessions.get(id);must(s,404,'Session unavailable');return s;}
  create(name,consent){
    must(consent===true,400,'Explicit sharing consent required');this.prune();must(this.sessions.size<1000,503,'Capacity reached');
    const s={id:randomUUID(),code:randomBytes(4).toString('hex').toUpperCase(),expiresAt:this.now()+this.ttlMs,members:new Map(),revision:0,ownerId:null,destination:null,destinationVersion:0,proposal:null,messages:[]};
    this.sessions.set(s.id,s);try{return this.join(s.id,s.code,name,consent);}catch(e){this.sessions.delete(s.id);throw e;}
  }
  join(id,code,name,consent){
    const s=this.get(id);must(code===s.code,403,'Invalid invitation');must(consent===true,400,'Sharing consent required');must(label(name)&&name.length<=20,400,'Invalid display name');must(s.members.size<8,409,'Up to eight vehicles');
    const m={id:randomUUID(),token:randomBytes(32).toString('hex'),name:name.trim(),telemetry:null,lastSeq:-1,lastSeenAt:this.now(),route:null,routeError:null,routePending:false,nextRouteAttemptAt:0,baselineMeters:null,baselineArrivalAt:null,routeTarget:null,waypoint:null,arrived:false};
    s.members.set(m.id,m);if(!s.ownerId)s.ownerId=m.id;s.revision++;s.proposal=null;return{sessionId:s.id,inviteCode:s.code,memberId:m.id,token:m.token,expiresAt:s.expiresAt};
  }
  joinCode(code,name,consent){this.prune();const s=[...this.sessions.values()].find(s=>s.code===String(code).toUpperCase());must(s,404,'Invitation unavailable');return this.join(s.id,s.code,name,consent);}
  auth(id,token){const s=this.get(id),m=[...s.members.values()].find(m=>m.token===token);must(m,401,'Invalid membership');m.lastSeenAt=this.now();return{s,m};}
  snapshot(id,token){
    const{s,m}=this.auth(id,token),now=this.now();if(s.proposal&&s.proposal.expiresAt<=now&&s.proposal.state!=='EXPIRED'){s.proposal.state='EXPIRED';s.revision++;}
    return{...this.experience.snapshot(s,m),personalDestination:m.routeTarget||s.destination,sessionId:id,revision:s.revision,ownerId:s.ownerId,expiresAt:s.expiresAt,destination:s.destination,destinationVersion:s.destinationVersion,
      members:[...s.members.values()].map(m=>({id:m.id,name:m.name,telemetry:m.telemetry,stale:!m.telemetry||now-m.telemetry.observedAt>30000,online:now-m.lastSeenAt<30000,route:m.route,routeError:m.routeError,routePending:m.routePending,totalDistanceMeters:m.baselineMeters,arrived:m.arrived})),
      proposal:s.proposal,messages:s.messages.filter(x=>now-x.createdAt<600000)};
  }
  telemetry(id,token,t){
    const{s,m}=this.auth(id,token);must(validPoint(t),400,'Invalid coordinates');must(Number.isSafeInteger(t.seq)&&t.seq>m.lastSeq,409,'Out-of-order observation');
    must(['ANDROID_GPS','ANDROID_NETWORK','PLEOS_FUSED'].includes(t.source),400,'Actual location source required');
    must(Number.isSafeInteger(t.observedAt)&&Math.abs(this.now()-t.observedAt)<=30000,400,'Stale or future observation');
    must(t.accuracyMeters===undefined||(Number.isFinite(t.accuracyMeters)&&t.accuracyMeters>=0),400,'Invalid accuracy');
    if(m.waypoint&&metersBetween(t,m.waypoint)<=100){m.waypoint=null;m.route=null;}
    m.lastSeq=t.seq;m.telemetry=Object.fromEntries(['seq','source','latitude','longitude','observedAt','accuracyMeters'].filter(k=>t[k]!==undefined).map(k=>[k,t[k]]));s.revision++;
    this.experience.telemetry(s,m,t);this.refresh(s,m);return{revision:s.revision};
  }
  setDestination(id,token,target){
    const{s,m}=this.auth(id,token);must(s.ownerId===m.id,403,'Only group creator can set the destination');must(place(target),400,'Invalid destination');
    s.destination={name:target.name.trim(),latitude:target.latitude,longitude:target.longitude};s.destinationVersion++;s.proposal=null;
    for(const member of s.members.values()){member.route=null;member.baselineMeters=null;member.baselineArrivalAt=null;member.routeTarget=null;member.waypoint=null;member.arrived=false;this.refresh(s,member,true);}s.revision++;return{destination:s.destination,destinationVersion:s.destinationVersion};
  }
  refresh(s,m,force=false){
    const target=m.routeTarget||s.destination,t=m.telemetry;
    if(!this.router||!target||!t||this.now()-t.observedAt>30000||m.routePending||(!force&&this.now()<m.nextRouteAttemptAt))return;
    if(!force&&m.route&&this.now()-m.route.computedAt<60000&&metersBetween(m.route.origin,t)<250)return;
    const version=s.destinationVersion,waypoint=m.waypoint,location={latitude:t.latitude,longitude:t.longitude},memberId=m.id;
    m.routePending=true;m.routeError=null;
    this.router.route(location,target,waypoint).then(route=>{
      if(!this.sessions.has(s.id)||s.members.get(memberId)!==m||version!==s.destinationVersion||target!==(m.routeTarget||s.destination)||waypoint!==m.waypoint)return;
      m.route={...route,origin:location,target};m.routeError=null;m.nextRouteAttemptAt=0;m.baselineMeters??=route.distanceMeters;
      const arrival=route.computedAt+route.durationSeconds*1000;
      if(m.baselineArrivalAt&&arrival-m.baselineArrivalAt>=300000&&this.now()-(m.lastDelayAt||0)>=300000){this.addMessage(s,m,'DELAY',`예상 도착이 ${Math.round((arrival-m.baselineArrivalAt)/60000)}분 늦어졌습니다`);m.lastDelayAt=this.now();}
      m.baselineArrivalAt??=arrival;s.revision++;
    }).catch(()=>{if(s.members.get(memberId)===m&&version===s.destinationVersion&&target===(m.routeTarget||s.destination)&&waypoint===m.waypoint){m.nextRouteAttemptAt=this.now()+30000;m.routeError='경로를 불러오지 못했습니다. 재시도해 주세요';m.route=null;s.revision++;}})
      .finally(()=>{m.routePending=false;if(this.sessions.has(s.id)&&s.members.get(memberId)===m&&((version!==s.destinationVersion)||target!==(m.routeTarget||s.destination)||waypoint!==m.waypoint))this.refresh(s,m,true);});
  }
  retry(id,token){const{s,m}=this.auth(id,token);this.refresh(s,m,true);return{requested:true};}
  propose(id,token,target,kind='MEETUP'){
    const{s,m}=this.auth(id,token);must(s.destination,409,'Set a destination first');must(place(target),400,'Choose a real place on the map');must(['MEETUP','DESTINATION'].includes(kind),400,'Invalid proposal type');
    s.proposal={id:randomUUID(),kind,candidate:{name:target.name,latitude:target.latitude,longitude:target.longitude},creatorId:m.id,expiresAt:this.now()+180000,state:'VOTING',votes:Object.fromEntries([...s.members.keys()].map(id=>[id,null])),states:Object.fromEntries([...s.members.keys()].map(id=>[id,'PENDING']))};s.revision++;return s.proposal;
  }
  vote(id,token,proposalId,yes){const{s,m}=this.auth(id,token),p=this.proposal(s,proposalId);must(p.state==='VOTING',409,'Voting closed');must(typeof yes==='boolean',400,'Vote must be boolean');p.votes[m.id]=yes;
    if(Object.values(p.votes).some(v=>v===false))p.state='REJECTED';else if(Object.values(p.votes).every(v=>v===true))p.state='ACCEPTED';s.revision++;return p;}
  proposal(s,id){const p=s.proposal;must(p&&p.id===id&&this.now()<p.expiresAt,409,'Proposal expired or replaced');return p;}
  async adopt(id,token,proposalId){
    const{s,m}=this.auth(id,token),p=this.proposal(s,proposalId);
    must(p.state==='ACCEPTED',409,'Group agreement required');must(['PENDING','FAILED'].includes(p.states[m.id]),409,'Already applying');
    must(this.router&&m.telemetry&&this.now()-m.telemetry.observedAt<30000,409,'A current location and router are required');
    p.states[m.id]='APPLYING';s.revision++;
    try{
      const target=p.kind==='DESTINATION'?p.candidate:(m.routeTarget||s.destination);
      const waypoint=p.kind==='MEETUP'?p.candidate:null;
      const origin={latitude:m.telemetry.latitude,longitude:m.telemetry.longitude};
      const route=await this.router.route(origin,target,waypoint);
      must(this.sessions.get(id)===s&&s.members.get(m.id)===m&&s.proposal===p&&this.now()<p.expiresAt,409,'Proposal no longer active');
      if(p.kind==='DESTINATION'){m.routeTarget=p.candidate;m.waypoint=null;}else m.waypoint=p.candidate;
      m.route={...route,origin,target};m.routeError=null;
      m.baselineMeters=route.distanceMeters;m.baselineArrivalAt=route.computedAt+route.durationSeconds*1000;
      p.states[m.id]='APPLIED';s.revision++;return{state:'APPLIED',route:m.route};
    }catch(e){if(s.proposal===p&&s.members.has(m.id)){p.states[m.id]='FAILED';s.revision++;}throw e instanceof ApiError?e:new ApiError(503,'Unable to calculate the proposed road route');}
  }
  addMessage(s,m,kind,text){const msg={id:randomUUID(),memberId:m.id,name:m.name,kind,text,createdAt:this.now()};s.messages.push(msg);s.messages=s.messages.slice(-30);s.revision++;return msg;}
  message(id,token,kind){const{s,m}=this.auth(id,token);must(types.has(kind),400,'Invalid quick message');const text={REST:'잠시 쉬어갈게요',LATE:'조금 늦을 것 같아요',READY:'출발할 준비가 됐어요',ARRIVED:'목적지에 도착했어요'}[kind];if(kind==='ARRIVED'){must(m.route&&m.telemetry&&this.now()-m.telemetry.observedAt<30000&&m.route.distanceMeters<=200&&metersBetween(m.telemetry,m.route.target)<=200,409,'Arrival requires a current location near the destination');m.arrived=true;}m.emojiStatus={REST:"☕",LATE:"⏳",READY:"✅",ARRIVED:"🏁"}[kind];return this.addMessage(s,m,kind,text);}
  leave(id,token){const{s,m}=this.auth(id,token);this.experience.leave(s,m);s.members.delete(m.id);this.router?.clear?.();s.proposal=null;s.messages=s.messages.filter(x=>x.memberId!==m.id);s.revision++;if(!s.members.size)this.sessions.delete(id);else if(s.ownerId===m.id)s.ownerId=s.members.keys().next().value;return{left:true};}
  end(id,token){const{s,m}=this.auth(id,token);must(s.ownerId===m.id,403,'Only creator can end the group');s.members.clear();this.router?.clear?.();s.messages=[];s.proposal=null;this.sessions.delete(id);return{ended:true};}
}
