'use client';

import { useStore } from '@/lib/store';
import { Stepper } from '@/components/Stepper';
import { UrlForm } from '@/components/UrlForm';
import { ProductCard } from '@/components/ProductCard';
import { ScriptPanel } from '@/components/ScriptPanel';
import { ExportPanel } from '@/components/ExportPanel';

const HOW_IT_WORKS = [
  { title: 'Paste a URL', body: 'Any product page: Shopify, WooCommerce, Amazon, your own store.' },
  { title: 'We read the page', body: 'Title, price, features and every gallery image via JSON-LD, Open Graph and microdata.' },
  { title: 'Copy gets written', body: 'Hook, benefit scenes and a CTA — by OpenAI if a key is set, otherwise the built-in engine.' },
  { title: 'Export the ad', body: 'The animated composition is recorded in your browser at 1080p.' },
];

export default function Home() {
  const { step, isLoading, loadingMessage, error, setError, reset } = useStore();

  return (
    <main className="mx-auto w-full max-w-6xl px-4 pb-20 pt-10 sm:px-6">
      <header className="flex flex-wrap items-center justify-between gap-4">
        <div className="flex items-center gap-3">
          <div className="flex h-10 w-10 items-center justify-center rounded-xl bg-gradient-to-br from-brand to-accent text-lg font-black text-black">
            ⚡
          </div>
          <div>
            <p className="text-sm font-semibold tracking-tight">url-to-ad-gen</p>
            <p className="text-[11px] text-muted">URL → ad copy → video ad</p>
          </div>
        </div>
        <Stepper />
        <button type="button" onClick={reset} className="btn btn-ghost !py-1.5 text-xs">
          Start over
        </button>
      </header>

      <section className="mt-10 max-w-3xl">
        <h1 className="text-4xl font-extrabold leading-tight tracking-tight sm:text-5xl">
          Turn any product link into a{' '}
          <span className="bg-gradient-to-r from-brand-soft to-accent bg-clip-text text-transparent">scroll-stopping ad</span>.
        </h1>
        <p className="mt-4 max-w-2xl text-base text-white/65">
          Paste a product URL. Get the product data, the ad copy and an animated video ad you can post today — all
          without leaving the browser.
        </p>
      </section>

      {error && (
        <div className="mt-6 flex items-start justify-between gap-4 rounded-xl border border-red-500/40 bg-red-500/10 px-4 py-3 text-sm text-red-200">
          <span>{error}</span>
          <button type="button" className="text-xs text-red-300/80 hover:text-white" onClick={() => setError(null)}>
            Dismiss
          </button>
        </div>
      )}

      <div className="mt-8">
        {isLoading ? (
          <div className="card flex flex-col items-center gap-4 p-12 text-center">
            <div className="h-10 w-10 animate-spin rounded-full border-2 border-white/15 border-t-brand" />
            <p className="text-sm text-white/70">{loadingMessage || 'Working…'}</p>
            <div className="h-1.5 w-56 overflow-hidden rounded-full bg-white/10">
              <div className="h-full w-1/3 animate-[pulse_1.2s_ease-in-out_infinite] rounded-full bg-brand" />
            </div>
          </div>
        ) : (
          <div className="space-y-6">
            {step === 'url' && <UrlForm />}
            {step === 'product' && <ProductCard />}
            {step === 'script' && <ScriptPanel />}
            {step === 'video' && <ExportPanel />}
          </div>
        )}
      </div>

      <section className="mt-14 grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
        {HOW_IT_WORKS.map((item, index) => (
          <div key={item.title} className="card p-5">
            <p className="text-xs font-semibold text-brand-soft">0{index + 1}</p>
            <p className="mt-2 text-sm font-semibold">{item.title}</p>
            <p className="mt-1 text-xs leading-relaxed text-muted">{item.body}</p>
          </div>
        ))}
      </section>

      <footer className="mt-12 border-t border-line pt-6 text-xs text-muted">
        <p>
          An independent, re-implemented clone inspired by{' '}
          <a
            className="text-brand-soft hover:underline"
            href="https://github.com/Rakshath66/adgen"
            target="_blank"
            rel="noreferrer noopener"
          >
            Rakshath66/adgen
          </a>{' '}
          (MIT). No Puppeteer or server renderer required; optional OpenAI key enables LLM copy.
        </p>
      </footer>
    </main>
  );
}
