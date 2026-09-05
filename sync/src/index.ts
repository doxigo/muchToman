/**
 * muchtoman-sync — a household's ledger, moved between her devices and never read on the way.
 *
 * The server stores ciphertext. Every report, every narrative and every classification runs on
 * the device that owns the data, so this side has no use for plaintext and is not given any:
 * a record is an opaque AES-GCM blob plus the routing it takes to reach the right devices.
 *
 * That is not only a privacy position, it is less code. There is no transaction schema here, no
 * field validation, no rounding, and no way for an amount to reach a log line — the Phase 3
 * exit criterion "private transaction data never appears in application logs" is true by
 * construction rather than by review.
 *
 * It also makes the family sharing model real rather than decorative. "Share the amount and the
 * category but not the merchant" is not a policy this Worker promises to enforce; it is the
 * sending device encrypting a projection under the family key and the full record under its
 * own. The owner cannot read a private record because the owner does not have the key.
 *
 * Conventions follow ../worker/src/index.ts: uniform response builders that always set an
 * explicit cache-control and nosniff, size-capped body reads, a typed error carrying a stable
 * string code, and single-line structured console.error.
 */

import { DurableObject } from 'cloudflare:workers';

const MAX_SYNC_REQUEST_BYTES = 2 * 1024 * 1024;
const MAX_RECORDS_PER_PUSH = 500;
const MAX_PULL_RECORD_BYTES = 768 * 1024;
const MAX_BODY_BYTES = 64 * 1024;
const MAX_SCOPE_CHARS = 128;
// A household is a family, not a tenant: sixteen devices and a hundred and twenty thousand
// records are an order of magnitude past any real household, and a bound that exists is what
// keeps one hijacked token from growing a Durable Object without limit.
const MAX_RECORD_ROWS = 120_000;
const MAX_DEVICES = 16;
// LWW griefing/skew bound: a stamp from the far future would win every merge for ever, so
// nothing may claim to be written more than a day ahead of this server's clock. The clamped
// value is returned to the pusher so both sides converge on the same stamp.
const MAX_STAMP_SKEW_MS = 24 * 60 * 60 * 1000;
// A record id is the client's own transaction reference: "s:" + a 64-character SHA-256 + ":"
// + a sequence number, or "m:" + a UUID. 128 clears both with room to spare; 64 did not clear
// the first one, which is every SMS-derived transaction there is.
const MAX_ID_CHARS = 256;
const MAX_DEVICE_CHARS = 64;
const MAX_MEMBER_CHARS = 64;
const DEFAULT_RATES_ORIGIN = 'https://rates.muchtoman.com';

/** How long a pairing token is good for. Long enough to walk to the other phone, no longer. */
const PAIRING_TTL_MS = 10 * 60 * 1000;

class SyncError extends Error {
  constructor(readonly code: string, readonly status: number) {
    super(code);
  }
}

class BodyTooLargeError extends Error {}

function jsonResponse(payload: unknown, status = 200, cacheControl = 'no-store'): Response {
  return new Response(JSON.stringify(payload), {
    status,
    headers: {
      'content-type': 'application/json; charset=utf-8',
      'cache-control': cacheControl,
      'x-content-type-options': 'nosniff',
    },
  });
}

function textResponse(body: string, status = 200, allow?: string): Response {
  const headers: Record<string, string> = {
    'content-type': 'text/plain; charset=utf-8',
    'cache-control': 'no-store',
    'x-content-type-options': 'nosniff',
  };
  if (allow) headers.allow = allow;
  return new Response(body, { status, headers });
}

function errorMessage(error: unknown): string {
  return (error instanceof Error ? error.message : String(error)).slice(0, 500);
}

async function readTextLimited(request: Request, maxBytes: number): Promise<string> {
  const declared = request.headers.get('content-length');
  if (declared && Number(declared) > maxBytes) throw new BodyTooLargeError();
  const body = request.body;
  if (!body) return '';
  const reader = body.getReader();
  const chunks: Uint8Array[] = [];
  let total = 0;
  try {
    for (;;) {
      const { done, value } = await reader.read();
      if (done) break;
      total += value.byteLength;
      if (total > maxBytes) throw new BodyTooLargeError();
      chunks.push(value);
    }
  } finally {
    reader.releaseLock();
  }
  const joined = new Uint8Array(total);
  let at = 0;
  for (const chunk of chunks) {
    joined.set(chunk, at);
    at += chunk.byteLength;
  }
  return new TextDecoder().decode(joined);
}

function hex(bytes: ArrayBuffer): string {
  return [...new Uint8Array(bytes)].map((b) => b.toString(16).padStart(2, '0')).join('');
}

async function sha256Hex(input: string): Promise<string> {
  return hex(await crypto.subtle.digest('SHA-256', new TextEncoder().encode(input)));
}

/** Constant-time compare, so a token cannot be recovered a byte at a time from response timing. */
function timingSafeEqual(a: string, b: string): boolean {
  const encoder = new TextEncoder();
  return crypto.subtle.timingSafeEqual(encoder.encode(a.padEnd(64, '0')), encoder.encode(b.padEnd(64, '0')));
}

function randomToken(bytes = 32): string {
  return hex(crypto.getRandomValues(new Uint8Array(bytes)).buffer);
}

/**
 * `<householdId>.<secret>`.
 *
 * The household is in the token so the Worker can route to the right object without a lookup
 * table anywhere — and therefore without a KV namespace whose eventual consistency would make
 * "a revoked device loses access immediately" false. The object itself is the only authority on
 * whether the secret is good, so revocation takes effect on the very next request.
 */
function splitToken(raw: string | null): { hid: string; secret: string } | null {
  if (!raw) return null;
  const value = raw.startsWith('Bearer ') ? raw.slice(7) : raw;
  const dot = value.indexOf('.');
  if (dot <= 0) return null;
  const hid = value.slice(0, dot);
  const secret = value.slice(dot + 1);
  if (!/^[a-f0-9]{16,64}$/.test(hid) || !/^[a-f0-9]{32,128}$/.test(secret)) return null;
  return { hid, secret };
}

interface PushRecord {
  id: string;
  scope: string;
  updatedAt: number;
  device: string;
  kind: 'legacy' | 'member' | 'transaction' | 'category' | 'asset' | 'note' | 'goal';
  ownerMemberId: string;
  authorMemberId?: string;
  deleted?: boolean;
  nonce: string;
  body: string;
}

function asRecords(value: unknown): PushRecord[] {
  if (!Array.isArray(value)) throw new SyncError('invalid_request', 400);
  if (value.length > MAX_RECORDS_PER_PUSH) throw new SyncError('too_many_records', 413);
  return value.map((raw) => {
    if (raw == null || typeof raw !== 'object') throw new SyncError('invalid_record', 400);
    const r = raw as Record<string, unknown>;
    const id = typeof r.id === 'string' ? r.id : '';
    const scope = typeof r.scope === 'string' ? r.scope : '';
    const device = typeof r.device === 'string' ? r.device : '';
    const nonce = typeof r.nonce === 'string' ? r.nonce : '';
    const body = typeof r.body === 'string' ? r.body : '';
    const kind = typeof r.kind === 'string' ? r.kind : 'legacy';
    const ownerMemberId = typeof r.ownerMemberId === 'string' ? r.ownerMemberId : '';
    const updatedAt = typeof r.updatedAt === 'number' ? r.updatedAt : NaN;
    // Shape only. Nothing here inspects what the blob means, because nothing here can.
    if (!id || id.length > MAX_ID_CHARS) throw new SyncError('invalid_id', 400);
    if (!scope || scope.length > MAX_SCOPE_CHARS) throw new SyncError('invalid_scope', 400);
    if (!device || device.length > MAX_DEVICE_CHARS) throw new SyncError('invalid_device', 400);
    if (!['legacy', 'member', 'transaction', 'category', 'asset', 'note', 'goal'].includes(kind)) {
      throw new SyncError('invalid_kind', 400);
    }
    if (ownerMemberId.length > MAX_MEMBER_CHARS) throw new SyncError('invalid_member', 400);
    if (!Number.isFinite(updatedAt) || updatedAt < 0) throw new SyncError('invalid_updated_at', 400);
    if (!nonce || nonce.length > 64) throw new SyncError('invalid_nonce', 400);
    if (body.length > MAX_BODY_BYTES) throw new SyncError('body_too_large', 413);
    return {
      id, scope, updatedAt, device,
      kind: kind as PushRecord['kind'], ownerMemberId,
      deleted: r.deleted === true, nonce, body,
    };
  });
}

const IDENTITY = /^[a-f0-9]{16,64}$/;

function identity(value: unknown, fallback: () => string): string {
  return typeof value === 'string' && IDENTITY.test(value) ? value : fallback();
}

interface AuthorisedDevice {
  id: string;
  memberId: string;
  scopes: string[];
  tokenHash: string;
  identityLocked: boolean;
}

/**
 * One household. Everything it owns lives in this object's own SQLite database, which is the
 * whole security argument: there is no query in this file that could reach another household's
 * rows even if someone wrote one.
 */
export class Household extends DurableObject<Env> {
  private sql: SqlStorage;

  constructor(ctx: DurableObjectState, env: Env) {
    super(ctx, env);
    this.sql = ctx.storage.sql;
    ctx.blockConcurrencyWhile(async () => this.migrate());
  }

  private migrate(): void {
    this.sql.exec(`
      CREATE TABLE IF NOT EXISTS record (
        id         TEXT PRIMARY KEY,
        scope      TEXT NOT NULL,
        seq        INTEGER NOT NULL,
        updated_at INTEGER NOT NULL,
        device     TEXT NOT NULL,
        kind       TEXT NOT NULL DEFAULT 'legacy',
        owner_member TEXT NOT NULL DEFAULT '',
        author_member TEXT NOT NULL DEFAULT '',
        deleted    INTEGER NOT NULL DEFAULT 0,
        nonce      TEXT NOT NULL,
        body       TEXT NOT NULL
      );
      CREATE INDEX IF NOT EXISTS record_seq ON record(seq);
      DROP INDEX IF EXISTS record_scope_seq;

      CREATE TABLE IF NOT EXISTS device (
        id         TEXT PRIMARY KEY,
        member_id  TEXT NOT NULL DEFAULT '',
        identity_locked INTEGER NOT NULL DEFAULT 1,
        token_hash TEXT NOT NULL,
        scopes     TEXT NOT NULL,
        added_at   INTEGER NOT NULL,
        last_seen  INTEGER NOT NULL
      );

      CREATE TABLE IF NOT EXISTS pairing (
        code_hash  TEXT PRIMARY KEY,
        scopes     TEXT NOT NULL,
        expires_at INTEGER NOT NULL
      );

      CREATE TABLE IF NOT EXISTS meta (k TEXT PRIMARY KEY, v TEXT NOT NULL);
      CREATE TABLE IF NOT EXISTS _sql_schema_migrations (
        id INTEGER PRIMARY KEY,
        applied_at TEXT NOT NULL DEFAULT (datetime('now'))
      );
    `);

    const recordColumns = new Set(
      [...this.sql.exec<{ name: string }>('PRAGMA table_info(record)')].map((row) => row.name),
    );
    if (!recordColumns.has('kind')) {
      this.sql.exec("ALTER TABLE record ADD COLUMN kind TEXT NOT NULL DEFAULT 'legacy'");
    }
    if (!recordColumns.has('owner_member')) {
      this.sql.exec("ALTER TABLE record ADD COLUMN owner_member TEXT NOT NULL DEFAULT ''");
    }
    if (!recordColumns.has('author_member')) {
      this.sql.exec("ALTER TABLE record ADD COLUMN author_member TEXT NOT NULL DEFAULT ''");
    }
    const deviceColumns = new Set(
      [...this.sql.exec<{ name: string }>('PRAGMA table_info(device)')].map((row) => row.name),
    );
    if (!deviceColumns.has('member_id')) {
      this.sql.exec("ALTER TABLE device ADD COLUMN member_id TEXT NOT NULL DEFAULT ''");
    }
    if (!deviceColumns.has('identity_locked')) {
      // Existing tokens predate explicit person/device identity. They get exactly one upgrade.
      this.sql.exec('ALTER TABLE device ADD COLUMN identity_locked INTEGER NOT NULL DEFAULT 0');
    }
    this.sql.exec('INSERT OR IGNORE INTO _sql_schema_migrations (id) VALUES (2)');
    this.sql.exec('INSERT OR IGNORE INTO _sql_schema_migrations (id) VALUES (3)');
  }

  /**
   * The founder: the member who claimed the household. Recorded at claim; a household created
   * before this existed backfills lazily from its oldest device row, which is the claimer's.
   */
  private primaryMember(): string {
    const stored = [...this.sql.exec<{ v: string }>('SELECT v FROM meta WHERE k = ?', 'primary_member')][0];
    if (stored) return stored.v;
    const oldest = [...this.sql.exec<{ member_id: string }>(
      'SELECT member_id FROM device ORDER BY added_at ASC, id ASC LIMIT 1',
    )][0];
    const member = oldest && IDENTITY.test(oldest.member_id) ? oldest.member_id : '';
    if (member) this.sql.exec('INSERT OR REPLACE INTO meta (k, v) VALUES (?, ?)', 'primary_member', member);
    return member;
  }

  private device(secretHash: string): AuthorisedDevice | null {
    for (const row of this.sql.exec<{
      id: string; member_id: string; identity_locked: number; token_hash: string; scopes: string;
    }>(
      'SELECT id, member_id, identity_locked, token_hash, scopes FROM device',
    )) {
      if (timingSafeEqual(row.token_hash, secretHash)) {
        const memberId = IDENTITY.test(row.member_id) ? row.member_id : row.token_hash.slice(0, 32);
        return {
          id: row.id,
          memberId,
          scopes: JSON.parse(row.scopes) as string[],
          tokenHash: row.token_hash,
          identityLocked: row.identity_locked === 1,
        };
      }
    }
    return null;
  }

  async fetch(request: Request): Promise<Response> {
    const url = new URL(request.url);
    const path = url.pathname;

    try {
      if (path === '/claim' && request.method === 'POST') return await this.claim(request);
      if (path === '/invite' && request.method === 'POST') return await this.invite(request);
      if (path === '/pair' && request.method === 'POST') return await this.pair(request);
      if (path === '/identity' && request.method === 'POST') return await this.setIdentity(request);
      if (path === '/pull' && request.method === 'GET') return await this.pull(request, url);
      if (path === '/push' && request.method === 'POST') return await this.push(request);
      if (path === '/remove' && request.method === 'POST') return await this.remove(request);
      if (path === '/leave' && request.method === 'POST') return await this.leave(request);
      if (path === '/revoke' && request.method === 'POST') return await this.revoke(request);
      if (path === '/rotate' && request.method === 'POST') return await this.rotate(request);
      return textResponse('not found\n', 404);
    } catch (error) {
      if (error instanceof BodyTooLargeError) return jsonResponse({ code: 'body_too_large' }, 413);
      if (error instanceof SyncError) return jsonResponse({ code: error.code }, error.status);
      // The record body never appears here: it is ciphertext and nothing in this file decodes it.
      console.error(JSON.stringify({ message: 'household failed', error: errorMessage(error) }));
      return jsonResponse({ code: 'internal' }, 500);
    }
  }

  /** First device. Creates the household and takes the first token; refused ever after. */
  private async claim(request: Request): Promise<Response> {
    const existing = [...this.sql.exec<{ n: number }>('SELECT COUNT(*) AS n FROM device')][0];
    if (existing && existing.n > 0) throw new SyncError('already_claimed', 409);
    const body = JSON.parse((await readTextLimited(request, 4096)) || '{}') as Record<string, unknown>;
    const scopes = Array.isArray(body.scopes) ? (body.scopes as string[]).slice(0, 16) : [];
    if (scopes.length === 0) throw new SyncError('invalid_scope', 400);
    const memberId = identity(body.memberId, () => randomToken(16));
    const deviceId = identity(body.deviceId, () => randomToken(16));
    const secret = randomToken();
    const now = Date.now();
    this.sql.exec(
      'INSERT INTO device (id, member_id, token_hash, scopes, added_at, last_seen) VALUES (?, ?, ?, ?, ?, ?)',
      deviceId,
      memberId,
      await sha256Hex(secret),
      JSON.stringify(scopes),
      now,
      now,
    );
    this.sql.exec('INSERT OR REPLACE INTO meta (k, v) VALUES (?, ?)', 'primary_member', memberId);
    return jsonResponse({ secret, memberId, deviceId });
  }

  /** A one-time code an existing device shows, which the new one redeems for a token of its own. */
  private async invite(request: Request): Promise<Response> {
    const auth = await this.authorise(request);
    const body = JSON.parse((await readTextLimited(request, 4096)) || '{}') as Record<string, unknown>;
    const scopes = Array.isArray(body.scopes)
      ? (body.scopes as string[]).filter((s) => auth.scopes.includes(s))
      : auth.scopes;
    if (scopes.length === 0) throw new SyncError('invalid_scope', 400);
    const code = randomToken(16);
    this.sql.exec('DELETE FROM pairing WHERE expires_at < ?', Date.now());
    this.sql.exec(
      'INSERT OR REPLACE INTO pairing (code_hash, scopes, expires_at) VALUES (?, ?, ?)',
      await sha256Hex(code),
      JSON.stringify(scopes),
      Date.now() + PAIRING_TTL_MS,
    );
    // The scope KEY is not here and never passes through this Worker. It rides in the fragment
    // of the QR's URL, which is never sent in an HTTP request at all.
    return jsonResponse({ code, expiresIn: PAIRING_TTL_MS });
  }

  private async pair(request: Request): Promise<Response> {
    const body = JSON.parse((await readTextLimited(request, 4096)) || '{}') as Record<string, unknown>;
    const code = typeof body.code === 'string' ? body.code : '';
    if (!code) throw new SyncError('invalid_request', 400);
    const codeHash = await sha256Hex(code);
    const now = Date.now();
    this.sql.exec('DELETE FROM pairing WHERE expires_at < ?', now);
    const row = [...this.sql.exec<{ scopes: string }>(
      'SELECT scopes FROM pairing WHERE code_hash = ?',
      codeHash,
    )][0];
    if (!row) throw new SyncError('invalid_code', 403);
    // One time means one time: redeeming consumes it whether or not anything later fails.
    this.sql.exec('DELETE FROM pairing WHERE code_hash = ?', codeHash);
    const memberId = identity(body.memberId, () => randomToken(16));
    const deviceId = identity(body.deviceId, () => randomToken(16));
    const identityCollision = [...this.sql.exec<{ n: number }>(
      'SELECT COUNT(*) AS n FROM device WHERE id = ? OR member_id = ?',
      deviceId,
      memberId,
    )][0];
    if (identityCollision && identityCollision.n > 0) throw new SyncError('identity_exists', 409);
    const devices = [...this.sql.exec<{ n: number }>('SELECT COUNT(*) AS n FROM device')][0];
    if (devices && devices.n >= MAX_DEVICES) throw new SyncError('too_many_devices', 409);
    const secret = randomToken();
    this.sql.exec(
      'INSERT INTO device (id, member_id, token_hash, scopes, added_at, last_seen) VALUES (?, ?, ?, ?, ?, ?)',
      deviceId,
      memberId,
      await sha256Hex(secret),
      row.scopes,
      now,
      now,
    );
    return jsonResponse({ secret, scopes: JSON.parse(row.scopes), memberId, deviceId });
  }

  private async authorise(request: Request): Promise<AuthorisedDevice> {
    const secret = request.headers.get('x-device-secret');
    if (!secret) throw new SyncError('unauthorised', 401);
    const found = this.device(await sha256Hex(secret));
    if (!found) throw new SyncError('unauthorised', 401);
    this.sql.exec('UPDATE device SET last_seen = ? WHERE id = ?', Date.now(), found.id);
    return found;
  }

  private async setIdentity(request: Request): Promise<Response> {
    const auth = await this.authorise(request);
    const body = JSON.parse((await readTextLimited(request, 4096)) || '{}') as Record<string, unknown>;
    const memberId = identity(body.memberId, () => '');
    const deviceId = identity(body.deviceId, () => '');
    if (!memberId || !deviceId) throw new SyncError('invalid_identity', 400);
    if (auth.identityLocked) {
      if (memberId !== auth.memberId || deviceId !== auth.id) {
        throw new SyncError('identity_locked', 409);
      }
      return jsonResponse({ memberId: auth.memberId, deviceId: auth.id });
    }
    const collision = [...this.sql.exec<{ n: number }>(
      'SELECT COUNT(*) AS n FROM device WHERE id = ? AND token_hash != ?',
      deviceId,
      auth.tokenHash,
    )][0];
    if (collision && collision.n > 0) throw new SyncError('device_exists', 409);
    const memberCollision = [...this.sql.exec<{ n: number }>(
      'SELECT COUNT(*) AS n FROM device WHERE member_id = ? AND token_hash != ?',
      memberId,
      auth.tokenHash,
    )][0];
    if (memberCollision && memberCollision.n > 0) throw new SyncError('member_exists', 409);
    this.sql.exec(
      'UPDATE device SET id = ?, member_id = ?, identity_locked = 1, last_seen = ? WHERE token_hash = ?',
      deviceId,
      memberId,
      Date.now(),
      auth.tokenHash,
    );
    return jsonResponse({ memberId, deviceId });
  }

  private async pull(request: Request, url: URL): Promise<Response> {
    const auth = await this.authorise(request);
    const since = Number(url.searchParams.get('since') ?? '0');
    if (!Number.isFinite(since) || since < 0) throw new SyncError('invalid_since', 400);
    const requestedLimit = Number(url.searchParams.get('limit') ?? '500');
    if (!Number.isSafeInteger(requestedLimit) || requestedLimit <= 0) {
      throw new SyncError('invalid_limit', 400);
    }
    const limit = Math.min(requestedLimit, 1000);

    // The second lock. The key is the first: a scope she has no key for is unreadable even if
    // this filter were wrong. Both, because one of them being enough is not a thing to rely on.
    const rows: PushRecord[] = [];
    let highest = since;
    let scanned = 0;
    let bytes = 0;
    let hasMore = false;
    for (const row of this.sql.exec<{
      id: string; scope: string; seq: number; updated_at: number; kind: PushRecord['kind'];
      device: string; owner_member: string; author_member: string;
      deleted: number; nonce: string; body: string;
    }>(
      'SELECT * FROM record WHERE seq > ? ORDER BY seq ASC LIMIT ?',
      since,
      limit + 1,
    )) {
      if (scanned >= limit) {
        hasMore = true;
        break;
      }
      if (!auth.scopes.includes(row.scope)) {
        highest = row.seq;
        scanned++;
        continue;
      }
      const record: PushRecord = {
        id: row.id,
        scope: row.scope,
        updatedAt: row.updated_at,
        device: row.device,
        kind: row.kind,
        ownerMemberId: row.owner_member,
        authorMemberId: row.author_member,
        deleted: row.deleted === 1,
        nonce: row.nonce,
        body: row.body,
      };
      const recordBytes = new TextEncoder().encode(JSON.stringify(record)).byteLength;
      if (rows.length > 0 && bytes + recordBytes > MAX_PULL_RECORD_BYTES) {
        hasMore = true;
        break;
      }
      rows.push(record);
      bytes += recordBytes;
      highest = row.seq;
      scanned++;
    }
    return jsonResponse({
      seq: highest,
      records: rows,
      hasMore,
      rotationClientSecret: true,
      memberId: auth.memberId,
      deviceId: auth.id,
      primaryMemberId: this.primaryMember(),
    });
  }

  private async push(request: Request): Promise<Response> {
    const auth = await this.authorise(request);
    const parsed = JSON.parse((await readTextLimited(request, MAX_SYNC_REQUEST_BYTES)) || '{}');
    const records = asRecords((parsed as Record<string, unknown>).records);

    const head = [...this.sql.exec<{ v: string }>('SELECT v FROM meta WHERE k = ?', 'seq')][0];
    const start = head ? Number(head.v) : 0;
    let seq = start;
    // Replacing a row never grows the household; only a new id counts against the cap.
    let rows = [...this.sql.exec<{ n: number }>('SELECT COUNT(*) AS n FROM record')][0]?.n ?? 0;
    const maxStamp = Date.now() + MAX_STAMP_SKEW_MS;
    const clamped: { id: string; updatedAt: number }[] = [];
    try {
      for (const record of records) {
        if (!auth.scopes.includes(record.scope)) throw new SyncError('forbidden_scope', 403);
        const reservedKind = record.id.startsWith('member:') ? 'member'
          : record.id.startsWith('txn:') ? 'transaction'
            : record.id.startsWith('category:') ? 'category'
              : record.id.startsWith('asset:') ? 'asset'
                : record.id.startsWith('note:') ? 'note'
                  : record.id.startsWith('goal:') ? 'goal'
                    : null;
        if (reservedKind && record.kind !== reservedKind) throw new SyncError('invalid_kind', 400);
        if (record.kind === 'member') {
          // Member deletion changes household membership, so it never rides the general-purpose
          // push path. Dedicated remove/leave operations validate and commit the profile
          // tombstone together with token revocation.
          const ownWrite = !record.deleted &&
            record.ownerMemberId === auth.memberId &&
            record.id === `member:${auth.memberId}`;
          if (!ownWrite) throw new SyncError('forbidden_owner', 403);
        }
        if (record.kind === 'transaction') {
          if (record.ownerMemberId !== auth.memberId || !record.id.startsWith(`txn:${auth.memberId}:`)) {
            throw new SyncError('forbidden_owner', 403);
          }
        }
        if (record.kind === 'asset') {
          // One assets record per person, written only by that person — tombstone included.
          // Unlike a member row there is no removal ceremony to speak for: a removed member's
          // assets simply stop being shown once their profile is buried.
          if (record.ownerMemberId !== auth.memberId || record.id !== `asset:${auth.memberId}`) {
            throw new SyncError('forbidden_owner', 403);
          }
        }
        if (record.kind === 'category' && !record.id.startsWith('category:')) {
          throw new SyncError('invalid_id', 400);
        }
        // A note is written by whoever is reading the row, not by whose row it is, so — exactly
        // like a category — it carries no owner check. The id namespace is all that is fenced.
        if (record.kind === 'note' && !record.id.startsWith('note:')) {
          throw new SyncError('invalid_id', 400);
        }
        // A shared budget or goal belongs to the household rather than to whoever typed it in:
        // a cap only one of them may adjust is a cap they cannot keep together. So, like a
        // category and a note, it carries no owner check — the record names its owner for the
        // sentence on the card, and the stamp settles who moved the figure last. The id
        // namespace is all that is fenced, and the tombstone rides the same path for the same
        // reason: either of them may put the figure away.
        if (record.kind === 'goal' && !record.id.startsWith('goal:')) {
          throw new SyncError('invalid_id', 400);
        }
        const existing = [...this.sql.exec<{
          updated_at: number; author_member: string; kind: PushRecord['kind']; owner_member: string;
        }>(
          'SELECT updated_at, author_member, kind, owner_member FROM record WHERE id = ?',
          record.id,
        )][0];
        if (
          existing &&
          existing.owner_member !== auth.memberId &&
          (existing.kind === 'transaction' || existing.kind === 'asset' || existing.kind === 'member')
        ) throw new SyncError('forbidden_owner', 403);
        const storedAt = Math.min(record.updatedAt, maxStamp);
        if (storedAt !== record.updatedAt) clamped.push({ id: record.id, updatedAt: storedAt });
        // Last write wins, with the member id breaking a tie so two phones that disagree at the
        // same millisecond still converge on the same answer rather than flapping.
        //
        // Ceiling, named: two people editing one transaction's category in the same second — one
        // of them wins. A CRDT would fix that and cost more than the problem is worth.
        if (
          existing &&
          (existing.updated_at > storedAt ||
            (existing.updated_at === storedAt && existing.author_member >= auth.memberId))
        ) {
          continue;
        }
        if (!existing) {
          if (rows >= MAX_RECORD_ROWS) throw new SyncError('household_full', 409);
          rows += 1;
        }
        seq += 1;
        this.sql.exec(
          'INSERT OR REPLACE INTO record ' +
            '(id, scope, seq, updated_at, device, kind, owner_member, author_member, deleted, nonce, body) ' +
            'VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)',
          record.id,
          record.scope,
          seq,
          storedAt,
          auth.id,
          record.kind,
          record.ownerMemberId,
          auth.memberId,
          record.deleted ? 1 : 0,
          record.nonce,
          record.body,
        );
      }
    } finally {
      if (seq !== start) {
        this.sql.exec('INSERT OR REPLACE INTO meta (k, v) VALUES (?, ?)', 'seq', String(seq));
      }
    }
    return jsonResponse({ seq, accepted: records.length, clamped });
  }

  private memberTombstone(value: unknown, auth: AuthorisedDevice, targetMember: string): PushRecord {
    const [record] = asRecords([value]);
    if (
      !IDENTITY.test(targetMember) ||
      record.kind !== 'member' ||
      !record.deleted ||
      record.id !== `member:${targetMember}` ||
      record.ownerMemberId !== targetMember ||
      !auth.scopes.includes(record.scope)
    ) {
      throw new SyncError('invalid_member_tombstone', 400);
    }
    return record;
  }

  /** Writes the profile tombstone and cuts off the member as one SQLite transaction. */
  private removeMember(auth: AuthorisedDevice, targetMember: string, record: PushRecord): Response {
    const head = [...this.sql.exec<{ v: string }>('SELECT v FROM meta WHERE k = ?', 'seq')][0];
    const seq = (head ? Number(head.v) : 0) + 1;
    const existing = [...this.sql.exec<{ updated_at: number }>(
      'SELECT updated_at FROM record WHERE id = ?',
      record.id,
    )][0];
    // Leaving is authoritative. It must beat a previously clamped future profile stamp because
    // the member will have no token left with which to retry a later tombstone.
    const requestedAt = Math.min(record.updatedAt, Date.now() + MAX_STAMP_SKEW_MS);
    const storedAt = Math.max(requestedAt, (existing?.updated_at ?? -1) + 1);
    this.ctx.storage.transactionSync(() => {
      this.sql.exec(
        'INSERT OR REPLACE INTO record ' +
          '(id, scope, seq, updated_at, device, kind, owner_member, author_member, deleted, nonce, body) ' +
          'VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)',
        record.id,
        record.scope,
        seq,
        storedAt,
        auth.id,
        'member',
        targetMember,
        auth.memberId,
        1,
        record.nonce,
        record.body,
      );
      this.sql.exec('INSERT OR REPLACE INTO meta (k, v) VALUES (?, ?)', 'seq', String(seq));
      this.sql.exec('DELETE FROM device WHERE member_id = ?', targetMember);
      this.sql.exec('DELETE FROM pairing');
    });
    const clamped = storedAt === record.updatedAt ? [] : [{ id: record.id, updatedAt: storedAt }];
    return jsonResponse({ revoked: targetMember, seq, clamped });
  }

  /** Removes another member, including their profile, or changes nothing. */
  private async remove(request: Request): Promise<Response> {
    const auth = await this.authorise(request);
    const body = JSON.parse((await readTextLimited(request, MAX_SYNC_REQUEST_BYTES)) || '{}') as Record<string, unknown>;
    const member = typeof body.member === 'string' ? body.member : '';
    if (!member || member === auth.memberId) throw new SyncError('invalid_request', 400);
    const primary = this.primaryMember();
    if (primary && member === primary) throw new SyncError('forbidden_primary', 403);
    const record = this.memberTombstone(body.record, auth, member);
    return this.removeMember(auth, member, record);
  }

  /** Lets the caller leave and publishes that fact in the same transaction. */
  private async leave(request: Request): Promise<Response> {
    const auth = await this.authorise(request);
    const body = JSON.parse((await readTextLimited(request, MAX_SYNC_REQUEST_BYTES)) || '{}') as Record<string, unknown>;
    const record = this.memberTombstone(body.record, auth, auth.memberId);
    return this.removeMember(auth, auth.memberId, record);
  }

  /**
   * Immediate, because this object is the only thing that decides whether a token is good. There
   * is no cache anywhere to expire and no eventually-consistent store to catch up.
   *
   * Takes a device id or a member id: the phones list people, not devices, so removing a person
   * is the request they can actually make. Revoking by member cuts every device that person has.
   */
  private async revoke(request: Request): Promise<Response> {
    const auth = await this.authorise(request);
    const body = JSON.parse((await readTextLimited(request, 4096)) || '{}') as Record<string, unknown>;
    const device = typeof body.device === 'string' ? body.device : '';
    const member = typeof body.member === 'string' ? body.member : '';
    if ((!device && !member) || (device && member)) throw new SyncError('invalid_request', 400);
    // The founder is not removable by anyone else — a family survives its members falling out.
    // Removing themselves stays their own right, which is also how anyone leaves: revoke your
    // own member and walk.
    const targetMember = member || ([...this.sql.exec<{ member_id: string }>(
      'SELECT member_id FROM device WHERE id = ?', device,
    )][0]?.member_id ?? '');
    const primary = this.primaryMember();
    if (primary && targetMember === primary && auth.memberId !== primary) {
      throw new SyncError('forbidden_primary', 403);
    }
    if (device) this.sql.exec('DELETE FROM device WHERE id = ?', device);
    if (member) this.sql.exec('DELETE FROM device WHERE member_id = ?', member);
    // Outstanding invite codes die with the revocation. A pairing row is not attributed to the
    // device that minted it, and an ex-member who photographed a QR before being evicted must
    // not be able to walk back in with it — the remaining members can mint a fresh code in one
    // tap, so sweeping them all costs nothing.
    this.sql.exec('DELETE FROM pairing');
    return jsonResponse({ revoked: device || member });
  }

  /**
   * A new secret for the calling device; the old one is gone the moment the row updates — the
   * same immediacy argument as revoke. Clients rotate opportunistically after a month, so a
   * token lifted from a backup or a bus-shoulder photo has a bounded useful life.
   */
  private async rotate(request: Request): Promise<Response> {
    const auth = await this.authorise(request);
    const body = JSON.parse((await readTextLimited(request, 4096)) || '{}') as Record<string, unknown>;
    if (body.secret !== undefined && (typeof body.secret !== 'string' || !/^[a-f0-9]{64}$/.test(body.secret))) {
      throw new SyncError('invalid_secret', 400);
    }
    const secret = typeof body.secret === 'string' ? body.secret : randomToken();
    const nextHash = await sha256Hex(secret);
    const changed = [...this.sql.exec<{ id: string }>(
      'UPDATE device SET token_hash = ?, last_seen = ? WHERE id = ? AND token_hash = ? RETURNING id',
      nextHash,
      Date.now(),
      auth.id,
      auth.tokenHash,
    )];
    if (changed.length === 0) throw new SyncError('unauthorised', 401);
    return jsonResponse({ secret });
  }
}

/**
 * Household creation is the one unauthenticated write, so it gets a token bucket per caller IP.
 *
 * Honest about what this is: friction, not a wall. The map lives in one isolate's memory, and an
 * attacker who spreads requests across colos — or simply waits for the isolate to recycle —
 * starts with a fresh bucket every time. It exists to make casual abuse boring; the per-household
 * caps inside the Durable Object are the actual bound on what any one household can cost.
 */
const CLAIM_BURST = 5;
const CLAIM_REFILL_MS = 60_000; // one new household a minute, five in a burst
const claimBuckets = new Map<string, { tokens: number; last: number }>();

function allowClaim(ip: string, now = Date.now()): boolean {
  // The keys are attacker-chosen, so the map is bounded the blunt way: a rare full reset is
  // cheaper than an LRU and errs on the side of letting people in.
  if (claimBuckets.size > 10_000) claimBuckets.clear();
  const bucket = claimBuckets.get(ip) ?? { tokens: CLAIM_BURST, last: now };
  bucket.tokens = Math.min(CLAIM_BURST, bucket.tokens + (now - bucket.last) / CLAIM_REFILL_MS);
  bucket.last = now;
  const allowed = bucket.tokens >= 1;
  if (allowed) bucket.tokens -= 1;
  claimBuckets.set(ip, bucket);
  return allowed;
}

/**
 * One policy, stated twice — here as a header on every asset, and as a `<meta>` in the PWA's
 * index.html so a copy of the page saved or served from a cache keeps it. `unsafe-inline` styles
 * are real: the page ships its stylesheet as an inline `<style>` and sizes figures with inline
 * `style="--fit:…"` attributes. Scripts stay 'self' only.
 */
const ASSET_CSP =
  "default-src 'self'; connect-src 'self'; img-src 'self' data:; " +
  "style-src 'self' 'unsafe-inline'; script-src 'self'";

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    const url = new URL(request.url);
    const path = url.pathname;

    try {
      // Served from here so the PWA is same-origin with its prices. The rates Worker sets no
      // CORS headers at all today, and proxying is both cheaper and safer than teaching a
      // public endpoint to answer cross-origin — its own edge cache still does the work.
      if (path === '/rates') {
        if (request.method !== 'GET') return textResponse('GET only\n', 405, 'GET');
        const origin = env.RATES_ORIGIN ?? DEFAULT_RATES_ORIGIN;
        const upstream = await fetch(`${origin}/rates`, {
          signal: AbortSignal.timeout(8_000),
        });
        return new Response(upstream.body, {
          status: upstream.status,
          headers: {
            'content-type': upstream.headers.get('content-type') ?? 'application/json',
            'cache-control': upstream.headers.get('cache-control') ?? 'no-store',
            'x-content-type-options': 'nosniff',
          },
        });
      }

      if (path === '/v1/claim') {
        if (request.method !== 'POST') return textResponse('POST only\n', 405, 'POST');
        // Cloudflare's edge always sets CF-Connecting-IP and strips any copy a client sends, so
        // an absent header means the request never crossed the edge — tests and local workerd —
        // and those are exactly the callers the bucket should not throttle.
        const ip = request.headers.get('cf-connecting-ip');
        if (ip && !allowClaim(ip)) return jsonResponse({ code: 'rate_limited' }, 429);
        // A new household names itself; there is no registry to collide with because the id is
        // 32 random bytes and the object is created by being addressed.
        const hid = url.searchParams.get('hid') ?? '';
        if (!/^[a-f0-9]{16,64}$/.test(hid)) return jsonResponse({ code: 'invalid_hid' }, 400);
        return await env.HOUSEHOLD.getByName(hid).fetch(
          new Request('https://household/claim', {
            method: 'POST',
            headers: request.headers,
            body: request.body,
          }),
        );
      }

      if (path.startsWith('/v1/')) {
        const token = splitToken(
          request.headers.get('authorization') ?? request.headers.get('x-muchtoman-token'),
        );
        if (!token) return jsonResponse({ code: 'unauthorised' }, 401);

        const route =
          path === '/v1/sync' && request.method === 'GET' ? 'pull'
            : path === '/v1/sync' && request.method === 'POST' ? 'push'
            : path === '/v1/invite' && request.method === 'POST' ? 'invite'
            : path === '/v1/pair' && request.method === 'POST' ? 'pair'
            : path === '/v1/identity' && request.method === 'POST' ? 'identity'
            : path === '/v1/remove' && request.method === 'POST' ? 'remove'
            : path === '/v1/leave' && request.method === 'POST' ? 'leave'
            : path === '/v1/revoke' && request.method === 'POST' ? 'revoke'
            : path === '/v1/rotate' && request.method === 'POST' ? 'rotate'
            : null;
        if (!route) return textResponse('not found\n', 404);

        const inner = new Request(`https://household/${route}${url.search}`, {
          method: request.method,
          headers: withSecret(request.headers, token.secret),
          body: request.body,
        });
        return await env.HOUSEHOLD.getByName(token.hid).fetch(inner);
      }

      // Anything else is the PWA, with its content-security-policy riding on every response.
      const asset = await env.ASSETS.fetch(request);
      const headers = new Headers(asset.headers);
      headers.set('content-security-policy', ASSET_CSP);
      return new Response(asset.body, { status: asset.status, headers });
    } catch (error) {
      console.error(JSON.stringify({ message: 'sync failed', error: errorMessage(error) }));
      return jsonResponse({ code: 'internal' }, 500);
    }
  },
};

/** The household half of the token never reaches the object; only the secret it must check. */
function withSecret(headers: Headers, secret: string): Headers {
  const copy = new Headers(headers);
  copy.delete('authorization');
  copy.delete('x-muchtoman-token');
  copy.set('x-device-secret', secret);
  return copy;
}
