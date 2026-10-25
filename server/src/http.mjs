import http from 'node:http';
import { fileURLToPath } from 'node:url';
import { resolve } from 'node:path';
import { Store, ApiError } from './store.mjs';
import { RoadRouter,validPoint } from './routing.mjs';
export function createServer(store = new Store({router:new RoadRouter()})) {
  const buckets = new Map();
  const server = http.createServer(async (req,res) => {
    res.setHeader('Cache-Control','no-store'); res.setHeader('X-Content-Type-Options','nosniff');
    res.setHeader('Content-Type','application/json; charset=utf-8');
    const send = (status, value) => { res.writeHead(status); res.end(JSON.stringify(value)); };
    try {
      const now = Date.now(), ip = req.socket.remoteAddress;
      if (buckets.size > 2000) for (const [k,v] of buckets) if (v.reset < now) buckets.delete(k);
      if (!buckets.has(ip) && buckets.size >= 5000) throw new ApiError(503,'Rate limiter capacity reached');
      const bucket = buckets.get(ip) ?? { count: 0, reset: now+60000 };
      if (now > bucket.reset) { bucket.count=0; bucket.reset=now+60000; }
      buckets.set(ip,bucket); if (++bucket.count > 600) throw new ApiError(429,'Rate limit exceeded');
      const path = new URL(req.url,'http://localhost').pathname;
      if (req.method === 'GET' && path === '/health') return send(200,{ok:true,mode:'LIVE',version:'0.3.0'});
      let body = {};
      if (req.method === 'POST') {
        if (!req.headers['content-type']?.startsWith('application/json')) throw new ApiError(415,'JSON required');
        let size=0; const chunks=[];
        for await (const chunk of req) { size+=chunk.length; if (size>8192) throw new ApiError(413,'Body too large'); chunks.push(chunk); }
        const raw=Buffer.concat(chunks).toString('utf8');
        try { body=JSON.parse(raw || '{}'); } catch { throw new ApiError(400,'Invalid JSON'); }
        if (!body || typeof body !== 'object' || Array.isArray(body)) throw new ApiError(400,'JSON object required');
      }
      if(req.method==='POST' && path==='/route') {
        if(!validPoint(body.origin)||!validPoint(body.destination))throw new ApiError(400,'Valid route points required');
        if(!store.router)throw new ApiError(503,'Routing unavailable');
        try{return send(200,await store.router.route(body.origin,body.destination,body.waypoint||null));}
        catch{throw new ApiError(503,'Road routing is unavailable. Try again.');}
      }
      if(req.method==='POST' && path==='/join')return send(200,store.joinCode(body.inviteCode,body.name,body.consent));
      if (req.method==='POST' && path==='/sessions') return send(201,store.create(body.name,body.consent));
      const match=path.match(/^\/sessions\/([0-9a-f-]+)(?:\/(join|telemetry|destination|proposals|vote|adopt|retry|message|leave|end))?$/);
      if (!match) throw new ApiError(404,'Not found');
      const [,id,action]=match; const token=(req.headers.authorization ?? '').replace(/^Bearer /,'');
      if (req.method==='GET' && !action) return send(200,store.snapshot(id,token));
      if (req.method!=='POST') throw new ApiError(405,'Method not allowed');
      const handlers={
        join:()=>store.join(id,body.inviteCode,body.name,body.consent),
        telemetry:()=>store.telemetry(id,token,body), destination:()=>store.setDestination(id,token,body),
        proposals:()=>store.propose(id,token,body.candidate,body.kind), vote:()=>store.vote(id,token,body.proposalId,body.yes),
        retry:()=>store.retry(id,token), message:()=>store.message(id,token,body.kind),
        adopt:()=>store.adopt(id,token,body.proposalId),
        leave:()=>store.leave(id,token), end:()=>store.end(id,token)
      };
      if (!handlers[action]) throw new ApiError(404,'Not found');
      send(200,await handlers[action]());
    } catch(e) { send(e instanceof ApiError ? e.status : 500,{error:e instanceof ApiError ? e.message : 'Internal error'}); }
  });
  // No telemetry is persisted. Prune idle sessions without requiring another request.
  const timer=setInterval(()=>store.prune(),60000); timer.unref();
  server.on('close',()=>clearInterval(timer)); server.requestTimeout=10000; server.headersTimeout=10000;
  return server;
}
if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const host=process.env.HOST || '127.0.0.1', port=Number(process.env.PORT || 8787);
  createServer().listen(port,host,()=>console.log(`Together on the Road live server http://${host}:${port}`));
}
