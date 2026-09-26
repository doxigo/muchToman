/**
 * Demo.kt: fourteen months of a plausible household, for development only — the dev build's
 * `setDemoData` on the phone, reached here with `?demo=1` on the Vite dev server. Same seeded dice
 * (a 64-bit LCG, done in BigInt so it wraps exactly like Kotlin's Long), same lines, same drift,
 * so the browser's demo ledger is the phone's demo ledger row for row.
 *
 * Every id starts `demo-`, so the rows can be told apart and cleared; nothing here ever runs in
 * a production build.
 */
import { CAT_TRANSFER } from './rules';
import { DAY_MS, jalaliMonthLength, tehranDay, tehranDayStart } from './jalali';
import { reportMonthOf } from './reports';
import { batch, put, putAll, rows, setPref, erase } from './state';
import { currentTotals } from './data';
import type { Decision, Holding, ManualTxn } from './model';

export const DEMO_PREFIX = 'demo-';
const MASK = (1n << 64n) - 1n;

class Dice {
  private state: bigint;
  constructor(seed: number) { this.state = BigInt(seed) & MASK; }
  private next(): bigint {
    this.state = (this.state * 6364136223846793005n + 1442695040888963407n) & MASK;
    return this.state >> 16n;
  }
  int(from: number, to: number): number { return from + Number(this.next() % BigInt(to - from + 1)); }
  long(from: number, to: number): number { return from + Number(this.next() % BigInt(to - from + 1)); }
  pick<T>(items: T[]): T { return items[Number(this.next() % BigInt(items.length))]; }
  chance(percent: number): boolean { return this.int(1, 100) <= percent; }
}

type Line = [categoryId: string, merchants: string[], times: [number, number], toman: [number, number]];
const OUTGOINGS: Line[] = [
  ['cat_home', ['اجاره خانه'], [1, 1], [11_000_000, 13_000_000]],
  ['cat_instalment', ['قسط وام مسکن'], [1, 1], [3_200_000, 3_600_000]],
  ['cat_bills', ['قبض برق', 'قبض گاز', 'قبض آب', 'شارژ ساختمان'], [1, 3], [150_000, 900_000]],
  ['cat_internet', ['ایرانسل', 'همراه اول', 'شاتل'], [1, 1], [250_000, 500_000]],
  ['cat_groceries', ['هایپراستار', 'افق کوروش', 'جانبو', 'میوه‌فروشی محله', 'نانوایی', 'قصابی'], [5, 8], [400_000, 1_600_000]],
  ['cat_dining', ['کافه نادری', 'رستوران شاندیز', 'اسنپ‌فود', 'فست‌فود سیب'], [2, 5], [150_000, 900_000]],
  ['cat_transport', ['اسنپ', 'تپسی', 'مترو', 'اتوبوس'], [3, 7], [50_000, 250_000]],
  ['cat_shopping', ['دیجی‌کالا', 'بازار روز', 'لوازم خانگی'], [1, 3], [200_000, 1_200_000]],
  ['cat_atina', ['مهدکودک آتینا', 'اسباب‌بازی‌فروشی', 'کتاب کودک'], [1, 3], [200_000, 1_200_000]],
  ['cat_car', ['پمپ بنزین', 'تعمیرگاه', 'بیمه ایران', 'پارکینگ'], [0, 2], [300_000, 2_500_000]],
  ['cat_health', ['داروخانه هلال', 'مطب دکتر مهدوی', 'آزمایشگاه پارس'], [0, 2], [200_000, 3_000_000]],
  ['cat_clothing', ['پوشاک ال‌سی', 'کفش ملی'], [0, 1], [500_000, 4_000_000]],
  ['cat_beauty', ['آرایشگاه', 'لوازم آرایشی'], [0, 1], [200_000, 1_200_000]],
  ['cat_culture', ['سینما کورش', 'کتاب‌فروشی ققنوس'], [0, 1], [100_000, 600_000]],
  ['cat_gifts', ['هدیه تولد', 'کمک به خیریه'], [0, 1], [200_000, 2_000_000]],
  ['cat_spouse', ['کارت به کارت به همسر'], [0, 1], [1_000_000, 4_000_000]],
  ['cat_savings', ['خرید طلا', 'صندوق سرمایه‌گذاری'], [0, 1], [2_000_000, 6_000_000]],
];
const WINDFALLS: Line[] = [
  ['cat_sales', ['فروش در دیوار'], [0, 1], [1_000_000, 8_000_000]],
  ['cat_loan_back', ['پس‌گرفتن قرض'], [0, 1], [500_000, 4_000_000]],
];
const MONTHLY_INFLATION = 1.022;

export function demoLedger(today: number, now: number, months = 14): { transactions: ManualTxn[]; filings: Decision[] } {
  const here = reportMonthOf(today);
  const dice = new Dice(here.startDay * 1_000_003 + months);
  const txns: ManualTxn[] = []; const filings: Decision[] = [];
  let n = 0;
  const add = (day: number, toman: number, categoryId: string | null, merchant: string) => {
    n++;
    const id = `${DEMO_PREFIX}${String(n).padStart(3, '0')}`;
    const at = Math.min(tehranDayStart(day) + (8 + (n % 13)) * 3_600_000 + ((n * 7) % 60) * 60_000, now - n * 1_000);
    txns.push({ id, at, day, amountRial: toman * 10, accountId: null, categoryId, merchant, note: '', createdAt: now, updatedAt: now, deleted: false });
    if (categoryId) {
      const ref = `m:${id}`;
      filings.push({ id: `category:${ref}`, ref, kind: 'category', value: categoryId, createdAt: now, updatedAt: now, deleted: false, memberId: '', familyRef: '' });
    }
  };
  for (let back = months - 1; back >= 0; back--) {
    const month = here.back(back);
    const length = jalaliMonthLength(month.year, month.month);
    const last = back === 0 ? Math.min(Math.max(today - month.startDay + 1, 1), length) : length;
    const drift = MONTHLY_INFLATION ** (months - 1 - back);
    const scaled = ([a, b]: [number, number]) => Math.trunc(dice.long(a, b) * drift);
    const day = (from: number, to: number) => month.startDay + Math.min(dice.int(from, to), last) - 1;

    add(day(1, 3), Math.trunc(dice.long(36_000_000, 41_000_000) * drift), 'cat_salary', 'واریز حقوق');
    if (month.month === 12) add(day(20, 27), Math.trunc(dice.long(30_000_000, 45_000_000) * drift), 'cat_bonus', 'عیدی');
    for (const [cat, merchants, times, toman] of WINDFALLS) {
      for (let i = dice.int(times[0], times[1]); i > 0; i--) add(day(5, 26), scaled(toman), cat, dice.pick(merchants));
    }
    for (const [cat, merchants, times, toman] of OUTGOINGS) {
      for (let i = dice.int(times[0], times[1]); i > 0; i--) {
        const unfiled = dice.chance(7);
        add(day(2, length), -scaled(toman), unfiled ? null : cat, dice.pick(merchants));
      }
    }
    if (back % 2 === 0) {
      const moved = Math.trunc(dice.long(10_000_000, 30_000_000) * drift);
      const on = day(12, 20);
      add(on, -moved, CAT_TRANSFER, 'انتقال به حساب پس‌انداز');
      add(on, moved, CAT_TRANSFER, 'انتقال از حساب جاری');
    }
  }
  return { transactions: txns.sort((a, b) => a.at - b.at), filings };
}

const demoHoldings = (): Holding[] => [
  { typeId: 'gold18', amount: 38.5, id: `${DEMO_PREFIX}gold18`, excluded: false, wallet: null, label: '' },
  { typeId: 'coin_emami', amount: 4, id: `${DEMO_PREFIX}coin_emami`, excluded: false, wallet: null, label: '' },
  { typeId: 'usd', amount: 2_400, id: `${DEMO_PREFIX}usd`, excluded: false, wallet: null, label: '' },
  { typeId: 'toman', amount: 12_000_000, id: `${DEMO_PREFIX}toman`, excluded: false, wallet: null, label: '' },
];

/** A year of the total walking back from `total`, keyed by UTC epoch day exactly as recordDay keys it. */
function demoHistory(now: number, total: number, days = 395): Record<string, number> {
  const dice = new Dice(Math.trunc(now / DAY_MS) * 7_919);
  const out: Record<string, number> = {};
  let value = total;
  const end = Math.trunc(now / DAY_MS);
  for (let i = 0; i < days; i++) {
    out[String(end - i)] = value;
    value = (value / 1.0018) * (1 + dice.int(-4, 4) / 1000);
  }
  return out;
}

/**
 * Replaces whatever demo data is there with a fresh set; her own rows are never touched. The
 * history ends on the live total, as the phone's demoHistory(now, total) does, once rates are cached.
 */
export function seedDemo(): void {
  const now = Date.now();
  const { transactions, filings } = demoLedger(tehranDay(now), now);
  batch(() => {
    for (const r of rows('manual')) if (r.id.startsWith(DEMO_PREFIX)) erase('manual', r.id);
    putAll('manual', transactions);
    putAll('decisions', filings);
    put('anchors', { id: `${DEMO_PREFIX}SAMAN`, accountId: 'SAMAN', at: now, balanceRial: 845_000_000, source: 'user', createdAt: now, updatedAt: now, deleted: false });
    put('anchors', { id: `${DEMO_PREFIX}MELLAT`, accountId: 'MELLAT', at: now, balanceRial: 239_000_000, source: 'user', createdAt: now, updatedAt: now, deleted: false });
    setPref('holdings', demoHoldings());
    const live = currentTotals().toman;
    setPref('history', demoHistory(now, live > 0 ? live : 3_054_100_221));
    setPref('onboarded', true);
  });
}
