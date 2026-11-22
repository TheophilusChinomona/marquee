import { useState } from 'react';
import { Check, Film, Play, Plus, Radio, Tv } from 'lucide-react';
import type { MediaItem, MediaRef } from '../api/types';

export function mediaKey(ref: MediaRef): string {
  return `${ref.kind}:${ref.id}`;
}

export function isSafeImageUrl(url?: string): boolean {
  if (!url) return false;
  return url.startsWith('http://') || url.startsWith('https://');
}

interface TitleCardProps {
  item: MediaItem;
  mode?: 'poster' | 'continue';
  saved?: boolean;
  onSelect: (item: MediaItem) => void;
  onToggleSave?: (item: MediaItem) => void;
}

export default function TitleCard({
  item,
  mode = 'poster',
  saved = false,
  onSelect,
  onToggleSave,
}: TitleCardProps) {
  const [imgFailed, setImgFailed] = useState(false);
  const src = mode === 'continue' ? item.backdropUrl || item.posterUrl : item.posterUrl || item.backdropUrl;
  const showImage = Boolean(src && isSafeImageUrl(src) && !imgFailed);

  const progressPct =
    item.progress !== undefined ? Math.round(Math.max(0, Math.min(1, item.progress)) * 100) : null;

  const remainingMin =
    item.durationMs && item.positionMs !== undefined && item.durationMs > 0
      ? Math.max(1, Math.round((item.durationMs - item.positionMs) / 60000))
      : null;

  const subtitleParts: string[] = [];
  if (mode === 'continue') {
    if (remainingMin !== null) {
      subtitleParts.push(`${remainingMin} min remaining`);
    } else if (progressPct !== null) {
      subtitleParts.push(`${progressPct}% watched`);
    }
  } else {
    if (item.year) subtitleParts.push(item.year);
    if (item.categoryName) subtitleParts.push(item.categoryName);
    if (item.rating && item.rating > 0) subtitleParts.push(`★ ${item.rating.toFixed(1)}`);
  }

  const kindLabel =
    item.mediaRefKind ??
    (item.ref.kind === 'series' ? 'SERIES' : item.ref.kind === 'live' ? 'LIVE' : 'MOVIE');

  const FallbackIcon = item.ref.kind === 'live' ? Radio : item.ref.kind === 'series' ? Tv : Film;

  return (
    <article className={`title-card ${mode}`}>
      <button
        type="button"
        className="art-button"
        onClick={() => onSelect(item)}
        aria-label={`View ${item.name}`}
      >
        {showImage ? (
          <img
            src={src}
            alt={item.name}
            loading="lazy"
            onError={() => setImgFailed(true)}
          />
        ) : (
          <div className="poster-fallback" aria-hidden="true">
            <FallbackIcon size={24} strokeWidth={1.25} />
            <span>{item.name}</span>
          </div>
        )}
        <span className="art-shade" />

        {mode === 'continue' ? (
          <>
            <span className="continue-title">{item.name}</span>
            <span className="center-play">
              <Play size={22} fill="currentColor" />
            </span>
            {progressPct !== null && (
              <span className="progress-track">
                <span style={{ width: `${progressPct}%` }} />
              </span>
            )}
          </>
        ) : (
          <>
            {item.rank !== undefined ? (
              <span className="rank-badge">{item.rank}</span>
            ) : (
              <span className="poster-quality">{kindLabel}</span>
            )}
            <span className="poster-hover">
              <span className="center-play">
                <Play size={20} fill="currentColor" />
              </span>
              <span>{item.ref.kind === 'live' ? 'Watch live' : 'View details'}</span>
            </span>
          </>
        )}
      </button>

      <div className="card-caption">
        <div>
          <h3>{item.name}</h3>
          {subtitleParts.length > 0 && <p>{subtitleParts.join(' · ')}</p>}
        </div>
        {onToggleSave && item.ref.kind !== 'live' && (
          <button
            type="button"
            className={`small-icon save ${saved ? 'is-saved' : ''}`}
            aria-label={`${saved ? 'Remove' : 'Add'} ${item.name} ${saved ? 'from' : 'to'} My List`}
            onClick={() => onToggleSave(item)}
          >
            {saved ? <Check size={16} /> : <Plus size={16} />}
          </button>
        )}
      </div>
    </article>
  );
}

declare module '../api/types' {
  interface MediaItem {
    mediaRefKind?: string;
  }
}
