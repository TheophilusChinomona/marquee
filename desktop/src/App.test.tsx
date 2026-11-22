import { render, screen, fireEvent } from '@testing-library/react';
import { afterEach, expect, test, vi } from 'vitest';
import { cleanup } from '@testing-library/react';
import App from './App';
import { NativeApp } from './App';
afterEach(() => { cleanup(); vi.unstubAllGlobals(); });
test('native_bootstrap_failure_shows_retry', async () => {
  vi.stubGlobal('isTauri', true);
  vi.stubGlobal('__TAURI_INTERNALS__', { invoke: vi.fn().mockRejectedValue(new Error('unavailable')) });
  Element.prototype.scrollTo = vi.fn();
  render(<App />);
  expect(await screen.findByRole('button', { name: 'Retry' })).toBeTruthy();
  expect(screen.queryByText('Dune: Part Two')).toBeNull();
  fireEvent.click(screen.getByRole('button', { name: 'Retry' }));
  expect(await screen.findByRole('button', { name: 'Retry' })).toBeTruthy();
});

test('native startup retries against backend and recovers to real setup', async () => {
  const bootstrap = vi.fn().mockRejectedValueOnce(new Error('locked')).mockResolvedValue({ connected: false, profiles: [], activeProfileId: null, credentialStatus: 'none' });
  render(<NativeApp api={{ bootstrap }} />);
  fireEvent.click(await screen.findByRole('button', { name: 'Retry' }));
  expect(await screen.findByText('Welcome to Marquee')).toBeTruthy();
  expect(bootstrap).toHaveBeenCalledTimes(2);
  expect(screen.queryByText('Dune: Part Two')).toBeNull();
});


