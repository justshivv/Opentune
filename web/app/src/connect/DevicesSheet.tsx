import { AudioLines, Plus, X } from 'lucide-react';
import { useEffect } from 'react';
import { useNavigate } from 'react-router';
import { DeviceIcon, doing } from './parts';
import { closeDevices, transferTo, useConnect } from './connect';

const row = 'flex w-full items-center gap-3 rounded-[14px] px-3 py-2.5 text-left hover:bg-quaternary';

/** The person's devices, to move playback to one of them; opened from the player bar. */
export function DevicesSheet() {
  const open = useConnect((s) => s.devicesOpen);
  const view = useConnect((s) => s.view);
  const name = useConnect((s) => s.saved.name);
  const navigate = useNavigate();
  useEffect(() => {
    if (!open) return;
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') closeDevices(); };
    addEventListener('keydown', onKey);
    return () => removeEventListener('keydown', onKey);
  }, [open]);
  if (!open) return null;
  const settings = () => { closeDevices(); navigate('/settings/devices'); };
  const pick = (id: string) => { transferTo(id); closeDevices(); };
  return (
    <div className="fixed inset-0 z-[calc(var(--z-web-chrome)+5)] grid place-items-center bg-black/30" onClick={closeDevices}>
      <div className="glass-pill w-[min(380px,calc(100%-32px))] rounded-[22px] p-3" role="dialog" aria-label="Your devices" onClick={(e) => e.stopPropagation()}>
        <div className="mb-1 flex items-center justify-between px-2 pt-1">
          <h2 className="type-title-3-emphasized text-primary">Your devices</h2>
          <button className="grid size-8 place-items-center rounded-full hover:bg-quaternary" aria-label="Close" onClick={closeDevices}><X size={18} /></button>
        </div>
        {!view ? (
          <p className="px-3 py-3 type-callout text-secondary">Play on your phone from here, and the other way round. Add your phone to start.</p>
        ) : (
          <>
            <button className={row} onClick={() => pick(view.myId)}>
              <DeviceIcon form="web" />
              <span className="min-w-0 flex-1">
                <span className={`block truncate type-body-emphasized ${view.activeId === view.myId ? 'text-key' : 'text-primary'}`}>{name}</span>
                <span className="block type-caption-1 text-secondary">This browser</span>
              </span>
              {view.activeId === view.myId && <AudioLines size={18} className="text-key" />}
            </button>
            {view.devices.map((d) => (
              <button key={d.id} className={row} onClick={() => pick(d.id)}>
                <DeviceIcon form={d.form} />
                <span className="min-w-0 flex-1">
                  <span className={`block truncate type-body-emphasized ${view.activeId === d.id ? 'text-key' : 'text-primary'}`}>{d.name || 'Another device'}</span>
                  {doing(d) && <span className="block truncate type-caption-1 text-secondary">{doing(d)}</span>}
                </span>
                {view.activeId === d.id && <AudioLines size={18} className="text-key" />}
              </button>
            ))}
            {!view.devices.length && (
              <p className="px-3 py-3 type-callout text-secondary">{view.relays ? 'Open OpenTune on your phone to see it here.' : 'Connecting…'}</p>
            )}
          </>
        )}
        <button className={`${row} mt-1 text-primary`} onClick={settings}><Plus size={18} /> <span className="type-body">Add a device</span></button>
      </div>
    </div>
  );
}
