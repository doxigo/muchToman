/**
 * Catalog.kt, one to one: what she can hold, what each is called, and how it is grouped.
 *
 * Stocks (Tse.kt) have no source here — TSETMC sends no CORS headers and is geo-blocked, so a
 * browser cannot fetch it. `Stock` and `stockType` stay so a holding or a family share saved on a
 * phone still resolves to its نماد; it just never has a rate, and so is named missing, never zero.
 */
import { computeTotals } from './data';
import { faDigits } from './format';
import type { Coin, Holding, WalletOption } from './model';

export const KINDS = ['CASH', 'FIAT', 'CRYPTO', 'GOLD', 'SILVER', 'COIN', 'STOCK', 'PROPERTY'] as const;
export type Kind = typeof KINDS[number];
export const KIND_FA: Record<Kind, string> = {
  CASH: 'تومان',
  FIAT: 'ارز',
  CRYPTO: 'رمزارز',
  GOLD: 'طلا',
  SILVER: 'نقره',
  COIN: 'سکه',
  STOCK: 'بورس',
  PROPERTY: 'املاک و خودرو',
};

/** Toman held as Toman. Its rate is 1 by definition and never comes from the network. */
export const TOMAN_ID = 'toman';
/** Sms.kt's id for the one row standing for every bank balance. */
export const BANK_ID = 'bank_accounts';

/**
 * `id` keys the Worker's rates; `unitFa` is what one of it is called; `dec` how many decimals
 * are meaningful in the held amount; `en` a latin name it is searched by, never shown; `emoji`
 * for where there is no real logo; `iconUrl` a real logo (same-origin `/coin-icon?…`).
 */
export interface AssetType {
  id: string;
  fa: string;
  kind: Kind;
  unitFa: string;
  dec: number;
  en: string;
  emoji?: string;
  iconUrl?: string;
  wallets: WalletOption[];
}

/** A نماد: `symbol` is what she says, `name` the company behind it. */
export interface Stock { id: string; symbol: string; name: string }

const type = (id: string, fa: string, kind: Kind, unitFa: string, rest: Partial<AssetType> = {}): AssetType =>
  ({ id, fa, kind, unitFa, dec: 0, en: '', wallets: [], ...rest });

/**
 * The fifteen parsian sizes tgju quotes, no longer offered but still resolvable: holdings saved
 * against them keep their name and their own (per-size, so more accurate) price.
 */
const LEGACY_PARSIAN: AssetType[] = Array.from({ length: 15 }, (_, i) => {
  const soot = (i + 1) * 100;
  return type(`parsian_${soot}`, `سکه پارسیان ${faDigits(String(soot))} سوت`, 'COIN', 'عدد', {
    // Also written by its weight everywhere they are sold, so "0.400" has to find it.
    en: `Parsian Gold Coin ${Math.trunc(soot / 1000)}.${String(soot % 1000).padStart(3, '0')}g`,
    emoji: '🪙',
  });
});

// The fixed half of the catalogue: things bonbast/tgju price. Crypto comes from the Worker.
export const STATIC_CATALOG: AssetType[] = [
  // The Toman she counts herself — bank balances are their own row.
  type(TOMAN_ID, 'پول نقد', 'CASH', 'تومان', { en: 'Toman Cash', emoji: '💰' }),

  // dec 2: a held 100.50 must round-trip through the edit field without becoming 100.
  type('usd', 'دلار آمریکا', 'FIAT', 'دلار', { dec: 2, en: 'US Dollar', emoji: '💵' }),
  type('eur', 'یورو', 'FIAT', 'یورو', { dec: 2, en: 'Euro', emoji: '💶' }),
  type('gbp', 'پوند انگلیس', 'FIAT', 'پوند', { dec: 2, en: 'British Pound', emoji: '💷' }),
  type('nok', 'کرون نروژ', 'FIAT', 'کرون', { dec: 2, en: 'Norwegian Krone', emoji: '🇳🇴' }),
  type('try', 'لیر ترکیه', 'FIAT', 'لیر', { dec: 2, en: 'Turkish Lira', emoji: '🇹🇷' }),
  type('aed', 'درهم امارات', 'FIAT', 'درهم', { dec: 2, en: 'UAE Dirham', emoji: '🇦🇪' }),
  type('cad', 'دلار کانادا', 'FIAT', 'دلار', { dec: 2, en: 'Canadian Dollar', emoji: '🇨🇦' }),

  type('gold18', 'طلای ۱۸ عیار', 'GOLD', 'گرم', { dec: 3, en: '18k Gold', emoji: '🟡' }),
  // Her generation prices gold in مثقال, not grams; bonbast quotes it directly.
  type('gold_mesghal', 'مثقال طلا', 'GOLD', 'مثقال', { dec: 2, en: 'Mesghal Gold', emoji: '🟡' }),

  // Only tgju prices silver: no rate means left out of the total and named, never zero.
  type('silver_999', 'نقره ۹۹۹', 'SILVER', 'گرم', { dec: 3, en: '999 Silver', emoji: '⚪' }),
  type('silver_925', 'نقره ۹۲۵', 'SILVER', 'گرم', { dec: 3, en: '925 Sterling Silver', emoji: '⚪' }),

  type('coin_emami', 'سکه امامی', 'COIN', 'عدد', { en: 'Emami Gold Coin', emoji: '🪙' }),
  type('coin_bahar', 'سکه بهار آزادی', 'COIN', 'عدد', { en: 'Bahar Azadi Gold Coin', emoji: '🪙' }),
  type('coin_nim', 'نیم‌سکه', 'COIN', 'عدد', { en: 'Nim Half Gold Coin', emoji: '🪙' }),
  type('coin_rob', 'ربع‌سکه', 'COIN', 'عدد', { en: 'Rob Quarter Gold Coin', emoji: '🪙' }),
  type('coin_gerami', 'سکه گرمی', 'COIN', 'عدد', { en: 'Gerami Gram Gold Coin', emoji: '🪙' }),

  // Counted in سوت (a thousand to the gram), whatever mix of sizes she holds, at the ۱ گرم
  // coin's rate; the small sizes' اجرت makes them worth more, and the override says so.
  type('parsian', 'سکه پارسیان', 'COIN', 'سوت', { en: 'Parsian Gold Coin', emoji: '🪙' }),

  // Nobody quotes her car or her land: the figure she types is the value, in Toman.
  type('car', 'خودرو', 'PROPERTY', 'تومان', { en: 'Car Vehicle', emoji: '🚗' }),
  type('house', 'خانه و ویلا', 'PROPERTY', 'تومان', { en: 'House Villa Home', emoji: '🏡' }),
  type('land', 'زمین', 'PROPERTY', 'تومان', { en: 'Land Plot', emoji: '🏞️' }),
];

/** Rate 1 by definition and not correctable: Toman, the bank balances, what she values herself. */
export const TOMAN_BY_DEFINITION: Record<string, number> = Object.fromEntries(
  [...STATIC_CATALOG.filter((t) => t.kind === 'PROPERTY').map((t) => t.id), TOMAN_ID, BANK_ID].map((id) => [id, 1]),
);

/** She types this one's value straight in Toman: no rate to show, none to correct. */
export const valuedInToman = (t: AssetType): boolean => t.kind === 'CASH' || t.kind === 'PROPERTY';

/** The one asset never added by hand, kept off STATIC_CATALOG so the picker never offers it. */
export const BANK_TYPE: AssetType = type(BANK_ID, 'حساب‌های بانکی', 'CASH', 'تومان', { en: 'Bank Accounts', emoji: '💳' });

const STATIC_BY_ID = new Map([...STATIC_CATALOG, BANK_TYPE, ...LEGACY_PARSIAN].map((t) => [t.id, t]));

/** Coin.toAssetType */
export const coinType = (c: Coin): AssetType =>
  type(c.id, c.name, 'CRYPTO', c.id.toUpperCase(), { dec: 6, en: c.en, iconUrl: c.icon || undefined, wallets: c.wallets });

/** Stock.toAssetType: the نماد is the name shown; the company goes in `en` to be searched. */
export const stockType = (s: Stock): AssetType =>
  type(s.id, s.symbol, 'STOCK', 'سهم', { dec: 0, en: s.name, emoji: '📈' });

/** The full picker list: fixed assets, then everything the network knows how to price. */
export const catalog = (coins: Coin[], stocks: Stock[] = []): AssetType[] =>
  [...STATIC_CATALOG, ...coins.map(coinType), ...stocks.map(stockType)];

/**
 * Static entries win, so a coin shipping as "nok" never shadows the currency; an id nothing
 * recognises still renders rather than vanishing with its value. `coins` may also be the
 * pre-resolved id → type map (Kotlin's `dynamic` overload).
 */
export function resolveType(id: string, coins: Coin[] | Map<string, AssetType>, stocks: Stock[] = []): AssetType {
  const fixed = STATIC_BY_ID.get(id);
  if (fixed) return fixed;
  if (coins instanceof Map) {
    const found = coins.get(id);
    if (found) return found;
  } else {
    const coin = coins.find((c) => c.id === id);
    if (coin) return coinType(coin);
    const stock = stocks.find((s) => s.id === id);
    if (stock) return stockType(stock);
  }
  return type(id, id.toUpperCase(), 'CRYPTO', id.toUpperCase(), { dec: 6 });
}

/**
 * Arabic ي and ك standing in for Persian ی and ک: TSETMC spells with them, her keyboard does not,
 * and unfolded a نماد is unfindable by anyone who types its name correctly.
 */
export const faLetters = (s: string): string => s.replaceAll('ي', 'ی').replaceAll('ك', 'ک');

// ZWNJ and spaces vary by keyboard and source ("بیت‌کوین", "بیت کوین", "بیتکوین").
const searchKey = (s: string): string => faLetters(s).replaceAll('‌', '').replaceAll(' ', '').toLowerCase();

/** Found by the Persian name she reads, the latin one she may know it by, or the ticker. */
export function matchesSearch(t: AssetType, query: string): boolean {
  const needle = searchKey(query);
  if (!needle) return false;
  return searchKey(t.fa).includes(needle) || searchKey(t.en).includes(needle) ||
    t.id.toLowerCase().includes(query.trim().toLowerCase());
}

/**
 * Fixed assets keep their catalogue order; coins fall in after them in the order she added them
 * (the sort is stable), since there is no meaningful order for 250 coins. (AppVm.catalogOrdered)
 */
const ORDER = new Map(STATIC_CATALOG.map((t, i) => [t.id, i]));
export const catalogOrdered = (list: Holding[]): Holding[] =>
  [...list].sort((a, b) => (ORDER.get(a.typeId) ?? Number.MAX_SAFE_INTEGER) - (ORDER.get(b.typeId) ?? Number.MAX_SAFE_INTEGER));

/**
 * Her holdings split into sections by kind, in the order they are stored — banding never moves
 * a row, and only kinds she holds appear.
 */
export function holdingsByKind(holdings: Holding[], coins: Coin[] | Map<string, AssetType>, stocks: Stock[] = []): Map<Kind, Holding[]> {
  const out = new Map<Kind, Holding[]>();
  for (const h of holdings) {
    const kind = resolveType(h.typeId, coins, stocks).kind;
    const group = out.get(kind);
    if (group) group.push(h); else out.set(kind, [h]);
  }
  return out;
}

/** What the total is made of, per kind, in the list's own sections; kinds worth nothing are left out. */
export function compositionByKind(
  holdings: Holding[], coins: Coin[], rates: Record<string, number>, stocks: Stock[] = [],
): Array<[Kind, number]> {
  return [...holdingsByKind(holdings, coins, stocks)]
    .map(([kind, held]): [Kind, number] => [kind, computeTotals(held, rates).toman])
    .filter(([, toman]) => toman > 0);
}
