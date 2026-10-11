/**
 * دفتر as a spreadsheet (Csv.kt) — the one file this app writes that is *not* sealed. Transactions
 * alone say nothing about what is left, so it opens with what each member has as it is made (the
 * hero's own lines, typed «موجودی») and every row carries the مانده its bank stated after it. The
 * rows are دفتر's rows (`ledgerRows`: duplicates out, a split payment as its parts, transfers in and
 * marked), never a second derivation; the file is what Excel needs to open Persian without asking —
 * UTF-8 with a BOM, CRLF lines, RFC 4180 quoting, Latin digits in the figures so a number lands as one.
 */
import type { AssetShareItem } from './data';
import { jalaliOf, tehranDay, tehranDayStart } from './jalali';
import type { LedgerEntry } from './model';
import { bankFa } from './sms';
import { ledgerCategoryFa, ledgerRows } from './timeline';

const HEADER = [
  'تاریخ', 'ساعت', 'مبلغ (ریال)', 'مبلغ (تومان)', 'موجودی (ریال)', 'موجودی (تومان)',
  'بانک', 'طرف حساب', 'دسته', 'یادداشت', 'نوع', 'صاحب تراکنش',
];

/** One member's دارایی for the «موجودی» lines: a `FamilyAssetView`, or hers built the same way. */
export interface CsvBalances { name: string; items: AssetShareItem[] }

const two = (n: number): string => String(n).padStart(2, '0');

/**
 * The whole file, BOM first: a line for each thing each of `balances` holds, stamped `now`, then one
 * line per row دفتر lists, in the order it lists them.
 */
export function ledgerCsv(entries: LedgerEntry[], balances: CsvBalances[] = [], now = Date.now()): string {
  const lines = [HEADER.map(csvText)];
  // Nothing moved, so no مبلغ: a figure there would be summed with the spending under it.
  for (const member of balances) {
    for (const item of member.items) {
      if (!Number.isFinite(item.toman)) continue;
      const rial = csvRial(item.toman);
      lines.push([csvDate(tehranDay(now)), csvClock(now), '', '', String(rial), csvToman(rial),
        csvText(item.name), '', '', '', 'موجودی', csvText(member.name)]);
    }
  }
  let previous = '';
  for (const e of ledgerRows(entries.filter((it) => !it.duplicate), 'ALL', [], '')) {
    const txn = e.txn;
    // As دفتر draws it: a message that did not say which way prints with the minus there, so it
    // does here — and its نوع stays blank rather than claim a side nobody read.
    const rial = txn.signedRial ?? (txn.amountRial == null ? null : -txn.amountRial);
    // The parts of a split payment sit together under the one message's ref, and the مانده that
    // message stated goes on the first of them, not once per part.
    const balance = txn.ref === previous ? null : txn.balanceRial;
    previous = txn.ref;
    lines.push([
      csvDate(txn.day),
      // A row entered from a bare date sits on its midnight; «00:00» would be a minute nobody recorded.
      txn.at === tehranDayStart(txn.day) ? '' : csvClock(txn.at),
      rial == null ? '' : String(rial),
      rial == null ? '' : csvToman(rial),
      balance == null ? '' : String(balance),
      balance == null ? '' : csvToman(balance),
      csvText(txn.sourceKind === 'manual' ? 'ورود دستی' : bankFa(txn.bank)),
      csvText(txn.merchant),
      csvText(ledgerCategoryFa(e)),
      csvText(e.note),
      e.transfer ? 'انتقال' : rial == null ? 'مانده' : txn.signedRial == null ? '' : rial < 0 ? 'خرج' : 'درآمد',
      csvText(e.ownerName),
    ]);
  }
  return '﻿' + lines.map((cells) => `${cells.join(',')}\r\n`).join('');
}

/** `muchtoman-1405-07-11.csv` — today in Tehran, Jalali, Latin digits. */
export function ledgerCsvName(now = Date.now()): string {
  const d = jalaliOf(tehranDay(now));
  return `muchtoman-${d.year}-${two(d.month)}-${two(d.day)}.csv`;
}

/** Rial ÷ 10, exactly: the decimal only when there is one, and never a digit rounded away. */
export function csvToman(rial: number): string {
  const m = Math.abs(rial);
  return `${rial < 0 ? '-' : ''}${Math.trunc(m / 10)}${m % 10 ? `.${m % 10}` : ''}`;
}

/**
 * A Toman value as whole Rial, cut and never rounded up (Csv.kt `csvRial`): settled at nine places
 * first, so a binary hair under a Rial she really has does not eat it.
 */
export const csvRial = (toman: number): number => Math.trunc(Number((toman * 10).toFixed(9)));

/**
 * A text cell. Merchant names and notes are somebody else's words, and a spreadsheet runs a cell
 * that starts with = + - @ as a formula, so those (and a leading tab or CR) get a leading ' and stay
 * text. Never applied to the figures, where a leading minus is the sign.
 */
export const csvText = (s: string): string => csvField(/^[=+\-@\t\r]/.test(s) ? `'${s}` : s);

/** RFC 4180: quoted when it holds a comma, a quote, CR or LF, inner quotes doubled. */
export const csvField = (s: string): string => (/[,"\r\n]/.test(s) ? `"${s.replace(/"/g, '""')}"` : s);

function csvDate(day: number): string {
  const d = jalaliOf(day);
  return `${d.year}/${two(d.month)}/${two(d.day)}`;
}

function csvClock(at: number): string {
  const since = at - tehranDayStart(tehranDay(at));
  return `${two(Math.trunc(since / 3_600_000))}:${two(Math.trunc(since / 60_000) % 60)}`;
}

/** Handed to the browser as a download — iOS Safari passes it to the share sheet. backup.ts's way. */
export function downloadLedgerCsv(entries: LedgerEntry[], balances: CsvBalances[] = []): void {
  const url = URL.createObjectURL(new Blob([ledgerCsv(entries, balances)], { type: 'text/csv;charset=utf-8' }));
  const a = document.createElement('a');
  a.href = url; a.download = ledgerCsvName();
  document.body.append(a); a.click(); a.remove();
  // Revoked late: Safari starts the download after the click returns.
  setTimeout(() => URL.revokeObjectURL(url), 60_000);
}
