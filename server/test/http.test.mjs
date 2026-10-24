import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createServer } from '../src/http.mjs';
test('HTTP trip across three independent clients and consent revocation',async()=>{
  const server=createServer();await new Promise(r=>server.listen(0,'127.0.0.1',r));
  const base=`http://127.0.0.1:${server.address().port}`;
  const call=async(path,body,token)=>{
    const res=await fetch(base+path,{method:body?'POST':'GET',headers:{'Content-Type':'application/json',...(token?{Authorization:`Bearer ${token}`}:{})},...(body?{body:JSON.stringify(body)}:{})});
    return {status:res.status,body:await res.json()};
  };
  try {
    assert.equal((await call('/health')).status,200);
    const a=(await call('/sessions',{name:'A',consent:true})).body;
    const prefix=`/sessions/${a.sessionId}`;
    const join=async name=>(await call(prefix+'/join',{name,consent:true,inviteCode:a.inviteCode})).body;
    const b=await join('B'),c=await join('C');
    assert.equal((await call(prefix+'/join',{name:'D',consent:true,inviteCode:a.inviteCode})).status,409);
    for(const [i,m] of [a,b,c].entries()) assert.equal((await call(prefix+'/telemetry',{seq:1,source:'REPLAY',latitude:37,longitude:127,etaMinutes:[150,158,155][i],observedAt:Date.now()},m.token)).status,200);
    const p=(await call(prefix+'/proposals',{},a.token)).body;
    for(const m of [a,b,c]) {
      assert.equal((await call(prefix+'/approve',{proposalId:p.id},m.token)).status,200);
      assert.equal((await call(prefix+'/result',{proposalId:p.id,result:'APPLIED'},m.token)).status,200);
    }
    assert.ok(Object.values((await call(prefix,undefined,c.token)).body.proposal.states).every(s=>s==='APPLIED'));
    assert.equal((await call(prefix+'/leave',{},b.token)).status,200);
    assert.equal((await call(prefix,undefined,b.token)).status,401);
    assert.equal((await call(prefix,undefined,a.token)).body.members.length,2);
    assert.equal((await call(prefix+'/end',{},a.token)).status,200);
    assert.equal((await call(prefix,undefined,c.token)).status,404);
    const bad=await fetch(base+'/sessions',{method:'POST',headers:{'Content-Type':'application/json'},body:'null'});assert.equal(bad.status,400);
  } finally { await new Promise(r=>server.close(r)); }
});
