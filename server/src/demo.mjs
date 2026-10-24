// Runs a disposable three-client session, then erases it. Start server first.
const base = process.env.SERVER_URL || 'http://127.0.0.1:8787';
async function call(path, body, token) {
  const r=await fetch(base+path,{method:body?'POST':'GET',headers:{'Content-Type':'application/json',...(token?{Authorization:`Bearer ${token}`}:{})},...(body?{body:JSON.stringify(body)}:{})});
  const data=await r.json();if(!r.ok)throw new Error(`HTTP ${r.status}: ${data.error}`);return data;
}
const a=await call('/sessions',{name:'A',consent:true});
const path=`/sessions/${a.sessionId}`;
try {
  const clients=[a];
  for(const name of ['B','C'])clients.push(await call(path+'/join',{name,consent:true,inviteCode:a.inviteCode}));
  async function publish(delay=0,seq=1) {
    for(const [i,c] of clients.entries())await call(path+'/telemetry',{seq,source:'REPLAY',latitude:37.5,longitude:127,etaMinutes:[150,158+delay,155][i],observedAt:Date.now()},c.token);
  }
  await publish();let p=await call(path+'/proposals',{},a.token);
  console.log(`Before delay: ${p.candidate.name} / score ${p.candidate.score}`);
  await publish(25,2);p=await call(path+'/proposals',{},a.token);
  console.log(`B delayed +25min: ${p.candidate.name} / score ${p.candidate.score}`);
  for(const c of clients) {
    await call(path+'/approve',{proposalId:p.id},c.token);
    // This is a REPLAY callback, not a Pleos SDK callback.
    await call(path+'/result',{proposalId:p.id,result:'APPLIED'},c.token);
  }
  const snap=await call(path,undefined,a.token);
  console.log('REPLAY apply states:',Object.values(snap.proposal.states).join(', '));
} finally {
  await call(path+'/end',{},a.token);
  console.log('Session ended; server locations and tokens erased.');
}
