import 'fake-indexeddb/auto';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { IDBFactory } from 'fake-indexeddb';

const closed = { open() { throw new Error('closed'); } } as never;
beforeEach(() => { vi.resetModules(); globalThis.indexedDB = new IDBFactory(); });

describe('startup and write failures', () => {
  it('a failed load is not remembered', async () => {
    globalThis.indexedDB = closed;
    const { ensureSeeded } = await import('../src/rules');
    await expect(ensureSeeded()).rejects.toThrow();
    globalThis.indexedDB = new IDBFactory();
    await expect(ensureSeeded()).resolves.toBeUndefined();
    const { rows } = await import('../src/state');
    expect(rows('categories').length).toBeGreaterThan(0);
  });

  it('a write that cannot land is reported until it does', async () => {
    const { ensureSeeded } = await import('../src/rules');
    const { reopen } = await import('../src/db');
    const { setPref, settled, unsaved } = await import('../src/state');
    await ensureSeeded();
    reopen();
    globalThis.indexedDB = closed;
    setPref('name', 'x' as never);
    await settled();
    expect(unsaved()).toBe(true);
    globalThis.indexedDB = new IDBFactory();
    reopen();
    setPref('name', 'y' as never);
    await settled();
    expect(unsaved()).toBe(false);
  });
});
