import { Check } from 'lucide-react';
import { useEffect, useLayoutEffect, useRef, useState, type ReactNode } from 'react';
import { createPortal } from 'react-dom';

export type MenuPlacement = 'above-start' | 'below-end';

/** Where a menu opened by a right-click (or a long press) sits: the pointer's place on screen. */
export interface MenuPoint {
  x: number;
  y: number;
}

interface MenuProps {
  /** The element the menu opens from, or the point it was asked for at. */
  anchor: HTMLElement | MenuPoint;
  placement?: MenuPlacement;
  onClose: () => void;
  label: string;
  children: ReactNode;
}

/**
 * Apple's contextual menu (report §8): a glass panel, 185px minimum, 13px
 * items, hairline separators, red for destructive items. Closes on Escape, a
 * click outside, or a scroll of the page under it.
 */
export function Menu({ anchor, placement = 'below-end', onClose, label, children }: MenuProps) {
  const panel = useRef<HTMLDivElement>(null);
  const [position, setPosition] = useState<React.CSSProperties>({ visibility: 'hidden' });

  useLayoutEffect(() => {
    if (!(anchor instanceof HTMLElement)) {
      // At the pointer, kept on screen: below it when there's room, else above it.
      const gap = 4;
      const left = Math.min(Math.max(8, anchor.x), window.innerWidth - 200);
      const below = anchor.y < window.innerHeight * 0.6;
      setPosition(
        below
          ? { left, top: anchor.y + gap, maxHeight: window.innerHeight - anchor.y - 3 * gap }
          : { left, bottom: window.innerHeight - anchor.y + gap, maxHeight: anchor.y - 3 * gap },
      );
      return;
    }
    const rect = anchor.getBoundingClientRect();
    const gap = 6;
    setPosition(
      placement === 'above-start'
        ? { left: Math.max(8, rect.left), bottom: window.innerHeight - rect.top + gap, maxHeight: rect.top - 2 * gap }
        : {
            right: Math.max(8, window.innerWidth - rect.right),
            top: rect.bottom + gap,
            maxHeight: window.innerHeight - rect.bottom - 2 * gap,
          },
    );
  }, [anchor, placement]);

  useEffect(() => {
    panel.current?.querySelector<HTMLElement>('[role^="menuitem"]')?.focus({ preventScroll: true });
    const onKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        event.stopPropagation();
        onClose();
        if (anchor instanceof HTMLElement) anchor.focus();
      } else if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
        event.preventDefault();
        const items = [...(panel.current?.querySelectorAll<HTMLElement>('[role^="menuitem"]:not(:disabled)') ?? [])];
        const index = items.indexOf(document.activeElement as HTMLElement);
        const next = event.key === 'ArrowDown' ? index + 1 : index - 1;
        items[(next + items.length) % items.length]?.focus();
      }
    };
    const onScroll = (event: Event) => {
      if (!panel.current?.contains(event.target as Node)) onClose();
    };
    document.addEventListener('keydown', onKey, true);
    document.addEventListener('scroll', onScroll, true);
    window.addEventListener('resize', onClose);
    return () => {
      document.removeEventListener('keydown', onKey, true);
      document.removeEventListener('scroll', onScroll, true);
      window.removeEventListener('resize', onClose);
    };
  }, [anchor, onClose]);

  return createPortal(
    <>
      <div className="fixed inset-0 z-[calc(var(--z-contextual-menus)-1)]" onPointerDown={onClose} aria-hidden="true" />
      <div
        ref={panel}
        // Apple's amp-contextual-menu (report §8).
        className="fixed z-(--z-contextual-menus) max-w-[300px] min-w-[185px] animate-menu-in overflow-y-auto rounded-[10px] bg-thick-material p-[5px] shadow-[0_0_0_0.5px_var(--vibrantDivider),0_10px_40px_var(--glassMaterialShadowColor)] [backdrop-filter:saturate(2.2)_blur(32px)]"
        style={position}
        role="menu"
        aria-label={label}
      >
        {children}
      </div>
    </>,
    document.body,
  );
}

interface MenuItemProps {
  onSelect: () => void;
  icon?: ReactNode;
  /** Shows a checkmark (a radio-style choice); null leaves the space empty. */
  checked?: boolean | null;
  destructive?: boolean;
  disabled?: boolean;
  /** Extra text at the trailing edge. */
  trailing?: ReactNode;
  children: ReactNode;
}

export function MenuItem({ onSelect, icon, checked, destructive, disabled, trailing, children }: MenuItemProps) {
  return (
    <button
      className="flex min-h-7 w-full items-center gap-2 rounded-[5px] px-2 py-1 text-start type-body text-primary enabled:hover:bg-quinary focus-visible:bg-quinary disabled:text-tertiary data-destructive:text-destructive"
      role={checked === undefined ? 'menuitem' : 'menuitemradio'}
      aria-checked={checked === undefined ? undefined : !!checked}
      data-destructive={destructive || undefined}
      disabled={disabled}
      onClick={onSelect}
    >
      {checked !== undefined && <span className="inline-grid w-3.5 flex-none place-items-center text-key">{checked && <Check size={14} strokeWidth={2.6} />}</span>}
      {icon && <span className="inline-grid w-5 flex-none place-items-center text-secondary">{icon}</span>}
      <span className="min-w-0 flex-1 truncate">{children}</span>
      {trailing && <span className="flex-none text-secondary">{trailing}</span>}
    </button>
  );
}

export function MenuSeparator() {
  return <div className="mx-2 my-[5px] border-t-[0.5px] border-t-vibrant-divider" role="separator" />;
}

/** A non-interactive heading inside a menu. */
export function MenuHeader({ children }: { children: ReactNode }) {
  return <div className="px-2 pt-1.5 pb-1 type-subhead-emphasized text-secondary">{children}</div>;
}
