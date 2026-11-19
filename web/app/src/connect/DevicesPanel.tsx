import { AudioLines, Plus } from 'lucide-react';
import { useNavigate } from 'react-router';
import { useUi } from '@/state/ui';
import { DeviceIcon, doing } from './parts';
import { transferTo, useConnect } from './connect';

// Up Next's rows: 55px, [icon] 40px [name] 1fr [mark] 24px, radius 6px.
const row = 'grid h-[55px] w-full grid-cols-[40px_minmax(0,1fr)_24px] items-center gap-x-3 rounded-row px-2.5 text-left hover:bg-quinary';
const icon = 'grid size-10 place-items-center rounded-[8px] bg-quaternary text-secondary';

/** The side panel's Devices: where the music plays, and a click moves it there (Connect). */
export function DevicesPanel() {
  const view = useConnect((s) => s.view);
  const name = useConnect((s) => s.saved.name);
  const navigate = useNavigate();
  const close = useUi((s) => s.closeSidePanel);
  const settings = () => { close(); navigate('/settings/devices'); };
  const line = (text: string | null, active: boolean) =>
    text ? <span className={`block truncate type-caption-1 ${active ? 'text-key' : 'text-secondary'}`}>{text}</span> : null;

  return (
    <>
      <div className="flex items-center gap-1.5 px-5 pt-[23px] pb-3">
        <h3 className="flex-1 type-title-2-emphasized">Devices</h3>
      </div>
      <div className="flex min-h-0 flex-1 flex-col overflow-y-auto px-2.5 pb-4">
        {!view ? (
          <p className="px-2.5 py-2 type-callout text-secondary">Play on your phone from here, and the other way round.</p>
        ) : (
          <>
            <button className={row} onClick={() => transferTo(view.myId)}>
              <span className={icon}><DeviceIcon form="web" /></span>
              <span className="min-w-0">
                <span className={`block truncate type-body-emphasized ${view.activeId === view.myId ? 'text-key' : 'text-primary'}`}>{name}</span>
                {line('This browser', view.activeId === view.myId)}
              </span>
              {view.activeId === view.myId && <AudioLines size={18} className="text-key" />}
            </button>
            {view.devices.map((d) => (
              <button key={d.id} className={row} onClick={() => transferTo(d.id)}>
                <span className={icon}><DeviceIcon form={d.form} /></span>
                <span className="min-w-0">
                  <span className={`block truncate type-body-emphasized ${view.activeId === d.id ? 'text-key' : 'text-primary'}`}>{d.name || 'Another device'}</span>
                  {line(doing(d) ?? 'Ready to play', view.activeId === d.id)}
                </span>
                {view.activeId === d.id && <AudioLines size={18} className="text-key" />}
              </button>
            ))}
            {!view.devices.length && (
              <p className="px-2.5 py-2 type-callout text-secondary">{view.relays ? 'Open OpenTune on your phone to see it here.' : 'Connecting…'}</p>
            )}
          </>
        )}
        <button className={`${row} mt-1`} onClick={settings}>
          <span className={icon}><Plus size={18} /></span>
          <span className="truncate type-body text-primary">{view ? 'Add a device' : 'Add your phone'}</span>
          <span />
        </button>
      </div>
    </>
  );
}
