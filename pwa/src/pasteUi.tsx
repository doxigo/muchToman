/**
 * The one door the phone does not have. A browser cannot read the SMS inbox, so she pastes the
 * bank's message here and says which bank sent it — the answer the phone reads off the sender.
 * What was read is shown before anything is stored, in the ledger's own words, so a misread
 * figure is caught on the sheet rather than in a report.
 *
 * `#paste=<urlencoded text>` opens the sheet prefilled: an iOS Shortcut can hand a message over
 * from the share sheet without her copying it at all.
 *
 * A Shortcut's bundle (paste.ts `readBundle`) is the other way in: many messages at once, each with
 * its sender, so the bank is read off the phone's own table and asked only for a sender it lacks.
 * Handed over by link, a bundle whose every sender has a bank is filed without the sheet, as the
 * phone files what arrives: the Shortcut runs on its own as each message lands, with nobody there
 * to tap ثبت.
 */
import { useEffect, useLayoutEffect, useRef, useState } from 'preact/hooks';
import './timeline.css';
import './pasteUi.css';
import { ledger } from './derived';
import { addPastedBundle, addPastedSms } from './ledger';
import { bodyToStore, parsePasted, readBundle, severalMessages, writeBundle } from './paste';
import type { Bundled } from './paste';
import { PICKABLE_BANKS, bankFa, bankOf, isBank } from './sms';
import { BankLogo } from './logos';
import { bidi, faCompact, faDigits, faNumber, faSignedParts, faWordsToman, tomanOf } from './format';
import { closeSheet, openSheet, registerSheet, showNotice } from './nav';
import { pref, setPref } from './state';
import { ensureSeeded } from './rules';
import { PillButton, Sheet, SheetLabel, SheetTitle, SignedFigure, TextField } from './ui';
import { openTxn } from './timeline';

/** What of a bundle the store would keep: the tests it applies, so one it would refuse is counted and left. */
const storable = (bundle: Bundled[]): Bundled[] => bundle.filter((r) => !severalMessages(r.body) && bodyToStore(r.body) != null);

/**
 * A sender's bank: the phone's own table, else what she answered for that sender before. A bank's
 * number saved as a contact reaches a Shortcut as the contact's name, which no table knows — asked
 * once, it is filed like any other sender from then on. The guide sheet can forget the answers.
 */
function bankFor(sender: string): string | null {
  const answered = pref('senderBanks')[sender];
  return bankOf(sender) ?? (answered && isBank(answered) ? answered : null);
}

const noticeAdded = (added: number): void => showNotice(added ? `${faNumber(added)} پیامک ثبت شد` : 'پیامک تازه‌ای نبود');

function PasteSheet({ text: given = '' }: { text?: string }) {
  const [text, setText] = useState(given);
  // A second hand-off while the sheet is up replaces what is in it.
  useEffect(() => setText(given), [given]);
  const [bank, setBank] = useState(() => pref('lastPasteBank'));
  const [missingText, setMissingText] = useState(false);
  const [missingBank, setMissingBank] = useState(false);
  const [saving, setSaving] = useState(false);
  const [bundleAdded, setBundleAdded] = useState(0);

  const blank = !text.trim();
  // A bundle is read message by message, each held to the tests the store applies; one the store
  // would refuse is counted and left. The bank chips answer for one unknown sender at a time, so
  // one tap never names two senders' messages.
  const bundle = blank ? null : readBundle(text);
  const keep = bundle ? storable(bundle) : [];
  const asking = keep.find((r) => bankFor(r.sender) == null)?.sender;
  // An unknown sender is answered by a tap, never by the last bank she used: the answer is kept
  // (bankFor), and a second sender's messages must not be taken for the first's.
  useLayoutEffect(() => { if (asking != null) setBank(''); }, [asking]);
  // Refused live, the same tests the store applies: several messages at once, a one-time code, or
  // a body naming no money. Several is asked first, so a huge paste is never parsed per keystroke.
  const several = !blank && !bundle && severalMessages(text);
  const refused = !blank && !bundle && !several && bodyToStore(text) == null;
  const ok = !blank && !bundle && !several && !refused;
  const read = ok ? parsePasted(text) : null;

  const save = async (): Promise<void> => {
    // Never greyed: a tap that cannot save says why, with the keyboard out of the way of the words.
    // The one guard is against a second tap while the first is still writing.
    if (saving) return;
    (document.activeElement as HTMLElement | null)?.blur();
    setMissingText(blank);
    const answered = isBank(bank) || (bundle != null && asking == null);
    setMissingBank(!answered);
    if (bundle) {
      if (!keep.length || !answered) return;
      setSaving(true);
      if (asking) setPref('senderBanks', { ...pref('senderBanks'), [asking]: bank });
      const added = bundleAdded + await addPastedBundle(keep, (sender) => bankFor(sender) ?? (sender === asking ? bank : null));
      setSaving(false);
      // Another unknown sender's messages stay in the field for their own answer. The count waits
      // for the sheet to close: a notice now would sit over the commit she taps next.
      const left = keep.filter((r) => bankFor(r.sender) == null && r.sender !== asking);
      if (left.length) { setText(writeBundle(left)); setBundleAdded(added); return; }
      closeSheet();
      noticeAdded(added);
      return;
    }
    if (!ok || !isBank(bank)) return;
    setSaving(true);
    const source = await addPastedSms(text, bank);
    setSaving(false);
    if (!source) return;
    closeSheet();
    const entry = ledger().entries.find((e) => e.txn.srcHash === source.id);
    showNotice('پیامک ثبت شد', entry ? { label: 'ببینش', run: () => openTxn(entry.txn.ref) } : undefined);
  };

  return (
    <Sheet label="پیامک بانک">
      <SheetTitle>پیامک بانک</SheetTitle>
      <div class="paste-text">
        <TextField label="متن پیامک" value={text} multiline autoFocus={blank} onInput={setText}
          error={missingText && blank ? 'متن پیامک رو اینجا بچسبون.'
            : bundle && !keep.length ? 'هیچ‌کدوم از این پیامک‌ها تراکنش بانکی نیست.'
            : several ? 'این بیشتر از یک پیامکه. هر بار فقط یکی رو بچسبون.'
            : refused ? 'این پیامک تراکنش بانکی نیست، یا رمز یک‌بارمصرفه.' : null}
          support={bundle && bundleLine(keep, bundle.length - keep.length)} />
      </div>
      {/* Where several at once is the wish: before anything is pasted, and right after she tried. */}
      {(blank || several) && (
        <div class="paste-how">
          <PillButton label="چطوری پیامک‌ها خودکار بیان؟" onClick={() => openSheet('pasteShortcut', { text })} />
        </div>
      )}
      {read && <PastePreview read={read} />}

      {(!bundle || asking != null) && (
        <>
          <SheetLabel>
            {!bundle ? 'از کدوم بانک؟'
              : asking ? `پیام‌های ${bidi(faDigits(asking))} از کدوم بانکه؟` : 'پیام‌های بی‌فرستنده از کدوم بانکه؟'}
          </SheetLabel>
          <BankChips bank={bank} onBank={(b) => { setBank(b); setMissingBank(false); }} />
          {missingBank && !isBank(bank) && <p class="grid-error" role="status">بانکش رو انتخاب کن.</p>}
        </>
      )}

      <div class="sheet-actions">
        <PillButton label="ثبت" voice="primary" block onClick={() => void save()} />
        <PillButton label="انصراف" block onClick={closeSheet} />
      </div>
    </Sheet>
  );
}

/** What a bundle holds, in one line: «۱۲ پیام از ۳ بانک؛ ۲ تا از فرستنده‌ی ناشناس؛ ۱ تا تراکنش نبود». */
function bundleLine(keep: Bundled[], skipped: number): string {
  const banks = keep.map((r) => bankFor(r.sender)).filter((b) => b != null);
  const unknown = keep.length - banks.length;
  return [
    banks.length ? `${faNumber(banks.length)} پیام از ${faNumber(new Set(banks).size)} بانک` : '',
    unknown ? `${faNumber(unknown)} تا از فرستنده‌ی ناشناس` : '',
    skipped ? `${faNumber(skipped)} تا تراکنش نبود` : '',
  ].filter(Boolean).join('؛ ');
}

/** What was read, in the words the transaction page uses for the same facts. */
function PastePreview({ read }: { read: ReturnType<typeof parsePasted> }) {
  const incoming = read.direction === 'in';
  const toman = read.amountRial == null ? null : tomanOf(read.amountRial);
  const rows: Array<[string, string]> = [];
  if (read.direction) rows.push(['نوع', incoming ? 'واریز' : 'برداشت']);
  if (read.balanceRial != null) rows.push(['مانده بعد از تراکنش', bidi(`${faCompact(tomanOf(read.balanceRial))} تومان`)]);
  if (read.feeRial != null && read.feeRial > 0) rows.push(['کارمزد', bidi(`${faCompact(tomanOf(read.feeRial))} تومان`)]);
  if (read.printedAt.trim()) rows.push(['زمان ثبت', bidi(read.printedAt)]);
  if (read.mask.trim()) rows.push(['کارت یا حساب', bidi(read.mask)]);
  if (toman == null && !rows.length) return null;
  return (
    <div class="paste-preview" aria-live="polite">
      {toman != null ? (
        <>
          {/* No sign where the message names no direction: a guessed «−» is a claim. */}
          <p class={`figure paste-amount${incoming ? ' gain' : ''}`}>
            {read.direction ? <SignedFigure parts={faSignedParts(toman, incoming)} /> : faCompact(toman)}{' '}<span class="unit">تومان</span>
          </p>
          {faWordsToman(toman) && <p class="muted small">{faWordsToman(toman)}</p>}
        </>
      ) : <p class="caution paste-no-amount">مبلغ در پیامک مشخص نشده</p>}
      {rows.length > 0 && (
        <div class="paste-rows">
          {rows.map(([label, value]) => <div class="detail-row" key={label}><span>{label}</span><span>{value}</span></div>)}
        </div>
      )}
    </div>
  );
}

/** The banks as chips on one scrolling line, the one she used last already chosen and in view. */
function BankChips({ bank, onBank }: { bank: string; onBank: (bank: string) => void }) {
  const line = useRef<HTMLDivElement>(null);
  useLayoutEffect(() => {
    line.current?.querySelector('[aria-checked="true"]')?.scrollIntoView({ inline: 'center', block: 'nearest' });
  }, []);
  return (
    <div ref={line} class="chips scroll bank-chips" role="radiogroup" aria-label="از کدوم بانک؟">
      {PICKABLE_BANKS.map((b) => (
        <button type="button" key={b.name} class="chip bank-chip" role="radio" aria-checked={b.name === bank} onClick={() => onBank(b.name)}>
          <BankLogo bank={b.name} size={24} />
          {b.fa}
        </button>
      ))}
    </div>
  );
}

registerSheet('pasteSms', PasteSheet);

/** An iOS label, in the Latin she sees on the screen, set apart from the Persian around it. */
const En = ({ children }: { children: string }) => <bdi class="ios">{children}</bdi>;

/**
 * How to build the Shortcut a bundle comes from (paste.ts `readBundle`): it finds the bank messages,
 * writes them as a bundle and opens this page with it, and an automation runs it as each bank
 * message lands. The labels are Shortcuts' English ones; what differs between iOS versions is said,
 * not promised.
 */
function ShortcutSheet({ text = '' }: { text?: string }) {
  const answered = Object.entries(pref('senderBanks')).filter(([, bank]) => isBank(bank));
  return (
    <Sheet label="پیامک‌ها خودکار">
      <SheetTitle>پیامک‌ها خودکار</SheetTitle>
      <p class="howto">
        آیفون پیامک‌ها رو به هیچ اپی نمی‌ده، ولی اپ <En>Shortcuts</En> خود آیفون می‌تونه پیامک‌های بانک رو پیدا کنه
        و بیاره اینجا. یه بار درستش کنی، هر پیامک بانک که برسه این صفحه باز می‌شه و خودش ثبتش می‌کنه.
      </p>

      <SheetLabel>ساختن میان‌بر</SheetLabel>
      <ol class="howto">
        <li>توی <En>Shortcuts</En> برو تب <En>Shortcuts</En> و دکمه‌ی <En>+</En> رو بزن.</li>
        <li>
          اکشن <En>Find Message</En> رو اضافه کن. <En>All</En> رو بکن <En>Any</En> و برای هر بانک یه
          ردیف <En>Sender is</En> بساز، بدون ردیف <En>Date</En>. <En>Sort by</En> رو بذار <En>Date</En> و <En>Latest First</En>، <En>Limit</En> رو روشن
          کن و بذار ۲۰۰.
        </li>
        <li>اکشن <En>Repeat with Each</En> رو اضافه کن.</li>
        <li>
          داخلش یه اکشن <En>Text</En> بذار و این دو خط رو توش بساز:
          <pre class="howto-code" dir="ltr">{'#muchtoman Sender Date\nContent'}</pre>
          هر سه رو از <En>Repeat Item</En> بردار. روی <En>Date</En> بزن، فرمتش رو
          بذار <En>ISO 8601</En> و <En>Include ISO 8601 Time</En> رو روشن کن.
        </li>
        <li>
          بعد از <En>End Repeat</En>، اکشن <En>Combine Text</En> رو
          روی <En>Repeat Results</En> با <En>New Lines</En> بذار، بعد <En>Copy to Clipboard</En>.
        </li>
        <li>
          اکشن <En>URL Encode</En> رو روی <En>Combined Text</En> بذار، بعد <En>Open URLs</En> با این آدرس
          و خروجی <En>URL Encode</En> ته‌اش:
          <pre class="howto-code" dir="ltr">{`${location.origin}/#paste=`}</pre>
        </li>
      </ol>

      <SheetLabel>خودکار کردنش</SheetLabel>
      <ol class="howto">
        <li>توی <En>Shortcuts</En> برو تب <En>Automation</En>، دکمه‌ی <En>+</En> رو بزن و از لیست <En>Message</En> رو انتخاب کن.</li>
        <li>
          جلوی <En>Sender</En> فرستنده‌های بانک رو انتخاب کن. <En>Run Immediately</En> رو بزن
          و <En>Notify When Run</En> رو خاموش کن.
        </li>
        <li>بزن <En>Next</En> و میان‌بری که ساختی رو انتخاب کن.</li>
      </ol>

      <p class="howto muted">
        پیامکی که یه بار ثبت شده دوباره ثبت نمی‌شه، پس اگه یه بار اجرا نشد، دفعه‌ی بعد جاافتاده‌ها هم میان.
        اگه باز شدن این صفحه با هر پیامک اذیتت می‌کنه، به‌جای <En>Message</En>، <En>Time of Day</En> رو
        انتخاب کن تا روزی یه بار بیاد.
      </p>
      <p class="howto muted">
        اگه به‌جای این صفحه <En>Safari</En> باز شد، قدم ۶ رو پاک کن و خودکار کردن رو بی‌خیال شو: هر وقت
        خواستی میان‌بر رو اجرا کن و متن رو اینجا بچسبون.
      </p>
      <p class="howto muted">
        بعضی نسخه‌های iOS قبل از اجرای خودکار یه اعلان نشون می‌دن که باید بزنیش؛ اگه <En>Run Immediately</En> رو
        نمی‌بینی، گوشیت از این‌هاست. اگه شماره‌ی بانک توی مخاطب‌هاته، به‌جاش اسم مخاطب میاد و فقط بار اول
        بانکش ازت پرسیده می‌شه.
      </p>

      {answered.length > 0 && (
        <>
          <SheetLabel>فرستنده‌هایی که بانکشون رو گفتی</SheetLabel>
          {answered.map(([sender, bank]) => (
            <div class="detail-row" key={sender}><span>{bidi(faDigits(sender))}</span><span>{bankFa(bank)}</span></div>
          ))}
          <p class="howto muted">اگه بانکی رو اشتباه گفتی، فراموششون کن تا دفعه‌ی بعد دوباره بپرسه.</p>
          <PillButton label="فراموش کن" block onClick={() => setPref('senderBanks', {})} />
        </>
      )}

      <div class="sheet-actions">
        <PillButton label="برگرد به چسباندن" voice="primary" block onClick={() => openSheet('pasteSms', { text })} />
      </div>
    </Sheet>
  );
}
registerSheet('pasteShortcut', ShortcutSheet);

/**
 * An iOS Shortcut's hand-off: the message in the hash, read once and taken out of the address. A
 * bundle whose every sender has a bank is filed at once; anything that needs her opens the sheet.
 */
async function pasteFromHash(): Promise<void> {
  if (!location.hash.startsWith('#paste=')) return;
  const raw = location.hash.slice('#paste='.length);
  let text = raw;
  try { text = decodeURIComponent(raw); } catch { /* a stray % — keep the text as it came */ }
  history.replaceState(history.state, '', location.pathname + location.search);
  const bundle = readBundle(text);
  if (bundle) {
    // The remembered senders and the dedupe both read the store, which may still be loading.
    await ensureSeeded();
    const keep = storable(bundle);
    if (keep.every((r) => bankFor(r.sender) != null)) { noticeAdded(await addPastedBundle(keep, bankFor)); return; }
  }
  openSheet('pasteSms', { text });
}
if (typeof location !== 'undefined') {
  void pasteFromHash();
  addEventListener('hashchange', () => void pasteFromHash());
}
