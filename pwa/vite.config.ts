import { defineConfig } from 'vite';
import { createHash } from 'node:crypto';
import { readFile, readdir, writeFile } from 'node:fs/promises';
import { resolve, relative } from 'node:path';

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
  build: { target: 'es2022', rollupOptions: { output: { manualChunks: undefined } } },
});
