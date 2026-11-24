import { createContext, useCallback, useContext, useEffect, useRef, useState, type ReactNode } from 'react';
import { invoke, isTauri } from '@tauri-apps/api/core';
import { check, type Update } from '@tauri-apps/plugin-updater';
import { relaunch } from '@tauri-apps/plugin-process';
import { SettingRow } from '../components/StatusView';

type UpdateContext = { version: string; enabled: boolean; installable: boolean };
type Status = 'idle' | 'checking' | 'current' | 'available' | 'downloading' | 'installing' | 'installed' | 'error';
type Updates = {
  context: UpdateContext | null;
  status: Status;
  version: string | null;
  progress: number | null;
  error: string | null;
  dismissed: boolean;
  dismiss: () => void;
  checkNow: () => Promise<void>;
  install: () => Promise<void>;
};
const Context = createContext<Updates | null>(null);
const releaseUrl = 'https://github.com/TheophilusChinomona/marquee/releases/latest';

export function UpdatesProvider({ children }: { children: ReactNode }) {
  const [context, setContext] = useState<UpdateContext | null>(null);
  const [status, setStatus] = useState<Status>('idle');
  const [version, setVersion] = useState<string | null>(null);
  const [progress, setProgress] = useState<number | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [dismissed, setDismissed] = useState(false);
  const update = useRef<Update | null>(null);
  const busy = useRef(false);
  const mounted = useRef(false);

  const checkNow = useCallback(async () => {
    if (busy.current) return;
    busy.current = true;
    setError(null);
    setStatus('checking');
    try {
      const next = await check({ timeout: 15000 });
      if (!mounted.current) { await next?.close(); return; }
      const previous = update.current;
      update.current = next;
      void previous?.close().catch(() => {});
      setVersion(next?.version ?? null);
      setDismissed(false);
      setStatus(next ? 'available' : 'current');
    } catch {
      if (mounted.current) {
        setError('Unable to check for updates. Check your connection and try again.');
        setStatus('error');
      }
    } finally { busy.current = false; }
  }, []);

  useEffect(() => {
    mounted.current = true;
    let cancelled = false;
    void (async () => {
      try {
        const info = isTauri() ? await invoke<UpdateContext>('update_context')
          : { version: '', enabled: false, installable: false };
        if (cancelled) return;
        setContext(info);
        if (info.enabled) await checkNow();
      } catch {
        if (!cancelled) setError('Unable to load update settings. Restart Marquee to try again.');
      }
    })();
    return () => {
      cancelled = true;
      mounted.current = false;
      const previous = update.current;
      update.current = null;
      void previous?.close().catch(() => {});
    };
  }, [checkNow]);

  async function install() {
    if (busy.current || !context?.installable) return;
    const selected = update.current;
    if (!selected && status !== 'installed') return;
    busy.current = true;
    setError(null);
    try {
      if (status !== 'installed' && selected) {
        setStatus('downloading');
        setProgress(null);
        let total = 0;
        let received = 0;
        await selected.download(event => {
          if (event.event === 'Started') { total = event.data.contentLength ?? 0; received = 0; }
          if (event.event === 'Progress') received += event.data.chunkLength;
          if (total > 0) setProgress(Math.min(100, Math.round(received / total * 100)));
        }, { timeout: 120000 });
        // Download verifies the signature before playback is stopped or files are replaced.
        setStatus('installing');
        await invoke('prepare_update');
        await selected.install();
        void selected.close().catch(() => {});
        update.current = null;
        setStatus('installed');
      }
      await relaunch();
    } catch {
      setError('Unable to install update. Check your connection and try again. If installation finished, restart Marquee.');
      // An installed update only needs a restart, never another installation.
      setStatus(previous => previous === 'installed' ? 'installed' : 'error');
    } finally { busy.current = false; }
  }

  return <Context.Provider value={{ context, status, version, progress, error, dismissed,
    dismiss: () => setDismissed(true), checkNow, install }}>{children}</Context.Provider>;
}

function UpdateAction({ updates }: { updates: Updates }) {
  if (!updates.context?.installable) {
    return <a className="button secondary compact" href={releaseUrl} target="_blank" rel="noreferrer">Download new package</a>;
  }
  const busy = updates.status === 'checking' || updates.status === 'downloading' || updates.status === 'installing';
  return <button type="button" className="button primary compact" disabled={busy} onClick={() => void updates.install()}>
    {busy ? 'Updating…' : updates.status === 'installed' ? 'Restart application' : 'Update and restart'}
  </button>;
}

function updateMessage(updates: Updates) {
  if (updates.status === 'downloading') return `Downloading update…${updates.progress === null ? '' : ` ${updates.progress}%`}`;
  if (updates.status === 'installing') return 'Installing update… Marquee will restart.';
  if (updates.status === 'installed') return 'Update installed. Restart Marquee to finish.';
  if (updates.status === 'checking') return 'Checking for updates…';
  if (updates.version) return `Marquee ${updates.version} is available.`;
  if (updates.status === 'current') return 'You’re up to date.';
  return 'Check for the latest Marquee release.';
}

export function DesktopUpdates() {
  const updates = useContext(Context);
  const busy = updates?.status === 'checking' || updates?.status === 'downloading' || updates?.status === 'installing';
  return <>
    <SettingRow title="Marquee for desktop" description={`Windows & Linux${updates?.context?.version ? ` · v${updates.context.version}` : ''}`}>
      <span className="status-tag">DESKTOP</span>
    </SettingRow>
    <SettingRow title="Updates" description={updates?.context?.enabled
      ? updateMessage(updates) : 'Updates are available in installed release builds.'}>
      {updates?.context?.enabled && <div className="update-actions">
        <button type="button" className="button secondary compact" disabled={busy || updates.status === 'installed'}
          onClick={() => void updates.checkNow()}>Check for updates</button>
        {updates.version && <UpdateAction updates={updates} />}
      </div>}
    </SettingRow>
    {updates?.error && <p role="alert" className="update-error">{updates.error}</p>}
    {updates?.context?.enabled && !updates.context.installable &&
      <p className="settings-intro">Install the new Debian package manually, or use AppImage for in-app updates.</p>}
  </>;
}

export function UpdateNotice() {
  const updates = useContext(Context);
  if (!updates?.version || updates.dismissed) return null;
  const installing = updates.status === 'downloading' || updates.status === 'installing' || updates.status === 'installed';
  return <aside className="desktop-update-notice" aria-label="Marquee update" role="status">
    <span>{updateMessage(updates)}</span>
    {updates.error && <span role="alert">{updates.error}</span>}
    <UpdateAction updates={updates} />
    {!installing && <button type="button" className="button secondary compact" onClick={updates.dismiss}>Later</button>}
  </aside>;
}
