import test from 'node:test';
import assert from 'node:assert/strict';
import {validateCatalog,readCatalogBody} from './addons.mjs';
import worker from './worker.mjs';
test('catalog rejects private endpoints, duplicates and oversized lists',()=>{
  const entry={id:'one',name:'Community source',url:'https://example.com/manifest.json'};
  assert.equal(validateCatalog({sources:[entry]})[0].name,entry.name);
  for(const url of ['http://example.com','https://127.0.0.1','https://192.168.1.1','https://name:password@example.com','https://service.local']) assert.throws(()=>validateCatalog({sources:[{...entry,url}]}));
  assert.throws(()=>validateCatalog({sources:[entry,entry]}));
  assert.throws(()=>validateCatalog({sources:Array(21).fill(entry)}));
});
test('unauthenticated catalog writes cannot change the database',async()=>{
  let touched=false;
  const response=await worker.fetch(new Request('https://owner.example/v1/addons',{method:'POST',headers:{'Content-Type':'application/json'},body:'{"revision":0,"sources":[]}'}),{DB:{prepare(){touched=true;throw Error('must not run');}}});
  assert.equal(response.status,401);assert.equal(touched,false);
});
test('only catalog reads are public',async()=>{
  const db={prepare(){return{first:async()=>({payload:'{"revision":1,"sources":[]}'})}}};
  assert.equal((await worker.fetch(new Request('https://owner.example/v1/addons'),{DB:db})).status,200);
  assert.equal((await worker.fetch(new Request('https://owner.example/v1/summary'),{DB:db})).status,401);
});
test('catalog body is bounded even without content length',async()=>{
  const request=new Request('https://owner.example/v1/addons',{method:'POST',headers:{'Content-Type':'application/json'},body:'x'.repeat(60001)});
  await assert.rejects(readCatalogBody(request),/too large/);
});
test('a source can be turned off and stays listed',()=>{
  const entry={id:'one',name:'Community source',url:'https://example.com/manifest.json'};
  assert.equal(validateCatalog({sources:[entry]})[0].enabled,true);
  assert.equal(validateCatalog({sources:[{...entry,enabled:false}]})[0].enabled,false);
  assert.throws(()=>validateCatalog({sources:[{...entry,enabled:'no'}]}));
});
test('catalog reads are remembered briefly',async()=>{
  let reads=0;const payload={revision:1,sources:[]};
  const db={prepare(q){return{first:async()=>{reads++;return{payload:JSON.stringify(payload)};}};}};
  await worker.fetch(new Request('https://owner.example/v1/addons'),{DB:db});
  await worker.fetch(new Request('https://owner.example/v1/addons'),{DB:db});
  assert.equal(reads,1);
});
