import type { ComponentType } from 'react';
import { FaChrome, FaFirefoxBrowser } from 'react-icons/fa';
import { config } from '@/config';
import { downloadUrl, type PublishedExtension } from './useExtensionUpdate';

export type Browser = 'chrome' | 'firefox';

export interface Download {
  browser: Browser;
  title: string;
  detail: string;
  href: string;
  Icon: ComponentType<{ size?: number; className?: string }>;
  steps: string[];
  updateSteps: string[];
}

/**
 * The two builds, as published (extension.json): the zip and the folder in it
 * carry the version, "opentune-extension-chrome (0.4.3)". Before that file
 * has loaded, the configured URLs and the unversioned names stand in.
 */
export function downloadsFor(published: PublishedExtension | null): Download[] {
  const folder = (browser: Browser) => `opentune-extension-${browser}${published ? ` (${published.version})` : ''}`;
  return [
    {
      browser: 'chrome',
      title: 'Download for Chrome',
      detail: 'Chrome, Edge, Brave and other Chromium browsers',
      href: published ? downloadUrl(published.files.chrome) : config.extensionChromeUrl,
      Icon: FaChrome,
      steps: [
        `Extract the zip somewhere you’ll keep it. It holds one folder, ${folder('chrome')}.`,
        'Open chrome://extensions and turn on Developer mode.',
        'Choose Load unpacked and select that folder.',
        'Come back to this page: the status above turns to Installed.',
      ],
      updateSteps: [
        `Extract the new zip. It holds ${folder('chrome')}.`,
        'In chrome://extensions, choose Load unpacked and select that folder. It has the same ID, so it takes the old one’s place and keeps your settings.',
        'Delete the old folder, then come back to this page: the version above updates on its own.',
      ],
    },
    {
      browser: 'firefox',
      title: 'Download for Firefox',
      detail: 'Firefox 128 or newer',
      href: published ? downloadUrl(published.files.firefox) : config.extensionFirefoxUrl,
      Icon: FaFirefoxBrowser,
      steps: [
        `Extract the zip. It holds one folder, ${folder('firefox')}.`,
        'Open about:debugging#/runtime/this-firefox and choose Load Temporary Add-on.',
        'Select manifest.json inside that folder.',
        'Firefox removes temporary add-ons when it quits; a signed version is coming.',
      ],
      updateSteps: [
        `Extract the new zip. It holds ${folder('firefox')}.`,
        'In about:debugging, choose Load Temporary Add-on and select manifest.json in the new folder.',
        'Come back to this page: the version above updates on its own.',
      ],
    },
  ];
}

/** The browser this page is open in, to put its download first. */
export const currentBrowser: Browser = /firefox/i.test(navigator.userAgent) ? 'firefox' : 'chrome';
