import { lazy, Suspense, useCallback, useEffect, useRef, useState } from 'react';
import { ArrowRight, Check } from 'lucide-react';
import { isNative, nativeApi } from './api/client';
import type { AppSettings, Bootstrap, DesktopApi, Profile } from './api/types';
import Header, { type NavPage } from './components/Header';
import { EmptyState } from './components/StatusView';
import Connect from './features/Connect';
import NativePlayer from './features/NativePlayer';
import Profiles from './features/Profiles';
import Settings from './features/Settings';
import './App.css';

const PreviewApp = lazy(() => import('./PreviewApp'));

export function NativeApp({ api = nativeApi }: { api?: DesktopApi }) {
  const [state, setState] = useState<Bootstrap | null>(null);
  const [failed, setFailed] = useState(false);
  const [attempt, setAttempt] = useState(0);
  const [page, setPage] = useState<NavPage>('Home');
  const [settingsTab, setSettingsTab] = useState('General');
  const [profileMenu, setProfileMenu] = useState(false);
  const [toast, setToast] = useState('');
  const scroller = useRef<HTMLElement>(null);

  const notify = useCallback((message: string) => setToast(message), []);

  useEffect(() => {
    if (!toast) return;
    const t = setTimeout(() => setToast(''), 3000);
    return () => clearTimeout(t);
  }, [toast]);

  const loadBootstrap = useCallback(async () => {
    setFailed(false);
    try {
      const value = await api.bootstrap();
      setState(value);
      if (!value.connected && !value.cacheStatus?.hasCatalogue) {
        setPage('Connect');
      }
    } catch {
      setFailed(true);
    }
  }, [api]);

  useEffect(() => {
    let active = true;
    setFailed(false);
    api
      .bootstrap()
      .then((value) => {
        if (!active) return;
        setState(value);
        if (!value.connected && !value.cacheStatus?.hasCatalogue) {
          setPage('Connect');
        }
      })
      .catch(() => {
        if (active) setFailed(true);
      });
    return () => {
      active = false;
    };
  }, [api, attempt]);

  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        setProfileMenu(false);
      }
      if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 'k') {
        event.preventDefault();
        setPage('Search');
      }
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, []);

  if (state?.developmentPlayer) return <NativePlayer />;

  if (failed) {
    return (
      <main className="native-start">
        <div className="native-brand">
          MARQUEE<span>DESKTOP</span>
        </div>
        <h1>Unable to open your library</h1>
        <p>Marquee could not load local state. Please try again.</p>
        <button type="button" className="button primary" onClick={() => setAttempt((v) => v + 1)}>
          Retry
        </button>
      </main>
    );
  }

  if (!state) {
    return (
      <main className="native-start">
        <div className="native-brand">
          MARQUEE<span>DESKTOP</span>
        </div>
        <p role="status">Opening your library…</p>
      </main>
    );
  }

  const hasAccountOrCache = state.connected || state.cacheStatus?.hasCatalogue;
  const activeProfile =
    state.profiles.find((p) => p.id === state.activeProfileId) ?? state.profiles[0];

  function go(next: NavPage) {
    setPage(next);
    setProfileMenu(false);
    scroller.current?.scrollTo({ top: 0 });
  }

  function handleConnected(next: Bootstrap) {
    setState(next);
    setPage('Home');
    if (next.credentialWarning) {
      notify(next.credentialWarning);
    } else {
      notify('Connected to provider.');
    }
  }

  function handleProfilesChanged(
    profiles: Profile[],
    activeProfileId: string | null,
    activated?: boolean,
  ) {
    setState((prev) => (prev ? { ...prev, profiles, activeProfileId } : prev));
    if (activated) go('Home');
  }

  function handleSettingsChanged(settings: AppSettings) {
    setState((prev) => (prev ? { ...prev, settings } : prev));
  }

  if (!hasAccountOrCache && page === 'Connect') {
    return (
      <div className="desktop-app">
        <main className="main-scroll">
          <Connect
            api={api}
            initialUrl={state.providerUrl}
            canGoBack={false}
            onConnected={handleConnected}
          />
        </main>
        {toast && (
          <div className="toast" role="status">
            <Check size={17} />
            {toast}
          </div>
        )}
      </div>
    );
  }

  return (
    <div className="desktop-app">
      <Header
        page={page}
        hasDetail={false}
        activeProfile={activeProfile}
        profileMenuOpen={profileMenu}
        onToggleProfileMenu={() => setProfileMenu((v) => !v)}
        onNavigate={go}
      />

      {!state.connected && state.cacheStatus?.hasCatalogue && (
        <div className="offline-banner" role="status">
          <span>
            Disconnected · Browsing cached library offline. Reconnect to refresh or start playback.
          </span>
          <button type="button" className="button secondary compact" onClick={() => go('Connect')}>
            Reconnect <ArrowRight size={13} />
          </button>
        </div>
      )}

      {state.credentialWarning && state.connected && (
        <div className="offline-banner" role="status">
          <span>{state.credentialWarning}</span>
        </div>
      )}

      <main ref={scroller} className="main-scroll">
        {page === 'Connect' ? (
          <Connect
            api={api}
            initialUrl={state.providerUrl}
            canGoBack={hasAccountOrCache}
            onBack={() => go('Settings')}
            onConnected={handleConnected}
          />
        ) : page === 'Profiles' ? (
          <Profiles
            api={api}
            profiles={state.profiles}
            activeProfileId={state.activeProfileId}
            onChanged={handleProfilesChanged}
            onNotify={notify}
          />
        ) : page === 'Settings' ? (
          <Settings
            api={api}
            bootstrap={state}
            initialTab={settingsTab}
            onGoProfiles={() => go('Profiles')}
            onGoConnect={() => go('Connect')}
            onRefreshLibrary={() => notify('Catalogue synchronization is next.')}
            onDisconnected={() => void loadBootstrap()}
            onSettingsChanged={handleSettingsChanged}
            onNotify={notify}
          />
        ) : (
          <section className="section-pad">
            <EmptyState
              title="Ready to synchronize your library"
              description="Your provider account is connected. Refresh to load movies, series, and live channels."
              action="Open provider settings"
              onAction={() => {
                setSettingsTab('Provider');
                go('Settings');
              }}
            />
          </section>
        )}

        <footer className="app-footer">
          <span className="footer-brand">MARQUEE</span>
          <span>Your seat. Your stories.</span>
          <div>
            <span className="footer-dot" />{' '}
            {state.connected ? 'Provider connected' : 'Offline cache'}
            <span className="footer-separator">/</span>
            <button
              type="button"
              onClick={() => {
                setSettingsTab('About Marquee');
                go('Settings');
              }}
            >
              About <ArrowRight size={12} />
            </button>
          </div>
        </footer>
      </main>

      {toast && (
        <div className="toast" role="status">
          <Check size={17} />
          {toast}
        </div>
      )}
    </div>
  );
}

export default function App() {
  return isNative() ? (
    <NativeApp />
  ) : (
    <Suspense fallback={<p>Opening preview…</p>}>
      <PreviewApp />
    </Suspense>
  );
}
