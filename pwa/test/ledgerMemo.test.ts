import { readFileSync } from 'node:fs';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { IDBFactory } from 'fake-indexeddb';
import { TABLES } from '../src/model';
import { GoalKind } from '../src/goals';
import { goalRow } from './plans-fixture';

let state: typeof import('../src/state');
let derived: typeof import('../src/derived');
let ledger: typeof import('../src/ledger');
beforeEach(async () => {
  vi.resetModules();
  globalThis.indexedDB = new IDBFactory();
  state = await import('../src/state');
  derived = await import('../src/derived');
  ledger = await import('../src/ledger');
  (await import('../src/rules')).seedBuiltins(1);
});

describe('the ledger memo', () => {
  it('writes the ledger does not read leave it alone', () => {
    const before = derived.ledger();
    state.setPref('themeMode', 'DARK');
    expect(derived.ledger()).toBe(before);
    state.setPref('lastBackupAt', 5);
    expect(derived.ledger()).toBe(before);
    state.batch(() => {});
    expect(derived.ledger()).toBe(before);
    state.put('goals', goalRow({ id: 'g', nameFa: 'رستوران و کافه', targetRial: 100, kind: GoalKind.CAP, categoryId: 'cat_dining', period: 'jmonth', startsOn: 0 }));
    expect(derived.ledger()).toBe(before);
  });

  it('each kind of input rebuilds it', () => {
    let prev = derived.ledger();
    const rebuilt = () => { const now = derived.ledger(); expect(now).not.toBe(prev); prev = now; };
    state.put('categories', state.rows('categories')[0]); rebuilt();
    expect(state.rows('rules').length).toBeGreaterThan(0);
    state.put('rules', state.rows('rules')[0]); rebuilt();
    ledger.addManualTxn(-3_000_000, 'cat_health', 'داروخانه', '', Date.now()); rebuilt();
    state.setPref('ledgerStartsOn', 1); rebuilt();
    state.setPref('disabledBanks', ['SAMAN'] as never); rebuilt();
    derived.setMineId('abcdef0123456789'); rebuilt();
  });

  it("every table's counter moves on a write", () => {
    for (const t of TABLES) {
      const v0 = state.tableVersion(t);
      state.put(t, { id: 'x' } as never);
      const v1 = state.tableVersion(t);
      expect(v1).toBeGreaterThan(v0);
      state.erase(t, 'x');
      expect(state.tableVersion(t)).toBeGreaterThan(v1);
    }
  });

  it('the list is complete', () => {
    const source = readFileSync('src/derived.ts', 'utf8');
    const read = new Set([...source.matchAll(/\brows\('(\w+)'\)/g)].map((m) => m[1]));
    expect(read).toEqual(new Set(derived.LEDGER_TABLES));
  });
});
