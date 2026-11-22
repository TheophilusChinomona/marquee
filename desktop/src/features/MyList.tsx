import { useEffect, useState } from 'react';
import { ChevronLeft, ChevronRight, Heart } from 'lucide-react';
import { extractAppError, type DesktopApi, type MediaItem, type Page } from '../api/types';
import { EmptyState, ErrorBanner } from '../components/StatusView';
import TitleCard, { mediaKey } from '../components/TitleCard';
import { useLatestRequest } from '../hooks/useLatestRequest';

const LIST_LIMIT = 60;

interface MyListProps {
  api: DesktopApi;
  profileId: string | null;
  listVersion: number;
  dense: boolean;
  savedKeys: Set<string>;
  onSelect: (item: MediaItem) => void;
  onToggleSave: (item: MediaItem) => void;
  onExploreMovies: () => void;
}

export default function MyList({
  api,
  profileId,
  listVersion,
  dense,
  savedKeys,
  onSelect,
  onToggleSave,
  onExploreMovies,
}: MyListProps) {
  const [offset, setOffset] = useState(0);
  const [page, setPage] = useState<Page<MediaItem> | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const req = useLatestRequest();

  useEffect(() => {
    setOffset(0);
  }, [profileId]);

  useEffect(() => {
    if (!api.favoritesList) {
      setLoading(false);
      return;
    }
    const token = req.begin();
    setLoading(true);
    setError(null);
    api
      .favoritesList({ offset, limit: LIST_LIMIT })
      .then((res) => {
        if (!req.isCurrent(token)) return;
        setPage(res);
        setLoading(false);
      })
      .catch((err) => {
        if (!req.isCurrent(token)) return;
        setError(extractAppError(err, 'Unable to load My List.').message);
        setLoading(false);
      });
  }, [api, profileId, listVersion, offset, req]);

  const total = page?.total ?? 0;
  const totalPages = Math.max(1, Math.ceil(total / LIST_LIMIT));
  const currentPage = Math.floor(offset / LIST_LIMIT) + 1;

  return (
    <section className="section-pad list-page">
      <div className="page-heading">
        <div>
          <span className="eyebrow">THE GOOD ONES, SAVED FOR LATER</span>
          <h1>
            My List<span className="page-count">{total}</span>
          </h1>
          <p>A personal collection saved for this profile.</p>
        </div>
        <Heart size={25} strokeWidth={1} />
      </div>

      {error && <ErrorBanner message={error} />}

      {loading && !page ? (
        <div className="empty-state">
          <p role="status">Loading My List…</p>
        </div>
      ) : page && page.items.length > 0 ? (
        <>
          <div className={`poster-grid ${dense ? 'dense' : ''}`}>
            {page.items.map((item) => (
              <TitleCard
                key={mediaKey(item.ref)}
                item={item}
                saved={savedKeys.has(mediaKey(item.ref))}
                onSelect={onSelect}
                onToggleSave={onToggleSave}
              />
            ))}
          </div>
          {totalPages > 1 && (
            <div className="pager-bar" aria-label="My List pagination">
              <button
                type="button"
                className="button secondary compact"
                disabled={offset === 0}
                onClick={() => setOffset(Math.max(0, offset - LIST_LIMIT))}
              >
                <ChevronLeft size={14} /> Previous
              </button>
              <span className="subtle">
                Page {currentPage} of {totalPages}
              </span>
              <button
                type="button"
                className="button secondary compact"
                disabled={offset + LIST_LIMIT >= total}
                onClick={() => setOffset(offset + LIST_LIMIT)}
              >
                Next <ChevronRight size={14} />
              </button>
            </div>
          )}
        </>
      ) : (
        <EmptyState
          title="Your next great watch starts here."
          description="Save a movie or series and make this space your own."
          action="Explore movies"
          onAction={onExploreMovies}
        />
      )}
    </section>
  );
}
