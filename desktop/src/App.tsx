import { lazy, Suspense, useEffect, useState } from 'react';
import { isNative, nativeApi } from './api/client';
import type { Bootstrap, DesktopApi } from './api/types';
import './App.css';
import NativePlayer from './features/NativePlayer';
const PreviewApp = lazy(() => import('./PreviewApp'));
export function NativeApp({ api = nativeApi }: { api?: DesktopApi }) {
  const [state, setState] = useState<Bootstrap | null>(null);
  const [failed, setFailed] = useState(false);
  const [attempt, setAttempt] = useState(0);
  useEffect(() => {
    let active = true;
    setFailed(false);
    api.bootstrap().then(value => { if (active) setState(value); }).catch(() => { if (active) setFailed(true); });
    return () => { active = false; };
  }, [api, attempt]);
  if (state?.developmentPlayer) return <NativePlayer />;
  return <main className="native-start"><div className="native-brand">MARQUEE<span>DESKTOP</span></div>{failed ? <><h1>Unable to open your library</h1><p>Marquee could not load local state. Please try again.</p><button className="button primary" onClick={() => setAttempt(v => v + 1)}>Retry</button></> : state ? <><h1>Welcome to Marquee</h1><p>Connect your provider to start watching.</p></> : <p role="status">Opening your library…</p>}</main>;
}
export default function App() {
  return isNative() ? <NativeApp /> : <Suspense fallback={<p>Opening preview…</p>}><PreviewApp /></Suspense>;
}
