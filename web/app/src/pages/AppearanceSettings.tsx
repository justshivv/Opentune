import { Check } from 'lucide-react';
import { ACCENTS, PLAYER_STYLES, useAppearance } from '@/state/appearance';
import { Toggle } from './IntegrationSettings';
import { settingsUi } from './settingsStyles';

const label = (value: string) => value.replace(/-/g, ' ').replace(/^./, (s) => s.toUpperCase());

/** A row of choices with the picked one raised, as the app's segmented buttons. */
function Segmented<T extends string>({ title, value, options, onChange }: { title: string; value: T; options: [T, string][]; onChange: (v: T) => void }) {
  return (
    <div className={`${settingsUi.row} flex-wrap gap-3`}>
      <span>{title}</span>
      <div className="ot-segmented" role="radiogroup" aria-label={title}>
        {options.map(([v, name]) => (
          <button key={v} role="radio" aria-checked={value === v} onClick={() => onChange(v)}>{name}</button>
        ))}
      </div>
    </div>
  );
}

export function AppearanceSettings() {
  const a = useAppearance();
  return (
    <>
      <section className={settingsUi.group} aria-label="Appearance">
        <Segmented title="Theme" value={a.mode} options={[['system', 'System'], ['light', 'Light'], ['dark', 'Dark']]} onChange={(mode) => a.update({ mode })} />
        <Toggle label="Pure black" detail="True black surfaces in dark mode." checked={a.pureBlack} onChange={(pureBlack) => a.update({ pureBlack })} />
        <Toggle label="Colors from artwork" detail="Let the playing song color OpenTune." checked={a.artworkColors} onChange={(artworkColors) => a.update({ artworkColors })} />
        <fieldset className="ot-accent-picker">
          <legend>Accent color{a.artworkColors && <span> · picking one turns off colors from artwork</span>}</legend>
          <div>
            {ACCENTS.map(([name, color]) => (
              <button key={color} aria-label={name} title={name} aria-pressed={!a.artworkColors && a.accent === color} onClick={() => a.update({ accent: color, artworkColors: false })} style={{ background: color }}>
                {!a.artworkColors && a.accent === color && <Check size={16} strokeWidth={3} />}
              </button>
            ))}
          </div>
        </fieldset>
      </section>
      <h3 className={settingsUi.sectionHeader}>Now Playing</h3>
      <section className={settingsUi.group} aria-label="Now Playing">
        <Segmented title="Background" value={a.playerBackground} options={[['mesh', 'Mesh'], ['gradient', 'Gradient'], ['echo', 'Echo glow']]} onChange={(playerBackground) => a.update({ playerBackground })} />
        <label className={settingsUi.controlRow}>
          Player style
          <select className={settingsUi.select} value={a.playerStyle} onChange={(e) => a.update({ playerStyle: e.target.value as typeof a.playerStyle })}>
            {PLAYER_STYLES.map((v) => <option key={v} value={v}>{label(v)}</option>)}
          </select>
        </label>
        <Toggle label="Animations" detail="Moving artwork, the player's waves and page motion. Your system's reduced-motion setting also applies." checked={a.motion} onChange={(motion) => a.update({ motion })} />
      </section>
      <p className={settingsUi.footer}>Saved in this browser. Wallpaper colors are in the Android app.</p>
    </>
  );
}
