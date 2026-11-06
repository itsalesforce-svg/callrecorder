import { NextResponse } from 'next/server';

export const runtime = 'nodejs';
export const dynamic = 'force-dynamic';

const UA =
  'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36';

export async function GET(request: Request) {
  const { searchParams } = new URL(request.url);
  const target = searchParams.get('url');

  if (!target) {
    return NextResponse.json({ error: 'url query parameter is required' }, { status: 400 });
  }

  let parsed: URL;
  try {
    parsed = new URL(target);
  } catch {
    return NextResponse.json({ error: 'Invalid image URL' }, { status: 400 });
  }
  if (!/^https?:$/.test(parsed.protocol)) {
    return NextResponse.json({ error: 'Only http(s) images are supported' }, { status: 400 });
  }

  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), 15_000);

  try {
    const upstream = await fetch(parsed, {
      signal: controller.signal,
      headers: { 'user-agent': UA, accept: 'image/*,*/*;q=0.8', referer: `${parsed.protocol}//${parsed.host}/` },
    });

    if (!upstream.ok) {
      return NextResponse.json({ error: `Image host returned ${upstream.status}` }, { status: 502 });
    }

    const contentType = upstream.headers.get('content-type') || 'image/jpeg';
    if (!contentType.startsWith('image/')) {
      return NextResponse.json({ error: 'That URL is not an image' }, { status: 415 });
    }

    const buffer = await upstream.arrayBuffer();
    return new NextResponse(buffer, {
      status: 200,
      headers: {
        'content-type': contentType,
        'cache-control': 'public, max-age=86400, immutable',
        'access-control-allow-origin': '*',
      },
    });
  } catch (error) {
    const message = error instanceof Error && error.name === 'AbortError' ? 'Image request timed out' : 'Could not fetch image';
    return NextResponse.json({ error: message }, { status: 502 });
  } finally {
    clearTimeout(timer);
  }
}
