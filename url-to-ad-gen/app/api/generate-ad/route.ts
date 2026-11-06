import { NextResponse } from 'next/server';
import { buildAiAd, buildTemplateAd } from '@/lib/ad-copy';
import type { Product } from '@/lib/types';

export const runtime = 'nodejs';
export const dynamic = 'force-dynamic';

export async function POST(request: Request) {
  let body: { product?: Product; aspect?: string; tone?: 'punchy' | 'premium' };
  try {
    body = await request.json();
  } catch {
    return NextResponse.json({ error: 'Invalid JSON body' }, { status: 400 });
  }

  const product = body?.product;
  if (!product?.title) {
    return NextResponse.json({ error: 'Product data is required.' }, { status: 400 });
  }

  const normalized: Product = {
    ...product,
    features: Array.isArray(product.features) ? product.features : [],
    images: Array.isArray(product.images) ? product.images : [],
  };

  const aiAd = await buildAiAd(normalized, body.aspect || '9:16');
  const ad = aiAd ?? buildTemplateAd(normalized, body.tone ?? 'punchy');

  return NextResponse.json({ ad });
}
