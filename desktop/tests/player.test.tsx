import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, expect, test, vi } from 'vitest';
import type { DesktopApi, PlayerSnapshot } from '../src/api/types';
import Player from '../src/features/Player';

function makeSnapshot(overrides: Partial<PlayerSnapshot> = {}): PlayerSnapshot {
  return {
    sessionId: 'sess-1',
    media: {
      ref: { kind: 'series', id: 's1' },
      name: 'Horizon',
      categoryId: '1',
    },
    episodeId: 'ep-101',
    episodeTitle: 'S1 E1 · Pilot',
    status: 'playing',
    positionMs: 120_000,
    durationMs: 3_000_000,
    seekable: true,
    volume: 75,
    muted: false,
    tracks: [
      { id: 1, kind: 'audio', title: 'English 5.1', lang: 'eng', selected: true },
      { id: 2, kind: 'audio', title: 'French Stereo', lang: 'fra', selected: false },
      { id: 1, kind: 'sub', title: 'English CC', lang: 'eng', selected: false },
    ],
    selectedAudioId: 1,
    selectedSubtitleId: null,
    queue: [
      { episodeId: 'ep-101', season: 1, number: 1, title: 'Pilot' },
      { episodeId: 'ep-102', season: 1, number: 2, title: 'Signal' },
    ],
    error: null,
    ...overrides,
  };
}

afterEach(() => {
  cleanup();
});

test('player controls dispatch typed commands for pause, seek, tracks, and episode IDs', async () => {
  const initial = makeSnapshot();
  const playerControl = vi.fn().mockResolvedValue(initial);
  const api: DesktopApi = {
    bootstrap: vi.fn(),
    playerControl,
  };
  const onClose = vi.fn();

  render(<Player api={api} initialSnapshot={initial} onClose={onClose} onRetry={vi.fn()} />);

  fireEvent.click(screen.getByRole('button', { name: 'Pause' }));
  expect(playerControl).toHaveBeenCalledWith({ action: 'pause' });

  fireEvent.click(screen.getByRole('button', { name: 'Skip forward 10 seconds' }));
  expect(playerControl).toHaveBeenCalledWith({ action: 'seekRelativeMs', value: 10_000 });

  // Switch episode by provider episodeId
  fireEvent.click(screen.getByRole('button', { name: /Episodes/i }));
  fireEvent.click(screen.getByRole('button', { name: /Signal/i }));
  expect(playerControl).toHaveBeenCalledWith({ action: 'selectEpisode', value: 'ep-102' });

  // Switch audio & subtitle track
  fireEvent.click(screen.getByRole('button', { name: /Audio & subtitles/i }));
  fireEvent.click(screen.getByRole('button', { name: 'French Stereo' }));
  expect(playerControl).toHaveBeenCalledWith({ action: 'audioTrack', value: 2 });
  fireEvent.click(screen.getByRole('button', { name: 'English CC' }));
  expect(playerControl).toHaveBeenCalledWith({ action: 'subtitleTrack', value: 1 });

  // Back to browse stops and closes
  fireEvent.click(screen.getByRole('button', { name: /Back to browse/i }));
  await waitFor(() => expect(playerControl).toHaveBeenCalledWith({ action: 'stop' }));
  expect(onClose).toHaveBeenCalledTimes(1);
});

test('live streams disable seek controls and player ignores obsolete session events', async () => {
  const liveSnap = makeSnapshot({
    sessionId: 'live-2',
    media: { ref: { kind: 'live', id: 'ch1' }, name: 'World News', categoryId: '10' },
    episodeId: undefined,
    episodeTitle: undefined,
    seekable: false,
    durationMs: undefined,
    queue: [],
  });

  let emitState: ((ev: { sessionId: string; snapshot: PlayerSnapshot }) => void) | null = null;
  const api: DesktopApi = {
    bootstrap: vi.fn(),
    playerControl: vi.fn().mockResolvedValue(liveSnap),
    onPlayerState: vi.fn().mockImplementation(async (handler) => {
      emitState = handler;
      return () => {};
    }),
  };

  render(<Player api={api} initialSnapshot={liveSnap} onClose={vi.fn()} onRetry={vi.fn()} />);
  const seek = screen.getByLabelText('Playback position') as HTMLInputElement;
  expect(seek.disabled).toBe(true);
  expect((screen.getByRole('button', { name: 'Skip forward 10 seconds' }) as HTMLButtonElement).disabled).toBe(true);

  // Obsolete session event must be ignored
  await waitFor(() => expect(emitState).not.toBeNull());
  emitState!({
    sessionId: 'old-session',
    snapshot: makeSnapshot({
      sessionId: 'old-session',
      media: { ref: { kind: 'movie', id: 'old' }, name: 'Wrong Old Title', categoryId: '1' },
    }),
  });
  expect(screen.getByText('World News')).toBeTruthy();
  expect(screen.queryByText('Wrong Old Title')).toBeNull();
});
