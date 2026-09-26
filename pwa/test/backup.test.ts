import { describe, expect, it } from 'vitest';
import {
  BACKUP_MAGIC, BackupError, gunzip, gzip, openBackup, restoredSnapshot, sealBackup,
} from '../src/backup';
import type { BrowserPayload } from '../src/backup';

// The real 600k rounds would make each case a second or two; the header carries the count, so a
// small one exercises the same path.
const ROUNDS = 1000;
const payload: BrowserPayload = {
  pwa: 1,
  prefs: { name: 'مریم', themeMode: 'DARK' },
  tables: { sources: [{ id: 'a', bank: 'MELLAT', body: 'برداشت ۱۰۰٬۰۰۰', at: 1, ingestedAt: 2 }] },
};
const fault = async (p: Promise<unknown>): Promise<string> => {
  try { await p; return 'none'; } catch (e) { return e instanceof BackupError ? e.fault : `other: ${e}`; }
};
const headerOf = (bytes: Uint8Array): string => {
  const len = new DataView(bytes.buffer, bytes.byteOffset).getUint32(6);
  return new TextDecoder().decode(bytes.slice(10, 10 + len));
};
/** An envelope with this header and junk after it — enough for every pre-decrypt check. */
function envelope(header: string | Uint8Array, tail = 32): Uint8Array<ArrayBuffer> {
  const h = typeof header === 'string' ? new TextEncoder().encode(header) : header;
  const out = new Uint8Array(10 + h.length + tail);
  out.set(new TextEncoder().encode(BACKUP_MAGIC));
  new DataView(out.buffer).setUint32(6, h.length);
  out.set(h, 10);
  return out;
}
const header = (over: Record<string, unknown> = {}) => JSON.stringify({
  formatVersion: 1, createdAt: 1, kdf: { algo: 'PBKDF2WithHmacSHA256', iterations: ROUNDS, saltB64: 'AAAAAAAAAAAAAAAAAAAAAA==' },
  cipher: { algo: 'AES/GCM/NoPadding', ivB64: 'AAAAAAAAAAAAAAAA' }, ...over,
});

describe('the .mtbak envelope', () => {
  it('round-trips, in Export.kt\'s layout and kotlinx\'s header shape', async () => {
    const sealed = await sealBackup(payload, 'secret-1', 1_750_000_000_000, 0, ROUNDS);
    expect(new TextDecoder().decode(sealed.slice(0, 6))).toBe('MTBAK1');
    expect(headerOf(sealed)).toMatch(
      /^\{"formatVersion":1,"createdAt":1750000000000,"kdf":\{"algo":"PBKDF2WithHmacSHA256","iterations":1000,"saltB64":"[A-Za-z0-9+/]{22}=="\},"cipher":\{"algo":"AES\/GCM\/NoPadding","ivB64":"[A-Za-z0-9+/]{16}"\}\}$/,
    );
    const opened = await openBackup(sealed, 'secret-1');
    expect(opened.payload).toEqual(payload);
    expect(opened.header.createdAt).toBe(1_750_000_000_000);
  });

  it('refuses a short passphrase at sealing', async () => {
    await expect(sealBackup(payload, '12345', 1, 0, ROUNDS)).rejects.toThrow();
  });

  it('says one thing for a wrong passphrase and a damaged body', async () => {
    const sealed = await sealBackup(payload, 'secret-1', 1, 0, ROUNDS);
    expect(await fault(openBackup(sealed, 'secret-2'))).toBe('WRONG_PASSPHRASE_OR_CORRUPT');
    const flipped = sealed.slice(); flipped[flipped.length - 20] ^= 1;
    expect(await fault(openBackup(flipped, 'secret-1'))).toBe('WRONG_PASSPHRASE_OR_CORRUPT');
  });

  it('authenticates the plaintext header: an edited createdAt fails like a wrong key', async () => {
    const sealed = await sealBackup(payload, 'secret-1', 1_750_000_000_000, 0, ROUNDS);
    const text = headerOf(sealed);
    const edited = sealed.slice();
    edited.set(new TextEncoder().encode(text.replace('1750000000000', '1750000000001')), 10);
    expect(await fault(openBackup(edited, 'secret-1'))).toBe('WRONG_PASSPHRASE_OR_CORRUPT');
  });

  it('judges the envelope before the passphrase', async () => {
    expect(await fault(openBackup(new TextEncoder().encode('MTBAK'), 'x'))).toBe('NOT_A_BACKUP');
    expect(await fault(openBackup(envelope(header()).map((b, i) => (i === 0 ? 0 : b)), 'x'))).toBe('NOT_A_BACKUP');
    expect(await fault(openBackup(envelope('{not json'), 'x'))).toBe('NOT_A_BACKUP');
    expect(await fault(openBackup(envelope(header({ formatVersion: 2 })), 'x'))).toBe('NEWER_FORMAT');
    expect(await fault(openBackup(envelope(header({ cipher: { algo: 'AES/CBC', ivB64: 'AAAAAAAAAAAAAAAA' } })), 'x'))).toBe('NOT_A_BACKUP');
    expect(await fault(openBackup(envelope(header(), 8), 'x'))).toBe('NOT_A_BACKUP');
  });

  it('holds the reader\'s limits: header size and KDF work', async () => {
    expect(await fault(openBackup(envelope(new Uint8Array(64 * 1024 + 1).fill(32)), 'x'))).toBe('NOT_A_BACKUP');
    const greedy = header({ kdf: { algo: 'PBKDF2WithHmacSHA256', iterations: 10_000_001, saltB64: 'AAAAAAAAAAAAAAAAAAAAAA==' } });
    expect(await fault(openBackup(envelope(greedy), 'x'))).toBe('NOT_A_BACKUP');
  });

  it('caps what a payload may inflate to', async () => {
    const packed = await gzip(new Uint8Array(4096));
    expect((await gunzip(packed, 4096)).length).toBe(4096);
    await expect(gunzip(packed, 4095)).rejects.toThrow('payload too large');
  });

  it('names a phone\'s backup instead of failing mute', async () => {
    const phone = await sealBackup({ prefs: {}, durableDbB64: 'U1FMaXRlIGZvcm1hdCAz' }, 'secret-1', 1, 1020500, ROUNDS);
    expect(headerOf(phone)).toContain('"appVersionCode":1020500');
    expect(await fault(openBackup(phone, 'secret-1'))).toBe('ANDROID_BACKUP');
  });
});

describe('what a restore keeps', () => {
  it('takes her data from the file and keeps this browser\'s lock, household and marks', () => {
    const current = {
      prefs: { name: 'قبلی', lockEnabled: true, lockCredential: 'cred', syncSeq: 44, rates: null } as never,
      tables: { familyMembers: [{ id: 'me', name: 'من', sharesSms: true, avatar: '', updatedAt: 1, deleted: false }], sources: [] },
    };
    const restored = restoredSnapshot({ pwa: 1, prefs: { name: 'مریم', lockEnabled: true } as never, tables: { sources: payload.tables.sources } }, current);
    expect(restored.prefs).toMatchObject({ name: 'مریم', lockEnabled: true, lockCredential: 'cred', syncSeq: 44 });
    expect(restored.tables.familyMembers).toHaveLength(1);
    expect(restored.tables.sources).toEqual(payload.tables.sources);
  });

  it('never switches a lock on in a browser that has no credential for it', () => {
    const restored = restoredSnapshot({ pwa: 1, prefs: { lockEnabled: true, lockCredential: 'elsewhere' } as never, tables: {} }, { prefs: {}, tables: {} });
    expect(restored.prefs.lockEnabled).toBeUndefined();
    expect(restored.prefs.lockCredential).toBeUndefined();
  });
});
