/**
 * تنظیمات — an index of rooms, not a scroll of everything (Settings.kt). Her at the top, three
 * bands of doors, the version line; every setting lives in the room it belongs to, beside the
 * sentences that qualify it.
 *
 * The rooms are the page's own `room` prop rather than more routes: each is only ever one level
 * behind the index, and opening one pushes the same page again, so the browser's back steps from a
 * room to the index exactly as the phone's BackHandler does.
 *
 * Two rooms change meaning in the browser. پیامک‌های بانک cannot switch an inbox on — the browser
 * has none — so its switch becomes the door to the paste sheet. The lock is WebAuthn's platform
 * authenticator (lock.tsx); the widget has no browser counterpart and its row is gone.
 */
import type { ComponentChildren } from 'preact';
import { useEffect, useRef, useState } from 'preact/hooks';
import './settings.css';
import { applyRestore, backupFaultFa, BACKUP_MIN_PASSPHRASE, exportBackup, readBackupFile } from './backup';
import type { BrowserPayload } from './backup';
import { CategoryIcon } from './categoryIcon';
import type { CategoryGlyph } from './categoryIcon';
import { refreshAll } from './data';
import { useLedger } from './derived';
import type { LedgerHealth } from './derived';
import { useFamily } from './family';
import { bidi, faAgo, faCompact, faDate, faNumber, tomanOf } from './format';
import { Chevron, PersonMark } from './icons';
import { INSTALLMENT_REMINDER_DAYS, installmentReminderFa } from './installments';
import { tehranDay } from './jalali';
import { setLedgerStartsOn, toggleBankDisabled } from './ledger';
import { disableLock, enableLock, lockAvailable } from './lock';
import { BankLogo } from './logos';
import type { ThemeMode } from './model';
import { closeSheet, openPage, openSheet, registerPage, registerSheet } from './nav';
import { reportMonthOf } from './reports';
import type { ReportMonth } from './reports';
import { BANKS } from './sms';
import { pref, setPref, useData } from './state';
import { PillButton, Screen, SegmentedChoice, Sheet, SheetTitle, TextField } from './ui';

type Room = 'INDEX' | 'SMS' | 'SECURITY' | 'BACKUP' | 'CACHE' | 'HEALTH';

const THEME_FA: Record<ThemeMode, string> = { SYSTEM: 'خودکار', LIGHT: 'روشن', DARK: 'تیره' };
const THEMES: ThemeMode[] = ['SYSTEM', 'LIGHT', 'DARK'];

// ---- the shared pieces (Settings.kt's internal ones, which خانواده wears too) -------------------

/** The frame every page behind the index wears: its name, «برگشت» in a pill, and the body. */
export function SettingsPage({ title, children }: { title: string; children: ComponentChildren }) {
  return <Screen title={title} back tabs={false}>{children}</Screen>;
}

export function SectionLabel({ children }: { children: ComponentChildren }) {
  return <h2 class="set-label">{children}</h2>;
}

export function Note({ children, tone }: { children: ComponentChildren; tone?: 'strong' | 'error' }) {
  return <p class={`set-note${tone ? ` ${tone}` : ''}`} aria-live={tone === 'error' ? 'polite' : undefined}>{children}</p>;
}

/** A band: rows of one kind grouped into one rounded object, hairline-divided. */
export function Band({ children, flat }: { children: ComponentChildren; flat?: boolean }) {
  return <div class={`set-band${flat ? ' flat' : ''}`}>{children}</div>;
}

const Chev = () => <span class="chev"><Chevron size={24} /></span>;
const Glyph = ({ glyph }: { glyph: CategoryGlyph }) => <CategoryIcon glyph={glyph} size={22} />;

/** One door on the index: a mark on the green disc, a name, what it is set to, the chevron. */
function IndexRow({ title, value, onClick, mark }: { title: string; value?: string | null; onClick: () => void; mark: ComponentChildren }) {
  return (
    <button type="button" class="set-row" onClick={onClick}>
      <span class="set-disc door">{mark}</span>
      <span class="set-text"><span class="set-title one" style={{ display: 'block' }}>{title}</span></span>
      {value != null && <span class="set-value">{value}</span>}
      <Chev />
    </button>
  );
}

/**
 * One switched setting: its mark, what it does, and the switch. The whole row is the switch,
 * named by its title — a row of bare "switch, on" tells a screen reader nothing.
 */
export function SettingCard({ title, subtitle, checked, onChange, enabled = true, badge, mark }: {
  title: string; subtitle: string; checked: boolean; onChange: (on: boolean) => void; enabled?: boolean;
  /** Replaces the disc, for a row with a full mark of its own (a bank's logo). */
  badge?: ComponentChildren; mark?: ComponentChildren;
}) {
  return (
    <button type="button" class="set-row" role="switch" aria-checked={checked} disabled={!enabled} onClick={() => onChange(!checked)}>
      {badge ?? <span class="set-disc">{mark}</span>}
      <span class="set-text">
        <span class="set-title one" style={{ display: 'block' }}>{title}</span>
        {subtitle.trim() && <span class="set-sub" style={{ display: 'block' }}>{subtitle}</span>}
      </span>
      <span class="switch" aria-hidden="true" aria-checked={checked} />
    </button>
  );
}

/** A row that is a door, in the band the switches wear. `chevron` off for rows that act in place. */
export function DoorRow({ title, subtitle, glyph, enabled = true, onClick, chevron = true }: {
  title: string; subtitle: string; glyph: CategoryGlyph; enabled?: boolean; onClick: () => void; chevron?: boolean;
}) {
  return (
    <button type="button" class="set-row" disabled={!enabled} onClick={onClick}>
      <span class="set-disc"><Glyph glyph={glyph} /></span>
      <span class="set-text">
        <span class="set-title" style={{ display: 'block' }}>{title}</span>
        {/* Blank until there is something to say — خانواده's sync row before its first run. */}
        {subtitle.trim() && <span class="set-sub" style={{ display: 'block' }}>{subtitle}</span>}
      </span>
      {chevron && <Chev />}
    </button>
  );
}

/** Two taps, the consequence named on the second — and announced, so the armed state exists for ears too. */
export function ArmedAction({ label, armedLabel, enabled = true, onConfirmed }: {
  label: string; armedLabel: string; enabled?: boolean; onConfirmed: () => void;
}) {
  const [armed, setArmed] = useState(false);
  return (
    <button type="button" class="text-btn danger block" disabled={!enabled} style={{ fontWeight: armed ? 700 : 600, fontSize: '15px' }}
      onClick={() => { if (armed) { setArmed(false); onConfirmed(); } else setArmed(true); }}>
      <span aria-live="polite">{armed ? armedLabel : label}</span>
    </button>
  );
}

/** «بی‌خیال» and its kin: always present, always a real way out. */
export function QuietButton({ label, onClick, disabled }: { label: string; onClick: () => void; disabled?: boolean }) {
  return (
    <button type="button" class="text-btn block" disabled={disabled} style={{ color: 'var(--on-surface-variant)', fontWeight: 400 }} onClick={onClick}>{label}</button>
  );
}

// ---- the marks the page draws in its own pen ------------------------------------------------------

/** The lock, as a line mark: a shackle over a rounded body. */
function LockGlyph({ size = 22 }: { size?: number }) {
  const w = size;
  return (
    <svg width={w} height={w} viewBox={`0 0 ${w} ${w}`} aria-hidden="true" fill="none" stroke="currentColor" stroke-width={1.8} stroke-linecap="round" stroke-linejoin="round">
      <path d={`M${w * 0.29} ${w * 0.46} A${w * 0.21} ${w * 0.21} 0 0 1 ${w * 0.71} ${w * 0.46}`} />
      <rect x={w * 0.18} y={w * 0.44} width={w * 0.64} height={w * 0.4} rx={w * 0.1} />
    </svg>
  );
}

/** A ring with one half filled — light against dark, in the app's pen. */
function AppearanceGlyph({ size = 22 }: { size?: number }) {
  const c = size / 2; const r = size * 0.38;
  return (
    <svg width={size} height={size} viewBox={`0 0 ${size} ${size}`} aria-hidden="true">
      <circle cx={c} cy={c} r={r} fill="none" stroke="currentColor" stroke-width={1.8} />
      <path d={`M${c} ${c + r} A${r} ${r} 0 0 1 ${c} ${c - r} Z`} fill="currentColor" />
    </svg>
  );
}

// ---- the index ----------------------------------------------------------------------------------

/** "1.0" -> "۱٫۰" so the one latin run on a Persian page disappears. */
const faVersion = (v: string): string => v.replace(/[0-9]/g, (d) => String.fromCharCode(0x6f0 + Number(d))).replace(/\./g, '٫');
// The release tag, stamped at build time (vite.config.ts); the phone's own placeholder without one.
const VERSION = import.meta.env.VITE_VERSION || '1.0';

const openRoom = (room: Room): void => openPage('settings', { room });

function SettingsIndex() {
  useData();
  const family = useFamily();
  const name = pref('name').trim();
  return (
    // «برگشت», not «ذخیره»: every setting here commits the moment it is touched.
    <Screen title="تنظیمات" back tabs={false}>
      <button type="button" class="identity" onClick={() => openSheet('name')}>
        <span class="disc" style={{ width: '56px', height: '56px', background: 'var(--primary-container)', color: 'var(--on-primary-container)' }}>
          <PersonMark size={28} />
        </span>
        <span class={`name${name ? '' : ' blank'}`}>{name || 'اسمت رو بنویس'}</span>
        <Chev />
      </button>

      <SectionLabel>دفترت</SectionLabel>
      <Band>
        <IndexRow title="پیامک‌های بانک" onClick={() => openRoom('SMS')} mark={<Glyph glyph="ENVELOPE" />} />
        <IndexRow title="دسته‌بندی‌ها" onClick={() => openPage('categories')} mark={<Glyph glyph="TAG" />} />
        {/* No value until there is a household: an unpaired browser is simply somebody's own book. */}
        <IndexRow title="خانواده" value={family.paired ? `${faNumber(family.members.length)} عضو` : null}
          onClick={() => openPage('family')} mark={<Glyph glyph="HOUSE" />} />
      </Band>

      <SectionLabel>برنامه</SectionLabel>
      <Band>
        <IndexRow title="ظاهر برنامه" value={THEME_FA[pref('themeMode')]} onClick={() => openSheet('theme')} mark={<AppearanceGlyph />} />
        <IndexRow title="قفل و امنیت" value={pref('lockEnabled') ? 'روشن' : 'خاموش'} onClick={() => openRoom('SECURITY')} mark={<LockGlyph />} />
        <IndexRow title="یادآوری قسط" value={installmentReminderFa(pref('installmentReminder'))} onClick={() => openSheet('installmentReminder')}
          mark={<Glyph glyph="INSTALMENT" />} />
      </Band>

      <SectionLabel>نگهداری</SectionLabel>
      <Band>
        <IndexRow title="پشتیبان‌گیری" onClick={() => openRoom('BACKUP')} mark={<Glyph glyph="STACK" />} />
        <IndexRow title="حافظهٔ موقت" onClick={() => openRoom('CACHE')} mark={<Glyph glyph="SWAP" />} />
        <IndexRow title="وضعیت دفتر" onClick={() => openRoom('HEALTH')} mark={<Glyph glyph="TRAY" />} />
      </Band>

      {/* The answer to "which version do you have?" over the phone. No build number: the browser has none. */}
      <p class="version">
        {`چقدر تومن • نسخهٔ ${faVersion(VERSION)}`}
        {import.meta.env.PROD ? '' : ` • ${bidi(import.meta.env.MODE)}`}
      </p>
    </Screen>
  );
}

/** The name, in the smallest room that fits it. The draft commits on every way out, back included. */
function NameSheet() {
  const [draft, setDraft] = useState(pref('name'));
  const latest = useRef(draft);
  latest.current = draft;
  useEffect(() => () => { if (latest.current !== pref('name')) setPref('name', latest.current); }, []);
  return (
    <Sheet label="اسمت">
      <SheetTitle>اسمت</SheetTitle>
      <div style={{ height: 'var(--l)' }} />
      <TextField label="اسمت" value={draft} onInput={(v) => setDraft(v.slice(0, 24))} placeholder="مثلاً مریم" maxLength={24} autoFocus onEnter={closeSheet} />
      <div style={{ height: 'var(--xl)' }} />
      <PillButton label="ذخیره" voice="primary" block onClick={closeSheet} />
    </Sheet>
  );
}

/** Three choices; picking closes the sheet, because the whole app re-themes under it. */
function ThemeSheet() {
  useData();
  return (
    <Sheet label="ظاهر برنامه">
      <SheetTitle>ظاهر برنامه</SheetTitle>
      <p class="sheet-body">«خودکار» یعنی هرچی گوشی‌ات می‌گه — روشن توی روز، تیره توی شب.</p>
      <div style={{ height: 'var(--l)' }} />
      <SegmentedChoice options={THEMES} selected={pref('themeMode')} label={(t) => THEME_FA[t]} fontSize={16}
        onSelect={(t) => { setPref('themeMode', t); closeSheet(); }} />
    </Sheet>
  );
}

/** How far ahead an installment is reminded of. Turning it on is the moment to ask for notifications. */
function InstallmentReminderSheet() {
  useData();
  const pick = (days: number) => {
    setPref('installmentReminder', days);
    if (days >= 0 && typeof Notification !== 'undefined' && Notification.permission === 'default') void Notification.requestPermission().catch(() => {});
    closeSheet();
  };
  return (
    <Sheet label="یادآوری قسط">
      <SheetTitle>یادآوری قسط</SheetTitle>
      <p class="sheet-body">قسطی که پرداختش رو ثبت کرده باشی، یادآوری نمی‌شه.</p>
      <div style={{ height: 'var(--l)' }} />
      <SegmentedChoice options={INSTALLMENT_REMINDER_DAYS} selected={pref('installmentReminder')} label={installmentReminderFa} fontSize={15} onSelect={pick} />
    </Sheet>
  );
}

// ---- پیامک‌های بانک -------------------------------------------------------------------------------

/**
 * The one room that changes meaning. The phone reads the inbox behind a switch; the browser has no
 * inbox, so the switch is the door to the paste sheet. What still applies stays: which banks'
 * messages are understood, the bank-app-notification trap, and a switch per bank actually seen.
 */
function SmsPage() {
  const view = useLedger();
  // Naming the banks is not decoration: a bank missing from this line is the reason its balance never appears.
  const watched = BANKS.filter((b) => b.numbers.length).map((b) => b.fa).join('، ');
  // One switch per bank actually seen, not per bank we know how to read.
  const banks = [...new Set(view.bankAccounts.map((a) => a.bank))];
  return (
    <SettingsPage title="پیامک‌های بانک">
      <Band>
        <DoorRow title="چسباندن پیامک بانک" subtitle="مرورگر پیامک‌ها رو نمی‌خونه؛ متن پیامک بانک رو کپی کن و اینجا بچسبون."
          glyph="ENVELOPE" onClick={() => openSheet('pasteSms')} />
      </Band>
      <Note>{`پیامک‌های ${watched} فقط روی همین گوشی خونده می‌شن و جایی فرستاده نمی‌شن.`}</Note>
      <p class="set-note" style={{ paddingTop: 'var(--xl)' }}>
        <span style={{ color: 'var(--on-surface)' }}>فقط پیامک‌های بانکی خونده می‌شن، نه اعلان‌های اپ بانک. </span>
        بعضی بانک‌ها مثل بلو بانک به جای پیامک اعلان می‌فرستن. این‌جوری چیزی برای خوندن نیست و موجودی به‌روز نمی‌شه. از تنظیمات اپ بانک، پیامک تراکنش رو روشن کن.
      </p>

      <SectionLabel>بانک‌ها</SectionLabel>
      {banks.length === 0
        ? <p class="set-note" style={{ paddingTop: 0 }}>هنوز پیامک بانکی نرسیده. اولین پیامک که بیاد، بانک اینجا نشون داده می‌شه.</p>
        : (
          <Band>
            {banks.map((bank) => {
              const accounts = view.bankAccounts.filter((a) => a.bank === bank);
              const rial = accounts.reduce((sum, a) => sum + a.balanceRial, 0);
              return (
                <SettingCard key={bank} title={accounts[0].bankFa}
                  subtitle={`${faCompact(tomanOf(rial))} تومان${accounts.some((a) => !a.trusted) ? '  •  نیاز به بررسی' : ''}`}
                  checked={!accounts[0].disabled} onChange={() => toggleBankDisabled(bank)} badge={<BankLogo bank={bank} size={44} />} />
              );
            })}
          </Band>
        )}
    </SettingsPage>
  );
}

// ---- قفل و امنیت ----------------------------------------------------------------------------------

function SecurityPage() {
  useData();
  const [available, setAvailable] = useState<boolean | null>(null);
  const [asking, setAsking] = useState(false);
  useEffect(() => { void lockAvailable().then(setAvailable); }, []);
  const change = async (on: boolean) => {
    if (!on) { disableLock(); return; }
    // Proven before it is armed: the lock is never switched on by someone who could not then get back in.
    setAsking(true);
    try { await enableLock(); } finally { setAsking(false); }
  };
  return (
    <SettingsPage title="قفل و امنیت">
      <Band>
        <SettingCard mark={<LockGlyph />} title="قفل برنامه" subtitle="با اثر انگشت یا رمز گوشی باز می‌شه"
          checked={pref('lockEnabled')} enabled={available === true && !asking} onChange={(on) => void change(on)} />
      </Band>
      {available === false && <Note>برای فعال کردن قفل، اول توی تنظیمات گوشی رمز یا اثر انگشت بذار.</Note>}
    </SettingsPage>
  );
}

// ---- پشتیبان‌گیری ----------------------------------------------------------------------------------

/** AppVm's BackupUi, for the page and its sheets: the one line under the rows says how it went. */
const backupUi = { working: false, notice: null as string | null, failed: false };
let backupVersion = 0;
const backupListeners = new Set<() => void>();
function setBackupUi(patch: Partial<typeof backupUi>): void {
  Object.assign(backupUi, patch);
  backupVersion++;
  for (const l of backupListeners) l();
}
function useBackupUi(): typeof backupUi {
  const [, tick] = useState(backupVersion);
  useEffect(() => {
    const l = () => tick(backupVersion);
    backupListeners.add(l);
    return () => { backupListeners.delete(l); };
  }, []);
  return backupUi;
}

async function runExport(passphrase: string): Promise<void> {
  if (backupUi.working) return;
  setBackupUi({ working: true, notice: null, failed: false });
  try {
    await exportBackup(passphrase);
    setBackupUi({ working: false, notice: 'پشتیبان ساخته شد. فایل و رمزش رو جای امن نگه دار.' });
  } catch (error) {
    console.warn('backup export failed', error);
    setBackupUi({ working: false, failed: true, notice: 'پشتیبان ساخته نشد. دوباره امتحان کن.' });
  }
}

/** The recovery path: without this file, a lost phone — or a cleared browser — loses everything. */
function BackupPage() {
  useData();
  const ui = useBackupUi();
  const picker = useRef<HTMLInputElement>(null);
  const last = pref('lastBackupAt');
  return (
    <SettingsPage title="پشتیبان‌گیری">
      <p style={{ color: 'var(--on-surface)', paddingBottom: 'var(--m)' }}>
        {last > 0 ? `آخرین پشتیبان: ${faDate(tehranDay(last))}` : 'روی این گوشی هنوز پشتیبانی ساخته نشده.'}
      </p>
      <Band>
        <DoorRow title="پشتیبان‌گیری از همه‌چیز" subtitle="پیامک‌ها، دسته‌بندی‌ها، موجودی‌ها و تنظیمات، توی یک فایل رمزدار"
          glyph="STACK" enabled={!ui.working} onClick={() => openSheet('exportPass')} />
        {/* The file first, so the passphrase is only ever asked about a file that exists. */}
        <DoorRow title="بازگردانی از پشتیبان" subtitle="همه‌چیز از روی فایل پشتیبان برمی‌گرده"
          glyph="TRAY" enabled={!ui.working} onClick={() => picker.current?.click()} />
      </Band>
      <input ref={picker} type="file" hidden onChange={(e) => {
        const input = e.currentTarget;
        const file = input.files?.[0];
        input.value = '';
        if (file) openSheet('restore', { file });
      }} />
      <Note>فایل پشتیبان رمز داره. فایل و رمزش رو جای مطمئن نگه دار. این تاریخ فقط زمان ساخت فایله؛ برنامه نمی‌تونه موندن فایل در محل ذخیره رو بررسی کنه.</Note>
      <div style={{ height: 'var(--l)' }} />
      <Band>
        {/* The house, not a bell: the reminder lives on the home page, and no notification comes. */}
        <SettingCard mark={<Glyph glyph="HOUSE" />} title="یادآوری پشتیبان در برنامه" subtitle="بعد از ۳۰ روز، صفحهٔ خانه یادآوری می‌کنه. اعلانی فرستاده نمی‌شه."
          checked={pref('backupReminderEnabled')} onChange={(on) => setPref('backupReminderEnabled', on)} />
      </Band>
      {ui.notice && <p class="set-note" style={{ color: ui.failed ? 'var(--error)' : 'var(--on-surface)' }} aria-live="polite">{ui.notice}</p>}
    </SettingsPage>
  );
}

/** The passphrase, twice, before anything is written — and the one warning that matters: forgotten means gone. */
function ExportPassSheet() {
  const [pass, setPass] = useState('');
  const [again, setAgain] = useState('');
  const [problem, setProblem] = useState<string | null>(null);
  const done = () => {
    if (pass.length < BACKUP_MIN_PASSPHRASE) setProblem('رمز کوتاهه — دست‌کم ۶ حرف باشه.');
    else if (again !== pass) setProblem('دوتا رمز یکی نیستن.');
    else { closeSheet(); void runExport(pass); }
  };
  return (
    <Sheet label="رمز فایل پشتیبان">
      <SheetTitle>رمز فایل پشتیبان</SheetTitle>
      <p class="sheet-body">فایل با همین رمز قفل می‌شه و بدون اون هیچ‌کس — حتی خود برنامه — نمی‌تونه بازش کنه. اگه یادت بره، هیچ راهی برای باز کردنش نیست.</p>
      <div style={{ height: 'var(--l)' }} />
      <div class="stack">
        <TextField type="password" label="رمز — دست‌کم ۶ حرف" value={pass} onInput={(v) => { setPass(v); setProblem(null); }} autoFocus />
        <TextField type="password" label="دوباره همون رمز" value={again} onInput={(v) => { setAgain(v); setProblem(null); }} onEnter={done} />
      </div>
      {problem && <p class="set-note error" aria-live="polite">{problem}</p>}
      <div style={{ height: 'var(--xl)' }} />
      <PillButton label="ساختن فایل پشتیبان" voice="primary" block onClick={done} />
    </Sheet>
  );
}

/**
 * The import sheet, in two moods: the passphrase and «خواندن فایل», then — once the file has
 * decrypted and proved itself — the armed two-tap that replaces everything. Nothing in the browser
 * changes before that second tap.
 */
function RestoreSheet({ file }: { file: File }) {
  const [pass, setPass] = useState('');
  const [problem, setProblem] = useState<string | null>(null);
  const [working, setWorking] = useState(false);
  const [ready, setReady] = useState<{ payload: BrowserPayload; words: string } | null>(null);
  const read = async () => {
    if (!pass) { setProblem('اول رمز فایل رو بزن.'); return; }
    setWorking(true); setProblem(null);
    try { setReady(await readBackupFile(file, pass)); } catch (error) {
      console.warn('backup open failed', error);
      setProblem(backupFaultFa(error));
    } finally { setWorking(false); }
  };
  const confirm = async () => {
    if (!ready) return;
    setWorking(true);
    try {
      await applyRestore(ready.payload);
      closeSheet();
      // A platform difference: the phone stages a restore for its next launch; here it lands at once.
      setBackupUi({ notice: 'همه‌چیز از روی فایل پشتیبان برگشت.', failed: false });
    } catch (error) {
      console.warn('restore failed', error);
      setWorking(false);
      setProblem('بازگردانی نشد. فایل رو بررسی کن و دوباره امتحان کن.');
    }
  };
  return (
    <Sheet label="بازگردانی از پشتیبان">
      <SheetTitle>بازگردانی از پشتیبان</SheetTitle>
      {!ready ? (
        <>
          <p class="sheet-body">رمزی که موقع ساختن فایل گذاشتی رو بزن.</p>
          <div style={{ height: 'var(--l)' }} />
          <TextField type="password" label="رمز فایل" value={pass} onInput={(v) => { setPass(v); setProblem(null); }} autoFocus onEnter={() => void read()} />
          {problem && <p class="set-note error" aria-live="polite">{problem}</p>}
          <div style={{ height: 'var(--xl)' }} />
          {/* The KDF is deliberately slow, so the wait is named rather than mute. */}
          <PillButton label={working ? 'در حال خواندن…' : 'خواندن فایل'} voice="primary" block disabled={working} onClick={() => void read()} />
        </>
      ) : (
        <>
          <p style={{ fontSize: '15px', lineHeight: '24px', marginTop: 'var(--xs)' }}>{`${ready.words} خونده شد و رمزش درسته.`}</p>
          <p class="sheet-body" style={{ marginTop: 'var(--m)' }}>با بازگردانی، همه‌چیزِ الانِ برنامه — پیامک‌ها، دسته‌بندی‌ها، موجودی‌ها و تنظیمات — با نسخهٔ پشتیبان عوض می‌شه و برنمی‌گرده.</p>
          {problem && <p class="set-note error" aria-live="polite">{problem}</p>}
          <div style={{ height: 'var(--xl)' }} />
          <ArmedAction label="بازگردانی از این پشتیبان" armedLabel="مطمئنی؟ همه‌چیز با نسخهٔ پشتیبان عوض می‌شه — دوباره بزن"
            enabled={!working} onConfirmed={() => void confirm()} />
          <QuietButton label="بی‌خیال" onClick={closeSheet} />
        </>
      )}
    </Sheet>
  );
}

// ---- حافظهٔ موقت ------------------------------------------------------------------------------------

/** Everything it drops is rebuilt, and nothing she typed is touched — so one tap, no confirm. */
function CachePage() {
  const [cleared, setCleared] = useState(false);
  return (
    <SettingsPage title="حافظهٔ موقت">
      <p class="muted" style={{ fontSize: '15px', lineHeight: '26px', padding: '0 var(--xs)' }}>
        نرخ‌های ذخیره‌شده، نشان کوین‌ها و جدول‌هایی که از روی پیامک‌ها ساخته شدن پاک و از نو ساخته می‌شن. پیامک‌ها، دسته‌بندی‌ها و هر چیزی که خودت وارد کردی دست نمی‌خوره.
      </p>
      <div style={{ height: 'var(--xl)' }} />
      <PillButton label={cleared ? 'پاک شد — در حال ساختن دوباره' : 'پاک کردن حافظهٔ موقت'} onClick={() => {
        if (cleared) return;
        setCleared(true);
        // The ledger is derived in memory on every change; the rates are the one stored cache.
        setPref('rates', null);
        refreshAll();
      }} />
    </SettingsPage>
  );
}

// ---- وضعیت دفتر -------------------------------------------------------------------------------------

/** «از اول», or the month the ledger starts at and what that leaves out. */
function ledgerStartFa(health: LedgerHealth): string {
  if (health.startsOn <= 0) return 'از اول';
  if (health.setAside > 0) return `از ${reportMonthOf(health.startsOn).fa} • ${faNumber(health.setAside)} تراکنش کنار رفته`;
  return `از ${reportMonthOf(health.startsOn).fa}`;
}

/** Facts, read-only, as one band: the name at the start of each row and the answer at its end. */
function Facts({ rows }: { rows: Array<[string, string]> }) {
  return (
    <Band flat>
      {rows.map(([label, value]) => (
        <div class="set-row fact" role="group" aria-label={`${label}، ${value}`}>
          <span class="grow">{label}</span>
          <span class="set-value">{value}</span>
        </div>
      ))}
    </Band>
  );
}

function LedgerHealthPage() {
  const health = useLedger().health;
  const now = Date.now();
  return (
    <SettingsPage title="وضعیت دفتر">
      <Band>
        <DoorRow title="شروع دفتر" subtitle={ledgerStartFa(health)} glyph="INSTALMENT" onClick={() => openSheet('ledgerStart')} />
      </Band>

      <SectionLabel>دفتر</SectionLabel>
      <Facts rows={[
        ['تراکنش‌ها', faNumber(health.transactionCount)],
        ['قدیمی‌ترین تراکنش', health.oldestDay != null ? faDate(health.oldestDay) : 'هنوز ثبت نشده'],
        ['آخرین آماده‌سازی', health.derivedAt != null ? faAgo(health.derivedAt, now) : 'هنوز آماده نشده'],
      ]} />
      <Note>روزهای بدون پیامک لزوماً روزهای بدون خرج نیستن.</Note>

      {/* No «مرز خواندن»: the browser scans no inbox, so there is no edge to report. */}
      <SectionLabel>پیامک‌ها</SectionLabel>
      <Facts rows={[
        ['نگه‌داشته‌شده', faNumber(health.sourceCount)],
        ['قدیمی‌ترین پیامک', health.oldestSourceAt != null ? faDate(tehranDay(health.oldestSourceAt)) : 'هنوز ثبت نشده'],
        ['آخرین پیامک واردشده', health.lastIngestAt != null ? faAgo(health.lastIngestAt, now) : 'هنوز وارد نشده'],
      ]} />
    </SettingsPage>
  );
}

/**
 * Where the ledger starts: «از اول», or the first of a month, newest first, each saying what it
 * would leave out. The months run from this one back to the oldest the ledger holds, gaps included.
 */
function LedgerStartSheet() {
  const health = useLedger().health;
  const thisMonth = reportMonthOf(tehranDay(Date.now()));
  const candidates: ReportMonth[] = [thisMonth];
  if (health.months.length) candidates.push(reportMonthOf(health.months[0][0]));
  if (health.startsOn > 0) candidates.push(reportMonthOf(health.startsOn));
  const oldest = candidates.reduce((a, b) => (b.compareTo(a) < 0 ? b : a));
  const months: ReportMonth[] = [];
  for (let m = thisMonth; ; m = m.previous()) { months.push(m); if (m.compareTo(oldest) <= 0) break; }
  const options: Array<[number, string]> = [[0, 'از اول'], ...months.map((m): [number, string] => [m.startDay, m.fa])];
  return (
    <Sheet label="شروع دفتر">
      <SheetTitle>شروع دفتر</SheetTitle>
      <p class="sheet-body">تراکنش‌های قبل از ماهی که انتخاب کنی، دیگه توی دفتر و گزارش‌ها و بودجه‌ها نمیان. چیزی پاک نمی‌شه؛ موجودی حساب‌ها و هدف‌ها دست نمی‌خورن و با «از اول» همه‌شون برمی‌گردن.</p>
      <div style={{ height: 'var(--l)' }} />
      <div class="set-band flat" role="radiogroup" aria-label="شروع دفتر">
        {options.map(([day, label]) => {
          const setAside = health.months.reduce((sum, [start, count]) => sum + (start < day ? count : 0), 0);
          const detail = day === 0 ? null : setAside > 0 ? `${faNumber(setAside)} تراکنش قبلش کنار می‌ره` : 'چیزی کنار نمی‌ره';
          const selected = day === health.startsOn;
          return (
            <button type="button" class="set-row" role="radio" aria-checked={selected} style={{ padding: 'var(--m) var(--l)' }}
              onClick={() => { setLedgerStartsOn(day); closeSheet(); }}>
              <span class="grow">
                <span style={{ display: 'block', fontSize: '16px', fontWeight: selected ? 700 : 600 }}>{label}</span>
                {detail && <span class="set-sub" style={{ display: 'block', marginTop: 0 }}>{detail}</span>}
              </span>
              <span class="radio" aria-hidden="true" />
            </button>
          );
        })}
      </div>
    </Sheet>
  );
}

// ---- the page ------------------------------------------------------------------------------------

function Settings({ room = 'INDEX' }: { room?: Room }) {
  switch (room) {
    case 'SMS': return <SmsPage />;
    case 'SECURITY': return <SecurityPage />;
    case 'BACKUP': return <BackupPage />;
    case 'CACHE': return <CachePage />;
    case 'HEALTH': return <LedgerHealthPage />;
    default: return <SettingsIndex />;
  }
}

registerPage('settings', Settings);
registerSheet('name', NameSheet);
registerSheet('theme', ThemeSheet);
registerSheet('installmentReminder', InstallmentReminderSheet);
registerSheet('exportPass', ExportPassSheet);
registerSheet('restore', RestoreSheet);
registerSheet('ledgerStart', LedgerStartSheet);

