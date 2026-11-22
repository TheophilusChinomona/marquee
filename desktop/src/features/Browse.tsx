import { useEffect, useState } from 'react';
import { ChevronLeft, ChevronRight, SlidersHorizontal } from 'lucide-react';
import {
  extractAppError,
  type CatalogSort,
  type Category,
  type DesktopApi,
  type MediaItem,
  type MediaKind,
  type Page,
} from '../api/types';
import { EmptyState, ErrorBanner } from '../components/StatusView';
import TitleCard, { mediaKey } from '../components/TitleCard';
import { useLatestRequest } from '../hooks/useLatestRequest';

const PAGE_LIMIT = 60;

interface BrowseProps {
  api: DesktopApi;
  kind: Extract<MediaKind, 'movie' | 'series'>;
  dense: boolean;
  refreshStamp: number;
  savedKeys: Set<string>;
  onSelect: (item: MediaItem) => void;
  onToggleSave: (item: MediaItem) => void;
}

export default function Browse({
  api,
  kind,
  dense,
  refreshStamp,
  savedKeys,
  onSelect,
  onToggleSave,
}: BrowseProps) {
  const [categories, setCategories] = useState<Category[]>([]);
  const [selectedCategory, setSelectedCategory] = useState<string>('');
  const [sort, setSort] = useState<CatalogSort>('recommended');
  const [offset, setOffset] = useState(0);
  const [page, setPage] = useState<Page<MediaItem> | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [reloadCount, setReloadCount] = useState(0);

  const catReq = useLatestRequest();
  const listReq = useLatestRequest();

  useEffect(() => {
    setSelectedCategory('');
    setOffset(0);
  }, [kind]);

  useEffect(() => {
    if (!api.catalogCategories) return;
    const token = catReq.begin();
    api
      .catalogCategories({ kind })
      .then((cats) => {
        if (catReq.isCurrent(token)) setCategories(cats);
      })
      .catch(() => {
        if (catReq.isCurrent(token)) setCategories([]);
      });
  }, [api, kind, refreshStamp, catReq]);

  useEffect(() => {
    if (!api.catalogQuery) {
      setLoading(false);
      return;
    }
    const token = listReq.begin();
    setLoading(true);
    setError(null);
    api
      .catalogQuery({
        kind,
        categoryId: selectedCategory || undefined,
        offset,
        limit: PAGE_LIMIT,
        sort,
      })
      .then((res) => {
        if (!listReq.isCurrent(token)) return;
        setPage(res);
        setLoading(false);
      })
      .catch((err) => {
        if (!listReq.isCurrent(token)) return;
        setError(extractAppError(err, 'Unable to load titles.').message);
        setLoading(false);
      });
  }, [api, kind, selectedCategory, offset, sort, refreshStamp, reloadCount, listReq]);

  const heading = kind === 'series' ? 'Series' : 'Movies';
  const activeCatName =
    categories.find((c) => c.id === selectedCategory)?.name ?? `All ${heading.toLowerCase()}`;
  const total = page?.total ?? 0;
  const totalPages = Math.max(1, Math.ceil(total / PAGE_LIMIT));
  const currentPage = Math.floor(offset / PAGE_LIMIT) + 1;

  return (
    <div className="browse-page">
      <div className="page-heading section-pad">
        <div>
          <span className="eyebrow">YOUR PERSONAL CINEMA</span>
          <h1>{heading}</h1>
          <p>
            {kind === 'series'
              ? 'Browse seasons and episodes from your provider.'
              : 'Browse films from your synchronized library.'}
          </p>
        </div>
        <label className="sort-control">
          <SlidersHorizontal size={15} />
          <select
            aria-label="Sort titles"
            value={sort}
            onChange={(e) => {
              setSort(e.target.value as CatalogSort);
              setOffset(0);
            }}
          >
            <option value="recommended">Recommended</option>
            <option value="added">Recently added</option>
            <option value="year">Release year</option>
            <option value="title">Title A–Z</option>
          </select>
        </label>
      </div>

      {categories.length > 0 && (
        <div className="chips section-pad" role="group" aria-label="Categories">
          <button
            type="button"
            className={selectedCategory === '' ? 'chip selected' : 'chip'}
            onClick={() => {
              setSelectedCategory('');
              setOffset(0);
            }}
          >
            All categories
          </button>
          {categories.slice(0, 40).map((cat) => (
            <button
              type="button"
              key={cat.id}
              className={selectedCategory === cat.id ? 'chip selected' : 'chip'}
              onClick={() => {
                setSelectedCategory(cat.id);
                setOffset(0);
              }}
            >
              {cat.name}
            </button>
          ))}
        </div>
      )}

      <section className="section-pad">
        {error && (
          <ErrorBanner message={error} retryable onRetry={() => setReloadCount((c) => c + 1)} />
        )}

        <div className="section-heading">
          <h2>
            {activeCatName}
            <span className="tiny-count">{total.toLocaleString()}</span>
          </h2>
          {total > PAGE_LIMIT && (
            <span className="subtle">
              Showing {offset + 1}–{Math.min(offset + PAGE_LIMIT, total)} of{' '}
              {total.toLocaleString()}
            </span>
          )}
        </div>

        {loading && !page ? (
          <div className="empty-state">
            <p role="status">Loading {heading.toLowerCase()}…</p>
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
              <div className="pager-bar" aria-label="Pagination">
                <button
                  type="button"
                  className="button secondary compact"
                  disabled={offset === 0}
                  onClick={() => setOffset(Math.max(0, offset - PAGE_LIMIT))}
                >
                  <ChevronLeft size={14} /> Previous
                </button>
                <span className="subtle">
                  Page {currentPage} of {totalPages}
                </span>
                <button
                  type="button"
                  className="button secondary compact"
                  disabled={offset + PAGE_LIMIT >= total}
                  onClick={() => setOffset(offset + PAGE_LIMIT)}
                >
                  Next <ChevronRight size={14} />
                </button>
              </div>
            )}
          </>
        ) : (
          <EmptyState
            title="A little quiet in this category"
            description={
              selectedCategory
                ? 'Try another category to find something to watch.'
                : 'No titles are available in your library yet.'
            }
            action={selectedCategory ? 'See all titles' : undefined}
            onAction={selectedCategory ? () => setSelectedCategory('') : undefined}
          />
        )}
      </section>
    </div>
  );
}
