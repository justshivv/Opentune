/** Smoke-test the extracted ZIP, not the source checkout. No provider calls. */
import { spawn, execFileSync } from 'node:child_process';
import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import assert from 'node:assert/strict';
const archive=resolve(process.argv[2] || 'hostinger-package/opentune-hostinger.zip');
const dir=mkdtempSync(join(tmpdir(),'opentune-package-'));
let server;
const request=(url,options={})=>fetch(url,{...options,signal:AbortSignal.timeout(10000)});
try {
 execFileSync('python3',['-m','zipfile','-e',archive,dir],{timeout:15000});
 server=spawn(process.execPath,['server.mjs'],{cwd:join(dir,'opentune-addon'),env:{...process.env,HOST:'127.0.0.1',PORT:'0',PUBLIC_HOST:'addon.example',DATA_DIR:join(dir,'data'),
  CONTROL_ADMIN_USERNAME:'test-owner',CONTROL_ADMIN_PASSWORD:'test-package-password-only',USAGE_ENABLED:'true',ADDON_WORKERS:'2',APP_CERT_SHA256:'a'.repeat(64)},stdio:['ignore','pipe','inherit']});
 const base=await new Promise((resolve,reject)=>{
  const timer=setTimeout(()=>reject(new Error('Packaged server did not start')),15000);
  server.stdout.on('data',data=>{const match=String(data).match(/http:\/\/127\.0\.0\.1:\d+/);if(match){clearTimeout(timer);resolve(match[0]);}});
  server.once('exit',code=>{clearTimeout(timer);reject(new Error('Packaged server exited '+code));});
 });
 assert.equal((await request(base+'/monochrome/manifest.json')).status,200);
 assert.equal((await request(base+'/monochrome/search?q=test')).status,401);
 assert.equal((await request(base+'/control/')).status,200);
 assert.equal((await request(base+'/usage/',{redirect:'manual'})).status,303);
 const login=await request(base+'/auth/login',{method:'POST',redirect:'manual',headers:{Origin:'https://addon.example','Content-Type':'application/x-www-form-urlencoded'},
  body:new URLSearchParams({username:'test-owner',password:'test-package-password-only'})});
 assert.equal(login.status,303);
 const cookie=login.headers.get('set-cookie').split(';')[0];
 for(const route of ['/usage/','/usage/dashboard.js','/usage/model.mjs','/usage/session.mjs','/usage/dashboard.css','/usage/v1/summary']) {
  const response=await request(base+route,{headers:{Cookie:cookie},redirect:'manual'});
  assert.equal(response.status,200,route);
  assert.ok((await response.text()).length>0,route+' should not be empty');
 }
 const activity=await request(base+'/v1/activity',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({installationId:'00000000-0000-4000-8000-000000000001',days:[{day:new Date().toISOString().slice(0,10),version:'1.0.3',plays:3}]})});
 assert.equal(activity.status,200);
 const summary=await (await request(base+'/usage/v1/summary',{headers:{Cookie:cookie}})).json();
 assert.equal(summary.totals.plays,3);assert.equal(summary.totals.installs,1);
 console.log('Extracted ZIP: startup, strict rejection, owner login, usage assets and exact counts passed.');
} finally {
 if(server && server.exitCode===null) {
  await new Promise(resolve=>{const timer=setTimeout(()=>{server.kill('SIGKILL');resolve();},3000);server.once('exit',()=>{clearTimeout(timer);resolve();});server.kill();});
 }
 rmSync(dir,{recursive:true,force:true});
}
