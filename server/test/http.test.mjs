import{test}from'node:test';import assert from'node:assert/strict';import{createServer}from'../src/http.mjs';import{Store}from'../src/store.mjs';
test('three HTTP clients agree, adopt actual provider routes, message and leave',async()=>{
 const router={route:async(from,to)=>({distanceMeters:5000,durationSeconds:400,geometry:{type:'LineString',coordinates:[[from.longitude,from.latitude],[to.longitude,to.latitude]]},provider:'TEST',computedAt:Date.now(),steps:[]})};
 const server=createServer(new Store({router}));await new Promise(r=>server.listen(0,'127.0.0.1',r));const base=`http://127.0.0.1:${server.address().port}`;
 const call=async(path,body,token)=>{const r=await fetch(base+path,{method:body?'POST':'GET',headers:{'Content-Type':'application/json',...(token?{Authorization:`Bearer ${token}`}:{})},...(body?{body:JSON.stringify(body)}:{})});return{status:r.status,body:await r.json()}};
 try{assert.equal((await call('/health')).body.mode,'LIVE');const a=(await call('/sessions',{name:'A',consent:true})).body;const prefix=`/sessions/${a.sessionId}`,clients=[a];for(const name of['B','C'])clients.push((await call('/join',{name,consent:true,inviteCode:a.inviteCode})).body);
 for(const m of clients)assert.equal((await call(prefix+'/telemetry',{seq:1,source:'ANDROID_GPS',latitude:37.4854,longitude:127.0364,observedAt:Date.now()},m.token)).status,200);
 assert.equal((await call(prefix+'/destination',{name:'강릉',latitude:37.7519,longitude:128.8761},a.token)).status,200);
 const p=(await call(prefix+'/proposals',{candidate:{name:'합류 장소',latitude:37.6,longitude:128},kind:'MEETUP'},a.token)).body;
 for(const m of clients)assert.equal((await call(prefix+'/vote',{proposalId:p.id,yes:true},m.token)).status,200);
 for(const m of clients)assert.equal((await call(prefix+'/adopt',{proposalId:p.id},m.token)).status,200);
 assert.ok(Object.values((await call(prefix,undefined,a.token)).body.proposal.states).every(s=>s==='APPLIED'));
 assert.equal((await call(prefix+'/message',{kind:'REST'},clients[1].token)).status,200);assert.equal((await call(prefix+'/leave',{},clients[1].token)).status,200);assert.equal((await call(prefix,undefined,clients[1].token)).status,401);
 assert.equal((await call(prefix+'/end',{},a.token)).status,200);assert.equal((await call(prefix,undefined,clients[2].token)).status,404);
 const invalid=await fetch(base+'/sessions',{method:'POST',headers:{'Content-Type':'application/json'},body:'null'});assert.equal(invalid.status,400);
 }finally{await new Promise(r=>server.close(r))}
});
