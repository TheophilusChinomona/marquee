import { useState } from 'react';
import { ArrowRight, ChevronLeft, ChevronRight } from 'lucide-react';
import type { MediaItem } from '../api/types';
import TitleCard, { mediaKey } from './TitleCard';

interface RailProps {
  title: string;
  eyebrow?: string;
  items: MediaItem[];
  mode?: 'poster' | 'continue';
  savedKeys: Set<string>;
  onSelect: (item: MediaItem) => void;
  onToggleSave: (item: MediaItem) => void;
  onViewAll?: () => void;
}

export default function Rail({
  title,
  eyebrow,
  items,
  mode = 'poster',
  savedKeys,
  onSelect,
  onToggleSave,
  onViewAll,
}: RailProps) {
  const pageSize = mode === 'continue' ? 3 : 7;
  const [page, setPage] = useState(0);
  const maxPage = Math.max(0, Math.ceil(items.length / pageSize) - 1);
  const clampedPage = Math.min(page, maxPage);
  const visible = items.slice(clampedPage * pageSize, (clampedPage + 1) * pageSize);

  return (
    <section className={`rail section-pad ${mode === 'continue' ? 'continue-section' : ''}`}>
      <div className="section-heading">
        <div>
          {eyebrow && <span className="eyebrow muted">{eyebrow}</span>}
          <h2>
            {title}
            <span className="tiny-count">{items.length}</span>
          </h2>
        </div>
        <div className="rail-header-actions">
          {maxPage > 0 && (
            <>
              <button
                type="button"
                className="icon-button"
                aria-label={`Previous ${title}`}
                disabled={clampedPage === 0}
                onClick={() => setPage((p) => Math.max(0, p - 1))}
              >
                <ChevronLeft size={16} />
              </button>
              <button
                type="button"
                className="icon-button"
                aria-label={`Next ${title}`}
                disabled={clampedPage >= maxPage}
                onClick={() => setPage((p) => Math.min(maxPage, p + 1))}
              >
                <ChevronRight size={16} />
              </button>
            </>
          )}
          {onViewAll && (
            <button type="button" className="text-button" onClick={onViewAll}>
              View all <ArrowRight size={14} />
            </button>
          )}
        </div>
      </div>
      <div className={`rail-items ${mode === 'continue' ? 'landscape-grid' : ''}`}>
        {visible.map((item) => (
          <TitleCard
            key={mediaKey(item.ref)}
            item={item}
            mode={mode}
            saved={savedKeys.has(mediaKey(item.ref))}
            onSelect={onSelect}
            onToggleSave={onToggleSave}
          />
        ))}
      </div>
    </section>
  );
}
