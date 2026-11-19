import { Laptop, Smartphone } from 'lucide-react';
import type { Device } from './session';

/** The line a device's song shows under its name: "Song · Artist", or what it's doing instead. */
export const doing = (d: Device): string | null => {
  const s = d.state;
  if (!s) return null;
  if (!s.track) return s.private ? 'Playing something of its own' : null;
  const what = [s.track.title, s.track.artist].filter(Boolean).join(' · ');
  return s.playing ? what : `Paused · ${what}`;
};

export const DeviceIcon = ({ form, size = 20 }: { form: string; size?: number }) => (form === 'web' ? <Laptop size={size} /> : <Smartphone size={size} />);
