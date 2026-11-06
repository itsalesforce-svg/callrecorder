'use client';

import { useCallback, useEffect, useRef, useState } from 'react';
import type { AdCopy, AspectRatio, Product } from '@/lib/types';
import { RATIOS, sanitizeFilename } from '@/lib/format';
import { drawFrame, imageUrls, loadImages, totalDurationMs } from '@/lib/render-frame';

export type ExportPhase = 'idle' | 'preparing' | 'recording' | 'encoding' | 'done' | 'error';

export interface ExportState {
  phase: ExportPhase;
  progress: number;
  message: string;
  error: string | null;
}

const initialExport: ExportState = { phase: 'idle', progress: 0, message: '', error: null };
const FPS = 30;

function pickMimeType(): string | null {
  if (typeof window === 'undefined' || typeof MediaRecorder === 'undefined') return null;
  const candidates = [
    'video/mp4;codecs=avc1.42E01E',
    'video/mp4;codecs=avc1',
    'video/mp4',
    'video/webm;codecs=vp9',
    'video/webm;codecs=vp8',
    'video/webm',
  ];
  return candidates.find((type) => MediaRecorder.isTypeSupported(type)) ?? null;
}

function extensionFor(mime: string): string {
  return mime.includes('mp4') ? 'mp4' : 'webm';
}

/**
 * Records the ad entirely in the browser: each frame is painted with the same
 * canvas renderer used by the preview, so the exported file matches the preview.
 */
export function useExport({
  ad,
  product,
  aspect,
}: {
  ad: AdCopy | null;
  product: Product | null;
  aspect: AspectRatio;
}) {
  const [state, setState] = useState<ExportState>(initialExport);
  const [previewUrl, setPreviewUrl] = useState<string | null>(null);
  const [downloaded, setDownloaded] = useState(false);
  const [mime, setMime] = useState<string | null>(null);
  const [liveFrame, setLiveFrame] = useState(0);

  const runIdRef = useRef(0);
  const liveCanvasRef = useRef<HTMLCanvasElement | null>(null);

  useEffect(() => {
    setMime(pickMimeType());
  }, []);

  useEffect(() => {
    setState(initialExport);
    setDownloaded(false);
    setPreviewUrl((old) => {
      if (old) URL.revokeObjectURL(old);
      return null;
    });
  }, [ad, aspect]);

  // Paint a representative frame into the on-screen canvas so the user can see
  // exactly what is being captured.
  useEffect(() => {
    if (!ad || !product) return;
    const canvas = liveCanvasRef.current;
    if (!canvas) return;
    const { width, height } = RATIOS[aspect];
    canvas.width = width;
    canvas.height = height;
    const ctx = canvas.getContext('2d');
    if (!ctx) return;
    let alive = true;
    loadImages(imageUrls(product)).then((images) => {
      if (!alive) return;
      drawFrame(ctx, {
        ad,
        product,
        aspect,
        elapsedMs: state.phase === 'recording' ? liveFrame : 900,
        images,
      });
    });
    return () => {
      alive = false;
    };
  }, [ad, product, aspect, liveFrame, state.phase]);

  const start = useCallback(async () => {
    if (!ad || !product) return;

    const mimeType = pickMimeType();
    if (!mimeType) {
      setState({
        phase: 'error',
        progress: 0,
        message: '',
        error: 'This browser cannot record canvas video. Try Chrome, Edge or Safari on desktop.',
      });
      return;
    }

    const runId = ++runIdRef.current;
    const { width, height } = RATIOS[aspect];
    const duration = totalDurationMs(ad);
    const totalFrames = Math.round((duration / 1000) * FPS);
    const extension = extensionFor(mimeType);

    try {
      setState({ phase: 'preparing', progress: 3, message: 'Loading product images…', error: null });
      try {
        await (document as any).fonts?.ready;
      } catch {
        /* non-fatal */
      }

      const images = await loadImages(imageUrls(product));
      if (runId !== runIdRef.current) return;

      const canvas = document.createElement('canvas');
      canvas.width = width;
      canvas.height = height;
      const ctx = canvas.getContext('2d', { alpha: false });
      if (!ctx) throw new Error('Canvas 2D is unavailable in this browser.');

      // Prime the first frame before the recorder starts so the video does not
      // open on a blank canvas.
      drawFrame(ctx, { ad, product, aspect, elapsedMs: 0, images });

      const stream = canvas.captureStream(FPS);
      const recorder = new MediaRecorder(stream, {
        mimeType,
        videoBitsPerSecond: width * height > 1_200_000 ? 8_000_000 : 5_000_000,
      });
      const chunks: Blob[] = [];
      recorder.ondataavailable = (event) => {
        if (event.data && event.data.size > 0) chunks.push(event.data);
      };

      const finished = new Promise<Blob>((resolve, reject) => {
        recorder.onstop = () => resolve(new Blob(chunks, { type: mimeType }));
        recorder.onerror = () => reject(new Error('The recorder stopped unexpectedly.'));
      });

      recorder.start(100);
      const startedAt = performance.now();
      const frameMs = 1000 / FPS;

      for (let frame = 0; frame < totalFrames; frame += 1) {
        if (runId !== runIdRef.current) {
          recorder.stop();
          return;
        }

        drawFrame(ctx, { ad, product, aspect, elapsedMs: frame * frameMs, images });

        if (frame % 4 === 0) {
          setLiveFrame(frame * frameMs);
          setState({
            phase: 'recording',
            progress: 8 + Math.round((frame / totalFrames) * 80),
            message: `Recording frame ${frame + 1} of ${totalFrames}`,
            error: null,
          });
        }

        // Pace frames to real time so the recorded file plays at the right speed.
        const target = startedAt + (frame + 1) * frameMs;
        const wait = target - performance.now();
        if (wait > 0) await new Promise((resolve) => setTimeout(resolve, wait));
      }

      setState({ phase: 'encoding', progress: 91, message: 'Encoding video…', error: null });
      await new Promise((resolve) => setTimeout(resolve, 400));
      recorder.stop();
      stream.getTracks().forEach((track) => track.stop());

      const blob = await finished;
      if (runId !== runIdRef.current) return;
      if (!blob.size) throw new Error('The browser produced an empty recording. Try a shorter ad or another browser.');

      const url = URL.createObjectURL(blob);
      setPreviewUrl((old) => {
        if (old) URL.revokeObjectURL(old);
        return url;
      });

      const filename = `${sanitizeFilename(product.title)}-${aspect.replace(':', 'x')}.${extension}`;
      const link = document.createElement('a');
      link.href = url;
      link.download = filename;
      document.body.appendChild(link);
      link.click();
      link.remove();

      setDownloaded(true);
      setState({ phase: 'done', progress: 100, message: `Saved ${filename}`, error: null });
    } catch (error) {
      console.error('export failed', error);
      setState({
        phase: 'error',
        progress: 0,
        message: '',
        error: error instanceof Error ? error.message : 'Export failed.',
      });
    }
  }, [ad, product, aspect]);

  const download = useCallback(() => {
    if (!previewUrl || !product || !mime) return;
    const link = document.createElement('a');
    link.href = previewUrl;
    link.download = `${sanitizeFilename(product.title)}-${aspect.replace(':', 'x')}.${extensionFor(mime)}`;
    document.body.appendChild(link);
    link.click();
    link.remove();
  }, [previewUrl, product, aspect, mime]);

  const cancel = useCallback(() => {
    runIdRef.current += 1;
    setState(initialExport);
  }, []);

  return { state, previewUrl, start, cancel, download, downloaded, mime, liveCanvasRef };
}
