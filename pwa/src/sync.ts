/**
 * Family sync, as Sync.kt does it: the same records on the wire, the same checks on arrival, the
 * same household operations. The browser is a member like any phone — it publishes its own rows
 * from its own tables and folds everyone else's into them — so nothing here is a companion's
 * shortcut; where this file and Sync.kt disagree, Sync.kt is right.
 *
 * Two things differ, both because a browser is not a phone:
 *  - The scope key is kept as a non-extractable CryptoKey for sealing, and its bytes beside it
 *    only so an invite can put them in the QR. A session paired before the browser kept them has
 *    no bytes and cannot invite (see `pairingUrl`).
 *  - Where the phone's durable_meta keeps sync state, the session and token live in IndexedDB's
 *    `meta` (never in a backup) and the rest in prefs (`SyncPrefs`), so the cursor is written
 *    through the same ordered queue as the rows it covers.
 *
 * Kotlin's `Json { ignoreUnknownKeys = true }` leaves out every field equal to its default, so a
 * phone's member record may carry no `avatar` and its manual transaction no `sourceKind`, `bank`
 * or `categoryKind`. Every payload is therefore read by its envelope's kind, with the Kotlin
 * defaults filled in — never by a `kind` inside the plaintext, which the phone does not send.
 */
import { glyphNamed } from './categoryIcon';
import { generateKey, hexOf, importKey, openSealed, randomHex, seal, toBase64Url } from './crypto';
import { safeAssetShareItems } from './data';
import type { AssetShareItem } from './data';
import { deleteMeta, getMeta, partition, setMeta, withSyncLock } from './db';
import { familyLocalRef, ledger } from './derived';
import { tehranDay } from './jalali';
import { MAX_NOTE_CHARS } from './rules';
import { MAX_PLAUSIBLE_RIAL, sha256Hex } from './sms';
import type { Category, CategoryKindId, Decision, FamilyAsset, FamilyMember, Goal, GoalKindId, GoalPeriodId, LedgerEntry, Prefs, Publication, Txn } from './model';
import { batch, pref, put, putAll, row, rows, setPref, settled } from './state';

// ---- constants (Sync.kt, Ledger.kt, Derived.kt, Rules.kt) ---------------------------------

/**
 * The client half of the server's 24-hour stamp clamp, wider so an honest offline device that
 * pushed through a skewed peer still converges: a record claiming to be written more than two
 * days in the future is treated as written at the horizon.
 */
export const MAX_SYNC_STAMP_SKEW_MS = 48 * 60 * 60 * 1000;
/** `FamilyMember.avatar`'s photo shape: this prefix, then base64 JPEG bytes. */
export const AVATAR_PHOTO_PREFIX = 'b64:';
export const AVATAR_MAN = '👨';
export const AVATAR_WOMAN = '👩';
/** Thumbnail edge in pixels — the largest disc any screen draws. */
export const AVATAR_PX = 128;
/** The longest photo avatar accepted, in base64 characters; the server refuses sealed bodies over 64k. */
export const AVATAR_B64_MAX = 24_000;
/** The one report-exclusion record a household keeps. */
export const REPORT_EXCLUSIONS_RECORD_ID = 'exclusion:report';
const TOKEN_ROTATE_AFTER_MS = 30 * 24 * 60 * 60 * 1000;
const PUSH_CHUNK = 200;
const PULL_LIMIT = 1000;
const MEMBER_FALLBACK = 'عضو خانواده';

// ---- the session -------------------------------------------------------------------------

export interface Session {
  base: string;
  token: string;
  /** When the token was minted; a sync-time rotation runs off it a month later. */
  issuedAt: number;
  device: string;
  member: string;
  scope: string;
  key: CryptoKey;
  /** The scope key's 32 bytes, base64url. Absent on a session paired before the browser kept them. */
  raw?: string;
}

/** The pre-v3 shape too: `name` rode here, and the oldest records kept the key's bytes as numbers. */
interface StoredSession extends Omit<Session, 'key' | 'member' | 'issuedAt'> {
  key: CryptoKey | number[];
  member?: string;
  issuedAt?: number;
  name?: string;
}

const SYNC_IDENTITY = /^[a-f0-9]{16,64}$/;
export const isValidSyncIdentity = (value: string | null | undefined): value is string => !!value && SYNC_IDENTITY.test(value);

export function uuid7(now = Date.now()): string {
  const b = crypto.getRandomValues(new Uint8Array(16));
  for (let i = 0; i < 6; i++) b[i] = Math.floor(now / 2 ** (8 * (5 - i))) & 0xff;
  b[6] = (b[6] & 0x0f) | 0x70;
  b[8] = (b[8] & 0x3f) | 0x80;
  const hex = hexOf(b);
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}
export const newIdentity = (): string => uuid7().replaceAll('-', '');
export const nextStamp = (previous: number | null | undefined, now: number): number => Math.max(now, (previous ?? 0) + 1);

export async function loadSession(): Promise<Session | null> {
  const stored = await getMeta<StoredSession>('session');
  if (!stored?.base || !stored.token || !stored.scope || !stored.key) return null;
  let changed = false;
  const fresh = (value: string | undefined): string => {
    if (isValidSyncIdentity(value)) return value;
    changed = true;
    return newIdentity();
  };
  const device = fresh(stored.device);
  const member = fresh(stored.member);
  let { key, raw } = stored;
  if (Array.isArray(key)) {
    // The pre-release shape kept the bytes as numbers; they are exactly what an invite needs.
    const bytes = new Uint8Array(key);
    raw = toBase64Url(bytes);
    key = await importKey(bytes);
    changed = true;
  }
  // A missing issue date is written down now, as the phone's META_SYNC_TOKEN_AT is on first read,
  // or a month would never come round for the rotation.
  if (stored.issuedAt == null) changed = true;
  const session: Session = { base: stored.base, token: stored.token, issuedAt: stored.issuedAt ?? Date.now(), device, member, scope: stored.scope, key, raw };
  if (changed) await storeSession(session);
  return session;
}
async function storeSession(session: Session): Promise<void> {
  const { base, token, issuedAt, device, member, scope, key, raw } = session;
  await setMeta('session', { base, token, issuedAt, device, member, scope, key, raw });
}
/** Every caller is a moment a token was minted, so the issue date rides along. */
async function saveSession(session: Session): Promise<void> {
  session.issuedAt = Date.now();
  await deleteMeta('pending-rotation', partition(session));
  await storeSession(session);
}
async function clearSession(): Promise<void> {
  await deleteMeta('session');
}

export function sameHouseholdSession(expected: Session, actual: Session): boolean {
  return expected.base === actual.base && expected.token.split('.')[0] === actual.token.split('.')[0] &&
    expected.member === actual.member && expected.device === actual.device && expected.scope === actual.scope &&
    // CryptoKeys cannot be compared; the bytes can, where both sides still have them.
    (!expected.raw || !actual.raw || expected.raw === actual.raw);
}

/** One family sync or household change at a time, across tabs too. */
export const withFamilySync = <T>(action: () => Promise<T>): Promise<T> => withSyncLock('family', action);

// ---- sync's own prefs ----------------------------------------------------------------------

export interface ReportExclusionsState { ids: string[]; updatedAt: number; editedByMemberId: string }

/**
 * What the phone keeps in durable_meta beside the session, kept here as prefs so a pulled page
 * and its cursor go down one ordered write queue. Not in model.ts's `Prefs`, hence the one cast.
 */
export interface SyncPrefs {
  /** META_SYNC_SEQ. A backup must not carry it back (the phone strips it). */
  syncSeq: number;
  /** META_SYNC_SHARE_SMS — pasted messages count as SMS rows. */
  syncShareSms: boolean;
  syncShareAssets: boolean;
  /** META_SYNC_EXCLUDED_BANKS, sorted: kept out of sharing, transactions and balances both. */
  syncExcludedBanks: string[];
  /** META_SYNC_PRIMARY: the founder, as the server names them on every pull. */
  syncPrimaryMember: string;
  /** META_REPORT_EXCLUSIONS: the LWW state behind `reportExcluded`. Null is a set nobody touched. */
  reportExclusions: ReportExclusionsState | null;
}
const SYNC_PREF_DEFAULTS: SyncPrefs = {
  syncSeq: 0, syncShareSms: false, syncShareAssets: false, syncExcludedBanks: [], syncPrimaryMember: '', reportExclusions: null,
};
export function syncPref<K extends keyof SyncPrefs>(key: K): SyncPrefs[K] {
  return pref(key) ?? SYNC_PREF_DEFAULTS[key];
}
export function setSyncPref<K extends keyof SyncPrefs>(key: K, value: SyncPrefs[K]): void {
  setPref(key, value as Prefs[K]);
}

// ---- the wire ------------------------------------------------------------------------------

export interface WireRecord {
  id: string;
  scope: string;
  updatedAt: number;
  device: string;
  kind: string;
  ownerMemberId: string;
  authorMemberId: string;
  deleted: boolean;
  nonce: string;
  body: string;
}
export interface PreparedRecord { wire: WireRecord; publication?: Publication }
export interface SyncResult { sent: number; received: number; unsupportedKinds: string[] }

export class SyncHttpError extends Error {
  readonly code: string;
  constructor(readonly status: number, detail = '') {
    super(`sync ${status}: ${detail}`);
    let code = '';
    try { const value = JSON.parse(detail) as { code?: unknown }; if (typeof value?.code === 'string') code = value.code; } catch { /* not JSON */ }
    this.code = code;
  }
}
/** The browser's IOException: the request never got an answer. */
export class SyncIoError extends Error {}

export function syncErrorFa(error: unknown): string {
  if (error instanceof SyncHttpError) {
    if (error.code === 'invalid_kind') return 'سرویس همگام‌سازی به به‌روزرسانی نیاز داره. تغییرات روی گوشی محفوظ موند.';
    if (error.status === 401 || error.status === 403) return 'دسترسی به خانواده تأیید نشد. تغییرات روی گوشی محفوظ موند.';
    if (error.status === 429) return 'درخواست‌ها زیاد شده. کمی بعد دوباره امتحان کن.';
    return `سرویس همگام‌سازی خطا داد (${error.status}). تغییرات روی گوشی محفوظ موند.`;
  }
  if (error instanceof SyncIoError) return 'اتصال نشد. اینترنتت رو چک کن. تغییرات روی گوشی محفوظ موند.';
  return 'همگام‌سازی کامل نشد. تغییرات روی گوشی محفوظ موند.';
}

async function request(url: string, method: string, token: string | null, payload?: string): Promise<string> {
  let response: Response;
  try {
    response = await fetch(url, {
      method,
      body: payload,
      signal: AbortSignal.timeout(20_000),
      headers: { ...(token ? { authorization: `Bearer ${token}` } : {}), ...(payload !== undefined ? { 'content-type': 'application/json' } : {}) },
    });
  } catch (error) {
    throw new SyncIoError(String(error));
  }
  const text = await response.text().catch((error: unknown) => { throw new SyncIoError(String(error)); });
  if (!response.ok) throw new SyncHttpError(response.status, text.slice(0, 120));
  return text;
}

// ---- reading a payload the Kotlin way ------------------------------------------------------

type Json = Record<string, unknown>;
const REQUIRED = Symbol('required');
class Malformed extends Error {}
const isString = (v: unknown): v is string => typeof v === 'string';
const isLong = (v: unknown): v is number => Number.isSafeInteger(v);
const isDouble = (v: unknown): v is number => typeof v === 'number';
const isBool = (v: unknown): v is boolean => typeof v === 'boolean';
const isLongOrNull = (v: unknown): v is number | null => v === null || isLong(v);
const isStrings = (v: unknown): v is string[] => Array.isArray(v) && v.every(isString);
const isItems = (v: unknown): v is AssetShareItem[] =>
  Array.isArray(v) && v.every((i) => !!i && typeof i === 'object' && isString((i as Json).name) && isDouble((i as Json).toman));

/** A field as kotlinx reads it: absent takes the default (or fails when there is none), a wrong type fails. */
function field<T>(o: Json, key: string, is: (v: unknown) => v is T, fallback: T | typeof REQUIRED = REQUIRED): T {
  const v = o[key];
  if (v === undefined) {
    if (fallback === REQUIRED) throw new Malformed(key);
    return fallback;
  }
  if (!is(v)) throw new Malformed(key);
  return v;
}
function decode<T>(plain: string, build: (o: Json) => T): T | null {
  try {
    const o = JSON.parse(plain) as unknown;
    return o && typeof o === 'object' && !Array.isArray(o) ? build(o as Json) : null;
  } catch {
    return null;
  }
}

export interface SyncEntry {
  kind: string; ownerMemberId: string; sourceKind: string; at: number; amountRial: number; direction: string;
  bank: string; categoryId: string; categoryName: string; categoryKind: string; transfer: boolean;
  categoryGlyph: string; categoryEditorId: string; categoryUpdatedAt: number; categoryEditedAt: number;
  /** Compatibility with records written before category ids were synchronized. */
  category: string;
  merchant: string;
}
export const parseEntry = (plain: string): SyncEntry | null => decode(plain, (o) => ({
  kind: field(o, 'kind', isString, 'transaction'),
  ownerMemberId: field(o, 'ownerMemberId', isString, ''),
  sourceKind: field(o, 'sourceKind', isString, 'manual'),
  at: field(o, 'at', isLong),
  amountRial: field(o, 'amountRial', isLong),
  direction: field(o, 'direction', isString),
  bank: field(o, 'bank', isString, 'MANUAL'),
  categoryId: field(o, 'categoryId', isString, ''),
  categoryName: field(o, 'categoryName', isString, ''),
  categoryKind: field(o, 'categoryKind', isString, 'expense'),
  transfer: field(o, 'transfer', isBool, false),
  categoryGlyph: field(o, 'categoryGlyph', isString, ''),
  categoryEditorId: field(o, 'categoryEditorId', isString, ''),
  categoryUpdatedAt: field(o, 'categoryUpdatedAt', isLong, 0),
  categoryEditedAt: field(o, 'categoryEditedAt', isLong, 0),
  category: field(o, 'category', isString, ''),
  merchant: field(o, 'merchant', isString, ''),
}));

export interface SyncMemberPayload { memberId: string; name: string; sharesSms: boolean; avatar: string }
export const parseMember = (plain: string): SyncMemberPayload | null => decode(plain, (o) => ({
  memberId: field(o, 'memberId', isString),
  name: field(o, 'name', isString),
  sharesSms: field(o, 'sharesSms', isBool),
  avatar: field(o, 'avatar', isString, ''),
}));

interface SyncAssetPayload { memberId: string; totalToman: number; items: AssetShareItem[] }
const parseAsset = (plain: string): SyncAssetPayload | null => decode(plain, (o) => ({
  memberId: field(o, 'memberId', isString),
  totalToman: field(o, 'totalToman', isDouble),
  items: field(o, 'items', isItems, []).map((i) => ({ name: i.name, toman: i.toman })),
}));

export interface SyncGoalPayload {
  goalId: string; nameFa: string; targetRial: number; goalKind: string; categoryId: string; period: string;
  startsOn: number; endsOn: number | null; createdAt: number; ownerMemberId: string; editedByMemberId: string;
}
const parseGoal = (plain: string): SyncGoalPayload | null => decode(plain, (o) => ({
  goalId: field(o, 'goalId', isString),
  nameFa: field(o, 'nameFa', isString),
  targetRial: field(o, 'targetRial', isLong),
  goalKind: field(o, 'goalKind', isString),
  categoryId: field(o, 'categoryId', isString, ''),
  period: field(o, 'period', isString),
  startsOn: field(o, 'startsOn', isLong),
  endsOn: field(o, 'endsOn', isLongOrNull, null),
  createdAt: field(o, 'createdAt', isLong),
  ownerMemberId: field(o, 'ownerMemberId', isString),
  editedByMemberId: field(o, 'editedByMemberId', isString),
}));

interface SyncCategoryPayload {
  target: string; categoryId: string; categoryName: string; categoryKind: string; categoryGlyph: string;
  editedByMemberId: string; categoryEditedAt: number;
}
const parseCategory = (plain: string): SyncCategoryPayload | null => decode(plain, (o) => ({
  target: field(o, 'target', isString),
  categoryId: field(o, 'categoryId', isString),
  categoryName: field(o, 'categoryName', isString),
  categoryKind: field(o, 'categoryKind', isString),
  categoryGlyph: field(o, 'categoryGlyph', isString, ''),
  editedByMemberId: field(o, 'editedByMemberId', isString),
  categoryEditedAt: field(o, 'categoryEditedAt', isLong, 0),
}));

interface SyncExclusionPayload { categoryIds: string[]; editedByMemberId: string }
const parseExclusion = (plain: string): SyncExclusionPayload | null => decode(plain, (o) => ({
  categoryIds: field(o, 'categoryIds', isStrings),
  editedByMemberId: field(o, 'editedByMemberId', isString),
}));

interface SyncNotePayload { target: string; note: string; editedByMemberId: string }
const parseNote = (plain: string): SyncNotePayload | null => decode(plain, (o) => ({
  target: field(o, 'target', isString),
  note: field(o, 'note', isString),
  editedByMemberId: field(o, 'editedByMemberId', isString),
}));

/**
 * A delete, said inside the ciphertext. No field has a default: the old contentless shape must
 * fail to parse, so a compromised server cannot mint a delete by flipping the envelope's flag.
 */
export const parseTombstone = (plain: string): { v: number; id: string; deleted: boolean } | null => decode(plain, (o) => ({
  v: field(o, 'v', isLong),
  id: field(o, 'id', isString),
  deleted: field(o, 'deleted', isBool),
}));
const tombstonePayload = (id: string): string => JSON.stringify({ v: 1, id, deleted: true });

interface PullBody {
  seq: number; records: WireRecord[]; primaryMemberId: string; hasMore: boolean | null; rotationClientSecret: boolean;
}
const isWire = (v: unknown): v is Json => !!v && typeof v === 'object' && !Array.isArray(v);
function parsePull(text: string): PullBody {
  const body = decode(text, (o) => ({
    seq: field(o, 'seq', isLong, 0),
    records: field(o, 'records', (v): v is Json[] => Array.isArray(v) && v.every(isWire), []).map((r) => ({
      id: field(r, 'id', isString),
      scope: field(r, 'scope', isString),
      updatedAt: field(r, 'updatedAt', isLong),
      device: field(r, 'device', isString),
      kind: field(r, 'kind', isString, 'legacy'),
      ownerMemberId: field(r, 'ownerMemberId', isString, ''),
      authorMemberId: field(r, 'authorMemberId', isString, ''),
      deleted: field(r, 'deleted', isBool, false),
      nonce: field(r, 'nonce', isString),
      body: field(r, 'body', isString),
    })),
    primaryMemberId: field(o, 'primaryMemberId', isString, ''),
    hasMore: field(o, 'hasMore', (v): v is boolean | null => v === null || isBool(v), null),
    rotationClientSecret: field(o, 'rotationClientSecret', isBool, false),
  }));
  if (!body) throw new Error('malformed pull');
  return body;
}

// ---- ids and the shapes both sides store ---------------------------------------------------

const blank = (s: string | null | undefined): boolean => !s || !s.trim();
const withoutControls = (s: string): string => s.replace(/[\u0000-\u001f\u007f-\u009f]/g, '');
const utf8Hex = (value: string): string => hexOf(new TextEncoder().encode(value));

export const familyTxnId = (memberId: string, localRef: string): string => `txn:${memberId}:${utf8Hex(localRef)}`;
export function ownerOfFamilyTxnId(familyRef: string): string | null {
  if (!familyRef.startsWith('txn:')) return null;
  const owner = familyRef.split(':')[1];
  return blank(owner) ? null : owner;
}
function hexText(value: string): string | null {
  if (value.length % 2 !== 0 || !/^[0-9a-f]*$/.test(value)) return null;
  const bytes = new Uint8Array(value.length / 2);
  for (let i = 0; i < bytes.length; i++) bytes[i] = parseInt(value.slice(i * 2, i * 2 + 2), 16);
  return new TextDecoder().decode(bytes);
}
export function localRefOfFamilyTxn(familyRef: string, memberId: string): string | null {
  const [head, owner, ...rest] = familyRef.split(':');
  if (!rest.length || head !== 'txn' || owner !== memberId) return null;
  return hexText(rest.join(':'));
}
const memberRecordId = (memberId: string): string => `member:${memberId}`;
const assetRecordId = (memberId: string): string => `asset:${memberId}`;
const categoryRecordId = (familyRef: string): string => `category:${sha256Hex(familyRef)}`;
const noteRecordId = (familyRef: string): string => `note:${sha256Hex(familyRef)}`;
/** Keyed by the goal and not by who made it: any member may move a household budget. */
export const goalRecordId = (goalId: string): string => `goal:${goalId}`;
const removePrefix = (s: string, prefix: string): string => (s.startsWith(prefix) ? s.slice(prefix.length) : s);

export const clampSyncStamp = (stamp: number, now: number): number => Math.min(stamp, now + MAX_SYNC_STAMP_SKEW_MS);

const safeSyncedText = (value: string, max: number, fallback = ''): string => {
  const clean = withoutControls(value).trim().slice(0, max);
  return blank(clean) ? fallback : clean;
};
export const cleanMemberName = (value: string): string => safeSyncedText(value, 32, MEMBER_FALLBACK);
/** Somebody else's words: the newline survives, because a note is prose. */
const safeSyncedNote = (value: string): string =>
  value.replace(/[\u0000-\u0009\u000b-\u001f\u007f-\u009f]/g, '').trim().slice(0, MAX_NOTE_CHARS);
/** A face another phone chose, held to the two shapes this build renders; anything else is the initial. */
export const safeSyncedAvatar = (value: string): string => value.startsWith(AVATAR_PHOTO_PREFIX)
  ? (value.length <= AVATAR_B64_MAX ? value : '')
  : withoutControls(value).trim().slice(0, 16);

/** A mark another device chose, kept only if this build draws it; blank falls back to the name. */
const safeSyncedGlyph = (value: string): string => glyphNamed(withoutControls(value).trim()) ?? '';

/** When her name and mark for this category were set; 0 for a shipped one nobody has edited. */
const editedAt = (c: Category): number => (blank(c.glyph) ? 0 : c.updatedAt);

/**
 * Category ids in the one shape both sides store and send: canonical (trimmed, deduplicated,
 * sorted) so two phones holding one set build one byte string, and capped so the payload cannot
 * outgrow the server's body cap. Truncated before trimming, so the function is idempotent.
 */
export function safeExcludedCategoryIds(ids: Iterable<string>): string[] {
  const out = [...ids].map((id) => withoutControls(id).slice(0, 80).trim()).filter((id) => !blank(id));
  return [...new Set(out)].sort().slice(0, 400);
}

/** One goal as it goes on the wire, built here and nowhere else — the fixed point the publish loop rests on. */
export function goalPayload(goal: Goal): string {
  return JSON.stringify({
    kind: 'goal',
    goalId: goal.id,
    nameFa: safeSyncedText(goal.nameFa, 40, 'بی‌نام'),
    targetRial: goal.targetRial,
    goalKind: goal.kind,
    categoryId: safeSyncedText(goal.categoryId ?? '', 80),
    period: goal.period,
    startsOn: goal.startsOn,
    endsOn: goal.endsOn,
    createdAt: goal.createdAt,
    ownerMemberId: goal.ownerMemberId,
    editedByMemberId: goal.editedByMemberId,
  });
}
export function reportExclusionsPayload(ids: Iterable<string>, editor: string): string {
  return JSON.stringify({ kind: 'exclusion', categoryIds: safeExcludedCategoryIds(ids), editedByMemberId: editor });
}

/** Last write wins, the editor's id breaking a same-millisecond tie. */
export const syncedEditWins = (existingAt: number | null | undefined, existingEditor: string, incomingAt: number, incomingEditor: string): boolean =>
  existingAt == null || existingAt < incomingAt || (existingAt === incomingAt && existingEditor < incomingEditor);

export const categoryUpdateWins = (existingAt: number | null | undefined, existingEditor: string, incomingAt: number, incomingEditor: string): boolean =>
  existingAt == null || incomingAt > existingAt || (incomingAt === existingAt && incomingEditor > existingEditor) ||
  (incomingAt === 0 && existingAt === 0 && incomingEditor === existingEditor);

export const resolvedTransactionOwner = (wireKind: string, wireOwner: string, authenticatedAuthor: string): string =>
  wireKind === 'legacy' ? authenticatedAuthor : (blank(wireOwner) ? authenticatedAuthor : wireOwner);

// ---- publishing: outgoingRecords ----------------------------------------------------------

/**
 * Which shared transaction an answer is about, in the household's own naming: the decision's
 * family ref, else the transaction's, else — one of her own rows — the ref she builds. A row that
 * came from the family carrying neither cannot be named to anybody, so it is left alone.
 */
function familyTargetOf(decision: Decision, txn: Txn | undefined, member: string): string | null {
  if (!blank(decision.familyRef)) return decision.familyRef;
  if (txn && !blank(txn.familyRef)) return txn.familyRef;
  return decision.ref.startsWith('f:') ? null : familyTxnId(member, decision.ref);
}

/**
 * Whether an answer about one row may leave this device: it inherits every gate the transaction
 * rides, and only her own answers go — somebody else's arrived as their record and stays theirs.
 */
export function decisionMayLeave(decision: Decision, txn: Txn | undefined, shareSms: boolean, member: string, excludedBanks: Set<string>): boolean {
  if (!blank(decision.memberId) && decision.memberId !== member) return false;
  if (txn && !blank(txn.familyRef)) return true;
  if (!shareSms && (txn?.sourceKind === 'sms' || decision.ref.startsWith('s:'))) return false;
  return !txn || !excludedBanks.has(txn.bank);
}

export interface PublishInput {
  session: Session;
  now: number;
  /** The ledger as derived.ts builds it — her rows and the family's. */
  entries: LedgerEntry[];
  /** Her own member row, already squared with the SMS switch. */
  member: FamilyMember;
  publications: Publication[];
  /** Every goal, deleted ones included. */
  goals: Goal[];
  /** Every decision, retracted ones included. */
  decisions: Decision[];
  /** Every category; archived ones are left out here, as `categories().all()` leaves them out. */
  categories: Category[];
  shareSms: boolean;
  shareAssets: boolean;
  excludedBanks: string[];
  /** Her دارایی as she shares it, or null when there are no prices to hand. */
  assets: AssetShareItem[] | null;
  exclusions: ReportExclusionsState | null;
}

interface Draft { id: string; kind: string; owner: string; updatedAt: number; payload: string; deleted?: boolean; publication?: Publication }

/** Sync.kt `outgoingRecords`: everything this device owes the household, sealed and ready to push. */
export async function outgoingRecords(p: PublishInput): Promise<PreparedRecord[]> {
  const { session, now } = p;
  const me = session.member;
  const excludedBanks = new Set(p.excludedBanks);
  const out: Draft[] = [];

  // The profile rides every sync; the server keeps the stored one when the stamp has not moved.
  out.push({
    id: memberRecordId(p.member.id), kind: 'member', owner: p.member.id, updatedAt: p.member.updatedAt,
    payload: JSON.stringify({ kind: 'member', memberId: p.member.id, name: p.member.name, sharesSms: p.member.sharesSms, avatar: p.member.avatar }),
  });

  const categoryById = new Map(p.categories.filter((c) => !c.archived).map((c) => [c.id, c]));
  const categoryDecisions = p.decisions.filter((d) => d.kind === 'category' && !d.deleted);
  const categoryDecisionByRef = new Map(categoryDecisions.map((d) => [d.ref, d]));
  const publications = new Map(p.publications.map((pub) => [pub.id, pub]));
  const activeIds = new Set<string>();

  for (const entry of p.entries) {
    const txn = entry.txn;
    if (entry.duplicate || !blank(txn.familyRef) || !blank(txn.ownerMemberId)) continue;
    const signed = txn.signedRial;
    if (signed == null) continue;
    const sourceKind = txn.sourceKind;
    if (sourceKind === 'sms' && !p.shareSms) continue;
    // A bank she set aside: its rows never leave, and ones already out are swept below —
    // dropping out of activeIds is what turns yesterday's share into today's tombstone.
    if (excludedBanks.has(txn.bank)) continue;
    const id = familyTxnId(me, txn.ref);
    activeIds.add(id);
    const category = categoryById.get(entry.categoryId);
    const decision = categoryDecisionByRef.get(txn.ref);
    const payload = JSON.stringify({
      kind: 'transaction',
      ownerMemberId: me,
      sourceKind,
      at: txn.at,
      amountRial: Math.abs(signed),
      direction: signed > 0 ? 'in' : 'out',
      bank: txn.bank,
      categoryId: entry.categoryId,
      categoryName: entry.categoryFa,
      categoryKind: category?.kind ?? 'expense',
      transfer: entry.transfer,
      categoryGlyph: category?.glyph ?? '',
      categoryEditorId: blank(decision?.memberId) ? me : decision!.memberId,
      categoryUpdatedAt: decision?.updatedAt ?? 0,
      categoryEditedAt: category ? editedAt(category) : 0,
      merchant: txn.merchant,
    });
    const contentHash = sha256Hex(payload);
    const previous = publications.get(id);
    if (previous && !previous.deleted && previous.contentHash === contentHash) continue;
    const updatedAt = nextStamp(previous?.updatedAt, now);
    out.push({ id, kind: 'transaction', owner: me, updatedAt, payload, publication: { id, sourceKind, contentHash, updatedAt, deleted: false } });
  }

  for (const publication of publications.values()) {
    // Only transaction publications sweep here; every other kind answers for itself, so the next
    // kind cannot forget to exempt itself and be swept as a "transaction" the server refuses.
    if (publication.sourceKind !== 'sms' && publication.sourceKind !== 'manual') continue;
    if (publication.deleted || activeIds.has(publication.id)) continue;
    const updatedAt = nextStamp(publication.updatedAt, now);
    out.push({
      id: publication.id, kind: 'transaction', owner: me, updatedAt, payload: tombstonePayload(publication.id), deleted: true,
      publication: { ...publication, contentHash: '', updatedAt, deleted: true },
    });
  }

  // دارایی is one record per person, replaced wholesale, tombstoned the sync after sharing stops.
  const assetId = assetRecordId(me);
  const assetPublication = publications.get(assetId);
  if (p.assets && p.shareAssets) {
    const shared = safeAssetShareItems(p.assets);
    const payload = JSON.stringify({ kind: 'asset', memberId: me, totalToman: shared.reduce((sum, i) => sum + i.toman, 0), items: shared });
    const contentHash = sha256Hex(payload);
    if (!assetPublication || assetPublication.deleted || assetPublication.contentHash !== contentHash) {
      const updatedAt = nextStamp(assetPublication?.updatedAt, now);
      out.push({ id: assetId, kind: 'asset', owner: me, updatedAt, payload, publication: { id: assetId, sourceKind: 'asset', contentHash, updatedAt, deleted: false } });
    }
  } else if (assetPublication && !assetPublication.deleted && !p.shareAssets) {
    // Only her switch unshares: null prices with the switch on leave the record standing.
    const updatedAt = nextStamp(assetPublication.updatedAt, now);
    out.push({
      id: assetId, kind: 'asset', owner: me, updatedAt, payload: tombstonePayload(assetId), deleted: true,
      publication: { ...assetPublication, contentHash: '', updatedAt, deleted: true },
    });
  }

  // Shared budgets and goals, one record each. Every device that knows one holds a publication
  // for it (applyGoal writes one), so a device that only ever received a goal stays quiet.
  for (const goal of p.goals) {
    const goalId = goalRecordId(goal.id);
    const previous = publications.get(goalId);
    const owner = blank(goal.ownerMemberId) ? me : goal.ownerMemberId;
    if (goal.shared && !goal.deleted) {
      const payload = goalPayload(goal);
      const contentHash = sha256Hex(payload);
      if (previous && !previous.deleted && previous.contentHash === contentHash && previous.updatedAt >= goal.updatedAt) continue;
      // Stamped from the edit, not from this moment: the stamp is what decides whose edit won.
      const updatedAt = nextStamp(previous?.updatedAt, goal.updatedAt);
      out.push({ id: goalId, kind: 'goal', owner, updatedAt, payload, publication: { id: goalId, sourceKind: 'goal', contentHash, updatedAt, deleted: false } });
    } else {
      // Never shared, or already retracted: nothing out there to take back.
      if (!previous || previous.deleted) continue;
      const updatedAt = nextStamp(previous.updatedAt, goal.updatedAt);
      out.push({
        id: goalId, kind: 'goal', owner, updatedAt, payload: tombstonePayload(goalId), deleted: true,
        publication: { ...previous, contentHash: '', updatedAt, deleted: true },
      });
    }
  }

  // The report's excluded categories: only a set somebody here actually touched, never a tombstone.
  const exclusions = p.exclusions;
  if (exclusions && exclusions.updatedAt > 0) {
    const editor = blank(exclusions.editedByMemberId) ? me : exclusions.editedByMemberId;
    const payload = reportExclusionsPayload(exclusions.ids, editor);
    const contentHash = sha256Hex(payload);
    const previous = publications.get(REPORT_EXCLUSIONS_RECORD_ID);
    if (!previous || previous.deleted || previous.contentHash !== contentHash || previous.updatedAt < exclusions.updatedAt) {
      const updatedAt = nextStamp(previous?.updatedAt, exclusions.updatedAt);
      out.push({
        id: REPORT_EXCLUSIONS_RECORD_ID, kind: 'exclusion', owner: editor, updatedAt, payload,
        publication: { id: REPORT_EXCLUSIONS_RECORD_ID, sourceKind: 'exclusion', contentHash, updatedAt, deleted: false },
      });
    }
  }

  const entriesByRef = new Map(p.entries.map((e) => [e.txn.ref, e]));
  for (const decision of categoryDecisions) {
    const categoryId = decision.value;
    if (categoryId == null) continue;
    const txn = entriesByRef.get(decision.ref)?.txn;
    if (!decisionMayLeave(decision, txn, p.shareSms, me, excludedBanks)) continue;
    const target = familyTargetOf(decision, txn, me);
    if (!target) continue;
    const category = categoryById.get(categoryId);
    if (!category) continue;
    const payload = JSON.stringify({
      kind: 'category', target, categoryId: category.id, categoryName: category.nameFa, categoryKind: category.kind,
      categoryGlyph: category.glyph, editedByMemberId: blank(decision.memberId) ? me : decision.memberId, categoryEditedAt: editedAt(category),
    });
    const id = categoryRecordId(target);
    const contentHash = sha256Hex(payload);
    const previous = publications.get(id);
    if (previous && !previous.deleted && previous.contentHash === contentHash) continue;
    const updatedAt = nextStamp(previous?.updatedAt, decision.updatedAt);
    out.push({ id, kind: 'category', owner: ownerOfFamilyTxnId(target) ?? me, updatedAt, payload, publication: { id, sourceKind: 'category', contentHash, updatedAt, deleted: false } });
  }

  // Notes go out on records of their own; a retracted one is walked too, or her words stay put.
  for (const decision of p.decisions) {
    if (decision.kind !== 'note') continue;
    const txn = entriesByRef.get(decision.ref)?.txn;
    if (!decisionMayLeave(decision, txn, p.shareSms, me, excludedBanks)) continue;
    const target = familyTargetOf(decision, txn, me);
    if (!target) continue;
    const id = noteRecordId(target);
    const previous = publications.get(id);
    const note = decision.deleted ? '' : decision.value ?? '';
    if (blank(note) && (!previous || previous.deleted)) continue;
    const payload = JSON.stringify({ kind: 'note', target, note, editedByMemberId: blank(decision.memberId) ? me : decision.memberId });
    const contentHash = sha256Hex(payload);
    if (previous && !previous.deleted && previous.contentHash === contentHash) continue;
    const updatedAt = nextStamp(previous?.updatedAt, decision.updatedAt);
    out.push({ id, kind: 'note', owner: ownerOfFamilyTxnId(target) ?? me, updatedAt, payload, publication: { id, sourceKind: 'note', contentHash, updatedAt, deleted: false } });
  }

  return Promise.all(out.map(async (d) => ({ wire: await wireRecord(session, d.id, d.kind, d.owner, d.updatedAt, d.payload, d.deleted), publication: d.publication })));
}

async function wireRecord(session: Session, id: string, kind: string, ownerMemberId: string, updatedAt: number, payload: string, deleted = false): Promise<WireRecord> {
  const { nonce, body } = await seal(session.key, payload);
  return { id, scope: session.scope, updatedAt, device: session.device, kind, ownerMemberId, authorMemberId: '', deleted, nonce, body };
}

// ---- applying: applyRecord ------------------------------------------------------------------

function ensureMemberPlaceholder(id: string, at: number): void {
  if (!row('familyMembers', id)) put('familyMembers', { id, name: MEMBER_FALLBACK, sharesSms: false, avatar: '', updatedAt: at, deleted: false });
}

/**
 * The category row a synced record asks for, or null when this device's row already says it.
 * A newer edit replaces the row, "newer" by the editor's own stamp; a blank mark is never an edit.
 */
export function syncedCategory(existing: Category | undefined, id: string, name: string, kind: CategoryKindId, glyph: string, editedAtStamp: number, arrivedAt: number): Category | null {
  const stamp = editedAtStamp > 0 ? editedAtStamp : arrivedAt;
  if (!existing) {
    return { id, parentId: null, nameFa: blank(name) ? 'دسته‌بندی نشده' : name, kind, sort: 500, builtin: false, archived: false, updatedAt: stamp, glyph };
  }
  if (blank(glyph)) return null;
  if (!blank(existing.glyph) && editedAtStamp <= existing.updatedAt) return null;
  const next = { ...existing, nameFa: blank(name) ? existing.nameFa : name, glyph, updatedAt: stamp };
  return next.nameFa !== existing.nameFa || next.glyph !== existing.glyph ? next : null;
}

function putSyncedCategory(id: string, name: string, kind: string, glyph: string, editedAtStamp: number, arrivedAt: number, now: number): boolean {
  const next = syncedCategory(
    row('categories', id), id, safeSyncedText(name, 60),
    kind === 'expense' || kind === 'income' || kind === 'transfer' ? kind : 'expense',
    safeSyncedGlyph(glyph),
    // Clamped like every other stamp inside a payload, or one skewed clock pins its mark for ever.
    clampSyncStamp(Math.max(editedAtStamp, 0), now),
    arrivedAt,
  );
  if (!next) return false;
  put('categories', next);
  return true;
}

/** The live answer of one kind about one ref — `forRef`, which hides a retracted one. */
const liveDecision = (ref: string, kind: 'category' | 'note'): Decision | undefined => {
  const d = row('decisions', `${kind}:${ref}`);
  return d && !d.deleted ? d : undefined;
};

function applyMember(record: WireRecord, payload: SyncMemberPayload): boolean {
  const memberId = payload.memberId;
  if (blank(memberId)) return false;
  if (!blank(record.ownerMemberId) && record.ownerMemberId !== memberId) return false;
  const previous = row('familyMembers', memberId);
  if (previous && previous.updatedAt > record.updatedAt) return false;
  put('familyMembers', {
    id: memberId, name: safeSyncedText(payload.name, 32, MEMBER_FALLBACK), sharesSms: payload.sharesSms,
    avatar: safeSyncedAvatar(payload.avatar), updatedAt: record.updatedAt, deleted: record.deleted,
  });
  return true;
}

function applyTransaction(session: Session, record: WireRecord, payload: SyncEntry, now: number): boolean {
  const owner = resolvedTransactionOwner(record.kind, record.ownerMemberId, record.authorMemberId);
  if (blank(owner) || owner === session.member) return false;
  if (!blank(payload.ownerMemberId) && payload.ownerMemberId !== owner) return false;
  const familyRef = record.id.startsWith('txn:') ? record.id : familyTxnId(owner, record.id);
  if (ownerOfFamilyTxnId(familyRef) !== owner) return false;
  const existing = row('familyTxns', familyRef);
  if (payload.direction !== 'in' && payload.direction !== 'out') return false;
  if (payload.amountRial < 0 || payload.amountRial > MAX_PLAUSIBLE_RIAL) return false;
  if (existing && existing.updatedAt > record.updatedAt) return false;
  ensureMemberPlaceholder(owner, record.updatedAt);
  const sourceKind = payload.sourceKind === 'sms' || payload.sourceKind === 'manual' ? payload.sourceKind : record.id.startsWith('s:') ? 'sms' : 'manual';
  put('familyTxns', {
    id: familyRef, ownerMemberId: owner, sourceKind, at: payload.at, day: tehranDay(payload.at),
    amountRial: payload.direction === 'in' ? payload.amountRial : -payload.amountRial || 0,
    bank: safeSyncedText(payload.bank, 40, 'MANUAL'), merchant: safeSyncedText(payload.merchant, 120),
    updatedAt: record.updatedAt, deleted: false, transfer: payload.transfer,
  });

  const categoryId = safeSyncedText(payload.categoryId, 80);
  if (!blank(categoryId)) {
    putSyncedCategory(categoryId, blank(payload.categoryName) ? payload.category : payload.categoryName, payload.categoryKind,
      payload.categoryGlyph, payload.categoryEditedAt, record.updatedAt, now);
    const localRef = familyLocalRef(familyRef);
    const existingDecision = liveDecision(localRef, 'category');
    // Same skew bound as the envelope stamp: this one rides inside the payload.
    const categoryUpdatedAt = clampSyncStamp(Math.max(payload.categoryUpdatedAt, 0), now);
    const categoryEditorId = blank(payload.categoryEditorId) ? owner : payload.categoryEditorId;
    if (categoryUpdateWins(existingDecision?.updatedAt, existingDecision?.memberId ?? '', categoryUpdatedAt, categoryEditorId)) {
      put('decisions', {
        id: `category:${localRef}`, ref: localRef, kind: 'category', value: categoryId,
        createdAt: existingDecision?.createdAt ?? categoryUpdatedAt, updatedAt: categoryUpdatedAt, deleted: false,
        memberId: categoryEditorId, familyRef,
      });
    }
  }
  return true;
}

function answerTarget(session: Session, target: string): string | null {
  const targetOwner = ownerOfFamilyTxnId(target);
  if (!targetOwner) return null;
  return targetOwner === session.member ? localRefOfFamilyTxn(target, session.member) : familyLocalRef(target);
}

function applyCategory(session: Session, record: WireRecord, payload: SyncCategoryPayload, now: number): boolean {
  const localRef = answerTarget(session, payload.target);
  if (localRef == null) return false;
  const categoryId = safeSyncedText(payload.categoryId, 80);
  if (blank(categoryId)) return false;
  // A new mark is news even when the filing it rides on is not.
  const remarked = putSyncedCategory(categoryId, payload.categoryName, payload.categoryKind, payload.categoryGlyph, payload.categoryEditedAt, record.updatedAt, now);
  const existing = liveDecision(localRef, 'category');
  const editor = blank(record.authorMemberId) ? payload.editedByMemberId : record.authorMemberId;
  if (!syncedEditWins(existing?.updatedAt, existing?.memberId ?? '', record.updatedAt, editor)) return remarked;
  put('decisions', {
    id: `category:${localRef}`, ref: localRef, kind: 'category', value: categoryId,
    createdAt: existing?.createdAt ?? record.updatedAt, updatedAt: record.updatedAt, deleted: record.deleted,
    memberId: editor, familyRef: payload.target,
  });
  return true;
}

/** The guard reads the retracted row too, so a note taken back here is not resurrected by an older copy. */
function applyNote(session: Session, record: WireRecord, payload: SyncNotePayload): boolean {
  const localRef = answerTarget(session, payload.target);
  if (localRef == null) return false;
  const note = safeSyncedNote(payload.note);
  const existing = row('decisions', `note:${localRef}`);
  const editor = blank(record.authorMemberId) ? payload.editedByMemberId : record.authorMemberId;
  if (!syncedEditWins(existing?.updatedAt, existing?.memberId ?? '', record.updatedAt, editor)) return false;
  put('decisions', {
    id: `note:${localRef}`, ref: localRef, kind: 'note', value: note,
    createdAt: existing?.createdAt ?? record.updatedAt, updatedAt: record.updatedAt, deleted: note === '',
    memberId: editor, familyRef: payload.target,
  });
  return true;
}

function applyAsset(session: Session, record: WireRecord, payload: SyncAssetPayload): boolean {
  const memberId = removePrefix(record.id, 'asset:');
  if (!isValidSyncIdentity(memberId) || memberId === session.member) return false;
  if (record.ownerMemberId !== memberId || payload.memberId !== memberId) return false;
  const existing = row('familyAssets', memberId);
  if (existing && existing.updatedAt > record.updatedAt) return false;
  const items = payload.items.slice(0, 64)
    .map((i) => ({ name: safeSyncedText(i.name, 60, 'دارایی'), toman: i.toman }))
    .filter((i) => Number.isFinite(i.toman) && i.toman >= 0 && i.toman <= MAX_PLAUSIBLE_RIAL);
  ensureMemberPlaceholder(memberId, record.updatedAt);
  const sane = Number.isFinite(payload.totalToman) && payload.totalToman >= 0 && payload.totalToman <= MAX_PLAUSIBLE_RIAL;
  put('familyAssets', {
    id: memberId, items,
    // Their figure where it is sane, the sum of what survived where it is not.
    totalToman: sane ? payload.totalToman : items.reduce((sum, i) => sum + i.toman, 0),
    updatedAt: record.updatedAt, deleted: false,
  });
  return true;
}

/** One arriving goal record as a row; `goalPayload` of it must be byte-identical to what it would send. */
export function syncedGoal(payload: SyncGoalPayload, kind: GoalKindId, updatedAt: number, owner: string, editor: string): Goal {
  const categoryId = safeSyncedText(payload.categoryId, 80);
  return {
    id: payload.goalId, nameFa: safeSyncedText(payload.nameFa, 40, 'بی‌نام'), targetRial: payload.targetRial, kind,
    // Blank is the total — a cap on everything — never a category id.
    categoryId: blank(categoryId) ? null : categoryId,
    period: (payload.period.length <= 16 ? payload.period : 'jmonth') as GoalPeriodId,
    startsOn: payload.startsOn, endsOn: payload.endsOn, createdAt: payload.createdAt, updatedAt,
    deleted: false, shared: true, ownerMemberId: owner, editedByMemberId: editor,
  };
}

function applyGoal(record: WireRecord, payload: SyncGoalPayload): boolean {
  const goalId = payload.goalId;
  if (blank(goalId) || record.id !== goalRecordId(goalId)) return false;
  const kind = payload.goalKind;
  if (kind !== 'save' && kind !== 'cap') return false;
  if (payload.targetRial < 0 || payload.targetRial > MAX_PLAUSIBLE_RIAL) return false;
  const existing = row('goals', goalId);
  if (existing && existing.updatedAt > record.updatedAt) return false;
  // Two edits on one millisecond arrive in whichever order the pull returned them; only a rule
  // both devices apply makes them agree.
  const editor = blank(record.authorMemberId) ? payload.editedByMemberId : record.authorMemberId;
  if (existing && existing.updatedAt === record.updatedAt && existing.editedByMemberId >= editor) return false;
  const owner = isValidSyncIdentity(payload.ownerMemberId) ? payload.ownerMemberId : record.ownerMemberId;
  const goal = syncedGoal(payload, kind, record.updatedAt, owner, editor);
  ensureMemberPlaceholder(owner, record.updatedAt);
  put('goals', goal);
  // This device's copy of what the household record says, hashed from what it would send.
  put('publications', { id: record.id, sourceKind: 'goal', contentHash: sha256Hex(goalPayload(goal)), updatedAt: record.updatedAt, deleted: false });
  return true;
}

function applyReportExclusions(session: Session, record: WireRecord, payload: SyncExclusionPayload): boolean {
  if (record.id !== REPORT_EXCLUSIONS_RECORD_ID) return false;
  const existing = syncPref('reportExclusions');
  const editor = blank(record.authorMemberId) ? payload.editedByMemberId : record.authorMemberId;
  if (blank(editor)) return false;
  // A blank stored editor is an edit from before this device had a member id; published, it wears
  // this member's name, so the draw is broken against that name.
  if (existing && !syncedEditWins(existing.updatedAt, blank(existing.editedByMemberId) ? session.member : existing.editedByMemberId, record.updatedAt, editor)) return false;
  const ids = safeExcludedCategoryIds(payload.categoryIds);
  setSyncPref('reportExclusions', { ids, updatedAt: record.updatedAt, editedByMemberId: editor });
  // landReportExclusions: the copy every figure reads moves with it.
  setPref('reportExcluded', ids);
  put('publications', { id: record.id, sourceKind: 'exclusion', contentHash: sha256Hex(reportExclusionsPayload(ids, editor)), updatedAt: record.updatedAt, deleted: false });
  return true;
}

/** Somebody deleted a shared goal, or took it back: the local row is buried, never un-shared. */
function applyGoalTombstone(session: Session, record: WireRecord): boolean {
  const goalId = removePrefix(record.id, 'goal:');
  if (blank(goalId)) return false;
  const existing = row('goals', goalId);
  if (!existing) return false;
  const editor = record.authorMemberId;
  if (existing.updatedAt > record.updatedAt || (existing.updatedAt === record.updatedAt && existing.editedByMemberId >= editor)) return false;
  // Her own row, retracted by a record naming her: her own unshare heard back through a peer.
  if (!existing.shared && existing.editedByMemberId === session.member) return false;
  put('goals', { ...existing, updatedAt: record.updatedAt, editedByMemberId: editor, deleted: true });
  put('publications', { id: record.id, sourceKind: 'goal', contentHash: '', updatedAt: record.updatedAt, deleted: true });
  return true;
}

function applyAssetTombstone(session: Session, record: WireRecord): boolean {
  const memberId = removePrefix(record.id, 'asset:');
  if (!isValidSyncIdentity(memberId) || memberId === session.member) return false;
  const existing = row('familyAssets', memberId);
  if (!existing || existing.updatedAt > record.updatedAt) return false;
  put('familyAssets', { ...existing, updatedAt: record.updatedAt, deleted: true });
  return true;
}

function applyTransactionTombstone(session: Session, record: WireRecord): boolean {
  const owner = record.ownerMemberId;
  if (blank(owner) || owner === session.member) return false;
  const existing = row('familyTxns', record.id);
  if (!existing || existing.updatedAt > record.updatedAt) return false;
  put('familyTxns', { ...existing, updatedAt: record.updatedAt, deleted: true });
  return true;
}

/** How the household learns someone was removed. Never applied to this device's own member. */
function applyMemberTombstone(session: Session, record: WireRecord): boolean {
  const memberId = removePrefix(record.id, 'member:');
  if (!isValidSyncIdentity(memberId) || memberId === session.member) return false;
  const existing = row('familyMembers', memberId);
  if (existing && existing.updatedAt > record.updatedAt) return false;
  put('familyMembers', { ...(existing ?? { id: memberId, name: MEMBER_FALLBACK, sharesSms: false, avatar: '' }), updatedAt: record.updatedAt, deleted: true });
  return true;
}

/**
 * Sync.kt `applyRecord`, given the plaintext already opened (null when it would not open). A
 * record that will not open or will not parse is skipped, never the whole pull: it is what a
 * record sealed under a household's previous key looks like.
 */
export function applyRecord(session: Session, record: WireRecord, plain: string | null, now: number): boolean {
  if (record.device === session.device || plain == null) return false;
  if (record.deleted) {
    // A delete is only a delete when the sealed body says so and names this very record.
    const tombstone = parseTombstone(plain);
    if (!tombstone || !tombstone.deleted || blank(tombstone.id) || tombstone.id !== record.id) return false;
    switch (record.kind) {
      case 'transaction': return applyTransactionTombstone(session, record);
      case 'member': return applyMemberTombstone(session, record);
      case 'asset': return applyAssetTombstone(session, record);
      case 'goal': return applyGoalTombstone(session, record);
      default: return false;
    }
  }
  const apply = <T>(payload: T | null, then: (p: T) => boolean): boolean => (payload ? then(payload) : false);
  switch (record.kind) {
    case 'member': return apply(parseMember(plain), (p) => applyMember(record, p));
    case 'category': return apply(parseCategory(plain), (p) => applyCategory(session, record, p, now));
    case 'note': return apply(parseNote(plain), (p) => applyNote(session, record, p));
    case 'asset': return apply(parseAsset(plain), (p) => applyAsset(session, record, p));
    case 'goal': return apply(parseGoal(plain), (p) => applyGoal(record, p));
    // Never deleted, only replaced: a deleted envelope under this kind falls through to nothing.
    case 'exclusion': return apply(parseExclusion(plain), (p) => applyReportExclusions(session, record, p));
    case 'transaction': case 'legacy': return apply(parseEntry(plain), (p) => applyTransaction(session, record, p, now));
    default: return false;
  }
}

/** One pulled page, opened first and then folded in as one write, its cursor last in the queue. */
async function applyPage(session: Session, pulled: PullBody, nextCursor: number, now: number): Promise<number> {
  // The client half of the skew bound, in one choke point: everything downstream sees the clamp.
  const bounded = pulled.records.map((r) => ({ ...r, updatedAt: clampSyncStamp(r.updatedAt, now) }));
  const plains = await Promise.all(bounded.map((r) => (r.device === session.device ? null : openSealed(session.key, r.nonce, r.body))));
  let applied = 0;
  batch(() => {
    bounded.forEach((record, i) => { if (applyRecord(session, record, plains[i], now)) applied++; });
    setSyncPref('syncSeq', nextCursor);
    if (!blank(pulled.primaryMemberId)) setSyncPref('syncPrimaryMember', pulled.primaryMemberId);
  });
  return applied;
}

// ---- syncNow -------------------------------------------------------------------------------

async function registerIdentity(session: Session): Promise<void> {
  const identityKey = `sync_identity_ok:${session.member}:${session.device}`;
  if (await getMeta<boolean>(identityKey, partition(session))) return;
  await request(`${session.base}/v1/identity`, 'POST', session.token, JSON.stringify({ memberId: session.member, deviceId: session.device }));
  await setMeta(identityKey, true, partition(session));
}

/** Her own row as the push needs it: made if missing, and squared with the SMS switch. */
function ownMemberRow(session: Session, shareSms: boolean, now: number): FamilyMember {
  const own = row('familyMembers', session.member) ??
    { id: session.member, name: MEMBER_FALLBACK, sharesSms: shareSms, avatar: '', updatedAt: now, deleted: false };
  const member = own.sharesSms === shareSms ? own : { ...own, sharesSms: shareSms, updatedAt: nextStamp(own.updatedAt, now) };
  if (member !== row('familyMembers', session.member)) put('familyMembers', member);
  return member;
}

export async function syncNow(session: Session, now = Date.now(), assets: AssetShareItem[] | null = null): Promise<SyncResult> {
  return withFamilySync(async () => {
    const stored = await loadSession();
    if (!stored || !sameHouseholdSession(session, stored)) return { sent: 0, received: 0, unsupportedKinds: [] };
    const active = await recoverTokenRotation(stored);
    await registerIdentity(active);
    const shareSms = syncPref('syncShareSms');
    const outgoing = await outgoingRecords({
      session: active, now, entries: ledger().allEntries, member: ownMemberRow(active, shareSms, now),
      publications: rows('publications'), goals: rows('goals'), decisions: rows('decisions'), categories: rows('categories'),
      shareSms, shareAssets: syncPref('syncShareAssets'), excludedBanks: syncPref('syncExcludedBanks'),
      assets, exclusions: syncPref('reportExclusions'),
    });

    let sent = 0;
    const unsupportedKinds = new Set<string>();
    const byKind = new Map<string, PreparedRecord[]>();
    for (const r of outgoing) byKind.set(r.wire.kind, [...(byKind.get(r.wire.kind) ?? []), r]);
    for (const group of byKind.values()) {
      for (let i = 0; i < group.length; i += PUSH_CHUNK) {
        const chunk = group.slice(i, i + PUSH_CHUNK);
        const kind = chunk[0].wire.kind;
        if (unsupportedKinds.has(kind)) continue;
        let response: string;
        try {
          response = await request(`${active.base}/v1/sync`, 'POST', active.token, JSON.stringify({ records: chunk.map((r) => r.wire) }));
        } catch (error) {
          // An old server refuses a kind it does not know for the whole batch; the rest still go.
          if (!(error instanceof SyncHttpError) || error.status !== 400 || error.code !== 'invalid_kind') throw error;
          unsupportedKinds.add(kind);
          continue;
        }
        // The server clamps far-future stamps and says what it stored; the marks take its word.
        const clamped = new Map<string, number>();
        const ack = decode(response, (o) => field(o, 'clamped', (v): v is Json[] => Array.isArray(v) && v.every(isWire), []));
        for (const c of ack ?? []) if (isString(c.id) && isLong(c.updatedAt)) clamped.set(c.id, c.updatedAt);
        const publications = chunk.flatMap((r) => (r.publication ? [{ ...r.publication, updatedAt: clamped.get(r.publication.id) ?? r.publication.updatedAt }] : []));
        putAll('publications', publications);
        const serverAt = clamped.get(REPORT_EXCLUSIONS_RECORD_ID);
        const state = syncPref('reportExclusions');
        if (serverAt !== undefined && state && state.updatedAt > serverAt) setSyncPref('reportExclusions', { ...state, updatedAt: serverAt });
        sent += chunk.length;
      }
    }

    let cursor = syncPref('syncSeq');
    let received = 0;
    let canRotate = false;
    let more: boolean;
    do {
      const pulled = parsePull(await request(`${active.base}/v1/sync?since=${cursor}&limit=${PULL_LIMIT}`, 'GET', active.token));
      const previous = cursor;
      canRotate = pulled.rotationClientSecret;
      const nextCursor = Math.max(cursor, pulled.seq);
      received += await applyPage(active, pulled, nextCursor, now);
      cursor = nextCursor;
      more = (pulled.hasMore ?? pulled.records.length >= PULL_LIMIT) && cursor > previous;
    } while (more);
    if (canRotate) await rotateTokenIfStale(active, now);
    return { sent, received, unsupportedKinds: [...unsupportedKinds] };
  });
}

// ---- token rotation ------------------------------------------------------------------------

interface PendingRotation { oldToken: string; newToken: string; startedAt: number }

/** The old token first; a 401 means the server already took the new one and only the answer was lost. */
async function recoverTokenRotation(session: Session): Promise<Session> {
  const space = partition(session);
  const pending = await getMeta<PendingRotation>('pending-rotation', space);
  if (!pending) return session;
  if (session.token !== pending.oldToken && session.token !== pending.newToken) {
    await deleteMeta('pending-rotation', space);
    return session;
  }
  const secret = pending.newToken.split('.')[1];
  const send = async (token: string): Promise<void> => {
    const response = await request(`${session.base}/v1/rotate`, 'POST', token, JSON.stringify({ secret }));
    if ((JSON.parse(response) as { secret?: unknown }).secret !== secret) throw new Error('rotation mismatch');
  };
  try {
    await send(pending.oldToken);
  } catch (error) {
    if (!(error instanceof SyncHttpError) || error.status !== 401) throw error;
    await send(pending.newToken);
  }
  const rotated = { ...session, token: pending.newToken, issuedAt: pending.startedAt };
  await storeSession(rotated);
  await deleteMeta('pending-rotation', space);
  return rotated;
}

async function rotateTokenIfStale(session: Session, now: number): Promise<void> {
  if (now - session.issuedAt < TOKEN_ROTATE_AFTER_MS) return;
  // Written down before it is asked for, so a lost answer is recoverable on the next run.
  const pending: PendingRotation = { oldToken: session.token, newToken: `${session.token.split('.')[0]}.${randomHex(32)}`, startedAt: now };
  await setMeta('pending-rotation', pending, partition(session));
  await recoverTokenRotation(session);
}

async function activeSession(expected: Session): Promise<Session> {
  const actual = await loadSession();
  if (!actual) throw new Error('no household');
  if (!sameHouseholdSession(expected, actual)) throw new Error('household changed');
  return recoverTokenRotation(actual);
}

// ---- the household: claim, pair, invite, remove, leave, renew -----------------------------

async function claimFreshHousehold(base: string, member: string, device: string): Promise<Session> {
  const hid = randomHex(16);
  const scope = `family:${hid}`;
  const trimmed = base.replace(/\/+$/, '');
  const response = await request(`${trimmed}/v1/claim?hid=${hid}`, 'POST', null, JSON.stringify({ scopes: [scope], memberId: member, deviceId: device }));
  const secret = (JSON.parse(response) as { secret?: string }).secret ?? '';
  const { key, raw } = await generateKey();
  return { base: trimmed, token: `${hid}.${secret}`, issuedAt: Date.now(), device, member, scope, key, raw: toBase64Url(raw) };
}

function ownRow(session: Session, memberName: string): void {
  put('familyMembers', { id: session.member, name: cleanMemberName(memberName), sharesSms: false, avatar: '', updatedAt: Date.now(), deleted: false });
}
function resetFamilySharing(): void {
  setSyncPref('syncShareSms', false);
  setSyncPref('syncShareAssets', false);
  setSyncPref('syncExcludedBanks', []);
}

export function claimHousehold(base: string, memberName: string): Promise<Session> {
  return withFamilySync(async () => {
    const session = await claimFreshHousehold(base, newIdentity(), newIdentity());
    batch(() => {
      setSyncPref('syncSeq', 0);
      resetFamilySharing();
      ownRow(session, memberName);
    });
    await settled();
    await saveSession(session);
    return session;
  });
}

/** Sync.kt `pairingUrl`: `Uri.encode` leaves exactly what `encodeURIComponent` leaves. */
export function pairingUrl(session: Session, code: string): string {
  if (!session.raw) throw new NoInviteKeyError();
  const hid = session.token.split('.')[0];
  return `${session.base}/join#url=${encodeURIComponent(session.base)}&hid=${hid}&pair=${code}` +
    `&scope=${encodeURIComponent(session.scope)}&k=${session.raw}`;
}
/** A session paired before the browser kept the key's bytes: it can sync, but has nothing to hand on. */
export class NoInviteKeyError extends Error {}

export function invite(session: Session): Promise<string> {
  return withFamilySync(async () => {
    const active = await activeSession(session);
    await registerIdentity(active);
    const response = await request(`${active.base}/v1/invite`, 'POST', active.token, JSON.stringify({ scopes: [active.scope] }));
    return (JSON.parse(response) as { code?: string }).code ?? '';
  });
}

/** What a scanned link means here, decided from the stored token: the hid *is* the household. */
export type PairingCase = 'JOIN' | 'SAME_HOUSEHOLD' | 'REJOIN';
export function pairingCase(sessionToken: string | null | undefined, linkHid: string): PairingCase {
  if (sessionToken == null) return 'JOIN';
  return sessionToken.split('.')[0] === linkHid ? 'SAME_HOUSEHOLD' : 'REJOIN';
}

export interface PairingInvite { base: string; hid: string; code: string; scope: string; key: Uint8Array<ArrayBuffer> }

/** The network half of a join: redeem the one-time code for a session. Persists nothing. */
async function pairHousehold(pairing: PairingInvite): Promise<Session> {
  const member = newIdentity();
  const device = newIdentity();
  const response = await request(`${pairing.base}/v1/pair`, 'POST', `${pairing.hid}.${'0'.repeat(64)}`,
    JSON.stringify({ code: pairing.code, memberId: member, deviceId: device }));
  const secret = (JSON.parse(response) as { secret?: string }).secret ?? '';
  return {
    base: pairing.base, token: `${pairing.hid}.${secret}`, issuedAt: Date.now(), device, member, scope: pairing.scope,
    key: await importKey(pairing.key), raw: toBase64Url(pairing.key),
  };
}

/**
 * The local half: the session becomes this device's household, private until she says otherwise.
 * Her report-exclusion stamp is put down rather than carried in, or her join would rewrite a set
 * the household settled on months ago.
 */
async function commitJoin(session: Session, memberName: string): Promise<void> {
  batch(() => {
    setSyncPref('syncSeq', 0);
    resetFamilySharing();
    setSyncPref('reportExclusions', null);
    ownRow(session, memberName);
  });
  await settled();
  await saveSession(session);
}

export function joinHousehold(pairing: PairingInvite, memberName: string): Promise<Session> {
  return withFamilySync(async () => {
    const session = await pairHousehold(pairing);
    await commitJoin(session, memberName);
    return session;
  });
}

/** A confirmed replace: the pair runs first, so a dead code leaves the old household untouched. */
export function rejoinHousehold(pairing: PairingInvite, memberName: string): Promise<Session> {
  return withFamilySync(async () => {
    const former = (await loadSession())?.member ?? null;
    const session = await pairHousehold(pairing);
    buryHousehold(null, former);
    await commitJoin(session, memberName);
    return session;
  });
}

/**
 * Cuts a member's devices off the household. The server publishes the sealed tombstone and
 * revokes in one transaction; only after that is the local row buried. It does not re-key —
 * `renewHousehold` is the answer to that.
 */
export function removeFamilyMember(session: Session, memberId: string): Promise<void> {
  return withFamilySync(async () => {
    const active = await activeSession(session);
    if (memberId === active.member) throw new Error('not for leaving');
    const member = row('familyMembers', memberId);
    const stamp = nextStamp(member?.updatedAt, Date.now());
    const recordId = memberRecordId(memberId);
    const tombstone = await wireRecord(active, recordId, 'member', memberId, stamp, tombstonePayload(recordId), true);
    await request(`${active.base}/v1/remove`, 'POST', active.token, JSON.stringify({ member: memberId, record: tombstone }));
    put('familyMembers', { ...(member ?? { id: memberId, name: MEMBER_FALLBACK, sharesSms: false, avatar: '' }), updatedAt: stamp, deleted: true });
  });
}

/** This device walks out: the goodbye and the revocation are one server call, then the burial. */
export function leaveFamily(session: Session): Promise<void> {
  return withFamilySync(async () => {
    const active = await activeSession(session);
    const recordId = memberRecordId(active.member);
    const stamp = nextStamp(row('familyMembers', active.member)?.updatedAt, Date.now());
    const tombstone = await wireRecord(active, recordId, 'member', active.member, stamp, tombstonePayload(recordId), true);
    await request(`${active.base}/v1/leave`, 'POST', active.token, JSON.stringify({ record: tombstone }));
    batch(() => {
      buryHousehold(null, active.member);
      setSyncPref('syncPrimaryMember', '');
    });
    await settled();
    await clearSession();
  });
}

/**
 * Cryptographic eviction: a fresh household under a fresh key. This device keeps its identity;
 * the publication marks are buried so the next sync re-pushes everything it owns under the new
 * key, and the old household's copy of everyone else is buried here.
 */
export function renewHousehold(): Promise<Session> {
  return withFamilySync(async () => {
    const old = await loadSession();
    if (!old) throw new Error('no household to renew');
    const session = await claimFreshHousehold(old.base, old.member, old.device);
    buryHousehold(session.member, old.member);
    await settled();
    await saveSession(session);
    return session;
  });
}

/**
 * Every local trace of the household being left, buried in place. Written before the session
 * that replaces it: a crash between the two leaves the old session with a buried ledger, which
 * the next sync republishes, never a new session holding the old household's rows.
 */
function buryHousehold(keepMember: string | null, formerMember: string | null): void {
  const now = Date.now();
  batch(() => {
    setSyncPref('syncSeq', 0);
    resetFamilySharing();
    // Buried rather than deleted, so the same rows keep their monotonic stamps under the new key.
    putAll('publications', rows('publications').map((p) => ({ ...p, deleted: true })));
    for (const member of rows('familyMembers')) {
      if (member.deleted) continue;
      if (member.id === keepMember) {
        if (member.sharesSms) put('familyMembers', { ...member, sharesSms: false, updatedAt: nextStamp(member.updatedAt, now) });
        continue;
      }
      put('familyMembers', { ...member, updatedAt: nextStamp(member.updatedAt, now), deleted: true });
    }
    putAll('familyTxns', rows('familyTxns').filter((t) => !t.deleted).map((t) => ({ ...t, updatedAt: nextStamp(t.updatedAt, now), deleted: true })));
    putAll('familyAssets', rows('familyAssets').filter((a) => !a.deleted).map((a): FamilyAsset => ({ ...a, updatedAt: nextStamp(a.updatedAt, now), deleted: true })));
    // Somebody else's shared cap goes the way their transactions go; hers stay, back on «مال خودم».
    for (const goal of rows('goals')) {
      if (goal.deleted) continue;
      // Every goal she made while paired carries the member id being left — private caps and
      // instalment plans included — so that id is hers too, or leaving deletes all her plans.
      const hers = blank(goal.ownerMemberId) || goal.ownerMemberId === keepMember || goal.ownerMemberId === formerMember;
      put('goals', hers
        ? { ...goal, shared: false, ownerMemberId: keepMember ?? '', updatedAt: nextStamp(goal.updatedAt, now) }
        : { ...goal, updatedAt: nextStamp(goal.updatedAt, now), deleted: true });
    }
  });
}
