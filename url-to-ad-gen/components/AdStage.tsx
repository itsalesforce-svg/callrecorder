'use client';

import { useEffect, useRef } from 'react';
import type { AdCopy, AspectRatio, Product } from '@/lib/types';
import { RATIOS } from '@/lib/format';
import { drawFrame, imageUrls, loadImages } from '@/lib/render-frame';

export interface AdStageProps {
  ad: AdCopy;
  product: Product;
  aspect: AspectRatio;
  /** Elapsed milliseconds since the ad started. */
  elapsedMs: number;
  className?: string;
  /** Fixed canvas element; export mode reuses the same drawing pipeline. */
  canvasRef?: React.RefObject<HTMLCanvasElement | null>;
  /** Images already decoded (the exporter loads them once for all frames). */
  images?: (HTMLImageElement | null)[];
}

const FALLBACK = 'data:image/gif;base64,R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7';

/**
 * The ad composition. Every frame is painted onto a canvas with the same
 * function used by the exporter, so what you preview is byte-for-byte what
 * gets recorded.
 */
export function AdStage({ ad, product, aspect, elapsedMs, className, canvasRef, images }: AdStageProps) {
  const localRef = useRef<HTMLCanvasElement | null>(null);
  const ref = canvasRef ?? localRef;
  const loadedRef = useRef<(HTMLImageElement | null)[]>([]);

  const { width, height } = RATIOS[aspect];

  useEffect(() => {
    if (images) {
      loadedRef.current = images;
      return;
    }
    let alive = true;
    loadImages(imageUrls(product)).then((result) => {
      if (alive) loadedRef.current = result;
    });
    return () => {
      alive = false;
    };
  }, [product, images]);

  useEffect(() => {
    const canvas = ref.current;
    if (!canvas) return;
    if (canvas.width !== width || canvas.height !== height) {
      canvas.width = width;
      canvas.height = height;
    }
    const ctx = canvas.getContext('2d');
    if (!ctx) return;
    drawFrame(ctx, { ad, product, aspect, elapsedMs, images: loadedRef.current });
  }, [ad, product, aspect, elapsedMs, width, height, ref]);

  return (
    <canvas
      ref={ref}
      width={width}
      height={height}
      className={className}
      aria-label={`Ad preview: ${ad.hook}`}
      style={{ display: 'block', maxWidth: '100%', maxHeight: '100%' }}
    >
      {/* eslint-disable-next-line @next/next/no-img-element */}
      <img src={FALLBACK} alt="" width={1} height={1} />
    </canvas>
  );
}
