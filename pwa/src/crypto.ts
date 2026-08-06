/**
 * The scope keys, and the only place plaintext exists on this side of the wire.
 *
 * A scope is a keyed stream — `personal:<member>`, `family:<hid>` — with its own AES-GCM key.
 * Membership in a scope *is* possession of its key. The server keeps an access list too, but as
 * a second lock rather than the only one: a record from a scope she has no key for is
 * indistinguishable from noise even if that list were wrong.
 *
 * This is what makes the sharing model real. "Share the amount and the category but not the
 * merchant" is not a rule the server promises to apply — the sending device encrypts a
 * projection under the family key and the whole record under its own, and the family key simply
 * cannot open the second one.
 */

const ALGO = 'AES-GCM';
const NONCE_BYTES = 12;

export type ScopeKey = { scope: string; key: CryptoKey };

function toBase64(bytes: Uint8Array<ArrayBuffer>): string {
  let s = '';
  for (const b of bytes) s += String.fromCharCode(b);
  return btoa(s);
}

function fromBase64(value: string): Uint8Array<ArrayBuffer> {
  const binary = atob(value);
  const out = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i++) out[i] = binary.charCodeAt(i);
  return out;
}

/** base64url, because these travel in a URL fragment. */
export function fromBase64Url(value: string): Uint8Array<ArrayBuffer> {
  return fromBase64(value.replace(/-/g, '+').replace(/_/g, '/'));
}

export async function importKey(raw: Uint8Array<ArrayBuffer>): Promise<CryptoKey> {
  if (raw.byteLength !== 32) throw new Error('a scope key is 32 bytes');
  return crypto.subtle.importKey('raw', raw, ALGO, false, ['encrypt', 'decrypt']);
}

export async function generateKey(): Promise<{ key: CryptoKey; raw: Uint8Array<ArrayBuffer> }> {
  const raw = crypto.getRandomValues(new Uint8Array(32));
  return { key: await importKey(raw), raw };
}

export async function seal(key: CryptoKey, value: unknown): Promise<{ nonce: string; body: string }> {
  // A fresh nonce per record, never a counter: AES-GCM loses all its guarantees the moment one
  // is reused with the same key, and a counter has to survive a reinstall to stay unique.
  const nonce = crypto.getRandomValues(new Uint8Array(NONCE_BYTES));
  const plaintext = new TextEncoder().encode(JSON.stringify(value));
  const sealed = await crypto.subtle.encrypt({ name: ALGO, iv: nonce }, key, plaintext);
  return { nonce: toBase64(nonce), body: toBase64(new Uint8Array(sealed)) };
}

/**
 * Returns null when the key cannot open it — which is the ordinary case for a record from a
 * scope this device is not in, not an error worth surfacing.
 */
export async function open<T>(key: CryptoKey, nonce: string, body: string): Promise<T | null> {
  try {
    const plain = await crypto.subtle.decrypt(
      { name: ALGO, iv: fromBase64(nonce) },
      key,
      fromBase64(body),
    );
    return JSON.parse(new TextDecoder().decode(plain)) as T;
  } catch {
    return null;
  }
}

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

export function readPairing(hash: string): Pairing | null {
  if (!hash || hash.length < 2) return null;
  const params = new URLSearchParams(hash.replace(/^#/, ''));
  const hid = params.get('hid');
  const code = params.get('pair');
  const scope = params.get('scope');
  const key = params.get('k');
  if (!hid || !code || !scope || !key) return null;
  const raw = fromBase64Url(key);
  if (raw.byteLength !== 32) return null;
  return { url: params.get('url') ?? location.origin, hid, code, scope, key: raw };
}
