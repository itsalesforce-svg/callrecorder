import type { AdCopy, AdScene, Product } from './types';

const HOOKS = [
  'Stop scrolling — this is the one.',
  'You did not know you needed this.',
  'This changes the everyday.',
  'The upgrade your routine asked for.',
  'Meet the {brand} everyone is talking about.',
];

const CTAS = [
  'Tap the link and grab yours today.',
  'Shop now — before it sells out again.',
  'Link in bio. Free shipping on your first order.',
  'Order today and feel the difference this week.',
  'Get yours now while the offer lasts.',
];

function sentenceCase(input: string): string {
  const trimmed = input.trim().replace(/[.!?]+$/, '');
  if (!trimmed) return '';
  return trimmed.charAt(0).toUpperCase() + trimmed.slice(1);
}

function shorten(input: string, max: number): string {
  const text = sentenceCase(input);
  if (text.length <= max) return text;
  const cut = text.slice(0, max);
  const boundary = Math.max(cut.lastIndexOf('. '), cut.lastIndexOf(', '), cut.lastIndexOf(' '));
  return `${cut.slice(0, boundary > 40 ? boundary : max).replace(/[,;:]$/, '')}`;
}

function pick<T>(list: T[], seed: number): T {
  return list[Math.abs(seed) % list.length];
}

function seedFrom(input: string): number {
  let hash = 0;
  for (let i = 0; i < input.length; i += 1) {
    hash = (hash * 31 + input.charCodeAt(i)) | 0;
  }
  return hash;
}

/**
 * Deterministic, dependency-free copywriter used when no LLM key is configured
 * (and as a fallback if the LLM call fails). Produces the same AdCopy shape.
 */
export function buildTemplateAd(product: Product, tone: 'punchy' | 'premium' = 'punchy'): AdCopy {
  const seed = seedFrom(`${product.title}|${product.url}`);
  const brand = product.brand || product.siteName || 'the brand';
  const hook = pick(HOOKS, seed).replace('{brand}', brand);
  const cta = pick(CTAS, seed >> 3);
  const priceLine = product.price ? `Only ${product.price}${product.currency ? ` ${product.currency}` : ''}.` : '';

  const benefits = (product.features.length ? product.features : [])
    .map((f) => shorten(f, 92))
    .filter(Boolean)
    .slice(0, 3);

  const fallbackBenefits = [
    shorten(product.description, 92) || 'Built to make your everyday easier.',
    'Designed for people who expect more.',
    'Quality you can feel from the first use.',
  ];

  const sceneBenefits = benefits.length ? benefits : fallbackBenefits;

  const scenes: AdScene[] = [
    {
      headline: hook,
      subline: shorten(product.title, 70),
      voiceover: `Let me show you ${shorten(product.title, 60)}.`,
      imageIndex: 0,
    },
    {
      headline: sceneBenefits[0],
      subline: tone === 'premium' ? 'Crafted with intent.' : 'Here is why it wins.',
      voiceover: `${sceneBenefits[0]}.`,
      imageIndex: 1,
    },
  ];

  if (sceneBenefits[1]) {
    scenes.push({
      headline: sceneBenefits[1],
      subline: priceLine || undefined,
      voiceover: `${sceneBenefits[1]}. ${priceLine}`.trim(),
      imageIndex: 2,
    });
  }

  if (sceneBenefits[2]) {
    scenes.push({
      headline: sceneBenefits[2],
      subline: tone === 'premium' ? 'Details that matter.' : 'No compromises.',
      voiceover: sceneBenefits[2],
      imageIndex: 3,
    });
  }

  scenes.push({
    headline: product.price ? `${product.price}${product.currency ? ` ${product.currency}` : ''}` : 'Available now',
    subline: cta,
    voiceover: `${cta}`,
    imageIndex: 0,
  });

  const hashtagSource = `${product.title} ${product.brand ?? ''} ${product.features.join(' ')}`;
  const hashtags = Array.from(
    new Set(
      ['ad', 'shorts', 'reels', ...hashtagSource.toLowerCase().match(/[a-z]{4,}/g) ?? []]
        .filter((w) => !['this', 'that', 'with', 'your', 'from', 'have', 'will', 'they', 'more', 'than', 'when', 'what'].includes(w))
        .slice(0, 6),
    ),
  ).map((tag) => `#${tag}`);

  return {
    hook,
    callToAction: cta,
    scenes,
    caption: `${hook} ${shorten(product.description, 140)} ${cta}`.replace(/\s+/g, ' ').trim(),
    hashtags,
    engine: 'template',
  };
}

function coerceAdCopy(raw: unknown, product: Product): AdCopy | null {
  if (!raw || typeof raw !== 'object') return null;
  const data = raw as Partial<AdCopy>;
  if (!Array.isArray(data.scenes) || data.scenes.length === 0) return null;

  const imageCount = 1 + (product.images?.length ?? 0);
  const scenes: AdScene[] = data.scenes
    .filter((s) => s && typeof s === 'object')
    .map((s, index) => ({
      headline: String(s.headline ?? '').trim().slice(0, 90) || 'Discover more',
      subline: s.subline ? String(s.subline).trim().slice(0, 120) : undefined,
      voiceover: String(s.voiceover ?? '').trim().slice(0, 260),
      imageIndex: Number.isFinite(s.imageIndex) ? Math.abs(Number(s.imageIndex)) % imageCount : index % imageCount,
    }))
    .slice(0, 6);

  if (!scenes.length) return null;

  return {
    hook: String(data.hook ?? scenes[0].headline).slice(0, 120),
    callToAction: String(data.callToAction ?? 'Shop now').slice(0, 120),
    scenes,
    caption: String(data.caption ?? '').slice(0, 400),
    hashtags: Array.isArray(data.hashtags)
      ? data.hashtags.map((h) => `#${String(h).replace(/^#/, '')}`).slice(0, 8)
      : [],
    engine: 'ai',
  };
}

export async function buildAiAd(product: Product, aspect: string): Promise<AdCopy | null> {
  const apiKey = process.env.OPENAI_API_KEY;
  if (!apiKey) return null;

  try {
    const { default: OpenAI } = await import('openai');
    const client = new OpenAI({ apiKey });
    const model = process.env.OPENAI_MODEL || 'gpt-4o-mini';

    const completion = await client.chat.completions.create({
      model,
      temperature: 0.8,
      response_format: { type: 'json_object' },
      messages: [
        {
          role: 'system',
          content:
            'You are a senior direct-response advertising copywriter. You return only valid JSON. ' +
            'Write short, concrete, benefit-led lines. Never invent specifications that are not in the input.',
        },
        {
          role: 'user',
          content: `Write a ${aspect} video ad for this product.

Title: ${product.title}
Brand: ${product.brand ?? 'unknown'}
Price: ${product.price ?? 'not listed'} ${product.currency ?? ''}
Description: ${product.description || 'not available'}
Features:
${product.features.map((f) => `- ${f}`).join('\n') || '- none listed'}
Images available: ${1 + product.images.length}

Return JSON exactly in this shape:
{
  "hook": "3-6 word scroll-stopper",
  "callToAction": "one punchy CTA line",
  "scenes": [
    { "headline": "<= 8 words on-screen", "subline": "<= 12 words", "voiceover": "one spoken sentence", "imageIndex": 0 }
  ],
  "caption": "social caption, max 2 sentences",
  "hashtags": ["tag1", "tag2", "tag3", "tag4"]
}

Rules: 4 to 5 scenes. Scene 1 is the hook, the last scene is the CTA. imageIndex must be an integer between 0 and ${
            product.images.length
          }. Do not use emojis inside scenes.`,
        },
      ],
    });

    const content = completion.choices[0]?.message?.content;
    if (!content) return null;
    return coerceAdCopy(JSON.parse(content), product);
  } catch (error) {
    console.error('LLM ad generation failed, falling back to template:', error);
    return null;
  }
}
