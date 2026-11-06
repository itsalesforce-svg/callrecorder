'use client';

import { useEffect, useMemo, useRef, useState } from 'react';
import { useStore } from '@/lib/store';
import { useExport } from '@/components/useExport';
import { RATIOS } from '@/lib/format';
import { totalDurationMs } from '@/lib/render-frame';
import type { AspectRatio } from '@/lib/types';

const ASPECTS: AspectRatio[] = ['9:16', '1:1', '16:9'];

export function ExportPanel() {
  const { ad, product, aspect, setAspect, setStep } = useStore();
  const { state, previewUrl, start, cancel, download, downloaded, mime, liveCanvasRef } = useExport({
    ad,
    product,
    aspect,
  });

  const seconds = useMemo(() => (ad ? Math.round(totalDurationMs(ad) / 100) / 10 : 10), [ad]);
  const { width, height } = RATIOS[aspect];

  const shellRef = useRef<HTMLDivElement | null>(null);
  const [fit, setFit] = useState(0.24);

  useEffect(() => {
    const compute = () => {
      const maxWidth = Math.min(window.innerWidth - 96, 420);
      const maxHeight = Math.min(window.innerHeight - 280, 600);
      setFit(Math.max(0.1, Math.min(maxWidth / width, maxHeight / height)));
    };
    compute();
    window.addEventListener('resize', compute);
    return () => window.removeEventListener('resize', compute);
  }, [width, height]);

  if (!ad || !product) return null;

  const busy = state.phase === 'preparing' || state.phase === 'recording' || state.phase === 'encoding';
  const extension = mime?.includes('mp4') ? 'mp4' : 'webm';

  return (
    <section className="animate-fade-up grid gap-6 lg:grid-cols-[minmax(0,1fr)_minmax(0,440px)]">
      <div className="card space-y-5 p-6">
        <div>
          <h2 className="text-lg font-semibold">Export your ad</h2>
          <p className="text-xs text-muted">
            {seconds}s · {width}×{height} · 30 fps. Recording happens in your browser — nothing is uploaded.
          </p>
        </div>

        <div className="flex gap-2">
          {ASPECTS.map((option) => (
            <button
              key={option}
              type="button"
              disabled={busy}
              onClick={() => setAspect(option)}
              className={`flex-1 rounded-lg border px-2 py-2 text-xs transition disabled:opacity-50 ${
                aspect === option ? 'border-brand/70 bg-brand/15 text-white' : 'border-line text-muted hover:border-white/25'
              }`}
            >
              <span className="block font-semibold">{RATIOS[option].label}</span>
              <span className="block text-[10px] text-muted">{RATIOS[option].hint}</span>
            </button>
          ))}
        </div>

        <div className="rounded-xl border border-line bg-black/25 p-4">
          <div className="flex items-center justify-between text-xs text-muted">
            <span>
              {state.phase === 'idle' && 'Ready to record'}
              {state.phase === 'preparing' && 'Preparing'}
              {state.phase === 'recording' && 'Recording'}
              {state.phase === 'encoding' && 'Encoding'}
              {state.phase === 'done' && 'Complete'}
              {state.phase === 'error' && 'Failed'}
            </span>
            <span className="tabular-nums">{Math.round(state.progress)}%</span>
          </div>
          <div className="mt-2 h-2 overflow-hidden rounded-full bg-white/10">
            <div
              className="h-full rounded-full bg-gradient-to-r from-brand to-accent transition-[width] duration-200"
              style={{ width: `${state.progress}%` }}
            />
          </div>
          {state.message && <p className="mt-2 text-xs text-white/60">{state.message}</p>}
          {state.error && <p className="mt-2 text-xs text-red-400">{state.error}</p>}
        </div>

        <div className="flex flex-wrap gap-3">
          {!busy && state.phase !== 'done' && (
            <button type="button" className="btn btn-primary" onClick={() => void start()}>
              Record &amp; download .{extension}
            </button>
          )}
          {busy && (
            <button type="button" className="btn btn-ghost" onClick={cancel}>
              Cancel
            </button>
          )}
          {state.phase === 'done' && (
            <>
              <button type="button" className="btn btn-primary" onClick={download}>
                Download again
              </button>
              <button type="button" className="btn btn-ghost" onClick={() => void start()}>
                Re-record
              </button>
            </>
          )}
          <button type="button" className="btn btn-ghost" onClick={() => setStep('script')}>
            ← Back to copy
          </button>
        </div>

        {previewUrl && (
          <div className="space-y-2 rounded-xl border border-line bg-black/40 p-3">
            <p className="text-xs uppercase tracking-wider text-muted">Rendered file</p>
            <video src={previewUrl} controls playsInline className="w-full rounded-lg" />
            {downloaded && (
              <p className="text-xs text-accent">
                Your download started ({extension.toUpperCase()}). Use “Download again” if the browser blocked it.
              </p>
            )}
          </div>
        )}

        <div className="rounded-xl border border-line bg-black/20 p-4 text-xs leading-relaxed text-muted">
          <p className="font-medium text-white/75">How the export works</p>
          <p className="mt-1">
            Each frame is painted to an off-screen canvas with the same renderer as the preview, streamed through{' '}
            <code>canvas.captureStream()</code> and encoded by <code>MediaRecorder</code>. Chrome and Edge produce MP4,
            Firefox produces WebM. No server, no Puppeteer, no upload.
          </p>
        </div>
      </div>

      <div className="card flex flex-col items-center gap-4 p-6">
        <div>
          <h2 className="text-lg font-semibold">Frame being captured</h2>
          <p className="text-xs text-muted">Live view of the canvas at {width}×{height}.</p>
        </div>

        <div
          ref={shellRef}
          className="overflow-hidden rounded-xl border border-line bg-black"
          style={{ width: width * fit, height: height * fit }}
        >
          <canvas
            ref={liveCanvasRef}
            width={width}
            height={height}
            style={{ transform: `scale(${fit})`, transformOrigin: 'top left', display: 'block' }}
          />
        </div>

        <ul className="w-full space-y-1.5 text-xs text-muted">
          <li>• Keep this tab visible during recording for the steadiest frame rate.</li>
          <li>• Product images are proxied through this app so the canvas stays clean.</li>
          <li>• The file is named after your product, e.g. lumen-arc-desk-lamp-9x16.{extension}.</li>
        </ul>
      </div>
    </section>
  );
}
