import { useEffect, useState } from 'react';
import { useConnect } from '@/connect/connect';
import { useRoom } from '@/together/room';

/** Short messages from a room ("Only the host can change the song") or from Connect, for a few seconds over the page. */
export function RoomNotice() {
  const fromRoom = useRoom((s) => s.notice);
  const fromConnect = useConnect((s) => s.notice);
  const notice = !fromConnect || (fromRoom && fromRoom.at > fromConnect.at) ? fromRoom : fromConnect;
  const [shown, setShown] = useState(false);
  useEffect(() => {
    if (!notice) return;
    setShown(true);
    const timer = setTimeout(() => setShown(false), 3500);
    return () => clearTimeout(timer);
  }, [notice]);
  if (!notice) return null;
  return (
    <p key={notice.at} className="ot-room-notice glass-pill" data-shown={shown || undefined} role="status">
      {notice.text}
    </p>
  );
}
