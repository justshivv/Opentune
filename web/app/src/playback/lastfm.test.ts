import { describe, expect, it } from 'vitest';
import { isListen, primaryArtist, signature } from './lastfmRules';

describe('Last.fm', () => {
  // Expected values from Python's hashlib over the same sorted name+value string.
  it('signs the sorted parameters with the secret', () => {
    expect(signature({ method: 'track.scrobble', api_key: 'abc', sk: 's3ss', 'artist[0]': 'Daft Punk', 'track[0]': 'Digital Love', 'timestamp[0]': '1700000000' }, 'sekret')).toBe(
      'd5e55eb18d690865ef3a94b29d378d0a',
    );
    expect(signature({ method: 'auth.getToken', api_key: 'abc' }, 'sekret')).toBe('bb46dabf3cb8f8aece40e7cdba682f31');
  });

  it('signs UTF-8 and leaves format out of the signature', () => {
    expect(signature({ method: 'track.updateNowPlaying', api_key: 'k', track: 'Café del Mar', artist: 'Energy 52', format: 'json' }, 's')).toBe(
      'b5497fe66732297478ddae8099fa4b07',
    );
  });

  it('counts half the song, or four minutes, as a listen', () => {
    expect(isListen(89, 180)).toBe(false);
    expect(isListen(90, 180)).toBe(true);
    expect(isListen(239, 900)).toBe(false);
    expect(isListen(240, 900)).toBe(true);
    // Under 30 seconds is never scrobbled; an unknown length waits the four minutes.
    expect(isListen(25, 25)).toBe(false);
    expect(isListen(120, 0)).toBe(false);
    expect(isListen(240, 0)).toBe(true);
  });

  it('sends the main artist', () => {
    expect(primaryArtist('Daft Punk, Pharrell Williams & Nile Rodgers')).toBe('Daft Punk');
    expect(primaryArtist('Drake feat. Rihanna')).toBe('Drake');
    expect(primaryArtist('Beyoncé')).toBe('Beyoncé');
  });
});
