import { ArrowUpRight, AudioLines, ChevronRight, Info, Mic2, Palette, Play, Plug, Smartphone, Tv, Users, Wrench, type LucideIcon } from 'lucide-react';
import { lazy, Suspense, useEffect, useRef, useState, type ComponentType, type ReactNode } from 'react';
import { MdOutlineExtension } from 'react-icons/md';
import { SiApplemusic, SiDiscord, SiGithub, SiSpotify, SiYoutubemusic } from 'react-icons/si';
import { PageHeader } from '@/components/PageHeader';
import { PrefetchLink } from '@/components/PrefetchLink';
import { LogoMark } from '@/components/icons/LogoMark';
import { config } from '@/config';
import { useRemoteConfig } from '@/config/remote';
import { useAddons } from '@/addons/client';
import { useDiscord } from '@/extension/useDiscord';
import { useExtension } from '@/extension/useExtension';
import { useYtAuth } from '@/extension/useYtAuth';
import { useRoom } from '@/together/room';
import { useHistorySettings } from '@/playback/historyReporting';
import { CROSSFADE_CHOICES, usePlayer } from '@/state/player';
import { AppleMusicIntegration, AppleMusicToggles, CanvasToggle, LyricSourcesAccess, SpotifyIntegration, Toggle } from './IntegrationSettings';
import { AppearanceSettings } from './AppearanceSettings';
import { settingsUi } from './settingsStyles';

const LyricsSettings = lazy(() => import('@/shell/LyricsSettings').then((m) => ({ default: m.LyricsSettings })));

/** An integration's coloured glyph, as on the Extension page. */
function Glyph({ Icon, color, ink }: { Icon: ComponentType<{ size?: number }>; color: string; ink?: string }) {
  return (
    <span className={settingsUi.integrationIcon} style={{ backgroundColor: color, ...(ink && { color: ink }) }}>
      <Icon size={15} />
    </span>
  );
}

/** A row that opens a page of its own. */
function LinkRow({ to, icon, title, value }: { to: string; icon?: ReactNode; title: string; value?: string | null }) {
  return (
    <PrefetchLink to={to} className={`${icon ? settingsUi.integration : settingsUi.integrationPlain} ${settingsUi.linkRow}`}>
      {icon}
      <span className={settingsUi.integrationName}>{title}</span>
      <span className={settingsUi.value}>
        {value && <span>{value}</span>}
        <ChevronRight size={16} className="flex-none text-tertiary" />
      </span>
    </PrefetchLink>
  );
}

/** A row that leaves for another site. */
function ExternalRow({ href, icon, title, value }: { href: string; icon: ReactNode; title: string; value?: string }) {
  return (
    <a href={href} target="_blank" rel="noreferrer" className={`${settingsUi.integration} ${settingsUi.linkRow}`}>
      {icon}
      <span className={settingsUi.integrationName}>{title}</span>
      <span className={settingsUi.value}>
        {value && <span>{value}</span>}
        <ArrowUpRight size={16} className="flex-none text-tertiary" />
      </span>
    </a>
  );
}

/** The page's sections, in order: the side list and the chips are built from this. */
const SECTIONS: { id: string; name: string; icon: LucideIcon; color: string }[] = [
  { id: 'audio', name: 'Audio', icon: AudioLines, color: '#e5483a' },
  { id: 'playback', name: 'Playback', icon: Play, color: '#2fa84f' },
  { id: 'lyrics', name: 'Lyrics', icon: Mic2, color: '#d6337a' },
  { id: 'appearance', name: 'Appearance', icon: Palette, color: '#8e5bd8' },
  { id: 'accounts', name: 'Accounts', icon: Plug, color: '#3478f6' },
  { id: 'together', name: 'Listen Together', icon: Users, color: '#f08a24' },
  { id: 'about', name: 'About', icon: Info, color: '#636366' },
];

function Section({ id, title, children }: { id: string; title: string; children: ReactNode }) {
  return (
    <section id={`set-${id}`} className="ot-set-section" aria-labelledby={`set-${id}-title`}>
      <h2 id={`set-${id}-title`} className="ot-set-title">{title}</h2>
      {children}
    </section>
  );
}

/** Which section the reader is in: the last one whose title has passed the top quarter of the page. */
function useCurrentSection(): string {
  const [current, setCurrent] = useState(SECTIONS[0]!.id);
  useEffect(() => {
    const page = document.getElementById('scrollable-page');
    if (!page) return;
    let frame = 0;
    const read = () => {
      frame = 0;
      const line = page.getBoundingClientRect().top + page.clientHeight * 0.25;
      let at = SECTIONS[0]!.id;
      for (const { id } of SECTIONS) {
        const el = document.getElementById(`set-${id}`);
        if (el && el.getBoundingClientRect().top <= line) at = id;
      }
      // At the very bottom the last sections may never reach the line; the last one is current.
      if (page.scrollTop + page.clientHeight >= page.scrollHeight - 4) at = SECTIONS[SECTIONS.length - 1]!.id;
      setCurrent(at);
    };
    const onScroll = () => { frame ||= requestAnimationFrame(read); };
    read();
    page.addEventListener('scroll', onScroll, { passive: true });
    return () => {
      page.removeEventListener('scroll', onScroll);
      cancelAnimationFrame(frame);
    };
  }, []);
  return current;
}

function jumpTo(id: string) {
  const reduce = window.matchMedia('(prefers-reduced-motion: reduce)').matches || document.documentElement.dataset.motion === 'false';
  document.getElementById(`set-${id}`)?.scrollIntoView({ behavior: reduce ? 'auto' : 'smooth', block: 'start' });
}

/**
 * The sections, twice over: a list beside the page where there's room for one,
 * chips that stick to the top where there isn't. CSS shows one or the other.
 */
function SectionNav({ current }: { current: string }) {
  const chips = useRef<HTMLDivElement>(null);
  // Keeps the current chip in view as the page scrolls past sections.
  useEffect(() => {
    const row = chips.current;
    const chip = row?.querySelector<HTMLElement>('[aria-current="true"]');
    if (!row || !chip || row.offsetParent === null) return;
    const left = chip.offsetLeft - 16;
    const right = chip.offsetLeft + chip.offsetWidth + 40 - row.clientWidth;
    if (row.scrollLeft > left) row.scrollTo({ left, behavior: 'smooth' });
    else if (row.scrollLeft < right) row.scrollTo({ left: right, behavior: 'smooth' });
  }, [current]);
  return (
    <>
      <nav className="ot-set-side" aria-label="Settings sections">
        {SECTIONS.map(({ id, name, icon: Icon, color }) => (
          <button key={id} aria-current={current === id || undefined} onClick={() => jumpTo(id)}>
            <span style={{ background: color }}><Icon size={14} strokeWidth={2.4} /></span>
            {name}
          </button>
        ))}
      </nav>
      <div ref={chips} className="ot-set-jump" role="navigation" aria-label="Settings sections">
        {SECTIONS.map(({ id, name }) => (
          <button key={id} aria-current={current === id || undefined} onClick={() => jumpTo(id)}>{name}</button>
        ))}
      </div>
    </>
  );
}

function youtubeText(auth: ReturnType<typeof useYtAuth>): string {
  if (auth.state === 'checking') return 'Checking…';
  if (auth.state === 'unavailable') return 'Needs the extension';
  const { browserSignedIn, connected, active } = auth.auth;
  if (!browserSignedIn) return 'Not signed in';
  if (!connected) return 'Disconnected';
  return active ? active.name : 'Signed in';
}

/** The extension's state at the top: what works now, and the one thing to do next. */
function ExtensionCard() {
  const extension = useExtension();
  const yt = useYtAuth();
  const present = extension.state === 'present';
  return (
    <section className="ot-set-hero" aria-label="OpenTune Extension">
      <LogoMark size={26} className="ot-set-mark" />
      <div className="ot-set-hero-text">
        <p className="type-title-3-emphasized">{present ? 'Extension connected' : 'OpenTune on the web'}</p>
        <p className="type-body-tall text-secondary">
          {present
            ? `Version ${extension.extension.info.version} · YouTube Music: ${youtubeText(yt)}`
            : 'Install the extension to play YouTube Music, sign in, and use Spotify and Discord.'}
        </p>
      </div>
      <PrefetchLink to="/extension" className={present ? 'ot-set-hero-button ot-quiet' : 'ot-set-hero-button'}>
        {present ? 'Manage' : 'Get the extension'}
      </PrefetchLink>
    </section>
  );
}

/**
 * Settings (SettingsSheet on the phone): sources and playback first, then
 * how it looks, the accounts it uses, Listen Together, and where OpenTune
 * lives. One page, grouped rows, a list of the sections beside it.
 */
export default function SettingsPage() {
  const links = useRemoteConfig((s) => s.config.data.links);
  const extension = useExtension();
  const yt = useYtAuth();
  const discord = useDiscord();
  const room = useRoom((s) => s.view);
  const autoplay = usePlayer((s) => s.autoplay);
  const crossfadeSec = usePlayer((s) => s.crossfadeSec);
  const shareHistory = useHistorySettings((s) => s.share);
  const setShareHistory = useHistorySettings((s) => s.setShare);
  const { setAutoplay, setCrossfade } = usePlayer.getState();
  const [lyrics, setLyrics] = useState(false);
  const sources = useAddons((st) => st.sources.filter((a) => a.enabled && !a.ownerOff).length);
  const jioSaavn = useAddons((st) => st.jioSaavn);
  const sourcesText = [sources ? `${sources} add-on${sources === 1 ? '' : 's'}` : 'No add-ons', jioSaavn ? 'JioSaavn' : null].filter(Boolean).join(' · ');
  const present = extension.state === 'present';
  const current = useCurrentSection();
  const discordText = !present
    ? 'Needs the extension'
    : discord.state === 'ready' && discord.discord.connected
      ? discord.discord.enabled
        ? 'On'
        : 'Off'
      : discord.state === 'ready' && discord.discord.available
        ? 'Not connected'
        : 'Update the extension';

  return (
    <div className="ot-settings">
      <div className="ot-set-frame">
      <PageHeader title="Settings" />
      <div className="ot-set-layout">
        <SectionNav current={current} />
        <div className="ot-set-content">
          <ExtensionCard />

          <Section id="audio" title="Audio">
            <section className={settingsUi.group}>
              <LinkRow to="/settings/addons" icon={<Glyph Icon={AudioLines} color="#e5483a" />} title="Audio sources" value={sourcesText} />
            </section>
            <p className={settingsUi.footer}>Lossless FLAC from add-ons and 320 kbps from JioSaavn, in the order you choose. YouTube Music is the fallback.</p>
          </Section>

          <Section id="playback" title="Playback">
            <section className={settingsUi.group}>
              <Toggle label="AutoPlay" detail="When the queue ends, keep playing similar music." checked={autoplay} onChange={setAutoplay} />
              <label className={settingsUi.controlRow}>
                <span className={settingsUi.rowText}>
                  <span>Crossfade</span>
                  <span className={settingsUi.rowDetail}>Blend the end of one song into the next.</span>
                </span>
                <select className={settingsUi.select} value={crossfadeSec} onChange={(e) => setCrossfade(Number(e.currentTarget.value))}>
                  {CROSSFADE_CHOICES.map((seconds) => (
                    <option key={seconds} value={seconds}>
                      {seconds === 0 ? 'Off' : `${seconds} seconds`}
                    </option>
                  ))}
                </select>
              </label>
            </section>
          </Section>

          <Section id="lyrics" title="Lyrics">
            <section className={settingsUi.group}>
              <button className={`${settingsUi.integrationPlain} ${settingsUi.linkRow}`} onClick={() => setLyrics(true)}>
                <span className={settingsUi.rowText}>
                  <span>Lyric sources</span>
                  <span className={settingsUi.rowDetail}>Order, sources, syllable timing</span>
                </span>
                <ChevronRight size={16} className="text-tertiary" />
              </button>
              <div className={settingsUi.integrationPlain}>
                <span className={settingsUi.rowText}>
                  <span>Access to lyric sites</span>
                  <span className={settingsUi.rowDetail}>KuGou, Musixmatch, Genius and others answer only the extension.</span>
                </span>
                {present ? <LyricSourcesAccess /> : <span className={settingsUi.value}>Needs the extension</span>}
              </div>
            </section>
          </Section>

          <Section id="appearance" title="Appearance">
            <AppearanceSettings />
          </Section>

          <Section id="accounts" title="Accounts & integrations">
            <section className={settingsUi.group}>
              <div className={settingsUi.integration}>
                <Glyph Icon={SiYoutubemusic} color="#ffffff" ink="#ff0033" />
                <span className={settingsUi.integrationName}>YouTube Music</span>
                <span className={settingsUi.value}><span>{youtubeText(yt)}</span></span>
              </div>
              <div className={settingsUi.integration}>
                <Glyph Icon={SiSpotify} color="#1ed760" ink="#000000" />
                <span className={settingsUi.integrationName}>Spotify</span>
                {present ? <SpotifyIntegration /> : <span className={settingsUi.value}>Needs the extension</span>}
              </div>
              <LinkRow to="/settings/discord" icon={<Glyph Icon={SiDiscord} color="#5865f2" />} title="Discord" value={discordText} />
              <LinkRow
                to="/extension"
                icon={<Glyph Icon={MdOutlineExtension} color="#636366" />}
                title="OpenTune Extension"
                value={present ? `v${extension.extension.info.version}` : 'Not installed'}
              />
            </section>
            <section className={`${settingsUi.group} mt-3 empty:hidden`}>
              <Toggle
                label="Share listening activity to YouTube"
                detail={
                  present && !extension.extension.info.capabilities.includes('yt.stats')
                    ? 'Update the OpenTune extension to share: this version can’t send plays to YouTube.'
                    : 'Songs you play here go to your YouTube Music history and shape its recommendations.'
                }
                checked={shareHistory}
                onChange={setShareHistory}
              />
              <CanvasToggle />
            </section>
            <h3 className={settingsUi.sectionHeader}>Apple Music</h3>
            <section className={settingsUi.group}>
              <div className={settingsUi.integration}>
                <Glyph Icon={SiApplemusic} color="#fa2d48" />
                <span className={settingsUi.integrationName}>Access to Apple Music</span>
                {present ? <AppleMusicIntegration /> : <span className={settingsUi.value}>Needs the extension</span>}
              </div>
              <AppleMusicToggles />
            </section>
            <p className={settingsUi.footer}>Animated covers and artist art from Apple Music’s public pages. No Apple Music account needed.</p>
          </Section>

          <Section id="together" title="Listen Together">
            <section className={settingsUi.group}>
              <LinkRow
                to="/settings/listen-together"
                icon={<Glyph Icon={Users} color="#f08a24" />}
                title="Rooms"
                value={room && room.phase !== 'ended' ? (room.hosting ? 'Hosting a room' : `In ${room.hostName ?? 'a'}’s room`) : 'With the web player and the Android app'}
              />
            </section>
          </Section>

          <Section id="about" title="About">
            <section className={settingsUi.group}>
              <ExternalRow href={config.androidAppUrl} icon={<Glyph Icon={Smartphone} color="#2fa84f" />} title="OpenTune for Android" value="Download" />
              <ExternalRow href={links.tv} icon={<Glyph Icon={Tv} color="#3478f6" />} title="OpenTune on TV" />
              <ExternalRow href="https://justshivv.github.io/Opentune/addons/" icon={<Glyph Icon={Wrench} color="#e5483a" />} title="Build an add-on" />
              <ExternalRow href={links.github} icon={<Glyph Icon={SiGithub} color="#24292f" />} title="Source code" value="GitHub" />
              <ExternalRow href={links.discord} icon={<Glyph Icon={SiDiscord} color="#5865f2" />} title="Community" value="Discord" />
            </section>
            <p className={settingsUi.footer}>OpenTune is free and open source. Report bugs, suggest features and share the add-ons you build.</p>
          </Section>
        </div>
      </div>
      </div>
      {lyrics && (
        <Suspense fallback={null}>
          <LyricsSettings onClose={() => setLyrics(false)} />
        </Suspense>
      )}
    </div>
  );
}
