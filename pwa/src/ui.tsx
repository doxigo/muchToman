/**
 * The shared kit — Ui.kt's ScreenTitle, SheetTitle, SheetLabel, Panel, HeroPanel, PillButton,
 * ChipChoice, SegmentedChoice, SheetDelete, ActionCircle, the band, the switch row and the text
 * fields — so every screen speaks in the same shapes the app does. Styles live in app.css under
 * the same names; a screen that needs a one-off reaches for the tokens there, not a new colour.
 */
import type { ComponentChildren, JSX } from 'preact';
import { useEffect, useLayoutEffect, useRef, useState } from 'preact/hooks';
import { closePage, closeSheet } from './nav';
import { faWordsToman, groupDigits, parseAmount } from './format';

export function ScreenTitle({ children }: { children: ComponentChildren }) {
  return <h1 class="screen-title">{children}</h1>;
}
export function SheetTitle({ children }: { children: ComponentChildren }) {
  return <h2 class="sheet-title">{children}</h2>;
}
export function SheetLabel({ children }: { children: ComponentChildren }) {
  return <h3 class="sheet-label">{children}</h3>;
}

/** A root page's frame: the title row (with an optional «برگشت» or trailing control) and the body. */
export function Screen({ title, back, trailing, tabs = true, children }: {
  title?: ComponentChildren; back?: boolean | (() => void); trailing?: ComponentChildren; tabs?: boolean; children: ComponentChildren;
}) {
  return (
    <div class={`screen${tabs ? '' : ' no-tabs'}`}>
      {title != null && (
        <div class="screen-head">
          <ScreenTitle>{title}</ScreenTitle>
          {trailing}
          {back && <PillButton label="برگشت" onClick={typeof back === 'function' ? back : closePage} />}
        </div>
      )}
      {children}
    </div>
  );
}

export function Panel({ children, class: cls = '' }: { children: ComponentChildren; class?: string }) {
  return <div class={`panel ${cls}`}>{children}</div>;
}
export function HeroPanel({ children, class: cls = '' }: { children: ComponentChildren; class?: string }) {
  return <div class={`hero ${cls}`}>{children}</div>;
}

export type Voice = 'primary' | 'tonal' | 'hero';
export function PillButton({ label, onClick, voice = 'tonal', block, disabled, children, type = 'button' }: {
  label?: string; onClick?: () => void; voice?: Voice; block?: boolean; disabled?: boolean; children?: ComponentChildren;
  type?: 'button' | 'submit';
}) {
  const cls = `pill${voice === 'primary' ? ' primary' : voice === 'hero' ? ' hero-voice' : ''}${block ? ' block' : ''}`;
  return <button type={type} class={cls} onClick={onClick} disabled={disabled}>{children}{label}</button>;
}
export function TextButton({ label, onClick, danger, block }: { label: string; onClick: () => void; danger?: boolean; block?: boolean }) {
  return <button type="button" class={`text-btn${danger ? ' danger' : ''}${block ? ' block' : ''}`} onClick={onClick}>{label}</button>;
}

/** One track, one filled pill: the app's only shape for "pick exactly one of a few". */
export function SegmentedChoice<T>({ options, selected, label, onSelect, enabled = () => true, fontSize }: {
  options: T[]; selected: T; label: (t: T) => string; onSelect: (t: T) => void; enabled?: (t: T) => boolean; fontSize?: number;
}) {
  return (
    <div class="segmented" role="radiogroup">
      {options.map((o) => (
        <button type="button" role="radio" aria-checked={o === selected} disabled={!enabled(o)}
          style={fontSize ? { fontSize: `${fontSize}px` } : undefined} onClick={() => onSelect(o)}>{label(o)}</button>
      ))}
    </div>
  );
}

/** The quieter sibling: loose chips that slice the data below them. */
export function ChipChoice<T>({ options, selected, label, onSelect, enabled = () => true, scroll }: {
  options: T[]; selected: T; label: (t: T) => string; onSelect: (t: T) => void; enabled?: (t: T) => boolean; scroll?: boolean;
}) {
  return (
    <div class={`chips${scroll ? ' scroll' : ''}`} role="radiogroup">
      {options.map((o) => (
        <button type="button" class="chip" role="radio" aria-checked={o === selected} disabled={!enabled(o)} onClick={() => onSelect(o)}>{label(o)}</button>
      ))}
    </div>
  );
}

export function Switch({ checked, onChange, label, disabled }: { checked: boolean; onChange: (v: boolean) => void; label?: string; disabled?: boolean }) {
  return <button type="button" class="switch" role="switch" aria-checked={checked} aria-label={label} disabled={disabled} onClick={() => onChange(!checked)} />;
}
export function SwitchRow({ title, sub, checked, onChange, disabled }: {
  title: string; sub?: ComponentChildren; checked: boolean; onChange: (v: boolean) => void; disabled?: boolean;
}) {
  return (
    <div class="switch-row">
      <div class="grow"><div>{title}</div>{sub && <div>{sub}</div>}</div>
      <Switch checked={checked} onChange={onChange} label={title} disabled={disabled} />
    </div>
  );
}

/** Two taps, and the armed state is announced, or the safeguard is invisible to a screen reader. */
export function SheetDelete({ label, onConfirmed }: { label: string; onConfirmed: () => void }) {
  const [armed, setArmed] = useState(false);
  return (
    <button type="button" class="text-btn danger block" style={{ marginTop: 'var(--xs)', fontWeight: armed ? 700 : 400 }}
      onClick={() => (armed ? onConfirmed() : setArmed(true))}>
      <span aria-live="polite">{armed ? 'مطمئنی؟ برای حذف دوباره بزن' : label}</span>
    </button>
  );
}

export function ActionCircle({ label, onClick, icon, disabled, quiet }: {
  label: string; onClick: () => void; icon: ComponentChildren; disabled?: boolean; quiet?: boolean;
}) {
  return (
    <button type="button" class={`action-circle${quiet ? ' quiet' : ''}`} onClick={onClick} disabled={disabled} aria-label={label}>
      <span class="disc">{icon}</span>
      <span>{label}</span>
    </button>
  );
}

export function Disc({ size = 44, bg, color, children, class: cls = '' }: {
  size?: number; bg?: string; color?: string; children?: ComponentChildren; class?: string;
}) {
  return <span class={`disc ${cls}`} style={{ width: `${size}px`, height: `${size}px`, background: bg, color }}>{children}</span>;
}

export function EmptyState({ icon, title, body, children }: { icon: ComponentChildren; title: string; body?: ComponentChildren; children?: ComponentChildren }) {
  return (
    <div class="empty">
      <span class="disc">{icon}</span>
      <p style={{ fontWeight: 800, fontSize: '17px' }}>{title}</p>
      {body && <p class="muted">{body}</p>}
      {children}
    </div>
  );
}

/** A bottom sheet's body: the handle, then whatever the sheet says. Registered sheets wrap in it. */
export function Sheet({ children, onClose = closeSheet, label }: { children: ComponentChildren; onClose?: () => void; label?: string }) {
  useEffect(() => {
    const key = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose(); };
    addEventListener('keydown', key);
    const prev = document.body.style.overflow; document.body.style.overflow = 'hidden';
    return () => { removeEventListener('keydown', key); document.body.style.overflow = prev; };
  }, [onClose]);
  return (
    <>
      <div class="scrim" onClick={onClose} />
      <div class="sheet" role="dialog" aria-modal="true" aria-label={label}>
        <div class="handle" />
        {children}
      </div>
    </>
  );
}

// ---- fields -------------------------------------------------------------------------------------

type FieldProps = {
  label?: string; value: string; onInput: (v: string) => void; placeholder?: string; maxLength?: number;
  ltr?: boolean; error?: string | null; support?: ComponentChildren; inputMode?: JSX.HTMLAttributes['inputMode'];
  multiline?: boolean; autoFocus?: boolean; onEnter?: () => void; id?: string; type?: string;
  /** What a screen reader hears when it differs from the visible label, or there is none. */
  ariaLabel?: string;
};
export function TextField({ label, value, onInput, placeholder, maxLength, ltr, error, support, inputMode, multiline, autoFocus, onEnter, id, type, ariaLabel }: FieldProps) {
  const ref = useRef<HTMLInputElement & HTMLTextAreaElement>(null);
  useEffect(() => { if (autoFocus) ref.current?.focus(); }, []);
  const common = {
    ref, id, value, placeholder, maxLength, inputMode, 'aria-invalid': !!error, 'aria-label': ariaLabel ?? label,
    onInput: (e: Event) => onInput((e.currentTarget as HTMLInputElement).value),
    onKeyDown: (e: KeyboardEvent) => { if (e.key === 'Enter' && !multiline && onEnter) { e.preventDefault(); onEnter(); } },
  };
  return (
    <div>
      <label class={`field${error ? ' error' : ''}${ltr ? ' ltr' : ''}`}>
        {multiline ? <textarea {...common} /> : <input {...common} type={type ?? 'text'} dir={ltr ? 'ltr' : undefined} />}
        {label && <span class="label">{label}</span>}
      </label>
      {(error || support) && <div class={`field-support${error ? ' error' : ''}`}>{error || support}</div>}
    </div>
  );
}

/**
 * A number she types with the thousands grouped as she types (GroupedNumber, Ui.kt:177): the raw
 * state stays ASCII digits so it always round-trips through `parseAmount`; the field shows Persian
 * digits and ٬, and the caret keeps its place among the digits across the separators.
 */
export function AmountField({ label, raw, onRaw, decimals = 0, error, words = true, placeholder, autoFocus, onEnter, ariaLabel }: {
  label?: string; raw: string; onRaw: (raw: string) => void; decimals?: number; error?: string | null;
  words?: boolean; placeholder?: string; autoFocus?: boolean; onEnter?: () => void; ariaLabel?: string;
}) {
  const ref = useRef<HTMLInputElement>(null);
  const caret = useRef<number | null>(null);
  const shown = groupDigits(raw);
  useLayoutEffect(() => {
    const el = ref.current; const want = caret.current;
    if (!el || want == null) return;
    // Put the caret after the same number of digits it was after, whatever separators moved.
    let seen = 0; let at = 0;
    for (; at < shown.length && seen < want; at++) if (/[0-9۰-۹٫]/.test(shown[at])) seen++;
    el.setSelectionRange(at, at);
    caret.current = null;
  });
  useEffect(() => { if (autoFocus) ref.current?.focus(); }, []);
  const onInput = (e: Event) => {
    const el = e.currentTarget as HTMLInputElement;
    const before = el.value.slice(0, el.selectionStart ?? el.value.length);
    const norm = (s: string) => s.replace(/[۰-۹]/g, (d) => String(d.charCodeAt(0) - 0x6f0))
      .replace(/[٠-٩]/g, (d) => String(d.charCodeAt(0) - 0x660)).replace(/[٫]/g, '.').replace(/[^0-9.]/g, '');
    let next = norm(el.value);
    if (decimals === 0) next = next.replace(/\./g, '');
    else {
      const [i, ...f] = next.split('.');
      next = f.length ? `${i}.${f.join('').slice(0, decimals)}` : i;
    }
    caret.current = norm(before).replace(decimals === 0 ? /\./g : /$^/, '').length;
    onRaw(next);
    if (next !== raw) return;
    el.value = groupDigits(next);
  };
  const value = parseAmount(raw);
  const spelled = words && value != null && Number.isInteger(value) && value >= 1000 ? faWordsToman(value) : null;
  return (
    <div>
      <label class={`field${error ? ' error' : ''}`}>
        <input ref={ref} value={shown} inputMode={decimals ? 'decimal' : 'numeric'} placeholder={placeholder} aria-label={ariaLabel ?? label}
          aria-invalid={!!error} class="figure" onInput={onInput}
          onKeyDown={(e) => { if (e.key === 'Enter' && onEnter) { e.preventDefault(); onEnter(); } }} />
        {label && <span class="label">{label}</span>}
      </label>
      <div class={`field-support${error ? ' error' : ''}`}>{error || spelled || ''}</div>
    </div>
  );
}

/** Esc and the scrim close a page opened as a modal-looking room, same as the sheet. */
export function useBackKey(action: () => void = closePage): void {
  useEffect(() => {
    const key = (e: KeyboardEvent) => { if (e.key === 'Escape') action(); };
    addEventListener('keydown', key);
    return () => removeEventListener('keydown', key);
  }, [action]);
}

/** Section heading over a band: `SectionHead` (Ui.kt:2661) without the subtotal, which callers add. */
export function Section({ title, trailing }: { title: ComponentChildren; trailing?: ComponentChildren }) {
  return <div class="section"><h2>{title}</h2>{trailing}</div>;
}

/** The hero figure: `heroFigure` — digits, then «تومان» at 0.42em and 75%. `fit` is its length in ems. */
export function TomanFigure({ text, class: cls = '', unit = 'تومان' }: { text: string; class?: string; unit?: string | null }) {
  return <span class={`figure ${cls}`} style={{ '--fit': Math.max(6, [...text].length * .62 + (unit ? 2.4 : 0)) }}>{text}{unit && <span class="unit">{unit}</span>}</span>;
}
