import { useCallback, useEffect, useState } from 'react';
import { getCurrentWindow } from '@tauri-apps/api/window';
import {
  ArrowLeft,
  Check,
  ChevronLeft,
  ChevronRight,
  ListVideo,
  Maximize,
  Pause,
  Play,
  RefreshCw,
  SkipBack,
  SkipForward,
  Subtitles,
  Volume2,
  VolumeX,
  X,
} from 'lucide-react';
import {
  extractAppError,
  type DesktopApi,
  type PlayerControlAction,
  type PlayerSnapshot,
} from '../api/types';

function formatClock(ms: number): string {
  const totalSecs = Math.max(0, Math.floor(ms / 1000));
  const hours = Math.floor(totalSecs / 3600);
  const mins = Math.floor((totalSecs % 3600) / 60);
  const secs = totalSecs % 60;
  if (hours > 0) {
    return `${hours}:${String(mins).padStart(2, '0')}:${String(secs).padStart(2, '0')}`;
  }
  return `${mins}:${String(secs).padStart(2, '0')}`;
}

interface PlayerProps {
  api: DesktopApi;
  initialSnapshot: PlayerSnapshot;
  onClose: () => void;
  onRetry: () => void;
}

export default function Player({ api, initialSnapshot, onClose, onRetry }: PlayerProps) {
  const [snapshot, setSnapshot] = useState<PlayerSnapshot>(initialSnapshot);
  const [panel, setPanel] = useState<'tracks' | 'episodes' | null>(null);
  const [controlError, setControlError] = useState<string | null>(null);

  useEffect(() => {
    setSnapshot(initialSnapshot);
    setControlError(null);
  }, [initialSnapshot.sessionId, initialSnapshot]);

  // Listen to native player-state events, ignoring obsolete session IDs.
  useEffect(() => {
    if (!api.onPlayerState) return;
    let disposed = false;
    let unlistenFn: (() => void) | null = null;
    api
      .onPlayerState((ev) => {
        if (disposed) return;
        if (ev.sessionId && snapshot.sessionId && ev.sessionId !== snapshot.sessionId) {
          return;
        }
        setSnapshot(ev.snapshot);
      })
      .then((unlisten) => {
        if (disposed) unlisten();
        else unlistenFn = unlisten;
      })
      .catch(() => {});
    return () => {
      disposed = true;
      unlistenFn?.();
    };
  }, [api, snapshot.sessionId]);

  const sendControl = useCallback(
    async (action: PlayerControlAction) => {
      if (!api.playerControl) return;
      setControlError(null);
      try {
        const next = await api.playerControl(action);
        setSnapshot(next);
      } catch (err) {
        setControlError(extractAppError(err, 'The player could not perform that action.').message);
      }
    },
    [api],
  );

  // Hide the native child video surface while a React overlay drawer is open so it is never occluded.
  const togglePanel = useCallback(
    (next: 'tracks' | 'episodes' | null) => {
      setPanel(next);
      if (api.playerControl) {
        void api.playerControl({ action: 'overlayVisible', value: next !== null }).catch(() => {});
      }
    },
    [api],
  );

  const toggleFullscreen = useCallback(async () => {
    try {
      const win = getCurrentWindow();
      const full = await win.isFullscreen();
      await win.setFullscreen(!full);
    } catch {
      // Non-native test environment
    }
  }, []);

  const handleClose = useCallback(async () => {
    try {
      const win = getCurrentWindow();
      if (await win.isFullscreen()) {
        await win.setFullscreen(false);
      }
    } catch {
      // Ignore in test environment
    }
    await sendControl({ action: 'stop' });
    onClose();
  }, [onClose, sendControl]);

  const isPaused = snapshot.status === 'paused' || snapshot.status === 'ended';
  const isLive = snapshot.media?.ref.kind === 'live';
  const canSeek = snapshot.seekable && !isLive && Boolean(snapshot.durationMs && snapshot.durationMs > 0);
  const durationMs = snapshot.durationMs ?? 0;
  const positionMs = Math.min(snapshot.positionMs, durationMs || snapshot.positionMs);
  const progressPct = durationMs > 0 ? Math.min(100, (positionMs / durationMs) * 100) : 0;

  const currentEpIndex = snapshot.episodeId
    ? snapshot.queue.findIndex((q) => q.episodeId === snapshot.episodeId)
    : -1;
  const hasPrevEpisode = currentEpIndex > 0;
  const hasNextEpisode = currentEpIndex >= 0 && currentEpIndex < snapshot.queue.length - 1;

  const audioTracks = snapshot.tracks.filter((t) => t.kind === 'audio');
  const subtitleTracks = snapshot.tracks.filter((t) => t.kind === 'sub');

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      const tag = (e.target as HTMLElement | null)?.tagName;
      if (tag === 'INPUT' || tag === 'SELECT' || tag === 'TEXTAREA') return;
      if (e.code === 'Space') {
        e.preventDefault();
        void sendControl({ action: isPaused ? 'resume' : 'pause' });
      } else if (e.key === 'ArrowRight' && canSeek) {
        e.preventDefault();
        void sendControl({ action: 'seekRelativeMs', value: 10_000 });
      } else if (e.key === 'ArrowLeft' && canSeek) {
        e.preventDefault();
        void sendControl({ action: 'seekRelativeMs', value: -10_000 });
      } else if (e.key.toLowerCase() === 'm') {
        e.preventDefault();
        void sendControl({ action: 'mute', value: !snapshot.muted });
      } else if (e.key.toLowerCase() === 'f') {
        e.preventDefault();
        void toggleFullscreen();
      } else if (e.key === 'Escape') {
        e.preventDefault();
        if (panel) {
          togglePanel(null);
        } else {
          void handleClose();
        }
      }
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [canSeek, handleClose, isPaused, panel, sendControl, snapshot.muted, toggleFullscreen, togglePanel]);

  return (
    <div className="native-player-shell" role="dialog" aria-label="Video player">
      <header className="native-player-top">
        <button type="button" className="player-back" onClick={() => void handleClose()}>
          <ArrowLeft size={21} />
          <span>Back to browse</span>
        </button>
        <div className="player-title">
          <h1>{snapshot.media?.name ?? 'Now Playing'}</h1>
          {snapshot.episodeTitle && <p>{snapshot.episodeTitle}</p>}
        </div>
        <span className="player-preview-label">
          {isLive ? 'LIVE' : snapshot.status.toUpperCase()}
        </span>
      </header>

      <div className="native-player-stage" aria-label="Embedded native video">
        {(snapshot.status === 'loading' || snapshot.status === 'buffering') && (
          <div className="player-status-overlay" role="status">
            <p>{snapshot.status === 'loading' ? 'Starting stream…' : 'Buffering…'}</p>
          </div>
        )}
        {snapshot.status === 'error' && (
          <div className="player-status-overlay" role="alert">
            <p>{snapshot.error?.message ?? 'Playback failed. Please try again.'}</p>
            <button
              type="button"
              className="button primary compact"
              style={{ marginTop: 12 }}
              onClick={onRetry}
            >
              <RefreshCw size={14} /> Retry playback
            </button>
          </div>
        )}
        {snapshot.status === 'ended' && (
          <div className="player-status-overlay" role="status">
            <p>Playback finished.</p>
            <button
              type="button"
              className="button secondary compact"
              style={{ marginTop: 12 }}
              onClick={() => void handleClose()}
            >
              Return to library
            </button>
          </div>
        )}
      </div>

      <footer className="native-player-bottom">
        {controlError && (
          <p role="alert" style={{ color: '#e8858d', fontSize: 11, margin: 0 }}>
            {controlError}
          </p>
        )}

        <div className="player-timeline">
          <input
            type="range"
            min="0"
            max={durationMs > 0 ? durationMs : 1}
            value={canSeek ? positionMs : 0}
            disabled={!canSeek}
            aria-label="Playback position"
            style={{ '--progress': `${canSeek ? progressPct : 0}%` } as React.CSSProperties}
            onChange={(e) =>
              void sendControl({ action: 'seekAbsoluteMs', value: Number(e.target.value) })
            }
          />
          <div>
            <span>{isLive ? 'Live stream' : formatClock(positionMs)}</span>
            <span>
              {isLive
                ? 'Live'
                : durationMs > 0
                  ? `−${formatClock(Math.max(0, durationMs - positionMs))}`
                  : '--:--'}
            </span>
          </div>
        </div>

        <div className="player-toolbar">
          <div>
            <button
              type="button"
              className="icon-button"
              aria-label={isPaused ? 'Play' : 'Pause'}
              onClick={() => void sendControl({ action: isPaused ? 'resume' : 'pause' })}
            >
              {isPaused ? <Play size={22} fill="currentColor" /> : <Pause size={22} fill="currentColor" />}
            </button>

            <button
              type="button"
              className="icon-button"
              aria-label="Skip back 10 seconds"
              disabled={!canSeek}
              onClick={() => void sendControl({ action: 'seekRelativeMs', value: -10_000 })}
            >
              <SkipBack size={20} />
            </button>

            <button
              type="button"
              className="icon-button"
              aria-label="Skip forward 10 seconds"
              disabled={!canSeek}
              onClick={() => void sendControl({ action: 'seekRelativeMs', value: 10_000 })}
            >
              <SkipForward size={20} />
            </button>

            {snapshot.queue.length > 1 && (
              <>
                <span className="toolbar-divider" />
                <button
                  type="button"
                  className="icon-button"
                  aria-label="Previous episode"
                  disabled={!hasPrevEpisode}
                  onClick={() => void sendControl({ action: 'previousEpisode' })}
                >
                  <ChevronLeft size={20} />
                </button>
                <button
                  type="button"
                  className="icon-button"
                  aria-label="Next episode"
                  disabled={!hasNextEpisode}
                  onClick={() => void sendControl({ action: 'nextEpisode' })}
                >
                  <ChevronRight size={20} />
                </button>
              </>
            )}

            <span className="toolbar-divider" />
            <button
              type="button"
              className="icon-button"
              aria-label={snapshot.muted || snapshot.volume === 0 ? 'Unmute' : 'Mute'}
              onClick={() => void sendControl({ action: 'mute', value: !snapshot.muted })}
            >
              {snapshot.muted || snapshot.volume === 0 ? (
                <VolumeX size={20} />
              ) : (
                <Volume2 size={20} />
              )}
            </button>
            <input
              className="volume-slider"
              aria-label="Volume"
              type="range"
              min="0"
              max="100"
              value={snapshot.muted ? 0 : Math.round(snapshot.volume)}
              onChange={(e) =>
                void sendControl({ action: 'volume', value: Number(e.target.value) })
              }
            />
          </div>

          <div>
            {snapshot.queue.length > 0 && (
              <button
                type="button"
                className="toolbar-text"
                onClick={() => togglePanel(panel === 'episodes' ? null : 'episodes')}
              >
                <ListVideo size={19} />
                Episodes
              </button>
            )}
            <button
              type="button"
              className="toolbar-text"
              onClick={() => togglePanel(panel === 'tracks' ? null : 'tracks')}
            >
              <Subtitles size={20} />
              Audio & subtitles
            </button>
            <button
              type="button"
              className="icon-button"
              aria-label="Fullscreen"
              onClick={() => void toggleFullscreen()}
            >
              <Maximize size={20} />
            </button>
          </div>
        </div>
      </footer>

      {panel && (
        <div className="player-panel">
          <div className="section-heading">
            <h2>{panel === 'tracks' ? 'Audio & subtitles' : 'Episodes'}</h2>
            <button
              type="button"
              className="icon-button"
              aria-label="Close panel"
              onClick={() => togglePanel(null)}
            >
              <X size={18} />
            </button>
          </div>

          {panel === 'tracks' ? (
            <div className="track-columns">
              <div>
                <span className="eyebrow">AUDIO</span>
                {audioTracks.length > 0 ? (
                  audioTracks.map((t) => (
                    <button
                      type="button"
                      key={t.id}
                      onClick={() => void sendControl({ action: 'audioTrack', value: t.id })}
                    >
                      {t.title}
                      {snapshot.selectedAudioId === t.id && <Check size={16} />}
                    </button>
                  ))
                ) : (
                  <p className="subtle">Default audio</p>
                )}
              </div>
              <div>
                <span className="eyebrow">SUBTITLES</span>
                <button
                  type="button"
                  onClick={() => void sendControl({ action: 'subtitleTrack', value: null })}
                >
                  Off
                  {snapshot.selectedSubtitleId === null && <Check size={16} />}
                </button>
                {subtitleTracks.map((t) => (
                  <button
                    type="button"
                    key={t.id}
                    onClick={() => void sendControl({ action: 'subtitleTrack', value: t.id })}
                  >
                    {t.title}
                    {snapshot.selectedSubtitleId === t.id && <Check size={16} />}
                  </button>
                ))}
              </div>
            </div>
          ) : (
            snapshot.queue.map((ep) => (
              <button
                type="button"
                className="player-episode"
                key={ep.episodeId}
                onClick={() => {
                  togglePanel(null);
                  void sendControl({ action: 'selectEpisode', value: ep.episodeId });
                }}
              >
                <span>
                  S{ep.season} E{ep.number}
                </span>
                {ep.title}
                {snapshot.episodeId === ep.episodeId ? <Check size={15} /> : <Play size={14} />}
              </button>
            ))
          )}
        </div>
      )}
    </div>
  );
}
