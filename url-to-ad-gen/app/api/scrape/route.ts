import { NextResponse } from 'next/server';
import { scrapeProduct, ScrapeError } from '@/lib/scrape';

export const runtime = 'nodejs';
export const dynamic = 'force-dynamic';

export async function POST(request: Request) {
  let body: { url?: string };
  try {
    body = await request.json();
  } catch {
    return NextResponse.json({ error: 'Invalid JSON body' }, { status: 400 });
  }

  const url = body?.url?.trim();
  if (!url) {
    return NextResponse.json({ error: 'A product URL is required.' }, { status: 400 });
  }

  try {
    const product = await scrapeProduct(url);
    return NextResponse.json({ product });
  } catch (error) {
    const status = error instanceof ScrapeError ? 422 : 500;
    const message = error instanceof Error ? error.message : 'Failed to read that product page.';
    console.error('scrape failed:', message);
    return NextResponse.json({ error: message }, { status });
  }
}
