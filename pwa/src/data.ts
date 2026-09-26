/**
 * Data.kt's wealth half and AppVm's holding/rate/wallet actions, one to one. The SMS fold stays
 * with the ledger; where Kotlin summed `bankTotal(bankAccounts, disabledBanks)` this takes the
 * figure as `bankToman` — null when there are no bank accounts to show at all.
 *
 * Network: `/rates` and `/wallet-balance` are fetched same-origin (the sync Worker proxies both;
 * the page's CSP is connect-src 'self'), and coin logos are rewritten to its `/coin-icon` proxy.
 * There is no APK update note in a browser, so the payload's `latest` is dropped unread.
 */
import { useEffect, useState } from 'preact/hooks';
import { BANK_ID, TOMAN_BY_DEFINITION, TOMAN_ID, catalogOrdered, resolveType } from './catalog';
import type { Stock } from './catalog';
import { DAY_MS } from './jalali';
import { holdingKey } from './model';
import type { Coin, Holding, Rates, WalletLink, WalletOption } from './model';
import { BANKS, MAX_PLAUSIBLE_RIAL, bankFa } from './sms';
import { batch, pref, setPref } from './state';

// ---- pure ------------------------------------------------------------------------------------

export interface Totals { toman: number; missing: string[] }
export interface Change { delta: number; percent: number | null; sinceDay: number }
export interface DayRecord { history: Record<string, number>; rates: Record<string, number> }
export interface WalletBalance { amount: number; updatedAt: number }
export interface AssetShareItem { name: string; toman: number }
/** One bank balance as the family share needs it: Sms.kt BankAccount's fields, balance in Toman. */
export interface BankShare { bank: string; balance: number; anchored: boolean }

export const EMPTY_RATES: Rates = { updatedAt: 0, toman: {}, coins: [] };
export const HISTORY_KEEP_DAYS = 400;
export const WALLET_SNAPSHOT_MAX_AGE_MS = 10 * 60_000;
const MAX_FUTURE_CLOCK_SKEW_MS = 5 * 60_000;

/** A rate map lookup that cannot land on Object.prototype ("constructor" is a legal coin id). */
const rateOf = (rates: Record<string, number>, id: string): number | undefined =>
  Object.hasOwn(rates, id) ? rates[id] : undefined;

/** What to print for a holding: her name for it, never blank. */
export const nameOr = (h: Holding, fallback: string): string => (h.label.trim() ? h.label : fallback);
/** A fresh Holding.id, only ever minted when she adds one. */
export const newHoldingId = (): string => crypto.randomUUID();

/**
 * Anything with no rate is left OUT of the total and named in `missing` — counting it as zero
 * would show a total wrong in the reassuring direction, the worst failure here.
 */
export function computeTotals(holdings: Holding[], rates: Record<string, number>): Totals {
  let sum = 0;
  const missing: string[] = [];
  for (const h of holdings) {
    if (h.excluded) continue; // set aside on purpose — not summed, and not "missing" either
    const rate = rateOf(rates, h.typeId);
    const value = rate === undefined ? undefined : h.amount * rate;
    const next = value === undefined ? undefined : sum + value;
    if (!Number.isFinite(h.amount) || rate === undefined || !Number.isFinite(rate) || rate <= 0 ||
      value === undefined || !Number.isFinite(value) || next === undefined || !Number.isFinite(next)) {
      missing.push(h.typeId);
    } else {
      sum = next;
    }
  }
  return { toman: sum, missing };
}

/** One total per UTC epoch day; the same day converges to its last good total; ≤ 400 days. */
export function recordDay(history: Record<string, number>, epochDay: number, total: number): Record<string, number> {
  const next: Record<string, number> = { ...history, [epochDay]: total };
  const days = Object.keys(next);
  if (days.length <= HISTORY_KEEP_DAYS) return next;
  return Object.fromEntries(
    days.map(Number).sort((a, b) => a - b).slice(-HISTORY_KEEP_DAYS).map((d) => [String(d), next[d]]),
  );
}

/**
 * What the list shows: her holdings, plus one row for every bank account right after her cash,
 * so تومان reads cash, then bank. Never persisted: its amount is not hers to type.
 */
export function listHoldings(holdings: Holding[], bankToman: number | null): Holding[] {
  if (bankToman === null) return holdings;
  const bank: Holding = { typeId: BANK_ID, amount: bankToman, excluded: false, wallet: null, label: '', id: '' };
  const cash = holdings.findIndex((h) => h.typeId === TOMAN_ID);
  return cash < 0 ? [bank, ...holdings] : [...holdings.slice(0, cash + 1), bank, ...holdings.slice(cash + 1)];
}

/**
 * دارایی as shared with the family: her holdings priced with these rates, then one line per bank.
 * The hero total's own rules — set-aside stays home, unpriced is left out rather than shared as
 * zero, a switched-off bank is out — with `familyExcluded` as the family-only veto on top.
 */
export function assetShareItems(
  holdings: Holding[], rates: Record<string, number>, coins: Coin[], stocks: Stock[],
  bankAccounts: BankShare[], disabledBanks: string[], familyExcluded: string[],
): AssetShareItem[] {
  const own = holdings.filter((h) => !h.excluded).flatMap((h) => {
    const rate = rateOf(rates, h.typeId);
    return rate === undefined ? [] : [{ name: nameOr(h, resolveType(h.typeId, coins, stocks).fa), toman: h.amount * rate }];
  });
  const banks = bankAccounts
    .filter((a) => a.anchored && !disabledBanks.includes(a.bank) && !familyExcluded.includes(a.bank))
    .map((a) => ({ name: bankFa(a.bank), toman: a.balance }));
  return safeAssetShareItems([...own, ...banks]);
}

/**
 * Builds before this one sent «بانک » in front of a name that already carries it («بانک بانک سامان»),
 * and a member on such a build keeps sending it. Folded where the row is read, as Kotlin does.
 */
export function undoubledBankName(name: string): string {
  const rest = name.startsWith('بانک ') ? name.slice('بانک '.length) : name;
  return rest !== name && BANKS.some((b) => b.fa === rest) ? rest : name;
}

/**
 * Keeps malformed or overflowing values out of the payload and the receiving ledger. The bound is
 * the ledger's Rial one compared against Toman, exactly as Kotlin compares it.
 */
export function safeAssetShareItems(items: Iterable<AssetShareItem>): AssetShareItem[] {
  const safe: AssetShareItem[] = [];
  let total = 0;
  for (const item of items) {
    if (safe.length === 64) break;
    const next = total + item.toman;
    if (!Number.isFinite(item.toman) || item.toman < 0 || item.toman > MAX_PLAUSIBLE_RIAL ||
      !Number.isFinite(next) || next > MAX_PLAUSIBLE_RIAL) continue;
    safe.push(item);
    total = next;
  }
  return safe;
}

/**
 * Today's history entry, but only a total worth remembering: something on the list, every
 * holding priced, rates younger than a day, every counted wallet read in the last ten minutes.
 * A partial or stale point would draw a crash that never happened. Null: leave history alone.
 */
export function snapshotHistory(
  history: Record<string, number>, list: Holding[], rates: Record<string, number>, ratesUpdatedAt: number, now: number,
): Record<string, number> | null {
  if (!list.length) return null;
  if (now - ratesUpdatedAt > 24 * 60 * 60_000) return null;
  if (list.some((h) => !h.excluded && h.wallet !== null && (
    h.wallet.updatedAt <= 0 || h.wallet.updatedAt > now + MAX_FUTURE_CLOCK_SKEW_MS ||
    now - h.wallet.updatedAt > WALLET_SNAPSHOT_MAX_AGE_MS))) return null;
  const totals = computeTotals(list, rates);
  if (totals.missing.length) return null;
  return recordDay(history, Math.trunc(now / DAY_MS), totals.toman);
}

/**
 * Today's total and today's dollar rate, recorded together or not at all: a rate written on a
 * day the total refused would freeze a closed month against a price already distrusted. No
 * dollar rate leaves the rate history as it was — a missing day reads «no figure», a zero «free».
 */
export function snapshotDay(
  history: Record<string, number>, rateHistory: Record<string, number>, list: Holding[],
  rates: Record<string, number>, ratesUpdatedAt: number, now: number,
): DayRecord | null {
  const next = snapshotHistory(history, list, rates, ratesUpdatedAt, now);
  if (!next) return null;
  const usd = rateOf(rates, 'usd');
  const good = usd !== undefined && usd > 0 && Number.isFinite(usd);
  return { history: next, rates: good ? recordDay(rateHistory, Math.trunc(now / DAY_MS), usd) : rateHistory };
}

/** Due after thirty days, or at once when the file she has holds one-time codes. */
export const backupReminderDue = (enabled: boolean, lastExportAt: number, now: number, holdsCodes = false): boolean =>
  enabled && (holdsCodes || lastExportAt <= 0 || now - lastExportAt >= 30 * DAY_MS);

const sameList = (a: string[], b: string[]): boolean => a.length === b.length && a.every((x, i) => x === b[i]);

/**
 * The history rescaled when what the total *counts* changes without money moving — an asset set
 * aside, a bank switched off — so the chart does not step and every percentage the report quotes
 * stays what it was; it also undoes itself. Refused when the toggle changed what is missing, or
 * either basis is not positive: a zero can never be scaled back.
 */
export function rebaseHistory(history: Record<string, number>, before: Totals, after: Totals): Record<string, number> {
  if (!sameList(before.missing, after.missing)) return history;
  if (before.toman <= 0 || after.toman <= 0) return history;
  const factor = after.toman / before.toman;
  if (!Number.isFinite(factor) || factor === 1) return history;
  return Object.fromEntries(Object.entries(history).map(([day, total]) => [day, total * factor]));
}

/**
 * The change against the newest snapshot at least `windowDays` old, with three days of grace
 * just inside the window; a shorter history is null, never a two-week change sold as a month.
 */
export function changeOver(history: Record<string, number>, today: number, windowDays: number, current: number): Change | null {
  const target = today - windowDays;
  const days = Object.keys(history).map(Number);
  const before = days.filter((d) => d <= target);
  const grace = days.filter((d) => d >= target + 1 && d <= target + 3);
  const day = before.length ? Math.max(...before) : grace.length ? Math.min(...grace) : null;
  if (day === null) return null;
  const base = history[day];
  return { delta: current - base, percent: base > 0 ? (current - base) / base * 100 : null, sinceDay: day };
}

/**
 * Overrides over fetched rates, and rate 1 pinned on top of both for Toman, the bank balances
 * and what she values herself: none of them may be "corrected" to anything else.
 */
export const effectiveRates = (fetched: Rates | null, overrides: Record<string, number>): Record<string, number> =>
  ({ ...fetched?.toman, ...overrides, ...TOMAN_BY_DEFINITION });

/**
 * Fresh prices, but the old catalogue when the new one is empty: when the Worker's name-and-logo
 * source is down it sends prices with no coins, and names and logos do not go stale like a price.
 */
export const mergeRates = (fresh: Rates, cached: Rates | null): Rates =>
  ({ ...fresh, coins: fresh.coins.length ? fresh.coins : cached?.coins ?? [] });

// ---- the payload's trust boundary -----------------------------------------------------------

const MAX_RATES_RESPONSE_BYTES = 2 * 1024 * 1024;
const MAX_WALLET_RESPONSE_BYTES = 64 * 1024;
const MAX_RATE_ENTRIES = 10_000;
const MAX_COIN_ENTRIES = 500;
const MAX_WALLET_OPTIONS = 16;
const MAX_ASSET_ID_LENGTH = 64;
const MAX_COIN_NAME_LENGTH = 100;
const MAX_NETWORK_NAME_LENGTH = 40;
const MAX_CONTRACT_LENGTH = 128;
const OFFICIAL_RATES_ORIGIN = 'https://rates.muchtoman.com';

const EVM_ADDRESS = /^0x[0-9a-fA-F]{40}$/;
const BASE58_KEY = /^[1-9A-HJ-NP-Za-km-z]{32,44}$/;
const TRON_ADDRESS = /^T[1-9A-HJ-NP-Za-km-z]{33}$/;
const BITCOIN_ADDRESS = /^(?:[13][a-km-zA-HJ-NP-Z1-9]{25,34}|bc1[ac-hj-np-z02-9]{11,71})$/i;
const EVM_NETWORKS = new Set(['ethereum', 'bsc', 'arbitrum', 'polygon', 'optimism', 'avalanche']);
const WALLET_NETWORKS = new Set([...EVM_NETWORKS, 'bitcoin', 'solana', 'tron']);

export function isWalletAddressFormatValid(network: string, value: string): boolean {
  const address = value.trim();
  if (!address || address.length > 128) return false;
  if (network === 'bitcoin') return BITCOIN_ADDRESS.test(address);
  if (network === 'solana') return BASE58_KEY.test(address);
  if (network === 'tron') return TRON_ADDRESS.test(address);
  return EVM_NETWORKS.has(network) && EVM_ADDRESS.test(address);
}

export function isWalletContractFormatValid(network: string, value: string): boolean {
  const contract = value.trim();
  if (!contract) return true;
  if (contract.length > MAX_CONTRACT_LENGTH) return false;
  if (network === 'tron') return TRON_ADDRESS.test(contract);
  return EVM_NETWORKS.has(network) && EVM_ADDRESS.test(contract);
}

export const isWalletBalanceValid = (b: WalletBalance, now = Date.now()): boolean =>
  Number.isFinite(b.amount) && b.amount >= 0 && b.updatedAt >= 1 && b.updatedAt <= now + MAX_FUTURE_CLOCK_SKEW_MS;

// Kotlin's isISOControl and isWhitespace.
const CONTROL = /[\u0000-\u001f\u007f-\u009f]/g;
const BAD_ID_CHAR = /[\u0000-\u001f\u007f-\u009f\s]/;
const str = (v: unknown): string => (typeof v === 'string' ? v : '');

function safeText(value: string, maxLength: number, fallback: string): string {
  const text = value.replace(CONTROL, '').trim().slice(0, maxLength);
  return text.trim() ? text : fallback;
}

/**
 * A logo only from the rates origin (or this one) and only its `/coin-icon` path, served back
 * through this origin's proxy because img-src is 'self'. Idempotent, so a cached payload passes.
 */
function trustedCoinIcon(value: string): string {
  if (!value.trim()) return '';
  try {
    const icon = new URL(value, OFFICIAL_RATES_ORIGIN);
    const trusted = icon.origin === OFFICIAL_RATES_ORIGIN || icon.origin === globalThis.location?.origin;
    if (!trusted || icon.pathname !== '/coin-icon' || icon.username || icon.password || value.includes('#') ||
      !icon.search.slice(1).trim()) return '';
    return `/coin-icon${icon.search}`;
  } catch {
    return '';
  }
}

/** Everything from the Worker is capped and checked here, field by field, before anything reads it. */
export function sanitizeRates(raw: unknown, now = Date.now()): Rates {
  const r = raw as { updatedAt?: unknown; toman?: unknown; coins?: unknown } | null;
  // Where kotlinx would have refused to decode, refuse too.
  if (typeof r !== 'object' || r === null) throw new Error('rates is not an object');
  const rawToman = r.toman ?? {};
  const rawCoins = r.coins ?? [];
  if (typeof rawToman !== 'object' || Array.isArray(rawToman) || !Array.isArray(rawCoins)) throw new Error('rates has the wrong shape');

  const toman: Array<[string, number]> = [];
  for (const [id, value] of Object.entries(rawToman as Record<string, unknown>)) {
    if (toman.length >= MAX_RATE_ENTRIES) break;
    if (id.trim() && id.length <= MAX_ASSET_ID_LENGTH && !BAD_ID_CHAR.test(id) &&
      typeof value === 'number' && Number.isFinite(value) && value > 0) toman.push([id, value]);
  }

  const seen = new Set<string>();
  const coins: Coin[] = [];
  for (const c of rawCoins as Array<Record<string, unknown> | null>) {
    if (coins.length >= MAX_COIN_ENTRIES) break;
    const id = str(c?.id).trim();
    if (!id || id.length > MAX_ASSET_ID_LENGTH || BAD_ID_CHAR.test(id) || seen.has(id)) continue;
    seen.add(id);
    const networks = new Set<string>();
    const wallets: WalletOption[] = [];
    for (const w of Array.isArray(c?.wallets) ? c.wallets as Array<Record<string, unknown> | null> : []) {
      const network = str(w?.network);
      const contract = str(w?.contract);
      if (!WALLET_NETWORKS.has(network) || contract.length > MAX_CONTRACT_LENGTH || /[\u0000-\u001f\u007f-\u009f]/.test(contract) ||
        !isWalletContractFormatValid(network, contract) || networks.has(network)) continue;
      networks.add(network);
      if (wallets.length < MAX_WALLET_OPTIONS) {
        wallets.push({ network, networkFa: safeText(str(w?.networkFa), MAX_NETWORK_NAME_LENGTH, network), contract: contract.trim() });
      }
    }
    coins.push({
      id,
      name: safeText(str(c?.name), MAX_COIN_NAME_LENGTH, id.toUpperCase()),
      en: safeText(str(c?.en), MAX_COIN_NAME_LENGTH, ''),
      icon: trustedCoinIcon(str(c?.icon)),
      wallets,
    });
  }

  const updatedAt = Number.isInteger(r.updatedAt) && (r.updatedAt as number) >= 1 &&
    (r.updatedAt as number) <= now + MAX_FUTURE_CLOCK_SKEW_MS ? r.updatedAt as number : 0;
  return { updatedAt, toman: Object.fromEntries(toman), coins };
}

/** The body, but never more than `maxBytes` of it: a payload that big is a mistake or an attack. */
async function readLimited(res: Response, maxBytes: number): Promise<string> {
  const reader = res.body?.getReader();
  if (!reader) return '';
  const decoder = new TextDecoder();
  let total = 0;
  let text = '';
  for (;;) {
    const { done, value } = await reader.read();
    if (done) return text + decoder.decode();
    total += value.byteLength;
    if (total > maxBytes) {
      void reader.cancel();
      throw new Error('response too large');
    }
    text += decoder.decode(value, { stream: true });
  }
}

/**
 * The Worker's prices. `updatedAt` is when it last pulled real prices, not when we asked — the
 * number worth showing, since a cached response is still old prices.
 */
export async function fetchRates(now = Date.now()): Promise<Rates> {
  const res = await fetch('/rates', {
    headers: { Accept: 'application/json' }, cache: 'no-cache', signal: AbortSignal.timeout(20_000),
  });
  if (!res.ok) throw new Error(`HTTP ${res.status}`);
  const parsed = sanitizeRates(JSON.parse(await readLimited(res, MAX_RATES_RESPONSE_BYTES)), now);
  if (!Object.keys(parsed.toman).length) throw new Error('empty rates');
  if (parsed.updatedAt <= 0) throw new Error('invalid rates timestamp');
  return parsed;
}

export class WalletFetchError extends Error {
  readonly reason: string;
  constructor(reason: string) {
    super(reason);
    this.reason = reason;
  }
}

/** The address goes in a POST body so it lands in no URL, cache key or access log. */
export async function fetchWalletBalance(network: string, address: string, contract: string): Promise<WalletBalance> {
  if (!isWalletAddressFormatValid(network, address)) throw new WalletFetchError('invalid_address');
  if (!isWalletContractFormatValid(network, contract)) throw new WalletFetchError('invalid_contract');
  const res = await fetch('/wallet-balance', {
    method: 'POST',
    headers: { Accept: 'application/json', 'Content-Type': 'application/json; charset=utf-8' },
    // kotlinx leaves a default-valued contract out of the body, so this does too.
    body: JSON.stringify(contract ? { network, address: address.trim(), contract } : { network, address: address.trim() }),
    redirect: 'manual',
    cache: 'no-store',
    signal: AbortSignal.timeout(25_000),
  });
  const body = await readLimited(res, MAX_WALLET_RESPONSE_BYTES);
  if (!res.ok) {
    let reason = 'unavailable';
    try {
      const code: unknown = JSON.parse(body)?.code;
      if (typeof code === 'string') reason = code;
    } catch { /* not JSON: unavailable */ }
    throw new WalletFetchError(reason);
  }
  const parsed = JSON.parse(body) as { amount?: unknown; updatedAt?: unknown };
  if (typeof parsed?.amount !== 'number' || !Number.isInteger(parsed.updatedAt)) throw new Error('bad wallet response');
  const balance = { amount: parsed.amount, updatedAt: parsed.updatedAt as number };
  if (!isWalletBalanceValid(balance)) throw new WalletFetchError('invalid_response');
  return balance;
}

export function walletErrorMessage(error: unknown): string {
  switch (error instanceof WalletFetchError ? error.reason : null) {
    case 'invalid_address':
    case 'invalid_contract':
      return 'این آدرس با شبکه انتخاب‌شده جور نیست.';
    case 'unsupported_network':
      return 'هنوز نمی‌شه از این شبکه استفاده کرد.';
    default:
      return 'موجودی نیومد. اینترنتت رو چک کن و دوباره امتحان کن.';
  }
}

// ---- actions (AppVm) -------------------------------------------------------------------------

const WALLET_REFRESH_MS = 10 * 60_000;
const MAX_PARALLEL_WALLET_FETCHES = 4;

/** What the refresh indicator and the wallet rows read; in memory, never persisted. */
export interface WealthStatus {
  loading: boolean;
  error: string | null;
  refreshingWallets: ReadonlySet<string>;
  /** Holding key → what to tell her about its wallet. */
  walletErrors: ReadonlyMap<string, string>;
  refreshing: boolean;
}
let status: WealthStatus = { loading: false, error: null, refreshingWallets: new Set(), walletErrors: new Map(), refreshing: false };
const statusListeners = new Set<() => void>();

function setStatus(patch: Partial<WealthStatus>): void {
  const next = { ...status, ...patch };
  status = { ...next, refreshing: next.loading || next.refreshingWallets.size > 0 };
  for (const l of statusListeners) l();
}
function without(from: ReadonlySet<string>, keys: Iterable<string>): Set<string>;
function without(from: ReadonlyMap<string, string>, keys: Iterable<string>): Map<string, string>;
function without(from: ReadonlySet<string> | ReadonlyMap<string, string>, keys: Iterable<string>): Set<string> | Map<string, string> {
  const next = from instanceof Map ? new Map(from) : new Set(from as ReadonlySet<string>);
  for (const k of keys) next.delete(k);
  return next;
}

export const wealthStatus = (): WealthStatus => status;
export function useWealthStatus(): WealthStatus {
  const [, set] = useState(status);
  useEffect(() => {
    const listener = () => set(status);
    statusListeners.add(listener);
    return () => { statusListeners.delete(listener); };
  }, []);
  return status;
}

let bankToman: () => number | null = () => null;
let afterSnapshot: () => void = () => {};
/**
 * Wires in what lives elsewhere: the ledger's bank figure (Kotlin's `bankTotal` over the enabled,
 * anchored accounts; null when there are no accounts at all, so there is no bank row), and what
 * to kick after every snapshot (Kotlin requests a silent family sync when she shares assets).
 */
export function configureWealth(options: { bankToman?: () => number | null; afterSnapshot?: () => void }): void {
  if (options.bankToman) bankToman = options.bankToman;
  if (options.afterSnapshot) afterSnapshot = options.afterSnapshot;
}

export const currentEffective = (): Record<string, number> => effectiveRates(pref('rates'), pref('overrides'));
export const currentList = (): Holding[] => listHoldings(pref('holdings'), bankToman());
export const currentTotals = (): Totals => computeTotals(currentList(), currentEffective());

const sameRecord = (a: Record<string, number>, b: Record<string, number>): boolean => {
  const keys = Object.keys(a);
  return keys.length === Object.keys(b).length && keys.every((k) => Object.hasOwn(b, k) && a[k] === b[k]);
};

/**
 * Remembers today's total (and dollar rate) through the same `snapshotDay` gate, rebasing first
 * when a toggle changed what the total counts. `countedBefore` is the totals read just before
 * that toggle; the pair is taken now, one toggle apart. Synchronous, so nothing can land between
 * the history read and its write — the job Kotlin's ledgerGate does.
 */
export function recordSnapshot(countedBefore?: Totals, now = Date.now()): void {
  const history = pref('history');
  const base = countedBefore ? rebaseHistory(history, countedBefore, currentTotals()) : history;
  const rates = pref('rates');
  // Stale rates refuse today's point, but the rebase still stands.
  const day = snapshotDay(base, pref('rateHistory'), currentList(), currentEffective(), rates?.updatedAt ?? 0, now);
  const next = day?.history ?? base;
  if (!sameRecord(next, history)) setPref('history', next);
  if (day && !sameRecord(day.rates, pref('rateHistory'))) setPref('rateHistory', day.rates);
  afterSnapshot();
}

function persist(list: Holding[], countedBefore?: Totals): void {
  batch(() => {
    setPref('holdings', list);
    recordSnapshot(countedBefore);
  });
}

/**
 * Writes the row `key` names, or adds one under that key — how a second Tether beside the first
 * is made: the picker hands out a fresh key. Copied, not rebuilt, so an edit never un-excludes
 * a set-aside asset nor drops her name for it.
 */
function saveHolding(key: string, typeId: string, amount: number, wallet: WalletLink | null): void {
  const list = pref('holdings');
  const next = list.some((h) => holdingKey(h) === key)
    ? list.map((h) => (holdingKey(h) === key ? { ...h, amount, wallet } : h))
    : [...list, { typeId, amount, excluded: false, wallet, label: '', id: key }];
  persist(catalogOrdered(next));
}

/** Typing an amount is taking it over by hand: any wallet link goes. */
export function setHolding(key: string, typeId: string, amount: number): void {
  saveHolding(key, typeId, amount, null);
  setStatus({ walletErrors: without(status.walletErrors, [key]) });
}

/** Her own name for a holding, or blank to go back to the asset's own. */
export function setLabel(key: string, label: string): void {
  persist(pref('holdings').map((h) => (holdingKey(h) === key ? { ...h, label: label.trim().slice(0, 32) } : h)));
}

/** A rainy-day asset: stays on the list, drops out of the total — and the chart rebases, not steps. */
export function setExcluded(key: string, excluded: boolean): void {
  const before = currentTotals();
  persist(pref('holdings').map((h) => (holdingKey(h) === key ? { ...h, excluded } : h)), before);
}

/** Returns what was removed, for `reinstateHolding` to undo. */
export function removeHolding(key: string): Holding | undefined {
  const list = pref('holdings');
  const gone = list.find((h) => holdingKey(h) === key);
  persist(list.filter((h) => holdingKey(h) !== key));
  setStatus({ refreshingWallets: without(status.refreshingWallets, [key]), walletErrors: without(status.walletErrors, [key]) });
  return gone;
}

/** Puts back exactly what removeHolding took — amount, label, wallet, set-aside flag. */
export function reinstateHolding(h: Holding): void {
  const list = pref('holdings');
  if (list.some((x) => holdingKey(x) === holdingKey(h))) return; // undo tapped twice
  persist(catalogOrdered([...list, h]));
}

export function clearWalletError(key: string): void {
  if (status.walletErrors.has(key)) setStatus({ walletErrors: without(status.walletErrors, [key]) });
}

/** A hand-typed rate that wins over the Worker's; null clears it. */
export function setOverride(typeId: string, rate: number | null): void {
  const entries = Object.entries(pref('overrides')).filter(([id]) => id !== typeId);
  if (rate !== null) entries.push([typeId, rate]);
  setPref('overrides', Object.fromEntries(entries));
  recordSnapshot();
}
export const clearOverride = (typeId: string): void => setOverride(typeId, null);

// Kotlin's Semaphore(4): the permit is handed straight to the next waiter.
let permits = MAX_PARALLEL_WALLET_FETCHES;
const waiting: Array<() => void> = [];
async function withPermit<T>(task: () => Promise<T>): Promise<T> {
  if (permits > 0) permits--; else await new Promise<void>((resolve) => waiting.push(resolve));
  try {
    return await task();
  } finally {
    const next = waiting.shift();
    if (next) next(); else permits++;
  }
}

/** Reads the wallet once and saves the holding on it. Resolves true on success (Kotlin's onSuccess). */
export async function connectWallet(key: string, typeId: string, option: WalletOption, address: string): Promise<boolean> {
  if (status.refreshingWallets.has(key)) return false;
  const wallet: WalletLink = { network: option.network, networkFa: option.networkFa, address: address.trim(), contract: option.contract, updatedAt: 0 };
  setStatus({ refreshingWallets: new Set([...status.refreshingWallets, key]), walletErrors: without(status.walletErrors, [key]) });
  try {
    const balance = await withPermit(() => fetchWalletBalance(wallet.network, wallet.address, wallet.contract));
    saveHolding(key, typeId, balance.amount, { ...wallet, updatedAt: balance.updatedAt });
    setStatus({ refreshingWallets: without(status.refreshingWallets, [key]), walletErrors: without(status.walletErrors, [key]) });
    return true;
  } catch (error) {
    setStatus({
      refreshingWallets: without(status.refreshingWallets, [key]),
      walletErrors: new Map([...status.walletErrors, [key, walletErrorMessage(error)]]),
    });
    return false;
  }
}

const sameWallet = (a: WalletLink | null, b: WalletLink | null): boolean =>
  a === b || (a !== null && b !== null && a.network === b.network && a.networkFa === b.networkFa &&
    a.address === b.address && a.contract === b.contract && a.updatedAt === b.updatedAt);

/**
 * Re-reads every tracked wallet older than ten minutes (or all, forced), four at a time. A result
 * lands only on a holding whose wallet is still the one asked about — she may have re-linked or
 * typed over it meanwhile.
 */
export async function refreshWallets(force = false, now = Date.now()): Promise<void> {
  const busy = status.refreshingWallets;
  const tracked = pref('holdings').filter((h) =>
    h.wallet !== null && !busy.has(holdingKey(h)) && (force || now - h.wallet.updatedAt >= WALLET_REFRESH_MS));
  if (!tracked.length) return;
  const ids = tracked.map(holdingKey);
  setStatus({ refreshingWallets: new Set([...busy, ...ids]), walletErrors: without(status.walletErrors, ids) });

  type Outcome = { original: Holding; balance: WalletBalance } | { original: Holding; error: unknown };
  const results = new Map(await Promise.all(tracked.map(async (h): Promise<[string, Outcome]> => {
    const w = h.wallet as WalletLink;
    try {
      return [holdingKey(h), { original: h, balance: await withPermit(() => fetchWalletBalance(w.network, w.address, w.contract)) }];
    } catch (error) {
      return [holdingKey(h), { original: h, error }];
    }
  })));

  const errors = new Map(status.walletErrors);
  let changed = false;
  const next = pref('holdings').map((h) => {
    const result = results.get(holdingKey(h));
    if (!result || h.wallet === null || !sameWallet(h.wallet, result.original.wallet)) return h;
    if ('balance' in result) {
      changed = true;
      errors.delete(holdingKey(h));
      return { ...h, amount: result.balance.amount, wallet: { ...h.wallet, updatedAt: result.balance.updatedAt } };
    }
    errors.set(holdingKey(h), walletErrorMessage(result.error));
    return h;
  });
  if (changed) setPref('holdings', next);
  setStatus({ refreshingWallets: without(status.refreshingWallets, ids), walletErrors: errors });
  if (changed) recordSnapshot();
}

/** Fetches the Worker's prices; on failure the cached ones stay, and a calm line says so. */
export async function refreshRates(): Promise<void> {
  if (status.loading) return;
  setStatus({ loading: true, error: null });
  let failure: string | null = null;
  try {
    const fetched = await fetchRates();
    setPref('rates', mergeRates(fetched, pref('rates')));
  } catch (error) {
    // The console is the only place the real reason survives; the UI stays Persian and calm.
    console.warn('rates fetch failed', error);
    failure = 'نرخ‌ها به‌روز نشدن. اینترنتت رو چک کن.';
  }
  setStatus({ loading: false, error: failure });
  recordSnapshot();
}

/**
 * On every return to the foreground. The Worker's edge cache is ten minutes, so anything younger
 * cannot change; anything older is refetched without her finding the button.
 */
export function refreshIfStale(now = Date.now()): void {
  if (now - (pref('rates')?.updatedAt ?? 0) > 10 * 60_000) void refreshRates();
  void refreshWallets(false, now);
}

export function refreshAll(): void {
  void refreshRates();
  void refreshWallets(true);
}
