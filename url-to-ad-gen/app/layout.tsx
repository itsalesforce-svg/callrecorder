import type { Metadata } from 'next';
import './globals.css';

export const metadata: Metadata = {
  title: 'url-to-ad-gen — turn any product URL into a video ad',
  description:
    'Paste a product link from any store, get scrape-backed ad copy and an animated, ready-to-post video ad you can export straight from the browser.',
};

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="en">
      <body className="antialiased min-h-screen">{children}</body>
    </html>
  );
}
