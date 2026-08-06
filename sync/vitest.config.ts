import { cloudflareTest } from '@cloudflare/vitest-pool-workers';
import { defineConfig } from 'vitest/config';

/**
 * Runs the real Worker and real Durable Objects under workerd, so the tests exercise the same
 * SQLite and the same one-request-at-a-time execution that production does. `worker/` has no
 * tests at all; this one holds people's ledgers, so it does.
 *
 * `cloudflareTest` is a Vite plugin as of vitest-pool-workers 0.20 — the older
 * `defineWorkersConfig` helper it replaced no longer exists.
 */
export default defineConfig({
  plugins: [
    cloudflareTest({
      singleWorker: true,
      wrangler: { configPath: './wrangler.jsonc' },
    }),
  ],
});
