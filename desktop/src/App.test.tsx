import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, expect, test, vi } from 'vitest';
import type { Bootstrap, DesktopApi } from './api/types';
import App, { NativeApp } from './App';

function baseBootstrap(overrides: Partial<Bootstrap> = {}): Bootstrap {
  return {
    connected: false,
    activeProviderId: null,
    providerUrl: null,
    credentialWarning: null,
    profiles: [],
    activeProfileId: null,
    credentialStatus: 'none',
    cacheStatus: {
      hasCatalogue: false,
      stale: true,
      liveCount: 0,
      movieCount: 0,
      seriesCount: 0,
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

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

test('native_bootstrap_failure_shows_retry', async () => {
  vi.stubGlobal('isTauri', true);
  vi.stubGlobal('__TAURI_INTERNALS__', {
    invoke: vi.fn().mockRejectedValue(new Error('unavailable')),
  });
  Element.prototype.scrollTo = vi.fn();
  render(<App />);
  expect(await screen.findByRole('button', { name: 'Retry' })).toBeTruthy();
  expect(screen.queryByText('Dune: Part Two')).toBeNull();
  fireEvent.click(screen.getByRole('button', { name: 'Retry' }));
  expect(await screen.findByRole('button', { name: 'Retry' })).toBeTruthy();
});

test('native startup retries against backend and recovers to real setup', async () => {
  const bootstrap = vi
    .fn()
    .mockRejectedValueOnce(new Error('locked'))
    .mockResolvedValue(baseBootstrap());
  render(<NativeApp api={{ bootstrap }} />);
  fireEvent.click(await screen.findByRole('button', { name: 'Retry' }));
  expect(await screen.findByText('Let’s get you connected.')).toBeTruthy();
  expect(bootstrap).toHaveBeenCalledTimes(2);
  expect(screen.queryByText('Dune: Part Two')).toBeNull();
});

test('connect clears password immediately and surfaces session-only warning', async () => {
  const connectedBoot = baseBootstrap({
    connected: true,
    activeProviderId: 'prov-1',
    providerUrl: 'http://provider.test/',
    credentialStatus: 'session',
    credentialWarning:
      'Secure credential storage is unavailable. This login lasts until Marquee closes.',
    profiles: [{ id: 'p1', name: 'My profile', avatar: 'red' }],
    activeProfileId: 'p1',
  });
  const providerConnect = vi.fn().mockResolvedValue(connectedBoot);
  const api: DesktopApi = {
    bootstrap: vi.fn().mockResolvedValue(baseBootstrap()),
    providerConnect,
  };

  render(<NativeApp api={api} />);
  const urlInput = (await screen.findByPlaceholderText('https://your-provider.com')) as HTMLInputElement;
  const userInput = screen.getByPlaceholderText('Your provider username') as HTMLInputElement;
  const passInput = screen.getByPlaceholderText('••••••••••••') as HTMLInputElement;

  fireEvent.change(urlInput, { target: { value: 'http://provider.test' } });
  fireEvent.change(userInput, { target: { value: 'alice' } });
  fireEvent.change(passInput, { target: { value: 'super-secret' } });
  fireEvent.click(screen.getByRole('button', { name: /Connect provider/i }));

  expect(passInput.value).toBe('');
  await waitFor(() => expect(providerConnect).toHaveBeenCalledTimes(1));
  expect(providerConnect).toHaveBeenCalledWith({
    baseUrl: 'http://provider.test',
    username: 'alice',
    password: 'super-secret',
    remember: true,
  });
  expect(
    (await screen.findAllByText(/Secure credential storage is unavailable/i)).length,
  ).toBeGreaterThan(0);
});

test('profiles flow creates and activates profiles and protects the last profile', async () => {
  const initialBoot = baseBootstrap({
    connected: true,
    activeProviderId: 'prov-1',
    providerUrl: 'http://provider.test/',
    credentialStatus: 'remembered',
    profiles: [{ id: 'p1', name: 'Theo', avatar: 'red' }],
    activeProfileId: 'p1',
  });
  const profileCreate = vi.fn().mockResolvedValue({ id: 'p2', name: 'Alex', avatar: 'blue' });
  const profileActivate = vi.fn().mockResolvedValue(undefined);
  const api: DesktopApi = {
    bootstrap: vi.fn().mockResolvedValue(initialBoot),
    profileCreate,
    profileActivate,
  };

  render(<NativeApp api={api} />);
  fireEvent.click(await screen.findByRole('button', { name: 'Profile menu (Theo)' }));
  fireEvent.click(screen.getByRole('menuitem', { name: /Switch profile/i }));

  expect(await screen.findByText('Who’s watching?')).toBeTruthy();

  // Last profile cannot be deleted in edit mode
  fireEvent.click(screen.getByRole('button', { name: /Manage profiles/i }));
  fireEvent.click(screen.getByRole('button', { name: 'Theo' }));
  expect(screen.queryByRole('button', { name: /Remove profile/i })).toBeNull();
  fireEvent.click(screen.getByRole('button', { name: 'Close' }));
  fireEvent.click(screen.getByRole('button', { name: /Done/i }));

  // Create second profile
  fireEvent.click(screen.getByRole('button', { name: /Add profile/i }));
  fireEvent.change(screen.getByPlaceholderText('Your name'), { target: { value: 'Alex' } });
  fireEvent.click(screen.getByRole('radio', { name: 'Avatar blue' }));
  fireEvent.click(screen.getByRole('button', { name: /Create profile/i }));

  await waitFor(() => expect(profileCreate).toHaveBeenCalledWith({ name: 'Alex', avatar: 'blue' }));
  fireEvent.click(await screen.findByRole('button', { name: 'Alex' }));
  await waitFor(() => expect(profileActivate).toHaveBeenCalledWith({ id: 'p2' }));
});
