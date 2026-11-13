import { X } from 'lucide-react';
import { useEffect, useState } from 'react';
import { createPortal } from 'react-dom';

const mac = /Mac|iPhone|iPad/.test(navigator.platform || navigator.userAgent);
const mod = mac ? '⌘' : 'Ctrl';

const SHORTCUTS: [string, string[]][] = [
  ['Play or pause', ['Space']],
  ['Next song', [mod, '→']],
  ['Previous song', [mod, '←']],
  ['Skip forward 10 seconds', ['Shift', '→']],
  ['Skip back 10 seconds', ['Shift', '←']],
  ['Volume up', [mod, '↑']],
  ['Volume down', [mod, '↓']],
  ['Mute', ['M']],
  ['Shuffle', ['S']],
  ['Repeat', ['R']],
  ['Lyrics', ['L']],
  ['Search', ['/']],
  ['Fold the sidebar', [mod, '\\']],
  ['Close the player or panel', ['Esc']],
  ['This list', ['?']],
];

/** "?" shows the keyboard shortcuts, as most web players do. */
export function ShortcutsHelp() {
  const [open, setOpen] = useState(false);
  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      const target = event.target as HTMLElement | null;
      if (target?.isContentEditable || /^(INPUT|TEXTAREA|SELECT)$/.test(target?.tagName ?? '')) return;
      if (event.key === '?' && !event.metaKey && !event.ctrlKey && !event.altKey) {
        if (document.querySelector('[data-allow-gate]')) return;
        event.preventDefault();
        setOpen((o) => !o);
      } else if (event.key === 'Escape') setOpen(false);
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, []);
  if (!open) return null;
  return createPortal(
    <div
      className="fixed inset-0 z-(--z-modal) grid animate-fade place-items-center bg-scrim p-4"
      onPointerDown={(e) => e.target === e.currentTarget && setOpen(false)}
    >
      <div
        className="flex max-h-[min(640px,calc(100vh-32px))] w-[min(420px,100%)] animate-zoom flex-col rounded-[14px] bg-page shadow-glass"
        role="dialog"
        aria-modal="true"
        aria-label="Keyboard shortcuts"
      >
        <header className="flex items-center justify-between px-5 pt-[18px] pb-2">
          <h2 className="type-title-2-emphasized">Keyboard Shortcuts</h2>
          <button
            className="grid size-[30px] place-items-center rounded-full bg-quaternary text-secondary"
            aria-label="Close"
            onClick={() => setOpen(false)}
            autoFocus
          >
            <X size={16} />
          </button>
        </header>
        <dl className="overflow-y-auto px-5 pb-5">
          {SHORTCUTS.map(([what, keys]) => (
            <div key={what} className="flex items-center justify-between gap-4 border-b-[0.5px] border-divider py-2 last:border-0">
              <dt className="type-body text-primary">{what}</dt>
              <dd className="flex gap-1">
                {keys.map((k) => (
                  <kbd key={k} className="min-w-6 rounded-[6px] bg-quaternary px-1.5 py-0.5 text-center type-callout-emphasized text-secondary">
                    {k}
                  </kbd>
                ))}
              </dd>
            </div>
          ))}
        </dl>
      </div>
    </div>,
    document.body,
  );
}
