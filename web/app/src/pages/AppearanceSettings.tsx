import { ACCENTS, PLAYER_STYLES, useAppearance } from '@/state/appearance';
import { Toggle } from './IntegrationSettings';
import { settingsUi } from './settingsStyles';
const label = (value: string) => value.replace(/-/g, ' ').replace(/^./, s => s.toUpperCase());
export function AppearanceSettings() {
  const a = useAppearance();
  return <>
    <h2 className={settingsUi.sectionHeader}>Appearance</h2>
    <section className={settingsUi.group} aria-label="Appearance">
      <label className={settingsUi.controlRow}>Theme<select className={settingsUi.select} value={a.mode} onChange={e => a.update({ mode: e.target.value as typeof a.mode })}>
        {['system', 'light', 'dark'].map(v => <option key={v} value={v}>{label(v)}</option>)}
      </select></label>
      <Toggle label="Pure black" detail="True black surfaces in dark mode." checked={a.pureBlack} onChange={pureBlack => a.update({ pureBlack })} />
      <Toggle label="Colors from artwork" detail="Let the playing song color OpenTune." checked={a.artworkColors} onChange={artworkColors => a.update({ artworkColors })} />
      <fieldset className="ot-accent-picker"><legend>Accent color</legend><div>{ACCENTS.map(([name, color]) =>
        <button key={color} aria-label={name} aria-pressed={a.accent === color} onClick={() => a.update({ accent: color, artworkColors: false })} style={{ background: color }} />
      )}</div></fieldset>
      <label className={settingsUi.controlRow}>Player background<select className={settingsUi.select} value={a.playerBackground} onChange={e => a.update({ playerBackground: e.target.value as typeof a.playerBackground })}>
        <option value="mesh">Mesh</option><option value="gradient">Gradient</option><option value="echo">Echo glow</option>
      </select></label>
      <label className={settingsUi.controlRow}>Player style<select className={settingsUi.select} value={a.playerStyle} onChange={e => a.update({ playerStyle: e.target.value as typeof a.playerStyle })}>
        {PLAYER_STYLES.map(v => <option key={v} value={v}>{label(v)}</option>)}
      </select></label>
      <Toggle label="Animated artwork and backgrounds" detail="System reduced-motion preferences also apply." checked={a.motion} onChange={motion => a.update({ motion })} />
    </section>
    <p className={settingsUi.footer}>Your appearance choices are saved in this browser. Android wallpaper colors are available in the Android app.</p>
  </>;
}
