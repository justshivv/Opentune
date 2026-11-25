import { describe, expect, it } from 'vitest';
import { defaults, validateData } from './protocol';

describe('optional links from the dashboard', () => {
  it('keeps a link that is set and on its own site, and drops the rest', () => {
    const data = defaults().data;
    const links = {
      ...data.links,
      website: 'https://opentune.example.org/',
      x: 'https://x.com/opentune',
      instagram: 'https://www.instagram.com/opentune/',
      telegram: 'https://t.me.evil.example/',
      youtube: 'http://youtube.com/@opentune',
      reddit: '',
    };
    const clean = validateData(JSON.parse(JSON.stringify({ ...data, links })));
    expect(clean.links.website).toBe('https://opentune.example.org/');
    expect(clean.links.x).toBe('https://x.com/opentune');
    expect(clean.links.instagram).toBe('https://www.instagram.com/opentune/');
    expect(clean.links.telegram).toBeUndefined();
    expect(clean.links.youtube).toBeUndefined();
    expect(clean.links.reddit).toBeUndefined();
  });

  it('reads a configuration from before these links', () => {
    const clean = validateData(JSON.parse(JSON.stringify(defaults().data)));
    expect(clean.links.github).toBe('https://github.com/justshivv/Opentune');
    expect(clean.links.website).toBeUndefined();
  });
});
