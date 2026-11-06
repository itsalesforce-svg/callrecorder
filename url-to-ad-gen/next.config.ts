import type { NextConfig } from 'next';

const nextConfig: NextConfig = {
  serverExternalPackages: ['cheerio'],
  /**
   * The sandbox / preview host is not known at build time, so allow any origin
   * to reach the dev server. Server Actions are not used, so this is safe here.
   */
  allowedDevOrigins: ['*'],
  images: {
    remotePatterns: [{ protocol: 'https', hostname: '**' }],
  },
  eslint: { ignoreDuringBuilds: true },
};

export default nextConfig;
