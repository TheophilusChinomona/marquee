import { useEffect, useState } from 'react';
import { ArrowRight, Check, Info, Play, Plus, Radio, RefreshCw } from 'lucide-react';
import {
  extractAppError,
  type DesktopApi,
  type HomeSectionsResponse,
  type MediaItem,
} from '../api/types';
import Rail from '../components/Rail';
import { EmptyState, ErrorBanner } from '../components/StatusView';
import { isSafeImageUrl, mediaKey } from '../components/TitleCard';
import { useLatestRequest } from '../hooks/useLatestRequest';
import type { NavPage } from '../components/Header';

interface HomeProps {
  api: DesktopApi;
  profileId: string | null;
  refreshStamp: number;
  connected: boolean;
  savedKeys: Set<string>;
  onSelect: (item: MediaItem) => void;
  onPlay: (item: MediaItem, resume: boolean) => void;
  onToggleSave: (item: MediaItem) => void;
  onNavigate: (page: NavPage) => void;
  onRefreshLibrary: () => void;
}

export default function Home({
  api,
  profileId,
  refreshStamp,
  connected,
  savedKeys,
  onSelect,
  onPlay,
  onToggleSave,
  onNavigate,
  onRefreshLibrary,
}: HomeProps) {
  const [data, setData] = useState<HomeSectionsResponse | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [heroIndex, setHeroIndex] = useState(0);
  const [reloadCount, setReloadCount] = useState(0);
  const req = useLatestRequest();

  useEffect(() => {
    if (!api.homeSections) {
      setLoading(false);
      return;
    }
    const token = req.begin();
    setLoading(true);
    setError(null);
    api
      .homeSections()
      .then((res) => {
        if (!req.isCurrent(token)) return;
        setData(res);
        setHeroIndex(0);
        setLoading(false);
      })
      .catch((err) => {
        if (!req.isCurrent(token)) return;
        setError(extractAppError(err, 'Unable to load your home screen.').message);
        setLoading(false);
      });
  }, [api, profileId, refreshStamp, reloadCount, req]);

  if (loading && !data) {
    return (
      <section className="section-pad">
        <div className="empty-state">
          <p role="status">Loading your library…</p>
        </div>
      </section>
    );
  }

  if (error && !data) {
    return (
      <section className="section-pad" style={{ paddingTop: 40 }}>
        <ErrorBanner
          message={error}
          retryable
          onRetry={() => setReloadCount((c) => c + 1)}
        />
      </section>
    );
  }

  const hasItems = Boolean(data && (data.hero.length > 0 || data.sections.length > 0));
  if (!hasItems) {
    return (
      <section className="section-pad">
        <EmptyState
          title={
            data?.cacheStatus.hasCatalogue
              ? 'Your provider returned no titles'
              : 'Synchronize your library'
          }
          description={
            data?.cacheStatus.hasCatalogue
              ? 'Refresh your library or check your provider account if you expected movies, series, or live channels.'
              : connected
                ? 'Load movies, series, and live channels from your provider to populate Home.'
                : 'Reconnect your provider to refresh and load your library.'
          }
          action={connected ? 'Refresh library' : 'Connect provider'}
          onAction={connected ? onRefreshLibrary : () => onNavigate('Connect')}
        />
      </section>
    );
  }

  const heroes = data!.hero;
  const spotlight = heroes[Math.min(heroIndex, Math.max(0, heroes.length - 1))];
  const heroArt =
    spotlight && isSafeImageUrl(spotlight.backdropUrl || spotlight.posterUrl)
      ? spotlight.backdropUrl || spotlight.posterUrl
      : undefined;

  return (
    <>
      {error && (
        <div className="section-pad" style={{ paddingTop: 18 }}>
          <ErrorBanner message={error} retryable onRetry={() => setReloadCount((c) => c + 1)} />
        </div>
      )}

      {spotlight && (
        <section
          className="hero alternate-hero"
          style={
            heroArt
              ? ({ '--hero': `url("${heroArt}")` } as React.CSSProperties)
              : undefined
          }
        >
          <div className="hero-art" />
          <div className="hero-vignette" />
          <div className="hero-content">
            <div className="feature-label">
              <span className="red-line" /> FROM YOUR LIBRARY
            </div>
            <div className="hero-kind">
              <span className="mini-mark">M</span>{' '}
              {spotlight.ref.kind === 'series' ? 'SERIES' : 'FILM'}
            </div>
            <h1 className="hero-name">{spotlight.name}</h1>
            <div className="metadata">
              {spotlight.year && <span>{spotlight.year}</span>}
              {spotlight.categoryName && <span>{spotlight.categoryName}</span>}
              {spotlight.rating && spotlight.rating > 0 && (
                <span>★ {spotlight.rating.toFixed(1)}</span>
              )}
            </div>
            <div className="hero-actions" style={{ marginTop: 26 }}>
              <button
                type="button"
                className="button primary"
                onClick={() => onPlay(spotlight, true)}
              >
                <Play size={17} fill="currentColor" />{' '}
                {spotlight.progress ? 'Resume watching' : 'Watch now'}
              </button>
              <button
                type="button"
                className="button secondary"
                onClick={() => onSelect(spotlight)}
              >
                <Info size={17} /> More info
              </button>
              <button
                type="button"
                className={`round-button ${savedKeys.has(mediaKey(spotlight.ref)) ? 'active' : ''}`}
                onClick={() => onToggleSave(spotlight)}
                aria-label={
                  savedKeys.has(mediaKey(spotlight.ref)) ? 'Remove from My List' : 'Add to My List'
                }
              >
                {savedKeys.has(mediaKey(spotlight.ref)) ? (
                  <Check size={21} />
                ) : (
                  <Plus size={21} />
                )}
              </button>
            </div>
          </div>

          {heroes.length > 1 && (
            <div className="hero-pagination">
              {heroes.map((h, idx) =>
                idx === heroIndex ? (
                  <span key={mediaKey(h.ref)} className="current" />
                ) : (
                  <button
                    type="button"
                    key={mediaKey(h.ref)}
                    aria-label={`Spotlight ${h.name}`}
                    onClick={() => setHeroIndex(idx)}
                  />
                ),
              )}
              <span className="pagination-label">
                {String(heroIndex + 1).padStart(2, '0')} / {String(heroes.length).padStart(2, '0')}
              </span>
            </div>
          )}
          <span className="hero-corner">
            {data!.cacheStatus.stale && connected ? (
              <button type="button" className="text-button" onClick={onRefreshLibrary}>
                <RefreshCw size={12} /> Refresh library
              </button>
            ) : (
              <>
                FROM YOUR LIBRARY <span>✦</span>
              </>
            )}
          </span>
        </section>
      )}

      {data!.sections.map((section, idx) => (
        <div key={section.id}>
          <Rail
            title={section.title}
            eyebrow={section.eyebrow}
            items={section.items}
            mode={section.kind === 'continue' ? 'continue' : 'poster'}
            savedKeys={savedKeys}
            onSelect={(item) =>
              item.ref.kind === 'live' ? onPlay(item, false) : onSelect(item)
            }
            onToggleSave={onToggleSave}
            onViewAll={
              section.kind === 'myList'
                ? () => onNavigate('My List')
                : section.kind === 'live'
                  ? () => onNavigate('Live TV')
                  : section.kind === 'forYou' || section.kind === 'newReleases'
                    ? () => onNavigate('Movies')
                    : undefined
            }
          />
          {idx === 1 && (
            <section className="live-banner section-pad">
              <div className="live-banner-inner">
                <span className="live-icon">
                  <Radio size={24} />
                </span>
                <div>
                  <span className="eyebrow">SOMETHING GOOD IS ON</span>
                  <h2>A world of live television.</h2>
                  <p>Browse your provider’s live channels and programme guide in one place.</p>
                </div>
                <button
                  type="button"
                  className="button secondary"
                  onClick={() => onNavigate('Live TV')}
                >
                  Explore live TV <ArrowRight size={16} />
                </button>
              </div>
            </section>
          )}
        </div>
      ))}
    </>
  );
}
