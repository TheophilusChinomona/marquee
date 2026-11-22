import { lazy, Suspense, useCallback, useEffect, useRef, useState } from 'react';
import { ArrowRight, Check, RefreshCw } from 'lucide-react';
import { isNative, nativeApi } from './api/client';
import {
  extractAppError,
  type AppSettings,
  type Bootstrap,
  type CatalogProgressEvent,
  type DesktopApi,
  type MediaItem,
  type MediaRef,
  type PlayerSnapshot,
  type Profile,
} from './api/types';
import Header, { type NavPage } from './components/Header';
import { EmptyState } from './components/StatusView';
import { mediaKey } from './components/TitleCard';
import Browse from './features/Browse';
import Connect from './features/Connect';
import Details from './features/Details';
import Home from './features/Home';
import LiveGuide from './features/LiveGuide';
import MyList from './features/MyList';
import NativePlayer from './features/NativePlayer';
import Player from './features/Player';
import Profiles from './features/Profiles';
import Search from './features/Search';
import Settings from './features/Settings';
import './App.css';

const PreviewApp = lazy(() => import('./PreviewApp'));

export function NativeApp({ api = nativeApi }: { api?: DesktopApi }) {
  const [state, setState] = useState<Bootstrap | null>(null);
  const [failed, setFailed] = useState(false);
  const [attempt, setAttempt] = useState(0);
  const [page, setPage] = useState<NavPage>('Home');
  const [selectedRef, setSelectedRef] = useState<MediaRef | null>(null);
  const [settingsTab, setSettingsTab] = useState('General');
  const [profileMenu, setProfileMenu] = useState(false);
  const [toast, setToast] = useState('');
  const [savedKeys, setSavedKeys] = useState<Set<string>>(() => new Set());
  const [listVersion, setListVersion] = useState(0);
  const [refreshStamp, setRefreshStamp] = useState(0);
  const [refreshProgress, setRefreshProgress] = useState<CatalogProgressEvent | null>(null);
  const [activePlayer, setActivePlayer] = useState<PlayerSnapshot | null>(null);
  const [lastPlayArgs, setLastPlayArgs] = useState<{
    ref: MediaRef;
    episodeId?: string;
    resume: boolean;
  } | null>(null);
  const activeRefreshIdRef = useRef<string | null>(null);
  const scroller = useRef<HTMLElement>(null);

  const notify = useCallback((message: string) => setToast(message), []);

  useEffect(() => {
    if (!toast) return;
    const t = setTimeout(() => setToast(''), 3200);
    return () => clearTimeout(t);
  }, [toast]);

  const loadFavoritesSnapshot = useCallback(async () => {
    if (!api.favoritesList) return;
    try {
      const res = await api.favoritesList({ offset: 0, limit: 200 });
      setSavedKeys(new Set(res.items.map((item) => mediaKey(item.ref))));
    } catch {
      setSavedKeys(new Set());
    }
  }, [api]);

  const loadBootstrap = useCallback(async () => {
    setFailed(false);
    try {
      const value = await api.bootstrap();
      setState(value);
      if (!value.connected && !value.cacheStatus?.hasCatalogue) {
        setPage('Connect');
      }
      if (value.activeProviderId && value.activeProfileId) {
        await loadFavoritesSnapshot();
      }
    } catch {
      setFailed(true);
    }
  }, [api, loadFavoritesSnapshot]);

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
        if (value.activeProviderId && value.activeProfileId) {
          void loadFavoritesSnapshot();
        }
        if (api.playerSnapshot) {
          void api
            .playerSnapshot()
            .then((snap) => {
              if (active && snap.media && snap.status !== 'stopped') {
                setActivePlayer(snap);
              }
            })
            .catch(() => {});
        }
      })
      .catch(() => {
        if (active) setFailed(true);
      });
    return () => {
      active = false;
    };
  }, [api, attempt, loadFavoritesSnapshot]);

  // Subscribe to catalog-progress events before triggering refreshes, unlisten on teardown.
  useEffect(() => {
    if (!api.onCatalogProgress) return;
    let disposed = false;
    let unlistenFn: (() => void) | null = null;
    api
      .onCatalogProgress((ev) => {
        if (disposed) return;
        if (
          activeRefreshIdRef.current &&
          ev.refreshId &&
          ev.refreshId !== activeRefreshIdRef.current &&
          ev.status === 'running'
        ) {
          // Track latest refresh ID if backend initiated it
          activeRefreshIdRef.current = ev.refreshId;
        }
        if (activeRefreshIdRef.current && ev.refreshId !== activeRefreshIdRef.current) {
          return;
        }
        setRefreshProgress(ev);
        if (ev.status === 'completed') {
          setRefreshStamp((s) => s + 1);
          void loadBootstrap();
          notify('Library updated.');
        } else if (ev.status === 'failed') {
          notify(ev.error?.message ?? 'Library refresh failed. Keeping previous catalogue.');
          void loadBootstrap();
        }
      })
      .then((unlisten) => {
        if (disposed) {
          unlisten();
        } else {
          unlistenFn = unlisten;
        }
      })
      .catch(() => {});
    return () => {
      disposed = true;
      unlistenFn?.();
    };
  }, [api, loadBootstrap, notify]);

  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      if (activePlayer) return;
      if (event.key === 'Escape') {
        setSelectedRef(null);
        setProfileMenu(false);
      }
      if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 'k') {
        event.preventDefault();
        setSelectedRef(null);
        setPage('Search');
      }
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [activePlayer]);

  const triggerRefresh = useCallback(async () => {
    if (!api.catalogRefresh) {
      notify('Library refresh is unavailable.');
      return;
    }
    setState((prev) =>
      prev ? { ...prev, cacheStatus: { ...prev.cacheStatus, refreshing: true } } : prev,
    );
    try {
      const refreshId = await api.catalogRefresh();
      activeRefreshIdRef.current = refreshId;
      setRefreshStamp((s) => s + 1);
      await loadBootstrap();
    } catch (err) {
      notify(extractAppError(err, 'Unable to refresh library.').message);
      await loadBootstrap();
    }
  }, [api, loadBootstrap, notify]);

  const handleToggleSave = useCallback(
    async (item: MediaItem) => {
      if (!api.favoriteSet) return;
      const key = mediaKey(item.ref);
      const currentlySaved = savedKeys.has(key);
      const nextSaved = !currentlySaved;
      setSavedKeys((prev) => {
        const next = new Set(prev);
        if (nextSaved) next.add(key);
        else next.delete(key);
        return next;
      });
      try {
        await api.favoriteSet({ ref: item.ref, saved: nextSaved });
        setListVersion((v) => v + 1);
        setRefreshStamp((s) => s + 1);
        notify(nextSaved ? 'Added to My List' : 'Removed from My List');
      } catch (err) {
        // Revert optimistic update on error
        setSavedKeys((prev) => {
          const next = new Set(prev);
          if (currentlySaved) next.add(key);
          else next.delete(key);
          return next;
        });
        notify(extractAppError(err, 'Unable to update My List.').message);
      }
    },
    [api, savedKeys, notify],
  );

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
    setSelectedRef(null);
    setProfileMenu(false);
    scroller.current?.scrollTo({ top: 0 });
  }

  function handleConnected(next: Bootstrap) {
    setState(next);
    setSelectedRef(null);
    setPage('Home');
    void loadFavoritesSnapshot();
    if (next.credentialWarning) {
      notify(next.credentialWarning);
    } else {
      notify('Connected to provider.');
    }
    if (next.connected && (!next.cacheStatus.hasCatalogue || next.cacheStatus.stale)) {
      void triggerRefresh();
    }
  }

  function handleProfilesChanged(
    profiles: Profile[],
    activeProfileId: string | null,
    activated?: boolean,
  ) {
    setState((prev) => (prev ? { ...prev, profiles, activeProfileId } : prev));
    setSelectedRef(null);
    setListVersion((v) => v + 1);
    setRefreshStamp((s) => s + 1);
    void loadFavoritesSnapshot();
    if (activated) go('Home');
  }

  function handleSettingsChanged(settings: AppSettings) {
    setState((prev) => (prev ? { ...prev, settings } : prev));
  }

  function handleSelectItem(item: MediaItem) {
    setSelectedRef(item.ref);
    scroller.current?.scrollTo({ top: 0 });
  }

  function handleStartPlayback(ref: MediaRef, episodeId?: string, resume = true) {
    if (!state?.connected) {
      notify('Reconnect your provider to start playback.');
      return;
    }
    if (!api.playerStart) {
      notify('Playback is unavailable in this mode.');
      return;
    }
    setLastPlayArgs({ ref, episodeId, resume });
    void api
      .playerStart({ ref, episodeId, resume })
      .then((snap) => {
        setActivePlayer(snap);
      })
      .catch((err) => notify(extractAppError(err, 'Unable to start playback.').message));
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
        hasDetail={Boolean(selectedRef)}
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

      {refreshProgress && refreshProgress.status === 'running' && (
        <div className="refresh-banner" role="status">
          <RefreshCw size={14} />
          <span>
            Synchronizing {refreshProgress.stage}
            {refreshProgress.totalCategories > 0
              ? ` (${refreshProgress.completedCategories}/${refreshProgress.totalCategories})`
              : '…'}
          </span>
        </div>
      )}

      <main ref={scroller} className="main-scroll">
        {selectedRef ? (
          <Details
            api={api}
            itemRef={selectedRef}
            backLabel={page === 'Home' ? 'browse' : page.toLowerCase()}
            savedKeys={savedKeys}
            onBack={() => setSelectedRef(null)}
            onSelect={handleSelectItem}
            onPlay={handleStartPlayback}
            onToggleSave={(item) => void handleToggleSave(item)}
          />
        ) : page === 'Home' ? (
          <Home
            api={api}
            profileId={state.activeProfileId}
            refreshStamp={refreshStamp}
            connected={state.connected}
            savedKeys={savedKeys}
            onSelect={handleSelectItem}
            onPlay={(item, resume) => handleStartPlayback(item.ref, item.episodeId, resume)}
            onToggleSave={(item) => void handleToggleSave(item)}
            onNavigate={go}
            onRefreshLibrary={() => void triggerRefresh()}
          />
        ) : page === 'Movies' || page === 'Series' ? (
          <Browse
            api={api}
            kind={page === 'Series' ? 'series' : 'movie'}
            dense={state.settings.compactLibrary}
            refreshStamp={refreshStamp}
            savedKeys={savedKeys}
            onSelect={handleSelectItem}
            onToggleSave={(item) => void handleToggleSave(item)}
          />
        ) : page === 'Search' ? (
          <Search
            api={api}
            dense={state.settings.compactLibrary}
            savedKeys={savedKeys}
            onSelect={handleSelectItem}
            onPlayLive={(item) => handleStartPlayback(item.ref, undefined, false)}
            onToggleSave={(item) => void handleToggleSave(item)}
          />
        ) : page === 'My List' ? (
          <MyList
            api={api}
            profileId={state.activeProfileId}
            listVersion={listVersion}
            dense={state.settings.compactLibrary}
            savedKeys={savedKeys}
            onSelect={handleSelectItem}
            onToggleSave={(item) => void handleToggleSave(item)}
            onExploreMovies={() => go('Movies')}
          />
        ) : page === 'Connect' ? (
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
            refreshProgress={refreshProgress}
            onGoProfiles={() => go('Profiles')}
            onGoConnect={() => go('Connect')}
            onRefreshLibrary={() => void triggerRefresh()}
            onDisconnected={() => void loadBootstrap()}
            onSettingsChanged={handleSettingsChanged}
            onNotify={notify}
          />
        ) : page === 'Live TV' ? (
          <LiveGuide
            api={api}
            refreshStamp={refreshStamp}
            onPlayChannel={(channel) => handleStartPlayback(channel.ref, undefined, false)}
            onNotify={notify}
          />
        ) : (
          <section className="section-pad">
            <EmptyState
              title="Ready to watch"
              description="Choose a section above to browse your library."
              action="Go to Home"
              onAction={() => go('Home')}
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

      {activePlayer && (
        <Player
          api={api}
          initialSnapshot={activePlayer}
          onClose={() => {
            setActivePlayer(null);
            setRefreshStamp((s) => s + 1);
          }}
          onRetry={() => {
            if (lastPlayArgs) {
              handleStartPlayback(lastPlayArgs.ref, lastPlayArgs.episodeId, true);
            }
          }}
        />
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
