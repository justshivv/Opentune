// @vitest-environment happy-dom
import { act } from 'react';
import { createRoot } from 'react-dom/client';
import { MemoryRouter } from 'react-router';
import { afterEach, expect, it, vi } from 'vitest';

(globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;

const platform = vi.hoisted(() => ({ mobile: false }));
vi.mock('@/lib/platform', () => ({ isMobileBrowser: () => platform.mobile }));

const { NeedsExtension } = await import('./States');
const { config } = await import('@/config');

let root: ReturnType<typeof createRoot> | null = null;
afterEach(() => {
  act(() => root?.unmount());
  root = null;
  document.body.innerHTML = '';
});

function render() {
  const host = document.createElement('div');
  document.body.append(host);
  root = createRoot(host);
  act(() =>
    root!.render(
      <MemoryRouter>
        <NeedsExtension what="your library" />
      </MemoryRouter>,
    ),
  );
  return host;
}

it('invites a computer to install the extension', () => {
  platform.mobile = false;
  const host = render();
  expect(host.textContent).toContain('Browse your library with the NepTune extension');
  expect(host.querySelector('a')?.getAttribute('href')).toBe('/extension');
});

it('sends a phone, which can’t run the extension, to the app instead', () => {
  platform.mobile = true;
  const host = render();
  expect(host.textContent).toContain('Browse your library in the NepTune app');
  expect(host.textContent).not.toContain('Get the Extension');
  expect(host.querySelector('a')?.getAttribute('href')).toBe(config.androidAppUrl);
});
