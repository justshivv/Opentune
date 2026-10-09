const UUID = /^[a-f0-9]{8}-[a-f0-9]{4}-4[a-f0-9]{3}-[89ab][a-f0-9]{3}-[a-f0-9]{12}$/i;
const VERSION = /^\d+\.\d+\.\d+(?:-[a-zA-Z0-9.-]{1,32})?$/;
const DAY = 86400000;
export const activitySql = `INSERT INTO activity(installation,day,version,plays,received_at) VALUES(?,?,?,?,?)
 ON CONFLICT(installation,day,version) DO UPDATE SET plays=MAX(activity.plays,excluded.plays),received_at=excluded.received_at`;
export const downloadSql = 'INSERT OR IGNORE INTO downloads(browser,release,first_requested_at) VALUES(?,?,?)';

export function validateActivity(body, now = new Date()) {
  if (!UUID.test(body?.installationId) || !Array.isArray(body.days) || body.days.length < 1 || body.days.length > 64) throw new Error('Invalid installation or batch');
  const today = now.toISOString().slice(0,10);
  const earliest = new Date(now.getTime() - 30 * DAY).toISOString().slice(0,10);
  const rows = body.days.map(row => {
    if (!/^\d{4}-\d{2}-\d{2}$/.test(row.day) || !Number.isFinite(Date.parse(row.day)) ||
        new Date(row.day).toISOString().slice(0,10) !== row.day || row.day > today || row.day < earliest ||
        !VERSION.test(row.version) || !Number.isInteger(row.plays) || row.plays < 0 || row.plays > 10000) throw new Error('Invalid daily aggregate');
    return [body.installationId.toLowerCase(), row.day, row.version, row.plays, now.toISOString()];
  });
  return rows;
}

export async function ingest(db, body, now = new Date()) {
  const rows = validateActivity(body, now);
  await db.batch(rows.map(row => db.prepare(activitySql).bind(...row)));
  return { accepted: rows.length };
}

export async function summary(db, now = new Date()) {
  const day = now.toISOString().slice(0,10);
  const week = new Date(Date.UTC(now.getUTCFullYear(),now.getUTCMonth(),now.getUTCDate()));
  week.setUTCDate(week.getUTCDate() - ((week.getUTCDay()+6)%7));
  const month = day.slice(0,7)+'-01';
  const from = new Date(now.getTime()-29*DAY).toISOString().slice(0,10);
  const results = await db.batch([
    db.prepare('SELECT COUNT(DISTINCT installation) AS installs, COALESCE(SUM(plays),0) AS plays, MIN(day) AS since FROM activity'),
    db.prepare('SELECT COUNT(DISTINCT installation) AS active, COALESCE(SUM(plays),0) AS plays FROM activity WHERE day=?').bind(day),
    db.prepare('SELECT COUNT(DISTINCT installation) AS active FROM activity WHERE day>=? AND day<=?').bind(week.toISOString().slice(0,10),day),
    db.prepare('SELECT COUNT(DISTINCT installation) AS active FROM activity WHERE day>=? AND day<=?').bind(month,day),
    db.prepare('SELECT day,COUNT(DISTINCT installation) AS active,SUM(plays) AS plays FROM activity WHERE day>=? AND day<=? GROUP BY day ORDER BY day').bind(from,day),
    db.prepare('SELECT version,COUNT(DISTINCT installation) AS installations FROM activity GROUP BY version ORDER BY MAX(day) DESC'),
    db.prepare('SELECT release,COUNT(*) AS browsers FROM downloads GROUP BY release ORDER BY MAX(first_requested_at) DESC'),
    db.prepare('SELECT COUNT(DISTINCT browser) AS browsers FROM downloads'),
  ]);
  return { asOf: now.toISOString(), totals: results[0].results[0], today: results[1].results[0],
    week: results[2].results[0].active, month: results[3].results[0].active,
    daily: results[4].results, versions: results[5].results, downloads: results[6].results,
    downloadBrowsers: results[7].results[0].browsers };
}

export default {
  async fetch(request, env) {
    const origin = request.headers.get('Origin');
    const allowed = (env.ALLOWED_ORIGINS || 'https://justshivv.github.io').split(',');
    const headers = { 'Content-Type':'application/json', 'Cache-Control':'no-store', 'Vary':'Origin' };
    if (origin && !allowed.includes(origin)) return new Response('{}', {status:403,headers});
    if (origin) headers['Access-Control-Allow-Origin']=origin;
    headers['Access-Control-Allow-Methods']='GET, POST, OPTIONS';
    headers['Access-Control-Allow-Headers']='Content-Type';
    const json = (body,status=200) => new Response(JSON.stringify(body),{status,headers});
    if (request.method==='OPTIONS') return new Response(null,{status:204,headers});
    const path = new URL(request.url).pathname;
    try {
      if (request.method==='GET' && path==='/v1/summary') return json(await summary(env.DB));
      if (request.method!=='POST' || !['/v1/activity','/v1/download'].includes(path)) return json({error:'Not found'},404);
      if (!request.headers.get('Content-Type')?.startsWith('application/json')) return json({error:'JSON required'},415);
      // Bounded streaming read: don't trust Content-Length from public clients.
      const reader = request.body?.getReader();
      if (!reader) return json({error:'Body required'},400);
      let size=0, chunks=[];
      while (true) { const {done,value}=await reader.read(); if(done) break; size+=value.length;
        if(size>16384) { await reader.cancel(); return json({error:'Too large'},413); } chunks.push(value); }
      const bytes = new Uint8Array(size); let offset=0;
      for(const chunk of chunks) { bytes.set(chunk,offset); offset+=chunk.length; }
      let body;
      try { body=JSON.parse(new TextDecoder().decode(bytes)); } catch { return json({error:'Invalid JSON'},400); }
      if (path==='/v1/activity') {
        try { validateActivity(body); } catch(e) { return json({error:e.message},400); }
        return json(await ingest(env.DB,body));
      }
      if(!UUID.test(body?.browserId) || !VERSION.test(body?.release)) return json({error:'Invalid browser or release'},400);
      await env.DB.prepare(downloadSql).bind(body.browserId.toLowerCase(),body.release,new Date().toISOString()).run();
      return json({accepted:true});
    } catch { return json({error:'Temporarily unavailable; retry the same payload'},503); }
  }
};
