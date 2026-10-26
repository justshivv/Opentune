import { DatabaseSync } from 'node:sqlite';
import { readFileSync, existsSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { randomBytes } from 'node:crypto';
const usageRoot = new URL(existsSync(new URL('./usage-api/worker.mjs', import.meta.url)) ? './usage-api/' : '../../usage-api/', import.meta.url);
const {default: usage} = await import(new URL('worker.mjs', usageRoot));

/** The same usage API and SQL as Cloudflare D1, with one transactional SQLite writer. */
export function createUsageServer(directory) {
  const sql = new DatabaseSync(join(directory, 'usage.sqlite'));
  sql.exec('PRAGMA journal_mode=WAL; PRAGMA busy_timeout=5000;');
  sql.exec(readFileSync(new URL('schema.sql', usageRoot), 'utf8'));
  const wrap = (query, args = []) => ({ query, args, bind: (...args) => wrap(query, args),
    async run() { return sql.prepare(query).run(...args); },
    async first() { return sql.prepare(query).get(...args) || null; },
  });
  const DB = { prepare: query => wrap(query), async batch(statements) {
    sql.exec('BEGIN IMMEDIATE');
    try { const results = statements.map(s => ({results:sql.prepare(s.query).all(...s.args)})); sql.exec('COMMIT'); return results; }
    catch (e) { sql.exec('ROLLBACK'); throw e; }
  } };
  const secretFile = join(directory, 'usage-session.key');
  if (!existsSync(secretFile)) writeFileSync(secretFile, randomBytes(32).toString('hex'), {mode:0o600, flag:'wx'});
  const attempts = new Map();
  const env = { DB,
    USAGE_ADMIN_USERNAME: process.env.USAGE_ADMIN_USERNAME || process.env.CONTROL_ADMIN_USERNAME,
    USAGE_ADMIN_PASSWORD: process.env.USAGE_ADMIN_PASSWORD || process.env.CONTROL_ADMIN_PASSWORD,
    USAGE_SESSION_SECRET: readFileSync(secretFile, 'utf8').trim(),
    ALLOWED_ORIGINS: process.env.WEB_ORIGINS || 'https://justshivv.github.io',
    LOGIN_LIMITER: { async limit({key}) {
      const now=Date.now();
      for (const [k,v] of attempts) if(now-v.at>60_000) attempts.delete(k);
      const entry=attempts.get(key)||{at:now,n:0}; entry.n++; attempts.set(key,entry);
      return {success:entry.n<=10};
    } },
    ASSETS: { async fetch(request) {
      const path = new URL(request.url).pathname;
      const name = ['/usage','/usage/','/usage/index.html'].includes(path) ? 'index.html' : path.replace(/^\/usage\//, '');
      const types = {'index.html':'text/html', 'dashboard.css':'text/css', 'dashboard.js':'text/javascript', 'model.mjs':'text/javascript', 'session.mjs':'text/javascript'};
      if (!Object.hasOwn(types,name)) return new Response('Not found',{status:404});
      return new Response(readFileSync(new URL('dashboard/'+name,usageRoot)), {headers:{'Content-Type':types[name]+'; charset=utf-8'}});
    } },
  };
  return request => usage.fetch(request,env);
}
