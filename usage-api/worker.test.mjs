import { test } from 'node:test';
import assert from 'node:assert/strict';
import { DatabaseSync } from 'node:sqlite';
import { readFileSync } from 'node:fs';
import worker, { ingest, summary, downloadSql, validateActivity } from './worker.mjs';

function database() {
  const sql = new DatabaseSync(':memory:'); sql.exec(readFileSync(new URL('./schema.sql',import.meta.url),'utf8'));
  return { prepare(query) { return { bind(...args) { return { query,args,run:async()=>sql.prepare(query).run(...args) }; }, query,args:[] }; },
    async batch(statements) {
      sql.exec('BEGIN');
      try { const results=statements.map(s=>({results:sql.prepare(s.query).all(...s.args)})); sql.exec('COMMIT'); return results; }
      catch(e) { sql.exec('ROLLBACK'); throw e; }
    }
  };
}
const id='00000000-0000-4000-8000-000000000001';
const now=new Date('2026-10-09T12:00:00Z');
const body=(plays=0,version='0.4.2')=>({installationId:id,days:[{day:'2026-10-09',version,plays}]});
test('retries, reordering and upgrades preserve one active installation and exact play aggregates',async()=>{
  const db=database();
  await ingest(db,body(4),now); await ingest(db,body(4),now); await ingest(db,body(2),now);
  await ingest(db,body(1,'0.4.3'),now);
  const result=await summary(db,now);
  assert.equal(result.totals.installs,1); assert.equal(result.today.active,1); assert.equal(result.totals.plays,5);
  assert.equal(result.versions.length,2);
});
test('different installations count separately; week/month use distinct IDs across days',async()=>{
  const db=database(); await ingest(db,body(1),now);
  await ingest(db,{installationId:id,days:[{day:'2026-10-08',version:'0.4.2',plays:2}]},now);
  await ingest(db,{...body(3),installationId:id.replace(/1$/,'2')},now);
  const result=await summary(db,now); assert.equal(result.week,2); assert.equal(result.month,2); assert.equal(result.totals.plays,6);
});
test('four downloads from one browser count once per release',async()=>{
  const db=database(); for(let i=0;i<4;i++) await db.prepare(downloadSql).bind(id,'0.4.2',now.toISOString()).run();
  await db.prepare(downloadSql).bind(id,'0.4.3',now.toISOString()).run();
  const result=await summary(db,now); assert.equal(result.downloadBrowsers,1); assert.equal(result.downloads.length,2);
  assert.ok(result.downloads.every(d=>d.browsers===1));
});
test('reject invalid IDs, negative counts, invalid dates and future/backdated batches',()=>{
  for(const change of [{installationId:'phone'}, {days:[{day:'2026-10-10',version:'0.4.2',plays:1}]},
    {days:[{day:'2026-02-30',version:'0.4.2',plays:1}]}, {days:[{day:'2026-10-09',version:'0.4.2',plays:-1}]}])
    assert.throws(()=>validateActivity({...body(),...change},now));
});
test('API rejects foreign origins and oversized bodies without touching database',async()=>{
  const rejected=await worker.fetch(new Request('https://example.test/v1/summary',{headers:{Origin:'https://foreign.test'}}),{});
  assert.equal(rejected.status,403);
  const large=await worker.fetch(new Request('https://example.test/v1/activity',{method:'POST',headers:{'Content-Type':'application/json'},body:' '.repeat(17000)}),{});
  assert.equal(large.status,413);
});
