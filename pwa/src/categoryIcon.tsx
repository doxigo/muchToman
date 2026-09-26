/**
 * CategoryIcon.kt: a mark per category, the same in the grid, the timeline and the report.
 *
 * The drawings are Lucide's (`lucide-static` 1.47.0, ISC) kept as path data, inked with this
 * app's pen — round caps and joins — so they stay one family with the hand-drawn tab bar. Six
 * are drawn by hand because no set has them: همسر's ring, the «unknown» dots, and the faces.
 *
 * Decorative on purpose: the category's name is always the text beside it.
 */
import type { ComponentChildren } from 'preact';
import type { Category } from './model';

export const CATEGORY_GLYPHS = [
  'BASKET', 'CUP', 'BUS', 'RECEIPT', 'CROSS', 'TAG', 'NOTE', 'PERCENT', 'TRAY', 'SWAP',
  'STACK', 'PLANE', 'GIFT', 'BLOOM', 'SHIRT', 'MUSIC', 'HOUSE', 'PERSON', 'LEND', 'PAYBACK',
  'INSTALMENT', 'SMOKE', 'WHEEL', 'WIFI', 'ENVELOPE', 'STAR', 'SHOP', 'CHART', 'ASTERISK',
  'RING', 'AIRPLANE', 'SCISSORS', 'BOTTLE', 'PIN', 'MUSCLE',
  'BALL', 'MIRROR', 'BOOK', 'DUMBBELL', 'BROOM',
  'SWEET', 'MEAT', 'CONE',
  // A category that outgrew its mark gets a new one: a category she made stores the name.
  'TAXI', 'PIGGY',
  'BUN', 'MUSTACHE', 'SMITTEN',
  'DOTS',
] as const;
export type CategoryGlyph = typeof CATEGORY_GLYPHS[number];

/** What she is offered for a category of her own: everything but the three dots of «unknown». */
export const PICKABLE_GLYPHS: CategoryGlyph[] = CATEGORY_GLYPHS.filter((g) => g !== 'DOTS');

/** A stored Category.glyph, or null when it names nothing this build draws. */
export const glyphNamed = (name: string): CategoryGlyph | null =>
  (CATEGORY_GLYPHS as readonly string[]).includes(name) ? name as CategoryGlyph : null;

/**
 * Keyed on the display name: the report aggregates by name and a settled transfer shows under a
 * name no row has. Anything unrecognised is DOTS, which is honest about knowing nothing.
 */
const BY_NAME = new Map<string, CategoryGlyph>([
  ['خواربار', 'BASKET'],
  ['رستوران و کافه', 'CUP'],
  ['حمل و نقل', 'BUS'],
  ['قبض‌ها', 'RECEIPT'],
  ['سلامت', 'CROSS'],
  ['خرید روزانه', 'TAG'],
  ['پس‌انداز و سرمایه', 'PIGGY'],
  ['انتقال وجه', 'PLANE'],
  ['هدیه و نیکوکاری', 'GIFT'],
  ['زیبایی', 'MIRROR'],
  ['آرایشگاه', 'SCISSORS'],
  ['آرایشی و بهداشتی', 'BOTTLE'],
  ['مد و پوشاک', 'SHIRT'],
  ['فرهنگی و هنری', 'MUSIC'],
  ['خانه و کاشانه', 'HOUSE'],
  ['خرج اتینا', 'PERSON'],
  ['ورزش', 'BALL'],
  ['آموزش', 'BOOK'],
  ['باشگاه', 'DUMBBELL'],
  ['نظافت', 'BROOM'],
  ['شیرینی', 'SWEET'],
  ['گوشت و مرغ', 'MEAT'],
  ['آبمیوه بستنی', 'CONE'],
  ['قرض', 'LEND'],
  ['پس‌گرفتن قرض', 'PAYBACK'],
  ['قسط و وام', 'INSTALMENT'],
  ['بازپرداخت اسنپ و تپسی', 'TAXI'],
  ['دخانیات', 'SMOKE'],
  ['خودرو', 'WHEEL'],
  ['اینترنت', 'WIFI'],
  ['سفر', 'AIRPLANE'],
  ['برداشت نقدی', 'NOTE'],
  ['کارمزد', 'PERCENT'],
  ['درآمد', 'TRAY'],
  ['حقوق', 'ENVELOPE'],
  ['پاداش', 'STAR'],
  ['فروش', 'SHOP'],
  ['سود سرمایه‌گذاری', 'CHART'],
  ['سایر', 'ASTERISK'],
  ['همسر', 'RING'],
  ['مامان', 'BUN'],
  ['مادر', 'BUN'],
  ['بابا', 'MUSTACHE'],
  ['پدر', 'MUSTACHE'],
  ['پارتنر', 'SMITTEN'],
  ['انتقال بین حساب‌ها', 'SWAP'],
]);
export const categoryGlyph = (nameFa: string): CategoryGlyph => BY_NAME.get(nameFa) ?? 'DOTS';

/** Name → mark for every category carrying one of its own; Kotlin's LocalCustomGlyphs. */
export function customGlyphs(categories: Category[]): Record<string, CategoryGlyph> {
  return Object.fromEntries(categories.flatMap((c) => {
    const stored = glyphNamed(c.glyph);
    const corrected = (c.nameFa === 'آموزش' || c.nameFa === 'باشگاه') && stored === 'BASKET' ? categoryGlyph(c.nameFa)
      : c.nameFa === 'نظافت' && stored === 'ASTERISK' ? 'BROOM'
        : stored;
    return corrected ? [[c.nameFa, corrected]] : [];
  }));
}

export const glyphOf = (nameFa: string, custom: Record<string, CategoryGlyph>): CategoryGlyph =>
  (Object.hasOwn(custom, nameFa) ? custom[nameFa] : categoryGlyph(nameFa));

/**
 * The colour a category is known by, [light, dark]. The dark set is lifted, because the same hue
 * on a dark card is a hole, not a mark. Spaced against the four-column grid, not the wheel: a
 * category and the one after it, and the one four after it, sit at least 60° apart — the long
 * why of every entry is in CategoryIcon.kt glyphHue.
 */
const HUES: Record<Exclude<CategoryGlyph, 'DOTS'>, [string, string]> = {
  BASKET: ['#55893A', '#A3D486'],
  CUP: ['#B0731E', '#F0BE6E'],
  BUS: ['#2A6EB4', '#85BEF0'],
  RECEIPT: ['#6A45B0', '#C2A8F0'],
  CROSS: ['#B94A4E', '#F2989B'],
  TAG: ['#AC4586', '#EC96CC'],
  NOTE: ['#17805A', '#6FD5A0'],
  PERCENT: ['#66737A', '#B4C0C6'],
  TRAY: ['#17805A', '#6FD5A0'],
  SWAP: ['#14798C', '#6FCFDE'],
  STACK: ['#2F8544', '#85D993'],
  PLANE: ['#14798C', '#6FCFDE'],
  GIFT: ['#4A52B8', '#A5AEF2'],
  BLOOM: ['#A85A38', '#F2AF92'],
  SHIRT: ['#7B4AA8', '#CBA4EA'],
  MUSIC: ['#B04A6E', '#F2A0BC'],
  HOUSE: ['#6E7526', '#C9CC7A'],
  PERSON: ['#14806A', '#6ED4B8'],
  LEND: ['#BC5138', '#F79C7E'],
  PAYBACK: ['#A9761F', '#EFC177'],
  INSTALMENT: ['#3C6390', '#94B8E8'],
  SMOKE: ['#96479B', '#DDA2E0'],
  WHEEL: ['#348030', '#89D486'],
  WIFI: ['#4538B2', '#A59EEB'],
  ENVELOPE: ['#3A5BC0', '#9FB2F2'],
  STAR: ['#A07A12', '#E8C46A'],
  SHOP: ['#B43F6B', '#F29BBB'],
  CHART: ['#A33FBE', '#DCA0F0'],
  ASTERISK: ['#9F4191', '#E892DB'],
  RING: ['#638A1B', '#B6D877'],
  AIRPLANE: ['#598A28', '#A6D478'],
  SCISSORS: ['#8F56A4', '#DBA5EE'],
  BOTTLE: ['#1B78A7', '#7BC9EA'],
  PIN: ['#1E854B', '#7ED39C'],
  MUSCLE: ['#917D17', '#DBC768'],
  BALL: ['#917D17', '#DBC768'],
  MIRROR: ['#A85A38', '#F2AF92'],
  BOOK: ['#3C6390', '#94B8E8'],
  DUMBBELL: ['#55893A', '#A3D486'],
  BROOM: ['#267F77', '#7ED3CB'],
  SWEET: ['#865AAC', '#D0ABF5'],
  MEAT: ['#AE532D', '#FAA685'],
  CONE: ['#5C69BC', '#A9B8FF'],
  TAXI: ['#1E854B', '#7ED39C'],
  PIGGY: ['#2F8544', '#85D993'],
  BUN: ['#A55C1E', '#F4AB77'],
  MUSTACHE: ['#208085', '#64D1D7'],
  SMITTEN: ['#B3485D', '#FBA0AC'],
};
/** DOTS knows nothing, so it borrows muted text and claims no hue. */
const DOTS_HUE = 'var(--on-surface-variant)';

export const hueOf = (glyph: CategoryGlyph, dark: boolean): string =>
  (glyph === 'DOTS' ? DOTS_HUE : HUES[glyph][dark ? 1 : 0]);

/** The same hue as a CSS colour that follows the theme by itself, off app.css's `--dark` (0 or 1). */
export const hueCss = (glyph: CategoryGlyph): string =>
  (glyph === 'DOTS' ? DOTS_HUE : `color-mix(in srgb, ${HUES[glyph][1]} calc(var(--dark) * 100%), ${HUES[glyph][0]})`);

export const categoryHue = (nameFa: string, custom: Record<string, CategoryGlyph>): string => hueCss(glyphOf(nameFa, custom));

/**
 * Lucide's drawing for every mark but the hand-drawn six, on its 24-unit grid. Every subpath
 * opens with an absolute M: joined after another, a relative m would start wherever that stopped.
 */
export const LUCIDE: Partial<Record<CategoryGlyph, string>> = {
  BASKET: 'M15 11l-1 9 M19 11l-4-7 M2 11h20 M3.5 11l1.6 7.4a2 2 0 0 0 2 1.6h9.8a2 2 0 0 0 2-1.6l1.7-7.4 M4.5 15.5h15 M5 11l4-7 M9 11l1 9', // shopping-basket
  CUP: 'M10 2v2 M14 2v2 M16 8a1 1 0 0 1 1 1v8a4 4 0 0 1-4 4H7a4 4 0 0 1-4-4V9a1 1 0 0 1 1-1h14a4 4 0 1 1 0 8h-1 M6 2v2', // coffee
  BUS: 'M8 6v6 M15 6v6 M2 12h19.6 M18 18h3s.5-1.7.8-2.8c.1-.4.2-.8.2-1.2 0-.4-.1-.8-.2-1.2l-1.4-5C20.1 6.8 19.1 6 18 6H4a2 2 0 0 0-2 2v10h3 M5 18a2 2 0 1 0 4 0a2 2 0 1 0 -4 0 M9 18h5 M14 18a2 2 0 1 0 4 0a2 2 0 1 0 -4 0', // bus
  RECEIPT: 'M12 17V7 M16 8h-6a2 2 0 0 0 0 4h4a2 2 0 0 1 0 4H8 M4 3a1 1 0 0 1 1-1 1.3 1.3 0 0 1 .7.2l.933.6a1.3 1.3 0 0 0 1.4 0l.934-.6a1.3 1.3 0 0 1 1.4 0l.933.6a1.3 1.3 0 0 0 1.4 0l.933-.6a1.3 1.3 0 0 1 1.4 0l.934.6a1.3 1.3 0 0 0 1.4 0l.933-.6A1.3 1.3 0 0 1 19 2a1 1 0 0 1 1 1v18a1 1 0 0 1-1 1 1.3 1.3 0 0 1-.7-.2l-.933-.6a1.3 1.3 0 0 0-1.4 0l-.934.6a1.3 1.3 0 0 1-1.4 0l-.933-.6a1.3 1.3 0 0 0-1.4 0l-.933.6a1.3 1.3 0 0 1-1.4 0l-.934-.6a1.3 1.3 0 0 0-1.4 0l-.933.6a1.3 1.3 0 0 1-.7.2 1 1 0 0 1-1-1z', // receipt
  CROSS: 'M11 2v2 M5 2v2 M5 3H4a2 2 0 0 0-2 2v4a6 6 0 0 0 12 0V5a2 2 0 0 0-2-2h-1 M8 15a6 6 0 0 0 12 0v-3 M18 10a2 2 0 1 0 4 0a2 2 0 1 0 -4 0', // stethoscope
  TAG: 'M12.586 2.586A2 2 0 0 0 11.172 2H4a2 2 0 0 0-2 2v7.172a2 2 0 0 0 .586 1.414l8.704 8.704a2.426 2.426 0 0 0 3.42 0l6.58-6.58a2.426 2.426 0 0 0 0-3.42z M7 7.5a0.5 0.5 0 1 0 1 0a0.5 0.5 0 1 0 -1 0', // tag
  NOTE: 'M4 6h16a2 2 0 0 1 2 2v8a2 2 0 0 1 -2 2h-16a2 2 0 0 1 -2 -2v-8a2 2 0 0 1 2 -2z M10 12a2 2 0 1 0 4 0a2 2 0 1 0 -4 0 M6 12h.01M18 12h.01', // banknote
  PERCENT: 'M19 5L5 19 M4 6.5a2.5 2.5 0 1 0 5 0a2.5 2.5 0 1 0 -5 0 M15 17.5a2.5 2.5 0 1 0 5 0a2.5 2.5 0 1 0 -5 0', // percent
  TRAY: 'M12 15V3 M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4 M7 10l5 5 5-5', // download
  SWAP: 'M8 3 4 7l4 4 M4 7h16 M16 21l4-4-4-4 M20 17H4', // arrow-left-right
  STACK: 'M3 5a9 3 0 1 0 18 0a9 3 0 1 0 -18 0 M3 5V19A9 3 0 0 0 21 19V5 M3 12A9 3 0 0 0 21 12', // database
  PLANE: 'M14.536 21.686a.5.5 0 0 0 .937-.024l6.5-19a.496.496 0 0 0-.635-.635l-19 6.5a.5.5 0 0 0-.024.937l7.93 3.18a2 2 0 0 1 1.112 1.11z M21.854 2.147l-10.94 10.939', // send
  GIFT: 'M12 7v14 M20 11v8a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2v-8 M7.5 7a1 1 0 0 1 0-5A4.8 8 0 0 1 12 7a4.8 8 0 0 1 4.5-5 1 1 0 0 1 0 5 M4 7h16a1 1 0 0 1 1 1v2a1 1 0 0 1 -1 1h-16a1 1 0 0 1 -1 -1v-2a1 1 0 0 1 1 -1z', // gift
  BLOOM: 'M9 12a3 3 0 1 0 6 0a3 3 0 1 0 -6 0 M12 16.5A4.5 4.5 0 1 1 7.5 12 4.5 4.5 0 1 1 12 7.5a4.5 4.5 0 1 1 4.5 4.5 4.5 4.5 0 1 1-4.5 4.5 M12 7.5V9 M7.5 12H9 M16.5 12H15 M12 16.5V15 M8 8l1.88 1.88 M14.12 9.88 16 8 M8 16l1.88-1.88 M14.12 14.12 16 16', // flower
  SHIRT: 'M20.38 3.46 16 2a4 4 0 0 1-8 0L3.62 3.46a2 2 0 0 0-1.34 2.23l.58 3.47a1 1 0 0 0 .99.84H6v10c0 1.1.9 2 2 2h8a2 2 0 0 0 2-2V10h2.15a1 1 0 0 0 .99-.84l.58-3.47a2 2 0 0 0-1.34-2.23z', // shirt
  MUSIC: 'M9 18V5l12-2v13 M3 18a3 3 0 1 0 6 0a3 3 0 1 0 -6 0 M15 16a3 3 0 1 0 6 0a3 3 0 1 0 -6 0', // music
  HOUSE: 'M15 21v-8a1 1 0 0 0-1-1h-4a1 1 0 0 0-1 1v8 M3 10a2 2 0 0 1 .709-1.528l7-6a2 2 0 0 1 2.582 0l7 6A2 2 0 0 1 21 10v9a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z', // house
  LEND: 'M11 15h2a2 2 0 1 0 0-4h-3c-.6 0-1.1.2-1.4.6L3 17 M7 21l1.6-1.4c.3-.4.8-.6 1.4-.6h4c1.1 0 2.1-.4 2.8-1.2l4.6-4.4a2 2 0 0 0-2.75-2.91l-4.2 3.9 M2 16l6 6 M13.1 9a2.9 2.9 0 1 0 5.8 0a2.9 2.9 0 1 0 -5.8 0 M3 5a3 3 0 1 0 6 0a3 3 0 1 0 -6 0', // hand-coins
  PAYBACK: 'M12 18H4a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h16a2 2 0 0 1 2 2v5 M16 19l3 3 3-3 M18 12h.01 M19 16v6 M6 12h.01 M10 12a2 2 0 1 0 4 0a2 2 0 1 0 -4 0', // banknote-arrow-down
  INSTALMENT: 'M16 14v2.2l1.6 1 M16 2v3 M21 7.338V5a2 2 0 00-2-2H5a2 2 0 00-2 2v14a2 2 0 002 2h2.338 M3 9h5.859 M8 2v3 M10 16a6 6 0 1 0 12 0a6 6 0 1 0 -12 0', // calendar-clock
  SMOKE: 'M17 12H3a1 1 0 0 0-1 1v2a1 1 0 0 0 1 1h14 M18 8c0-2.5-2-2.5-2-5 M21 16a1 1 0 0 0 1-1v-2a1 1 0 0 0-1-1 M22 8c0-2.5-2-2.5-2-5 M7 12v4', // cigarette
  WHEEL: 'M19 17h2c.6 0 1-.4 1-1v-3c0-.9-.7-1.7-1.5-1.9C18.7 10.6 16 10 16 10s-1.3-1.4-2.2-2.3c-.5-.4-1.1-.7-1.8-.7H5c-.6 0-1.1.4-1.4.9l-1.4 2.9A3.7 3.7 0 0 0 2 12v4c0 .6.4 1 1 1h2 M5 17a2 2 0 1 0 4 0a2 2 0 1 0 -4 0 M9 17h6 M15 17a2 2 0 1 0 4 0a2 2 0 1 0 -4 0', // car
  WIFI: 'M12 20h.01 M2 8.82a15 15 0 0 1 20 0 M5 12.859a10 10 0 0 1 14 0 M8.5 16.429a5 5 0 0 1 7 0', // wifi
  ENVELOPE: 'M22 7l-8.991 5.727a2 2 0 0 1-2.009 0L2 7 M4 4h16a2 2 0 0 1 2 2v12a2 2 0 0 1 -2 2h-16a2 2 0 0 1 -2 -2v-12a2 2 0 0 1 2 -2z', // mail
  STAR: 'M11.525 2.295a.53.53 0 0 1 .95 0l2.31 4.679a2.123 2.123 0 0 0 1.595 1.16l5.166.756a.53.53 0 0 1 .294.904l-3.736 3.638a2.123 2.123 0 0 0-.611 1.878l.882 5.14a.53.53 0 0 1-.771.56l-4.618-2.428a2.122 2.122 0 0 0-1.973 0L6.396 21.01a.53.53 0 0 1-.77-.56l.881-5.139a2.122 2.122 0 0 0-.611-1.879L2.16 9.795a.53.53 0 0 1 .294-.906l5.165-.755a2.122 2.122 0 0 0 1.597-1.16z', // star
  SHOP: 'M15 21v-5a1 1 0 0 0-1-1h-4a1 1 0 0 0-1 1v5 M17.774 10.31a1.12 1.12 0 0 0-1.549 0 2.5 2.5 0 0 1-3.451 0 1.12 1.12 0 0 0-1.548 0 2.5 2.5 0 0 1-3.452 0 1.12 1.12 0 0 0-1.549 0 2.5 2.5 0 0 1-3.77-3.248l2.889-4.184A2 2 0 0 1 7 2h10a2 2 0 0 1 1.653.873l2.895 4.192a2.5 2.5 0 0 1-3.774 3.244 M4 10.95V19a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-8.05', // store
  CHART: 'M16 7h6v6 M22 7l-8.5 8.5-5-5L2 17', // trending-up
  ASTERISK: 'M12 5v14 M18.065 8.496l-12.125 7 M5.94 8.504l12.125 7', // asterisk
  AIRPLANE: 'M17.8 19.2 16 11l3.5-3.5C21 6 21.5 4 21 3c-1-.5-3 0-4.5 1.5L13 8 4.8 6.2c-.5-.1-.9.1-1.1.5l-.3.5c-.2.5-.1 1 .3 1.3L9 12l-2 3H4l-1 1 3 2 2 3 1-1v-3l3-2 3.5 5.3c.3.4.8.5 1.3.3l.5-.2c.4-.3.6-.7.5-1.2z', // plane
  SCISSORS: 'M3 6a3 3 0 1 0 6 0a3 3 0 1 0 -6 0 M8.12 8.12 12 12 M20 4 8.12 15.88 M3 18a3 3 0 1 0 6 0a3 3 0 1 0 -6 0 M14.8 14.8 20 20', // scissors
  BOTTLE: 'M10.5 2v4 M14 2H7a2 2 0 0 0-2 2 M19.29 14.76A6.67 6.67 0 0 1 17 11a6.6 6.6 0 0 1-2.29 3.76c-1.15.92-1.71 2.04-1.71 3.19 0 2.22 1.8 4.05 4 4.05s4-1.83 4-4.05c0-1.16-.57-2.26-1.71-3.19 M9.607 21H6a2 2 0 0 1-2-2v-7a2 2 0 0 1 2-2h7V7a1 1 0 0 0-1-1H9a1 1 0 0 0-1 1v3', // soap-dispenser-droplet
  PIN: 'M20 10c0 4.993-5.539 10.193-7.399 11.799a1 1 0 0 1-1.202 0C9.539 20.193 4 14.993 4 10a8 8 0 0 1 16 0 M9 10a3 3 0 1 0 6 0a3 3 0 1 0 -6 0', // map-pin
  MUSCLE: 'M12.409 13.017A5 5 0 0 1 22 15c0 3.866-4 7-9 7-4.077 0-8.153-.82-10.371-2.462-.426-.316-.631-.832-.62-1.362C2.118 12.723 2.627 2 10 2a3 3 0 0 1 3 3 2 2 0 0 1-2 2c-1.105 0-1.64-.444-2-1 M15 14a5 5 0 0 0-7.584 2 M9.964 6.825C8.019 7.977 9.5 13 8 15', // biceps-flexed
  BALL: 'M11 7a16 16 20 0 1 10.98 4.362 M12 12a13 13 0 0 1-8.66 5 M16.83 13.634a16 16 0 0 1-9.267 7.328 M20.66 17A13 13 0 0 0 12 12a13 13 0 0 1 0-10 M8.17 15.366a16 16 0 0 1-1.713-11.69 M2 12a10 10 0 1 0 20 0a10 10 0 1 0 -20 0', // volleyball
  MIRROR: 'M11.017 2.814a1 1 0 0 1 1.966 0l1.051 5.558a2 2 0 0 0 1.594 1.594l5.558 1.051a1 1 0 0 1 0 1.966l-5.558 1.051a2 2 0 0 0-1.594 1.594l-1.051 5.558a1 1 0 0 1-1.966 0l-1.051-5.558a2 2 0 0 0-1.594-1.594l-5.558-1.051a1 1 0 0 1 0-1.966l5.558-1.051a2 2 0 0 0 1.594-1.594z M20 2v4 M22 4h-4 M2 20a2 2 0 1 0 4 0a2 2 0 1 0 -4 0', // sparkles
  BOOK: 'M12 5v16 M20.001 19A2 2 0 0022 17V5a2 2 0 00-1.999-2L16 3.002A5 5 0 0012 5a5 5 0 00-4-2H4a2 2 0 00-2 2v12a2 2 0 001.999 2H8a5 5 0 014 2 5 5 0 014-2z', // book-open
  DUMBBELL: 'M17.596 12.768a2 2 0 1 0 2.829-2.829l-1.768-1.767a2 2 0 0 0 2.828-2.829l-2.828-2.828a2 2 0 0 0-2.829 2.828l-1.767-1.768a2 2 0 1 0-2.829 2.829z M2.5 21.5l1.4-1.4 M20.1 3.9l1.4-1.4 M5.343 21.485a2 2 0 1 0 2.829-2.828l1.767 1.768a2 2 0 1 0 2.829-2.829l-6.364-6.364a2 2 0 1 0-2.829 2.829l1.768 1.767a2 2 0 0 0-2.828 2.829z M9.6 14.4l4.8-4.8', // dumbbell
  BROOM: 'M16 22l-1-4 M19 14a1 1 0 0 0 1-1v-1a2 2 0 0 0-2-2h-3a1 1 0 0 1-1-1V4a2 2 0 0 0-4 0v5a1 1 0 0 1-1 1H6a2 2 0 0 0-2 2v1a1 1 0 0 0 1 1 M19 14H5l-1.973 6.767A1 1 0 0 0 4 22h16a1 1 0 0 0 .973-1.233z M8 22l1-4', // brush-cleaning
  SWEET: 'M10 7v10.9 M14 6.1V17 M16 7V3a1 1 0 0 1 1.707-.707 2.5 2.5 0 0 0 2.152.717 1 1 0 0 1 1.131 1.131 2.5 2.5 0 0 0 .717 2.152A1 1 0 0 1 21 8h-4 M16.536 7.465a5 5 0 0 0-7.072 0l-2 2a5 5 0 0 0 0 7.07 5 5 0 0 0 7.072 0l2-2a5 5 0 0 0 0-7.07 M8 17v4a1 1 0 0 1-1.707.707 2.5 2.5 0 0 0-2.152-.717 1 1 0 0 1-1.131-1.131 2.5 2.5 0 0 0-.717-2.152A1 1 0 0 1 3 16h4', // candy
  MEAT: 'M15.4 15.63a7.875 6 135 1 1 6.23-6.23 4.5 3.43 135 0 0-6.23 6.23 M8.29 12.71l-2.6 2.6a2.5 2.5 0 1 0-1.65 4.65A2.5 2.5 0 1 0 8.7 18.3l2.59-2.59', // drumstick
  CONE: 'M7 11l4.08 10.35a1 1 0 0 0 1.84 0L17 11 M17 7A5 5 0 0 0 7 7 M17 7a2 2 0 0 1 0 4H7a2 2 0 0 1 0-4', // ice-cream-cone
  TAXI: 'M10 2h4 M21 8l-2 2-1.5-3.7A2 2 0 0 0 15.646 5H8.4a2 2 0 0 0-1.903 1.257L5 10 3 8 M7 14h.01 M17 14h.01 M5 10h14a2 2 0 0 1 2 2v4a2 2 0 0 1 -2 2h-14a2 2 0 0 1 -2 -2v-4a2 2 0 0 1 2 -2z M5 18v2 M19 18v2', // car-taxi-front
  PIGGY: 'M11 17h3v2a1 1 0 0 0 1 1h2a1 1 0 0 0 1-1v-3a3.16 3.16 0 0 0 2-2h1a1 1 0 0 0 1-1v-2a1 1 0 0 0-1-1h-1a5 5 0 0 0-2-4V3a4 4 0 0 0-3.2 1.6l-.3.4H11a6 6 0 0 0-6 6v1a5 5 0 0 0 2 4v3a1 1 0 0 0 1 1h2a1 1 0 0 0 1-1z M16 10h.01 M2 8v1a2 2 0 0 0 2 2h1', // piggy-bank
};

// ---- drawing ---------------------------------------------------------------------------------

/** The pen: round caps, round joins, nothing filled. Colour rides `currentColor`. */
export const PEN = { fill: 'none', stroke: 'currentColor', 'stroke-linecap': 'round', 'stroke-linejoin': 'round' } as const;
export const INK = { fill: 'currentColor', stroke: 'none' } as const;

/** A decorative square canvas; `color` is any CSS colour, var() and color-mix() included. */
export function Canvas({ size, color, class: cls, children }: { size: number; color: string; class?: string; children: ComponentChildren }) {
  return (
    <svg width={size} height={size} viewBox={`0 0 ${size} ${size}`} class={cls} aria-hidden="true" focusable="false"
      style={{ flex: 'none', display: 'block', color: color === 'currentColor' ? undefined : color }}>
      {children}
    </svg>
  );
}

/**
 * Compose's `arcTo(rect, start, sweep)` as SVG: a line to the arc's start (a move on an empty
 * path), then the arc. Degrees clockwise from 3 o'clock, as Compose measures them on screen.
 */
export function arcTo(cx: number, cy: number, rx: number, ry: number, start: number, sweep: number, move = false): string {
  const at = (deg: number) => [cx + rx * Math.cos(deg * Math.PI / 180), cy + ry * Math.sin(deg * Math.PI / 180)];
  const [sx, sy] = at(start);
  const [ex, ey] = at(start + sweep);
  return `${move ? 'M' : 'L'}${sx} ${sy} A${rx} ${ry} 0 ${Math.abs(sweep) > 180 ? 1 : 0} ${sweep > 0 ? 1 : 0} ${ex} ${ey}`;
}

/** A filled heart `s` across from its centre to either side, point down, for SMITTEN. */
const heart = (cx: number, cy: number, s: number): string =>
  `M${cx} ${cy + 0.8 * s}` +
  `C${cx - 0.1 * s} ${cy + 0.7 * s} ${cx - s} ${cy + 0.1 * s} ${cx - s} ${cy - 0.35 * s}` +
  `C${cx - s} ${cy - 0.75 * s} ${cx - 0.6 * s} ${cy - 0.95 * s} ${cx - 0.35 * s} ${cy - 0.9 * s}` +
  `C${cx - 0.15 * s} ${cy - 0.86 * s} ${cx} ${cy - 0.7 * s} ${cx} ${cy - 0.55 * s}` +
  `C${cx} ${cy - 0.7 * s} ${cx + 0.15 * s} ${cy - 0.86 * s} ${cx + 0.35 * s} ${cy - 0.9 * s}` +
  `C${cx + 0.6 * s} ${cy - 0.95 * s} ${cx + s} ${cy - 0.75 * s} ${cx + s} ${cy - 0.35 * s}` +
  `C${cx + s} ${cy + 0.1 * s} ${cx + 0.1 * s} ${cy + 0.7 * s} ${cx} ${cy + 0.8 * s}Z`;

/**
 * GlyphIcon. `stroke` is in CSS px at any `size`: 1.6 is the default, timeline rows pass 1.8 at
 * 22, the report 1.5 at 17, and an asset row size / 12 (the tab bar's 2 at 24).
 */
export function CategoryIcon({ glyph, size = 18, color = 'currentColor', stroke = 1.6, class: cls }: {
  glyph: CategoryGlyph; size?: number; color?: string; stroke?: number; class?: string;
}) {
  // The same proportion the tab bar keeps: nothing touches the edge of its own box.
  const o = size * 0.06;
  const b = size - 2 * o;
  const X = (f: number) => o + b * f;
  const pen = { ...PEN, 'stroke-width': stroke };
  const lucide = LUCIDE[glyph];
  if (lucide) {
    // Lucide's 2-unit margin dropped so it fills the box the hand-drawn ones fill; the pen is
    // divided by the scale so the ink comes out at this app's weight.
    const k = b / 20;
    return (
      <Canvas size={size} color={color} class={cls}>
        <path {...PEN} stroke-width={stroke / k} transform={`translate(${o} ${o}) scale(${k}) translate(-2 -2)`} d={lucide} />
      </Canvas>
    );
  }
  let marks;
  switch (glyph) {
    // A face pulling a daft one, tongue out: the mismatched eyes are the whole trick, drawn
    // wider apart than looks right on paper so they still read at 20px.
    case 'PERSON':
      marks = <>
        <circle {...pen} cx={X(0.5)} cy={X(0.46)} r={b * 0.42} />
        <circle {...INK} cx={X(0.35)} cy={X(0.4)} r={b * 0.045} />
        <circle {...INK} cx={X(0.66)} cy={X(0.34)} r={b * 0.115} />
        {/* Mouth and tongue as one stroke: two left a visible join at this size. */}
        <path {...pen} d={`M${X(0.32)} ${X(0.6)} Q${X(0.48)} ${X(0.75)} ${X(0.6)} ${X(0.63)} Q${X(0.72)} ${X(0.76)} ${X(0.57)} ${X(0.88)}`} />
      </>;
      break;
    // مامان: a smaller face under a cap of hair and a bun — the hair makes her a grown woman.
    case 'BUN': {
      const r = b * 0.38;
      const hair = `M${X(0.147)} ${X(0.42)} ${arcTo(X(0.5), X(0.56), r, r, 201.6, 136.8)}` +
        ` Q${X(0.68)} ${X(0.35)} ${X(0.5)} ${X(0.45)} Q${X(0.32)} ${X(0.35)} ${X(0.147)} ${X(0.42)}Z`;
      marks = <>
        <circle {...pen} cx={X(0.5)} cy={X(0.56)} r={r} />
        {/* Filled and inked, so its edge melts into the face's outline. */}
        <path {...pen} fill="currentColor" d={hair} />
        <circle {...INK} cx={X(0.5)} cy={X(0.12)} r={b * 0.11} />
        <circle {...INK} cx={X(0.37)} cy={X(0.6)} r={b * 0.055} />
        <circle {...INK} cx={X(0.63)} cy={X(0.6)} r={b * 0.055} />
        <path {...pen} d={`M${X(0.38)} ${X(0.74)} Q${X(0.5)} ${X(0.83)} ${X(0.62)} ${X(0.74)}`} />
      </>;
      break;
    }
    // بابا: a solid سبیل with its ends turned up, and no mouth — turned up, it is the smile.
    case 'MUSTACHE':
      marks = <>
        <circle {...pen} cx={X(0.5)} cy={X(0.5)} r={b * 0.42} />
        <circle {...INK} cx={X(0.35)} cy={X(0.4)} r={b * 0.06} />
        <circle {...INK} cx={X(0.65)} cy={X(0.4)} r={b * 0.06} />
        <path {...INK} d={`M${X(0.22)} ${X(0.6)} C${X(0.28)} ${X(0.68)} ${X(0.42)} ${X(0.66)} ${X(0.5)} ${X(0.59)}` +
          ` C${X(0.58)} ${X(0.66)} ${X(0.72)} ${X(0.68)} ${X(0.78)} ${X(0.6)}` +
          ` C${X(0.74)} ${X(0.74)} ${X(0.6)} ${X(0.78)} ${X(0.5)} ${X(0.7)}` +
          ` C${X(0.4)} ${X(0.78)} ${X(0.26)} ${X(0.74)} ${X(0.22)} ${X(0.6)}Z`} />
      </>;
      break;
    // پارتنر: hearts for eyes.
    case 'SMITTEN':
      marks = <>
        <circle {...pen} cx={X(0.5)} cy={X(0.5)} r={b * 0.42} />
        <path {...INK} d={heart(X(0.33), X(0.42), b * 0.13)} />
        <path {...INK} d={heart(X(0.67), X(0.42), b * 0.13)} />
        <path {...pen} d={`M${X(0.33)} ${X(0.64)} Q${X(0.5)} ${X(0.8)} ${X(0.67)} ${X(0.64)}`} />
      </>;
      break;
    // A ring with its stone sitting on the band — two circles is «coins» in this app.
    case 'RING':
      marks = <>
        <circle {...pen} cx={X(0.5)} cy={X(0.64)} r={b * 0.3} />
        <path {...pen} d={`M${X(0.5)} ${X(0.06)} L${X(0.68)} ${X(0.22)} L${X(0.5)} ${X(0.38)} L${X(0.32)} ${X(0.22)}Z`} />
      </>;
      break;
    // DOTS, and anything without a drawing: «unset» without the alarm a «؟» carries.
    default:
      marks = <>{[0.22, 0.5, 0.78].map((x) => <circle key={x} {...INK} cx={X(x)} cy={X(0.5)} r={b * 0.085} />)}</>;
  }
  return <Canvas size={size} color={color} class={cls}>{marks}</Canvas>;
}
