import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, expect, test, vi } from 'vitest';
import type { Bootstrap, DesktopApi, MediaItem, Page } from '../src/api/types';
import { NativeApp } from '../src/App';

function connectedBoot(overrides: Partial<Bootstrap> = {}): Bootstrap {
  return {
    connected: true,
    activeProviderId: 'prov-1',
    providerUrl: 'http://provider.test/',
    credentialWarning: null,
    profiles: [
      { id: 'p1', name: 'Theo', avatar: 'red' },
      { id: 'p2', name: 'Alex', avatar: 'blue' },
    ],
    activeProfileId: 'p1',
    credentialStatus: 'remembered',
    cacheStatus: {
      hasCatalogue: true,
      stale: false,
      liveCount: 2,
      movieCount: 50000,
      seriesCount: 100,
      refreshing: false,
    },
    settings: {
      autoplayNext: true,
      subtitlesDefault: false,
      preferredAudio: 'English',
      compactLibrary: false,
    },
    ...overrides,
  };
}

function makeMovie(id: string, name: string, partial: Partial<MediaItem> = {}): MediaItem {
  return {
    ref: { kind: 'movie', id },
    name,
    categoryId: '1',
    ...partial,
  };
}

afterEach(() => {
  cleanup();
});

test('switching profiles updates Home and My List without leaking previous profile items', async () => {
  Element.prototype.scrollTo = vi.fn();
  let activeProfile = 'p1';
  const api: DesktopApi = {
    bootstrap: vi.fn().mockResolvedValue(connectedBoot()),
    profileActivate: vi.fn().mockImplementation(async ({ id }: { id: string }) => {
      activeProfile = id;
    }),
    homeSections: vi.fn().mockImplementation(async () => ({
      hero: [
        activeProfile === 'p1'
          ? makeMovie('m1', 'Theo Spotlight')
          : makeMovie('m2', 'Alex Spotlight'),
      ],
      sections: [
        {
          id: 'my-list',
          kind: 'myList',
          title: 'My List',
          items: [
            activeProfile === 'p1'
              ? makeMovie('m1', 'Theo Saved Film')
              : makeMovie('m2', 'Alex Saved Film'),
          ],
        },
      ],
      cacheStatus: connectedBoot().cacheStatus,
    })),
    favoritesList: vi.fn().mockImplementation(async () => ({
      items: [
        activeProfile === 'p1'
          ? makeMovie('m1', 'Theo Saved Film')
          : makeMovie('m2', 'Alex Saved Film'),
      ],
      total: 1,
      offset: 0,
      limit: 60,
    })),
  };

  render(<NativeApp api={api} />);
  expect(await screen.findByText('Theo Spotlight')).toBeTruthy();

  // Switch to Alex
  fireEvent.click(screen.getByRole('button', { name: 'Profile menu (Theo)' }));
  fireEvent.click(screen.getByRole('menuitem', { name: /Switch profile/i }));
  fireEvent.click(await screen.findByRole('button', { name: 'Alex' }));

  expect(await screen.findByRole('heading', { name: 'Alex Spotlight' })).toBeTruthy();
  expect(screen.queryByRole('heading', { name: 'Theo Saved Film' })).toBeNull();

  // Check My List
  fireEvent.click(screen.getByRole('button', { name: 'My List' }));
  expect(await screen.findByRole('heading', { name: 'Alex Saved Film' })).toBeTruthy();
  expect(screen.queryByRole('heading', { name: 'Theo Saved Film' })).toBeNull();
});

test('rapid search ignores stale earlier responses and handles special characters', async () => {
  Element.prototype.scrollTo = vi.fn();
  const resolvers: Record<string, (page: Page<MediaItem>) => void> = {};
  const catalogQuery = vi.fn().mockImplementation(
    ({ query }: { query?: string }) =>
      new Promise<Page<MediaItem>>((resolve) => {
        resolvers[query ?? ''] = resolve;
      }),
  );

  const api: DesktopApi = {
    bootstrap: vi.fn().mockResolvedValue(connectedBoot()),
    homeSections: vi.fn().mockResolvedValue({
      hero: [],
      sections: [],
      cacheStatus: connectedBoot().cacheStatus,
    }),
    favoritesList: vi.fn().mockResolvedValue({ items: [], total: 0, offset: 0, limit: 60 }),
    catalogQuery,
  };

  render(<NativeApp api={api} />);
  fireEvent.click(await screen.findByRole('button', { name: 'Search' }));
  const input = await screen.findByLabelText('Search your library');

  fireEvent.change(input, { target: { value: 'slow' } });
  fireEvent.change(input, { target: { value: '100% _fast' } });

  // Resolve newer query first, then resolve older stale query later
  resolvers['100% _fast']({
    items: [makeMovie('fast-1', 'Latest Exact Match')],
    total: 1,
    offset: 0,
    limit: 60,
  });
  expect(await screen.findByRole('heading', { name: 'Latest Exact Match' })).toBeTruthy();

  resolvers['slow']({
    items: [makeMovie('stale-1', 'Stale Earlier Result')],
    total: 1,
    offset: 0,
    limit: 60,
  });

  await waitFor(() => {
    expect(screen.getByRole('heading', { name: 'Latest Exact Match' })).toBeTruthy();
    expect(screen.queryByRole('heading', { name: 'Stale Earlier Result' })).toBeNull();
  });
});

test('50,000-item library renders only bounded cards and omits missing metadata safely', async () => {
  Element.prototype.scrollTo = vi.fn();
  const pageItems = Array.from({ length: 60 }, (_, idx) =>
    makeMovie(`m-${idx}`, `Catalog Film ${idx}`, {
      posterUrl: undefined,
      year: undefined,
      rating: undefined,
    }),
  );

  const api: DesktopApi = {
    bootstrap: vi.fn().mockResolvedValue(connectedBoot()),
    homeSections: vi.fn().mockResolvedValue({
      hero: [],
      sections: [],
      cacheStatus: connectedBoot().cacheStatus,
    }),
    favoritesList: vi.fn().mockResolvedValue({ items: [], total: 0, offset: 0, limit: 60 }),
    catalogCategories: vi.fn().mockResolvedValue([{ id: '1', name: 'All', kind: 'movie', count: 50000 }]),
    catalogQuery: vi.fn().mockResolvedValue({
      items: pageItems,
      total: 50000,
      offset: 0,
      limit: 60,
    }),
  };

  render(<NativeApp api={api} />);
  fireEvent.click(await screen.findByRole('button', { name: 'Movies' }));

  expect(await screen.findByRole('heading', { name: 'Catalog Film 0' })).toBeTruthy();
  const cards = document.querySelectorAll('.title-card');
  expect(cards.length).toBe(60);
  expect(screen.queryByText('4K')).toBeNull();
  expect(screen.queryByText('HDR')).toBeNull();
  expect(screen.queryByText('Dune: Part Two')).toBeNull();
});
