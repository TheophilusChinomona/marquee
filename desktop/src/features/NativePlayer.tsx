import { useEffect, useState } from 'react';
import { invoke } from '@tauri-apps/api/core';
import { getCurrentWindow } from '@tauri-apps/api/window';
import { Maximize, Pause, Play, Volume2 } from 'lucide-react';
interface Snapshot { paused: boolean; positionMs: number; durationMs: number; seekable: boolean; volume: number; muted: boolean; version: string }
const time = (ms: number) => `${Math.floor(ms / 60000)}:${String(Math.floor(ms / 1000) % 60).padStart(2, '0')}`;
export default function NativePlayer() {
  const [state, setState] = useState<Snapshot | null>(null);
  const [error, setError] = useState('');
  useEffect(() => {
    let active = true;
    let timer: ReturnType<typeof setTimeout>;
    async function refresh() {
      try { const next = await invoke<Snapshot>('player_snapshot'); if (active) { setState(next); setError(''); } }
      catch { if (active) setError('Unable to read playback state.'); }
      if (active) timer = setTimeout(refresh, 250);
    }
    void refresh();
    return () => { active = false; clearTimeout(timer); };
  }, []);
  async function control(action: string, value?: number | boolean) {
    try { await invoke('player_control', { command: { action, value } }); setError(''); }
    catch { setError('The player could not perform that action.'); }
  }
  return <main className="native-player">
    <header><strong>MARQUEE</strong><span>Native playback validation · Sintel trailer</span></header>
    <div className="native-video-space" aria-label="Embedded native video" />
    <footer>{error && <p role="alert">{error}</p>}<p>{state?.version || 'Opening native player…'}</p>
      <input aria-label="Seek" type="range" min="0" max={state?.durationMs || 1} value={state?.positionMs || 0} disabled={!state?.seekable} onChange={e => void control('seek', Number(e.target.value) / 1000)} />
      <div className="native-controls"><button aria-label={state?.paused ? 'Play' : 'Pause'} onClick={() => void control('pause', !state?.paused)}>{state?.paused ? <Play /> : <Pause />}</button>
      <span>{time(state?.positionMs || 0)} / {time(state?.durationMs || 0)}</span><Volume2 size={18} />
      <input aria-label="Volume" type="range" min="0" max="100" value={state?.volume || 0} onChange={e => void control('volume', Number(e.target.value))} />
      <button aria-label="Fullscreen" onClick={() => void (async () => { const w = getCurrentWindow(); await w.setFullscreen(!(await w.isFullscreen())); })()}><Maximize /></button></div>
    </footer>
  </main>;
}
