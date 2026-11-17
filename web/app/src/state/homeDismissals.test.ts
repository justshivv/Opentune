// @vitest-environment happy-dom
import { beforeEach, describe, expect, it } from 'vitest';
import { openBanners, savedOf, showsPlaylists, useHomeDismissals } from './homeDismissals';

const banner = (id: string, platforms = ['web'], enabled = true) => ({ id, enabled, platforms, title: id });

describe('home dismissals', () => {
  beforeEach(() => {
    localStorage.clear();
    useHomeDismissals.setState({ banners: [], playlists: [] });
  });

  it('a closed banner stays closed, and a new one still shows', () => {
    useHomeDismissals.getState().dismissBanner('launch');
    const shown = openBanners([banner('launch'), banner('survey')], useHomeDismissals.getState().banners);
    expect(shown.map((b) => b.id)).toEqual(['survey']);
  });

  it('only banners switched on for the web show', () => {
    expect(openBanners([banner('phone', ['android']), banner('off', ['web'], false), banner('web')], []).map((b) => b.id)).toEqual(['web']);
  });

  it('the featured shelf comes back once a playlist is added that was not there when it was closed', () => {
    useHomeDismissals.getState().dismissPlaylists(['a', 'b']);
    const closed = useHomeDismissals.getState().playlists;
    expect(showsPlaylists(['a', 'b'], closed)).toBe(false);
    expect(showsPlaylists(['b'], closed)).toBe(false);
    expect(showsPlaylists(['a', 'b', 'c'], closed)).toBe(true);
  });

  it('is kept in this browser', () => {
    useHomeDismissals.getState().dismissBanner('launch');
    useHomeDismissals.getState().dismissPlaylists(['a']);
    expect(savedOf(localStorage.getItem('opentune:home-dismissals'))).toEqual({ banners: ['launch'], playlists: ['a'] });
  });

  it('an unreadable saved value closes nothing', () => {
    expect(savedOf('not json')).toEqual({ banners: [], playlists: [] });
    expect(savedOf('{"banners":"x","playlists":[1,"a"]}')).toEqual({ banners: [], playlists: ['a'] });
  });
});
