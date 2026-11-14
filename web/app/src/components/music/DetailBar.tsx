import { ChevronLeft } from 'lucide-react';
import { useEffect, useRef, useState } from 'react';
import { useNavigate } from 'react-router';
import { PlayIcon } from '@/components/icons/Transport';
import { Artwork } from './Artwork';

interface DetailBarProps {
  /** What the page is: the album, playlist or artist's name. */
  title: string;
  thumbnail: string | null;
  /** The page's own heading: once it has scrolled up under the bar, the bar names the page. */
  watch: React.RefObject<HTMLElement | null>;
  onPlay: () => void;
  canPlay: boolean;
  /** An artist's photo is drawn round. */
  round?: boolean;
  /** The bar starts over a photograph (an artist's), where Back needs a dark glass of its own. */
  overPhoto?: boolean;
}

/**
 * The bar along the top of an album, playlist or artist page, as in the phone app: Back at its
 * left from the start, and once the page's heading has scrolled up under it, a frosted strip of
 * the page's colour with the picture, the name and Play, so the page stays named.
 */
export function DetailBar({ title, thumbnail, watch, onPlay, canPlay, round, overPhoto }: DetailBarProps) {
  const navigate = useNavigate();
  const bar = useRef<HTMLDivElement>(null);
  const [stuck, setStuck] = useState(false);
  useEffect(() => {
    const page = document.getElementById('scrollable-page');
    if (!page) return;
    // This bar has its own glass; the page's top-edge blur would only smear it.
    page.dataset.ownBar = '';
    let frame = 0;
    const mark = () => {
      frame = 0;
      const heading = watch.current?.getBoundingClientRect();
      const strip = bar.current?.getBoundingClientRect();
      if (heading && strip) setStuck(heading.bottom <= strip.bottom);
    };
    const onScroll = () => { if (!frame) frame = requestAnimationFrame(mark); };
    mark();
    page.addEventListener('scroll', onScroll, { passive: true });
    return () => {
      page.removeEventListener('scroll', onScroll);
      cancelAnimationFrame(frame);
      delete page.dataset.ownBar;
    };
  }, [watch]);
  // Back where the visitor came from; a page opened straight from a link goes Home instead.
  const back = () => ((window.history.state as { idx?: number } | null)?.idx ? navigate(-1) : navigate('/home'));
  return (
    <div ref={bar} className="ot-detail-bar" data-stuck={stuck || undefined} data-over-photo={overPhoto || undefined}>
      <button className="ot-detail-back" aria-label="Back" title="Back" onClick={back}>
        <ChevronLeft size={22} strokeWidth={2.4} />
      </button>
      <div className="ot-detail-bar-title" aria-hidden={!stuck}>
        {thumbnail && (
          <span className="size-8 shrink-0">
            <Artwork src={thumbnail} displayWidth={32} radius={round ? '999px' : '6px'} />
          </span>
        )}
        <span className="min-w-0 truncate type-title-3-emphasized">{title}</span>
      </div>
      <button className="ot-detail-bar-play" aria-label={`Play ${title}`} tabIndex={stuck ? 0 : -1} aria-hidden={!stuck} onClick={onPlay} disabled={!canPlay}>
        <PlayIcon size={15} />
      </button>
    </div>
  );
}
