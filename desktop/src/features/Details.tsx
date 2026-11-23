import { useEffect, useState } from 'react';
import { ArrowLeft, Check, Play, Plus } from 'lucide-react';
import {
  extractAppError,
  type DesktopApi,
  type MediaDetails,
  type MediaItem,
  type MediaRef,
} from '../api/types';
import Rail from '../components/Rail';
import { ErrorBanner } from '../components/StatusView';
import { isSafeImageUrl, mediaKey } from '../components/TitleCard';
import { useLatestRequest } from '../hooks/useLatestRequest';

interface DetailsProps {
  api: DesktopApi;
  itemRef: MediaRef;
  backLabel: string;
  savedKeys: Set<string>;
  onBack: () => void;
  onSelect: (item: MediaItem) => void;
  onPlay: (ref: MediaRef, episodeId?: string, resume?: boolean) => void;
  onToggleSave: (item: MediaItem) => void;
}

export default function Details({
  api,
  itemRef,
  backLabel,
  savedKeys,
  onBack,
  onSelect,
  onPlay,
  onToggleSave,
}: DetailsProps) {
  const [details, setDetails] = useState<MediaDetails | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [season, setSeason] = useState<number>(1);
  const [reloadCount, setReloadCount] = useState(0);
  const req = useLatestRequest();

  useEffect(() => {
    if (!api.mediaDetails) {
      setLoading(false);
      return;
    }
    const token = req.begin();
    setLoading(true);
    setError(null);
    api
      .mediaDetails({ ref: itemRef })
      .then((res) => {
        if (!req.isCurrent(token)) return;
        setDetails(res);
        if (res.seasons && res.seasons.length > 0) {
          const resumeSeason = res.resumeEpisodeId
            ? res.seasons.find((s) => s.episodes.some((ep) => ep.id === res.resumeEpisodeId))
            : undefined;
          setSeason(resumeSeason ? resumeSeason.number : res.seasons[0].number);
        }
        setLoading(false);
      })
      .catch((err) => {
        if (!req.isCurrent(token)) return;
        setError(extractAppError(err, 'Unable to load title details.').message);
        setLoading(false);
      });
  }, [api, itemRef.kind, itemRef.id, reloadCount, req]);

  if (loading && !details) {
    return (
      <section className="section-pad" style={{ paddingTop: 40 }}>
        <button type="button" className="back-button" onClick={onBack}>
          <ArrowLeft size={16} /> Back to {backLabel}
        </button>
        <div className="empty-state">
          <p role="status">Loading title details…</p>
        </div>
      </section>
    );
  }

  if (error || !details) {
    return (
      <section className="section-pad" style={{ paddingTop: 40 }}>
        <button type="button" className="back-button" onClick={onBack}>
          <ArrowLeft size={16} /> Back to {backLabel}
        </button>
        <div style={{ marginTop: 24 }}>
          <ErrorBanner
            message={error ?? 'Title details could not be loaded.'}
            retryable
            onRetry={() => setReloadCount((c) => c + 1)}
          />
        </div>
      </section>
    );
  }

  const art = isSafeImageUrl(details.backdropUrl || details.posterUrl)
    ? details.backdropUrl || details.posterUrl
    : undefined;
  const isSaved = savedKeys.has(mediaKey(details.ref));
  const asMediaItem: MediaItem = {
    ref: details.ref,
    name: details.name,
    categoryId: details.categoryId,
    categoryName: details.categoryName,
    posterUrl: details.posterUrl,
    backdropUrl: details.backdropUrl,
    year: details.year,
    rating: details.rating,
  };

  const activeSeason = details.seasons?.find((s) => s.number === season) ?? details.seasons?.[0];
  const hasResume = Boolean(details.resumePositionMs && details.resumePositionMs > 0);

  return (
    <>
      <section
        className="hero detail-hero alternate-hero"
        style={art ? ({ '--hero': `url("${art}")` } as React.CSSProperties) : undefined}
      >
        <div className="hero-art" />
        <div className="hero-vignette" />
        <div className="hero-content">
          <button type="button" className="back-button" onClick={onBack}>
            <ArrowLeft size={16} /> Back to {backLabel}
          </button>
          <div className="hero-kind">
            <span className="mini-mark">M</span>{' '}
            {details.ref.kind === 'series' ? 'SERIES' : 'FILM'}
          </div>
          <h1 className="hero-name">{details.name}</h1>
          <div className="metadata">
            {details.year && <span>{details.year}</span>}
            {details.rating && details.rating > 0 && <span>★ {details.rating.toFixed(1)}</span>}
            {details.durationMinutes && details.durationMinutes > 0 && (
              <span>
                {details.durationMinutes >= 60
                  ? `${Math.floor(details.durationMinutes / 60)}h ${details.durationMinutes % 60}m`
                  : `${details.durationMinutes} min`}
              </span>
            )}
            {details.seasons && details.seasons.length > 0 && (
              <span>
                {details.seasons.length}{' '}
                {details.seasons.length === 1 ? 'season' : 'seasons'}
              </span>
            )}
          </div>
          {details.plot && <p className="hero-description">{details.plot}</p>}
          <div className="hero-actions" style={{ marginTop: details.plot ? 0 : 22 }}>
            <button
              type="button"
              className="button primary"
              onClick={() => onPlay(details.ref, details.resumeEpisodeId, true)}
            >
              <Play size={17} fill="currentColor" />{' '}
              {hasResume ? 'Resume watching' : 'Watch now'}
            </button>
            {hasResume && (
              <button
                type="button"
                className="button secondary"
                onClick={() => onPlay(details.ref, details.resumeEpisodeId, false)}
              >
                Start over
              </button>
            )}
            <button
              type="button"
              className={`round-button ${isSaved ? 'active' : ''}`}
              onClick={() => onToggleSave(asMediaItem)}
              aria-label={isSaved ? 'Remove from My List' : 'Add to My List'}
            >
              {isSaved ? <Check size={21} /> : <Plus size={21} />}
            </button>
          </div>
          {(details.cast || details.genre || details.categoryName) && (
            <div className="detail-facts">
              {details.cast && (
                <p>
                  <span>Starring</span>
                  {details.cast}
                </p>
              )}
              {(details.genre || details.categoryName) && (
                <p>
                  <span>Genres</span>
                  {details.genre || details.categoryName}
                </p>
              )}
            </div>
          )}
        </div>
      </section>

      {details.ref.kind === 'series' && details.seasons && details.seasons.length > 0 && (
        <section className="section-pad episodes">
          <div className="section-heading">
            <h2>
              Episodes{' '}
              <span className="tiny-count">{activeSeason?.episodes.length ?? 0}</span>
            </h2>
            <label className="sort-control">
              <select
                aria-label="Season"
                value={activeSeason?.number ?? 1}
                onChange={(e) => setSeason(Number(e.target.value))}
              >
                {details.seasons.map((s) => (
                  <option key={s.number} value={s.number}>
                    Season {s.number}
                  </option>
                ))}
              </select>
            </label>
          </div>

          {activeSeason?.episodes.map((ep) => {
            const epArt = isSafeImageUrl(ep.imageUrl || details.backdropUrl || details.posterUrl)
              ? ep.imageUrl || details.backdropUrl || details.posterUrl
              : undefined;
            const epPct =
              ep.progress !== undefined
                ? Math.round(Math.max(0, Math.min(1, ep.progress)) * 100)
                : null;
            return (
              <button
                type="button"
                className="episode-row"
                key={ep.id}
                onClick={() => onPlay(details.ref, ep.id, true)}
              >
                <span className="episode-number">{String(ep.number).padStart(2, '0')}</span>
                <span className="episode-image">
                  {epArt && <img src={epArt} alt="" loading="lazy" />}
                  <Play size={20} fill="currentColor" />
                  {epPct !== null && epPct > 0 && (
                    <span className="progress-track">
                      <span style={{ width: `${epPct}%` }} />
                    </span>
                  )}
                </span>
                <span className="episode-copy">
                  <strong>{ep.title}</strong>
                  {ep.plot && <span>{ep.plot}</span>}
                </span>
                {ep.durationMinutes && ep.durationMinutes > 0 && (
                  <span className="episode-duration">{ep.durationMinutes} min</span>
                )}
              </button>
            );
          })}
        </section>
      )}

      {details.related.length > 0 && (
        <Rail
          title="More like this"
          items={details.related}
          savedKeys={savedKeys}
          onSelect={onSelect}
          onToggleSave={onToggleSave}
        />
      )}
    </>
  );
}
