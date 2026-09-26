/**
 * خانواده as the phone's AppVm runs it (MainActivity.kt refreshFamily → renewFamily, Family.kt
 * FamilyState): the household actions the family screen calls, the state it reads, and the one
 * sync queue every edit asks through. The wire and the household operations themselves are
 * sync.ts's; this file is the part the phone keeps in its view model.
 *
 * Also the one-time move of the old companion's data (IndexedDB v2 mirror) into the browser's own
 * tables, so a browser that was already in a household stays in it as a full member.
 */
import { useEffect, useState } from 'preact/hooks';
import { readPairing } from './crypto';
import type { Pairing } from './crypto';
import { assetShareItems, currentEffective, undoubledBankName } from './data';
import type { AssetShareItem } from './data';
import { getMeta, legacyOutbox, legacyRecords, partition, partitionLegacyStores, setMeta } from './db';
import type { StoredRecord } from './db';
import { ledger, setMineId } from './derived';
import { faNumber } from './format';
import { tehranDay } from './jalali';
import type { FamilyMember, LedgerEntry, ManualTxn, Publication } from './model';
import { showNotice } from './nav';
import { batch, pref, put, putAll, row, rows, setPref, settled, subscribe, useData } from './state';
import {
  AVATAR_B64_MAX, AVATAR_PHOTO_PREFIX, AVATAR_PX, NoInviteKeyError, SyncHttpError, claimHousehold, invite, joinHousehold,
  leaveFamily as leaveHousehold, loadSession, localRefOfFamilyTxn, pairingCase, pairingUrl, rejoinHousehold,
  removeFamilyMember, renewHousehold, safeExcludedCategoryIds, setSyncPref, syncErrorFa, syncNow, syncPref,
} from './sync';
import type { PairingInvite, Session } from './sync';

export interface FamilyState {
  paired: boolean;
  /** A scanned invite, waiting on her name. */
  pendingPairing: Pairing | null;
  /** A scanned invite for a *different* household than this browser's, waiting on the confirm. */
  pendingRejoin: Pairing | null;
  memberId: string;
  memberName: string;
  /** Everyone in the household, her own row included, by name. */
  members: FamilyMember[];
  me: FamilyMember | null;
  sharesSms: boolean;
  sharesAssets: boolean;
  /** Banks kept out of sharing — transactions and balances both. */
  excludedBanks: string[];
  /** The founder, as the server names them. Nobody else can remove this member. */
  primaryMemberId: string;
  pairingUrl: string | null;
  lastSync: string | null;
  working: boolean;
  /** A sync in flight, silent ones included — what the ledger's pull indicator watches. */
  syncing: boolean;
  error: string | null;
}

/** A platform difference with no phone text to copy: an old companion session kept no key bytes. */
const NO_INVITE_KEY = 'این مرورگر کلید خانواده رو نگه نداشته. کد دعوت رو از گوشی یکی دیگه از اعضا بساز.';

/** Where the household syncs: the Worker that serves this page, so one origin and no CORS. */
const syncBase = (): string => location.origin;

let session: Session | null = null;
const ui = {
  pendingPairing: null as Pairing | null,
  pendingRejoin: null as Pairing | null,
  pairingUrl: null as string | null,
  lastSync: null as string | null,
  working: false,
  syncing: false,
  error: null as string | null,
};
let version = 0;
const listeners = new Set<() => void>();
function changed(): void {
  version++;
  for (const l of listeners) l();
}
function set(patch: Partial<typeof ui>): void {
  Object.assign(ui, patch);
  changed();
}

const byNameThenId = (a: FamilyMember, b: FamilyMember): number =>
  a.name < b.name ? -1 : a.name > b.name ? 1 : a.id < b.id ? -1 : a.id > b.id ? 1 : 0;

export function familyState(): FamilyState {
  const me = session ? row('familyMembers', session.member) ?? null : null;
  return {
    paired: session != null,
    pendingPairing: ui.pendingPairing,
    pendingRejoin: ui.pendingRejoin,
    memberId: session?.member ?? '',
    memberName: me?.name ?? '',
    members: session ? rows('familyMembers').filter((m) => !m.deleted).sort(byNameThenId) : [],
    me,
    sharesSms: me?.sharesSms ?? false,
    sharesAssets: session != null && syncPref('syncShareAssets'),
    excludedBanks: session ? syncPref('syncExcludedBanks') : [],
    primaryMemberId: session ? syncPref('syncPrimaryMember') : '',
    pairingUrl: ui.pairingUrl,
    lastSync: ui.lastSync,
    working: ui.working,
    syncing: ui.syncing,
    error: ui.error,
  };
}

/** The family screen's state, re-rendering on every data change and every household change. */
export function useFamily(): FamilyState {
  useData();
  const [, tick] = useState(version);
  useEffect(() => {
    const listener = () => tick(version);
    listeners.add(listener);
    return () => { listeners.delete(listener); };
  }, []);
  return familyState();
}

/** Who "me" is to the ledger, told whenever the member or what she shares moves. */
let told = '';
function tellLedger(): void {
  const member = session?.member ?? '';
  const sharesSms = syncPref('syncShareSms');
  const excludedBanks = syncPref('syncExcludedBanks');
  const key = JSON.stringify([member, sharesSms, excludedBanks]);
  if (key === told) return;
  told = key;
  setMineId(member, sharesSms, excludedBanks);
}

/**
 * The state the family screen is left in after anything happens — the phone's `refreshFamily`.
 * Her own row is made here if missing, under the name تنظیمات greets her by.
 */
function refreshFamily(next: Session | null, note?: string, error: string | null = null): void {
  session = next;
  if (next && !row('familyMembers', next.member)) {
    put('familyMembers', {
      id: next.member, name: pref('name').trim().slice(0, 32) || 'من', sharesSms: syncPref('syncShareSms'),
      avatar: '', updatedAt: Date.now(), deleted: false,
    });
  }
  Object.assign(ui, {
    pendingPairing: next ? null : ui.pendingPairing,
    lastSync: note ?? ui.lastSync,
    error,
    working: false,
  });
  tellLedger();
  changed();
}

// ---- starting up ---------------------------------------------------------------------------

let started: Promise<void> | null = null;

/**
 * Once, after state.ts has loaded: the old companion's data moved in, the session read, the
 * ledger told who she is, and the first quiet sync asked for — then again whenever the page
 * comes back or the network does, as the phone syncs on every return to the foreground.
 */
export function loadFamily(): Promise<void> {
  return (started ??= (async () => {
    try {
      await migrateOldCompanion();
    } catch (error) {
      // Left unmarked, so it runs again next load; nothing old is ever deleted by it.
      console.error('family migration failed', error);
    }
    refreshFamily(await loadSession());
    subscribe(tellLedger);
    addEventListener('online', () => requestFamilySync(true));
    document.addEventListener('visibilitychange', () => { if (document.visibilityState === 'visible') requestFamilySync(true); });
    requestFamilySync(true);
  })());
}

// ---- the household -------------------------------------------------------------------------

/** Create a family with this browser's owner as its first member. */
export async function startFamily(name: string): Promise<void> {
  if (!name.trim()) return;
  set({ working: true, error: null });
  try {
    const next = (await loadSession()) ?? (await claimHousehold(syncBase(), name));
    refreshFamily(next, 'خانواده ساخته شد.');
    requestFamilySync(true);
  } catch {
    refreshFamily(null, undefined, 'اتصال نشد. بعداً دوباره امتحان کن.');
  }
}

/**
 * A scanned invitation, from `location.hash`. The stored session decides the question she is
 * asked — join, nothing (her own family's QR), or replace. The caller should take the key out of
 * the address bar (`history.replaceState`) once this has read it.
 */
export async function acceptPairing(hash: string): Promise<void> {
  if (!hash.trim()) return;
  const pairing = readPairing(hash);
  if (!pairing) {
    set({ error: 'کد خانواده معتبر نیست.' });
    return;
  }
  const current = await loadSession();
  switch (pairingCase(current?.token, pairing.hid)) {
    case 'JOIN': set({ pendingPairing: pairing, error: null, pairingUrl: null }); break;
    case 'SAME_HOUSEHOLD': set({ error: 'این گوشی از قبل عضو یک خانواده است.' }); break;
    case 'REJOIN': set({ pendingRejoin: pairing, error: null, pairingUrl: null }); break;
  }
}

/**
 * The invite's own server is never asked: this page only ever talks to the origin it came from
 * (its CSP says so), which is the phone's refusal of any other server, made structural.
 */
const invitation = (p: Pairing): PairingInvite => ({ base: syncBase(), hid: p.hid, code: p.code, scope: p.scope, key: p.key });

export async function joinFamily(name: string): Promise<void> {
  const pairing = ui.pendingPairing;
  if (!pairing || !name.trim()) return;
  set({ working: true, error: null });
  const existing = await loadSession();
  if (existing) {
    refreshFamily(existing, undefined, 'این گوشی از قبل عضو یک خانواده است.');
    return;
  }
  try {
    const next = await joinHousehold(invitation(pairing), name);
    ui.pendingPairing = null;
    refreshFamily(next, 'به خانواده پیوستی.');
    requestFamilySync(true);
  } catch {
    set({ working: false, error: 'کد منقضی شده یا قبلاً استفاده شده.' });
  }
}

/** The confirmed replace: the old household is buried only once the new one has said yes. */
export async function confirmRejoin(): Promise<void> {
  const pairing = ui.pendingRejoin;
  if (!pairing) return;
  set({ working: true, error: null });
  // Her name walks with her: the old household's row, not a fresh ask.
  const old = await loadSession();
  const name = (old && row('familyMembers', old.member)?.name) || pref('name');
  try {
    const next = await rejoinHousehold(invitation(pairing), name);
    ui.pendingRejoin = null;
    refreshFamily(next, 'به خانواده جدید پیوستی.');
    requestFamilySync(true);
  } catch {
    set({ working: false, error: 'کد منقضی شده یا قبلاً استفاده شده.' });
  }
}

/** She thought better of it: the old household stands, the scanned link is dropped. */
export const dismissRejoin = (): void => set({ pendingRejoin: null, error: null });

/** Her name, on her family row and as تنظیمات greets her; blank keeps the old one. */
export function setFamilyName(name: string): void {
  const clean = name.replace(/[\u0000-\u001f\u007f-\u009f]/g, '').trim().slice(0, 32);
  if (!clean) return;
  setPref('name', clean);
  if (!session) return;
  const previous = row('familyMembers', session.member);
  const now = Math.max(Date.now(), (previous?.updatedAt ?? 0) + 1);
  put('familyMembers', { ...(previous ?? { id: session.member, sharesSms: false, avatar: '', deleted: false }), name: clean, updatedAt: now });
  refreshFamily(session);
  requestFamilySync(true);
}

/** The face she picked for her own row. Blank is a valid answer — it is how a face goes back to the initial. */
export function setFamilyAvatar(avatar: string): void {
  if (avatar.length > AVATAR_B64_MAX || !session) return;
  const previous = row('familyMembers', session.member);
  const now = Math.max(Date.now(), (previous?.updatedAt ?? 0) + 1);
  put('familyMembers', {
    ...(previous ?? { id: session.member, name: pref('name').trim().slice(0, 32) || 'من', sharesSms: false, deleted: false }),
    avatar, updatedAt: now,
  });
  refreshFamily(session);
  requestFamilySync(true);
}

/**
 * A picked photo, shrunk to the one size any screen draws it — centre-cropped square, turned
 * upright by its EXIF flag — and packed into `FamilyMember.avatar`'s `b64:` shape, small enough
 * for the member record to stay far under the server's body cap. Null when it is not an image.
 */
export async function avatarThumbnail(file: Blob): Promise<string | null> {
  try {
    const bitmap = await createImageBitmap(file, { imageOrientation: 'from-image' });
    const side = Math.min(bitmap.width, bitmap.height);
    if (side <= 0) return null;
    const canvas = document.createElement('canvas');
    canvas.width = canvas.height = AVATAR_PX;
    const context = canvas.getContext('2d');
    if (!context) return null;
    context.imageSmoothingQuality = 'high';
    context.drawImage(bitmap, (bitmap.width - side) / 2, (bitmap.height - side) / 2, side, side, 0, 0, AVATAR_PX, AVATAR_PX);
    bitmap.close();
    const url = canvas.toDataURL('image/jpeg', 0.78);
    if (!url.startsWith('data:image/jpeg;base64,')) return null;
    const avatar = AVATAR_PHOTO_PREFIX + url.slice(url.indexOf(',') + 1);
    return avatar.length <= AVATAR_B64_MAX ? avatar : null;
  } catch {
    return null;
  }
}

/** Pasted messages are this browser's SMS rows, so this switch is theirs. */
export function setFamilySmsSharing(enabled: boolean): void {
  if (!session) return;
  const previous = row('familyMembers', session.member) ?? { id: session.member, name: 'من', sharesSms: false, avatar: '', updatedAt: 0, deleted: false };
  const now = Math.max(Date.now(), previous.updatedAt + 1);
  batch(() => {
    setSyncPref('syncShareSms', enabled);
    put('familyMembers', { ...previous, sharesSms: enabled, updatedAt: now });
  });
  refreshFamily(session);
  requestFamilySync(false);
}

/** Whether her دارایی list rides along on the next sync. Off is a tombstone. */
export function setFamilyAssetSharing(enabled: boolean): void {
  if (!session) return;
  setSyncPref('syncShareAssets', enabled);
  refreshFamily(session);
  requestFamilySync(false);
}

/** Banks kept out of sharing entirely: their transactions and their balances both. */
export function toggleFamilyExcludedBank(bank: string): void {
  if (!bank.trim() || !session) return;
  const current = new Set(syncPref('syncExcludedBanks'));
  if (current.has(bank)) current.delete(bank); else current.add(bank);
  setSyncPref('syncExcludedBanks', [...current].sort());
  refreshFamily(session);
  requestFamilySync(false);
}

/** A one-time code, shown as a QR. Ten minutes, one use. */
export async function inviteDevice(): Promise<void> {
  const current = await loadSession();
  if (!current) {
    refreshFamily(null);
    return;
  }
  if (!current.raw) {
    refreshFamily(current, undefined, NO_INVITE_KEY);
    return;
  }
  try {
    const url = pairingUrl(current, await invite(current));
    session = current;
    set({ pairingUrl: url, error: null });
  } catch (error) {
    refreshFamily(current, undefined, error instanceof NoInviteKeyError ? NO_INVITE_KEY : 'کد ساخته نشد. اینترنتت رو چک کن.');
  }
}

/**
 * Cuts one member's devices off the household. True once the server has done it, and a sync is
 * asked for; false for the sheet to say «حذف نشد. اینترنتت رو چک کن.» (Family.kt). The founder
 * cannot be removed — the server refuses it, so the screen does not offer it.
 */
export async function removeMember(memberId: string): Promise<boolean> {
  const current = await loadSession();
  if (!current) return false;
  try {
    await removeFamilyMember(current, memberId);
  } catch {
    return false;
  }
  requestFamilySync(false);
  return true;
}

/** Walks this browser out of the household; the buried rows leave every screen with the derive. */
export async function leaveFamily(): Promise<void> {
  if (ui.working || transitioning) return;
  transitioning = true;
  // If leave fails, the sync it waited out still has to be retried.
  queueFamilySync(true);
  const active = job;
  announce = false;
  set({ working: true, error: null });
  // A fetch cannot be cancelled mid-flight here the way the phone cancels its job; it is waited out.
  await active;
  const current = await loadSession();
  if (!current) {
    refreshFamily(null);
    finishFamilyTransition(false);
    return;
  }
  try {
    await leaveHousehold(current);
    refreshFamily(null, 'از خانواده خارج شدی.');
    finishFamilyTransition(false);
  } catch {
    refreshFamily(current, undefined, 'خروج نشد. اینترنتت رو چک کن.');
    finishFamilyTransition(true);
  }
}

/** Re-keys the household, then finishes the way the button promises: the fresh QR first, then the re-push. */
export async function renewFamily(): Promise<void> {
  if (ui.working) return;
  set({ working: true, error: null });
  try {
    await renewHousehold();
  } catch {
    refreshFamily(await loadSession(), undefined, 'نو نشد. اینترنتت رو چک کن.');
    return;
  }
  await inviteDevice();
  requestFamilySync(false);
}

// ---- the sync queue ------------------------------------------------------------------------

let job: Promise<void> | null = null;
let queued: boolean | null = null;
let transitioning = false;
/** Whether the sync now in flight owes her a sentence when it lands — raised by the ledger's pull. */
let announce = false;

export const syncFamily = (silent = false): void => requestFamilySync(silent);

/** The ledger's pull: a silent sync that answers out loud, once, when it lands. */
export function pullFamilySync(): void {
  announce = true;
  requestFamilySync(true);
}

/**
 * Ask for a sync. Every edit that changes what the household sees asks — silent — as the phone's
 * view model does after each one; a sync already running takes the request as one more lap.
 */
export function requestFamilySync(silent = true): void {
  if (transitioning) {
    queueFamilySync(silent);
    return;
  }
  if (job) {
    queueFamilySync(silent);
    markFamilySync(silent);
    return;
  }
  markFamilySync(silent);
  job = runFamilySyncLoop(silent).catch((error: unknown) => console.error('sync loop failed', error));
}

function queueFamilySync(silent: boolean): void {
  queued = queued == null ? silent : queued && silent;
}

/** `syncing` moves with every sync; `working` only with the family screen's own buttons. */
function markFamilySync(silent: boolean): void {
  set(silent ? { syncing: true } : { syncing: true, working: true, error: null });
}

async function runFamilySyncLoop(initiallySilent: boolean): Promise<void> {
  let silent = initiallySilent;
  try {
    for (;;) {
      await runFamilySyncOnce(silent);
      if (transitioning || queued == null) break;
      silent = queued;
      queued = null;
      markFamilySync(silent);
    }
  } finally {
    job = null;
    set({ syncing: false });
  }
}

/** Her دارایی as the hero prices it, minus what she keeps out of the family (Data.kt `assetShareItems`). */
const sharedAssets = (): AssetShareItem[] => assetShareItems(
  pref('holdings'), currentEffective(), pref('rates')?.coins ?? [], [],
  ledger().bankAccounts.map((a) => ({ bank: a.bank, balance: a.balanceRial / 10, anchored: a.anchored })),
  pref('disabledBanks'), syncPref('syncExcludedBanks'),
);

async function runFamilySyncOnce(silent: boolean): Promise<void> {
  const current = await loadSession();
  if (!current) {
    // No household, nothing to say: a pull raced a leave.
    announce = false;
    if (!silent) refreshFamily(null);
    return;
  }
  // Priced here, where the rates live, so the sync layer stays a courier.
  const assets = syncPref('syncShareAssets') ? sharedAssets() : null;
  try {
    const result = await syncNow(current, Date.now(), assets);
    const compatibility = result.unsupportedKinds.length ? syncErrorFa(new SyncHttpError(400, '{"code":"invalid_kind"}')) : null;
    refreshFamily(current, `${faNumber(result.sent)} مورد فرستادیم، ${faNumber(result.received)} مورد گرفتیم.`, compatibility);
    announcePull(compatibility ?? (result.received === 0 ? 'مورد تازه‌ای از خانواده نبود.' : `${faNumber(result.received)} مورد تازه از خانواده گرفتیم.`));
  } catch (error) {
    console.warn('sync failed', error);
    const message = syncErrorFa(error);
    refreshFamily(current, undefined, message);
    announcePull(message);
  }
}

function announcePull(message: string): void {
  if (!announce) return;
  announce = false;
  showNotice(message);
}

function finishFamilyTransition(resumeQueued: boolean): void {
  transitioning = false;
  const next = resumeQueued ? queued : null;
  queued = null;
  if (next != null) requestFamilySync(next);
}

// ---- what other screens read and set -------------------------------------------------------

/**
 * Which categories دخل و خرج leaves out — a way the household reads the report together, so the
 * edit is stamped for the sync to carry, the same last-write-wins walk a shared budget takes.
 * Screens call this, not `setPref('reportExcluded')`, or the edit never leaves this browser.
 */
export function setReportExcluded(ids: Iterable<string>): void {
  const list = [...ids];
  const previous = syncPref('reportExclusions');
  batch(() => {
    setPref('reportExcluded', list);
    setSyncPref('reportExclusions', {
      ids: safeExcludedCategoryIds(list),
      updatedAt: Math.max(Date.now(), (previous?.updatedAt ?? 0) + 1),
      editedByMemberId: session?.member ?? '',
    });
  });
  if (session) requestFamilySync(true);
}

/** Whether the hero counts the household or just her. */
export const setFamilyTotal = (on: boolean): void => setPref('familyTotal', on);

/** How many ledger entries each member id has put in, for the member rows. */
export function contributionsOf(entries: readonly LedgerEntry[]): Record<string, number> {
  const counts: Record<string, number> = {};
  for (const e of entries) if (!e.duplicate) counts[e.ownerMemberId] = (counts[e.ownerMemberId] ?? 0) + 1;
  return counts;
}

/** A member's shared دارایی as the screens read it: who, what, and their own total. */
export interface FamilyAssetView { memberId: string; name: string; items: AssetShareItem[]; totalToman: number }

/** Rows ride the member list: one whose member is gone simply stops being shown. */
export function familyAssetViews(): FamilyAssetView[] {
  if (!session) return [];
  const members = new Map(rows('familyMembers').filter((m) => !m.deleted).map((m) => [m.id, m]));
  return rows('familyAssets').filter((a) => !a.deleted).flatMap((a) => {
    const member = members.get(a.id);
    return member ? [{
      memberId: a.id, name: member.name, totalToman: a.totalToman,
      items: a.items.map((i) => ({ ...i, name: undoubledBankName(i.name) })),
    }] : [];
  }).sort((a, b) => (a.name < b.name ? -1 : a.name > b.name ? 1 : 0));
}

// ---- the old companion, moved in once -------------------------------------------------------

/**
 * The old mirror's rows that were this browser's own, as v3 rows: each manual transaction it
 * published becomes a `manual` row under the same local id, so its wire id
 * (`txn:<member>:<hex(m:<id>)>`) stays the one the household already holds.
 *
 * Each gets a publication carrying its last stamp, so the next push outranks it and a later
 * delete sweeps it. The hash is blank — the old payload is not what the ledger now builds — so
 * each is sent once more, which is what a phone does after a parser change. A delete that never
 * left (still in the outbox) is marked live, so the sweep sends its tombstone.
 */
export function legacyRows(member: string, records: readonly StoredRecord[], unsent: ReadonlySet<string>): {
  manual: ManualTxn[]; publications: Publication[]; profile: { name: string; updatedAt: number } | null;
} {
  const manual: ManualTxn[] = [];
  const publications: Publication[] = [];
  let profile: { name: string; updatedAt: number } | null = null;
  for (const r of records) {
    if (r.kind === 'member' && r.id === `member:${member}` && !r.deleted) {
      const name = (r.value as { name?: unknown } | null)?.name;
      profile = { name: typeof name === 'string' ? name : '', updatedAt: r.updatedAt };
      continue;
    }
    if (r.kind !== 'transaction' || r.ownerMemberId !== member) continue;
    const localRef = localRefOfFamilyTxn(r.id, member);
    if (!localRef?.startsWith('m:')) continue;
    if (r.deleted) {
      publications.push({ id: r.id, sourceKind: 'manual', contentHash: '', updatedAt: r.updatedAt, deleted: !unsent.has(r.id) });
      continue;
    }
    const v = (r.value ?? {}) as { at?: unknown; amountRial?: unknown; direction?: unknown; merchant?: unknown; categoryId?: unknown };
    // A row that cannot be read back is left alone: a live publication without its row would
    // sweep the household's copy away as a delete.
    if (!Number.isSafeInteger(v.at) || !Number.isSafeInteger(v.amountRial)) continue;
    const at = v.at as number;
    const amount = v.amountRial as number;
    manual.push({
      id: localRef.slice(2), at, day: tehranDay(at), amountRial: v.direction === 'in' ? amount : -amount,
      accountId: null, categoryId: typeof v.categoryId === 'string' && v.categoryId ? v.categoryId : null,
      merchant: typeof v.merchant === 'string' ? v.merchant : '', note: '',
      createdAt: r.updatedAt, updatedAt: r.updatedAt, deleted: false,
    });
    publications.push({ id: r.id, sourceKind: 'manual', contentHash: '', updatedAt: r.updatedAt, deleted: false });
  }
  return { manual, publications, profile };
}

const MIGRATED = 'v3-migrated';

/**
 * The active household only — other stored households' data is left where it is, untouched, as
 * the browser now belongs to one household the way a phone does. Rows other members published
 * are not copied: the cursor starts at zero, and the first pull brings them in through the same
 * checks as any arrival.
 */
async function migrateOldCompanion(): Promise<void> {
  if (await getMeta<boolean>(MIGRATED)) return;
  const stored = await getMeta<{ name?: string }>('session');
  const current = await loadSession();
  if (current) {
    await partitionLegacyStores(current);
    const space = partition(current);
    const [records, outbox] = await Promise.all([legacyRecords(space), legacyOutbox(space)]);
    // An unsent write is also in the mirror, at its newest; the outbox only says it never left.
    const moved = legacyRows(current.member, records, new Set(outbox.map((r) => r.id)));
    const oldName = stored?.name?.trim().slice(0, 32) ?? '';
    batch(() => {
      putAll('manual', moved.manual.filter((m) => !row('manual', m.id)));
      putAll('publications', moved.publications.filter((p) => !row('publications', p.id)));
      if (!pref('name').trim() && oldName) setPref('name', oldName);
      if (!row('familyMembers', current.member)) {
        put('familyMembers', {
          id: current.member, name: moved.profile?.name.trim().slice(0, 32) || oldName || pref('name').trim() || 'من',
          sharesSms: false, avatar: '', updatedAt: moved.profile?.updatedAt ?? Date.now(), deleted: false,
        });
      }
    });
    await settled();
  }
  await setMeta(MIGRATED, true);
}
