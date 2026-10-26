import test from 'node:test';
import assert from 'node:assert/strict';
import { createPool, memoryCache } from './pool.mjs';
import { withMetadataCache } from './cache.mjs';

test('bounded pool handles parallel requests, empty responses and closure', async () => {
 const pool=createPool(2);
 try {
  const results=await Promise.all(Array.from({length:12},()=>pool.fetch(new Request('https://addon.example/monochrome/manifest.json'))));
  for(const result of results) {assert.equal(result.status,200);assert.equal((await result.json()).id,'org.opentune.monochrome');}
  assert.equal((await pool.fetch(new Request('https://addon.example/',{method:'OPTIONS'}))).status,204);
 } finally {await pool.close();}
 assert.equal((await pool.fetch(new Request('https://addon.example/health'))).status,503);
});
test('shared metadata cache serves successful lookups across workers',async()=>{
 let calls=0; const store=memoryCache(1);
 const handle=withMetadataCache(async()=>{calls++;return Response.json({tracks:[]});},{cache:()=>store});
 await handle(new Request('https://addon.example/search?q=a'));
 await handle(new Request('https://addon.example/search?q=a'));
 assert.equal(calls,1);
 await handle(new Request('https://addon.example/search?q=b'));
 await handle(new Request('https://addon.example/search?q=a'));
 assert.equal(calls,3);
});
