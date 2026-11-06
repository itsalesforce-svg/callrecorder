import * as cheerio from 'cheerio';
import type { AnyNode } from 'domhandler';
import type { Product } from './types';

const UA =
  'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) ' +
  'Chrome/124.0.0.0 Safari/537.36';

const MAX_IMAGES = 8;

class ScrapeError extends Error {}

function absolute(url: string | undefined | null, base: string): string {
  if (!url) return '';
  const cleaned = url.trim().replace(/^\/\//, 'https://');
  if (!cleaned || cleaned.startsWith('data:')) return '';
  try {
    return new URL(cleaned, base).toString();
  } catch {
    return '';
  }
}

/** Discard tracking pixels, sprites, svg data URIs and tiny thumbnails. */
function isUsableImage(url: string): boolean {
  if (!url) return false;
  if (!/^https?:\/\//i.test(url)) return false;
  const lower = url.toLowerCase();
  if (/(sprite|placeholder|spacer|pixel|1x1|blank|transparent|loader|no-image)/.test(lower)) return false;
  if (/\.(svg|gif)(\?|$)/.test(lower)) return false;
  return true;
}

/** Upgrade common CDN thumbnail patterns to a large version. */
function upscale(url: string): string {
  return url
    .replace(/_S[LR]\d+_/g, '_SL1500_') // Amazon
    .replace(/_(\d{2,3})x(\d{2,3})(?=\.(jpg|jpeg|png|webp))/i, (m, w, h) => {
      // Shopify / generic: only bump small variants up
      const width = Number(w);
      return width < 800 ? '_1200x1200' : m;
    });
}

function cleanText(input: string | undefined | null, max = 1200): string {
  if (!input) return '';
  return input
    .replace(/<[^>]*>/g, ' ')
    .replace(/&nbsp;/g, ' ')
    .replace(/\s+/g, ' ')
    .trim()
    .slice(0, max);
}

function dedupe(images: string[]): string[] {
  const seen = new Set<string>();
  const out: string[] = [];
  for (const img of images) {
    const key = img.split('?')[0].toLowerCase();
    if (seen.has(key)) continue;
    seen.add(key);
    out.push(img);
  }
  return out;
}

function firstJsonLdProduct($: cheerio.CheerioAPI): Record<string, any> | null {
  const nodes = $('script[type="application/ld+json"]').toArray();
  const candidates: Record<string, any>[] = [];

  const walk = (value: any) => {
    if (!value) return;
    if (Array.isArray(value)) {
      value.forEach(walk);
      return;
    }
    if (typeof value !== 'object') return;
    const type = value['@type'];
    const types = Array.isArray(type) ? type.map(String) : [String(type ?? '')];
    if (types.some((t) => /product/i.test(t))) candidates.push(value);
    if (value['@graph']) walk(value['@graph']);
  };

  for (const node of nodes) {
    try {
      walk(JSON.parse($(node).text()));
    } catch {
      /* ignore malformed ld+json */
    }
  }

  return candidates[0] ?? null;
}

function priceFromLd(node: Record<string, any> | null): { price?: string; currency?: string } {
  if (!node) return {};
  const offers = Array.isArray(node.offers) ? node.offers[0] : node.offers;
  const price = offers?.price ?? offers?.lowPrice ?? offers?.highPrice ?? node.price;
  if (price === undefined || price === null || price === '') return {};
  const numeric = typeof price === 'number' ? price : Number(String(price).replace(/[^0-9.]/g, ''));
  return {
    price: Number.isFinite(numeric) && numeric > 0 ? numeric.toFixed(2).replace(/\.00$/, '') : String(price),
    currency: offers?.priceCurrency || node.priceCurrency || undefined,
  };
}

function imagesFromLd(node: Record<string, any> | null, base: string): string[] {
  if (!node) return [];
  const raw = node.image ?? node.thumbnailUrl;
  const list = Array.isArray(raw) ? raw : [raw];
  return list
    .map((entry: any) => (typeof entry === 'string' ? entry : entry?.url || entry?.contentUrl))
    .map((u: string | undefined) => upscale(absolute(u, base)))
    .filter(isUsableImage);
}

function featuresFrom($: cheerio.CheerioAPI): string[] {
  const selectors = [
    '#feature-bullets li span.a-list-item',
    '#productFactsDesktopExpander li',
    '.product__description li',
    '.product-description ul li',
    '[class*="feature"] li',
    'ul li',
  ];

  for (const selector of selectors) {
    const items = $(selector)
      .toArray()
      .map((el) => cleanText($(el).text(), 220))
      .filter((t) => t.length > 18 && t.length < 220 && !/cookie|newsletter|shipping policy/i.test(t));
    const unique = Array.from(new Set(items));
    if (unique.length >= 3) return unique.slice(0, 6);
  }
  return [];
}

function meta($: cheerio.CheerioAPI, name: string): string {
  const el = $(
    `meta[property="${name}"], meta[name="${name}"], meta[itemprop="${name}"], meta[property="og:${name}"]`,
  ).first();
  return el.attr('content')?.trim() || '';
}

function text($: cheerio.CheerioAPI, selector: string): string {
  return cleanText($(selector).first().text());
}

export async function scrapeProduct(rawUrl: string): Promise<Product> {
  let url: URL;
  try {
    url = new URL(rawUrl.trim());
  } catch {
    throw new ScrapeError('That does not look like a valid URL.');
  }
  if (!/^https?:$/.test(url.protocol)) {
    throw new ScrapeError('Only http and https URLs are supported.');
  }

  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), 20_000);

  let html: string;
  try {
    const res = await fetch(url, {
      signal: controller.signal,
      redirect: 'follow',
      headers: {
        'user-agent': UA,
        accept: 'text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8',
        'accept-language': 'en-US,en;q=0.9',
      },
    });
    if (!res.ok) {
      throw new ScrapeError(
        `The store returned HTTP ${res.status}. Some sites block bots — try another product URL or use manual edit.`,
      );
    }
    html = await res.text();
  } catch (error) {
    if (error instanceof ScrapeError) throw error;
    const message = error instanceof Error && error.name === 'AbortError'
      ? 'The page took too long to respond (20s limit).'
      : 'Could not reach that page. Check the URL, or try a different store.';
    throw new ScrapeError(message);
  } finally {
    clearTimeout(timer);
  }

  const $ = cheerio.load(html);
  const ld = firstJsonLdProduct($);
  const finalUrl = url.toString();

  const title =
    cleanText(String(ld?.name ?? ''), 200) ||
    meta($, 'og:title') ||
    text($, 'h1') ||
    cleanText($('title').text(), 200) ||
    'Untitled product';

  const description =
    cleanText(String(ld?.description ?? ''), 900) ||
    meta($, 'og:description') ||
    meta($, 'description') ||
    text($, '[class*="productDescription"], [class*="product-description"], #productDescription') ||
    '';

  const ldPrice = priceFromLd(ld);
  const price =
    ldPrice.price ||
    meta($, 'product:price:amount') ||
    meta($, 'og:price:amount') ||
    text($, '[itemprop="price"]') ||
    text($, '.price--withoutTax, .price__current, .product__price, .a-price .a-offscreen') ||
    undefined;

  const currency = ldPrice.currency || meta($, 'product:price:currency') || meta($, 'og:price:currency') || undefined;

  const brand =
    cleanText(String(ld?.brand?.name ?? ld?.brand ?? ''), 80) ||
    meta($, 'og:site_name') ||
    text($, '[itemprop="brand"], .product__vendor') ||
    undefined;

  const imageCandidates: string[] = [
    ...imagesFromLd(ld, finalUrl),
    upscale(absolute(meta($, 'og:image'), finalUrl)),
    upscale(absolute(meta($, 'twitter:image'), finalUrl)),
    ...$(
      '.product-gallery img, .product__media img, [class*="product"] img, [data-product-media-type="image"] img, #altImages img, #landingImage, [itemprop="image"]',
    )
      .toArray()
      .map((el: AnyNode) => {
        const node = $(el);
        const srcset = node.attr('srcset') || node.attr('data-srcset') || '';
        const fromSrcset = srcset
          .split(',')
          .map((part) => {
            const [href, width] = part.trim().split(/\s+/);
            return { href, width: parseInt((width || '0').replace('w', ''), 10) || 0 };
          })
          .sort((a, b) => b.width - a.width)[0]?.href;
        return upscale(absolute(fromSrcset || node.attr('data-src') || node.attr('src'), finalUrl));
      }),
  ];

  const images = dedupe(imageCandidates.filter(isUsableImage)).slice(0, MAX_IMAGES);

  if (!images.length) {
    throw new ScrapeError(
      'No product images were found on that page. Try a direct product page (not a category or search page).',
    );
  }

  return {
    title: title.slice(0, 160),
    brand: brand?.slice(0, 80),
    price,
    currency,
    description,
    features: featuresFrom($),
    image: images[0],
    images: images.slice(1),
    url: finalUrl,
    siteName: meta($, 'og:site_name') || url.hostname.replace(/^www\./, ''),
  };
}

export { ScrapeError };
