import { useState } from 'react';
import { StarFilledIcon, StarIcon } from '@/components/icons/Transport';
import { useLikeStatus, useSetLiked, useSignedIn } from '@/data/music/queries';

interface FavoriteButtonProps {
  videoId: string | null;
  className?: string;
  size?: number;
}

/**
 * Apple's Favorite star, backed by YouTube Music's like (a liked song joins
 * Liked Music). Only shown signed in; the state is read from the track's
 * watch page, as YouTube's own player reads it.
 */
export function FavoriteButton({ videoId, className, size = 18 }: FavoriteButtonProps) {
  const signedIn = useSignedIn();
  const status = useLikeStatus(videoId);
  const setLiked = useSetLiked();
  // Bumped on each like, so the burst plays again every time.
  const [bursts, setBursts] = useState(0);
  if (!signedIn || !videoId) return null;
  const liked = status.data === 'LIKE';
  return (
    <button
      className={`ot-fav ${className ?? ''}`}
      aria-label={liked ? 'Unfavorite' : 'Favorite'}
      aria-pressed={liked}
      data-burst={bursts > 0 || undefined}
      disabled={status.isPending}
      onClick={(event) => {
        event.stopPropagation();
        if (!liked) setBursts((n) => n + 1);
        setLiked.mutate({ videoId, liked: !liked });
      }}
    >
      {liked ? <StarFilledIcon size={size} /> : <StarIcon size={size} />}
      {liked && bursts > 0 && (
        <span key={bursts} className="ot-burst" aria-hidden="true">
          {Array.from({ length: 8 }, (_, i) => <i key={i} style={{ '--a': `${i * 45}deg` } as React.CSSProperties} />)}
        </span>
      )}
    </button>
  );
}
