/**
 * دفتر as a spreadsheet (Csv.kt) — the one file this app writes that is *not* sealed. The rows are
 * دفتر's rows (`ledgerRows`: duplicates out, a split payment as its parts, transfers in and marked),
 * never a second derivation; the file is what Excel needs to open Persian without asking — UTF-8
 * with a BOM, CRLF lines, RFC 4180 quoting, Latin digits in the figures so a number lands as one.
 */
import { jalaliOf, tehranDay, tehranDayStart } from './jalali';
import type { LedgerEntry } from './model';
import { bankFa } from './sms';
import { ledgerCategoryFa, ledgerRows } from './timeline';

const HEADER = ['تاریخ', 'ساعت', 'مبلغ (ریال)', 'مبلغ (تومان)', 'بانک', 'طرف حساب', 'دسته', 'یادداشت', 'نوع', 'صاحب تراکنش'];

const two = (n: number): string => String(n).padStart(2, '0');

/** The whole file, BOM first, one line per row دفتر lists, in the order it lists them. */
export function ledgerCsv(entries: LedgerEntry[]): string {
  const lines = [HEADER.map(csvText)];
  for (const e of ledgerRows(entries.filter((it) => !it.duplicate), 'ALL', [], '')) {
    const txn = e.txn;
    // As دفتر draws it: a message that did not say which way prints with the minus there, so it
    // does here — and its نوع stays blank rather than claim a side nobody read.
    const rial = txn.signedRial ?? (txn.amountRial == null ? null : -txn.amountRial);
    const d = jalaliOf(txn.day);
    lines.push([
      `${d.year}/${two(d.month)}/${two(d.day)}`,
      // A row entered from a bare date sits on its midnight; «00:00» would be a minute nobody recorded.
      txn.at === tehranDayStart(txn.day) ? '' : csvClock(txn.at),
      rial == null ? '' : String(rial),
      rial == null ? '' : csvToman(rial),
      csvText(txn.sourceKind === 'manual' ? 'ورود دستی' : bankFa(txn.bank)),
      csvText(txn.merchant),
      csvText(ledgerCategoryFa(e)),
      csvText(e.note),
      e.transfer ? 'انتقال' : rial == null ? 'مانده' : txn.signedRial == null ? '' : rial < 0 ? 'خرج' : 'درآمد',
      csvText(e.ownerName),
    ]);
  }
  return '\uFEFF' + lines.map((cells) => `${cells.join(',')}\r\n`).join('');
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
 * A text cell. Merchant names and notes are somebody else's words, and a spreadsheet runs a cell
 * that starts with = + - @ as a formula, so those (and a leading tab or CR) get a leading ' and stay
 * text. Never applied to the figures, where a leading minus is the sign.
 */
export const csvText = (s: string): string => csvField(/^[=+\-@\t\r]/.test(s) ? `'${s}` : s);

/** RFC 4180: quoted when it holds a comma, a quote, CR or LF, inner quotes doubled. */
export const csvField = (s: string): string => (/[,"\r\n]/.test(s) ? `"${s.replace(/"/g, '""')}"` : s);

function csvClock(at: number): string {
  const since = at - tehranDayStart(tehranDay(at));
  return `${two(Math.trunc(since / 3_600_000))}:${two(Math.trunc(since / 60_000) % 60)}`;
}

/** Handed to the browser as a download — iOS Safari passes it to the share sheet. backup.ts's way. */
export function downloadLedgerCsv(entries: LedgerEntry[]): void {
  const url = URL.createObjectURL(new Blob([ledgerCsv(entries)], { type: 'text/csv;charset=utf-8' }));
  const a = document.createElement('a');
  a.href = url; a.download = ledgerCsvName();
  document.body.append(a); a.click(); a.remove();
  // Revoked late: Safari starts the download after the click returns.
  setTimeout(() => URL.revokeObjectURL(url), 60_000);
}
