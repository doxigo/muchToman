/**
 * خانواده — a room behind تنظیمات, laid out the way its siblings are (Family.kt CompanionScreen):
 * who is here and the way to add one, what this browser sends them, what never leaves it, and —
 * last, quiet until touched — the two ways out. My name and face open from my row (sheet 'me'); a
 * member's removal opens from theirs (sheet 'member'). The household itself is family.ts's.
 *
 * The one platform difference is the invite: a browser has no share-to-app intent, so beside the
 * QR sits the link itself and a way to send or copy it.
 */
import type { ComponentChildren } from 'preact';
import { useEffect, useMemo, useRef, useState } from 'preact/hooks';
import { encode } from 'uqr';
import './settings.css';
import { CategoryIcon } from './categoryIcon';
import { useLedger } from './derived';
import {
  avatarThumbnail, confirmRejoin, contributionsOf, dismissRejoin, inviteDevice, joinFamily, leaveFamily, removeMember,
  renewFamily, setFamilyAssetSharing, setFamilyAvatar, setFamilyName, setFamilySmsSharing, startFamily, syncFamily,
  toggleFamilyExcludedBank, useFamily,
} from './family';
import type { FamilyState } from './family';
import { faNumber } from './format';
import { Chevron, PlusMark, TabIcon } from './icons';
import { BankLogo } from './logos';
import type { FamilyMember } from './model';
import { closeSheet, openSheet, registerPage, registerSheet, showNotice } from './nav';
import { ArmedAction, Band, DoorRow, Note, QuietButton, SectionLabel, SettingCard, SettingsPage } from './settings';
import { bankFa } from './sms';
import { pref } from './state';
import { AVATAR_MAN, AVATAR_PHOTO_PREFIX, AVATAR_WOMAN } from './sync';
import { MemberFace } from './timeline';
import { PillButton, Sheet, SheetTitle, TextField } from './ui';

const FAMILY_LEAD = 'تراکنش‌های اعضا در یک دفتر دیده می‌شن و اسم صاحب هر مورد همیشه کنارش میاد.';

const SectionHeading = ({ title, count }: { title: string; count?: number }) =>
  <SectionLabel>{count == null ? title : `${title} (${faNumber(count)})`}</SectionLabel>;

/** How much of the family ledger came from this person. */
const contributionsFa = (count: number): string => (count > 0 ? `${faNumber(count)} تراکنش` : 'هنوز تراکنشی نفرستاده');

/** Whether this person's transactions reach the family ledger, in words and colour at once. */
function ShareStatus({ sharing }: { sharing: boolean }) {
  return (
    <span class="share-status" style={{ '--tone': sharing ? 'var(--tertiary)' : 'var(--outline)' }}>
      {sharing ? 'به اشتراک می‌ذاره' : 'خصوصی'}
    </span>
  );
}

/** One person in the household: face, name, what they have put in, what they share. */
function MemberRow({ name, avatar, mine, founder, contributions, sharing, label, onClick }: {
  name: string; avatar: string; mine: boolean; founder: boolean; contributions: number;
  /** Theirs, as the pill. Null on my own row, where the switches further down say it. */
  sharing: boolean | null; label: string;
  /** Null when the row leads nowhere — the founder's, seen by anyone else. */
  onClick: (() => void) | null;
}) {
  const body = (
    <>
      <MemberFace name={name} avatar={avatar} />
      <span class="set-text">
        <span style={{ display: 'flex', alignItems: 'center' }}>
          <span class="set-title one">{name}</span>
          {mine && <span class="mine">من</span>}
        </span>
        <span class="set-sub" style={{ display: 'block' }}>{founder ? `سرپرست • ${contributionsFa(contributions)}` : contributionsFa(contributions)}</span>
      </span>
      {sharing != null && <ShareStatus sharing={sharing} />}
      {onClick && <span class="chev"><Chevron size={24} /></span>}
    </>
  );
  return onClick
    ? <button type="button" class="set-row" onClick={onClick} aria-description={label}>{body}</button>
    : <div class="set-row">{body}</div>;
}

/** The page before there is a household: the room's own mark on the quiet green disc, a line, what happens. */
function Welcome({ heading }: { heading: string }) {
  return (
    <div class="welcome">
      <span class="disc"><CategoryIcon glyph="HOUSE" size={40} stroke={2} /></span>
      <h2>{heading}</h2>
      <p>{FAMILY_LEAD}</p>
    </div>
  );
}

/** Her name and the one button, for starting a household or joining one. */
function NameForm({ name, caption, action, working, onName, onSubmit }: {
  name: string; caption: string; action: string; working: boolean; onName: (v: string) => void; onSubmit: () => void;
}) {
  const ready = name.trim() !== '' && !working;
  return (
    <>
      <div style={{ height: 'var(--xxl)' }} />
      <TextField label="اسمت" value={name} onInput={onName} maxLength={32} onEnter={() => { if (ready) onSubmit(); }} />
      <p class="set-note" style={{ paddingTop: 'var(--s)' }}>{caption}</p>
      <div style={{ height: 'var(--xl)' }} />
      <PillButton label={action} voice="primary" block disabled={!ready} onClick={onSubmit} />
    </>
  );
}

/** The QR for a new member, on a white plate in both themes, and the link it carries. */
function InviteCode({ url }: { url: string }) {
  const path = useMemo(() => {
    // ZXing's settings on the phone: error correction M, a two-module quiet zone.
    const { data } = encode(url, { ecc: 'M', border: 2 });
    let d = '';
    data.forEach((row, y) => row.forEach((dark, x) => { if (dark) d += `M${x} ${y}h1v1h-1z`; }));
    return { d, size: data.length };
  }, [url]);
  const canShare = typeof navigator.share === 'function';
  const send = async () => {
    if (canShare) {
      try { await navigator.share({ url }); } catch { /* she closed the share sheet */ }
      return;
    }
    try {
      await navigator.clipboard.writeText(url);
      showNotice('لینک کپی شد.');
    } catch { /* the link is on screen to select by hand */ }
  };
  return (
    <div class="invite">
      <h3>عضو جدید این کد رو اسکن کنه</h3>
      <div class="qr">
        <svg viewBox={`0 0 ${path.size} ${path.size}`} role="img" aria-label="کد دعوت خانواده" shape-rendering="crispEdges">
          <path d={path.d} fill="#000" />
        </svg>
      </div>
      <p class="set-note" style={{ paddingTop: 'var(--l)', textAlign: 'center' }}>در اندروید، صفحه بازشده رو با اپ چقدر تومن باز کن. کد ده دقیقه اعتبار داره و یک‌بار مصرفه.</p>
      <p class="link">{url}</p>
      <PillButton label={canShare ? 'فرستادن لینک' : 'کپی لینک'} onClick={() => void send()} />
    </div>
  );
}

/** One of the two ways out, as a row that is its own confirm: the first tap turns it into the question. */
function DangerRow({ title, armedTitle, subtitle, detail, enabled, onConfirmed }: {
  title: string; armedTitle: string; subtitle: string; detail: string; enabled: boolean; onConfirmed: () => void;
}) {
  const [armed, setArmed] = useState(false);
  return (
    <button type="button" class="danger-row" disabled={!enabled} onClick={() => { if (armed) { setArmed(false); onConfirmed(); } else setArmed(true); }}>
      <span class="set-title" style={{ display: 'block' }} aria-live="polite">{armed ? armedTitle : title}</span>
      <span class="set-sub" style={{ display: 'block' }}>{subtitle}</span>
      {armed && <span class="detail" style={{ display: 'block' }}>{detail}</span>}
    </button>
  );
}

/**
 * A pairing link on a browser that already has a household — the other side of «نو کردن
 * خانواده». Nothing replaces anything silently; the words say what stops, and the confirm is armed.
 */
function RejoinBlock({ working }: { working: boolean }) {
  return (
    <>
      <SectionHeading title="پیوستن به خانواده جدید" />
      <p class="set-note" style={{ paddingTop: 0, lineHeight: '22px' }}>
        این کد مال یک خانواده دیگه‌ست. با پیوستن، خانواده قبلی روی این گوشی کنار می‌ره: موارد مشترک اعضای قبلی دیگه به‌روز نمی‌شن و دفتر مشترک از نو شروع می‌شه. تراکنش‌های خود این گوشی سر جاشون می‌مونن و با همون اسم قبلی وارد می‌شی.
      </p>
      <div style={{ height: 'var(--m)' }} />
      <ArmedAction label={working ? 'در حال پیوستن...' : 'پیوستن به خانواده جدید'} armedLabel="مطمئنی؟ خانواده قبلی کنار می‌ره — دوباره بزن"
        enabled={!working} onConfirmed={() => void confirmRejoin()} />
      <QuietButton label="بی‌خیال" disabled={working} onClick={dismissRejoin} />
    </>
  );
}

function Household({ state, contributions }: { state: FamilyState; contributions: Record<string, number> }) {
  const others = state.members.filter((m) => m.id !== state.memberId);
  const myName = state.memberName || state.me?.name || '';
  return (
    <>
      <p class="set-lead">{FAMILY_LEAD}</p>
      {/* One band: me first, because mine is the only row anybody can change, then everyone else,
          then the way to add one — a list and the way to grow it are one object. */}
      <SectionHeading title="اعضای خانواده" count={others.length + 1} />
      <Band>
        <MemberRow name={myName} avatar={state.me?.avatar ?? ''} mine founder={state.memberId === state.primaryMemberId}
          contributions={contributions[state.memberId] ?? 0} sharing={null} label="تغییر اسم و چهره" onClick={() => openSheet('me')} />
        {others.map((member) => {
          const founder = member.id === state.primaryMemberId;
          return (
            <MemberRow key={member.id} name={member.name} avatar={member.avatar} mine={false} founder={founder}
              contributions={contributions[member.id] ?? 0} sharing={member.sharesSms} label="حذف از خانواده"
              // The founder's row opens nothing: removal is all the sheet offers, and the server refuses it.
              onClick={founder ? null : () => openSheet('member', { id: member.id })} />
          );
        })}
        <button type="button" class="add-member" disabled={state.working} onClick={() => void inviteDevice()}>
          <PlusMark size={18} />{state.pairingUrl == null ? 'دعوت عضو جدید' : 'ساختن کد تازه'}
        </button>
      </Band>
      {state.pairingUrl && <InviteCode url={state.pairingUrl} />}
      <div style={{ height: 'var(--l)' }} />
      <Band>
        <DoorRow title={state.working ? 'در حال همگام‌سازی...' : 'همگام‌سازی الان'} subtitle={state.lastSync ?? ''} glyph="SWAP"
          enabled={!state.working} onClick={() => syncFamily()} chevron={false} />
      </Band>
    </>
  );
}

function Family() {
  const state = useFamily();
  const view = useLedger();
  const contributions = useMemo(() => contributionsOf(view.entries), [view.entries]);
  // The draft for the two forms that come before a household; once there is one, the name lives in 'me'.
  const [name, setName] = useState(() => state.memberName || pref('name'));
  const household = state.paired && !state.pendingRejoin && !state.pendingPairing;
  const banks = [...new Set(view.bankAccounts.map((a) => a.bank))];

  return (
    <SettingsPage title="خانواده">
      {state.pendingRejoin ? (
        // Before the plain join: a link on a browser that already belongs somewhere is this question.
        <><p class="set-lead">{FAMILY_LEAD}</p><RejoinBlock working={state.working} /></>
      ) : state.pendingPairing ? (
        <>
          <Welcome heading="پیوستن به خانواده" />
          <NameForm name={name} caption="این اسم کنار تراکنش‌های تو دیده می‌شه." action={state.working ? 'در حال پیوستن...' : 'پیوستن'}
            working={state.working} onName={(v) => setName(v.slice(0, 32))} onSubmit={() => void joinFamily(name.trim())} />
        </>
      ) : !state.paired ? (
        <>
          <Welcome heading="خرج‌های خونه رو با هم توی یک دفتر ببینید" />
          <NameForm name={name} caption="اعضای بعدی با کد دعوت وارد می‌شن." action={state.working ? 'در حال ساختن...' : 'ساختن خانواده'}
            working={state.working} onName={(v) => setName(v.slice(0, 32))} onSubmit={() => void startFamily(name.trim())} />
        </>
      ) : <Household state={state} contributions={contributions} />}

      {state.error && <Note tone="error">{state.error}</Note>}

      {household && (
        <>
          {/* What this browser sends, as the switch rows every other settings room uses. Pasted
              messages are this browser's SMS rows, so the first switch is theirs. */}
          <SectionHeading title="اشتراک‌گذاری" />
          <Band>
            <SettingCard mark={<TabIcon tab="LEDGER" />} title="تراکنش‌های پیامکی"
              subtitle={state.sharesSms ? 'برای خانواده فرستاده می‌شن' : 'فقط روی همین گوشی می‌مونن'}
              checked={state.sharesSms} onChange={setFamilySmsSharing} />
            <SettingCard mark={<TabIcon tab="ASSETS" />} title="دارایی‌ها"
              subtitle={state.sharesAssets ? 'فقط اسم و ارزش تومنی‌شون می‌ره' : 'فقط روی همین گوشی می‌مونن'}
              checked={state.sharesAssets} onChange={setFamilyAssetSharing} />
          </Band>
          {/* The set-aside banks, only while something is being shared for them to be set aside from. */}
          {banks.length > 0 && (state.sharesSms || state.sharesAssets) && (
            <>
              <SectionHeading title="بانک‌ها" />
              <Band>
                {banks.map((bank) => {
                  const shared = !state.excludedBanks.includes(bank);
                  return (
                    <SettingCard key={bank} title={bankFa(bank)} subtitle={shared ? 'برای خانواده فرستاده می‌شه' : 'فقط روی همین گوشی می‌مونه'}
                      checked={shared} onChange={() => toggleFamilyExcludedBank(bank)} badge={<BankLogo bank={bank} size={44} />} />
                  );
                })}
              </Band>
              <Note>از بانک خاموش، نه تراکنشی فرستاده می‌شه نه موجودی.</Note>
            </>
          )}
        </>
      )}

      <SectionHeading title="حریم خصوصی" />
      <p class="set-note" style={{ paddingTop: 0, lineHeight: '22px' }}>
        متن خام پیامک هیچ‌وقت از گوشی صاحبش خارج نمی‌شه. فقط مبلغ، زمان، بانک، فروشنده و دسته‌بندیِ استخراج‌شده، رمز‌شده جابه‌جا می‌شن. از دارایی‌ها هم فقط اسم و ارزش تومنی می‌ره؛ مقدار، آدرس کیف پول و شماره حساب هیچ‌وقت نه. خاموش کردن اشتراک، موارد قبلی رو بعد از همگام‌سازی از دفتر بقیه حذف می‌کنه؛ چیزی که قبلاً دیده یا کپی شده قابل پس‌گرفتن نیست.
      </p>

      {state.paired && (
        // The two ways out, in one band at the foot of the page, each its own confirm.
        <div class="set-band" style={{ marginTop: 'var(--xxl)' }}>
          <DangerRow title="خروج از خانواده" armedTitle="مطمئنی؟ برای خروج دوباره بزن"
            subtitle="دسترسی همین گوشی قطع می‌شه و دفتر مشترک از روش پاک می‌شه؛ تراکنش‌های خودت سر جاشون می‌مونن."
            detail="چیزی که بقیه قبلاً دیدن پس گرفته نمی‌شه." enabled={!state.working} onConfirmed={() => void leaveFamily()} />
          <DangerRow title="نو کردن خانواده" armedTitle="مطمئنی؟ برای نو کردن دوباره بزن"
            subtitle="برای وقتی که کسی رو حذف کردی و می‌خوای مطمئن باشی چیز تازه‌ای بهش نمی‌رسه."
            detail="یک خانواده تازه با کلید تازه ساخته می‌شه و فقط تراکنش‌های همین گوشی دوباره فرستاده می‌شن. بقیه اعضا باید کد تازه رو دوباره اسکن کنن؛ خانواده قبلی دیگه به‌روز نمی‌شه و دفتر مشترک از نو شروع می‌شه."
            enabled={!state.working} onConfirmed={() => void renewFamily()} />
        </div>
      )}
    </SettingsPage>
  );
}

// ---- sheets --------------------------------------------------------------------------------------

/** Somebody else in the household, and the one thing I can do about them. */
function MemberSheet({ id }: { id: string }) {
  const state = useFamily();
  const view = useLedger();
  const [acting, setActing] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const member: FamilyMember | undefined = state.members.find((m) => m.id === id);
  if (!member) return null;
  const count = contributionsOf(view.entries)[member.id] ?? 0;
  const remove = async () => {
    setActing(true); setError(null);
    const done = await removeMember(member.id);
    setActing(false);
    if (done) closeSheet(); else setError('حذف نشد. اینترنتت رو چک کن.');
  };
  return (
    <Sheet label={member.name}>
      <div class="member-head">
        <MemberFace name={member.name} avatar={member.avatar} size={56} />
        <div class="grow">
          <SheetTitle>{member.name}</SheetTitle>
          <p class="muted" style={{ fontSize: '13px' }}>{contributionsFa(count)}</p>
        </div>
        <ShareStatus sharing={member.sharesSms} />
      </div>
      <p class="sheet-body" style={{ marginTop: 'var(--xl)' }}>همگام‌سازی گوشی این عضو قطع می‌شه، ولی چیزی که قبلاً دیده یا کپی کرده پس گرفته نمی‌شه.</p>
      <div style={{ height: 'var(--s)' }} />
      <ArmedAction label="حذف از خانواده" armedLabel="مطمئنی؟ برای حذف دوباره بزن" enabled={!state.working && !acting} onConfirmed={() => void remove()} />
      {error && <Note tone="error">{error}</Note>}
    </Sheet>
  );
}

/** One face on the picker: a radio, so a screen reader hears which one is in use. */
function Face({ selected, label, onPick, children }: { selected: boolean; label: string; onPick: () => void; children: ComponentChildren }) {
  return <button type="button" role="radio" aria-checked={selected} aria-label={label} onClick={onPick}>{children}</button>;
}

/** My name and face as the household sees them. The name commits on every way out; a face the moment it is picked. */
function MeSheet() {
  const state = useFamily();
  const name = state.memberName || state.me?.name || '';
  const avatar = state.me?.avatar ?? '';
  const [draft, setDraft] = useState(name);
  const latest = useRef(draft);
  latest.current = draft;
  // Blank is not a name, so it keeps the old one rather than clearing it.
  useEffect(() => () => { const clean = latest.current.trim(); if (clean && clean !== name) setFamilyName(clean); }, []);
  const picker = useRef<HTMLInputElement>(null);
  const display = draft.trim() || name;
  return (
    <Sheet label="اسم و چهره">
      <SheetTitle>اسم و چهره</SheetTitle>
      <p class="sheet-body">کنار تراکنش‌هات و توی دفتر مشترک دیده می‌شه.</p>
      <div style={{ height: 'var(--l)' }} />
      <TextField label="اسم" value={draft} onInput={(v) => setDraft(v.slice(0, 32))} maxLength={32} onEnter={closeSheet} />
      <div style={{ height: 'var(--xl)' }} />
      {/* Four discs, not a list: the whole space of choices fits on one line, and the one in use wears the ring. */}
      <div class="faces" role="radiogroup" aria-label="چهره">
        <Face selected={!avatar.trim()} label="حرف اول اسم" onPick={() => setFamilyAvatar('')}><MemberFace name={display} avatar="" size={56} /></Face>
        <Face selected={avatar === AVATAR_MAN} label="مرد" onPick={() => setFamilyAvatar(AVATAR_MAN)}><MemberFace name={display} avatar={AVATAR_MAN} size={56} /></Face>
        <Face selected={avatar === AVATAR_WOMAN} label="زن" onPick={() => setFamilyAvatar(AVATAR_WOMAN)}><MemberFace name={display} avatar={AVATAR_WOMAN} size={56} /></Face>
        <Face selected={avatar.startsWith(AVATAR_PHOTO_PREFIX)} label="انتخاب عکس از گالری" onPick={() => picker.current?.click()}>
          {avatar.startsWith(AVATAR_PHOTO_PREFIX)
            ? <MemberFace name={display} avatar={avatar} size={56} />
            : <span class="member-face" style={{ width: '56px', height: '56px', color: 'var(--on-surface-variant)' }}><PlusMark size={20} /></span>}
        </Face>
      </div>
      <input ref={picker} type="file" accept="image/*" hidden onChange={async (e) => {
        const input = e.currentTarget;
        const file = input.files?.[0];
        input.value = '';
        const thumb = file ? await avatarThumbnail(file) : null;
        if (thumb) setFamilyAvatar(thumb);
      }} />
      <div style={{ height: 'var(--xxl)' }} />
      <PillButton label="ذخیره" voice="primary" block onClick={closeSheet} />
    </Sheet>
  );
}

registerPage('family', Family);
registerSheet('me', MeSheet);
registerSheet('member', MemberSheet);
