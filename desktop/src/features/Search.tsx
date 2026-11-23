import { useEffect, useState } from 'react';
import { ChevronLeft, ChevronRight, Search as SearchIcon, X } from 'lucide-react';
import {
  extractAppError,
  type DesktopApi,
  type MediaItem,
  type MediaKind,
  type Page,
} from '../api/types';
import { EmptyState, ErrorBanner } from '../components/StatusView';
import TitleCard, { mediaKey } from '../components/TitleCard';
import { useLatestRequest } from '../hooks/useLatestRequest';

const SEARCH_LIMIT = 60;

interface SearchProps {
  api: DesktopApi;
  onlineEnabled?: boolean;
  localEnabled?: boolean;
  dense: boolean;
  savedKeys: Set<string>;
  onSelect: (item: MediaItem) => void;
  onPlayLive: (item: MediaItem) => void;
  onToggleSave: (item: MediaItem) => void;
}

export default function Search({
  api,
  onlineEnabled = false,
  localEnabled = true,
  dense,
  savedKeys,
  onSelect,
  onPlayLive,
  onToggleSave,
}: SearchProps) {
  const [onlineItems, setOnlineItems] = useState<MediaItem[]>([]);
  const [onlinePending, setOnlinePending] = useState(false);
  const [onlineError, setOnlineError] = useState<string | null>(null);
  const [onlineRetry, setOnlineRetry] = useState(0);
  const [query, setQuery] = useState('');
  const [kindFilter, setKindFilter] = useState<MediaKind | 'all'>('all');
  const [offset, setOffset] = useState(0);
  const [result, setResult] = useState<Page<MediaItem> | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const req = useLatestRequest();

  useEffect(() => {
    if (!localEnabled || !api.catalogQuery) { setLoading(false); setResult(null); return; }
    const trimmed = query.trim();
    const token = req.begin();
    setLoading(true);
    setError(null);
    api
      .catalogQuery({
        kind: kindFilter === 'all' ? undefined : kindFilter,
        query: trimmed || undefined,
        offset,
        limit: SEARCH_LIMIT,
        sort: 'recommended',
      })
      .then((res) => {
        if (!req.isCurrent(token)) return;
        setResult(res);
        setLoading(false);
      })
      .catch((err) => {
        if (!req.isCurrent(token)) return;
        setError(extractAppError(err, 'Unable to search your library.').message);
        setLoading(false);
      });
  }, [api, query, kindFilter, offset, req, localEnabled]);

  useEffect(() => {
    let current = true;
    setOnlineItems([]);
    setOnlineError(null);
    const trimmed = query.trim();
    if (!onlineEnabled || !api.onlineSearch || trimmed.length < 3 || kindFilter === 'live') {
      setOnlinePending(false);
      return;
    }
    setOnlinePending(true);
    const timeout = setTimeout(() => {
      void api.onlineSearch!({ query: trimmed }).then((items) => {
        if (current) { setOnlineItems(items); setOnlinePending(false); }
      }).catch((err) => {
        if (current) { setOnlineError(extractAppError(err, 'Unable to search online.').message); setOnlinePending(false); }
      });
    }, 350);
    return () => { current = false; clearTimeout(timeout); };
  }, [api, onlineEnabled, query, kindFilter, onlineRetry]);

  const visibleOnline = onlineItems.filter((item) => kindFilter === 'all' || item.ref.kind === kindFilter);
  const total = result?.total ?? 0;
  const totalPages = Math.max(1, Math.ceil(total / SEARCH_LIMIT));
  const currentPage = Math.floor(offset / SEARCH_LIMIT) + 1;

  return (
    <div className="section-pad search-page">
      <div className="page-heading">
        <div>
          <span className="eyebrow">FIND YOUR NEXT FAVOURITE</span>
          <h1>Search</h1>
        </div>
        <span className="keyboard-hint">Ctrl K</span>
      </div>

      <div className="search-field">
        <SearchIcon size={24} />
        <input
          autoFocus
          value={query}
          onChange={(e) => {
            setQuery(e.target.value);
            setOffset(0);
          }}
          placeholder="Search movies, series, or channels…"
          aria-label="Search your library"
        />
        {query && (
          <button
            type="button"
            className="icon-button"
            aria-label="Clear search"
            onClick={() => {
              setQuery('');
              setOffset(0);
            }}
          >
            <X size={18} />
          </button>
        )}
      </div>

      <div className="chips search-chips" role="group" aria-label="Filter by type">
        {(
          [
            ['all', 'All'],
            ['movie', 'Movies'],
            ['series', 'Series'],
            ['live', 'Live TV'],
          ] as const
        ).map(([val, label]) => (
          <button
            type="button"
            key={val}
            className={kindFilter === val ? 'chip selected' : 'chip'}
            onClick={() => {
              setKindFilter(val);
              setOffset(0);
            }}
          >
            {label}
          </button>
        ))}
      </div>

      {error && <ErrorBanner message={error} />}

      {localEnabled && <div className="section-heading">
        <h2>
          {query.trim() ? `Results for “${query.trim()}”` : 'Popular in your library'}
          <span className="tiny-count">{total.toLocaleString()}</span>
        </h2>
        {loading && <span className="subtle">Searching…</span>}
      </div>

      }
      {result && result.items.length > 0 ? (
        <>
          <div className={`poster-grid ${dense ? 'dense' : ''}`}>
            {result.items.map((item) => (
              <TitleCard
                key={mediaKey(item.ref)}
                item={item}
                saved={savedKeys.has(mediaKey(item.ref))}
                onSelect={(it) => (it.ref.kind === 'live' ? onPlayLive(it) : onSelect(it))}
                onToggleSave={onToggleSave}
              />
            ))}
          </div>

          {totalPages > 1 && (
            <div className="pager-bar" aria-label="Search pagination">
              <button
                type="button"
                className="button secondary compact"
                disabled={offset === 0}
                onClick={() => setOffset(Math.max(0, offset - SEARCH_LIMIT))}
              >
                <ChevronLeft size={14} /> Previous
              </button>
              <span className="subtle">
                Page {currentPage} of {totalPages}
              </span>
              <button
                type="button"
                className="button secondary compact"
                disabled={offset + SEARCH_LIMIT >= total}
                onClick={() => setOffset(offset + SEARCH_LIMIT)}
              >
                Next <ChevronRight size={14} />
              </button>
            </div>
          )}
        </>
      ) : !loading && !onlineEnabled ? (
        <EmptyState
          title="Nothing by that name. Yet."
          description="Try a different title or broaden your filter."
          action={query ? 'Clear search' : undefined}
          onAction={query ? () => setQuery('') : undefined}
        />
      ) : null}
      {onlineEnabled && <section aria-label="Online library results">
        <div className="section-heading"><h2>Online Library <span className="tiny-count">{visibleOnline.length}</span></h2>{onlinePending && <span role="status">Searching online...</span>}</div>
        {onlineError && <ErrorBanner message={onlineError} retryable onRetry={() => setOnlineRetry((value) => value + 1)} />}
        <div className={`poster-grid ${dense ? 'dense' : ''}`}>{visibleOnline.map((item) => <TitleCard key={mediaKey(item.ref)} item={item} saved={savedKeys.has(mediaKey(item.ref))} onSelect={onSelect} onToggleSave={onToggleSave} />)}</div>
        {!onlinePending && !onlineError && visibleOnline.length === 0 && <p className="subtle">{query.trim().length < 3 ? 'Enter at least 3 characters to search movies and series online.' : kindFilter === 'live' ? 'Online Library offers movies and series.' : 'No online titles found. Try another title.'}</p>}
      </section>}
    </div>
  );
}
