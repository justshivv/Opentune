import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { createUsageServer } from './usage-server.mjs';
test('VPS usage persists exact totals and protects dashboard and assets', async()=>{
 const dir=mkdtempSync(join(tmpdir(),'opentune-usage-'));
 try {
  const handle=createUsageServer(dir);
  const payload={installationId:'00000000-0000-4000-8000-000000000001',days:[{day:new Date().toISOString().slice(0,10),version:'1.0.3',plays:4}]};
  const report=()=>new Request('https://addon.example/v1/activity',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(payload)});
  assert.equal((await handle(report())).status,200);
  assert.equal((await handle(report())).status,429);
  const counts=await (await handle(new Request('https://addon.example/v1/public?timeZone=UTC'))).json();
  assert.equal(counts.installations,1);
  assert.equal((await handle(new Request('https://addon.example/usage/v1/summary'))).status,401);
  assert.equal((await handle(new Request('https://addon.example/usage/'))).status,303);
  assert.equal((await handle(new Request('https://addon.example/usage/dashboard.js'))).status,401);
 } finally {rmSync(dir,{recursive:true,force:true});}
});
