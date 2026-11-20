import 'fake-indexeddb/auto';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { IDBFactory } from 'fake-indexeddb';
import { fromBase64Url } from '../src/crypto';

const create = vi.fn();
const get = vi.fn();
let lock: typeof import('../src/lock');
let state: typeof import('../src/state');

beforeEach(async () => {
  vi.resetModules();
  globalThis.indexedDB = new IDBFactory();
  create.mockReset().mockResolvedValue({ rawId: new Uint8Array([1, 2, 3]).buffer });
  get.mockReset();
  vi.stubGlobal('location', { hostname: 'example.test' });
  vi.stubGlobal('navigator', { credentials: { create, get } });
  // Without it nothingToCheck concludes the device has no lock and turns it off after one refusal.
  vi.stubGlobal('PublicKeyCredential', { isUserVerifyingPlatformAuthenticatorAvailable: async () => true });
  state = await import('../src/state');
  lock = await import('../src/lock');
});
afterEach(() => vi.unstubAllGlobals());

const handle = (call: number): Uint8Array => new Uint8Array(create.mock.calls[call][0].publicKey.user.id);

describe('the lock', () => {
  it('every enrolment of one browser uses the same handle', async () => {
    await lock.enableLock();
    lock.disableLock();
    await lock.enableLock();
    expect(handle(0)).toHaveLength(16);
    expect(handle(1)).toEqual(handle(0));
    expect(handle(0)).toEqual(fromBase64Url(state.pref('lockUser')));
  });

  it('the handle never rides a backup', async () => {
    await lock.enableLock();
    const { prefs } = await (await import('../src/backup')).browserPayload();
    for (const key of ['lockUser', 'lockCredential', 'lockEnabled']) expect(prefs).not.toHaveProperty(key);
  });

  it('a single refused tap asks for the same passkey again', async () => {
    await lock.enableLock();
    get.mockRejectedValue(new DOMException('no', 'NotAllowedError'));
    create.mockClear();
    await lock.unlock(true);
    await lock.unlock(true);
    expect(get).toHaveBeenCalledTimes(2);
    expect(create).not.toHaveBeenCalled();
    await lock.unlock(true);
    expect(create).toHaveBeenCalledTimes(1);
  });
});
