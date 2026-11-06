import type { AspectRatio } from './types';

export const RATIOS: Record<AspectRatio, { width: number; height: number; label: string; hint: string }> = {
  '9:16': { width: 1080, height: 1920, label: '9:16', hint: 'Reels / Shorts / TikTok' },
  '1:1': { width: 1080, height: 1080, label: '1:1', hint: 'Feed posts' },
  '16:9': { width: 1920, height: 1080, label: '16:9', hint: 'YouTube / web' },
};

export function sanitizeFilename(name: string, fallback = 'ad'): string {
  const cleaned = name
    .toLowerCase()
    .normalize('NFKD')
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-+|-+$/g, '')
    .slice(0, 60);
  return cleaned || fallback;
}

export function formatPrice(price?: string, currency?: string): string {
  if (!price) return '';
  const symbol =
    currency === 'INR' ? '₹' : currency === 'EUR' ? '€' : currency === 'GBP' ? '£' : currency === 'USD' ? '$' : '';
  return symbol ? `${symbol}${price}` : price;
}
