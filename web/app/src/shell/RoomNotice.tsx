import { useEffect, useState } from 'react';
import { useRoom } from '@/together/room';

/** A room's short messages ("Only the host can change the song"), for a few seconds over the page. */
export function RoomNotice() {
  const notice = useRoom((s) => s.notice);
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
