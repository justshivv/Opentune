import { Worker, isMainThread, parentPort } from 'node:worker_threads';
import { createAddon } from './worker.mjs';

if (!isMainThread) {
  const handle = createAddon();
  parentPort.on('message', async ({ id, url, method }) => {
    try {
      const response = await handle(new Request(url, { method }));
      parentPort.postMessage({ id, status: response.status, headers: [...response.headers], body: await response.text() });
    } catch { parentPort.postMessage({ id, error: true }); }
  });
}

/** Private worker threads; verification, replay protection and owner state stay in the parent. */
export function createPool(count = 1, { timeoutMs = 45_000, capacity = 64 } = {}) {
  if (!Number.isInteger(count) || count < 1 || count > 4) throw new Error('ADDON_WORKERS must be 1–4');
  let sequence = 0, closed = false;
  const slots = [];
  function spawn(index) {
    const thread = new Worker(new URL(import.meta.url));
    const slot = { thread, pending: new Map(), healthy: true };
    slots[index] = slot;
    thread.on('message', data => {
      const task = slot.pending.get(data.id);
      if (!task) return;
      slot.pending.delete(data.id); clearTimeout(task.timer);
      if (data.error) task.reject(new Error('worker request failed'));
      else task.resolve(new Response([204,205,304].includes(data.status) ? null : data.body, { status: data.status, headers: data.headers }));
    });
    function failed() {
      if (!slot.healthy) return;
      slot.healthy = false;
      for (const task of slot.pending.values()) { clearTimeout(task.timer); task.reject(new Error('worker unavailable')); }
      slot.pending.clear();
      if (!closed) setTimeout(() => { if (!closed) spawn(index); }, 1_000).unref();
    }
    thread.on('error', failed); thread.on('exit', failed);
  }
  for (let i = 0; i < count; i++) spawn(i);
  const busy = () => Response.json({error:'Busy; retry shortly'}, {status:503, headers:{'Retry-After':'5'}});
  return {
    async fetch(request) {
      if (closed || slots.reduce((n, s) => n + s.pending.size, 0) >= capacity) return busy();
      let previous;
      for (let attempt = 0; attempt < 2; attempt++) {
        const slot = slots.filter(s => s.healthy && s !== previous).sort((a,b) => a.pending.size - b.pending.size)[0];
        if (!slot) return busy();
        previous = slot;
        try {
          return await new Promise((resolve, reject) => {
            const id = ++sequence;
            const timer = setTimeout(() => {
              slot.pending.delete(id); reject(new Error('worker timeout'));
              // Terminate hung work, not just its caller, so the bound stays real.
              slot.thread.terminate();
            }, timeoutMs);
            slot.pending.set(id, {resolve, reject, timer});
            slot.thread.postMessage({id, url:request.url, method:request.method});
          });
        } catch { /* One retry on another healthy worker; never relax verification. */ }
      }
      return busy();
    },
    async close() { closed = true; await Promise.all(slots.map(s => s.thread.terminate())); },
  };
}

/** Shared bounded cache across the private workers; stores successful metadata only. */
export function memoryCache(limit = 128) {
  const entries = new Map();
  return {
    async match(request) {
      const hit = entries.get(request.url);
      if (!hit || hit.until <= Date.now()) { entries.delete(request.url); return undefined; }
      return new Response(hit.body, {status:200, headers:hit.headers});
    },
    async put(request, response) {
      const ttl = Number(/max-age=(\d+)/.exec(response.headers.get('Cache-Control'))?.[1] || 0);
      const body = await response.text();
      if (!ttl || body.length > 262_144) return;
      entries.delete(request.url);
      while (entries.size >= limit) entries.delete(entries.keys().next().value);
      entries.set(request.url, {until:Date.now()+ttl*1000, body, headers:[...response.headers]});
    },
  };
}
