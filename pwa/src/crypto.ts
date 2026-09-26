/**
 * The scope keys, and the only place plaintext exists on this side of the wire.
 *
 * A scope is a keyed stream — `family:<hid>` — with its own AES-GCM key. Membership in a scope
 * *is* possession of its key. The server keeps an access list too, but as a second lock rather
 * than the only one: a record from a scope she has no key for is indistinguishable from noise
 * even if that list were wrong.
 *
 * Sealing takes the plaintext as a string, as Sync.kt's `seal` does, because the string is also
 * what gets hashed: the publication table compares the bytes that went out, not a re-encoding.
 */

const ALGO = 'AES-GCM';
const NONCE_BYTES = 12;

export function toBase64(bytes: Uint8Array): string {
  let s = '';
  for (const b of bytes) s += String.fromCharCode(b);
  return btoa(s);
}

export function fromBase64(value: string): Uint8Array<ArrayBuffer> {
  const binary = atob(value);
  const out = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i++) out[i] = binary.charCodeAt(i);
  return out;
}

/** base64url without padding — Android's `NO_WRAP or URL_SAFE or NO_PADDING`, because it rides a URL fragment. */
export function toBase64Url(bytes: Uint8Array): string {
  return toBase64(bytes).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

export function fromBase64Url(value: string): Uint8Array<ArrayBuffer> {
  return fromBase64(value.replace(/-/g, '+').replace(/_/g, '/'));
}

export function hexOf(bytes: Uint8Array): string {
  return [...bytes].map((b) => b.toString(16).padStart(2, '0')).join('');
}

export function randomHex(bytes: number): string {
  return hexOf(crypto.getRandomValues(new Uint8Array(bytes)));
}

export async function importKey(raw: Uint8Array<ArrayBuffer>): Promise<CryptoKey> {
  if (raw.byteLength !== 32) throw new Error('a scope key is 32 bytes');
  return crypto.subtle.importKey('raw', raw, ALGO, false, ['encrypt', 'decrypt']);
}

/** Sync.kt `newScopeKey`: 32 random bytes, and the key made of them. */
export async function generateKey(): Promise<{ key: CryptoKey; raw: Uint8Array<ArrayBuffer> }> {
  const raw = crypto.getRandomValues(new Uint8Array(32));
  return { key: await importKey(raw), raw };
}

export async function seal(key: CryptoKey, plaintext: string): Promise<{ nonce: string; body: string }> {
  // A fresh nonce per record, never a counter: AES-GCM loses all its guarantees the moment one
  // is reused with the same key, and a counter has to survive a reinstall to stay unique.
  const nonce = crypto.getRandomValues(new Uint8Array(NONCE_BYTES));
  const sealed = await crypto.subtle.encrypt({ name: ALGO, iv: nonce }, key, new TextEncoder().encode(plaintext));
  return { nonce: toBase64(nonce), body: toBase64(new Uint8Array(sealed)) };
}

/**
 * Null when the key cannot open it — which is the ordinary case for a record from a scope this
 * device is not in, or one sealed under a household's previous key, not an error worth surfacing.
 */
export async function openSealed(key: CryptoKey, nonce: string, body: string): Promise<string | null> {
  try {
    const plain = await crypto.subtle.decrypt({ name: ALGO, iv: fromBase64(nonce) }, key, fromBase64(body));
    return new TextDecoder().decode(plain);
  } catch {
    return null;
  }
}

// ---- pairing links -------------------------------------------------------------------------

/**
 * What the QR carries, read out of `location.hash`.
 *
 * The fragment is the whole trick: a browser never sends it in an HTTP request, so the scope key
 * reaches this device without ever passing through the Worker. The iPhone's own Camera app scans
 * the code and opens the URL — no barcode library on this side at all, which matters because
 * iOS Safari has no BarcodeDetector.
 *
 * Ceiling, named and worth stating in the privacy screen: the URL sits in Safari's history until
 * the caller replaces it, and it is legible in any screenshot of the QR itself.
 */
export interface Pairing {
  url: string;
  hid: string;
  code: string;
  scope: string;
  key: Uint8Array<ArrayBuffer>;
}

/** Sync.kt `parsePairingLink`'s checks: a household id of 16–64 characters and a 32-byte key. */
export function readPairing(hash: string): Pairing | null {
  if (!hash || hash.length < 2) return null;
  const params = new URLSearchParams(hash.replace(/^#/, ''));
  const hid = params.get('hid');
  const code = params.get('pair');
  const scope = params.get('scope');
  const key = params.get('k');
  if (!hid || !code || !scope || !key || hid.length < 16 || hid.length > 64) return null;
  let raw: Uint8Array<ArrayBuffer>;
  try { raw = fromBase64Url(key); } catch { return null; }
  if (raw.byteLength !== 32) return null;
  return { url: (params.get('url') ?? location.origin).replace(/\/+$/, ''), hid, code, scope, key: raw };
}
