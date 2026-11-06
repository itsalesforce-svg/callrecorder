'use client';

import { useStore } from '@/lib/store';
import { proxyImage } from '@/lib/image';
import { formatPrice } from '@/lib/format';

export function ProductCard() {
  const { product, setLoading, setError, setAd, setStep, patchProduct } = useStore();

  if (!product) return null;

  const allImages = [product.image, ...product.images].filter(Boolean);

  const handleGenerate = async () => {
    setLoading(true, 'Writing your ad…');
    setError(null);
    try {
      const response = await fetch('/api/generate-ad', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ product }),
      });
      const data = await response.json();
      if (!response.ok) throw new Error(data?.error || 'Could not write the ad copy.');
      setAd(data.ad);
      setStep('script');
    } catch (error) {
      setError(error instanceof Error ? error.message : 'Something went wrong.');
    } finally {
      setLoading(false);
    }
  };

  return (
    <section className="card animate-fade-up overflow-hidden">
      <div className="grid gap-6 p-6 sm:p-8 md:grid-cols-[minmax(0,320px)_minmax(0,1fr)]">
        <div className="space-y-3">
          <div className="aspect-square overflow-hidden rounded-xl border border-line bg-white/5">
            {/* eslint-disable-next-line @next/next/no-img-element */}
            <img
              src={proxyImage(product.image)}
              alt={product.title}
              className="h-full w-full object-contain p-4"
            />
          </div>
          {allImages.length > 1 && (
            <div className="grid grid-cols-4 gap-2">
              {allImages.slice(0, 4).map((img) => (
                <button
                  key={img}
                  type="button"
                  onClick={() => patchProduct({ image: img, images: allImages.filter((i) => i !== img) })}
                  className="aspect-square overflow-hidden rounded-lg border border-line bg-white/5 transition hover:border-brand/60"
                  title="Use as main image"
                >
                  {/* eslint-disable-next-line @next/next/no-img-element */}
                  <img src={proxyImage(img)} alt="" className="h-full w-full object-contain p-1" />
                </button>
              ))}
            </div>
          )}
        </div>

        <div className="space-y-4">
          <div>
            <p className="text-xs uppercase tracking-[0.18em] text-muted">
              {product.brand || product.siteName || 'Product'}
            </p>
            <h2 className="mt-2 text-2xl font-semibold leading-snug">{product.title}</h2>
            {product.price && (
              <p className="mt-2 text-lg font-medium text-accent">{formatPrice(product.price, product.currency)}</p>
            )}
          </div>

          {product.description && (
            <p className="max-h-32 overflow-y-auto text-sm leading-relaxed text-white/70 scrollbar-slim">
              {product.description}
            </p>
          )}

          {product.features.length > 0 && (
            <div>
              <p className="text-xs uppercase tracking-wider text-muted">Key features</p>
              <ul className="mt-2 space-y-1.5 text-sm text-white/75">
                {product.features.slice(0, 6).map((feature) => (
                  <li key={feature} className="flex gap-2">
                    <span className="text-accent">✓</span>
                    <span>{feature}</span>
                  </li>
                ))}
              </ul>
            </div>
          )}

          <div className="flex flex-wrap gap-3 pt-2">
            <button type="button" onClick={handleGenerate} className="btn btn-primary">
              Write ad copy →
            </button>
            <button type="button" onClick={() => setStep('url')} className="btn btn-ghost">
              Use another URL
            </button>
            <a
              href={product.url}
              target="_blank"
              rel="noreferrer noopener"
              className="btn btn-ghost !text-white/60"
            >
              Open page ↗
            </a>
          </div>
        </div>
      </div>
    </section>
  );
}
