/** Small live check: catalogue + bounded FLAC header. Never downloads a whole song. */
import { createAddon } from './worker.mjs';
import { writeFile } from 'node:fs/promises';
const handler = createAddon();
const cases = [
  { source: 'monochrome', query: 'Wo Noor AP Dhillon', title: 'Wo Noor', artist: 'AP Dhillon' },
  { source: 'monochrome', query: 'Old Skool Prem Dhillon', title: 'Old Skool', artist: 'Prem Dhillon' },
  { source: 'archive', query: 'Grateful Dead' },
  { source: 'musopen', query: 'Goldberg Variations Aria Da Capo' },
];
const report = { checkedAt: new Date().toISOString(), scope: 'Catalogue and FLAC STREAMINFO only; full decoding, audible playback and CORS on the user device are not tested.', results: [] };
const norm = s => String(s).normalize('NFKC').toLowerCase().replace(/[^\p{L}\p{N}]+/gu, ' ').trim();
for (const item of cases) {
  const result = { source: item.source, query: item.query };
  try {
    const search = await handler(new Request(`https://addon.example/${item.source}/search?q=${encodeURIComponent(item.query)}`));
    const body = await search.json(); result.searchStatus = search.status;
    if (search.status !== 200) { result.error = body.error; result.retryAfter = search.headers.get('Retry-After'); }
    else {
      result.candidates = body.tracks.length;
      const track = body.tracks.find(t => !item.title || (norm(t.title) === norm(item.title) && norm(t.artist).includes(norm(item.artist))));
      if (!track) result.error = 'No matching recording with usable metadata';
      else {
        result.recording = { title: track.title, artist: track.artist, duration: track.duration };
        const response = await handler(new Request(`https://addon.example/${item.source}/stream/${track.id}?quality=best`));
        result.streamStatus = response.status;
        const stream = await response.json();
        if (response.status !== 200) { result.error = stream.error; result.retryAfter = response.headers.get('Retry-After'); }
        else result.flacHeader = { codec: stream.codec, bitDepth: stream.bitDepth, sampleRate: stream.sampleRate, channels: stream.channels, duration: stream.duration };
      }
    }
  } catch { result.error = 'Probe could not complete'; }
  report.results.push(result);
}
const output = JSON.stringify(report, null, 2);
if (process.argv[2]) await writeFile(process.argv[2], output + '\n');
console.log(output);
