/**
 * The backup file — Export.kt's `.mtbak`, byte for byte, so the envelope is one format on both
 * sides:
 *
 * ```
 * "MTBAK1" · 4-byte big-endian header length · header JSON · ciphertext
 * ```
 *
 * The header is plaintext (it carries the salt and IV) but rides inside GCM's authentication as
 * associated data, so a fiddled header fails exactly as a fiddled ciphertext does. The ciphertext
 * is AES-256-GCM over a gzip of one JSON payload; the key is PBKDF2-HMAC-SHA256 from a passphrase
 * she picks in the moment and that is never written anywhere.
 *
 * What differs is the payload. The phone's is its SQLite file; the browser's is its own tables as
 * JSON — `{ pwa: 1, prefs, tables }` — because that is what it has. Neither side can read the
 * other's, and each says so in words when handed the other's file rather than failing mute.
 */
import { fromBase64, toBase64 } from './crypto';
import { faDate } from './format';
import { tehranDay } from './jalali';
import type { Prefs, TableName } from './model';
import { setPref, snapshot, replaceAll, settled } from './state';
import type { Snapshot } from './state';

export const BACKUP_MAGIC = 'MTBAK1';
export const BACKUP_FORMAT_VERSION = 1;
export const BACKUP_KDF_ALGO = 'PBKDF2WithHmacSHA256';
export const BACKUP_CIPHER_ALGO = 'AES/GCM/NoPadding';
export const BACKUP_KDF_ITERATIONS = 600_000;
export const BACKUP_MIN_PASSPHRASE = 6;
/** The browser payload's own version, beside the envelope's. */
export const PWA_PAYLOAD_VERSION = 1;

/** Past this a header is garbage, not a header — read before anything is trusted. */
const MAX_HEADER_BYTES = 64 * 1024;
/** A file demanding more work than this is a denial of service, not a backup. */
const MAX_KDF_ITERATIONS = 10_000_000;
/** The most a payload may inflate to; anything past it is a zip bomb. Also the file cap. */
export const MAX_PAYLOAD_BYTES = 256 * 1024 * 1024;

/**
 * Kept out of the file, and kept as this browser's own on restore (Data.kt EXCLUDED_PREFS and
 * Ledger.kt BACKUP_STRIPPED_META, plus the browser's own): the rates cache; the marks of what
 * *this* browser already announced; the household cursor, founder and SMS-sharing switch, which
 * belong to the session and not to the file; the lock, whose credential lives in this device's
 * authenticator — restored anywhere else it is a lockout, not a setting, so a restore never
 * switches it on; and whether this browser has been through the first screen.
 */
const LOCAL_PREFS: string[] = [
  'rates', 'budgetMarks', 'installmentMarks', 'syncSeq', 'syncShareSms', 'syncPrimaryMember',
  'lockEnabled', 'lockCredential', 'onboarded',
];
/**
 * The household as this browser's session has synced it. The session is not in the file (a
 * restored browser is a new device and pairs again, as on the phone), so these stay whatever the
 * browser's current session made them rather than a mirror of some other session's.
 */
const LOCAL_TABLES: TableName[] = ['familyMembers', 'familyTxns', 'familyAssets', 'publications'];

export interface BackupHeader {
  formatVersion: number;
  createdAt: number;
  appVersionCode?: number;
  kdf: { algo: string; iterations: number; saltB64: string };
  cipher: { algo: string; ivB64: string };
}

export interface BrowserPayload extends Snapshot { pwa: number }

export type BackupFault =
  /** Wrong magic, torn envelope, malformed header — this was never one of our files. */
  | 'NOT_A_BACKUP'
  /** A well-formed backup from a build newer than this one. Updating is the fix. */
  | 'NEWER_FORMAT'
  /** GCM refused it. The two causes are indistinguishable by design, and the words say both. */
  | 'WRONG_PASSPHRASE_OR_CORRUPT'
  /** Opened fine, and it is the phone's: a SQLite file the browser has no way to read. */
  | 'ANDROID_BACKUP';

export class BackupError extends Error {
  constructor(readonly fault: BackupFault, message: string) { super(message); }
}
const fail = (fault: BackupFault, message: string): never => { throw new BackupError(fault, message); };

const utf8 = new TextEncoder();

async function deriveKey(passphrase: string, salt: Uint8Array<ArrayBuffer>, iterations: number): Promise<CryptoKey> {
  // PBKDF2 over the passphrase's UTF-8, exactly as the JCA factory encodes it (Export.kt:196).
  const base = await crypto.subtle.importKey('raw', utf8.encode(passphrase), 'PBKDF2', false, ['deriveBits']);
  const bits = await crypto.subtle.deriveBits({ name: 'PBKDF2', hash: 'SHA-256', salt, iterations }, base, 256);
  return crypto.subtle.importKey('raw', bits, 'AES-GCM', false, ['encrypt', 'decrypt']);
}

async function drain(stream: ReadableStream<Uint8Array>, maxBytes: number): Promise<Uint8Array<ArrayBuffer>> {
  const reader = stream.getReader();
  const chunks: Uint8Array[] = [];
  let total = 0;
  for (;;) {
    const { done, value } = await reader.read();
    if (done) break;
    total += value.length;
    if (total > maxBytes) { await reader.cancel(); throw new Error('payload too large'); }
    chunks.push(value);
  }
  const out = new Uint8Array(total);
  let at = 0;
  for (const c of chunks) { out.set(c, at); at += c.length; }
  return out;
}
const piped = (bytes: Uint8Array<ArrayBuffer>, through: CompressionStream | DecompressionStream): ReadableStream<Uint8Array> =>
  new Blob([bytes]).stream().pipeThrough(through) as ReadableStream<Uint8Array>;

export const gzip = (bytes: Uint8Array<ArrayBuffer>): Promise<Uint8Array<ArrayBuffer>> =>
  drain(piped(bytes, new CompressionStream('gzip')), Number.MAX_SAFE_INTEGER);
/** Capped: the size of the inflated result is attacker-chosen, and the phone's memory is not. */
export const gunzip = (bytes: Uint8Array<ArrayBuffer>, maxBytes = MAX_PAYLOAD_BYTES): Promise<Uint8Array<ArrayBuffer>> =>
  drain(piped(bytes, new DecompressionStream('gzip')), maxBytes);

/** Everything into one encrypted envelope. [iterations] and [formatVersion] bend only in tests. */
export async function sealBackup(payload: object, passphrase: string, createdAt: number, appVersionCode = 0,
  iterations = BACKUP_KDF_ITERATIONS, formatVersion = BACKUP_FORMAT_VERSION): Promise<Uint8Array<ArrayBuffer>> {
  if (passphrase.length < BACKUP_MIN_PASSPHRASE) throw new Error('passphrase too short');
  const salt = crypto.getRandomValues(new Uint8Array(16));
  const iv = crypto.getRandomValues(new Uint8Array(12));
  // kotlinx's encoder, key order and all; it leaves a default-valued appVersionCode out.
  const header: BackupHeader = {
    formatVersion, createdAt, ...(appVersionCode ? { appVersionCode } : {}),
    kdf: { algo: BACKUP_KDF_ALGO, iterations, saltB64: toBase64(salt) },
    cipher: { algo: BACKUP_CIPHER_ALGO, ivB64: toBase64(iv) },
  };
  const headerBytes = utf8.encode(JSON.stringify(header));
  const key = await deriveKey(passphrase, salt, iterations);
  const plain = await gzip(utf8.encode(JSON.stringify(payload)));
  // The header rides outside the encryption, so it rides inside the authentication.
  const sealed = new Uint8Array(await crypto.subtle.encrypt({ name: 'AES-GCM', iv, additionalData: headerBytes, tagLength: 128 }, key, plain));
  const out = new Uint8Array(BACKUP_MAGIC.length + 4 + headerBytes.length + sealed.length);
  out.set(utf8.encode(BACKUP_MAGIC), 0);
  new DataView(out.buffer).setUint32(BACKUP_MAGIC.length, headerBytes.length);
  out.set(headerBytes, BACKUP_MAGIC.length + 4);
  out.set(sealed, BACKUP_MAGIC.length + 4 + headerBytes.length);
  return out;
}

/**
 * The envelope back apart, with every refusal named. Everything that can be judged without the
 * passphrase is judged first, so «رمز اشتباهه» is never said about a file that was never a backup.
 */
export async function openBackup(bytes: Uint8Array<ArrayBuffer>, passphrase: string): Promise<{ header: BackupHeader; payload: BrowserPayload }> {
  const magic = utf8.encode(BACKUP_MAGIC);
  if (bytes.length < magic.length + 4) fail('NOT_A_BACKUP', 'too short');
  if (!magic.every((b, i) => bytes[i] === b)) fail('NOT_A_BACKUP', 'bad magic');
  const headerLen = new DataView(bytes.buffer, bytes.byteOffset).getInt32(magic.length);
  if (headerLen <= 0 || headerLen > MAX_HEADER_BYTES) fail('NOT_A_BACKUP', 'bad header length');
  const headerFrom = magic.length + 4;
  // GCM's tag alone is 16 bytes; anything shorter cannot hold even an empty payload.
  if (bytes.length < headerFrom + headerLen + 16) fail('NOT_A_BACKUP', 'truncated');

  const headerBytes = bytes.slice(headerFrom, headerFrom + headerLen);
  let header: BackupHeader;
  try {
    header = JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(headerBytes));
    if (typeof header.formatVersion !== 'number' || typeof header.kdf?.iterations !== 'number') throw new Error('shape');
  } catch { return fail('NOT_A_BACKUP', 'unreadable header'); }

  if (header.formatVersion > BACKUP_FORMAT_VERSION) fail('NEWER_FORMAT', `format ${header.formatVersion}`);
  if (header.formatVersion < 1) fail('NOT_A_BACKUP', 'bad format version');
  if (header.kdf.algo !== BACKUP_KDF_ALGO) fail('NOT_A_BACKUP', 'unknown kdf');
  if (header.cipher?.algo !== BACKUP_CIPHER_ALGO) fail('NOT_A_BACKUP', 'unknown cipher');
  const { iterations } = header.kdf;
  if (!Number.isInteger(iterations) || iterations < 1 || iterations > MAX_KDF_ITERATIONS) fail('NOT_A_BACKUP', 'bad iterations');
  let salt: Uint8Array<ArrayBuffer>, iv: Uint8Array<ArrayBuffer>;
  try { salt = fromBase64(header.kdf.saltB64); iv = fromBase64(header.cipher.ivB64); } catch { return fail('NOT_A_BACKUP', 'bad salt or iv'); }
  if (salt.length < 8 || salt.length > 64) fail('NOT_A_BACKUP', 'bad salt size');
  if (iv.length !== 12) fail('NOT_A_BACKUP', 'bad iv size');

  // From here down every failure is one answer on purpose: GCM cannot tell a wrong key from a
  // flipped bit, and pretending otherwise would be inventing a diagnosis.
  let payload: Record<string, unknown>;
  try {
    const key = await deriveKey(passphrase, salt, iterations);
    const plain = new Uint8Array(await crypto.subtle.decrypt(
      { name: 'AES-GCM', iv, additionalData: headerBytes, tagLength: 128 }, key, bytes.slice(headerFrom + headerLen)));
    payload = JSON.parse(new TextDecoder().decode(await gunzip(plain)));
    if (payload == null || typeof payload !== 'object') throw new Error('shape');
  } catch { return fail('WRONG_PASSPHRASE_OR_CORRUPT', 'auth failed'); }

  if (typeof payload.durableDbB64 === 'string' && payload.durableDbB64 && payload.pwa == null) fail('ANDROID_BACKUP', 'sqlite payload');
  if (typeof payload.pwa !== 'number' || typeof payload.tables !== 'object' || typeof payload.prefs !== 'object') fail('NOT_A_BACKUP', 'not a browser payload');
  if ((payload.pwa as number) > PWA_PAYLOAD_VERSION) fail('NEWER_FORMAT', `payload ${payload.pwa}`);
  return { header, payload: payload as unknown as BrowserPayload };
}

// ---- the browser's side: what goes in, what comes back -------------------------------------

/** Everything of hers, as it stands now, minus what is this browser's own. */
export async function browserPayload(): Promise<BrowserPayload> {
  await settled();
  const { prefs, tables } = snapshot();
  const kept: Record<string, unknown> = { ...prefs };
  for (const key of LOCAL_PREFS) delete kept[key];
  const hers = { ...tables };
  for (const t of LOCAL_TABLES) delete hers[t];
  return { pwa: PWA_PAYLOAD_VERSION, prefs: kept as Partial<Prefs>, tables: hers };
}

/** The backup's tables and prefs over this browser's, keeping what [LOCAL_PREFS]/[LOCAL_TABLES] name. */
export function restoredSnapshot(payload: BrowserPayload, current: Snapshot): Snapshot {
  const prefs: Record<string, unknown> = { ...payload.prefs };
  const mine = current.prefs as Record<string, unknown>;
  for (const key of LOCAL_PREFS) {
    delete prefs[key];
    if (key in mine) prefs[key] = mine[key];
  }
  const tables: Snapshot['tables'] = { ...payload.tables };
  for (const t of LOCAL_TABLES) (tables as Record<string, unknown>)[t] = current.tables[t] ?? [];
  return { prefs: prefs as Partial<Prefs>, tables };
}

/** The file's name as offered to the download — Settings.kt backupFileName. */
export function backupFileName(now = new Date()): string {
  const two = (n: number) => String(n).padStart(2, '0');
  return `muchtoman-${now.getFullYear()}-${two(now.getMonth() + 1)}-${two(now.getDate())}.mtbak`;
}

/** Every refusal, in the words Export.kt backupFaultFa says it. */
export function backupFaultFa(error: unknown): string {
  switch (error instanceof BackupError ? error.fault : null) {
    case 'NOT_A_BACKUP': return 'این فایل پشتیبانِ چقدر تومن نیست.';
    case 'NEWER_FORMAT': return 'این پشتیبان با نسخهٔ جدیدتر برنامه ساخته شده. اول برنامه رو به‌روز کن.';
    case 'WRONG_PASSPHRASE_OR_CORRUPT': return 'رمز اشتباهه یا فایل خرابه.';
    // The phone's file is its SQLite database; Export.kt's BROWSER_BACKUP says this mirrored.
    case 'ANDROID_BACKUP': return 'این پشتیبان مال اپ اندرویده و توی مرورگر باز نمی‌شه.';
    default: return 'فایل خونده نشد. دوباره امتحان کن.';
  }
}

/** Sealed and handed to the browser as a download. Resolves once the file is on its way. */
export async function exportBackup(passphrase: string): Promise<void> {
  const sealed = await sealBackup(await browserPayload(), passphrase, Date.now());
  const url = URL.createObjectURL(new Blob([sealed], { type: 'application/octet-stream' }));
  const a = document.createElement('a');
  a.href = url; a.download = backupFileName();
  document.body.append(a); a.click(); a.remove();
  // Revoked late: Safari starts the download after the click returns.
  setTimeout(() => URL.revokeObjectURL(url), 60_000);
  setPref('lastBackupAt', Date.now());
}

/** A picked file, judged. Nothing in the browser changes here; [applyRestore] is the step that does. */
export async function readBackupFile(file: Blob, passphrase: string): Promise<{ payload: BrowserPayload; words: string }> {
  if (file.size > MAX_PAYLOAD_BYTES) fail('NOT_A_BACKUP', 'file too large');
  const { header, payload } = await openBackup(new Uint8Array(await file.arrayBuffer()), passphrase);
  const day = header.createdAt > 0 ? faDate(tehranDay(header.createdAt)) : null;
  return { payload, words: day == null ? 'فایل پشتیبان' : `پشتیبانِ ${day}` };
}

/** The destructive step: one IndexedDB transaction, so a failure leaves the old ledger whole. */
export async function applyRestore(payload: BrowserPayload): Promise<void> {
  await replaceAll(restoredSnapshot(payload, snapshot()));
}
