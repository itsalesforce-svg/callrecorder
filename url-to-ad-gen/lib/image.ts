const cache = new Map<string, string>();

/**
 * Product images live on third-party CDNs that frequently block hotlinking or
 * lack CORS headers, which breaks canvas capture and video export. Routing them
 * through our own origin fixes both problems.
 */
export function proxyImage(url: string | undefined): string {
  if (!url) return '';
  if (url.startsWith('/api/image-proxy')) return url;
  if (url.startsWith('data:') || url.startsWith('blob:')) return url;

  const cached = cache.get(url);
  if (cached) return cached;

  const proxied = `/api/image-proxy?url=${encodeURIComponent(url)}`;
  cache.set(url, proxied);
  return proxied;
}
