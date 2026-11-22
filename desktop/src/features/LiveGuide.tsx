import { useEffect, useMemo, useState } from 'react';
import { ChevronLeft, ChevronRight, Globe, Play, Radio } from 'lucide-react';
import {
  extractAppError,
  type Category,
  type DesktopApi,
  type EpgProgram,
  type MediaItem,
  type Page,
} from '../api/types';
import { EmptyState, ErrorBanner } from '../components/StatusView';
import { isSafeImageUrl } from '../components/TitleCard';
import { useLatestRequest } from '../hooks/useLatestRequest';

const CHANNELS_PER_PAGE = 50;
const SLOT_MS = 30 * 60 * 1000;
const WINDOW_SLOTS = 4;

export function roundDownHalfHour(ms: number): number {
  return Math.floor(ms / SLOT_MS) * SLOT_MS;
}

export function formatLocalTime(ms: number): string {
  return new Date(ms).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
}

export function formatLocalHeaderClock(ms: number): string {
  const date = new Date(ms);
  const day = date.toLocaleDateString([], { weekday: 'long', day: 'numeric', month: 'long' });
  const time = formatLocalTime(ms);
  return `${day} · ${time}`;
}

export function selectCurrentAndNextProgram(
  programs: EpgProgram[],
  nowMs: number,
): { current: EpgProgram | null; next: EpgProgram | null; progressPct: number } {
  const valid = programs
    .filter((p) => Number.isFinite(p.startMs) && Number.isFinite(p.endMs) && p.endMs > p.startMs)
    .sort((a, b) => a.startMs - b.startMs || a.endMs - b.endMs);

  // If programmes overlap at nowMs, pick the latest-started active programme.
  let current: EpgProgram | null = null;
  for (const prog of valid) {
    if (prog.startMs <= nowMs && prog.endMs > nowMs) {
      current = prog;
    }
  }

  const next =
    valid.find((p) =>
      current ? p.startMs >= current.endMs || (p.startMs > nowMs && p.id !== current.id) : p.startMs > nowMs,
    ) ?? null;

  const progressPct = current
    ? Math.max(0, Math.min(100, ((nowMs - current.startMs) / (current.endMs - current.startMs)) * 100))
    : 0;

  return { current, next, progressPct };
}

interface LiveGuideProps {
  api: DesktopApi;
  refreshStamp: number;
  nowProvider?: () => number;
  onPlayChannel: (channel: MediaItem) => void;
  onNotify: (message: string) => void;
}

export default function LiveGuide({
  api,
  refreshStamp,
  nowProvider = () => Date.now(),
  onPlayChannel,
  onNotify,
}: LiveGuideProps) {
  const [nowMs, setNowMs] = useState<number>(() => nowProvider());
  const [categories, setCategories] = useState<Category[]>([]);
  const [selectedCategory, setSelectedCategory] = useState('');
  const [offset, setOffset] = useState(0);
  const [channelsPage, setChannelsPage] = useState<Page<MediaItem> | null>(null);
  const [selectedChannelId, setSelectedChannelId] = useState<string | null>(null);
  const [epgByChannel, setEpgByChannel] = useState<Record<string, EpgProgram[]>>({});
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const catReq = useLatestRequest();
  const listReq = useLatestRequest();
  const epgReq = useLatestRequest();

  // Keep the local clock & now marker live every 60s, cleanly unsubscribed on unmount.
  useEffect(() => {
    const id = setInterval(() => setNowMs(nowProvider()), 60_000);
    return () => clearInterval(id);
  }, [nowProvider]);

  useEffect(() => {
    if (!api.catalogCategories) return;
    const token = catReq.begin();
    api
      .catalogCategories({ kind: 'live' })
      .then((cats) => {
        if (catReq.isCurrent(token)) setCategories(cats);
      })
      .catch(() => {
        if (catReq.isCurrent(token)) setCategories([]);
      });
  }, [api, refreshStamp, catReq]);

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
        kind: 'live',
        categoryId: selectedCategory || undefined,
        offset,
        limit: CHANNELS_PER_PAGE,
        sort: 'title',
      })
      .then((res) => {
        if (!listReq.isCurrent(token)) return;
        setChannelsPage(res);
        setSelectedChannelId((prev) =>
          prev && res.items.some((ch) => ch.ref.id === prev)
            ? prev
            : (res.items[0]?.ref.id ?? null),
        );
        setLoading(false);
      })
      .catch((err) => {
        if (!listReq.isCurrent(token)) return;
        setError(extractAppError(err, 'Unable to load live channels.').message);
        setLoading(false);
      });
  }, [api, selectedCategory, offset, refreshStamp, listReq]);

  const windowStartMs = useMemo(() => roundDownHalfHour(nowMs), [nowMs]);
  const windowEndMs = windowStartMs + WINDOW_SLOTS * SLOT_MS;

  const visibleChannelIds = useMemo(
    () => (channelsPage?.items ?? []).slice(0, CHANNELS_PER_PAGE).map((c) => c.ref.id),
    [channelsPage],
  );
  const channelIdKey = visibleChannelIds.join(',');

  // Lazily fetch EPG for the visible bounded channel page (<= 50 channels, 2h window).
  useEffect(() => {
    if (!api.epgQuery || visibleChannelIds.length === 0) {
      setEpgByChannel({});
      return;
    }
    const token = epgReq.begin();
    api
      .epgQuery({
        channelIds: visibleChannelIds,
        fromMs: windowStartMs,
        toMs: windowEndMs,
      })
      .then((res) => {
        if (!epgReq.isCurrent(token)) return;
        setEpgByChannel(res.channels);
      })
      .catch(() => {
        if (!epgReq.isCurrent(token)) return;
        setEpgByChannel({});
      });
  }, [api, channelIdKey, windowStartMs, windowEndMs, epgReq]);

  const channels = channelsPage?.items ?? [];
  const total = channelsPage?.total ?? 0;
  const totalPages = Math.max(1, Math.ceil(total / CHANNELS_PER_PAGE));
  const currentPage = Math.floor(offset / CHANNELS_PER_PAGE) + 1;

  const activeChannel =
    channels.find((c) => c.ref.id === selectedChannelId) ?? channels[0] ?? null;
  const activePrograms = activeChannel ? (epgByChannel[activeChannel.ref.id] ?? []) : [];
  const activeGuide = selectCurrentAndNextProgram(activePrograms, nowMs);
  const activeRemainingMin = activeGuide.current
    ? Math.max(1, Math.round((activeGuide.current.endMs - nowMs) / 60_000))
    : null;

  const slots = Array.from({ length: WINDOW_SLOTS }, (_, i) => windowStartMs + i * SLOT_MS);

  return (
    <div className="live-page">
      <div className="page-heading section-pad">
        <div>
          <span className="eyebrow">TUNE IN. SWITCH OFF.</span>
          <h1>
            Live TV{' '}
            <span className="live-pill">
              <span /> LIVE
            </span>
          </h1>
        </div>
        <span className="subtle">
          <Globe size={14} /> {formatLocalHeaderClock(nowMs)}
        </span>
      </div>

      {error && (
        <div className="section-pad">
          <ErrorBanner message={error} />
        </div>
      )}

      {activeChannel && (
        <section className="live-feature section-pad">
          <div className="live-picture">
            {isSafeImageUrl(activeChannel.backdropUrl || activeChannel.posterUrl) ? (
              <img
                src={activeChannel.backdropUrl || activeChannel.posterUrl}
                alt={activeChannel.name}
              />
            ) : (
              <div className="poster-fallback" aria-hidden="true">
                <Radio size={32} strokeWidth={1.2} />
                <span>{activeChannel.name}</span>
              </div>
            )}
            <span className="picture-label">
              <span className="live-pill">
                <span /> LIVE
              </span>{' '}
              {activeChannel.name}
            </span>
            <button
              type="button"
              className="large-play"
              aria-label={`Watch ${activeChannel.name}`}
              onClick={() => onPlayChannel(activeChannel)}
            >
              <Play size={28} fill="currentColor" />
            </button>
          </div>

          <div className="live-info">
            <div className="channel-brand">{activeChannel.name}</div>
            <span className="eyebrow">
              {activeGuide.current
                ? `ON NOW · ${formatLocalTime(activeGuide.current.startMs)} – ${formatLocalTime(activeGuide.current.endMs)}`
                : 'ON NOW'}
            </span>
            <h2>{activeGuide.current?.title ?? activeChannel.name}</h2>
            <p>
              {activeGuide.current?.description ||
                (activeGuide.current
                  ? 'Live broadcast from your provider.'
                  : 'No programme guide information is available for this channel.')}
            </p>
            {activeGuide.current && (
              <>
                <div className="programme-progress">
                  <span style={{ width: `${activeGuide.progressPct}%` }} />
                </div>
                {activeRemainingMin !== null && (
                  <span className="subtle">{activeRemainingMin} minutes remaining</span>
                )}
              </>
            )}
            <button
              type="button"
              className="button primary"
              onClick={() => onPlayChannel(activeChannel)}
            >
              <Play size={16} fill="currentColor" /> Watch channel
            </button>
          </div>
        </section>
      )}

      {categories.length > 0 && (
        <div className="chips section-pad" role="group" aria-label="Channel categories">
          <button
            type="button"
            onClick={() => {
              setSelectedCategory('');
              setOffset(0);
            }}
            className={`chip ${selectedCategory === '' ? 'selected' : ''}`}
          >
            All channels
          </button>
          {categories.slice(0, 40).map((cat) => (
            <button
              type="button"
              key={cat.id}
              onClick={() => {
                setSelectedCategory(cat.id);
                setOffset(0);
              }}
              className={`chip ${selectedCategory === cat.id ? 'selected' : ''}`}
            >
              {cat.name}
            </button>
          ))}
          <span className="guide-key">
            <span /> On now
          </span>
        </div>
      )}

      <section className="section-pad guide-wrap">
        {loading && !channelsPage ? (
          <div className="empty-state">
            <p role="status">Loading live channels…</p>
          </div>
        ) : channels.length === 0 ? (
          <EmptyState
            title="No live channels available"
            description={
              selectedCategory
                ? 'Try selecting another channel category.'
                : 'Refresh your library once connected to load live channels.'
            }
            action={selectedCategory ? 'Show all channels' : undefined}
            onAction={selectedCategory ? () => setSelectedCategory('') : undefined}
          />
        ) : (
          <>
            <div className="guide" role="region" aria-label="TV programme guide">
              <div className="guide-header">
                <div className="guide-channel-title">
                  CHANNEL <span>{total.toLocaleString()}</span>
                </div>
                {slots.map((slotMs, idx) => (
                  <div key={slotMs}>
                    {formatLocalTime(slotMs)}
                    {idx === 0 && <span className="now-label">NOW</span>}
                  </div>
                ))}
              </div>

              {channels.map((channel, index) => {
                const progs = epgByChannel[channel.ref.id] ?? [];
                const { current, next, progressPct } = selectCurrentAndNextProgram(progs, nowMs);
                const isSelected = activeChannel?.ref.id === channel.ref.id;
                return (
                  <div
                    className={`guide-row ${isSelected ? 'active' : ''}`}
                    key={channel.ref.id}
                  >
                    <button
                      type="button"
                      className="guide-channel"
                      onClick={() => setSelectedChannelId(channel.ref.id)}
                      onDoubleClick={() => onPlayChannel(channel)}
                    >
                      <span className="channel-number">
                        {String(offset + index + 1).padStart(2, '0')}
                      </span>
                      {isSafeImageUrl(channel.posterUrl) ? (
                        <img
                          className="channel-logo-img"
                          src={channel.posterUrl}
                          alt=""
                          loading="lazy"
                        />
                      ) : (
                        <Radio size={15} />
                      )}
                      <span>{channel.name}</span>
                    </button>

                    <button
                      type="button"
                      className="programme on-now"
                      onClick={() => {
                        setSelectedChannelId(channel.ref.id);
                        onPlayChannel(channel);
                      }}
                    >
                      <strong>{current?.title ?? 'No guide data available'}</strong>
                      <span>
                        {current
                          ? `${formatLocalTime(current.startMs)} – ${formatLocalTime(current.endMs)}`
                          : 'Click to watch channel'}
                      </span>
                      {current && <i style={{ width: `${progressPct}%` }} />}
                    </button>

                    <button
                      type="button"
                      className="programme upcoming"
                      onClick={() => {
                        setSelectedChannelId(channel.ref.id);
                        if (next) {
                          onNotify(`${next.title} starts at ${formatLocalTime(next.startMs)}`);
                        } else {
                          onNotify('No upcoming schedule available for this channel.');
                        }
                      }}
                    >
                      <strong>{next?.title ?? 'No upcoming listing'}</strong>
                      <span>
                        {next
                          ? `${formatLocalTime(next.startMs)} – ${formatLocalTime(next.endMs)}`
                          : 'Guide unavailable'}
                      </span>
                    </button>
                  </div>
                );
              })}
            </div>

            {totalPages > 1 && (
              <div className="pager-bar" aria-label="Channels pagination">
                <button
                  type="button"
                  className="button secondary compact"
                  disabled={offset === 0}
                  onClick={() => setOffset(Math.max(0, offset - CHANNELS_PER_PAGE))}
                >
                  <ChevronLeft size={14} /> Previous
                </button>
                <span className="subtle">
                  Page {currentPage} of {totalPages}
                </span>
                <button
                  type="button"
                  className="button secondary compact"
                  disabled={offset + CHANNELS_PER_PAGE >= total}
                  onClick={() => setOffset(offset + CHANNELS_PER_PAGE)}
                >
                  Next <ChevronRight size={14} />
                </button>
              </div>
            )}
          </>
        )}
      </section>
    </div>
  );
}
