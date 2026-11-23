import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, expect, test, vi } from 'vitest';
import type { DesktopApi, EpgProgram, MediaItem } from '../src/api/types';
import LiveGuide, { selectCurrentAndNextProgram } from '../src/features/LiveGuide';

afterEach(() => {
  cleanup();
});

test('selectCurrentAndNextProgram handles overlap, day-boundary crossing, and invalid timestamps', () => {
  // 23:45 UTC on 2026-11-22
  const nowMs = Date.UTC(2026, 10, 22, 23, 45, 0);
  const programs: EpgProgram[] = [
    // Invalid (end <= start) must be ignored
    { id: 'bad', channelId: 'c1', title: 'Corrupt', startMs: nowMs + 1000, endMs: nowMs },
    // Earlier overlapping programme
    {
      id: 'p1',
      channelId: 'c1',
      title: 'Late Movie',
      startMs: Date.UTC(2026, 10, 22, 22, 0, 0),
      endMs: Date.UTC(2026, 10, 23, 0, 0, 0),
    },
    // Later-started overlapping programme crossing midnight (23:30 -> 00:30)
    {
      id: 'p2',
      channelId: 'c1',
      title: 'Midnight Bulletin',
      startMs: Date.UTC(2026, 10, 22, 23, 30, 0),
      endMs: Date.UTC(2026, 10, 23, 0, 30, 0),
    },
    // Upcoming next day (00:30 -> 01:30)
    {
      id: 'p3',
      channelId: 'c1',
      title: 'Overnight Sports',
      startMs: Date.UTC(2026, 10, 23, 0, 30, 0),
      endMs: Date.UTC(2026, 10, 23, 1, 30, 0),
    },
  ];

  const { current, next, progressPct } = selectCurrentAndNextProgram(programs, nowMs);
  expect(current?.id).toBe('p2');
  expect(next?.id).toBe('p3');
  expect(Math.round(progressPct)).toBe(25);
});

test('LiveGuide loads bounded EPG, handles missing guide data, and starts playback on click', async () => {
  const fixedNow = Date.UTC(2026, 10, 22, 18, 15, 0);
  const channels: MediaItem[] = [
    { ref: { kind: 'live', id: 'ch-1' }, name: 'World News', categoryId: 'news' },
    { ref: { kind: 'live', id: 'ch-2' }, name: 'No Guide TV', categoryId: 'news' },
  ];

  const epgQuery = vi.fn().mockResolvedValue({
    channels: {
      'ch-1': [
        {
          id: 'epg-1',
          channelId: 'ch-1',
          title: 'Evening Report',
          description: 'Live world headlines.',
          startMs: Date.UTC(2026, 10, 22, 18, 0, 0),
          endMs: Date.UTC(2026, 10, 22, 19, 0, 0),
        },
        {
          id: 'epg-2',
          channelId: 'ch-1',
          title: 'Nightly Analysis',
          startMs: Date.UTC(2026, 10, 22, 19, 0, 0),
          endMs: Date.UTC(2026, 10, 22, 20, 0, 0),
        },
      ],
      'ch-2': [],
    },
  });

  const api: DesktopApi = {
    bootstrap: vi.fn(),
    catalogCategories: vi.fn().mockResolvedValue([{ id: 'news', name: 'News', kind: 'live', count: 2 }]),
    catalogQuery: vi.fn().mockResolvedValue({
      items: channels,
      total: 2,
      offset: 0,
      limit: 50,
    }),
    epgQuery,
  };

  const onPlayChannel = vi.fn();
  const onNotify = vi.fn();

  render(
    <LiveGuide
      api={api}
      refreshStamp={0}
      nowProvider={() => fixedNow}
      onPlayChannel={onPlayChannel}
      onNotify={onNotify}
    />,
  );

  expect(await screen.findByRole('heading', { name: 'Evening Report' })).toBeTruthy();
  expect(screen.getByText('No guide data available')).toBeTruthy();
  expect(epgQuery).toHaveBeenCalledTimes(1);
  expect(epgQuery.mock.calls[0][0].channelIds).toEqual(['ch-1', 'ch-2']);

  // Clicking upcoming programme informs user without implying recording
  fireEvent.click(screen.getByText('Nightly Analysis'));
  expect(onNotify).toHaveBeenCalledWith(expect.stringContaining('Nightly Analysis starts at'));

  // Clicking Watch channel starts live playback
  fireEvent.click(screen.getByRole('button', { name: 'Watch channel' }));
  await waitFor(() => expect(onPlayChannel).toHaveBeenCalledWith(channels[0]));
});
