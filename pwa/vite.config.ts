import { defineConfig } from 'vite';
import { createHash } from 'node:crypto';
import { readFile, readdir, writeFile } from 'node:fs/promises';
import { resolve, relative } from 'node:path';
import { execSync } from 'node:child_process';

// The version the phone shows is its release tag; the browser build carries the same one.
try {
  process.env.VITE_VERSION ??= execSync('git describe --tags --abbrev=0', { stdio: ['ignore', 'pipe', 'ignore'] })
    .toString().trim().replace(/^v/, '');
} catch { /* no tags (a shallow clone): the version line falls back to the phone's placeholder */ }

export default defineConfig({
  plugins: [{
    name: 'complete-offline-shell',
    async writeBundle(options) {
      const root = resolve(options.dir ?? 'dist');
      const files: string[] = [];
      async function collect(directory: string): Promise<void> {
        for (const file of await readdir(directory, { withFileTypes: true })) {
          const path = resolve(directory, file.name);
          if (file.isDirectory()) await collect(path);
          else if (file.name !== 'sw.js') files.push(path);
        }
      }
      await collect(root); files.sort();
      const hash = createHash('sha256');
      const template = await readFile(new URL('./sw-template.js', import.meta.url), 'utf8');
      hash.update(template);
      for (const file of files) hash.update(relative(root, file)).update(await readFile(file));
      const paths = files.map((file) => `/${relative(root, file).replaceAll('\\', '/')}`);
      paths.push('/');
      const worker = template.replace('__SHELL_VERSION__', `muchtoman-shell-${hash.digest('hex').slice(0, 20)}`)
        .replace('__PRECACHE__', JSON.stringify(paths));
      await writeFile(resolve(root, 'sw.js'), worker);
    },
  }],
  // Preact's automatic runtime: TSX compiles to its `jsx()` with no React anywhere in the bundle.
  oxc: { jsx: { runtime: 'automatic', importSource: 'preact' } },
  build: { target: 'es2022', rollupOptions: { output: { manualChunks: undefined } } },
  // `SYNC_PROXY=https://sync.muchtoman.com vite preview`: this build, joined to a real household.
  // A proxy rather than a cross-origin base, because the page's CSP is connect-src 'self'.
  preview: process.env.SYNC_PROXY ? { proxy: { '/v1': { target: process.env.SYNC_PROXY, changeOrigin: true } } } : {},
  // The dev server stands in for the sync Worker's public proxies, so prices and wallet balances
  // are real while working on screens. The household API is never proxied here.
  server: {
    proxy: Object.fromEntries(['/rates', '/wallet-balance', '/coin-icon'].map((path) =>
      [path, { target: 'https://rates.muchtoman.com', changeOrigin: true }])),
  },
});
