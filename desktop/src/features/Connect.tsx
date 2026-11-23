import { useState } from 'react';
import { ArrowLeft, ArrowRight, Globe, Layers, ShieldCheck } from 'lucide-react';
import { extractAppError, type Bootstrap, type DesktopApi } from '../api/types';
import { ErrorBanner } from '../components/StatusView';
import OnlineConnect from './OnlineConnect';

interface ConnectProps {
  api: DesktopApi;
  initialUrl?: string | null;
  onlineUrl?: string | null;
  developmentImport?: boolean;
  initialMode?: 'iptv' | 'online';
  canGoBack: boolean;
  onBack?: () => void;
  onConnected: (bootstrap: Bootstrap) => void;
}

export default function Connect({
  api,
  initialUrl,
  onlineUrl,
  developmentImport,
  initialMode = 'iptv',
  canGoBack,
  onBack,
  onConnected,
}: ConnectProps) {
  const [mode, setMode] = useState(initialMode);
  const [baseUrl, setBaseUrl] = useState(initialUrl ?? '');
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [remember, setRemember] = useState(true);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function handleSubmit(event: React.FormEvent) {
    event.preventDefault();
    if (!api.providerConnect || submitting) return;
    const submittedPassword = password;
    // Clear password from form state immediately upon submission.
    setPassword('');
    setError(null);
    setSubmitting(true);
    try {
      const next = await api.providerConnect({
        baseUrl: baseUrl.trim(),
        username: username.trim(),
        password: submittedPassword,
        remember,
      });
      onConnected(next);
    } catch (err) {
      setError(extractAppError(err, 'Unable to connect to your provider.').message);
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <div className="connect-page">
      {canGoBack && onBack && (
        <button type="button" className="back-button" onClick={onBack}>
          <ArrowLeft size={16} /> Back
        </button>
      )}
      <div className="chips" role="group" aria-label="Connection type">
        <button type="button" className={mode === 'iptv' ? 'chip selected' : 'chip'} disabled={submitting} onClick={() => setMode('iptv')}>IPTV provider</button>
        <button type="button" className={mode === 'online' ? 'chip selected' : 'chip'} disabled={submitting} onClick={() => setMode('online')}>Online Library</button>
      </div>
      {mode === 'online' ? <OnlineConnect api={api} initialUrl={onlineUrl} developmentImport={developmentImport} onConnected={onConnected} /> : (
      <form className="connect-card" onSubmit={handleSubmit}>
        <span className="connect-icon">
          <Layers size={28} />
        </span>
        <span className="eyebrow">YOUR LIBRARY. ONE BEAUTIFUL HOME.</span>
        <h1>Let’s get you connected.</h1>
        <p>Bring your existing Xtream Codes IPTV provider to Marquee.</p>

        {error && <ErrorBanner message={error} />}

        <label className="form-label">
          Provider URL
          <div className="real-input">
            <Globe size={16} />
            <input
              type="url"
              value={baseUrl}
              onChange={(e) => setBaseUrl(e.target.value)}
              placeholder="https://your-provider.com"
              autoComplete="url"
              required
              disabled={submitting}
            />
          </div>
        </label>

        <label className="form-label">
          Username
          <div className="real-input">
            <input
              type="text"
              value={username}
              onChange={(e) => setUsername(e.target.value)}
              placeholder="Your provider username"
              autoComplete="username"
              required
              disabled={submitting}
            />
          </div>
        </label>

        <label className="form-label">
          Password
          <div className="real-input">
            <input
              type="password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              placeholder="••••••••••••"
              autoComplete="current-password"
              required
              disabled={submitting}
            />
          </div>
        </label>

        <label className="remember-check">
          <input
            type="checkbox"
            checked={remember}
            onChange={(e) => setRemember(e.target.checked)}
            disabled={submitting}
          />
          <span>Remember login in this device’s secure credential store</span>
        </label>

        <button className="button primary" type="submit" disabled={submitting}>
          {submitting ? 'Connecting…' : 'Connect provider'}
          {!submitting && <ArrowRight size={16} />}
        </button>

        <p className="preview-note">
          <ShieldCheck size={13} /> Credentials stay on your device and are never saved in plain text.
        </p>
      </form>
      )}
    </div>
  );
}
