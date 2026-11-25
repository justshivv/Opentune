import { PRODUCTION_ORIGIN } from './lib/site';

export { matchPatternsFor, PRODUCTION_ORIGIN } from './lib/site';

/** Only the NepTune player path on GitHub Pages may use the session bridge. */
export const SITE_ORIGINS = [PRODUCTION_ORIGIN, ...(process.env.OPENTUNE_STORE_BUILD === '1' ? [] : ['http://localhost:5173'])];
