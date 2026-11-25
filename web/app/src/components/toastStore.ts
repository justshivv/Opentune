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
