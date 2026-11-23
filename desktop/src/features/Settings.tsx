import { useState } from 'react';
import {
  ArrowRight,
  Check,
  ChevronRight,
  Globe,
  Info,
  LogOut,
  Moon,
  Play,
  RefreshCw,
  Settings as SettingsIcon,
  ShieldCheck,
  UserRound,
} from 'lucide-react';
import {
  extractAppError,
  type AppSettings,
  type Bootstrap,
  type CatalogProgressEvent,
  type DesktopApi,
} from '../api/types';
import { ErrorBanner, SettingRow, Toggle } from '../components/StatusView';

interface SettingsProps {
  api: DesktopApi;
  bootstrap: Bootstrap;
  initialTab?: string;
  refreshProgress?: CatalogProgressEvent | null;
  onGoProfiles: () => void;
  onGoConnect: () => void;
  onGoOnline?: () => void;
  onRefreshLibrary: () => void;
  onDisconnected: () => void;
  onSettingsChanged: (settings: AppSettings) => void;
  onNotify: (message: string) => void;
}

export default function Settings({
  api,
  bootstrap,
  initialTab = 'General',
  refreshProgress,
  onGoProfiles,
  onGoConnect,
  onGoOnline,
  onRefreshLibrary,
  onDisconnected,
  onSettingsChanged,
  onNotify,
}: SettingsProps) {
  const [tab, setTab] = useState(initialTab);
  const [error, setError] = useState<string | null>(null);
  const [disconnecting, setDisconnecting] = useState(false);
  const [onlineBusy, setOnlineBusy] = useState(false);

  const activeProfile =
    bootstrap.profiles.find((p) => p.id === bootstrap.activeProfileId) ?? bootstrap.profiles[0];

  const tabs = [
    { name: 'General', icon: SettingsIcon },
    { name: 'Provider', icon: Globe },
    { name: 'Online Library', icon: Globe },
    { name: 'Playback', icon: Play },
    { name: 'Appearance', icon: Moon },
    { name: 'About Marquee', icon: Info },
  ];

  async function updateSetting(patch: Partial<AppSettings>) {
    if (!api.settingsUpdate) return;
    setError(null);
    try {
      const updated = await api.settingsUpdate(patch);
      onSettingsChanged(updated);
    } catch (err) {
      setError(extractAppError(err, 'Unable to save setting.').message);
    }
  }

  async function handleDisconnect() {
    if (!api.providerDisconnect || disconnecting) return;
    setDisconnecting(true);
    setError(null);
    try {
      await api.providerDisconnect();
      onDisconnected();
      onNotify('Disconnected from provider.');
    } catch (err) {
      setError(extractAppError(err, 'Unable to disconnect cleanly.').message);
      onDisconnected();
    } finally {
      setDisconnecting(false);
    }
  }

  const credentialLabel =
    bootstrap.credentialStatus === 'remembered'
      ? 'REMEMBERED'
      : bootstrap.credentialStatus === 'session'
        ? 'SESSION ONLY'
        : 'DISCONNECTED';

  const hostLabel = (() => {
    if (!bootstrap.providerUrl) return 'No provider connected';
    try {
      return new URL(bootstrap.providerUrl).host;
    } catch {
      return bootstrap.providerUrl;
    }
  })();

  const refreshedLabel = bootstrap.cacheStatus.refreshedAtMs
    ? `Updated ${new Date(bootstrap.cacheStatus.refreshedAtMs).toLocaleString()}`
    : 'Not yet synchronized';

  return (
    <div className="settings-page section-pad">
      <div className="page-heading">
        <div>
          <span className="eyebrow">JUST THE WAY YOU LIKE IT</span>
          <h1>Settings</h1>
        </div>
      </div>

      <div className="settings-layout">
        <aside>
          {tabs.map(({ name, icon: Icon }) => (
            <button
              type="button"
              key={name}
              className={tab === name ? 'selected' : ''}
              onClick={() => setTab(name)}
            >
              <Icon size={17} />
              {name}
              <ChevronRight size={14} />
            </button>
          ))}
        </aside>

        <div className="settings-content">
          <h2>{tab}</h2>
          <p className="settings-intro">
            {tab === 'General'
              ? 'The little things that make Marquee yours.'
              : tab === 'Provider'
                ? 'Your library starts with your provider.'
                : tab === 'Playback'
                  ? 'Settle in. We’ll take care of the rest.'
                  : tab === 'Appearance'
                    ? 'A little less interface. A little more cinema.'
                    : 'A personal cinema. Built for your desktop.'}
          </p>

          {error && <ErrorBanner message={error} />}

          {tab === 'Online Library' && <>
            <div className="provider-card"><Globe size={24} /><div><strong>{bootstrap.online?.baseUrl ?? 'No online library connected'}</strong><span>{bootstrap.online?.connected ? 'Search movies and series online' : 'Connect your resolver to search and watch'}</span></div><span className="status-tag">{bootstrap.online?.credentialStatus === 'remembered' ? 'REMEMBERED' : bootstrap.online?.connected ? 'SESSION ONLY' : 'DISCONNECTED'}</span></div>
            {bootstrap.online?.warning && <ErrorBanner message={bootstrap.online.warning} />}
            <SettingRow title="Online connection" description="Connect independently of your IPTV provider."><button className="button secondary compact" type="button" onClick={onGoOnline ?? onGoConnect}>{bootstrap.online?.connected ? 'Change online connection' : 'Connect online library'}</button></SettingRow>
            {bootstrap.online?.connected && <SettingRow title="Check connection" description="Check authentication and resolver availability."><button className="button secondary compact" type="button" disabled={onlineBusy} onClick={async () => {
              setOnlineBusy(true); setError(null);
              try { await api.onlineCheck?.(); onNotify('Online library connection is healthy.'); }
              catch (err) { setError(extractAppError(err, 'Unable to reach the online library.').message); }
              finally { setOnlineBusy(false); }
            }}>{onlineBusy ? 'Checking…' : 'Check connection'}</button></SettingRow>}
            {bootstrap.online?.baseUrl && <SettingRow title="Disconnect online library" description="Clear saved credentials. Keep your saved titles and watch progress."><button className="button outline compact danger" type="button" disabled={onlineBusy} onClick={async () => {
              setOnlineBusy(true); setError(null);
              try { await api.onlineDisconnect?.(); onNotify('Disconnected from online library.'); }
              catch (err) { setError(extractAppError(err, 'Unable to clear online credentials.').message); }
              finally { onDisconnected(); setOnlineBusy(false); }
            }}>Disconnect online library</button></SettingRow>}
          </>}

          {tab === 'General' && (
            <>
              <SettingRow
                title="Watching as"
                description="Profiles keep your list and watch history separate."
              >
                <button
                  type="button"
                  className="button secondary compact"
                  onClick={onGoProfiles}
                >
                  <UserRound size={14} />
                  {activeProfile?.name ?? 'Select profile'}
                  <ChevronRight size={14} />
                </button>
              </SettingRow>
              <SettingRow title="Language" description="The language used throughout Marquee.">
                <select aria-label="Language" defaultValue="English">
                  <option>English</option>
                </select>
              </SettingRow>
              <SettingRow
                title="Continue watching"
                description="Pick up right where you left off."
              >
                <span className="status-positive">
                  <Check size={14} /> Always on
                </span>
              </SettingRow>
              <div className="privacy-note">
                <ShieldCheck size={21} />
                <div>
                  <strong>Your viewing stays yours.</strong>
                  <p>Profiles, favourites, and watch history stay on your device.</p>
                </div>
              </div>
            </>
          )}

          {tab === 'Provider' && (
            <>
              <div className="provider-card">
                <span className="provider-symbol">
                  <Globe size={24} />
                </span>
                <div>
                  <strong>{hostLabel}</strong>
                  <span>
                    {bootstrap.connected
                      ? `${bootstrap.cacheStatus.movieCount.toLocaleString()} movies · ${bootstrap.cacheStatus.seriesCount.toLocaleString()} series · ${bootstrap.cacheStatus.liveCount.toLocaleString()} live channels`
                      : bootstrap.cacheStatus.hasCatalogue
                        ? 'Offline cache available · Reconnect to stream or refresh'
                        : 'Connect your Xtream Codes account to load your library'}
                  </span>
                </div>
                <span className="status-tag">{credentialLabel}</span>
              </div>

              {bootstrap.credentialWarning && (
                <ErrorBanner message={bootstrap.credentialWarning} />
              )}

              <SettingRow
                title={bootstrap.connected ? 'Change provider' : 'Connect your provider'}
                description="Use your existing Xtream Codes account."
              >
                <button
                  type="button"
                  className="button secondary compact"
                  onClick={onGoConnect}
                >
                  {bootstrap.connected ? 'Switch account' : 'Connect'}
                  <ArrowRight size={14} />
                </button>
              </SettingRow>

              <SettingRow
                title="Library refresh"
                description={
                  refreshProgress && refreshProgress.status === 'running'
                    ? `Refreshing ${refreshProgress.stage} (${refreshProgress.completedCategories}/${refreshProgress.totalCategories})…`
                    : refreshedLabel
                }
              >
                <button
                  type="button"
                  className="button secondary compact"
                  disabled={!bootstrap.connected || bootstrap.cacheStatus.refreshing}
                  onClick={onRefreshLibrary}
                >
                  <RefreshCw size={14} />
                  {bootstrap.cacheStatus.refreshing ? 'Refreshing…' : 'Refresh library'}
                </button>
              </SettingRow>

              {bootstrap.connected && (
                <SettingRow
                  title="Disconnect"
                  description="Stop playback and clear saved credentials from this device."
                >
                  <button
                    type="button"
                    className="button outline compact danger"
                    disabled={disconnecting}
                    onClick={() => void handleDisconnect()}
                  >
                    <LogOut size={14} />
                    {disconnecting ? 'Disconnecting…' : 'Disconnect'}
                  </button>
                </SettingRow>
              )}
            </>
          )}

          {tab === 'Playback' && (
            <>
              <SettingRow
                title="Play next episode automatically"
                description="Keep the story going when an episode ends."
              >
                <Toggle
                  label="Play next episode automatically"
                  value={bootstrap.settings.autoplayNext}
                  onChange={(v) => void updateSetting({ autoplayNext: v })}
                />
              </SettingRow>
              <SettingRow
                title="Subtitles by default"
                description="Show subtitles whenever they’re available."
              >
                <Toggle
                  label="Subtitles by default"
                  value={bootstrap.settings.subtitlesDefault}
                  onChange={(v) => void updateSetting({ subtitlesDefault: v })}
                />
              </SettingRow>
              <SettingRow
                title="Preferred audio"
                description="Used when a title offers multiple languages."
              >
                <select
                  aria-label="Preferred audio"
                  value={bootstrap.settings.preferredAudio}
                  onChange={(e) => void updateSetting({ preferredAudio: e.target.value })}
                >
                  <option value="English">English</option>
                  <option value="Original language">Original language</option>
                </select>
              </SettingRow>
              <SettingRow
                title="Playback engine"
                description="Embedded native libmpv playback for desktop codecs."
              >
                <span className="status-positive">Embedded libmpv</span>
              </SettingRow>
            </>
          )}

          {tab === 'Appearance' && (
            <>
              <SettingRow
                title="Theme"
                description="Made for the moments after the lights go down."
              >
                <span className="theme-choice">
                  <Moon size={14} />
                  Cinema dark
                  <Check size={14} />
                </span>
              </SettingRow>
              <SettingRow
                title="Compact library"
                description="Fit a few more titles on your screen."
              >
                <Toggle
                  label="Compact library"
                  value={bootstrap.settings.compactLibrary}
                  onChange={(v) => void updateSetting({ compactLibrary: v })}
                />
              </SettingRow>
              <SettingRow
                title="Motion"
                description="Subtle transitions that respect your system settings."
              >
                <span className="subtle">Follow system</span>
              </SettingRow>
            </>
          )}

          {tab === 'About Marquee' && (
            <>
              <div className="about-logo">
                MARQUEE<span>Your seat. Your stories.</span>
              </div>
              <SettingRow title="Marquee for desktop" description="Windows & Linux · v0.1.0">
                <span className="status-tag">DESKTOP</span>
              </SettingRow>
              <SettingRow
                title="Updates"
                description="Signed desktop updates remain unconfigured until release keys and hosting are provided."
              >
                <span className="subtle">Not configured</span>
              </SettingRow>
            </>
          )}
        </div>
      </div>
    </div>
  );
}
