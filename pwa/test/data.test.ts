/**
 * MoneyTest's wealth half, SnapshotTest, RatesFallbackTest, HoldingLabelTest and WalletModelTest,
 * with their literal expectations, plus the fetch boundary and the AppVm actions.
 */
import 'fake-indexeddb/auto';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { BANK_ID, TOMAN_ID, resolveType } from '../src/catalog';
import {
  EMPTY_RATES, HISTORY_KEEP_DAYS, assetShareItems, backupReminderDue, undoubledBankName, changeOver, computeTotals, configureWealth,
  effectiveRates, fetchRates, fetchWalletBalance, isWalletAddressFormatValid, isWalletBalanceValid,
  isWalletContractFormatValid, listHoldings, mergeRates, nameOr, rebaseHistory, recordDay, refreshWallets,
  reinstateHolding, removeHolding, sanitizeRates, setExcluded, setHolding, setLabel, setOverride, snapshotDay,
  snapshotHistory, walletErrorMessage, wealthStatus,
} from '../src/data';
import type { Totals } from '../src/data';
import { DAY_MS } from '../src/jalali';
import type { Holding, Rates } from '../src/model';
import { pref, setPref } from '../src/state';

const H = (typeId: string, amount: number, extra: Partial<Holding> = {}): Holding =>
  ({ typeId, amount, excluded: false, wallet: null, label: '', id: '', ...extra });
const rates = (updatedAt: number, toman: Record<string, number>, coins: Rates['coins'] = []): Rates => ({ updatedAt, toman, coins });
const T = (toman: number, missing: string[] = []): Totals => ({ toman, missing });

describe('totals', () => {
  it('total multiplies each holding by its rate', () => {
    expect(computeTotals([H('usd', 3000), H('usdt', 2000)], { usd: 118_500, usdt: 118_000 }).toman)
      .toBeCloseTo(3000 * 118_500 + 2000 * 118_000, 2);
  });
  it('a set-aside holding is out of the total and not reported missing', () => {
    const t = computeTotals([H('usd', 100, { excluded: true }), H(TOMAN_ID, 5_000), H('btc', 1, { excluded: true })],
      { usd: 118_500, [TOMAN_ID]: 1 });
    expect(t).toEqual(T(5_000));
  });
  it('assets with no rate are excluded and reported, never counted as zero', () => {
    const t = computeTotals([H('usd', 100), H('btc', 0.5)], { usd: 118_500 });
    expect(t.toman).toBeCloseTo(11_850_000, 2);
    expect(t.missing).toEqual(['btc']);
  });
  it('a zero or negative rate counts as missing, not as free money', () => {
    expect(computeTotals([H('usd', 100)], { usd: 0 })).toEqual(T(0, ['usd']));
    expect(computeTotals([H('usd', 100)], { usd: -3 })).toEqual(T(0, ['usd']));
  });
  it('a prototype name is not a rate', () => {
    expect(computeTotals([H('constructor', 1)], {})).toEqual(T(0, ['constructor']));
  });
  it('toman counts at face value even with no rates at all', () => {
    expect(computeTotals([H(TOMAN_ID, 50_000_000)], effectiveRates(EMPTY_RATES, {}))).toEqual(T(50_000_000));
  });
  it('toman rate cannot be overridden to something other than itself', () => {
    expect(effectiveRates(rates(1, { [TOMAN_ID]: 7 }), { [TOMAN_ID]: 99 })[TOMAN_ID]).toBe(1);
  });
  it('a car is worth what she typed, whatever any rate says', () => {
    const r = effectiveRates(rates(1, { car: 7 }), { car: 99 });
    for (const id of ['car', 'house', 'land']) {
      expect(resolveType(id, []).kind).toBe('PROPERTY');
      expect(computeTotals([H(id, 900_000_000)], r), id).toEqual(T(900_000_000));
    }
  });
  it('toman adds to the rest of the portfolio', () => {
    const r = effectiveRates(rates(1, { usd: 187_000 }), {});
    expect(computeTotals([H(TOMAN_ID, 50_000_000), H('usd', 3000)], r).toman).toBeCloseTo(50_000_000 + 561_000_000, 2);
  });
  it('overrides win over fetched rates', () => {
    expect(effectiveRates(rates(1, { usd: 100 }), { usd: 130 }).usd).toBe(130);
  });
  it('a coin with a price but no catalogue entry still counts toward the total', () => {
    const t = computeTotals([H('dot', 2)], effectiveRates(rates(1, { dot: 300_000 }), {}));
    expect(t.toman).toBeCloseTo(600_000, 2);
    expect(t.missing).toEqual([]);
  });
  it('an overflowing holding is reported missing instead of poisoning the total', () => {
    expect(computeTotals([H('huge', Number.MAX_VALUE), H('usd', 2)], { huge: 2, usd: 10 })).toEqual(T(20, ['huge']));
  });
  it('a wallet link does not change how its verified amount is valued', () => {
    const h = H('eth', 1.25, { wallet: { network: 'ethereum', networkFa: 'اتریوم', address: '0x123', contract: '', updatedAt: 10 } });
    expect(computeTotals([h], { eth: 200 }).toman).toBe(250);
  });
});

describe('holdings by name', () => {
  it("a custom name replaces the asset's own, and blank falls back to it", () => {
    expect(nameOr(H('usdt', 1, { label: 'تتر شخصی' }), 'تتر')).toBe('تتر شخصی');
    expect(nameOr(H('usdt', 1, { label: '   ' }), 'تتر')).toBe('تتر');
    expect(nameOr(H('usdt', 10.5), 'تتر')).toBe('تتر');
  });
  it('two holdings of one asset stand apart and both count', () => {
    const personal = H('usdt', 10, { id: 'a', label: 'تتر شخصی' });
    const shared = H('usdt', 5, { id: 'b', label: 'تتر مشترک' });
    expect(computeTotals([personal, shared], { usdt: 2_000 }).toman).toBe(30_000);
  });
  it('two cars are two rows, each worth what she typed', () => {
    const hers = H('car', 1_200_000_000, { id: 'a', label: 'پژوی من' });
    const his = H('car', 800_000_000, { id: 'b', label: 'پراید بابا' });
    expect(computeTotals([hers, his], effectiveRates(EMPTY_RATES, {})).toman).toBe(2_000_000_000);
  });
});

describe('history', () => {
  it('history keeps one entry per day and never outgrows a year', () => {
    let h: Record<string, number> = {};
    h = recordDay(h, 100, 5);
    h = recordDay(h, 100, 7);
    expect(Object.keys(h).length).toBe(1);
    expect(h[100]).toBe(7);
    for (let d = 0; d < 500; d++) h = recordDay(h, d, d);
    expect(Object.keys(h).length).toBe(HISTORY_KEEP_DAYS);
    expect(h[99]).toBeUndefined();
    expect(h[499]).toBe(499);
  });

  it('change is measured against the newest snapshot old enough', () => {
    const c = changeOver({ 65: 7, 70: 8, 100: 10 }, 100, 30, 12)!;
    expect(c.sinceDay).toBe(70);
    expect(c.delta).toBe(4);
    expect(c.percent).toBeCloseTo(50, 9);
  });
  it('a short history refuses to impersonate a longer window', () => {
    expect(changeOver({ 90: 8, 100: 10 }, 100, 30, 12)).toBeNull();
  });
  it('a small gap just inside the window is tolerated — three days of grace, not four', () => {
    const c = changeOver({ 72: 8 }, 100, 30, 12)!;
    expect(c.sinceDay).toBe(72);
    expect(c.delta).toBe(4);
    expect(changeOver({ 73: 8 }, 100, 30, 12)!.sinceDay).toBe(73);
    expect(changeOver({ 74: 8 }, 100, 30, 12)).toBeNull();
    // Older than the window wins over the grace, and the earliest inside the grace wins among those.
    expect(changeOver({ 69: 5, 71: 8 }, 100, 30, 12)!.sinceDay).toBe(69);
    expect(changeOver({ 72: 5, 71: 8 }, 100, 30, 12)!.sinceDay).toBe(71);
  });
  it('percent is omitted when the baseline was zero', () => {
    expect(changeOver({ 50: 0 }, 100, 30, 12)!.percent).toBeNull();
  });
});

describe('snapshot gate', () => {
  const now = 100 * DAY_MS + 5_000;

  it('nothing on the list records nothing', () => {
    expect(snapshotHistory({}, [], { usd: 100 }, now, now)).toBeNull();
  });
  it('stale rates record nothing', () => {
    expect(snapshotHistory({}, [H('usd', 10)], { usd: 100 }, now - 25 * 60 * 60_000, now)).toBeNull();
    expect(snapshotHistory({}, [H('usd', 10)], { usd: 100 }, now - 24 * 60 * 60_000, now)).not.toBeNull();
  });
  it('a missing rate records nothing rather than a partial total', () => {
    expect(snapshotHistory({}, [H('usd', 10), H('btc', 1)], { usd: 100 }, now, now)).toBeNull();
  });
  it('the same day is overwritten, not appended', () => {
    const first = snapshotHistory({}, [H('usd', 10)], { usd: 100 }, now, now)!;
    const second = snapshotHistory(first, [H('usd', 10)], { usd: 120 }, now, now)!;
    expect(second).toEqual({ 100: 1_200 });
  });
  it('a wallet not read in the last ten minutes holds the day back, unless set aside', () => {
    const wallet = (updatedAt: number) => ({ network: 'bitcoin', networkFa: 'بیت‌کوین', address: 'x', contract: '', updatedAt });
    const r = { btc: 100 };
    expect(snapshotHistory({}, [H('btc', 1, { wallet: wallet(now - 10 * 60_000) })], r, now, now)).not.toBeNull();
    expect(snapshotHistory({}, [H('btc', 1, { wallet: wallet(now - 10 * 60_000 - 1) })], r, now, now)).toBeNull();
    expect(snapshotHistory({}, [H('btc', 1, { wallet: wallet(0) })], r, now, now)).toBeNull();
    expect(snapshotHistory({}, [H('btc', 1, { wallet: wallet(now + 5 * 60_000 + 1) })], r, now, now)).toBeNull();
    expect(snapshotHistory({}, [H(TOMAN_ID, 1), H('btc', 1, { excluded: true, wallet: wallet(0) })], { [TOMAN_ID]: 1 }, now, now)).not.toBeNull();
  });
  it("the dollar rate is recorded on the total's gate, never beside a refused day", () => {
    const list = [H('usd', 10)];
    const good = snapshotDay({}, {}, list, { usd: 100 }, now, now)!;
    expect(good.history[100]).toBe(1_000);
    expect(good.rates[100]).toBe(100);
    expect(snapshotDay({}, {}, list, { usd: 100 }, now - 25 * 60 * 60_000, now)).toBeNull();
    const kept = { 90: 90_000 };
    expect(snapshotDay({}, kept, [H(TOMAN_ID, 5)], { [TOMAN_ID]: 1 }, now, now)!.rates).toEqual(kept);
  });
  it('the day key is the UTC epoch day, exactly now / DAY_MS', () => {
    const late = 100 * DAY_MS + DAY_MS - 1;
    expect(Object.keys(snapshotHistory({}, [H('usd', 1)], { usd: 1 }, late, late)!)).toEqual(['100']);
  });
});

describe('bank row', () => {
  it('the bank row lands right after cash', () => {
    const list = listHoldings([H('usd', 1), H(TOMAN_ID, 5), H('btc', 1)], 300);
    expect(list.map((h) => h.typeId)).toEqual(['usd', TOMAN_ID, BANK_ID, 'btc']);
    expect(list.find((h) => h.typeId === BANK_ID)!.amount).toBe(300);
  });
  it('without cash the bank row comes first', () => {
    expect(listHoldings([H('usd', 1)], 300).map((h) => h.typeId)).toEqual([BANK_ID, 'usd']);
  });
  it('no bank accounts, no bank row', () => {
    const holdings = [H('usd', 1)];
    expect(listHoldings(holdings, null)).toBe(holdings);
  });
});

describe('rebase', () => {
  it('setting an asset aside rebases the chart instead of stepping it down', () => {
    const r = { usd: 100, gold: 100 };
    const list = [H('usd', 10), H('gold', 70, { id: 'g' })];
    const before = computeTotals(list, r);
    const history = { 90: 7_600, 95: 7_800 };
    const after = computeTotals(list.map((h) => (h.id === 'g' ? { ...h, excluded: true } : h)), r);
    const next = rebaseHistory(history, before, after);
    const percent = (h: Record<string, number>, now: number) => changeOver(h, 100, 10, now)!.percent!;
    expect(percent(next, after.toman)).toBeCloseTo(percent(history, before.toman), 9);
    expect(next[95]).toBeCloseTo(975, 9);
  });
  it('changing her mind puts the history back', () => {
    const priced = { usd: 100, gold: 100 };
    const list = [H('usd', 10), H('gold', 70, { id: 'g' })];
    const counted = computeTotals(list, priced);
    const aside = computeTotals(list.map((h) => (h.id === 'g' ? { ...h, excluded: true } : h)), priced);
    const back = rebaseHistory(rebaseHistory({ 90: 7_600 }, counted, aside), aside, counted);
    expect(back[90]).toBeCloseTo(7_600, 9);
  });
  it('a partial or empty basis leaves the history alone', () => {
    const history = { 90: 7_600 };
    expect(rebaseHistory(history, T(8_000), T(1_000, ['btc']))).toBe(history);
    expect(rebaseHistory(history, T(1_000, ['btc']), T(8_000))).toBe(history);
    expect(rebaseHistory(history, T(8_000), T(0))).toBe(history);
  });
  it('an unpriced bystander does not veto the rebase', () => {
    expect(rebaseHistory({ 90: 8_000 }, T(8_000, ['btc']), T(1_000, ['btc']))[90]).toBeCloseTo(1_000, 9);
  });
});

describe('rates payload', () => {
  const official = 'https://rates.muchtoman.com/coin-icon?path=%2Fcoins%2Fimages%2F1%2Fsmall.png';

  it('a response with no catalogue keeps the names we already had, and the new prices', () => {
    const wallet = { network: 'bitcoin', networkFa: 'بیت کوین', contract: '' };
    const cached = rates(1, { btc: 1 }, [{ id: 'btc', name: 'بیت‌کوین', en: 'Bitcoin', icon: 'u', wallets: [wallet] }]);
    const merged = mergeRates(rates(2, { btc: 2 }), cached);
    expect(merged.toman.btc).toBe(2);
    expect(merged.coins[0].name).toBe('بیت‌کوین');
    expect(merged.coins[0].wallets).toEqual([wallet]);
    expect(merged.updatedAt).toBe(2);
  });
  it('a real catalogue always wins over the cached one', () => {
    const cached = rates(1, {}, [{ id: 'btc', name: 'بیت‌کوین', en: 'Bitcoin', icon: 'u', wallets: [] }]);
    const fresh = rates(2, {}, [{ id: 'eth', name: 'اتریوم', en: 'Ethereum', icon: 'u', wallets: [] }]);
    expect(mergeRates(fresh, cached).coins.map((c) => c.id)).toEqual(['eth']);
  });

  it('network catalogue URLs are restricted to the trusted services, and served through this origin', () => {
    const now = 1_000_000;
    const clean = sanitizeRates({
      updatedAt: now,
      toman: { btc: 1, bad: Infinity, zero: 0, 'has space': 5, ['x'.repeat(65)]: 5, str: '5' },
      coins: [{ id: 'btc', name: 'Bitcoin', icon: official }, { id: 'eth', name: 'Ethereum', icon: 'https://example.com/tracker.png' }],
      latest: { name: '2.0', url: 'https://evil.example/fake.apk' },
    }, now);
    expect(Object.keys(clean.toman)).toEqual(['btc']);
    expect(clean.coins[0].icon).toBe('/coin-icon?path=%2Fcoins%2Fimages%2F1%2Fsmall.png');
    expect(clean.coins[1].icon).toBe('');
    expect('latest' in clean).toBe(false);
    // Idempotent: the cached payload passes again unchanged.
    expect(sanitizeRates(clean, now)).toEqual(clean);
  });
  it('icons off the coin-icon path, with credentials or a fragment are dropped', () => {
    const icon = (value: string) => sanitizeRates({ updatedAt: 1, toman: {}, coins: [{ id: 'a', name: 'A', icon: value }] }, 1).coins[0].icon;
    expect(icon('http://rates.muchtoman.com/coin-icon?x=1')).toBe('');
    expect(icon('https://rates.muchtoman.com/rates?x=1')).toBe('');
    expect(icon('https://u:p@rates.muchtoman.com/coin-icon?x=1')).toBe('');
    expect(icon('https://rates.muchtoman.com/coin-icon?x=1#y')).toBe('');
    expect(icon('https://rates.muchtoman.com/coin-icon')).toBe('');
    expect(icon('//evil.example/coin-icon?x=1')).toBe('');
  });
  it('coins and wallets are capped and cleaned', () => {
    const clean = sanitizeRates({
      updatedAt: 1,
      coins: [
        { id: ' dot ', name: 'پولکادات\u0000', en: 'x'.repeat(150), wallets: [
          { network: 'ethereum', networkFa: '', contract: '0x0000000000000000000000000000000000000000' },
          { network: 'ethereum', networkFa: 'دوباره', contract: '' },
          { network: 'bitcoin', networkFa: 'بیت‌کوین', contract: 'not-a-contract' },
          { network: 'dogechain', networkFa: 'x', contract: '' },
        ] },
        { id: 'dot', name: 'twice' },
        { id: '', name: 'blank' },
        { id: 'no name' },
        { id: 'nn' },
      ],
    }, 1);
    expect(clean.coins.map((c) => c.id)).toEqual(['dot', 'nn']);
    expect(clean.coins[0].name).toBe('پولکادات');
    expect(clean.coins[0].en.length).toBe(100);
    expect(clean.coins[0].wallets).toEqual([{ network: 'ethereum', networkFa: 'ethereum', contract: '0x0000000000000000000000000000000000000000' }]);
    expect(clean.coins[1].name).toBe('NN');
  });
  it('a future rates timestamp cannot suppress refresh indefinitely', () => {
    const now = 1_000_000;
    expect(sanitizeRates({ updatedAt: now + 5 * 60_000 + 1, toman: { usd: 1 } }, now).updatedAt).toBe(0);
    expect(sanitizeRates({ updatedAt: now + 5 * 60_000, toman: { usd: 1 } }, now).updatedAt).toBe(now + 5 * 60_000);
  });
  it('the wrong shape is refused outright', () => {
    expect(() => sanitizeRates(null)).toThrow();
    expect(() => sanitizeRates({ toman: [] })).toThrow();
    expect(() => sanitizeRates({ coins: {} })).toThrow();
  });
});

describe('wallets', () => {
  it('invalid wallet text is rejected before a network request', () => {
    expect(isWalletAddressFormatValid('ethereum', '0x0000000000000000000000000000000000000000')).toBe(true);
    expect(isWalletAddressFormatValid('bitcoin', '1BoatSLRHtKNngkdXEeobR76b53LETtpyT')).toBe(true);
    expect(isWalletAddressFormatValid('solana', '11111111111111111111111111111111')).toBe(true);
    expect(isWalletAddressFormatValid('tron', 'T' + '1'.repeat(33))).toBe(true);
    expect(isWalletAddressFormatValid('ethereum', 'word word word word word word')).toBe(false);
    expect(isWalletAddressFormatValid('unknown', '0x0000000000000000000000000000000000000000')).toBe(false);
    expect(isWalletContractFormatValid('ethereum', '0x0000000000000000000000000000000000000000')).toBe(true);
    expect(isWalletContractFormatValid('bitcoin', '')).toBe(true);
    expect(isWalletContractFormatValid('bitcoin', 'not-a-contract')).toBe(false);
  });
  it('a future wallet timestamp cannot suppress refresh indefinitely', () => {
    const now = 1_000_000;
    expect(isWalletBalanceValid({ amount: 1, updatedAt: now }, now)).toBe(true);
    expect(isWalletBalanceValid({ amount: 1, updatedAt: now + 5 * 60_000 + 1 }, now)).toBe(false);
  });
});

describe('family share', () => {
  it('prices her holdings, drops the set-aside and the unpriced, then one line per shared bank', () => {
    const items = assetShareItems(
      [H('usd', 10, { label: 'دلار من' }), H('eur', 1, { excluded: true }), H('doge', 5), H(TOMAN_ID, 7)],
      { usd: 100, eur: 200, [TOMAN_ID]: 1 }, [], [],
      [
        { bank: 'SAMAN', balance: 300, anchored: true },
        { bank: 'MELLAT', balance: 400, anchored: false },
        { bank: 'MELLI', balance: 500, anchored: true },
        { bank: 'DEY', balance: 600, anchored: true },
      ],
      ['MELLI'], ['DEY'],
    );
    expect(items).toEqual([
      { name: 'دلار من', toman: 1_000 }, { name: 'پول نقد', toman: 7 }, { name: 'بانک سامان', toman: 300 },
    ]);
  });
  it('folds the doubled bank label older builds sent, and nothing else', () => {
    expect(undoubledBankName('بانک بانک سامان')).toBe('بانک سامان');
    expect(undoubledBankName('بانک بلو بانک')).toBe('بلو بانک');
    expect(undoubledBankName('بانک سامان')).toBe('بانک سامان');
    expect(undoubledBankName('بانک من')).toBe('بانک من');
  });
  it('never shares a negative, an overflow, or more than 64 lines', () => {
    const many = Array.from({ length: 70 }, (_, i) => H(`c${i}`, 1));
    const r = Object.fromEntries(many.map((h) => [h.typeId, 1]));
    expect(assetShareItems(many, r, [], [], [], [], []).length).toBe(64);
    expect(assetShareItems([H('a', -1), H('b', 2e14)], { a: 1, b: 1 }, [], [], [], [], [])).toEqual([]);
  });
});

describe('backup reminder', () => {
  it('is due after thirty days, at once with codes, never when off', () => {
    const now = 100 * DAY_MS;
    expect(backupReminderDue(true, 0, now)).toBe(true);
    expect(backupReminderDue(true, now - 29 * DAY_MS, now)).toBe(false);
    expect(backupReminderDue(true, now - 30 * DAY_MS, now)).toBe(true);
    expect(backupReminderDue(true, now, now, true)).toBe(true);
    expect(backupReminderDue(false, 0, now, true)).toBe(false);
  });
});

describe('the network boundary', () => {
  afterEach(() => { vi.unstubAllGlobals(); });
  const respond = (body: unknown, status = 200) => vi.stubGlobal('fetch', vi.fn(async () =>
    new Response(typeof body === 'string' ? body : JSON.stringify(body), { status })));

  it('fetchRates asks this origin and refuses an empty or undated payload', async () => {
    respond({ updatedAt: 1_000, toman: { usd: 100 } });
    expect((await fetchRates(1_000)).toman).toEqual({ usd: 100 });
    expect(vi.mocked(fetch).mock.calls[0][0]).toBe('/rates');
    respond({ updatedAt: 1_000, toman: {} });
    await expect(fetchRates(1_000)).rejects.toThrow('empty rates');
    respond({ updatedAt: 0, toman: { usd: 100 } });
    await expect(fetchRates(1_000)).rejects.toThrow('invalid rates timestamp');
    respond('nope', 502);
    await expect(fetchRates(1_000)).rejects.toThrow('HTTP 502');
  });
  it('fetchRates counts the first fetch of a UTC day and only that one', async () => {
    const stored = new Map<string, string>();
    vi.stubGlobal('localStorage', { getItem: (k: string) => stored.get(k) ?? null, setItem: (k: string, v: string) => stored.set(k, v) });
    vi.stubEnv('PROD', true);
    vi.stubEnv('VITE_VERSION', '1.2.5');
    try {
      const daily = () => new Headers(vi.mocked(fetch).mock.calls[0][1]?.headers).get('x-muchtoman-daily');
      const day = 20_000 * 86_400_000;
      respond('nope', 502);
      await expect(fetchRates(day)).rejects.toThrow('HTTP 502');
      expect(daily()).toBe('1.2.5 pwa');
      respond({ updatedAt: 1_000, toman: { usd: 100 } });
      await fetchRates(day);
      expect(daily()).toBe('1.2.5 pwa'); // the failed one did not count, so this one does
      respond({ updatedAt: 1_000, toman: { usd: 100 } });
      await fetchRates(day + 3_600_000);
      expect(daily()).toBeNull();
      respond({ updatedAt: 1_000, toman: { usd: 100 } });
      await fetchRates(day + 86_400_000);
      expect(daily()).toBe('1.2.5 pwa');
    } finally {
      vi.unstubAllEnvs();
    }
  });
  it('fetchRates refuses anything past two megabytes', async () => {
    respond(JSON.stringify({ updatedAt: 1, toman: { usd: 1 }, pad: 'x'.repeat(2 * 1024 * 1024) }));
    await expect(fetchRates(1)).rejects.toThrow('response too large');
  });
  it('a wallet error names its reason, and a bad address never leaves the phone', async () => {
    respond({ code: 'unsupported_network' }, 400);
    const error = await fetchWalletBalance('bitcoin', '1BoatSLRHtKNngkdXEeobR76b53LETtpyT', '').catch((e) => e);
    expect(walletErrorMessage(error)).toBe('هنوز نمی‌شه از این شبکه استفاده کرد.');
    const [url, init] = vi.mocked(fetch).mock.calls[0];
    expect(url).toBe('/wallet-balance');
    expect(JSON.parse(init!.body as string)).toEqual({ network: 'bitcoin', address: '1BoatSLRHtKNngkdXEeobR76b53LETtpyT' });

    respond({ amount: 1, updatedAt: 1 });
    const bad = await fetchWalletBalance('ethereum', 'nope', '').catch((e) => e);
    expect(walletErrorMessage(bad)).toBe('این آدرس با شبکه انتخاب‌شده جور نیست.');
    expect(fetch).not.toHaveBeenCalled();

    respond('<html>', 500);
    expect(walletErrorMessage(await fetchWalletBalance('bitcoin', '1BoatSLRHtKNngkdXEeobR76b53LETtpyT', '').catch((e) => e)))
      .toBe('موجودی نیومد. اینترنتت رو چک کن و دوباره امتحان کن.');
  });
});

describe('actions', () => {
  const now = Date.now();
  beforeEach(() => {
    configureWealth({ bankToman: () => null });
    setPref('rates', rates(now, { usd: 100, gold: 100 }));
    setPref('overrides', {});
    setPref('history', {});
    setPref('rateHistory', {});
    setPref('holdings', [H('usd', 10), H('gold', 70, { id: 'g' })]);
  });

  it('an edit records today, and setting aside rebases the history instead of stepping it', () => {
    setHolding('usd', 'usd', 10);
    const today = String(Math.trunc(Date.now() / DAY_MS));
    expect(pref('history')[today]).toBe(8_000);
    expect(pref('rateHistory')[today]).toBe(100);
    setPref('history', { 1: 8_000, [today]: 8_000 });
    setExcluded('g', true);
    expect(pref('history')).toEqual({ 1: 1_000, [today]: 1_000 });
  });

  it('typing an amount drops the wallet link, keeps the name and the set-aside flag', () => {
    const wallet = { network: 'ethereum', networkFa: 'اتریوم', address: '0x', contract: '', updatedAt: 1 };
    setPref('holdings', [H('usd', 1, { id: 'k', wallet, label: 'من', excluded: true })]);
    setHolding('k', 'usd', 5);
    expect(pref('holdings')).toEqual([H('usd', 5, { id: 'k', label: 'من', excluded: true })]);
    setLabel('k', `  ${'ن'.repeat(40)}  `);
    expect(pref('holdings')[0].label).toBe('ن'.repeat(32));
  });

  it('a new key adds a second row of the same asset, in catalogue order', () => {
    setHolding('new', TOMAN_ID, 5);
    expect(pref('holdings').map((h) => h.typeId)).toEqual([TOMAN_ID, 'usd', 'gold']);
  });

  it('remove hands back what undo needs, and undo twice is once', () => {
    const gone = removeHolding('g')!;
    expect(pref('holdings').map((h) => h.typeId)).toEqual(['usd']);
    reinstateHolding(gone);
    reinstateHolding(gone);
    expect(pref('holdings')).toEqual([H('usd', 10), H('gold', 70, { id: 'g' })]);
  });

  it('an override wins, and clearing it gives the Worker its say back', () => {
    setOverride('usd', 200);
    expect(pref('overrides')).toEqual({ usd: 200 });
    setOverride('usd', null);
    expect(pref('overrides')).toEqual({});
  });

  it('a wallet refresh lands only on the wallet it asked about, and records the error otherwise', async () => {
    const wallet = { network: 'bitcoin', networkFa: 'بیت‌کوین', address: '1BoatSLRHtKNngkdXEeobR76b53LETtpyT', contract: '', updatedAt: 1 };
    setPref('holdings', [H('btc', 1, { id: 'a', wallet }), H('btc', 2, { id: 'b', wallet: { ...wallet, updatedAt: 2 } })]);
    let calls = 0;
    vi.stubGlobal('fetch', vi.fn(async () => (++calls === 1
      ? new Response(JSON.stringify({ amount: 3, updatedAt: Date.now() }))
      : new Response('{}', { status: 503 }))));
    await refreshWallets();
    vi.unstubAllGlobals();
    const [a, b] = pref('holdings');
    expect(a.amount).toBe(3);
    expect(b.amount).toBe(2);
    expect(wealthStatus().walletErrors.get('b')).toBe('موجودی نیومد. اینترنتت رو چک کن و دوباره امتحان کن.');
    expect(wealthStatus().refreshing).toBe(false);
  });
});
