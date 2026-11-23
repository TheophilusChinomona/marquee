import { useEffect, useState } from 'react';
import { invoke } from '@tauri-apps/api/core';
import { getCurrentWindow } from '@tauri-apps/api/window';
import { Maximize, Pause, Play, Volume2 } from 'lucide-react';
import type { PlayerSnapshot } from '../api/types';

const time = (ms: number) =>
  `${Math.floor(ms / 60000)}:${String(Math.floor(ms / 1000) % 60).padStart(2, '0')}`;

export default function NativePlayer() {
  const [state, setState] = useState<PlayerSnapshot | null>(null);
  const [error, setError] = useState('');

  useEffect(() => {
    let active = true;
    let timer: ReturnType<typeof setTimeout>;
    async function refresh() {
      try {
        const next = await invoke<PlayerSnapshot>('player_snapshot');
        if (active) {
          setState(next);
          setError('');
        }
      } catch {
        if (active) setError('Unable to read playback state.');
      }
      if (active) timer = setTimeout(refresh, 250);
    }
    void refresh();
    return () => {
      active = false;
      clearTimeout(timer);
    };
  }, []);

  async function control(command: Record<string, unknown>) {
    try {
      const next = await invoke<PlayerSnapshot>('player_control', { command });
      setState(next);
      setError('');
    } catch {
      setError('The player could not perform that action.');
    }
  }

  const paused = state?.status === 'paused';

  return (
    <main className="native-player">
      <header>
        <strong>MARQUEE</strong>
        <span>Native playback validation · Embedded libmpv</span>
      </header>
      <div className="native-video-space" aria-label="Embedded native video" />
      <footer>
        {error && <p role="alert">{error}</p>}
        <p>{state ? `Status: ${state.status}` : 'Opening native player…'}</p>
        <input
          aria-label="Seek"
          type="range"
          min="0"
          max={state?.durationMs || 1}
          value={state?.positionMs || 0}
          disabled={!state?.seekable}
          onChange={(e) =>
            void control({ action: 'seekAbsoluteMs', value: Number(e.target.value) })
          }
        />
        <div className="native-controls">
          <button
            type="button"
            aria-label={paused ? 'Play' : 'Pause'}
            onClick={() => void control({ action: paused ? 'resume' : 'pause' })}
          >
            {paused ? <Play /> : <Pause />}
          </button>
          <span>
            {time(state?.positionMs || 0)} / {time(state?.durationMs || 0)}
          </span>
          <Volume2 size={18} />
          <input
            aria-label="Volume"
            type="range"
            min="0"
            max="100"
            value={state?.volume || 0}
            onChange={(e) => void control({ action: 'volume', value: Number(e.target.value) })}
          />
          <button
            type="button"
            aria-label="Fullscreen"
            onClick={() =>
              void (async () => {
                const w = getCurrentWindow();
                await w.setFullscreen(!(await w.isFullscreen()));
              })()
            }
          >
            <Maximize />
          </button>
        </div>
      </footer>
    </main>
  );
}
