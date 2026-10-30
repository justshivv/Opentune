import { GripVertical, Puzzle } from 'lucide-react';
import { useRef, useState } from 'react';
import { allowSource, useAddons } from '@/addons/client';
import { settingsUi } from './settingsStyles';
export function SourceList() {
  const { sources, update, locked } = useAddons();
  const [drag, setDrag] = useState<{ id: string; y: number; offset: number } | null>(null);
  const rows = useRef(new Map<string, HTMLLIElement>());
  const [notice, setNotice] = useState('');
  const move = (from: number, to: number) => {
    const next = [...useAddons.getState().sources];
    if (from < 0 || to < 0 || to >= next.length) return;
    const item = next.splice(from, 1)[0]!; next.splice(to, 0, item); update(next);
    setNotice(`${item.name} moved to position ${to + 1}`);
  };
  return <><p role="status" className="sr-only">{notice}</p><ol className="ot-source-list">{sources.map((s, i) =>
    <li key={s.id} ref={el => { if (el) rows.current.set(s.id, el); else rows.current.delete(s.id); }} className="ot-source-row" data-dragging={drag?.id === s.id}
      style={drag?.id === s.id ? { transform: `translateY(${drag.offset}px) scale(1.02)`, transition: 'background .18s, box-shadow .18s' } : undefined}>
      <span className="ot-source-index">{i + 1}</span><span className="ot-source-icon"><Puzzle size={16} /></span><div><strong>{s.name}</strong><small>{s.admin ? 'Added by admin · ' : ''}{s.ownerOff ? 'Paused by admin' : !s.enabled ? 'Off' : locked.includes(s.id) ? 'Needs your OK in the extension' : 'best available FLAC'}</small>
        {s.enabled && !s.ownerOff && locked.includes(s.id) && <button type="button" className="ot-allow" onClick={() => void allowSource(s.url).then(() => setNotice(`Press Allow in the extension tab for ${s.name}`)).catch(e => setNotice(e instanceof Error ? e.message : 'Permission unavailable'))}>Allow</button>}</div>
      <input type="checkbox" role="switch" className={settingsUi.switch} aria-label={`Enable ${s.name}`} disabled={s.ownerOff} checked={s.enabled && !s.ownerOff} onChange={e => update(sources.map(x => x.id === s.id ? { ...x, enabled: e.target.checked } : x))} />
      <button className="ot-grab" aria-label={`Reorder ${s.name}`} title="Drag to reorder, or use arrow keys" onKeyDown={e => {
        if (e.key === 'ArrowUp' || e.key === 'ArrowDown') { e.preventDefault(); move(i, i + (e.key === 'ArrowUp' ? -1 : 1)); }
      }} onPointerDown={e => { if (e.button !== 0) return; e.currentTarget.setPointerCapture(e.pointerId); setDrag({ id: s.id, y: e.clientY, offset: 0 }); }}
      onPointerMove={e => { if (drag?.id === s.id) setDrag({ ...drag, offset: e.clientY - drag.y }); }}
      onPointerUp={e => {
        if (drag?.id !== s.id) return;
        const target = sources.reduce((closest, item, index) => {
          if (item.id === s.id) return closest;
          const box = rows.current.get(item.id)?.getBoundingClientRect();
          const distance = box ? Math.abs(e.clientY - box.top - box.height / 2) : Infinity;
          return distance < closest.distance ? { index, distance } : closest;
        }, { index: i, distance: Math.abs(drag.offset) });
        move(i, target.index); setDrag(null);
      }} onPointerCancel={() => setDrag(null)} onLostPointerCapture={() => setDrag(null)}><GripVertical size={20} /></button>
      <details><summary>Source options</summary><button disabled={i === 0} onClick={() => move(i, i - 1)}>Move up</button><button disabled={i === sources.length - 1} onClick={() => move(i, i + 1)}>Move down</button>{!s.admin && <button onClick={() => update(sources.filter(x => x.id !== s.id))}>Remove</button>}</details>
    </li>
  )}</ol></>;
}
