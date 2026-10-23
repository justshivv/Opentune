/** Abuse controls. These deliberately do not claim to authenticate an app. */
const response = (error, status, retry = null) => new Response(JSON.stringify({ error }), { status, headers: {
  'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store',
  'Access-Control-Allow-Origin': '*', 'Access-Control-Expose-Headers': 'Retry-After',
  'X-Content-Type-Options': 'nosniff', ...(retry ? { 'Retry-After': String(retry) } : {}),
} });
export function withAbuseProtection(handler) {
  return async (request, env = {}) => {
    // OPTIONS does not touch an upstream; preserve browser preflight support.
    if (request.method === 'OPTIONS') return handler(request);
    if (request.method !== 'GET') return response('Method not allowed', 405);
    if (request.url.length > 4096) return response('Request URL is too long', 414);
    if (typeof env.REQUEST_LIMITER?.limit !== 'function') return response('Request protection is unavailable', 503, 60);
    // Cloudflare supplies this header at its edge. Never trust caller-supplied
    // X-Forwarded-For, User-Agent, or a random client ID to bypass the limit.
    const ip = request.headers.get('CF-Connecting-IP');
    if (!ip || ip.length > 64) return response('Client address could not be verified', 400);
    try {
      // A daily hash avoids putting plain IPs in limiter keys. It is not an identity token.
      const day = new Date().toISOString().slice(0, 10);
      const digest = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(`${day}|${ip}`));
      const key = 'lossless-v1:' + Array.from(new Uint8Array(digest), b => b.toString(16).padStart(2, '0')).join('');
      const { success } = await env.REQUEST_LIMITER.limit({ key });
      if (!success) return response('Too many requests. Please try again shortly.', 429, 60);
    } catch { return response('Request protection is temporarily unavailable', 503, 60); }
    const result = await handler(request);
    const headers = new Headers(result.headers); headers.set('X-Content-Type-Options', 'nosniff');
    return new Response(result.body, { status: result.status, headers });
  };
}
