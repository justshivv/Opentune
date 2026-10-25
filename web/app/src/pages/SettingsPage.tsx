import { AudioLines, ChevronRight, Mic2, Palette, Play, Plug, Sparkles, Users, type LucideIcon } from 'lucide-react';
import { lazy, Suspense, useState, type ComponentType, type ReactNode } from 'react';
import { MdOutlineExtension } from 'react-icons/md';
import { SiApplemusic, SiDiscord, SiGithub, SiSpotify, SiYoutubemusic } from 'react-icons/si';
import { PageHeader } from '@/components/PageHeader';
import { PrefetchLink } from '@/components/PrefetchLink';
import { LogoMark } from '@/components/icons/LogoMark';
import { useRemoteConfig } from '@/config/remote';
import { useAddons } from '@/addons/client';
import { useDiscord } from '@/extension/useDiscord';
import { useExtension } from '@/extension/useExtension';
import { useYtAuth } from '@/extension/useYtAuth';
import { useRoom } from '@/together/room';
import { useHistorySettings } from '@/playback/historyReporting';
import { CROSSFADE_CHOICES, usePlayer } from '@/state/player';
import { AppleMusicIntegration, AppleMusicToggles, CanvasToggle, DiscordIntegration, LyricSourcesAccess, SpotifyIntegration, Toggle } from './IntegrationSettings';
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
        {value}
        <ChevronRight size={16} />
      </span>
    </PrefetchLink>
  );
}

/** A settings section: a coloured icon square and its title, as the app's settings list. */
function Section({ id, icon: Icon, color, title, children }: { id: string; icon: LucideIcon; color: string; title: string; children: ReactNode }) {
  return (
    <section id={`set-${id}`} className="ot-set-section" aria-labelledby={`set-${id}-title`}>
      <h2 id={`set-${id}-title`} className="ot-set-title">
        <span style={{ background: color }}><Icon size={15} strokeWidth={2.4} /></span>
        {title}
      </h2>
      {children}
    </section>
  );
}

const SECTIONS: [string, string][] = [
  ['sources', 'Audio'],
  ['appearance', 'Appearance'],
  ['accounts', 'Accounts'],
  ['together', 'Listen Together'],
  ['playback', 'Playback'],
  ['lyrics', 'Lyrics'],
];

/** Chips that scroll to each section; the page is long. */
function Jump() {
  return (
    <nav className="ot-set-jump" aria-label="Settings sections">
      {SECTIONS.map(([id, name]) => (
        <button key={id} onClick={() => document.getElementById(`set-${id}`)?.scrollIntoView({ behavior: 'smooth', block: 'start' })}>{name}</button>
      ))}
    </nav>
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

/**
 * Settings (SettingsSheet on the phone): the account and its integrations,
 * Listen Together, playback and lyrics, in the same grouped rows.
 */
export default function SettingsPage() {
  const links = useRemoteConfig(s => s.config.data.links);
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
  const sourcesText = [sources ? `${sources} add-on${sources === 1 ? '' : 's'}` : 'No add-ons', jioSaavn ? 'JioSaavn on' : null].filter(Boolean).join(' · ');
  const present = extension.state === 'present';
  const discordText =
    discord.state === 'ready' && discord.discord.connected
      ? discord.discord.enabled
        ? 'On'
        : 'Off'
      : discord.state === 'ready' && discord.discord.available
        ? 'Not connected'
        : null;

  return (
    <div className={settingsUi.centered}>
      <PageHeader title="Settings" />
      <div className={settingsUi.body}>
        <section className="ot-set-hero">
          <LogoMark size={44} className="ot-set-mark" />
          <div className="min-w-0 flex-1">
            <p className="type-title-2-emphasized">OpenTune on the web</p>
            <p className="type-body text-secondary">
              {present ? `Extension v${extension.extension.info.version} · YouTube Music: ${youtubeText(yt)}` : 'Install the extension to play YouTube Music, sign in and use Spotify and Discord.'}
            </p>
          </div>
          <PrefetchLink to="/extension" className="ot-set-hero-button">{present ? 'Extension' : 'Get the extension'}</PrefetchLink>
        </section>
        <Jump />

        <Section id="sources" icon={AudioLines} color="#e5483a" title="Audio">
          <section className={settingsUi.group}>
            <LinkRow to="/settings/addons" icon={<Glyph Icon={AudioLines} color="#e5483a" />} title="Audio sources" value={sourcesText} />
          </section>
          <p className={settingsUi.footer}>Lossless FLAC from add-ons and 320 kbps from JioSaavn, in the order you choose. YouTube Music is the fallback.</p>
        </Section>

        <Section id="appearance" icon={Palette} color="#8e5bd8" title="Appearance">
          <AppearanceSettings />
        </Section>

        <Section id="accounts" icon={Plug} color="#3478f6" title="Accounts & integrations">
          <section className={settingsUi.group}>
            <div className={settingsUi.integration}>
              <Glyph Icon={SiYoutubemusic} color="#ffffff" ink="#ff0033" />
              <span className={settingsUi.integrationName}>YouTube Music</span>
              <span className={settingsUi.value}>{youtubeText(yt)}</span>
            </div>
            <div className={settingsUi.integration}>
              <Glyph Icon={SiSpotify} color="#1ed760" />
              <span className={settingsUi.integrationName}>Spotify</span>
              {present ? <SpotifyIntegration /> : <span className={settingsUi.value}>Needs the extension</span>}
            </div>
            {present && discord.state === 'ready' && discord.discord.available && (
              <LinkRow to="/settings/discord" icon={<Glyph Icon={SiDiscord} color="#5865f2" />} title="Discord" value={discordText} />
            )}
            <LinkRow
              to="/extension"
              icon={<Glyph Icon={MdOutlineExtension} color="#636366" />}
              title="OpenTune Extension"
              value={present ? `v${extension.extension.info.version}` : 'Not installed'}
            />
          </section>
          <section className={`${settingsUi.group} mt-3 empty:hidden`}>
            <Toggle
              label="Share Listening Activity to YouTube"
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
              <span className={settingsUi.integrationName}>Extension Access</span>
              {present ? <AppleMusicIntegration /> : <span className={settingsUi.value}>Needs the extension</span>}
            </div>
            <AppleMusicToggles />
          </section>
          <p className={settingsUi.footer}>Animated covers and credits from Apple Music’s public pages. No Apple Music account needed.</p>
        </Section>

        <Section id="together" icon={Users} color="#f08a24" title="Listen Together">
          <section className={settingsUi.group}>
            <LinkRow
              to="/settings/listen-together"
              icon={<Glyph Icon={Users} color="#f08a24" />}
              title="Listen Together"
              value={room && room.phase !== 'ended' ? (room.hosting ? 'Hosting a room' : `In ${room.hostName ?? 'a'}’s room`) : 'Rooms with the Android app'}
            />
          </section>
        </Section>

        <Section id="playback" icon={Play} color="#2fa84f" title="Playback">
          <section className={settingsUi.group}>
            <Toggle label="AutoPlay" detail="When the queue ends, keep playing similar music." checked={autoplay} onChange={setAutoplay} />
            <label className={settingsUi.controlRow}>
              <span>Crossfade</span>
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

        <Section id="lyrics" icon={Mic2} color="#d6337a" title="Lyrics">
          <section className={settingsUi.group}>
            <button className={`${settingsUi.integrationPlain} ${settingsUi.linkRow}`} onClick={() => setLyrics(true)}>
              <span className={settingsUi.integrationName}>Lyric Sources</span>
              <span className={settingsUi.value}>
                Order, sources, syllable timing
                <ChevronRight size={16} />
              </span>
            </button>
            <div className={settingsUi.integrationPlain}>
              <span className={settingsUi.integrationName}>Lyric Sources Access</span>
              {present ? <LyricSourcesAccess /> : <span className={settingsUi.value}>Needs the extension</span>}
            </div>
          </section>
        </Section>

        <section className="ot-community" aria-labelledby="community-title">
          <Sparkles size={22} className="ot-community-icon" />
          <h2 id="community-title">Made together. Open to everyone.</h2>
          <p>OpenTune is free and open source. Report bugs, suggest features and share the audio add-ons you build.</p>
          <div>
            <a href={links.github} target="_blank" rel="noreferrer"><SiGithub size={16} /> GitHub</a>
            <a href={links.discord} target="_blank" rel="noreferrer"><SiDiscord size={16} /> Discord</a>
            <a href={links.web} target="_blank" rel="noreferrer">Web player ↗</a>
            <a href={links.tv} target="_blank" rel="noreferrer">OpenTune on TV ↗</a>
            <a href="https://justshivv.github.io/Opentune/addons/" target="_blank" rel="noreferrer">Build an add-on ↗</a>
          </div>
        </section>
      </div>
      {lyrics && (
        <Suspense fallback={null}>
          <LyricsSettings onClose={() => setLyrics(false)} />
        </Suspense>
      )}
    </div>
  );
}
