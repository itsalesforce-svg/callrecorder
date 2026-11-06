'use client';

import { useState } from 'react';
import { useStore } from '@/lib/store';
import { Product } from '@/lib/types';

const EXAMPLES = [
  {
    label: 'Shopify demo store',
    url: 'https://www.allbirds.com/products/mens-wool-runners',
  },
  {
    label: 'Amazon listing',
    url: 'https://www.amazon.com/dp/B0BSHF7WHW',
  },
];

export function UrlForm() {
  const [url, setUrl] = useState('');
  const { setLoading, setError, setProduct, setStep, isLoading } = useStore();

  const handleSubmit = async (event: React.FormEvent) => {
    event.preventDefault();
    if (!url.trim()) return;

    setLoading(true, 'Reading the product page…');
    setError(null);

    try {
      const response = await fetch('/api/scrape', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ url }),
      });

      const data = await response.json();
      if (!response.ok) throw new Error(data?.error || 'Failed to scrape that product page.');

      setProduct(data.product as Product);
      setStep('product');
    } catch (error) {
      setError(error instanceof Error ? error.message : 'Something went wrong.');
    } finally {
      setLoading(false);
    }
  };

  return (
    <section className="card animate-fade-up p-6 sm:p-8">
      <form onSubmit={handleSubmit} className="space-y-4">
        <label htmlFor="product-url" className="block text-sm font-medium text-white/80">
          Product URL
        </label>
        <div className="flex flex-col gap-3 sm:flex-row">
          <input
            id="product-url"
            type="url"
            required
            value={url}
            onChange={(event) => setUrl(event.target.value)}
            placeholder="https://store.com/products/your-product"
            className="field"
            disabled={isLoading}
          />
          <button type="submit" className="btn btn-primary shrink-0" disabled={isLoading}>
            {isLoading ? 'Reading…' : 'Generate ad →'}
          </button>
        </div>
        <p className="text-xs text-muted">
          Works with Shopify, WooCommerce, Amazon and most product pages. We read the page&apos;s structured data
          (JSON-LD, Open Graph and microdata) — no browser automation required.
        </p>
      </form>

      <div className="mt-5 flex flex-wrap items-center gap-2 border-t border-line pt-5">
        <span className="text-xs uppercase tracking-wider text-muted">Try</span>
        {EXAMPLES.map((example) => (
          <button
            key={example.url}
            type="button"
            onClick={() => setUrl(example.url)}
            className="rounded-full border border-line px-3 py-1 text-xs text-white/80 transition hover:border-brand/60 hover:bg-brand/10"
          >
            {example.label}
          </button>
        ))}
      </div>
    </section>
  );
}
