import { defineConfig } from 'vite';

/**
 * Builds into `dist`, which `sync/wrangler.jsonc` serves as the Worker's assets — so the app and
 * its API are one origin. No CORS to configure, and no second hostname for Iranian DNS to lose.
 *
 * No framework. The plan named Svelte for bundle size, and then the whole surface turned out to
 * be a list, a form and a pairing screen — about two hundred lines that render themselves. A
 * framework here would be a dependency bought for nothing, so this is plain TypeScript and the
 * deviation is deliberate. Reach for one the first time this needs real component state.
 */
export default defineConfig({
  build: {
    target: 'es2022',
    // The whole app is smaller than one chunk boundary would be worth.
    rollupOptions: { output: { manualChunks: undefined } },
  },
});
