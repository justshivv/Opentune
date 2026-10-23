import { lazy, Suspense, useEffect, useState } from 'react';
import '@/together/room';
import { useDiscordPresence } from '@/playback/discordPresence';
import { startHistoryReporting } from '@/playback/historyReporting';
import { Outlet, useLocation } from 'react-router';
import { PrefetchLink } from '@/components/PrefetchLink';
import { config } from '@/config';
import { useExtension } from '@/extension/useExtension';
import { syncPublished } from '@/addons/client';
import { isMobileBrowser } from '@/lib/platform';
import { useUi } from '@/state/ui';
import { useKeyboardShortcuts } from './useKeyboardShortcuts';
import { Banner, bannerButtonClass } from './Banner';
import { answered, DISCORD_URL, due, GITHUB_URL, usedToday, type Invitation } from './invitations';
import { MobileTopBar } from './MobileTopBar';
import { PlayerBar } from './PlayerBar';
import { SidePanel } from './SidePanel';
import { Appearance } from './Appearance';
import { MobileDock } from './MobileDock';
import { RoomNotice } from './RoomNotice';
import { Sidebar } from './Sidebar';

const mobile = isMobileBrowser();

// It carries am-lyrics; loaded the first time it opens, as the side panel's lyrics are.
const FullScreenPlayer = lazy(() => import('./FullScreenPlayer').then((m) => ({ default: m.FullScreenPlayer })));

/**
 * The app frame (report §1): glass sidebar, the scrolling page, the floating
 * player pill, the side panel, and the bottom band when there is one.
 */
export function AppShell() {
  useKeyboardShortcuts();
  useDiscordPresence();
  // Plays into the account's YouTube Music history (Settings › Share Listening Activity).
  useEffect(() => startHistoryReporting(), []);
  // The sources the owner publishes ("Added by admin"), folded into the listener's list.
  useEffect(() => { void syncPublished(); }, []);
  const extension = useExtension();
  const bannerDismissed = useUi((s) => s.installBannerDismissed);
  const dismissBanner = useUi((s) => s.dismissInstallBanner);
  const fullScreen = useUi((s) => s.fullScreen);
  // The full player stays up after it's asked to close, until it has folded back into the mini player.
  const [playerUp, setPlayerUp] = useState(fullScreen);
  useEffect(() => {
    if (fullScreen) setPlayerUp(true);
  }, [fullScreen]);
  const { pathname } = useLocation();
  // Once a month each, after some days of use, a GitHub star or Discord invitation;
  // never over the install banners, and not straight after opening.
  const [invitation, setInvitation] = useState<Invitation | null>(null);
  useEffect(() => {
    usedToday();
    const timer = setTimeout(() => {
      const next = due();
      if (next) answered(next, false);
      setInvitation(next);
    }, 45_000);
    return () => clearTimeout(timer);
  }, []);

  let banner = null;
  if (mobile) {
    banner = (
      <Banner
        title="OpenTune is better in the app."
        detail="Phone browsers can’t run the OpenTune extension. The Android app has everything."
        action={
          <a className={bannerButtonClass} href={config.androidAppUrl} target="_blank" rel="noreferrer">
            Get the App
          </a>
        }
      />
    );
  } else if (extension.state === 'missing' && !bannerDismissed) {
    banner = (
      <Banner
        title="Play YouTube Music with the OpenTune extension."
        detail="Playback, YouTube Music sign-in, Spotify and Discord need the extension. Addons and lyrics work without it."
        action={
          <PrefetchLink className={bannerButtonClass} to="/extension">
            Get the Extension
          </PrefetchLink>
        }
        onDismiss={dismissBanner}
      />
    );
  }

  if (!banner && invitation) {
    const discord = invitation === 'discord';
    const close = () => setInvitation(null);
    banner = (
      <Banner
        title={discord ? 'Come say hi on Discord.' : 'Made for music. Built together.'}
        detail={
          discord
            ? 'Talk to other listeners, swap add-ons, report bugs and hear about new features first.'
            : 'OpenTune is free and open source. A star on GitHub helps more listeners find it.'
        }
        action={
          <span className="flex items-center gap-3">
            <button className="type-body-emphasized text-white opacity-85 hover:opacity-100" onClick={() => { answered(invitation, true); close(); }}>
              Don’t show again
            </button>
            <a className={bannerButtonClass} href={discord ? DISCORD_URL : GITHUB_URL} target="_blank" rel="noreferrer" onClick={close}>
              {discord ? 'Join the Discord' : 'Star on GitHub'}
            </a>
          </span>
        }
        onDismiss={close}
      />
    );
  }

  return (
    <div className="grid h-full grid-rows-[minmax(0,1fr)_auto]">
      {/* The side panel slides in from beyond the right edge; never let it scroll the page. */}
      <div className="relative min-h-0 overflow-hidden">
        <Appearance />
        <Sidebar />
        <MobileTopBar />
        {/* The page scrolls under the fixed sidebar and the floating player. */}
        <main
          id="scrollable-page"
          className="absolute inset-0 overflow-x-hidden overflow-y-auto pl-(--web-navigation-width) [scrollbar-gutter:stable] data-tint:bg-(--page-tint) data-tint:transition-[background-color] data-tint:duration-300 data-tint:ease-in max-sm:pt-(--mobile-top-bar-height) max-sm:pl-0"
        >
          {/* Clears the floating pill: its height, its offset, and some air. */}
          <div className="min-h-full pb-[calc(var(--player-pill-height)+var(--player-bottom-offset)+40px)]">
            {/* Each page rises in as it arrives. */}
            <div key={pathname} className="ot-page">
              <Outlet />
            </div>
          </div>
        </main>
        <PlayerBar />
        <MobileDock />
        <SidePanel />
        <RoomNotice />
      </div>
      {banner}
      {(fullScreen || playerUp) && (
        <Suspense fallback={null}>
          <FullScreenPlayer closing={!fullScreen} onClosed={() => setPlayerUp(false)} />
        </Suspense>
      )}
    </div>
  );
}

