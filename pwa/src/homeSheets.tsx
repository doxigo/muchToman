/**
 * The holding's pieces and the three sheets behind the asset list — Ui.kt's AssetIcon, RowTitle,
 * RowAmount, PickTypeSheet, EditSheet and BankSheet, one to one. The list itself is home.tsx;
 * these live apart so the list can import the row pieces without the two files importing each other.
 *
 * What a browser cannot do is left out rather than faked: there is no inbox to open from a bank
 * card, no unknown sender to suggest and nothing to rescan (every balance is re-derived from the
 * pasted messages on each change), and no بورس source, so the picker's بورس section never shows.
 */
import type { ComponentChildren } from 'preact';
import { useEffect, useLayoutEffect, useMemo, useRef, useState } from 'preact/hooks';
import { KINDS, KIND_FA, TOMAN_ID, catalog, matchesSearch, resolveType, valuedInToman } from './catalog';
import type { AssetType, Kind } from './catalog';
import { AssetGlyph, assetGlyph } from './assetGlyph';
import {
  clearWalletError, connectWallet, currentEffective, currentTotals, isWalletAddressFormatValid, newHoldingId, recordSnapshot,
  reinstateHolding, removeHolding, setExcluded, setHolding, setLabel, setOverride, useWealthStatus,
} from './data';
import { useLedger } from './derived';
import type { BankAccountView } from './derived';
import { bidi, faAgo, faCompact, faHeld, faNumber, faWords, parseAmount, tomanOf, trimNumber } from './format';
import { forgetAccount, setBankBalance, toggleBankDisabled } from './ledger';
import { BankLogo, WalletNetworkLogo } from './logos';
import { holdingKey } from './model';
import type { WalletOption } from './model';
import { closeSheet, openSheet, registerSheet, showNotice } from './nav';
import { pref, useData } from './state';
import { AmountField, SegmentedChoice, Sheet, SheetDelete, SheetLabel, SheetTitle, TextField } from './ui';
import './home.css';

// ---- shared row pieces ------------------------------------------------------------------------

/** A rate map lookup that cannot land on Object.prototype ("constructor" is a legal coin id). */
export const rateIn = (rates: Record<string, number>, id: string): number | null =>
  (Object.hasOwn(rates, id) ? rates[id] : null);

/**
 * TextAutoSize.StepBased: the largest whole size in [min, max] at which the one line fits its box,
 * re-measured when the box resizes and once the font has loaded. Below `min` the ellipsis takes over.
 */
export function useAutoSize<T extends HTMLElement>(max: number, min: number, text: string) {
  const ref = useRef<T>(null);
  useLayoutEffect(() => {
    const el = ref.current;
    if (!el) return;
    const fit = () => {
      el.style.fontSize = `${max}px`;
      const over = el.scrollWidth / Math.max(1, el.clientWidth);
      el.style.fontSize = `${over > 1.001 ? Math.max(min, Math.floor(max / over)) : max}px`;
    };
    fit();
    void document.fonts?.ready.then(fit);
    const watch = new ResizeObserver(fit);
    if (el.parentElement) watch.observe(el.parentElement);
    return () => watch.disconnect();
  }, [max, min, text]);
  return ref;
}

/** A row's title: gives up a point or two of size before it gives up letters. */
export function RowTitle({ text, max = 18, class: cls = '' }: { text: string; max?: number; class?: string }) {
  const ref = useAutoSize<HTMLSpanElement>(max, 13, text);
  return <span ref={ref} class={`row-title ${cls}`} style={{ fontSize: `${max}px` }}>{text}</span>;
}

/**
 * A row's value in Toman at the hero's three decimals, shrink-to-fit and bounded, so the figure —
 * not the asset's name — is what gives way on a narrow phone. The unit is set inline at .7em.
 */
export function RowAmount({ toman, struck, unit, class: cls = '' }: { toman: number; struck?: boolean; unit?: string; class?: string }) {
  const text = faCompact(toman, 3, true);
  const ref = useAutoSize<HTMLSpanElement>(19, 13, text + (unit ?? ''));
  return (
    <span ref={ref} class={`row-amount figure${struck ? ' struck' : ''} ${cls}`} style={{ maxWidth: unit ? '140px' : '116px', fontSize: '19px' }}>
      {text}{unit && <> <span class="unit">{unit}</span></>}
    </span>
  );
}

// ic_toman.xml, drawn on a 16 box.
const TOMAN_PATH = 'M8.847 14.475c.307-.013.491-.025.607-.035.116-.01.273-.029.35-.041a3.222 3.222 0 0 0 .541-.127 2.022 2.022 0 0 0 .473-.224c.05-.034.126-.1.17-.146a.727.727 0 0 0 .124-.172.733.733 0 0 0 .056-.166c.013-.073.012-.08-.009-.096-.018-.013-.098-.016-.426-.016-.223 0-.462-.005-.532-.012a3.636 3.636 0 0 1-.233-.03 3.155 3.155 0 0 1-.233-.053 2.761 2.761 0 0 1-.27-.093 2.474 2.474 0 0 1-.268-.133 2.664 2.664 0 0 1-.21-.14 2.473 2.473 0 0 1-.206-.19 1.935 1.935 0 0 1-.196-.237 2.02 2.02 0 0 1-.112-.193 2.331 2.331 0 0 1-.145-.427 2.684 2.684 0 0 1-.035-.224 4.075 4.075 0 0 1 0-.631 3.98 3.98 0 0 1 .03-.226 3.794 3.794 0 0 1 .106-.432 2.737 2.737 0 0 1 .186-.419c.03-.052.08-.133.111-.18a2.202 2.202 0 0 1 .334-.384c.056-.05.15-.121.207-.16a2.35 2.35 0 0 1 .222-.125c.064-.03.175-.075.245-.097.071-.023.187-.053.257-.066.07-.013.203-.03.294-.035a3.365 3.365 0 0 1 .664.027l.187.04c.051.014.132.038.18.054a2.41 2.41 0 0 1 .424.208 2.358 2.358 0 0 1 .377.315 3.21 3.21 0 0 1 .277.354 2.617 2.617 0 0 1 .218.42 2.873 2.873 0 0 1 .16.632c.01.074.02.287.024.502.007.339.01.375.029.394.02.02.042.02.32.016l.528-.011a1.59 1.59 0 0 0 .332-.028.654.654 0 0 0 .284-.131.436.436 0 0 0 .078-.112c.036-.074.037-.082.046-.283.005-.113.01-.492.01-.84 0-.635 0-.635.027-.647.014-.006.352-.109.75-.228.398-.12.741-.22.763-.224.038-.006.038-.006.034 1.167-.004 1.028-.007 1.185-.024 1.267a2.216 2.216 0 0 1-.154.467 2.379 2.379 0 0 1-.134.231 2.08 2.08 0 0 1-.19.238 2.31 2.31 0 0 1-.202.186c-.051.04-.133.097-.183.127-.049.03-.134.075-.189.1a2.819 2.819 0 0 1-.214.086 3.38 3.38 0 0 1-.552.131c-.104.015-.273.02-.734.025-.542.006-.602.009-.62.026-.013.014-.024.058-.033.14-.008.065-.023.159-.033.208a2.518 2.518 0 0 1-.27.724c-.03.054-.09.145-.131.201a3.104 3.104 0 0 1-.474.477 3.533 3.533 0 0 1-.436.272 4.09 4.09 0 0 1-.513.212 6.88 6.88 0 0 1-.523.14 5.18 5.18 0 0 1-.315.058 8.27 8.27 0 0 1-1.019.089c-.163.006-.202.004-.207-.008a25.018 25.018 0 0 1-.003-.764l.003-.748ZM1.903 7.996c.007 4.089.009 4.228.028 4.3a.57.57 0 0 0 .054.132.274.274 0 0 0 .096.088.615.615 0 0 0 .306.071c.049 0 .142-.01.207-.023.065-.012.153-.039.195-.058a.513.513 0 0 0 .12-.081.546.546 0 0 0 .085-.132c.022-.047.064-.152.094-.233.03-.081.077-.215.105-.298.028-.083.076-.214.106-.29.03-.078.079-.187.106-.242a3.609 3.609 0 0 1 .363-.565 2.727 2.727 0 0 1 .38-.367c.06-.047.156-.113.214-.147a2.245 2.245 0 0 1 .429-.187c.056-.017.153-.041.216-.054.08-.017.184-.026.348-.03.15-.005.285-.003.378.006.08.008.213.032.298.053.084.021.205.06.269.087.064.027.158.073.21.104a2.5 2.5 0 0 1 .185.123 2.058 2.058 0 0 1 .483.53c.033.054.087.15.12.212a3.207 3.207 0 0 1 .27.771c.011.064.028.18.036.26.01.099.012.24.007.433a4.135 4.135 0 0 1-.026.397 3.122 3.122 0 0 1-.11.45 2.029 2.029 0 0 1-.236.467c-.04.057-.12.15-.174.206-.055.056-.14.13-.187.165a2.154 2.154 0 0 1-.169.11 1.743 1.743 0 0 1-.362.15 2.015 2.015 0 0 1-.216.048c-.06.01-.181.016-.268.015-.087 0-.205-.006-.263-.011a3.403 3.403 0 0 1-.21-.028 4.036 4.036 0 0 1-.52-.135 5.959 5.959 0 0 1-.975-.441 1.387 1.387 0 0 0-.193-.093c-.022-.004-.045.008-.086.042-.031.025-.1.07-.15.1-.052.029-.16.074-.238.1a2.764 2.764 0 0 1-.258.07 3.18 3.18 0 0 1-.28.037 3.413 3.413 0 0 1-.728-.023 2.791 2.791 0 0 1-.274-.066 2.793 2.793 0 0 1-.237-.084 2.337 2.337 0 0 1-.198-.1 2.34 2.34 0 0 1-.188-.122 2.026 2.026 0 0 1-.35-.352 2.503 2.503 0 0 1-.123-.185 2.056 2.056 0 0 1-.1-.204 2.53 2.53 0 0 1-.138-.467c-.018-.09-.02-.335-.024-2.628C.326 7.348.326 7.348.36 7.355c.018.004.324.128.68.277.357.149.697.292.756.318l.108.046Zm3.975 4.984c.058-.002.072-.008.107-.041a.419.419 0 0 0 .07-.108 1.26 1.26 0 0 0 .057-.171.923.923 0 0 0 .025-.254c0-.098-.007-.19-.02-.262a1.424 1.424 0 0 0-.064-.214 1.104 1.104 0 0 0-.24-.368.677.677 0 0 0-.154-.104.416.416 0 0 0-.178-.048.495.495 0 0 0-.166.013.759.759 0 0 0-.14.054.829.829 0 0 0-.123.085c-.032.027-.087.09-.124.139-.037.05-.093.144-.126.21a2.99 2.99 0 0 0-.184.513c-.006.035-.002.048.02.07.015.014.079.06.141.101.063.042.186.11.275.15.088.04.24.1.336.133.096.033.23.07.298.082.067.013.152.022.19.02Zm4.963-1.049c.31.002.325 0 .347-.022.022-.021.024-.039.024-.219 0-.107-.006-.265-.012-.35a1.914 1.914 0 0 0-.04-.264 1.17 1.17 0 0 0-.085-.22.69.69 0 0 0-.128-.181.568.568 0 0 0-.158-.114.833.833 0 0 0-.147-.055.814.814 0 0 0-.324.005.627.627 0 0 0-.304.176.594.594 0 0 0-.137.194c-.03.061-.063.143-.073.181-.01.039-.022.13-.026.205-.004.077-.001.166.006.209a.489.489 0 0 0 .138.27c.03.026.091.066.137.088.046.022.118.048.16.059.042.01.126.023.187.028.06.005.257.01.435.01Zm3.477-3.424c-.02.014-.045-.007-.315-.27l-.47-.455c-.17-.163-.176-.172-.16-.196.01-.014.214-.222.455-.463.24-.24.446-.438.457-.438.01 0 .23.203.488.451.257.248.468.458.468.467 0 .008-.203.212-.45.452-.248.24-.46.443-.473.452Zm-2.02.001c-.015 0-.2-.172-.48-.445-.289-.283-.454-.453-.452-.465.002-.01.205-.219.45-.463.27-.268.457-.444.47-.444.013 0 .129.103.274.242l.47.453c.204.197.216.211.2.234-.008.013-.217.218-.463.456-.272.263-.456.432-.47.432ZM5.46 1.362h1.576l.005 1.396c.002.912-.001 1.502-.01 1.7-.007.168-.023.37-.035.45-.012.081-.038.21-.057.288a2.947 2.947 0 0 1-.17.492c-.03.06-.08.154-.112.208a2.968 2.968 0 0 1-.13.193 2.378 2.378 0 0 1-.153.182c-.047.05-.132.13-.19.178-.058.049-.16.125-.228.17a3.196 3.196 0 0 1-.286.16c-.09.044-.224.1-.298.126a4.928 4.928 0 0 1-.3.089 4.861 4.861 0 0 1-.61.111c-.062.007-.393.015-.742.017-.347.002-.678.002-.736 0a4.168 4.168 0 0 1-.636-.075 3.606 3.606 0 0 1-.845-.289 3.083 3.083 0 0 1-.44-.275 3.118 3.118 0 0 1-.394-.371 3.738 3.738 0 0 1-.137-.169 2.733 2.733 0 0 1-.368-.725 3.621 3.621 0 0 1-.083-.283A3.463 3.463 0 0 1 .03 4.66a4.172 4.172 0 0 1-.018-.75c.008-.121.026-.305.041-.41a5.468 5.468 0 0 1 .153-.698 9.146 9.146 0 0 1 .258-.748c.042-.104.111-.265.153-.357.073-.158.08-.168.107-.163.017.002.314.13.66.283.348.153.66.292.694.308.055.026.062.033.057.06a1.406 1.406 0 0 1-.07.17 7.84 7.84 0 0 0-.245.614 5.224 5.224 0 0 0-.143.503 3.696 3.696 0 0 0-.084.624c-.005.118-.002.23.007.307a1.419 1.419 0 0 0 .116.44c.026.055.074.14.106.187.033.047.095.12.139.162.043.042.118.102.166.133.048.031.13.077.18.1a1.835 1.835 0 0 0 .509.141c.16.025.203.026.77.026.505 0 .628-.003.765-.02.09-.012.22-.034.29-.05a1.26 1.26 0 0 0 .401-.16.935.935 0 0 0 .141-.119.853.853 0 0 0 .183-.323c.016-.048.041-.146.055-.216.026-.128.026-.134.033-1.735l.007-1.607Zm-1.696.59c-.008 0-.235-.216-.505-.48-.288-.283-.491-.49-.491-.502 0-.012.194-.21.473-.48.26-.254.483-.468.496-.476.02-.014.03-.007.11.064.05.044.279.263.51.487.231.224.419.413.416.42-.002.008-.227.228-.5.49-.272.262-.501.476-.51.476Z';
function TomanMark({ size }: { size: number }) {
  return (
    <svg width={size} height={size} viewBox="0 0 16 16" aria-hidden="true" style={{ display: 'block', color: 'var(--primary)' }}>
      <path fill="currentColor" fill-rule="evenodd" d={TOMAN_PATH} />
    </svg>
  );
}

// Tinted by kind: it only ever shows behind the fallbacks, where eleven grey discs were hardest to scan.
const TINT: Record<Kind, [bg: string, ink: string]> = {
  CASH: ['var(--primary-container)', 'var(--on-primary-container)'],
  FIAT: ['var(--tertiary-container)', 'var(--on-tertiary-container)'],
  PROPERTY: ['var(--tertiary-container)', 'var(--on-tertiary-container)'],
  GOLD: ['var(--secondary-container)', 'var(--on-secondary-container)'],
  SILVER: ['var(--secondary-container)', 'var(--on-secondary-container)'],
  COIN: ['var(--secondary-container)', 'var(--on-secondary-container)'],
  CRYPTO: ['var(--surface-variant)', 'var(--on-surface-variant)'],
  STOCK: ['var(--surface-variant)', 'var(--on-surface-variant)'],
};

/**
 * A real logo where one exists, a drawn mark for the fixed assets, the ticker as a last resort. The
 * letters sit underneath until the logo has faded in, so a slow network shows a badge, not a hole.
 * `network` marks which chain the coin sits on, in the bottom-end corner.
 */
export function AssetIcon({ type, size = 44, network }: { type: AssetType; size?: number; network?: string | null }) {
  const [loaded, setLoaded] = useState(false);
  const [broken, setBroken] = useState(false);
  useEffect(() => { setLoaded(false); setBroken(false); }, [type.iconUrl]);
  const [bg, ink] = TINT[type.kind];
  const glyph = assetGlyph(type);
  // Three letters at 12, never four at 9: until the logo loads, this is the coin's only identity.
  const letters = <span class="letters">{type.id.slice(0, 3).toUpperCase()}</span>;
  let inner: ComponentChildren;
  if (type.id === TOMAN_ID) inner = <TomanMark size={size * 0.52} />;
  else if (type.iconUrl && !broken) {
    inner = (
      <>
        {!loaded && letters}
        <img src={type.iconUrl} alt="" width={size * 0.62} height={size * 0.62} decoding="async" loading="lazy"
          // The letters leave once the fade has landed, or the disc shows the hole they cover.
          onLoad={() => setTimeout(() => setLoaded(true), 250)} onError={() => setBroken(true)} />
      </>
    );
  } else if (glyph) inner = <AssetGlyph glyph={glyph} size={size * 0.55} color={ink} />;
  else if (type.emoji) inner = <span style={{ fontSize: `${size * 0.52}px`, lineHeight: 1 }}>{type.emoji}</span>;
  else inner = letters;
  return (
    <span class="asset-icon" aria-hidden="true">
      <span class="disc" style={{ width: `${size}px`, height: `${size}px`, background: bg }}>{inner}</span>
      {network && <span class="chain"><WalletNetworkLogo network={network} size={size * 0.36} /></span>}
    </span>
  );
}

/**
 * Label and switch as one named control, the words the hit target too — the row the edit sheet
 * and the bank card share. The drawn switch is the kit's, hidden from the tree: the row speaks.
 */
function ToggleRow({ title, sub, checked, onChange }: { title: string; sub?: string; checked: boolean; onChange: (on: boolean) => void }) {
  return (
    <button type="button" role="switch" aria-checked={checked} class="toggle-row" onClick={() => onChange(!checked)}>
      <span class="grow"><span>{title}</span>{sub && <span>{sub}</span>}</span>
      <span class="switch" aria-hidden="true" aria-checked={checked} />
    </button>
  );
}

function Btn({ label, onClick, disabled, muted, strong, danger }: {
  label: ComponentChildren; onClick: () => void; disabled?: boolean; muted?: boolean; strong?: boolean; danger?: boolean;
}) {
  const cls = `text-btn${muted ? ' muted' : ''}${strong ? ' strong' : ''}${danger ? ' danger' : ''}`;
  return <button type="button" class={cls} disabled={disabled} onClick={onClick}>{label}</button>;
}

/** `remember(keys) { … }` for a piece of state: reseeded whenever one of `keys` changes. */
function useSeeded<T>(seed: () => T, keys: readonly unknown[]): [T, (v: T) => void] {
  const [box, set] = useState(() => ({ keys, value: seed() }));
  if (box.keys.length !== keys.length || box.keys.some((k, i) => k !== keys[i])) {
    box.keys = keys;
    box.value = seed();
  }
  return [box.value, (value: T) => set({ keys: box.keys, value })];
}

// ---- PickTypeSheet ----------------------------------------------------------------------------

/** Kinds too numerous to list, and what to call them when saying so: a first handful, then search. */
const BROWSABLE_BY_SEARCH: Partial<Record<Kind, string>> = { CRYPTO: 'رمزارزها', STOCK: 'نمادها' };

function PickTypeSheet() {
  useData();
  const [query, setQuery] = useState('');
  const q = query.trim();
  const rates = pref('rates');
  const all = useMemo(() => catalog(rates?.coins ?? []), [rates]);
  // How many she already holds, not merely whether — picking again adds a second one.
  const already = new Map<string, number>();
  for (const h of pref('holdings')) already.set(h.typeId, (already.get(h.typeId) ?? 0) + 1);

  const sections: Array<{ kind: Kind | null; items: AssetType[] }> = q
    ? [{ kind: null, items: all.filter((t) => matchesSearch(t, q)) }]
    // Capped, or 250 tickers bury the assets she actually owns.
    : KINDS.map((kind) => {
      const items = all.filter((t) => t.kind === kind);
      return { kind, items: BROWSABLE_BY_SEARCH[kind] ? items.slice(0, 12) : items };
    });

  // A fresh key every time: picking تتر again opens an empty sheet for a second one.
  const pick = (id: string) => openSheet('editHolding', { typeId: id, holdingKey: newHoldingId() });

  return (
    <Sheet label="چی می‌خوای اضافه کنی؟">
      <div class="pick-body home-sheet">
        <div class="pick-head">
          <SheetTitle>چی می‌خوای اضافه کنی؟</SheetTitle>
          <label class="field">
            <input value={query} placeholder="جستجو..." aria-label="جستجو" type="text" enterKeyHint="search" autocomplete="off"
              onInput={(e) => setQuery((e.currentTarget as HTMLInputElement).value)} />
          </label>
        </div>
        {q && sections.every((s) => !s.items.length) && (
          // bidi(): a mixed query like "gold 18" would otherwise reorder inside the guillemets.
          <p class="pick-none">{`برای «${bidi(q)}» چیزی پیدا نشد. اسم انگلیسی یا نمادش رو هم امتحان کن.`}</p>
        )}
        {sections.map(({ kind, items }) => items.length > 0 && (
          <section key={kind ?? 'found'}>
            {kind && <h3 class="pick-kind">{KIND_FA[kind]}</h3>}
            {items.map((t) => <PickRow key={t.id} type={t} already={already.get(t.id) ?? 0} onClick={() => pick(t.id)} />)}
            {/* The cue that more exist sits under the rows it discloses. */}
            {!q && kind && BROWSABLE_BY_SEARCH[kind] && <p class="pick-more">{`بقیه ${BROWSABLE_BY_SEARCH[kind]} رو می‌خوای؟ اسمش رو جستجو کن.`}</p>}
          </section>
        ))}
        <div style={{ height: 'var(--xxl)' }} />
      </div>
    </Sheet>
  );
}

function PickRow({ type, already, onClick }: { type: AssetType; already: number; onClick: () => void }) {
  // The ticker tells one coin from another where the Persian names blur; for a نماد, the company.
  const subtitle = type.kind === 'CRYPTO' ? type.id.toUpperCase() : type.kind === 'STOCK' ? type.en : '';
  return (
    <button type="button" class="pick-row" onClick={onClick}>
      <AssetIcon type={type} size={40} />
      <span class="grow">
        <span>{type.fa}</span>
        {subtitle.trim() && <span>{subtitle}</span>}
      </span>
      {already > 0 && <span class="held">{already === 1 ? 'اضافه شده' : `${faNumber(already)} تا اضافه شده`}</span>}
    </button>
  );
}

// ---- EditSheet --------------------------------------------------------------------------------

type AmountSource = 'WALLET' | 'MANUAL';

/**
 * Nine networks in two columns, logo leading from a fixed inset: the column of marks is the index.
 * Chosen is a fill and a ring and a weight, because a fill alone is colour doing structure's work.
 */
function WalletNetworkChoice({ options, selected, enabled, onSelect }: {
  options: WalletOption[]; selected: string; enabled: boolean; onSelect: (network: string) => void;
}) {
  return (
    <div class="networks" role="radiogroup" aria-disabled={!enabled}>
      {options.map((o) => (
        <button type="button" role="radio" aria-checked={o.network === selected} disabled={!enabled} onClick={() => onSelect(o.network)}>
          <WalletNetworkLogo network={o.network} />
          <span>{o.networkFa}</span>
        </button>
      ))}
    </div>
  );
}

function EditHoldingSheet(props: { typeId: string; holdingKey: string }) {
  useData();
  const status = useWealthStatus();
  const { typeId, holdingKey: key } = props;
  const holding = pref('holdings').find((h) => holdingKey(h) === key);
  const type = resolveType(typeId, pref('rates')?.coins ?? []);
  const effective = currentEffective();
  const rate = rateIn(effective, typeId);
  const isOverridden = Object.hasOwn(pref('overrides'), typeId);
  const excluded = holding?.excluded ?? false;
  const walletBusy = status.refreshingWallets.has(key);
  const walletError = status.walletErrors.get(key) ?? null;

  const current = holding?.amount ?? null;
  const linkedWallet = holding?.wallet ?? null;
  const walletOptions: WalletOption[] = [];
  for (const o of [...type.wallets, ...(linkedWallet ? [{ network: linkedWallet.network, networkFa: linkedWallet.networkFa, contract: linkedWallet.contract }] : [])]) {
    if (!walletOptions.some((w) => w.network === o.network)) walletOptions.push(o);
  }
  const networksKey = walletOptions.map((w) => w.network).join();
  const startsWithWallet = type.kind === 'CRYPTO' && walletOptions.length > 0 && (linkedWallet != null || holding == null);

  const [source, setSource] = useSeeded<AmountSource>(() => (startsWithWallet ? 'WALLET' : 'MANUAL'),
    [linkedWallet != null, walletOptions.length > 0]);
  const [text, setText] = useState(() => (current != null ? trimNumber(current, type.dec) : ''));
  const [rateText, setRateText] = useState(() => (rate != null ? trimNumber(rate, 0) : ''));
  const [editingRate, setEditingRate] = useState(false);
  const [labelText, setLabelText] = useState(() => holding?.label ?? '');
  const [naming, setNaming] = useState(() => holding == null && type.kind === 'PROPERTY');
  const [nameFocusWanted, setNameFocusWanted] = useState(false);
  const [adjusting, setAdjusting] = useState(false);
  const [manualSubmitted, setManualSubmitted] = useState(false);
  const [walletAddress, setWalletAddress] = useSeeded(() => linkedWallet?.address ?? '', [linkedWallet?.address]);
  const [selectedNetwork, setSelectedNetwork] = useSeeded(() => linkedWallet?.network ?? walletOptions[0]?.network ?? '',
    [linkedWallet?.network, networksKey]);
  const [localWalletError, setLocalWalletError] = useState<string | null>(null);

  const manualAmount = parseAmount(text);
  const selectedWallet = walletOptions.find((w) => w.network === selectedNetwork) ?? null;
  const linkedSelection = linkedWallet != null && linkedWallet.network === selectedNetwork &&
    linkedWallet.address === walletAddress.trim() && linkedWallet.contract === selectedWallet?.contract;
  const amount = source === 'MANUAL' ? manualAmount : linkedSelection ? current : null;
  const typedRate = (() => { const r = parseAmount(rateText); return r != null && r > 0 ? r : null; })();
  // While she types a rate, the «یعنی …» preview follows it, so the override's effect shows first.
  const previewRate = editingRate ? typedRate ?? rate : rate;
  const valued = valuedInToman(type);
  const unit = bidi(type.unitFa);

  // The name she typed, written to the row — from ذخیره, ذخیره اسم, and from closing the sheet
  // any way at all (scrim, Esc, the back gesture): swiping a name away is how anyone gives one.
  const latestLabel = useRef(labelText);
  latestLabel.current = labelText;
  const nameIt = () => {
    const row = pref('holdings').find((h) => holdingKey(h) === key);
    // While adding there is no row until ذخیره makes one; that path calls this itself, after.
    if (row && latestLabel.current.trim() !== row.label) setLabel(key, latestLabel.current);
  };
  // Closing ends the sheet, not the fetch: a late answer must not close whatever sheet is open by then.
  const open = useRef(true);
  useEffect(() => () => { open.current = false; nameIt(); }, []);

  const saveLabel = () => { setLabel(key, labelText); setNaming(false); };

  const save = () => {
    if (source === 'MANUAL') {
      setManualSubmitted(true);
      if (manualAmount != null) {
        // After the amount, never before: a name is written onto a row by its key.
        setHolding(key, typeId, manualAmount);
        nameIt();
        closeSheet();
      } else document.querySelector<HTMLInputElement>('.edit-amount input')?.focus();
      return;
    }
    if (!selectedWallet) return;
    if (!walletAddress.trim()) setLocalWalletError('آدرس عمومی کیف پول رو وارد کن.');
    else if (!isWalletAddressFormatValid(selectedWallet.network, walletAddress)) setLocalWalletError('این آدرس با شبکه انتخاب‌شده جور نیست.');
    else {
      void connectWallet(key, typeId, selectedWallet, walletAddress.trim()).then((ok) => { if (ok) { nameIt(); if (open.current) closeSheet(); } });
      return;
    }
    document.querySelector<HTMLInputElement>('.edit-address input')?.focus();
  };

  const remove = () => {
    // The whole Holding, captured by the removal — amount, label, wallet, set-aside flag.
    const gone = removeHolding(key);
    if (gone) showNotice('دارایی پاک شد', { label: 'برگردون', run: () => reinstateHolding(gone) });
    closeSheet();
  };

  const amountInvalid = manualAmount == null && (text.trim() !== '' || manualSubmitted);
  const balanceCardShown = source === 'WALLET' && linkedSelection && current != null;

  return (
    <Sheet label={holding ? holding.label.trim() || type.fa : type.fa}>
      <div class="home-sheet">
        <div class="edit-head">
          <AssetIcon type={type} size={42} />
          <div class="grow">
            <span>{holding ? (holding.label.trim() ? holding.label : type.fa) : type.fa}</span>
            {holding?.label.trim() && <span>{type.fa}</span>}
          </div>
        </div>

        {/* Named, not renamed: the label on this holding of it — «تتر شخصی» beside «تتر مشترک». */}
        {!naming ? (
          <div style={{ marginTop: 'var(--s)' }}>
            <Btn label={labelText.trim() ? 'تغییر اسم' : 'اسم دلخواه بذار'} onClick={() => { setNaming(true); setNameFocusWanted(true); }} />
          </div>
        ) : (
          <>
            <SheetLabel>اسم دلخواه</SheetLabel>
            <div class="bare-label">
              <TextField label="اسم دلخواه" value={labelText} maxLength={32} autoFocus={nameFocusWanted}
                placeholder={type.kind === 'PROPERTY' ? `مثلاً ${type.fa} دوم` : `مثلاً ${type.fa} شخصی`}
                onInput={(v) => setLabelText(v.slice(0, 32))} onEnter={holding ? saveLabel : undefined} />
            </div>
            {holding && (
              <div class="btn-row">
                <Btn label="ذخیره اسم" onClick={saveLabel} />
                {holding.label.trim() && <Btn label="اسم اصلی" onClick={() => { setLabelText(''); setLabel(key, ''); setNaming(false); }} />}
                <span class="spacer" />
                <Btn label="بستن" muted onClick={() => { setLabelText(holding.label); setNaming(false); }} />
              </div>
            )}
          </>
        )}

        {type.kind === 'CRYPTO' && (
          <>
            <SheetLabel>موجودی رو چطور وارد می‌کنی؟</SheetLabel>
            {walletOptions.length > 0 ? (
              <>
                <SegmentedChoice<AmountSource> options={['WALLET', 'MANUAL']} selected={source}
                  label={(s) => (s === 'WALLET' ? 'خودکار' : 'دستی')} enabled={() => !walletBusy}
                  onSelect={(s) => { setSource(s); setAdjusting(false); setLocalWalletError(null); clearWalletError(key); }} />
                <p class="note" style={{ paddingTop: 'var(--s)' }}>
                  {source === 'WALLET' ? 'موجودی از آدرس عمومی کیف پول خونده می‌شه.' : 'مقدار رو خودت وارد می‌کنی.'}
                </p>
              </>
            ) : (
              <p class="edit-box">برای این رمزارز، شبکه‌ای برای خوندن خودکار پیدا نشد. مقدار رو دستی وارد کن.</p>
            )}
          </>
        )}

        {source === 'MANUAL' ? (
          <>
            {/* A house is a valuation she gives, not an amount she counts. */}
            <SheetLabel>{type.kind === 'PROPERTY' ? 'به نظرت چند تومن می‌ارزه؟' : `چقدر ${unit} داری؟`}</SheetLabel>
            <div class="bare-label big-amount edit-amount">
              <AmountField label={type.kind === 'PROPERTY' ? 'به نظرت چند تومن می‌ارزه؟' : `چقدر ${type.unitFa} داری؟`}
                raw={text} decimals={9} words={false} autoFocus={current == null}
                onRaw={(v) => { setText(v); setManualSubmitted(false); }} onEnter={save}
                error={amountInvalid ? (text.trim() ? 'این عدد قابل خوندن نیست. فقط عدد وارد کن.' : 'مقدار دارایی رو وارد کن.') : null} />
            </div>
            {/* The figure she just typed, in words — the guard against an extra zero. */}
            {manualAmount != null && Number.isInteger(manualAmount) && manualAmount >= 1000 && (
              <p class="edit-words">{`${faWords(manualAmount)} ${unit}`}</p>
            )}
            {current != null && (
              <Adjust dec={type.dec} unitFa={type.unitFa} base={amount ?? current} open={adjusting} onOpen={setAdjusting}
                onApply={(next) => setText(trimNumber(next, type.dec))} />
            )}
          </>
        ) : (
          <>
            <SheetLabel>شبکه رمزارز</SheetLabel>
            <WalletNetworkChoice options={walletOptions} selected={selectedNetwork} enabled={!walletBusy}
              onSelect={(n) => { if (selectedNetwork !== n) setWalletAddress(''); setSelectedNetwork(n); setLocalWalletError(null); clearWalletError(key); }} />
            <SheetLabel>آدرس عمومی کیف پول</SheetLabel>
            <div class="edit-address">
              <label class={`field ltr${localWalletError ? ' error' : ''}`}>
                <input value={walletAddress} dir="ltr" disabled={walletBusy} aria-label="آدرس عمومی کیف پول" aria-invalid={!!localWalletError}
                  autocomplete="off" autocapitalize="off" spellcheck={false} maxLength={128}
                  onInput={(e) => { setWalletAddress((e.currentTarget as HTMLInputElement).value.slice(0, 128)); setLocalWalletError(null); clearWalletError(key); }}
                  onKeyDown={(e) => { if (e.key === 'Enter') { e.preventDefault(); save(); } }} />
              </label>
              {localWalletError && <div class="field-support error">{localWalletError}</div>}
            </div>
            <p class="note" style={{ marginTop: 'var(--xs)' }}>فقط آدرس عمومی رو وارد کن. عبارت بازیابی یا کلید خصوصی رو هیچ‌وقت وارد نکن.</p>
            <p class="note small">برای خوندن موجودی، آدرس به سرویس عمومی همون شبکه فرستاده می‌شه.</p>
            {linkedSelection && current != null && linkedWallet ? (
              <WalletBalanceCard held={`${faHeld(current, type.dec)} ${unit}`}
                toman={previewRate != null ? current * previewRate : null}
                status={walletBusy ? 'در حال گرفتن موجودی' : walletError ?? `آخرین بار: ${faAgo(linkedWallet.updatedAt, Date.now())}`}
                error={!walletBusy && walletError != null} />
            ) : walletError != null && <p class="err" aria-live="polite">{walletError}</p>}
          </>
        )}

        {/* Not when the balance card is up: it already carries the Toman line. */}
        {amount != null && previewRate != null && !valued && !balanceCardShown && (
          <div class="edit-card"><FitLine text={`یعنی ${faCompact(amount * previewRate, 3, true)} تومان`} max={18} min={14} weight={700} /></div>
        )}

        {!valued && (!editingRate ? (
          <div class="edit-rate">
            <span>{rate == null ? 'نرخ پیدا نشد' : `هر ${unit}: ${faNumber(rate)} تومان${isOverridden ? '  (دستی وارد شده)' : ''}`}</span>
            <Btn label="تغییر نرخ" onClick={() => setEditingRate(true)} />
          </div>
        ) : (
          <div style={{ marginTop: 'var(--m)' }}>
            <p class="edit-caption" style={{ paddingTop: 0, marginBottom: 'var(--xs)' }}>{`هر ${unit} چند تومان؟`}</p>
            <div class="bare-label">
              <AmountField label={`هر ${type.unitFa} چند تومان؟`} raw={rateText} onRaw={setRateText} decimals={9} words={false} autoFocus
                onEnter={() => { setOverride(typeId, typedRate); setEditingRate(false); }} />
            </div>
            <div class="btn-row">
              <Btn label="ذخیره نرخ" onClick={() => { setOverride(typeId, typedRate); setEditingRate(false); }} />
              {isOverridden && <Btn label="برگشت به نرخ خودکار" onClick={() => { setOverride(typeId, null); setRateText(''); setEditingRate(false); }} />}
            </div>
          </div>
        ))}

        {current != null && (
          <div style={{ marginTop: 'var(--m)' }}>
            <ToggleRow title="توی جمع حساب بشه" sub="اگه فعلاً نمی‌خوای توی جمع بیاد، خاموشش کن." checked={!excluded}
              onChange={(on) => setExcluded(key, !on)} />
          </div>
        )}

        <button type="button" class="pill primary block save-btn" onClick={save}
          disabled={!(source === 'MANUAL' || (!walletBusy && selectedWallet != null))}>
          {source === 'WALLET' && walletBusy && <span class="ring" aria-hidden="true" />}
          {source === 'MANUAL' && linkedWallet ? 'ذخیره مقدار دستی'
            : source === 'MANUAL' ? 'ذخیره' : linkedSelection ? 'گرفتن دوباره موجودی' : 'چک و ذخیره'}
        </button>

        {/* One stray tap must not erase a holding: the first only asks. */}
        {current != null && <SheetDelete label="حذف این دارایی" onConfirmed={remove} />}
        <div style={{ height: 'var(--l)' }} />
      </div>
    </Sheet>
  );
}

/** One line that shrinks rather than wraps (the sheet's «یعنی …» and the balance card's figures). */
function FitLine({ text, max, min, weight, class: cls = '' }: { text: string; max: number; min: number; weight: number; class?: string }) {
  const ref = useAutoSize<HTMLSpanElement>(max, min, text);
  return <span ref={ref} class={`fit figure ${cls}`} style={{ fontSize: `${max}px`, fontWeight: weight }}>{text}</span>;
}

function WalletBalanceCard({ held, toman, status, error }: { held: string; toman: number | null; status: string; error: boolean }) {
  return (
    <div class="edit-card">
      <p style={{ fontSize: '13px', fontWeight: 700 }}>موجودی فعلی</p>
      {/* faHeld, not full precision: the row outside says the same number the same length. */}
      <FitLine text={held} max={30} min={18} weight={800} />
      {toman != null && <FitLine class="sub" text={`≈ ${faCompact(toman, 3, true)} تومان`} max={17} min={13} weight={600} />}
      <p class={`status${error ? ' error' : ''}`} aria-live={error ? 'polite' : undefined}>{status}</p>
    </div>
  );
}

/**
 * Received some, spent some: she types the change and the amount above becomes the new total.
 * A delta finer than the asset's precision is refused, never rounded — half a سکه rounded to a
 * whole one is money invented.
 */
function Adjust({ dec, unitFa, base, open, onOpen, onApply }: {
  dec: number; unitFa: string; base: number; open: boolean; onOpen: (open: boolean) => void; onApply: (next: number) => void;
}) {
  const [deltaText, setDeltaText] = useState('');
  if (!open) return <Btn label="اضافه یا کم کردن" onClick={() => onOpen(true)} />;
  const parsed = parseAmount(deltaText);
  const typedDelta = parsed != null && parsed > 0 ? parsed : null;
  const delta = typedDelta != null && parseAmount(trimNumber(typedDelta, dec)) === typedDelta ? typedDelta : null;
  const apply = (next: number) => { onApply(next); setDeltaText(''); onOpen(false); };
  return (
    <>
      <p class="edit-caption" style={{ marginBottom: 'var(--xs)' }}>مقدار تغییر رو وارد کن</p>
      <div class="bare-label">
        <AmountField label={`مقدار تغییر ${unitFa}`} raw={deltaText} onRaw={setDeltaText} decimals={9} words={false} autoFocus
          error={typedDelta != null && delta == null ? (dec === 0 ? 'فقط عدد کامل وارد کن.' : `حداکثر ${faNumber(dec)} رقم اعشار وارد کن.`) : null} />
      </div>
      {/* Air between the two opposite intents: an edge mis-tap here flips a money adjustment's sign. */}
      <div class="btn-row">
        <Btn label="＋ اضافه کن" strong disabled={delta == null} onClick={() => delta != null && apply(base + delta)} />
        {/* A holding cannot go below nothing: taking out more than is there is a typo. */}
        <Btn label="− کم کن" strong disabled={delta == null || base - delta < 0} onClick={() => delta != null && apply(base - delta)} />
        <span class="spacer" />
        <Btn label="بستن" muted onClick={() => { setDeltaText(''); onOpen(false); }} />
      </div>
    </>
  );
}

// ---- BankSheet --------------------------------------------------------------------------------

/**
 * Every tracked account, one card each, with the raw figure and when it last moved — the audit
 * surface: a balance read out of a message is only safe to act on if it can be corrected here.
 */
function BankSheet() {
  const view = useLedger();
  const now = useNow();
  const [fixing, setFixing] = useState<string | null>(null);
  return (
    <Sheet label="حساب‌های بانکی">
      <div class="home-sheet">
        <SheetTitle>حساب‌های بانکی</SheetTitle>
        <p class="muted" style={{ fontSize: '13px', lineHeight: '20px', marginTop: 'var(--xs)' }}>
          موجودی این حساب‌ها از پیامک بانک‌ها خونده می‌شه. اگه بانکی رو خاموش کنی، موجودیش توی جمع نمیاد.
        </p>
        <div class="bank-list">
          {view.bankAccounts.map((acc) => (
            <BankAccountRow key={acc.bank} account={acc} now={now} fixing={fixing === acc.bank}
              onFix={() => setFixing(fixing === acc.bank ? null : acc.bank)}
              onAnchor={(toman) => { setBankBalance(acc.bank, Math.round(toman * 10)); recordSnapshot(); setFixing(null); }}
              onToggle={(on) => {
                // What the total counts changes without money moving: the history rebases, not steps.
                const before = currentTotals();
                if (on === acc.disabled) toggleBankDisabled(acc.bank);
                recordSnapshot(before);
              }}
              onForget={() => { forgetAccount(acc.bank); recordSnapshot(); }} />
          ))}
        </div>
      </div>
    </Sheet>
  );
}

function BankAccountRow({ account, now, fixing, onFix, onAnchor, onToggle, onForget }: {
  account: BankAccountView; now: number; fixing: boolean; onFix: () => void; onAnchor: (toman: number) => void;
  onToggle: (on: boolean) => void; onForget: () => void;
}) {
  const off = account.disabled;
  const [draft, setDraft] = useState('');
  const [sure, setSure] = useState(false);
  const parsed = parseAmount(draft);
  const caption = [account.mask.trim() ? bidi(account.mask) : null, faAgo(account.updatedAt, now), off ? 'خاموش' : null]
    .filter((s): s is string => s != null).join('  •  ');
  return (
    <div class="bank-card">
      {/* Off dims by colour, not a blanket alpha, which took the caption below every contrast floor. */}
      <div class="top">
        <BankLogo bank={account.bank} />
        <div class="grow">
          <RowTitle text={account.bankFa} max={17} class={off ? 'off' : ''} />
          <span>{caption}</span>
        </div>
        <RowAmount toman={tomanOf(account.balanceRial)} struck={off} unit="تومان" class={off ? 'off' : ''} />
      </div>
      {/* Only for an anchored account: an unanchored one is never counted, and a switch saying
          "counted" above the red line saying it is not would be the card contradicting itself. */}
      {account.anchored && <ToggleRow title="توی جمع حساب بشه" checked={!off} onChange={onToggle} />}
      {!account.trusted && (
        <p class="doubt">
          {!account.anchored
            ? 'این عدد فقط جمع تراکنش‌هاست، نه موجودی واقعی، و توی جمع حساب نشده. موجودی حسابت رو وارد کن تا توی جمع بیاد.'
            : 'این عدد از پیامکی خونده شده که از درست بودنش مطمئن نیستیم. اگه اشتباهه، اصلاحش کن.'}
        </p>
      )}
      {fixing && (
        <>
          {/* A label that stays: while she types a balance, the unit must still be on screen. */}
          <p class="edit-caption" style={{ marginBottom: 'var(--xs)' }}>موجودی به تومان</p>
          <div class="bare-label">
            <AmountField label="موجودی به تومان" raw={draft} onRaw={setDraft} decimals={9} words={false} autoFocus
              onEnter={() => { if (parsed != null) onAnchor(parsed); }}
              error={draft.trim() && parsed == null ? 'این عدد قابل خوندن نیست. فقط عدد وارد کن.' : null} />
          </div>
        </>
      )}
      <div class="btn-row">
        {fixing
          ? <Btn label="ذخیره موجودی" disabled={parsed == null} onClick={() => { if (parsed != null) onAnchor(parsed); }} />
          : <Btn label="اصلاح موجودی" onClick={onFix} />}
        <span class="spacer" />
        {/* Two taps: a balance she anchored by hand is a number nothing can rebuild. */}
        <Btn danger strong={sure} onClick={() => (sure ? onForget() : setSure(true))}
          label={<span aria-live="polite">{sure ? 'موجودیش از صفر شروع می‌شه؛ برای حذف دوباره بزن' : 'حذف حساب'}</span>} />
      </div>
    </div>
  );
}

/** «همین الان» must not still say that half an hour later: a slow tick keeps every faAgo honest. */
export function useNow(every = 30_000): number {
  const [now, setNow] = useState(Date.now());
  useEffect(() => {
    const tick = setInterval(() => setNow(Date.now()), every);
    return () => clearInterval(tick);
  }, [every]);
  return now;
}

registerSheet('pickType', PickTypeSheet);
registerSheet('editHolding', EditHoldingSheet);
registerSheet('bank', BankSheet);

