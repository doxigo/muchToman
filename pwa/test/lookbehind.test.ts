import { readFileSync, readdirSync } from 'node:fs';
import { join } from 'node:path';
import { describe, expect, it } from 'vitest';
import { parsePasted } from '../src/paste';

const ROOT = join(__dirname, '..');

/**
 * Safari had no regex lookbehind before 16.4, and a regex an engine cannot read is a syntax error
 * for the whole bundle — a white page on the iPhone 6s, 7 and first SE, which stop at iOS 15.
 * Nothing the browser loads may carry one.
 */
describe('what the oldest iPhone can read', () => {
  it('ships no regex lookbehind', () => {
    const files = [
      ...readdirSync(join(ROOT, 'src'), { recursive: true }).map(String)
        .filter((f) => /\.tsx?$/.test(f)).map((f) => join('src', f)),
      'sw-template.js', 'index.html',
    ];
    // The path is right: a wrong one would read nothing and pass.
    expect(files.length).toBeGreaterThan(30);
    const found = files.filter((f) => /\(\?<[!=]/.test(readFileSync(join(ROOT, f), 'utf8')));
    expect(found, 'a lookbehind, in code or in a comment — say it another way').toEqual([]);
  });

  it('reads a long run of digits in linear time', () => {
    // No mask and no «پیگیری»: the dashed-account and slashed-reference searches both run.
    const body = `برداشت مبلغ 500,000 ریال\n${'1'.repeat(39_000)}`;
    const started = performance.now();
    const got = parsePasted(body);
    expect(performance.now() - started).toBeLessThan(2_000);
    expect(got.mask).toBe('');
    expect(got.refNo).toBe('');
  });
});
