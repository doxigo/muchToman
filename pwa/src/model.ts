/**
 * The app's tables, as the phone keeps them — `durable.db` (Ledger.kt, Rules.kt, Links.kt,
 * Goals.kt) and the prefs blobs (Data.kt, Sms.kt) — with the Kotlin field names kept so a
 * reader can hold the two side by side. Money is whole Rial in a `number` (safe to 2^53, and
 * the app refuses anything past 1e14); holdings and rates are Toman doubles, exactly as there.
 *
 * What the phone derives (derived.db: `Txn`, `LedgerEntry`) lives here too, because every
 * screen reads it; it is rebuilt in memory from these tables, never stored.
 */

// ---- durable.db ----------------------------------------------------------------------------

/** A pasted bank message: the browser's `sms_source`. `id` is the message's content hash. */
export interface Source {
  id: string;
  /** `Bank` enum name the message was read as (the paste sheet asks), or '' when unknown. */
  bank: string;
  body: string;
  /** When it happened: the printed time when the message carries one, else when pasted. */
  at: number;
  ingestedAt: number;
}

export interface ManualTxn {
  id: string; // uuid7, the ref is `m:<id>`
  at: number;
  day: number;
  /** Signed: negative is money out. */
  amountRial: number;
  accountId: string | null;
  categoryId: string | null;
  merchant: string;
  note: string;
  createdAt: number;
  updatedAt: number;
  deleted: boolean;
}

export type CategoryKindId = 'expense' | 'income' | 'transfer';

export interface Category {
  id: string;
  parentId: string | null;
  nameFa: string;
  kind: CategoryKindId;
  sort: number;
  builtin: boolean;
  archived: boolean;
  updatedAt: number;
  /** `CategoryGlyph` enum name; blank on a shipped category nobody re-marked. */
  glyph: string;
}

export interface Rule {
  id: string;
  priority: number;
  categoryId: string;
  pMerchantNorm: string | null;
  pMerchantLike: string | null;
  pAddrKey: string | null;
  pBank: string | null;
  pChannel: string | null;
  pDirection: string | null;
  pMinRial: number | null;
  pMaxRial: number | null;
  pInstrument: string | null;
  enabled: boolean;
  builtin: boolean;
  originRef: string | null;
  createdAt: number;
  updatedAt: number;
  deleted: boolean;
}

export type DecisionKind = 'category' | 'note' | 'hide' | 'worth_it' | 'account' | 'exclude' | 'installment';

/** `txn_decision`: one per (ref, kind). `id` is `${kind}:${ref}` here, which is that key. */
export interface Decision {
  id: string;
  ref: string;
  kind: DecisionKind;
  value: string | null;
  createdAt: number;
  updatedAt: number;
  deleted: boolean;
  memberId: string;
  familyRef: string;
}

export interface LinkDecision {
  id: string;
  aRef: string; // aRef < bRef
  bRef: string;
  kind: 'transfer' | 'duplicate';
  verdict: 'confirmed' | 'rejected';
  createdAt: number;
  updatedAt: number;
  deleted: boolean;
}

export type GoalKindId = 'save' | 'cap' | 'installment';
export type GoalPeriodId = 'week' | 'jmonth' | 'jquarter' | 'once';

/** Budgets (cap), savings goals (save) and installment plans (installment), one table. */
export interface Goal {
  id: string;
  nameFa: string;
  targetRial: number;
  kind: GoalKindId;
  /** Null on a cap is «کل خرج», the total. */
  categoryId: string | null;
  period: GoalPeriodId;
  startsOn: number; // Tehran day
  endsOn: number | null;
  createdAt: number;
  updatedAt: number;
  deleted: boolean;
  shared: boolean;
  ownerMemberId: string;
  editedByMemberId: string;
}

export interface BalanceAnchor {
  id: string;
  accountId: string; // Bank enum name
  at: number;
  balanceRial: number;
  source: 'user' | 'migrated';
  createdAt: number;
  updatedAt: number;
  deleted: boolean;
}

// ---- the household, as sync has folded it (Ledger.kt:383-441) -------------------------------

export interface FamilyMember {
  id: string;
  name: string;
  sharesSms: boolean;
  /** '' (the initial), 👨/👩, or `b64:` + a 128px JPEG. */
  avatar: string;
  updatedAt: number;
  deleted: boolean;
}

export interface FamilyTxn {
  id: string; // the wire id, `txn:<owner>:<hex>`
  ownerMemberId: string;
  sourceKind: 'sms' | 'manual';
  at: number;
  day: number;
  amountRial: number; // signed
  bank: string;
  merchant: string;
  updatedAt: number;
  deleted: boolean;
  transfer: boolean;
}

export interface FamilyAsset {
  id: string; // the member id
  items: Array<{ name: string; toman: number }>;
  totalToman: number;
  updatedAt: number;
  deleted: boolean;
}

/** What this browser last published under each wire id, so an unchanged row is not re-sent. */
export interface Publication {
  id: string;
  sourceKind: string;
  contentHash: string;
  updatedAt: number;
  deleted: boolean;
}

export interface Tables {
  sources: Source;
  manual: ManualTxn;
  categories: Category;
  rules: Rule;
  decisions: Decision;
  links: LinkDecision;
  goals: Goal;
  anchors: BalanceAnchor;
  familyMembers: FamilyMember;
  familyTxns: FamilyTxn;
  familyAssets: FamilyAsset;
  publications: Publication;
}
export type TableName = keyof Tables;
export const TABLES: TableName[] = [
  'sources', 'manual', 'categories', 'rules', 'decisions', 'links', 'goals', 'anchors',
  'familyMembers', 'familyTxns', 'familyAssets', 'publications',
];

// ---- prefs (Data.kt Store) -------------------------------------------------------------------

export interface WalletLink { network: string; networkFa: string; address: string; contract: string; updatedAt: number }
export interface Holding {
  typeId: string;
  amount: number;
  excluded: boolean;
  wallet: WalletLink | null;
  label: string;
  /** Blank on rows from before per-row ids; `key` falls back to the type. */
  id: string;
}
export const holdingKey = (h: Holding): string => h.id || h.typeId;

export interface WalletOption { network: string; networkFa: string; contract: string }
export interface Coin { id: string; name: string; en: string; icon: string; wallets: WalletOption[] }
export interface Rates { updatedAt: number; toman: Record<string, number>; coins: Coin[] }

export type ThemeMode = 'SYSTEM' | 'LIGHT' | 'DARK';

/**
 * Every prefs key the browser keeps, with its default. The names are the phone's, so a backup
 * or a reader moving between the two sees the same words.
 */
export interface Prefs {
  name: string;
  themeMode: ThemeMode;
  onboarded: boolean;
  holdings: Holding[];
  overrides: Record<string, number>;
  rates: Rates | null;
  /** UTC epoch day → Toman total, ≤ 400 days (Data.kt recordDay). */
  history: Record<string, number>;
  rateHistory: Record<string, number>;
  lockEnabled: boolean;
  familyTotal: boolean;
  reportExcluded: string[];
  /** The household's copy of the same choice, last-write-wins across members (Sync.kt:471). */
  reportExclusions: { ids: string[]; updatedAt: number; editedByMemberId: string } | null;
  ledgerStartsOn: number;
  installmentReminder: number;
  budgetMarks: Array<{ goalId: string; windowStart: number; level: number }>;
  installmentMarks: Record<string, number>;
  lastBackupAt: number;
  backupReminderEnabled: boolean;
  /** Banks whose accounts are switched out of the total. */
  disabledBanks: string[];
  /** The last bank the paste sheet read a message as. */
  lastPasteBank: string;
  /** WebAuthn credential id (base64url) the lock unlocks with. */
  lockCredential: string;
  // Household sync state (sync.ts SyncPrefs; durable_meta on the phone). A backup strips the
  // cursor and the SMS-sharing flag, as the phone's BACKUP_STRIPPED_META does.
  syncSeq: number;
  syncShareSms: boolean;
  syncShareAssets: boolean;
  syncExcludedBanks: string[];
  syncPrimaryMember: string;
}

export const PREF_DEFAULTS: Prefs = {
  name: '',
  themeMode: 'SYSTEM',
  onboarded: false,
  holdings: [],
  overrides: {},
  rates: null,
  history: {},
  rateHistory: {},
  lockEnabled: false,
  familyTotal: false,
  // The pass-through categories — قرض and همسر — sit outside the totals until she says
  // otherwise (Rules.kt PASS_THROUGH_CATEGORIES, Data.kt reportExcluded).
  reportExcluded: ['cat_loan', 'cat_loan_back', 'cat_spouse'],
  reportExclusions: null,
  ledgerStartsOn: 0,
  installmentReminder: 1,
  budgetMarks: [],
  installmentMarks: {},
  lastBackupAt: 0,
  backupReminderEnabled: false,
  disabledBanks: [],
  lastPasteBank: '',
  lockCredential: '',
  syncSeq: 0,
  syncShareSms: false,
  syncShareAssets: false,
  syncExcludedBanks: [],
  syncPrimaryMember: '',
};

// ---- derived (derived.db, Derived.kt) --------------------------------------------------------

/** A row of the ledger as the phone derives it (Derived.kt Txn). */
export interface Txn {
  ref: string; // `s:<hash>:<seq>`, `m:<uuid7>`, `f:<sha256(familyRef)>`
  srcHash: string;
  seq: number;
  at: number;
  day: number;
  bank: string;
  accountId: string;
  direction: 'in' | 'out' | null;
  amountRial: number | null;
  signedRial: number | null;
  balanceRial: number | null;
  feeRial: number | null;
  mask: string;
  instrument: string;
  merchant: string;
  merchantNorm: string;
  refNo: string;
  printedAt: string;
  channel: string;
  unitPrinted: string;
  inferred: boolean;
  familyRef: string;
  ownerMemberId: string;
  sourceKind: 'sms' | 'manual';
}

export interface LedgerEntry {
  txn: Txn;
  categoryId: string;
  categoryFa: string;
  confidence: number;
  needsReview: boolean;
  duplicate: boolean;
  transfer: boolean;
  ownerMemberId: string;
  ownerName: string;
  ownerAvatar: string;
  categoryEditorName: string;
  note: string;
  noteAuthorName: string;
  sharedWithFamily: boolean;
}
