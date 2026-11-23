import { useState } from 'react';
import { Globe, ShieldCheck } from 'lucide-react';
import { extractAppError, type Bootstrap, type DesktopApi } from '../api/types';
import { ErrorBanner } from '../components/StatusView';

export default function OnlineConnect({ api, initialUrl, developmentImport, onConnected }: {
  api: DesktopApi;
  initialUrl?: string | null;
  developmentImport?: boolean;
  onConnected: (bootstrap: Bootstrap) => void;
}) {
  const [baseUrl, setBaseUrl] = useState(initialUrl ?? '');
  const [clientId, setClientId] = useState('');
  const [clientSecret, setClientSecret] = useState('');
  const [remember, setRemember] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function connect(importDevelopment = false) {
    if (busy) return;
    const secret = clientSecret;
    setClientSecret('');
    setError(null);
    setBusy(true);
    try {
      const result = importDevelopment
        ? await api.onlineImportDevelopment?.({ baseUrl: baseUrl.trim(), remember })
        : await api.onlineConnect?.({ baseUrl: baseUrl.trim(), clientId: clientId.trim(), clientSecret: secret, remember });
      if (!result) throw new Error('Online connection is unavailable.');
      onConnected(result);
    } catch (err) {
      setError(extractAppError(err, 'Unable to connect to the online library.').message);
    } finally {
      setBusy(false);
    }
  }

  return (
    <form className="connect-card" onSubmit={(event) => { event.preventDefault(); void connect(); }}>
      <span className="connect-icon"><Globe size={28} /></span>
      <span className="eyebrow">MORE STORIES TO DISCOVER</span>
      <h1>Connect your online library.</h1>
      <p>Search movies and series through your resolver. No IPTV account needed.</p>
      {error && <ErrorBanner message={error} />}
      <label className="form-label">Resolver URL<div className="real-input">
        <input type="url" value={baseUrl} onChange={(e) => setBaseUrl(e.target.value)} placeholder="https://your-resolver.com" required disabled={busy} />
      </div></label>
      <label className="form-label">Client ID<div className="real-input">
        <input value={clientId} onChange={(e) => setClientId(e.target.value)} autoComplete="off" required disabled={busy} />
      </div></label>
      <label className="form-label">Client secret<div className="real-input">
        <input type="password" value={clientSecret} onChange={(e) => setClientSecret(e.target.value)} autoComplete="off" required disabled={busy} />
      </div></label>
      <label className="remember-check">
        <input type="checkbox" checked={remember} onChange={(e) => setRemember(e.target.checked)} disabled={busy} />
        <span>Remember login in this device’s secure credential store</span>
      </label>
      <button className="button primary" type="submit" disabled={busy}>{busy ? 'Connecting…' : 'Connect online library'}</button>
      {developmentImport && api.onlineImportDevelopment && (
        <button className="button secondary" type="button" disabled={busy || !baseUrl.trim()} onClick={() => void connect(true)}>Connect using local development credentials</button>
      )}
      <p className="preview-note"><ShieldCheck size={13} /> Your credentials stay on this device.</p>
    </form>
  );
}
