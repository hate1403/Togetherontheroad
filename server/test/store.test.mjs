import { test } from 'node:test';
import assert from 'node:assert/strict';
import { Store } from '../src/store.mjs';
const setup = () => {
  let now=100000;
  const store=new Store({now:()=>now,ttlMs:3600000});
  const a=store.create('A',true), b=store.join(a.sessionId,a.inviteCode,'B',true);
  const write=(m,seq=1,extra={})=>store.telemetry(m.sessionId,m.token,{seq,source:'REPLAY',latitude:37.5,longitude:127,etaMinutes:m===a?150:158,observedAt:now,...extra});
  return {store,a,b,write,advance:ms=>now+=ms};
};
test('consent, invitation and membership gate access',()=>{
  const {store,a}=setup();
  assert.throws(()=>store.create('X',false));
  assert.throws(()=>store.join(a.sessionId,'wrong','X',true));
  assert.throws(()=>store.snapshot(a.sessionId,'wrong'));
});
test('reject stale, nonfinite, future, duplicate and real-source data',()=>{
  const {store,a,write}=setup();write(a);
  for(const extra of [{seq:1},{seq:2,latitude:NaN},{seq:2,longitude:181},{seq:2,observedAt:1},{seq:2,observedAt:200000},{seq:2,source:'SDK'}]) assert.throws(()=>write(a,2,extra));
  assert.equal(store.snapshot(a.sessionId,a.token).members[0].telemetry.seq,1);
});
test('only approving member can report a result; callbacks are required',()=>{
  const {store,a,b,write}=setup();write(a);write(b);const p=store.propose(a.sessionId,a.token);
  assert.throws(()=>store.result(a.sessionId,a.token,p.id,'APPLIED'));
  store.approve(a.sessionId,a.token,p.id);
  assert.equal(store.snapshot(a.sessionId,b.token).proposal.states[a.memberId],'APPLYING');
  assert.equal(store.snapshot(a.sessionId,b.token).proposal.states[b.memberId],'PENDING');
  assert.throws(()=>store.result(b.sessionId,b.token,p.id,'APPLIED'));
  store.result(a.sessionId,a.token,p.id,'FAILED');
  assert.throws(()=>store.result(a.sessionId,a.token,p.id,'APPLIED'));
});
test('new proposal, expired approval and stale positions cannot execute',()=>{
  const {store,a,b,write,advance}=setup();write(a);write(b);const p=store.propose(a.sessionId,a.token);
  const p2=store.propose(a.sessionId,a.token);assert.notEqual(p.id,p2.id);
  assert.throws(()=>store.approve(a.sessionId,a.token,p.id));
  advance(120001);assert.throws(()=>store.approve(a.sessionId,a.token,p2.id));
  assert.throws(()=>store.propose(a.sessionId,a.token));
});
test('leave erases coordinates, credentials and proposals for remaining members',()=>{
  const {store,a,b,write}=setup();write(a);write(b);store.propose(a.sessionId,a.token);store.leave(a.sessionId,a.token);
  assert.throws(()=>store.snapshot(a.sessionId,a.token));assert.throws(()=>write(a,2));
  const snap=store.snapshot(b.sessionId,b.token);assert.equal(snap.members.length,1);assert.equal(snap.proposal,null);
  assert.ok(!JSON.stringify(snap).includes(a.token));assert.ok(!JSON.stringify(snap).includes(a.memberId));
});
test('end and TTL remove session and all location data',()=>{
  const {store,a,b,write,advance}=setup();write(a);write(b);store.end(a.sessionId,a.token);
  assert.equal(store.sessions.size,0);assert.throws(()=>store.snapshot(b.sessionId,b.token));
  const c=store.create('C',true);advance(3600001);store.prune();assert.equal(store.sessions.size,0);assert.throws(()=>store.snapshot(c.sessionId,c.token));
});
test('snapshots never leak tokens or invitation codes',()=>{
  const {store,a,b}=setup();const text=JSON.stringify(store.snapshot(a.sessionId,b.token));
  for(const secret of [a.token,b.token,a.inviteCode])assert.ok(!text.includes(secret));
});

test('invalid creation leaves no orphan session',()=>{
  const store=new Store();assert.throws(()=>store.create('',true));assert.equal(store.sessions.size,0);
});

test('B delay changes optimal fixture for three members',()=>{
  const {store,a,b,write}=setup();const c=store.join(a.sessionId,a.inviteCode,'C',true);
  write(a);write(b);write(c,1,{etaMinutes:155});
  assert.equal(store.propose(a.sessionId,a.token).candidate.id,'demo-1');
  write(b,2,{etaMinutes:183});assert.equal(store.propose(a.sessionId,a.token).candidate.id,'demo-2');
});
