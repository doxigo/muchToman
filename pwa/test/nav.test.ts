import { readFileSync } from 'node:fs';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { dismissNotice, holdNotice, landingFrom, navState, showNotice } from '../src/nav';

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

/** TransientNoticeBand's clock: six seconds, ten with an undo, and none of it while the band is held. */
describe('the notice', () => {
  afterEach(() => { dismissNotice(); vi.useRealTimers(); });

  it('stays six seconds alone and ten with an action', () => {
    vi.useFakeTimers();
    showNotice('ذخیره شد');
    vi.advanceTimersByTime(5999);
    expect(navState().notice).not.toBeNull();
    vi.advanceTimersByTime(1);
    expect(navState().notice).toBeNull();
    showNotice('تراکنش پاک شد', { label: 'برگردون', run: () => {} });
    vi.advanceTimersByTime(9999);
    expect(navState().notice).not.toBeNull();
    vi.advanceTimersByTime(1);
    expect(navState().notice).toBeNull();
  });

  it('pauses while the pointer or focus is on the band, and goes on from where it stopped', () => {
    vi.useFakeTimers();
    showNotice('تراکنش پاک شد', { label: 'برگردون', run: () => {} });
    vi.advanceTimersByTime(4000);
    holdNotice('pointer', true);
    holdNotice('focus', true);
    vi.advanceTimersByTime(60_000);
    holdNotice('pointer', false);
    // Focus is still inside: still held.
    vi.advanceTimersByTime(60_000);
    expect(navState().notice).not.toBeNull();
    holdNotice('focus', false);
    vi.advanceTimersByTime(5999);
    expect(navState().notice).not.toBeNull();
    vi.advanceTimersByTime(1);
    expect(navState().notice).toBeNull();
  });
});
