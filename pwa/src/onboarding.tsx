/**
 * The one screen that asks for everything, shown once, before anything else (Onboarding.kt).
 * Ask once, having said what each thing is for, and never again: a refusal is final here, and
 * everything stays reachable from تنظیمات afterwards.
 *
 * The browser has no inbox to ask for and no battery exemption to beg, so its two steps are the
 * two things that matter on an iPhone instead: notifications, and the Home Screen — the only place
 * Safari neither evicts the ledger after weeks away nor withholds notifications. Each step is
 * skipped where it has nothing to ask (no Notification API outside the Home Screen on iOS; already
 * installed), and a browser with nothing to ask never sees the screen at all.
 */
import { useEffect, useState } from 'preact/hooks';
import { bidi } from './format';
import { registerGate } from './nav';
import { pref, setPref } from './state';

const canAskNotify = (): boolean => typeof Notification !== 'undefined' && Notification.permission === 'default';
const installed = (): boolean =>
  matchMedia('(display-mode: standalone)').matches || (navigator as Navigator & { standalone?: boolean }).standalone === true;

const finish = (): void => setPref('onboarded', true);

/** One permission, said as what it does for her rather than as what it is called. */
function AskRow({ title, body }: { title: string; body: string }) {
  return (
    <div style={{ display: 'flex', paddingBottom: 'var(--l)' }}>
      <span style={{ flex: 'none', width: '8px', height: '8px', marginTop: 'var(--s)', borderRadius: '50%', background: 'var(--primary)' }} />
      <div style={{ paddingInlineStart: 'var(--m)' }}>
        <p style={{ fontSize: '16px', fontWeight: 700 }}>{title}</p>
        <p class="muted" style={{ fontSize: '13px', lineHeight: '22px', marginTop: 'var(--xs)' }}>{body}</p>
      </div>
    </div>
  );
}

function Primary({ label, onClick }: { label: string; onClick: () => void }) {
  return (
    <button type="button" class="pill block" style={{ minHeight: '56px', fontSize: '17px', background: 'var(--primary)', color: 'var(--on-primary)' }}
      onClick={onClick}>{label}</button>
  );
}

/** Always present, always a real way out: nothing here may be a wall. */
function Secondary({ label, onClick }: { label: string; onClick: () => void }) {
  return (
    <button type="button" class="text-btn block" style={{ color: 'var(--on-surface-variant)', fontWeight: 400, minHeight: '48px' }} onClick={onClick}>{label}</button>
  );
}

function Onboarding() {
  // Not saved: this screen is one gesture long, and a reload is better spent back at the start.
  const [step, setStep] = useState(() => (canAskNotify() ? 0 : 1));
  const nothingLeft = step === 1 && installed();
  useEffect(() => { if (nothingLeft) finish(); }, [nothingLeft]);
  if (nothingLeft) return null;

  const heading = { fontSize: '28px', fontWeight: 800, lineHeight: 1.4 };
  const body = { fontSize: '15px', lineHeight: '26px', marginTop: 'var(--m)' };
  return (
    <div style={{
      minHeight: '100dvh', display: 'flex', flexDirection: 'column', justifyContent: 'center',
      padding: 'calc(env(safe-area-inset-top) + var(--xxl)) var(--xl) calc(env(safe-area-inset-bottom) + var(--xxl))',
    }}>
      {/* The Home Screen icon itself, so the first screen wears the mark she tapped: the tile's
          middle 72 of its 108, as a round mask leaves it. */}
      <span style={{ display: 'block', width: '72px', height: '72px', borderRadius: '50%', overflow: 'hidden' }}>
        <img src="/icon.svg" alt="" width={108} height={108} style={{ display: 'block', margin: '-18px', maxWidth: 'none' }} />
      </span>
      <div style={{ height: 'var(--xl)' }} />
      {step === 0 ? (
        <>
          <h1 style={heading}>قبل از شروع</h1>
          <p class="muted" style={body}>چقدر تومن برای خبر دادن بهت به یه اجازه احتیاج داره:</p>
          <div style={{ height: 'var(--xl)' }} />
          <AskRow title="اطلاع‌رسانی" body="تا وقتی بودجه‌ای داره تموم می‌شه یا قسطی نزدیکه، خبردار بشی." />
          <div style={{ height: 'var(--xxl)' }} />
          <Primary label="اجازه بده" onClick={() => {
            const next = () => { if (installed()) finish(); else setStep(1); };
            void Notification.requestPermission().then(next, next);
          }} />
          <Secondary label="الان نه" onClick={finish} />
        </>
      ) : (
        <>
          <h1 style={heading}>یک قدم آخر</h1>
          <p class="muted" style={body}>
            چقدر تومن رو به صفحهٔ اصلی گوشی اضافه کن. این‌جوری مرورگر دفترت رو پاک نمی‌کنه.
          </p>
          <p class="muted" style={body}>{`توی سافاری دکمهٔ ${bidi('Share')} رو بزن، بعد ${bidi('Add\u00a0to\u00a0Home\u00a0Screen')}.`}</p>
          <div style={{ height: 'var(--xxl)' }} />
          {/* Finished either way: the page cannot add itself, and a screen that waited for it would have no way off. */}
          <Primary label="باشه" onClick={finish} />
        </>
      )}
    </div>
  );
}

registerGate({ order: 1, active: () => !pref('onboarded'), Component: Onboarding });
