'use client';

import { useEffect, useMemo, useRef, useState } from 'react';
import type { AdCopy, AspectRatio, Product } from '@/lib/types';
import { RATIOS } from '@/lib/format';
import { AdStage } from '@/components/AdStage';
import { totalDurationMs } from '@/lib/render-frame';

interface AdPlayerProps {
  ad: AdCopy;
  product: Product;
  aspect: AspectRatio;
  boxWidth: number;
  boxHeight: number;
  autoPlay?: boolean;
}

export function AdPlayer({ ad, product, aspect, boxWidth, boxHeight, autoPlay = true }: AdPlayerProps) {
  const { width, height } = RATIOS[aspect];
  const duration = useMemo(() => totalDurationMs(ad), [ad]);
  const [elapsed, setElapsed] = useState(0);
  const [playing, setPlaying] = useState(autoPlay);
  const rafRef = useRef<number | null>(null);
  const startRef = useRef<number>(0);
  const offsetRef = useRef<number>(0);

  const scale = Math.min(boxWidth / width, boxHeight / height);

  useEffect(() => {
    offsetRef.current = 0;
    setElapsed(0);
    setPlaying(autoPlay);
  }, [ad, aspect, autoPlay]);

  useEffect(() => {
    if (!playing) return;
    startRef.current = performance.now() - offsetRef.current;

    const tick = (now: number) => {
      const next = now - startRef.current;
      if (next >= duration) {
        setElapsed(duration);
        setPlaying(false);
        offsetRef.current = 0;
        return;
      }
      setElapsed(next);
      rafRef.current = requestAnimationFrame(tick);
    };

    rafRef.current = requestAnimationFrame(tick);
    return () => {
      if (rafRef.current) cancelAnimationFrame(rafRef.current);
    };
  }, [playing, duration]);

  const toggle = () => {
    if (elapsed >= duration) {
      offsetRef.current = 0;
      setElapsed(0);
      setPlaying(true);
      return;
    }
    offsetRef.current = elapsed;
    setPlaying((value) => !value);
  };

  return (
    <div className="flex flex-col items-center gap-4">
      <div
        className="relative overflow-hidden rounded-2xl border border-line bg-black shadow-2xl"
        style={{ width: width * scale, height: height * scale }}
      >
        <AdStage ad={ad} product={product} aspect={aspect} elapsedMs={elapsed} />
        <button
          type="button"
          onClick={toggle}
          aria-label={playing ? 'Pause preview' : 'Play preview'}
          className="absolute inset-0 flex items-center justify-center bg-black/0 opacity-0 transition hover:bg-black/25 hover:opacity-100"
        >
          <span className="flex h-16 w-16 items-center justify-center rounded-full bg-white/90 text-xl text-black">
            {playing ? '❚❚' : elapsed >= duration ? '↺' : '▶'}
          </span>
        </button>
      </div>

      <div className="flex w-full items-center gap-3" style={{ maxWidth: width * scale }}>
        <button type="button" onClick={toggle} className="btn btn-ghost !px-3 !py-1.5 text-xs">
          {playing ? 'Pause' : elapsed >= duration ? 'Replay' : 'Play'}
        </button>
        <input
          type="range"
          min={0}
          max={duration}
          step={16}
          value={elapsed}
          onChange={(event) => {
            const value = Number(event.target.value);
            offsetRef.current = value;
            setElapsed(value);
            if (playing) startRef.current = performance.now() - value;
          }}
          className="h-1 flex-1 cursor-pointer appearance-none rounded-full bg-white/15 accent-brand"
        />
        <span className="w-20 text-right text-xs tabular-nums text-muted">
          {(elapsed / 1000).toFixed(1)}s / {(duration / 1000).toFixed(1)}s
        </span>
      </div>
    </div>
  );
}
