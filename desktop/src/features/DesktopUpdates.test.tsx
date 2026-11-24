import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, expect, test, vi } from 'vitest';
import { invoke, isTauri } from '@tauri-apps/api/core';
import { check } from '@tauri-apps/plugin-updater';
import { relaunch } from '@tauri-apps/plugin-process';
import { DesktopUpdates, UpdatesProvider, UpdateNotice } from './DesktopUpdates';

vi.mock('@tauri-apps/api/core', () => ({ invoke: vi.fn(), isTauri: vi.fn() }));
vi.mock('@tauri-apps/plugin-updater', () => ({ check: vi.fn() }));
vi.mock('@tauri-apps/plugin-process', () => ({ relaunch: vi.fn() }));

beforeEach(() => {
  vi.mocked(isTauri).mockReturnValue(true);
  vi.mocked(invoke).mockImplementation(async (command) => command === 'update_context'
    ? { version: '1.15.3', enabled: true, installable: true } : undefined);
  vi.mocked(check).mockResolvedValue(null);
  vi.mocked(relaunch).mockResolvedValue();
});
afterEach(() => { cleanup(); vi.resetAllMocks(); });

function mount() {
  render(<UpdatesProvider><DesktopUpdates /></UpdatesProvider>);
}

function available() {
  const update = { version: '1.15.4', close: vi.fn().mockResolvedValue(undefined),
    download: vi.fn().mockResolvedValue(undefined), install: vi.fn().mockResolvedValue(undefined) };
  vi.mocked(check).mockResolvedValue(update as unknown as Awaited<ReturnType<typeof check>>);
  return update;
}

test('startup check shows installed version and a newer release without installing', async () => {
  const update = available();
  mount();
  expect(await screen.findByText('Marquee 1.15.4 is available.')).toBeTruthy();
  expect(screen.getByText('Windows & Linux · v1.15.3')).toBeTruthy();
  expect(screen.getByRole('button', { name: 'Update and restart' })).toBeTruthy();
  expect(update.download).not.toHaveBeenCalled();
});

test('manual checks show current version and allow retry after network failure', async () => {
  mount();
  expect(await screen.findByText('You’re up to date.')).toBeTruthy();
  vi.mocked(check).mockRejectedValueOnce(new Error('offline'));
  fireEvent.click(screen.getByRole('button', { name: 'Check for updates' }));
  expect(await screen.findByText(/Unable to check for updates/)).toBeTruthy();
  fireEvent.click(screen.getByRole('button', { name: 'Check for updates' }));
  expect(await screen.findByText('You’re up to date.')).toBeTruthy();
});

test('explicit installation shows progress and saves playback after download before installing', async () => {
  const update = available();
  const order: string[] = [];
  let finishDownload!: () => void;
  update.download.mockImplementation(async (event) => {
    order.push('download');
    event({ event: 'Started', data: { contentLength: 100 } });
    event({ event: 'Progress', data: { chunkLength: 50 } });
    await new Promise<void>(resolve => { finishDownload = resolve; });
  });
  vi.mocked(invoke).mockImplementation(async command => {
    if (command === 'update_context') return { version: '1.15.3', enabled: true, installable: true };
    order.push('save');
  });
  update.install.mockImplementation(async () => { order.push('install'); });
  vi.mocked(relaunch).mockImplementation(async () => { order.push('restart'); });
  mount();
  fireEvent.click(await screen.findByRole('button', { name: 'Update and restart' }));
  expect(await screen.findByText('Downloading update… 50%')).toBeTruthy();
  expect(screen.getByRole('button', { name: 'Updating…' }).hasAttribute('disabled')).toBe(true);
  expect(order).toEqual(['download']);
  finishDownload();
  await waitFor(() => expect(order).toEqual(['download', 'save', 'install', 'restart']));
});

test('signature/download failure preserves the running app and permits retry', async () => {
  const update = available();
  update.download.mockRejectedValueOnce(new Error('signature failed'));
  mount();
  fireEvent.click(await screen.findByRole('button', { name: 'Update and restart' }));
  expect(await screen.findByText(/Unable to install update/)).toBeTruthy();
  expect(update.install).not.toHaveBeenCalled();
  expect(relaunch).not.toHaveBeenCalled();
  expect(screen.getByRole('button', { name: 'Update and restart' }).hasAttribute('disabled')).toBe(false);
});

test('failed restart keeps an installed update retryable and releases its native handle', async () => {
  const update = available();
  vi.mocked(relaunch).mockRejectedValueOnce(new Error('restart failed'));
  mount();
  fireEvent.click(await screen.findByRole('button', { name: 'Update and restart' }));
  const restart = await screen.findByRole('button', { name: 'Restart application' });
  expect(screen.getByText('Update installed. Restart Marquee to finish.')).toBeTruthy();
  expect(update.close).toHaveBeenCalledOnce();
  fireEvent.click(restart);
  await waitFor(() => expect(relaunch).toHaveBeenCalledTimes(2));
  expect(update.install).toHaveBeenCalledOnce();
});

test('Debian users can discover updates and get a release download link', async () => {
  available();
  vi.mocked(invoke).mockResolvedValue({ version: '1.15.3', enabled: true, installable: false });
  mount();
  expect(await screen.findByRole('link', { name: 'Download new package' })).toBeTruthy();
  expect(screen.queryByRole('button', { name: 'Update and restart' })).toBeNull();
});

test('development and browser builds do not check production updates', async () => {
  vi.mocked(invoke).mockResolvedValue({ version: '0.1.0', enabled: false, installable: false });
  mount();
  expect(await screen.findByText('Updates are available in installed release builds.')).toBeTruthy();
  expect(check).not.toHaveBeenCalled();
  cleanup();
  vi.mocked(isTauri).mockReturnValue(false);
  mount();
  expect(await screen.findByText('Updates are available in installed release builds.')).toBeTruthy();
  expect(check).not.toHaveBeenCalled();
});

test('startup notice can be dismissed while Settings keeps the available update', async () => {
  available();
  render(<UpdatesProvider><DesktopUpdates /><UpdateNotice /></UpdatesProvider>);
  fireEvent.click(await screen.findByRole('button', { name: 'Later' }));
  expect(screen.queryByRole('button', { name: 'Later' })).toBeNull();
  expect(screen.getByText('Marquee 1.15.4 is available.')).toBeTruthy();
});
