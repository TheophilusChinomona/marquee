import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, expect, test } from 'vitest';
import { NativeApp } from '../src/App';
import type { Bootstrap, DesktopApi, MediaItem } from '../src/api/types';
import Search from '../src/features/Search';

const boot: Bootstrap = {
  connected: false, profiles: [{ id: 'p', name: 'Theo', avatar: 'red' }], activeProfileId: 'p',
  credentialStatus: 'none', cacheStatus: { hasCatalogue: false, stale: true, refreshing: false, movieCount: 0, seriesCount: 0, liveCount: 0 },
  settings: { autoplayNext: true, subtitlesDefault: false, preferredAudio: 'English', compactLibrary: false },
};
Element.prototype.scrollTo = () => {};
afterEach(cleanup);

test('a late response for an old online query cannot replace the current results', async () => {
  const pending = new Map<string, (items: MediaItem[]) => void>();
  const api: DesktopApi = { bootstrap: async () => boot, onlineSearch: ({ query }) => new Promise((resolve) => { pending.set(query, resolve); }) };
  render(<Search api={api} onlineEnabled localEnabled={false} dense={false} savedKeys={new Set()} onSelect={() => {}} onPlayLive={() => {}} onToggleSave={() => {}} />);
  const input = screen.getByRole('textbox', { name: 'Search your library' });
  fireEvent.change(input, { target: { value: 'older' } });
  await waitFor(() => expect(pending.has('older')).toBe(true));
  fireEvent.change(input, { target: { value: 'current' } });
  await waitFor(() => expect(pending.has('current')).toBe(true));
  pending.get('current')!([{ ref: { kind: 'movie', id: 'online:new' }, name: 'Current result', categoryId: 'online' }]);
  expect((await screen.findAllByText('Current result')).length).toBeGreaterThan(0);
  pending.get('older')!([{ ref: { kind: 'movie', id: 'online:old' }, name: 'Stale result', categoryId: 'online' }]);
  await waitFor(() => expect(screen.queryByText('Stale result')).toBeNull());
  expect(screen.getAllByText('Current result').length).toBeGreaterThan(0);
});

test('online-only connection opens search-led home without requesting an IPTV sync', async () => {
  const api: DesktopApi = {
    bootstrap: async () => ({ ...boot, online: { connected: true, baseUrl: 'https://resolver.test/', credentialStatus: 'session', developmentImport: false } }),
    homeSections: async () => ({ hero: [], sections: [], cacheStatus: boot.cacheStatus }),
  };
  render(<NativeApp api={api} />);
  expect(await screen.findByText('Find something to watch online')).toBeTruthy();
  expect(screen.queryByText('Synchronize your library')).toBeNull();
  fireEvent.click(screen.getByRole('button', { name: 'Search online library' }));
  expect(await screen.findByRole('textbox', { name: 'Search your library' })).toBeTruthy();
});

test('reconnecting an online-only library opens the online form', async () => {
  const api: DesktopApi = {
    bootstrap: async () => ({ ...boot, online: { connected: false, baseUrl: 'https://resolver.test/', credentialStatus: 'none', developmentImport: false } }),
    homeSections: async () => ({ hero: [], sections: [], cacheStatus: boot.cacheStatus }),
  };
  render(<NativeApp api={api} />);
  fireEvent.click(await screen.findByRole('button', { name: 'Connect online library' }));
  expect(await screen.findByLabelText('Resolver URL')).toBeTruthy();
  expect(screen.queryByLabelText('Provider URL')).toBeNull();
});

test('setup offers online credentials and reaches the library without IPTV', async () => {
  const api: DesktopApi = {
    bootstrap: async () => boot,
    onlineConnect: async () => ({ ...boot, online: { connected: true, baseUrl: 'https://resolver.test/', credentialStatus: 'session', developmentImport: false } }),
    homeSections: async () => ({ hero: [], sections: [], cacheStatus: boot.cacheStatus }),
  };
  render(<NativeApp api={api} />);
  fireEvent.click(await screen.findByRole('button', { name: 'Online Library' }));
  fireEvent.change(screen.getByLabelText('Resolver URL'), { target: { value: 'https://resolver.test' } });
  fireEvent.change(screen.getByLabelText('Client ID'), { target: { value: 'id' } });
  const secret = screen.getByLabelText('Client secret') as HTMLInputElement;
  fireEvent.change(secret, { target: { value: 'private-secret' } });
  fireEvent.click(screen.getByRole('button', { name: 'Connect online library' }));
  expect(secret.value).toBe('');
  expect(await screen.findByText('Find something to watch online')).toBeTruthy();
});
