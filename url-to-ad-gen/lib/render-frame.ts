import type { AdCopy, AspectRatio, Product } from './types';
import { RATIOS, formatPrice } from './format';
import { proxyImage } from './image';

export const HOOK_MS = 1600;
export const SCENE_MS = 2600;
export const CTA_MS = 2800;

export function totalDurationMs(ad: AdCopy): number {
  return HOOK_MS + ad.scenes.length * SCENE_MS + CTA_MS;
}

const FONT_STACK =
  "ui-sans-serif, system-ui, -apple-system, 'Segoe UI', Roboto, Helvetica, Arial, sans-serif";

const clamp01 = (v: number) => Math.min(1, Math.max(0, v));
const easeOut = (v: number) => 1 - Math.pow(1 - clamp01(v), 3);
const easeInOut = (v: number) => (v < 0.5 ? 2 * v * v : 1 - Math.pow(-2 * v + 2, 2) / 2);

interface Metrics {
  pad: number;
  hookSize: number;
  sceneSize: number;
  subSize: number;
  brandSize: number;
  cardSize: number;
  cardTop: number;
  ctaSize: number;
}

function metricsFor(aspect: AspectRatio): Metrics {
  if (aspect === '9:16') {
    return { pad: 76, hookSize: 86, sceneSize: 76, subSize: 38, brandSize: 29, cardSize: 720, cardTop: 470, ctaSize: 68 };
  }
  if (aspect === '1:1') {
    return { pad: 64, hookSize: 68, sceneSize: 62, subSize: 30, brandSize: 24, cardSize: 430, cardTop: 210, ctaSize: 56 };
  }
  return { pad: 64, hookSize: 60, sceneSize: 54, subSize: 27, brandSize: 22, cardSize: 360, cardTop: 190, ctaSize: 48 };
}

export interface FrameInput {
  ad: AdCopy;
  product: Product;
  aspect: AspectRatio;
  elapsedMs: number;
  images: (HTMLImageElement | null)[];
}

export function loadImages(urls: string[]): Promise<(HTMLImageElement | null)[]> {
  return Promise.all(
    urls.map(
      (url) =>
        new Promise<HTMLImageElement | null>((resolve) => {
          if (!url) return resolve(null);
          const img = new Image();
          img.crossOrigin = 'anonymous';
          img.decoding = 'async';
          img.onload = () => resolve(img);
          img.onerror = () => resolve(null);
          img.src = proxyImage(url);
        }),
    ),
  );
}

export function imageUrls(product: Product): string[] {
  return [product.image, ...product.images].filter(Boolean);
}

function roundedRect(
  ctx: CanvasRenderingContext2D,
  x: number,
  y: number,
  w: number,
  h: number,
  r: number,
) {
  const radius = Math.min(r, w / 2, h / 2);
  ctx.beginPath();
  ctx.moveTo(x + radius, y);
  ctx.arcTo(x + w, y, x + w, y + h, radius);
  ctx.arcTo(x + w, y + h, x, y + h, radius);
  ctx.arcTo(x, y + h, x, y, radius);
  ctx.arcTo(x, y, x + w, y, radius);
  ctx.closePath();
}

function wrapText(
  ctx: CanvasRenderingContext2D,
  text: string,
  maxWidth: number,
  maxLines: number,
): string[] {
  const words = text.split(/\s+/).filter(Boolean);
  const lines: string[] = [];
  let line = '';

  for (const word of words) {
    const candidate = line ? `${line} ${word}` : word;
    if (ctx.measureText(candidate).width <= maxWidth || !line) {
      line = candidate;
    } else {
      lines.push(line);
      line = word;
      if (lines.length === maxLines) break;
    }
  }
  if (line && lines.length < maxLines) lines.push(line);

  if (lines.length === maxLines) {
    const last = lines[maxLines - 1];
    if (ctx.measureText(last).width > maxWidth || words.join(' ').length > lines.join(' ').length) {
      let trimmed = last;
      while (trimmed.length > 1 && ctx.measureText(`${trimmed}…`).width > maxWidth) {
        trimmed = trimmed.slice(0, -1);
      }
      lines[maxLines - 1] = `${trimmed.trim()}…`;
    }
  }
  return lines;
}

function drawTextBlock(
  ctx: CanvasRenderingContext2D,
  text: string,
  x: number,
  y: number,
  maxWidth: number,
  font: string,
  lineHeight: number,
  maxLines: number,
  color: string,
): number {
  ctx.font = font;
  ctx.fillStyle = color;
  ctx.textBaseline = 'top';
  const lines = wrapText(ctx, text, maxWidth, maxLines);
  lines.forEach((line, index) => ctx.fillText(line, x, y + index * lineHeight));
  return y + lines.length * lineHeight;
}

/** Returns the wrapped lines without drawing, so blocks can be laid out bottom-up. */
function layoutLines(
  ctx: CanvasRenderingContext2D,
  text: string,
  maxWidth: number,
  font: string,
  lineHeight: number,
  maxLines: number,
): { lines: string[]; height: number } {
  ctx.font = font;
  const lines = wrapText(ctx, text, maxWidth, maxLines);
  return { lines, height: lines.length * lineHeight };
}

function drawLines(
  ctx: CanvasRenderingContext2D,
  lines: string[],
  x: number,
  y: number,
  lineHeight: number,
  color: string,
) {
  ctx.fillStyle = color;
  ctx.textBaseline = 'top';
  lines.forEach((line, index) => ctx.fillText(line, x, y + index * lineHeight));
}

function drawContainedImage(
  ctx: CanvasRenderingContext2D,
  img: HTMLImageElement | null,
  boxX: number,
  boxY: number,
  boxW: number,
  boxH: number,
) {
  if (!img || !img.naturalWidth) {
    ctx.fillStyle = 'rgba(255,255,255,0.18)';
    ctx.font = `600 ${Math.round(boxW / 12)}px ${FONT_STACK}`;
    ctx.textAlign = 'center';
    ctx.textBaseline = 'middle';
    ctx.fillText('IMAGE', boxX + boxW / 2, boxY + boxH / 2);
    ctx.textAlign = 'left';
    return;
  }
  const scale = Math.min(boxW / img.naturalWidth, boxH / img.naturalHeight);
  const w = img.naturalWidth * scale;
  const h = img.naturalHeight * scale;
  ctx.drawImage(img, boxX + (boxW - w) / 2, boxY + (boxH - h) / 2, w, h);
}

/** Renders a single frame of the ad. Pure canvas, so preview == export. */
export function drawFrame(ctx: CanvasRenderingContext2D, input: FrameInput) {
  const { ad, product, aspect, elapsedMs, images } = input;
  const { width, height } = RATIOS[aspect];
  const m = metricsFor(aspect);
  const portrait = aspect === '9:16';

  const scenesStart = HOOK_MS;
  const ctaStart = scenesStart + ad.scenes.length * SCENE_MS;
  const total = ctaStart + CTA_MS;
  const t = Math.min(elapsedMs, total - 1);

  const inHook = t < scenesStart;
  const inCta = t >= ctaStart;
  const sceneIndex = inHook || inCta ? -1 : Math.min(ad.scenes.length - 1, Math.floor((t - scenesStart) / SCENE_MS));
  const scene = sceneIndex >= 0 ? ad.scenes[sceneIndex] : null;
  const sceneLocal = sceneIndex >= 0 ? (t - scenesStart - sceneIndex * SCENE_MS) / SCENE_MS : 0;

  ctx.save();
  ctx.textAlign = 'left';
  ctx.textBaseline = 'alphabetic';

  // ---------- backdrop ----------
  const bg = ctx.createLinearGradient(0, 0, width * 0.4, height);
  bg.addColorStop(0, '#1b1440');
  bg.addColorStop(0.45, '#0d0b1d');
  bg.addColorStop(1, '#06060b');
  ctx.fillStyle = bg;
  ctx.fillRect(0, 0, width, height);

  const glow = ctx.createRadialGradient(width * 0.22, height * 0.42, 0, width * 0.22, height * 0.42, width * 0.95);
  glow.addColorStop(0, 'rgba(124,92,255,0.42)');
  glow.addColorStop(0.55, 'rgba(124,92,255,0.10)');
  glow.addColorStop(1, 'rgba(124,92,255,0)');
  ctx.fillStyle = glow;
  ctx.fillRect(0, 0, width, height);

  const mint = ctx.createRadialGradient(width * 0.9, height * 0.08, 0, width * 0.9, height * 0.08, width * 0.7);
  mint.addColorStop(0, 'rgba(38,224,168,0.16)');
  mint.addColorStop(1, 'rgba(38,224,168,0)');
  ctx.fillStyle = mint;
  ctx.fillRect(0, 0, width, height);

  // ---------- top row: brand + price ----------
  const brandText = (product.brand || product.siteName || 'NEW DROP').toUpperCase();
  ctx.font = `600 ${m.brandSize}px ${FONT_STACK}`;
  if ('letterSpacing' in ctx) {
    (ctx as CanvasRenderingContext2D & { letterSpacing: string }).letterSpacing = `${m.brandSize * 0.16}px`;
  }
  ctx.fillStyle = 'rgba(255,255,255,0.66)';
  ctx.textBaseline = 'middle';
  ctx.fillText(brandText, m.pad, m.pad + m.brandSize * 0.5);
  if ('letterSpacing' in ctx) {
    (ctx as CanvasRenderingContext2D & { letterSpacing: string }).letterSpacing = '0px';
  }

  const priceLabel = formatPrice(product.price, product.currency);
  if (priceLabel) {
    ctx.font = `600 ${Math.round(m.brandSize * 1.02)}px ${FONT_STACK}`;
    const priceW = ctx.measureText(priceLabel).width + m.brandSize * 2.4;
    const priceH = m.brandSize * 2.2;
    const priceX = width - m.pad - priceW;
    roundedRect(ctx, priceX, m.pad + m.brandSize * 0.5 - priceH / 2, priceW, priceH, priceH / 2);
    ctx.fillStyle = 'rgba(38,224,168,0.16)';
    ctx.fill();
    ctx.strokeStyle = 'rgba(38,224,168,0.55)';
    ctx.lineWidth = 2;
    ctx.stroke();
    ctx.fillStyle = '#8ff5d3';
    ctx.textAlign = 'center';
    ctx.fillText(priceLabel, priceX + priceW / 2, m.pad + m.brandSize * 0.5 + 1);
    ctx.textAlign = 'left';
  }

  // ---------- product card ----------
  const cardW = m.cardSize * (aspect === '9:16' ? 0.86 : 1);
  const cardH = cardW;
  const cardX = (width - cardW) / 2;
  const cardY = m.cardTop;
  const heroPulse = inHook ? easeOut(t / 900) : 1;
  const sceneZoom = scene ? 1 + easeInOut(sceneLocal) * 0.05 : 1;

  ctx.save();
  ctx.translate(cardX + cardW / 2, cardY + cardH / 2);
  ctx.scale(0.94 + 0.06 * heroPulse + (sceneZoom - 1), 0.94 + 0.06 * heroPulse + (sceneZoom - 1));
  ctx.translate(-(cardX + cardW / 2), -(cardY + cardH / 2));
  ctx.translate(0, (1 - heroPulse) * 30);

  ctx.shadowColor = 'rgba(0,0,0,0.55)';
  ctx.shadowBlur = 70;
  ctx.shadowOffsetY = 28;
  roundedRect(ctx, cardX, cardY, cardW, cardH, 56);
  const cardGradient = ctx.createLinearGradient(cardX, cardY, cardX + cardW, cardY + cardH);
  cardGradient.addColorStop(0, 'rgba(255,255,255,0.17)');
  cardGradient.addColorStop(1, 'rgba(255,255,255,0.03)');
  ctx.fillStyle = cardGradient;
  ctx.fill();
  ctx.shadowColor = 'transparent';
  ctx.shadowBlur = 0;
  ctx.shadowOffsetY = 0;
  ctx.strokeStyle = 'rgba(255,255,255,0.16)';
  ctx.lineWidth = 2;
  ctx.stroke();

  const inset = cardW * 0.07;
  const activeImage = (() => {
    if (inHook || inCta) return images[0] ?? null;
    const idx = scene ? Math.abs(scene.imageIndex) % Math.max(images.length, 1) : 0;
    return images[idx] ?? images[0] ?? null;
  })();
  drawContainedImage(ctx, activeImage, cardX + inset, cardY + inset, cardW - inset * 2, cardH - inset * 2);
  ctx.restore();

  // ---------- copy block (laid out bottom-up so nothing collides) ----------
  const copyWidth = width - m.pad * 2;
  const copyBottom = height - m.pad - (portrait ? 76 : 54);

  if (inHook) {
    const p = easeOut(t / 750);
    ctx.save();
    ctx.globalAlpha = p;
    ctx.translate(0, (1 - p) * 26);

    const titleFont = `500 ${m.subSize}px ${FONT_STACK}`;
    const titleLayout = layoutLines(ctx, product.title, copyWidth, titleFont, m.subSize * 1.35, 2);
    const titleTop = copyBottom - titleLayout.height;

    const hookFont = `800 ${m.hookSize}px ${FONT_STACK}`;
    const hookLayout = layoutLines(ctx, ad.hook, copyWidth, hookFont, m.hookSize * 1.06, 2);
    const hookTop = titleTop - m.subSize * 1.05 - hookLayout.height;

    const labelFont = `600 ${Math.round(m.subSize * 0.74)}px ${FONT_STACK}`;
    ctx.font = labelFont;
    ctx.fillStyle = '#a58bff';
    ctx.textBaseline = 'top';
    if ('letterSpacing' in ctx) {
      (ctx as CanvasRenderingContext2D & { letterSpacing: string }).letterSpacing = `${m.subSize * 0.3}px`;
    }
    ctx.fillText('INTRODUCING', m.pad, hookTop - m.subSize * 1.7);
    if ('letterSpacing' in ctx) {
      (ctx as CanvasRenderingContext2D & { letterSpacing: string }).letterSpacing = '0px';
    }

    drawLines(ctx, hookLayout.lines, m.pad, hookTop, m.hookSize * 1.06, '#ffffff');
    drawLines(ctx, titleLayout.lines, m.pad, titleTop, m.subSize * 1.35, 'rgba(255,255,255,0.66)');
    ctx.restore();
  }

  if (scene) {
    const fadeIn = easeOut(sceneLocal / 0.16);
    const fadeOut = sceneLocal > 0.86 ? clamp01((1 - sceneLocal) / 0.14) : 1;
    ctx.save();
    ctx.globalAlpha = fadeIn * fadeOut;
    ctx.translate(0, (1 - fadeIn) * 24);

    // scene dots sit on the very bottom edge of the copy area
    const dotH = 9;
    const dotY = copyBottom - dotH;
    let dotX = m.pad;
    ad.scenes.forEach((_, index) => {
      const active = index === sceneIndex;
      const w = active ? 56 : 18;
      roundedRect(ctx, dotX, dotY, w, dotH, dotH / 2);
      ctx.fillStyle = active ? '#a58bff' : 'rgba(255,255,255,0.25)';
      ctx.fill();
      dotX += w + 12;
    });

    const subFont = `500 ${m.subSize}px ${FONT_STACK}`;
    const subLayout = scene.subline
      ? layoutLines(ctx, scene.subline, copyWidth, subFont, m.subSize * 1.32, 2)
      : { lines: [], height: 0 };
    const subTop = dotY - 34 - subLayout.height;

    const headFont = `800 ${m.sceneSize}px ${FONT_STACK}`;
    const headLayout = layoutLines(ctx, scene.headline, copyWidth, headFont, m.sceneSize * 1.08, 2);
    const headTop = subTop - (scene.subline ? m.subSize * 1.05 : 0) - headLayout.height;

    drawLines(ctx, headLayout.lines, m.pad, headTop, m.sceneSize * 1.08, '#ffffff');
    if (scene.subline) {
      drawLines(ctx, subLayout.lines, m.pad, subTop, m.subSize * 1.32, 'rgba(255,255,255,0.7)');
    }
    ctx.restore();
  }

  if (inCta) {
    const p = easeOut((t - ctaStart) / 650);
    ctx.save();
    ctx.globalAlpha = p;
    ctx.translate(0, (1 - p) * 28);

    const buttonFont = `700 ${Math.round(m.ctaSize * 0.52)}px ${FONT_STACK}`;
    ctx.font = buttonFont;
    const priceText = priceLabel ? `Shop now · ${priceLabel}` : 'Shop now';
    const buttonW = ctx.measureText(priceText).width + m.ctaSize * 1.8;
    const buttonH = m.ctaSize * 1.34;
    const buttonY = copyBottom - buttonH;

    const ctaFont = `800 ${m.ctaSize}px ${FONT_STACK}`;
    const ctaLayout = layoutLines(ctx, ad.callToAction, copyWidth, ctaFont, m.ctaSize * 1.1, 2);
    const ctaTop = buttonY - 34 - ctaLayout.height;
    drawLines(ctx, ctaLayout.lines, m.pad, ctaTop, m.ctaSize * 1.1, '#ffffff');

    const gradient = ctx.createLinearGradient(m.pad, buttonY, m.pad + buttonW, buttonY + buttonH);
    gradient.addColorStop(0, '#7c5cff');
    gradient.addColorStop(1, '#5b7cff');
    ctx.shadowColor = 'rgba(124,92,255,0.65)';
    ctx.shadowBlur = 40;
    roundedRect(ctx, m.pad, buttonY, buttonW, buttonH, buttonH / 2);
    ctx.fillStyle = gradient;
    ctx.fill();
    ctx.shadowColor = 'transparent';
    ctx.shadowBlur = 0;

    ctx.font = buttonFont;
    ctx.fillStyle = '#ffffff';
    ctx.textBaseline = 'middle';
    ctx.textAlign = 'center';
    ctx.fillText(priceText, m.pad + buttonW / 2, buttonY + buttonH / 2 + 1);

    let host = '';
    try {
      host = new URL(product.url).hostname.replace(/^www\./, '');
    } catch {
      host = '';
    }
    if (host) {
      ctx.textAlign = 'left';
      ctx.font = `500 ${Math.round(m.ctaSize * 0.34)}px ${FONT_STACK}`;
      ctx.fillStyle = 'rgba(255,255,255,0.55)';
      ctx.fillText(host, m.pad + buttonW + 24, buttonY + buttonH / 2 + 1);
    }
    ctx.restore();
  }

  // ---------- progress bar ----------
  ctx.fillStyle = 'rgba(255,255,255,0.09)';
  ctx.fillRect(0, height - 8, width, 8);
  const barWidth = clamp01(t / total) * width;
  const barGradient = ctx.createLinearGradient(0, 0, width, 0);
  barGradient.addColorStop(0, '#7c5cff');
  barGradient.addColorStop(1, '#26e0a8');
  ctx.fillStyle = barGradient;
  ctx.fillRect(0, height - 8, barWidth, 8);

  ctx.restore();
}
