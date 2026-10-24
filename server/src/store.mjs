import { randomBytes, randomUUID } from 'node:crypto';
export class ApiError extends Error { constructor(status, message) { super(message); this.status = status; } }
const fail = (ok, status, message) => { if (!ok) throw new ApiError(status, message); };
const allowedResults = new Set(['APPLIED', 'FAILED']);
export class Store {
  constructor({ now = Date.now, ttlMs = 4 * 3600_000 } = {}) { this.now = now; this.ttlMs = ttlMs; this.sessions = new Map(); }
  prune() { for (const [id, s] of this.sessions) if (s.expiresAt <= this.now()) this.sessions.delete(id); }
  get(id) { this.prune(); const s = this.sessions.get(id); fail(s && s.active, 404, 'Session unavailable'); return s; }
  create(name, consent) {
    fail(consent === true, 400, 'Explicit sharing consent required');
    this.prune(); fail(this.sessions.size < 1000, 503, 'Session capacity reached');
    const s = { id: randomUUID(), code: randomBytes(16).toString('hex'), active: true,
      expiresAt: this.now() + this.ttlMs, members: new Map(), revision: 0, proposal: null };
    this.sessions.set(s.id, s);
    try { return this.join(s.id, s.code, name, consent); }
    catch (e) { this.sessions.delete(s.id); throw e; }
  }
  join(id, code, name, consent) {
    const s = this.get(id); fail(code === s.code, 403, 'Invalid invitation');
    fail(consent === true, 400, 'Explicit sharing consent required');
    fail(typeof name === 'string' && /^[A-Za-z0-9가-힣 _-]{1,20}$/u.test(name), 400, 'Invalid display name');
    fail(s.members.size < 3, 409, 'Prototype supports three vehicles');
    const m = { id: randomUUID(), token: randomBytes(32).toString('hex'), name, telemetry: null };
    s.members.set(m.id, m); s.revision++; s.proposal = null;
    return { sessionId: id, inviteCode: s.code, memberId: m.id, token: m.token, expiresAt: s.expiresAt };
  }
  auth(id, token) {
    const s = this.get(id); const m = [...s.members.values()].find(x => x.token === token);
    fail(m, 401, 'Invalid membership'); return { s, m };
  }
  snapshot(id, token) {
    const { s } = this.auth(id, token); const now = this.now();
    return { sessionId: id, revision: s.revision, expiresAt: s.expiresAt, destination: '강릉',
      members: [...s.members.values()].map(m => ({ id: m.id, name: m.name,
        telemetry: m.telemetry, stale: !m.telemetry || now - m.telemetry.observedAt > 15000 })), proposal: s.proposal };
  }
  telemetry(id, token, t) {
    const { s, m } = this.auth(id, token);
    fail(Number.isSafeInteger(t.seq) && t.seq >= 0, 400, 'Invalid sequence');
    fail(t.source === 'REPLAY', 400, 'Only explicit REPLAY telemetry accepted in v0.1');
    fail(Number.isFinite(t.latitude) && Math.abs(t.latitude) <= 90 && Number.isFinite(t.longitude) && Math.abs(t.longitude) <= 180, 400, 'Invalid coordinates');
    fail(Number.isFinite(t.etaMinutes) && t.etaMinutes >= 0 && t.etaMinutes <= 1440, 400, 'Invalid ETA');
    fail(Number.isSafeInteger(t.observedAt) && Math.abs(this.now() - t.observedAt) <= 15000, 400, 'Stale or future observation');
    fail(!m.telemetry || t.seq > m.telemetry.seq, 409, 'Out-of-order observation');
    // Whitelist fields: never retain arbitrary request properties or log coordinates.
    m.telemetry = Object.fromEntries(['seq','source','latitude','longitude','etaMinutes','observedAt'].map(k => [k,t[k]]));
    s.revision++; return { revision: s.revision };
  }
  propose(id, token) {
    const { s } = this.auth(id, token); const ms = [...s.members.values()];
    fail(ms.length >= 2 && ms.every(m => m.telemetry && this.now() - m.telemetry.observedAt <= 15000), 409, 'Two or more fresh vehicles required');
    const candidates = [
      { id: 'demo-1', name: '합류 후보 1 · 가상 휴게소', latitude: 37.48, longitude: 127.75, base: [40,45,43], detours: [4,6,5] },
      { id: 'demo-2', name: '합류 후보 2 · 가상 주차장', latitude: 37.50, longitude: 128.05, base: [58,35,56], detours: [6,4,6] }
    ].map(c => {
      const arrivals = ms.map((m,i) => c.base[i] + Math.max(0,m.telemetry.etaMinutes - [150,158,155][i]));
      return { ...c, arrivals, score: Math.max(...arrivals) + .5*c.detours.slice(0,ms.length).reduce((a,b)=>a+b,0) + .5*(Math.max(...arrivals)-Math.min(...arrivals)), source: 'REPLAY' };
    }).sort((a,b)=>a.score-b.score);
    s.proposal = { id: randomUUID(), expiresAt: this.now()+120000, candidate: candidates[0], candidates,
      states: Object.fromEntries(ms.map(m=>[m.id,'PENDING'])) };
    s.revision++; return s.proposal;
  }
  approve(id, token, proposalId) {
    const { s, m } = this.auth(id, token); const p = s.proposal;
    fail(p && p.id === proposalId && this.now() < p.expiresAt, 409, 'Proposal expired or replaced');
    fail(p.states[m.id] === 'PENDING', 409, 'Approval already consumed');
    p.states[m.id] = 'APPLYING'; s.revision++;
    return { proposalId: p.id, candidate: p.candidate }; // only this authenticated vehicle is authorized
  }
  result(id, token, proposalId, result) {
    const { s, m } = this.auth(id, token); const p = s.proposal;
    fail(p && p.id === proposalId && this.now() < p.expiresAt, 409, 'Proposal expired or replaced');
    fail(allowedResults.has(result) && p.states[m.id] === 'APPLYING', 409, 'No pending application');
    p.states[m.id] = result; s.revision++; return { state: result };
  }
  leave(id, token) {
    const { s, m } = this.auth(id, token); s.members.delete(m.id); s.proposal = null; s.revision++;
    if (!s.members.size) this.sessions.delete(id);
    return { left: true };
  }
  end(id, token) {
    const { s } = this.auth(id, token);
    // Any consenting member may end a temporary trip. All credentials and locations are erased.
    s.members.clear(); s.proposal = null; this.sessions.delete(id); return { ended: true };
  }
}
