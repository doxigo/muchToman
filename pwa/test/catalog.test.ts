/**
 * MoneyTest's catalogue half, CompositionTest, AssetGlyphTest and CategoryGlyphTest, with their
 * literal expectations. The one CategoryGlyphTest case left out needs BUILTIN_CATEGORIES, which
 * the ledger's rules port owns: every shipped name must map to a mark other than DOTS.
 */
import { describe, expect, it } from 'vitest';
import { AssetGlyph, assetGlyph } from '../src/assetGlyph';
import {
  BANK_TYPE, STATIC_CATALOG, TOMAN_ID, coinType, compositionByKind, holdingsByKind, matchesSearch, resolveType,
} from '../src/catalog';
import {
  CATEGORY_GLYPHS, LUCIDE, PICKABLE_GLYPHS, categoryGlyph, customGlyphs, glyphNamed, hueCss, hueOf,
} from '../src/categoryIcon';
import { computeTotals, effectiveRates } from '../src/data';
import type { Category, Coin, Holding } from '../src/model';

const H = (typeId: string, amount: number, extra: Partial<Holding> = {}): Holding =>
  ({ typeId, amount, excluded: false, wallet: null, label: '', id: '', ...extra });
const coin = (id: string, name: string, extra: Partial<Coin> = {}): Coin => ({ id, name, en: '', icon: '', wallets: [], ...extra });

describe('catalogue', () => {
  it('an unknown held asset still resolves instead of disappearing', () => {
    const t = resolveType('sol', []);
    expect(t.id).toBe('sol');
    expect(t.kind).toBe('CRYPTO');
  });

  it('a static id is never shadowed by a coin with the same ticker', () => {
    const t = resolveType('nok', [coin('nok', 'Some Coin')]);
    expect(t.fa).toBe('کرون نروژ');
    expect(t.kind).toBe('FIAT');
  });

  it('a coin is found by its persian name, its latin name or its ticker', () => {
    const dot = coinType(coin('dot', 'پولکادات', { en: 'Polkadot' }));
    for (const q of ['Polkadot', 'polkadot', 'POLKA', ' polka dot ', 'dot', 'DOT', 'پولکادات']) {
      expect(matchesSearch(dot, q), q).toBe(true);
    }
    expect(matchesSearch(dot, 'Cardano')).toBe(false);
    const sol = coinType(coin('sol', 'سولانا'));
    expect(matchesSearch(sol, 'سولانا')).toBe(true);
    expect(matchesSearch(sol, 'sol')).toBe(true);
  });

  it('arabic letters and ZWNJ fold away in search', () => {
    // TSETMC's spelling: Arabic kaf (U+0643) and yeh (U+064A).
    const shekar = { ...coinType(coin('tse_1', 'ش\u0643ر')), en: 'پالا\u064aش' };
    expect(matchesSearch(shekar, 'شکر')).toBe(true);
    expect(matchesSearch(shekar, 'پالایش')).toBe(true);
    expect(matchesSearch(coinType(coin('btc', 'بیت‌کوین')), 'بیت کوین')).toBe(true);
  });

  it('the fixed assets answer to english too', () => {
    const usd = STATIC_CATALOG.find((t) => t.id === 'usd')!;
    expect(matchesSearch(usd, 'dollar')).toBe(true);
    expect(matchesSearch(usd, 'USD')).toBe(true);
    expect(matchesSearch(usd, 'دلار')).toBe(true);
    expect(matchesSearch(STATIC_CATALOG.find((t) => t.id === 'gold18')!, 'gold')).toBe(true);
    expect(matchesSearch(usd, '   ')).toBe(false);
  });

  it('the picker offers one parsian coin, counted in سوت', () => {
    const offered = STATIC_CATALOG.filter((t) => t.id.startsWith('parsian'));
    expect(offered.map((t) => t.id)).toEqual(['parsian']);
    const [parsian] = offered;
    expect(parsian.fa).toBe('سکه پارسیان');
    expect(parsian.unitFa).toBe('سوت');
    expect(parsian.dec).toBe(0);
    expect(parsian.kind).toBe('COIN');
    expect(matchesSearch(parsian, 'پارسیان')).toBe(true);
    expect(matchesSearch(parsian, 'parsian')).toBe(true);
  });

  it('a holding saved against an old per-size parsian id still resolves', () => {
    for (let soot = 100; soot <= 1500; soot += 100) {
      const t = resolveType(`parsian_${soot}`, []);
      expect(t.id).toBe(`parsian_${soot}`);
      expect(t.kind).toBe('COIN');
      expect(t.unitFa).toBe('عدد');
    }
    expect(resolveType('parsian_100', []).fa).toBe('سکه پارسیان ۱۰۰ سوت');
    expect(resolveType('parsian_1500', []).fa).toBe('سکه پارسیان ۱۵۰۰ سوت');
    expect(matchesSearch(resolveType('parsian_400', []), '0.400')).toBe(true);
  });

  it('silver is priced by the gram at both purities', () => {
    const silver = STATIC_CATALOG.filter((t) => t.kind === 'SILVER');
    expect(silver.map((t) => t.id)).toEqual(['silver_999', 'silver_925']);
    expect(silver.every((t) => t.unitFa === 'گرم' && t.dec === 3)).toBe(true);
    expect(matchesSearch(silver[0], 'silver')).toBe(true);
    expect(matchesSearch(silver[0], 'نقره')).toBe(true);
  });

  it('no two assets in the catalogue share an id', () => {
    const ids = STATIC_CATALOG.map((t) => t.id);
    expect(new Set(ids).size).toBe(ids.length);
  });
});

describe('sections and composition', () => {
  const banded = [
    H(TOMAN_ID, 50_000_000), H('usd', 3_000), H('eur', 500), H('gold18', 12), H('btc', 0.5),
    H('sol', 40, { excluded: true }), H('doge', 100_000),
  ];
  const bandedCoins = [coin('btc', 'بیت‌کوین'), coin('sol', 'سولانا'), coin('doge', 'دوج‌کوین')];
  const bandedRates = effectiveRates({
    updatedAt: 1, coins: [],
    toman: { usd: 187_000, eur: 200_000, gold18: 90_000_000, btc: 10_000_000_000, sol: 14_000_000 },
  }, {});

  it('sections keep every holding, in the order the list already had them', () => {
    const sections = holdingsByKind(banded, bandedCoins);
    expect([...sections.keys()]).toEqual(['CASH', 'FIAT', 'GOLD', 'CRYPTO']);
    expect([...sections.values()].flat()).toEqual(banded);
  });

  it('the section subtotals add up to exactly the total above them', () => {
    const sections = holdingsByKind(banded, bandedCoins);
    const sum = [...sections.values()].reduce((s, held) => s + computeTotals(held, bandedRates).toman, 0);
    expect(sum).toBeCloseTo(computeTotals(banded, bandedRates).toman, 2);
    expect(computeTotals(sections.get('CRYPTO')!, bandedRates).toman).toBeCloseTo(0.5 * 10_000_000_000, 2);
  });

  const rates = { [TOMAN_ID]: 1, usd: 100, gold18: 500, btc: 9_000 };
  it('parts sum to the same total the hero shows', () => {
    const holdings = [H(TOMAN_ID, 1_000), H('usd', 10), H('gold18', 2), H('btc', 1)];
    const parts = compositionByKind(holdings, [], rates);
    expect(parts.reduce((s, [, t]) => s + t, 0)).toBe(computeTotals(holdings, rates).toman);
    expect(parts.map(([k]) => k)).toEqual(['CASH', 'FIAT', 'GOLD', 'CRYPTO']);
  });
  it('an excluded holding is not part of the composition', () => {
    expect(compositionByKind([H(TOMAN_ID, 1_000), H('usd', 10, { excluded: true })], [], rates).map(([k]) => k)).toEqual(['CASH']);
  });
  it('a kind worth nothing is left out', () => {
    expect(compositionByKind([H(TOMAN_ID, 1_000), H('usd', 0)], [], rates).map(([k]) => k)).toEqual(['CASH']);
  });
});

describe('asset marks', () => {
  it('every fixed asset draws a mark', () => {
    for (const t of [...STATIC_CATALOG, BANK_TYPE]) {
      if (t.id === TOMAN_ID) continue;
      expect(assetGlyph(t), t.id).not.toBeNull();
    }
  });
  it('a coin keeps its own logo', () => {
    expect(assetGlyph(coinType(coin('btc', 'بیت‌کوین', { en: 'Bitcoin', icon: '/coin-icon?x' })))).toBeNull();
  });
  it('is a component', () => expect(typeof AssetGlyph).toBe('function'));
});

describe('category marks', () => {
  const cat = (nameFa: string, glyph: string): Category =>
    ({ id: nameFa, parentId: null, nameFa, kind: 'expense', sort: 0, builtin: false, archived: false, updatedAt: 1, glyph });

  it('a category from a device that renamed it still draws something', () => {
    expect(categoryGlyph('چیزی که بلد نیستم')).toBe('DOTS');
  });
  it('a stored mark survives the round trip, and a made-up one does not', () => {
    for (const g of CATEGORY_GLYPHS) expect(glyphNamed(g)).toBe(g);
    expect(glyphNamed('SPACESHIP')).toBeNull();
    expect(glyphNamed('')).toBeNull();
  });
  it('every mark has exactly one drawing', () => {
    const byHand = new Set(['RING', 'PERSON', 'DOTS', 'BUN', 'MUSTACHE', 'SMITTEN']);
    const lucide = new Set(Object.keys(LUCIDE));
    expect(CATEGORY_GLYPHS.filter((g) => !lucide.has(g) && !byHand.has(g))).toEqual([]);
    expect([...lucide].filter((g) => byHand.has(g))).toEqual([]);
  });
  it('every Lucide line opens absolute', () => {
    for (const [g, d] of Object.entries(LUCIDE)) expect(d.startsWith('M'), g).toBe(true);
  });
  it('the picker offers every mark except the one that means unknown', () => {
    expect(PICKABLE_GLYPHS.length).toBe(CATEGORY_GLYPHS.length - 1);
    expect(PICKABLE_GLYPHS.includes('DOTS')).toBe(false);
  });
  it('a category she made is drawn by its own mark, not by its name', () => {
    expect(customGlyphs([cat('باشگاه', 'STAR')])).toEqual({ 'باشگاه': 'STAR' });
    expect(customGlyphs([cat('خواربار', '')])).toEqual({});
  });
  it('legacy placeholder marks get the named icons while explicit alternatives survive', () => {
    expect(customGlyphs([cat('آموزش', 'BASKET'), cat('باشگاه', 'BASKET'), cat('نظافت', 'ASTERISK')]))
      .toEqual({ 'آموزش': 'BOOK', 'باشگاه': 'DUMBBELL', 'نظافت': 'BROOM' });
    expect(customGlyphs([cat('باشگاه', 'STAR')])['باشگاه']).toBe('STAR');
  });
  it('hues come in a light and a dark, and DOTS claims nothing', () => {
    expect(hueOf('BASKET', false)).toBe('#55893A');
    expect(hueOf('BASKET', true)).toBe('#A3D486');
    expect(hueOf('DOTS', true)).toBe('var(--on-surface-variant)');
    expect(hueCss('BASKET')).toContain('var(--dark)');
    for (const g of CATEGORY_GLYPHS) expect(hueOf(g, false), g).toMatch(/^#[0-9A-F]{6}$|^var\(/);
  });
});
