import { useEffect, useState } from 'react';
import { createPortal } from 'react-dom';
import { create } from 'zustand';

interface ToastState {
  message: string | null;
  /** Bumped on every show, so the same message twice still shows twice. */
  serial: number;
  show: (message: string) => void;
}

/** A short confirmation at the bottom of the window ("Added to queue"), also read out by screen readers. */
export const useToast = create<ToastState>((set) => ({
  message: null,
  serial: 0,
  show: (message) => set((s) => ({ message, serial: s.serial + 1 })),
}));

export const toast = (message: string) => useToast.getState().show(message);

export function Toaster() {
  const message = useToast((s) => s.message);
  const serial = useToast((s) => s.serial);
  const [visible, setVisible] = useState(false);
  useEffect(() => {
    if (!serial) return;
    setVisible(true);
    const timer = setTimeout(() => setVisible(false), 2400);
    return () => clearTimeout(timer);
  }, [serial]);
  return createPortal(
    <div
      role="status"
      aria-live="polite"
      className="pointer-events-none fixed inset-x-0 bottom-[calc(var(--player-pill-height)+var(--player-bottom-offset)+16px)] z-(--z-contextual-menus) flex justify-center px-4"
    >
      <div
        className={`max-w-[min(92vw,420px)] truncate rounded-pill bg-thick-material px-4 py-2 type-callout-emphasized text-primary shadow-[0_0_0_0.5px_var(--vibrantDivider),0_10px_30px_var(--glassMaterialShadowColor)] [backdrop-filter:saturate(2.2)_blur(32px)] transition-[opacity,translate] duration-300 ease-(--ot-ease-emph) motion-reduce:transition-none ${
          visible ? 'translate-y-0 opacity-100' : 'translate-y-2 opacity-0'
        }`}
      >
        {message}
      </div>
    </div>,
    document.body,
  );
}
