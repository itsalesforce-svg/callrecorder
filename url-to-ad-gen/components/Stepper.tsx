'use client';

import { useStore } from '@/lib/store';
import { cn } from '@/lib/cn';

const STEPS = [
  { id: 'url', label: 'Product URL' },
  { id: 'product', label: 'Product data' },
  { id: 'script', label: 'Ad copy' },
  { id: 'video', label: 'Video' },
] as const;

export function Stepper() {
  const step = useStore((s) => s.step);
  const setStep = useStore((s) => s.setStep);
  const product = useStore((s) => s.product);
  const ad = useStore((s) => s.ad);
  const current = STEPS.findIndex((s) => s.id === step);

  const canVisit = (id: string) => {
    if (id === 'url') return true;
    if (id === 'product') return Boolean(product);
    if (id === 'script') return Boolean(product && ad);
    return Boolean(product && ad);
  };

  return (
    <ol className="flex items-center gap-1.5 text-xs sm:text-sm">
      {STEPS.map((s, index) => {
        const done = index < current;
        const active = index === current;
        const enabled = canVisit(s.id);
        return (
          <li key={s.id} className="flex items-center gap-1.5">
            <button
              type="button"
              disabled={!enabled}
              onClick={() => enabled && setStep(s.id)}
              className={cn(
                'flex items-center gap-2 rounded-full border px-3 py-1.5 transition',
                active && 'border-brand/60 bg-brand/15 text-white',
                done && 'border-accent/40 bg-accent/10 text-accent',
                !active && !done && 'border-line text-muted',
                !enabled && 'cursor-not-allowed opacity-45',
              )}
            >
              <span
                className={cn(
                  'flex h-5 w-5 items-center justify-center rounded-full text-[11px] font-bold',
                  active ? 'bg-brand text-white' : done ? 'bg-accent text-black' : 'bg-white/10',
                )}
              >
                {done ? '✓' : index + 1}
              </span>
              <span className="hidden sm:inline">{s.label}</span>
            </button>
            {index < STEPS.length - 1 && <span className="h-px w-4 bg-line sm:w-8" />}
          </li>
        );
      })}
    </ol>
  );
}
