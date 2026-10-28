import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';
import { landingFrom } from '../src/nav';

/** MainActivity's EXTRA_OPEN_TAB, EXTRA_OPEN_DECK and EXTRA_ADD_TXN, as the address carries them. */
describe('landingFrom', () => {
  it('reads the three launcher shortcuts the manifest declares', () => {
    const manifest = JSON.parse(readFileSync('public/manifest.webmanifest', 'utf8')) as { shortcuts: Array<{ url: string }> };
    expect(manifest.shortcuts.map((s) => landingFrom(new URL(s.url, 'https://x').search))).toEqual([
      { tab: 'LEDGER', open: 'manualTxn' },
      { tab: 'LEDGER', open: 'deck' },
      { tab: 'ASSETS' },
    ]);
  });

  it('still reads a tapped notification\'s tab', () => {
    expect(landingFrom('?tab=BUDGET')).toEqual({ tab: 'BUDGET' });
  });

  it('lands nowhere on an address that asks for nothing it knows', () => {
    expect(landingFrom('')).toBeNull();
    expect(landingFrom('?tab=SETTINGS')).toBeNull();
    expect(landingFrom('?open=settings')).toBeNull();
    expect(landingFrom('?demo')).toBeNull();
  });
});
