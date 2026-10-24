// The owner publishes a bounded catalog. Endpoints are hidden in the app UI, not secret on the wire.
export function validateCatalog(body) {
  if (!Array.isArray(body?.sources) || body.sources.length > 20) throw new Error('Use up to 20 sources');
  const ids = new Set();
  return body.sources.map(source => {
    if (!/^[a-zA-Z0-9_-]{1,64}$/.test(source.id || '') || ids.has(source.id)) throw new Error('Use unique source IDs');
    ids.add(source.id);
    if (typeof source.name !== 'string' || !source.name.trim() || source.name.length > 80) throw new Error('Source name is required (up to 80 characters)');
    const url = new URL(source.url);
    if (source.url.length > 2048 || url.protocol !== 'https:' || url.username || url.password || url.hash || !url.hostname.includes('.') || /(?:^|\.)(local|localhost|internal)$/.test(url.hostname)) throw new Error('Use a public HTTPS manifest URL');
    if (/^\d+\.\d+\.\d+\.\d+$/.test(url.hostname)) {
      const [a,b] = url.hostname.split('.').map(Number);
      if ([0,10,127].includes(a) || a>=224 || a===169&&b===254 || a===192&&b===168 || a===172&&b>=16&&b<=31 || a===100&&b>=64&&b<=127) throw new Error('Private addresses are unsupported');
    }
    if (source.enabled !== undefined && typeof source.enabled !== 'boolean') throw new Error('Turn a source on or off with true or false');
    // Off: apps keep it listed as turned off by the owner and send it nothing.
    return {id:source.id,name:source.name.trim(),url:url.href,enabled:source.enabled !== false};
  });
}
export async function catalog(db) {
  const value = await db.prepare("SELECT payload FROM addon_catalog WHERE id=1").first();
  return value ? JSON.parse(value.payload) : {revision:0,sources:[]};
}
export async function publishCatalog(db, body) {
  const sources = validateCatalog(body);
  if (!Number.isSafeInteger(body.revision) || body.revision < 0) throw new Error('Reload the catalog before publishing');
  const payload = {revision:body.revision+1,sources,updatedAt:new Date().toISOString()};
  const result = await db.prepare(`INSERT INTO addon_catalog(id,revision,payload) VALUES(1,?,?)
    ON CONFLICT(id) DO UPDATE SET revision=excluded.revision,payload=excluded.payload WHERE addon_catalog.revision=?`)
    .bind(payload.revision,JSON.stringify(payload),body.revision).run();
  if (!result.meta?.changes) throw new Error('The catalog changed. Reload it before publishing again.');
  return payload;
}
export async function readCatalogBody(request) {
  if (!request.headers.get('content-type')?.startsWith('application/json')) throw new Error('JSON required');
  const reader=request.body?.getReader();if(!reader) throw new Error('Body required');
  const parts=[];let size=0;
  try {while(true){const {done,value}=await reader.read();if(done)break;size+=value.length;if(size>60000)throw new Error('Catalog too large');parts.push(value);}}
  finally {await reader.cancel().catch(()=>{});}
  const bytes=new Uint8Array(size);let at=0;for(const p of parts){bytes.set(p,at);at+=p.length;}
  return JSON.parse(new TextDecoder().decode(bytes));
}
