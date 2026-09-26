/**
 * The app lock (Lock.kt), on the browser's own biometric door: a WebAuthn credential on this
 * device's platform authenticator — Face ID, Touch ID or the device passcode — made when she turns
 * the lock on and asked for whenever the app comes back.
 *
 * As on the phone, nothing here guards a key. There is no server to verify an assertion against
 * and the ledger in IndexedDB is not encrypted by it; it only hides the balances from whoever else
 * picks the phone up, and asking the platform for user verification is exactly that. The device
 * passcode is always a way in on the platform's own sheet, so a face that will not read never
 * leaves her out of her own balance.
 */
import { useEffect, useRef } from 'preact/hooks';
import { fromBase64Url, toBase64Url } from './crypto';
import { LockMark } from './icons';
import { registerGate } from './nav';
import { batch, pref, setPref } from './state';

/** False when the device has no biometric and no passcode, or the browser has no WebAuthn — then the lock cannot be offered. */
export async function lockAvailable(): Promise<boolean> {
  try {
    return typeof PublicKeyCredential !== 'undefined' && await PublicKeyCredential.isUserVerifyingPlatformAuthenticatorAvailable();
  } catch {
    return false;
  }
}

const challenge = (): Uint8Array<ArrayBuffer> => crypto.getRandomValues(new Uint8Array(32));

/** Makes the credential and arms the lock — only once the platform has said yes. Cancelled leaves it off. */
export async function enableLock(): Promise<boolean> {
  prompting = true;
  try {
    const credential = await navigator.credentials.create({
      publicKey: {
        challenge: challenge(),
        rp: { id: location.hostname, name: 'چقدر تومن' },
        // A random handle: the credential names no person, only this browser's lock.
        user: { id: crypto.getRandomValues(new Uint8Array(16)), name: 'چقدر تومن', displayName: 'چقدر تومن' },
        pubKeyCredParams: [{ type: 'public-key', alg: -7 }, { type: 'public-key', alg: -257 }],
        authenticatorSelection: { authenticatorAttachment: 'platform', userVerification: 'required', residentKey: 'discouraged' },
        attestation: 'none',
        timeout: 60_000,
      },
    }) as PublicKeyCredential | null;
    if (!credential) return false;
    batch(() => {
      setPref('lockCredential', toBase64Url(new Uint8Array(credential.rawId)));
      setPref('lockEnabled', true);
    });
    // Locked state is session-only (AppVm.setLockEnabled): arming it does not lock her out now.
    locked = false;
    return true;
  } catch (error) {
    console.warn('lock enrol refused', error);
    return false;
  } finally {
    prompting = false;
  }
}

export function disableLock(): void {
  batch(() => {
    setPref('lockEnabled', false);
    setPref('lockCredential', '');
  });
  locked = false;
}

let locked: boolean | undefined;
/** A platform sheet is up: whatever it does to the page's visibility is not her leaving the app. */
let prompting = false;
/** Taps on «باز کردن» the platform turned down since the lock last opened. */
let refused = 0;

/** Re-renders the app over the lock's change; a no-op batch is the store's own "something moved". */
function setLocked(next: boolean): void {
  locked = next;
  batch(() => {});
}

async function unlock(tapped = false): Promise<void> {
  const id = pref('lockCredential');
  if (prompting || !id) {
    // A lock with no credential behind it is a lockout, not a lock (a restore never brings one).
    if (!id) setLocked(false);
    return;
  }
  if (tapped && refused > 0) {
    // The credential may be gone — deleted from the phone's saved passwords, or lost with the
    // browser's data — and the platform cannot say so apart from a cancel. Enrolling a new one
    // still asks for the face or the passcode, so this is the same door, never a way round it;
    // without it a missing passkey would lock her out of her own ledger for good.
    if (await enableLock()) { refused = 0; setLocked(false); }
    return;
  }
  prompting = true;
  try {
    const assertion = await navigator.credentials.get({
      publicKey: {
        challenge: challenge(),
        rpId: location.hostname,
        allowCredentials: [{ type: 'public-key', id: fromBase64Url(id) }],
        userVerification: 'required',
        timeout: 60_000,
      },
    });
    if (assertion) { refused = 0; setLocked(false); }
  } catch (error) {
    // Dismissed or failed: the button is there for another go. Only a tap counts — the prompt on
    // arrival can be refused for want of a gesture alone.
    if (tapped) refused++;
    console.warn('unlock refused', error);
  } finally {
    prompting = false;
  }
}

// Leaving the app re-arms the lock, so coming back asks again.
if (typeof document !== 'undefined') {
  document.addEventListener('visibilitychange', () => {
    if (document.visibilityState === 'hidden' && !prompting && pref('lockEnabled') && locked === false) setLocked(true);
  });
}

/** Shown instead of everything while locked. Deliberately carries no figures. */
function LockScreen() {
  const button = useRef<HTMLButtonElement>(null);
  // Prompt immediately; the button is there for when it is dismissed.
  useEffect(() => { button.current?.focus(); void unlock(); }, []);
  return (
    // The fixed forest, not the theme-aware field: full-bleed, this screen is the brand moment.
    <div class="lock-screen" style={{
      position: 'fixed', inset: 0, zIndex: 60, background: 'var(--forest)', display: 'flex', flexDirection: 'column',
      alignItems: 'center', justifyContent: 'center', textAlign: 'center',
      padding: 'calc(env(safe-area-inset-top) + var(--xxl)) var(--xxl) calc(env(safe-area-inset-bottom) + var(--xxl))',
    }}>
      <span class="disc" style={{ width: '96px', height: '96px', background: 'var(--hero-well)', color: 'var(--hero-accent)' }}><LockMark size={40} /></span>
      <h1 style={{ marginTop: 'var(--xxl)', fontSize: '26px', fontWeight: 900, color: 'var(--hero-strong)' }}>چقدر تومن قفل شده</h1>
      <p style={{ marginTop: 'var(--m)', fontSize: '15px', lineHeight: '25px', color: 'var(--hero-muted)' }}>برای دیدن دارایی‌هات، اثر انگشت یا رمز گوشی‌ات رو وارد کن.</p>
      <button ref={button} type="button" class="pill primary block" style={{ marginTop: 'var(--huge)', minHeight: '60px', fontSize: '18px', maxWidth: '560px' }}
        onClick={() => void unlock(true)}>باز کردن</button>
    </div>
  );
}

// State is loaded before the app first renders, so the first read here is the saved preference.
registerGate({ order: 0, active: () => (locked ??= pref('lockEnabled')), Component: LockScreen });
