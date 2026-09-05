import { env, runInDurableObject, SELF } from 'cloudflare:test';
import { describe, expect, it } from 'vitest';

declare module 'cloudflare:test' {
  interface ProvidedEnv extends Env {}
}

/**
 * The claims this Worker makes about people's money, checked against the real Worker running on
 * real Durable Objects. Every one of these is an exit criterion from the plan rather than a
 * test written to match the code.
 */

const HID_A = 'a'.repeat(32);
const HID_B = 'b'.repeat(32);

interface ClaimedDevice {
  token: string;
  memberId: string;
  deviceId: string;
}

async function claimDevice(
  hid: string,
  scopes: string[],
  identity: { memberId?: string; deviceId?: string } = {},
): Promise<ClaimedDevice> {
  const res = await SELF.fetch(`https://sync.test/v1/claim?hid=${hid}`, {
    method: 'POST',
    body: JSON.stringify({ scopes, ...identity }),
  });
  expect(res.status).toBe(200);
  const result = (await res.json()) as { secret: string; memberId: string; deviceId: string };
  return { token: `${hid}.${result.secret}`, memberId: result.memberId, deviceId: result.deviceId };
}

async function claim(hid: string, scopes: string[]): Promise<string> {
  return (await claimDevice(hid, scopes)).token;
}

async function pairDevice(
  owner: string,
  memberId: string,
  deviceId: string,
): Promise<ClaimedDevice> {
  const invite = await SELF.fetch('https://sync.test/v1/invite', {
    method: 'POST',
    headers: { authorization: `Bearer ${owner}` },
    body: JSON.stringify({}),
  });
  expect(invite.status).toBe(200);
  const { code } = (await invite.json()) as { code: string };
  const paired = await SELF.fetch('https://sync.test/v1/pair', {
    method: 'POST',
    headers: { authorization: `Bearer ${owner}` },
    body: JSON.stringify({ code, memberId, deviceId }),
  });
  expect(paired.status).toBe(200);
  const { secret } = (await paired.json()) as { secret: string };
  return { token: `${owner.split('.')[0]}.${secret}`, memberId, deviceId };
}

function record(over: Partial<Record<string, unknown>> = {}) {
  return {
    id: 'r1',
    scope: 'personal:her',
    updatedAt: 1000,
    device: 'phone',
    nonce: 'nnnn',
    body: 'Y2lwaGVydGV4dA==',
    ...over,
  };
}

async function push(token: string, records: unknown[]) {
  return SELF.fetch('https://sync.test/v1/sync', {
    method: 'POST',
    headers: { authorization: `Bearer ${token}` },
    body: JSON.stringify({ records }),
  });
}

async function pull(token: string, since = 0) {
  const res = await SELF.fetch(`https://sync.test/v1/sync?since=${since}`, {
    headers: { authorization: `Bearer ${token}` },
  });
  return { status: res.status, json: (await res.json()) as { seq: number; records: any[] } };
}

function memberTombstone(memberId: string, scope: string, updatedAt = 2000) {
  return record({
    id: `member:${memberId}`,
    scope,
    kind: 'member',
    ownerMemberId: memberId,
    updatedAt,
    deleted: true,
  });
}

async function removeMember(token: string, memberId: string, scope: string) {
  return SELF.fetch('https://sync.test/v1/remove', {
    method: 'POST',
    headers: { authorization: `Bearer ${token}` },
    body: JSON.stringify({ member: memberId, record: memberTombstone(memberId, scope) }),
  });
}

async function leave(token: string, memberId: string, scope: string) {
  return SELF.fetch('https://sync.test/v1/leave', {
    method: 'POST',
    headers: { authorization: `Bearer ${token}` },
    body: JSON.stringify({ record: memberTombstone(memberId, scope) }),
  });
}

describe('a household', () => {
  it('is claimed once and never again', async () => {
    const hid = '1'.repeat(32);
    await claim(hid, ['personal:her']);
    const second = await SELF.fetch(`https://sync.test/v1/claim?hid=${hid}`, {
      method: 'POST',
      body: JSON.stringify({ scopes: ['personal:her'] }),
    });
    expect(second.status).toBe(409);
  });

  it('refuses a request with no token, a malformed one, or a token it never issued', async () => {
    expect((await SELF.fetch('https://sync.test/v1/sync')).status).toBe(401);
    const res = await SELF.fetch('https://sync.test/v1/sync', {
      headers: { authorization: 'Bearer not-a-token' },
    });
    expect(res.status).toBe(401);
    const wellFormed = await SELF.fetch('https://sync.test/v1/sync', {
      headers: { authorization: `Bearer ${'2'.repeat(32)}.${'f'.repeat(64)}` },
    });
    expect(wellFormed.status).toBe(401);
  });
});

describe('syncing', () => {
  it('stores and returns a record without ever reading it', async () => {
    const token = await claim('3'.repeat(32), ['personal:her']);
    expect((await push(token, [record()])).status).toBe(200);
    const { json } = await pull(token);
    expect(json.records).toHaveLength(1);
    expect(json.records[0].body).toBe('Y2lwaGVydGV4dA==');
    expect(json.seq).toBeGreaterThan(0);
  });

  it('is idempotent, so a retried push cannot duplicate a transaction', async () => {
    // The plan's exit criterion, and it holds without an idempotency-key table anywhere: the
    // client generates the id, so the retry is an upsert of the same row.
    const token = await claim('4'.repeat(32), ['personal:her']);
    await push(token, [record()]);
    await push(token, [record()]);
    await push(token, [record()]);
    const { json } = await pull(token);
    expect(json.records).toHaveLength(1);
  });

  it('keeps the newer write and ignores spoofed device attribution', async () => {
    const token = await claim('5'.repeat(32), ['personal:her']);
    await push(token, [record({ updatedAt: 2000, body: 'bmV3ZXI=' })]);
    await push(token, [record({ updatedAt: 1000, body: 'b2xkZXI=' })]);
    let { json } = await pull(token);
    expect(json.records[0].body).toBe('bmV3ZXI=');

    // The request body does not decide who wrote a record. Authenticated identity does.
    await push(token, [record({ updatedAt: 2000, device: 'zzz', body: 'd2lucw==' })]);
    ({ json } = await pull(token));
    expect(json.records[0].body).toBe('bmV3ZXI=');
    await push(token, [record({ updatedAt: 2000, device: 'aaa', body: 'bG9zZXM=' })]);
    ({ json } = await pull(token));
    expect(json.records[0].body).toBe('bmV3ZXI=');
  });

  it('attributes a transaction to the authenticated person and device', async () => {
    const memberId = '51'.repeat(16);
    const deviceId = '52'.repeat(16);
    const claimed = await claimDevice('53'.repeat(16), ['family:53'], { memberId, deviceId });
    const transaction = record({
      id: `txn:${memberId}:736f75726365`,
      scope: 'family:53',
      kind: 'transaction',
      ownerMemberId: memberId,
      device: 'spoofed-device',
    });

    expect((await push(claimed.token, [transaction])).status).toBe(200);
    const { json } = await pull(claimed.token);
    expect(json.records[0]).toMatchObject({
      device: deviceId,
      ownerMemberId: memberId,
      authorMemberId: memberId,
    });
  });

  it('does not let a legacy envelope bypass protected record attribution', async () => {
    const claimed = await claimDevice('65'.repeat(16), ['family:65'], {
      memberId: '66'.repeat(16),
      deviceId: '67'.repeat(16),
    });
    const disguised = record({
      id: `txn:${'68'.repeat(16)}:736f75726365`,
      scope: 'family:65',
      kind: 'legacy',
      ownerMemberId: '',
    });
    expect((await push(claimed.token, [disguised])).status).toBe(400);
  });

  it('hands back only what has changed since the cursor', async () => {
    const token = await claim('6'.repeat(32), ['personal:her']);
    await push(token, [record({ id: 'r1' })]);
    const first = await pull(token);
    await push(token, [record({ id: 'r2' })]);
    const second = await pull(token, first.json.seq);
    expect(second.json.records.map((r) => r.id)).toEqual(['r2']);
  });

  it('carries a delete as a tombstone rather than losing the row', async () => {
    const token = await claim('7'.repeat(32), ['personal:her']);
    await push(token, [record()]);
    await push(token, [record({ updatedAt: 2000, deleted: true })]);
    const { json } = await pull(token);
    expect(json.records).toHaveLength(1);
    expect(json.records[0].deleted).toBe(true);
  });

  it('refuses a record for a scope the device has no business writing', async () => {
    const token = await claim('8'.repeat(32), ['personal:her']);
    const res = await push(token, [record({ scope: 'personal:someone-else' })]);
    expect(res.status).toBe(403);
  });

  it('persists the cursor when a later record rejects the batch', async () => {
    const token = await claim('81'.repeat(16), ['personal:her']);
    const rejected = await push(token, [
      record({ id: 'before-error' }),
      record({ id: 'forbidden', scope: 'personal:someone-else' }),
    ]);
    expect(rejected.status).toBe(403);

    const first = await pull(token);
    expect(first.json.records.map((r) => r.id)).toEqual(['before-error']);
    expect(first.json.seq).toBeGreaterThan(0);

    await push(token, [record({ id: 'after-error' })]);
    const second = await pull(token, first.json.seq);
    expect(second.json.records.map((r) => r.id)).toEqual(['after-error']);
  });

  it('accepts a real transaction reference as an id', async () => {
    // "s:" + sha256 + ":0" is 68 characters, and an earlier 64-character cap rejected every
    // SMS-derived transaction there is — with a 400 that said only "invalid_id".
    const token = await claim('a1'.repeat(16), ['personal:her']);
    const ref = `s:${'ab'.repeat(32)}:0`;
    expect(ref.length).toBe(68);
    expect((await push(token, [record({ id: ref })])).status).toBe(200);
    const { json } = await pull(token);
    expect(json.records[0].id).toBe(ref);
  });

  it('caps what one request may carry', async () => {
    const token = await claim('9'.repeat(32), ['personal:her']);
    const many = Array.from({ length: 501 }, (_, i) => record({ id: `r${i}` }));
    expect((await push(token, many)).status).toBe(413);
  });
});

describe('privacy between households and members', () => {
  it('cannot be addressed with another household\'s token', async () => {
    const a = await claim(HID_A, ['personal:her']);
    await claim(HID_B, ['personal:him']);
    await push(a, [record({ body: 'aGVycw==' })]);

    // Same secret, another household's id in front of it. There is no query in the object that
    // could reach across; the object it lands in has simply never heard of this device.
    const forged = `${HID_B}.${a.split('.')[1]}`;
    const res = await SELF.fetch('https://sync.test/v1/sync', {
      headers: { authorization: `Bearer ${forged}` },
    });
    expect(res.status).toBe(401);
  });

  it('gives a member only the scopes they hold', async () => {
    // The family owner writes to two scopes; the member is invited into one. The key is the
    // real lock — the member has no key for the private scope — and this is the second one.
    const owner = await claim('c'.repeat(32), ['personal:owner', 'family:c']);
    await push(owner, [record({ id: 'p1', scope: 'personal:owner', body: 'cHJpdmF0ZQ==' })]);
    await push(owner, [record({ id: 'f1', scope: 'family:c', body: 'c2hhcmVk' })]);

    const invite = await SELF.fetch('https://sync.test/v1/invite', {
      method: 'POST',
      headers: { authorization: `Bearer ${owner}` },
      body: JSON.stringify({ scopes: ['family:c'] }),
    });
    expect(invite.status).toBe(200);
    const { code } = (await invite.json()) as { code: string };

    const paired = await SELF.fetch('https://sync.test/v1/pair', {
      method: 'POST',
      headers: { authorization: `Bearer ${owner}` },
      body: JSON.stringify({ code }),
    });
    expect(paired.status).toBe(200);
    const member = `${'c'.repeat(32)}.${((await paired.json()) as { secret: string }).secret}`;

    const { json } = await pull(member);
    expect(json.records.map((r) => r.id)).toEqual(['f1']);
    expect(json.records.map((r) => r.body)).not.toContain('cHJpdmF0ZQ==');
  });

  it('cannot widen its own scopes at the invite', async () => {
    const owner = await claim('d'.repeat(32), ['family:d']);
    const invite = await SELF.fetch('https://sync.test/v1/invite', {
      method: 'POST',
      headers: { authorization: `Bearer ${owner}` },
      body: JSON.stringify({ scopes: ['family:d', 'personal:someone'] }),
    });
    const { code } = (await invite.json()) as { code: string };
    const paired = await SELF.fetch('https://sync.test/v1/pair', {
      method: 'POST',
      headers: { authorization: `Bearer ${owner}` },
      body: JSON.stringify({ code }),
    });
    const { scopes } = (await paired.json()) as { scopes: string[] };
    expect(scopes).toEqual(['family:d']);
  });

  it('spends a pairing code exactly once', async () => {
    const owner = await claim('e'.repeat(32), ['family:e']);
    const invite = await SELF.fetch('https://sync.test/v1/invite', {
      method: 'POST',
      headers: { authorization: `Bearer ${owner}` },
      body: JSON.stringify({}),
    });
    const { code } = (await invite.json()) as { code: string };
    const body = JSON.stringify({ code });
    const headers = { authorization: `Bearer ${owner}` };
    expect((await SELF.fetch('https://sync.test/v1/pair', { method: 'POST', headers, body })).status).toBe(200);
    expect((await SELF.fetch('https://sync.test/v1/pair', { method: 'POST', headers, body })).status).toBe(403);
  });

  it('does not let one member replace another member transaction', async () => {
    const hid = '54'.repeat(16);
    const scope = 'family:54';
    const ownerMember = '55'.repeat(16);
    const owner = await claimDevice(hid, [scope], {
      memberId: ownerMember,
      deviceId: '56'.repeat(16),
    });
    const other = await pairDevice(owner.token, '57'.repeat(16), '58'.repeat(16));
    const id = `txn:${ownerMember}:736f75726365`;
    expect((await push(owner.token, [record({
      id,
      scope,
      kind: 'transaction',
      ownerMemberId: ownerMember,
    })])).status).toBe(200);

    const overwrite = await push(other.token, [record({
      id,
      scope,
      kind: 'transaction',
      ownerMemberId: ownerMember,
      updatedAt: 2000,
    })]);
    expect(overwrite.status).toBe(403);

    const category = await push(other.token, [record({
      id: `category:${'59'.repeat(32)}`,
      scope,
      kind: 'category',
      ownerMemberId: ownerMember,
    })]);
    expect(category.status).toBe(200);
  });

  it('locks an established token to its person and device identity', async () => {
    const memberId = '61'.repeat(16);
    const deviceId = '62'.repeat(16);
    const claimed = await claimDevice('63'.repeat(16), ['family:63'], { memberId, deviceId });
    const same = await SELF.fetch('https://sync.test/v1/identity', {
      method: 'POST',
      headers: { authorization: `Bearer ${claimed.token}` },
      body: JSON.stringify({ memberId, deviceId }),
    });
    expect(same.status).toBe(200);

    const rebound = await SELF.fetch('https://sync.test/v1/identity', {
      method: 'POST',
      headers: { authorization: `Bearer ${claimed.token}` },
      body: JSON.stringify({ memberId: '64'.repeat(16), deviceId }),
    });
    expect(rebound.status).toBe(409);
  });
});

describe('revocation', () => {
  it('takes effect on the very next request', async () => {
    // Why there is no KV anywhere in this design: the object is the only authority on a token,
    // so there is no cache to expire and no eventual consistency to wait out.
    const owner = await claimDevice('f'.repeat(32), ['family:f'], {
      memberId: 'f1'.repeat(16),
      deviceId: 'f2'.repeat(16),
    });
    const member = await pairDevice(owner.token, 'f3'.repeat(16), 'f4'.repeat(16));
    expect((await pull(member.token)).status).toBe(200);

    const revoke = await SELF.fetch('https://sync.test/v1/revoke', {
      method: 'POST',
      headers: { authorization: `Bearer ${owner.token}` },
      body: JSON.stringify({ device: member.deviceId }),
    });
    expect(revoke.status).toBe(200);
    expect((await pull(member.token)).status).toBe(401);
  });

  it('revokes a whole member and spends every outstanding pairing code with them', async () => {
    const owner = await claimDevice('cd'.repeat(16), ['family:cd'], {
      memberId: 'ce'.repeat(16),
      deviceId: 'cf'.repeat(16),
    });
    const member = await pairDevice(owner.token, 'd1'.repeat(16), 'd2'.repeat(16));
    // An invite is sitting on a screen — or in an ex-member's photo roll — when the revocation
    // happens. It must die with them: a code is not attributed to whoever minted it.
    const invite = await SELF.fetch('https://sync.test/v1/invite', {
      method: 'POST',
      headers: { authorization: `Bearer ${owner.token}` },
      body: JSON.stringify({}),
    });
    const { code } = (await invite.json()) as { code: string };

    const revoke = await SELF.fetch('https://sync.test/v1/revoke', {
      method: 'POST',
      headers: { authorization: `Bearer ${owner.token}` },
      body: JSON.stringify({ member: member.memberId }),
    });
    expect(revoke.status).toBe(200);
    expect((await pull(member.token)).status).toBe(401);

    const paired = await SELF.fetch('https://sync.test/v1/pair', {
      method: 'POST',
      headers: { authorization: `Bearer ${owner.token}` },
      body: JSON.stringify({ code }),
    });
    expect(paired.status).toBe(403);
  });

  it('removes a member and their profile in one operation', async () => {
    const hid = 'd3'.repeat(16);
    const scope = 'family:d3';
    const owner = await claimDevice(hid, [scope], {
      memberId: 'd4'.repeat(16),
      deviceId: 'd5'.repeat(16),
    });
    const removed = await pairDevice(owner.token, 'd6'.repeat(16), 'd7'.repeat(16));
    await push(removed.token, [
      record({
        id: `member:${removed.memberId}`,
        scope,
        kind: 'member',
        ownerMemberId: removed.memberId,
      }),
    ]);

    const tombstone = memberTombstone(removed.memberId, scope);
    expect((await push(owner.token, [tombstone])).status).toBe(403);
    expect((await removeMember(owner.token, removed.memberId, scope)).status).toBe(200);
    expect((await pull(removed.token)).status).toBe(401);

    const { json } = await pull(owner.token);
    const row = json.records.find((r) => r.id === `member:${removed.memberId}`);
    expect(row.deleted).toBe(true);
  });

  it('does not let a member tombstone the founder through sync', async () => {
    const scope = 'family:de';
    const founder = await claimDevice('de'.repeat(16), [scope], {
      memberId: 'df'.repeat(16),
      deviceId: 'e0'.repeat(16),
    });
    const other = await pairDevice(founder.token, 'e2'.repeat(16), 'e3'.repeat(16));
    await push(founder.token, [
      record({ id: `member:${founder.memberId}`, scope, kind: 'member', ownerMemberId: founder.memberId }),
    ]);

    expect((await push(other.token, [memberTombstone(founder.memberId, scope)])).status).toBe(403);
    expect((await removeMember(other.token, founder.memberId, scope)).status).toBe(403);
    expect((await pull(founder.token)).status).toBe(200);
  });

  it('does not extend the tombstone exception to another member\'s transactions', async () => {
    const hid = 'd8'.repeat(16);
    const scope = 'family:d8';
    const ownerMember = 'd9'.repeat(16);
    const owner = await claimDevice(hid, [scope], { memberId: ownerMember, deviceId: 'db'.repeat(16) });
    const other = await pairDevice(owner.token, 'da'.repeat(16), 'dc'.repeat(16));
    const id = `txn:${ownerMember}:736f75726365`;
    await push(owner.token, [record({ id, scope, kind: 'transaction', ownerMemberId: ownerMember })]);

    const buried = await push(other.token, [
      record({ id, scope, kind: 'transaction', ownerMemberId: ownerMember, updatedAt: 2000, deleted: true }),
    ]);
    expect(buried.status).toBe(403);
  });

  it('never lets another member remove the founder, while leaving stays anyone\'s right', async () => {
    const founder = await claimDevice('b1'.repeat(16), ['family:b1'], {
      memberId: 'b2'.repeat(16),
      deviceId: 'b3'.repeat(16),
    });
    const member = await pairDevice(founder.token, 'b4'.repeat(16), 'b5'.repeat(16));

    const revoke = (token: string, body: Record<string, string>) =>
      SELF.fetch('https://sync.test/v1/revoke', {
        method: 'POST',
        headers: { authorization: `Bearer ${token}` },
        body: JSON.stringify(body),
      });

    // Neither the founder's member id nor their device id is a door.
    expect((await revoke(member.token, { member: founder.memberId })).status).toBe(403);
    expect((await revoke(member.token, { device: founder.deviceId })).status).toBe(403);
    expect((await pull(founder.token)).status).toBe(200);

    // Leaving writes the profile tombstone and revokes the caller together.
    expect((await leave(member.token, member.memberId, 'family:b1')).status).toBe(200);
    expect((await pull(member.token)).status).toBe(401);
    const founderView = await pull(founder.token);
    expect(founderView.json.records.find((r) => r.id === `member:${member.memberId}`).deleted).toBe(true);
  });

  it('rejects ambiguous revoke selectors before deleting either target', async () => {
    const founder = await claimDevice('e4'.repeat(16), ['family:e4'], {
      memberId: 'e5'.repeat(16),
      deviceId: 'e6'.repeat(16),
    });
    const member = await pairDevice(founder.token, 'e7'.repeat(16), 'e8'.repeat(16));
    const ambiguous = await SELF.fetch('https://sync.test/v1/revoke', {
      method: 'POST',
      headers: { authorization: `Bearer ${member.token}` },
      body: JSON.stringify({ device: founder.deviceId, member: member.memberId }),
    });
    expect(ambiguous.status).toBe(400);
    expect((await pull(founder.token)).status).toBe(200);
    expect((await pull(member.token)).status).toBe(200);
  });

  it('validates a removal tombstone before revoking the target', async () => {
    const scope = 'family:e9';
    const founder = await claimDevice('e9'.repeat(16), [scope], {
      memberId: 'ea'.repeat(16),
      deviceId: 'eb'.repeat(16),
    });
    const member = await pairDevice(founder.token, 'ec'.repeat(16), 'ed'.repeat(16));
    const malformed = await SELF.fetch('https://sync.test/v1/remove', {
      method: 'POST',
      headers: { authorization: `Bearer ${founder.token}` },
      body: JSON.stringify({
        member: member.memberId,
        record: memberTombstone(founder.memberId, scope),
      }),
    });
    expect(malformed.status).toBe(400);
    expect((await pull(member.token)).status).toBe(200);
  });

  it('names the founder on every pull', async () => {
    const founder = await claimDevice('b6'.repeat(16), ['family:b6'], {
      memberId: 'b7'.repeat(16),
      deviceId: 'b8'.repeat(16),
    });
    const member = await pairDevice(founder.token, 'b9'.repeat(16), 'ba'.repeat(16));
    const seen = (await pull(member.token)).json as { primaryMemberId?: string };
    expect(seen.primaryMemberId).toBe(founder.memberId);
  });
});

describe('shared assets', () => {
  it('keeps a member\'s asset record theirs alone, tombstone included', async () => {
    const hid = 'c1'.repeat(16);
    const scope = 'family:c1';
    const owner = await claimDevice(hid, [scope], { memberId: 'bc'.repeat(16), deviceId: 'bd'.repeat(16) });
    const other = await pairDevice(owner.token, 'be'.repeat(16), 'bf'.repeat(16));

    const own = record({ id: `asset:${owner.memberId}`, scope, kind: 'asset', ownerMemberId: owner.memberId });
    expect((await push(owner.token, [own])).status).toBe(200);

    // Nobody else writes it, rewrites it, or buries it.
    expect((await push(other.token, [{ ...own, updatedAt: 2000 }])).status).toBe(403);
    expect((await push(other.token, [{ ...own, updatedAt: 2000, deleted: true }])).status).toBe(403);
    // And the id prefix is reserved: it cannot ride in under another kind.
    expect((await push(other.token, [record({ id: `asset:${owner.memberId}`, scope })])).status).toBe(400);

    // Their own record and their own tombstone are theirs.
    const theirs = record({ id: `asset:${other.memberId}`, scope, kind: 'asset', ownerMemberId: other.memberId });
    expect((await push(other.token, [theirs])).status).toBe(200);
    expect((await push(owner.token, [{ ...own, updatedAt: 3000, deleted: true }])).status).toBe(200);
    const { json } = await pull(other.token);
    expect(json.records.find((r) => r.id === `asset:${owner.memberId}`).deleted).toBe(true);
  });
});

describe('token rotation', () => {
  it('replaces the secret and kills the old one immediately', async () => {
    const token = await claim('ab'.repeat(16), ['personal:her']);
    await push(token, [record()]);

    const res = await SELF.fetch('https://sync.test/v1/rotate', {
      method: 'POST',
      headers: { authorization: `Bearer ${token}` },
    });
    expect(res.status).toBe(200);
    const { secret } = (await res.json()) as { secret: string };
    const fresh = `${token.split('.')[0]}.${secret}`;

    // Same immediacy argument as revocation: the object is the only authority on the secret.
    expect((await pull(token)).status).toBe(401);
    const rotated = await pull(fresh);
    expect(rotated.status).toBe(200);
    expect(rotated.json.records).toHaveLength(1);
  });
});

describe('abuse resistance', () => {
  it('caps how many record rows a household may hold', async () => {
    const hid = 'e1'.repeat(16);
    const token = await claim(hid, ['personal:her']);
    // 120_000 is the production cap; the test seeds to two under it directly in the object's
    // SQLite, because pushing 120k records through HTTP would test patience rather than the cap.
    const capMinusTwo = 120_000 - 2;
    const stub = env.HOUSEHOLD.getByName(hid);
    await runInDurableObject(stub, async (_instance, state) => {
      state.storage.sql.exec(
        `WITH RECURSIVE n(i) AS (SELECT 1 UNION ALL SELECT i + 1 FROM n WHERE i < ${capMinusTwo})
         INSERT INTO record (id, scope, seq, updated_at, device, kind, owner_member, author_member, deleted, nonce, body)
         SELECT 'seed-' || i, 'personal:her', i, 1, 'seed', 'legacy', '', '', 0, 'n', 'b' FROM n`,
      );
    });

    expect((await push(token, [record({ id: 'fits-1' }), record({ id: 'fits-2' })])).status).toBe(200);
    const refused = await push(token, [record({ id: 'one-too-many' })]);
    expect(refused.status).toBe(409);
    expect(((await refused.json()) as { code: string }).code).toBe('household_full');
    // Replacing an existing row is not growth, so edits keep working at the cap.
    expect((await push(token, [record({ id: 'fits-1', updatedAt: 2000 })])).status).toBe(200);
  });

  it('caps a household at sixteen devices', async () => {
    const owner = await claimDevice('b2'.repeat(16), ['family:b2'], {
      memberId: 'b3'.repeat(16),
      deviceId: 'b4'.repeat(16),
    });
    for (let i = 0; i < 15; i++) {
      await pairDevice(
        owner.token,
        (0xa0 + i).toString(16).repeat(16),
        (0xc0 + i).toString(16).repeat(16),
      );
    }
    const invite = await SELF.fetch('https://sync.test/v1/invite', {
      method: 'POST',
      headers: { authorization: `Bearer ${owner.token}` },
      body: JSON.stringify({}),
    });
    const { code } = (await invite.json()) as { code: string };
    const refused = await SELF.fetch('https://sync.test/v1/pair', {
      method: 'POST',
      headers: { authorization: `Bearer ${owner.token}` },
      body: JSON.stringify({ code }),
    });
    expect(refused.status).toBe(409);
    expect(((await refused.json()) as { code: string }).code).toBe('too_many_devices');
  });

  it('clamps a far-future stamp and returns the value it stored', async () => {
    // Without the bound, one device with a wrong clock — or a griefer — pins a record for
    // ever: nothing honest could ever outbid a stamp from the year 3000.
    const token = await claim('b5'.repeat(16), ['personal:her']);
    const farFuture = Date.now() + 365 * 24 * 60 * 60 * 1000;
    const res = await push(token, [record({ updatedAt: farFuture })]);
    expect(res.status).toBe(200);
    const ack = (await res.json()) as { clamped: Array<{ id: string; updatedAt: number }> };
    expect(ack.clamped).toHaveLength(1);
    expect(ack.clamped[0].id).toBe('r1');
    expect(ack.clamped[0].updatedAt).toBeGreaterThan(Date.now());
    expect(ack.clamped[0].updatedAt).toBeLessThanOrEqual(Date.now() + 24 * 60 * 60 * 1000 + 5_000);

    const { json } = await pull(token);
    expect(json.records[0].updatedAt).toBe(ack.clamped[0].updatedAt);

    // An honest stamp from within the skew window is left alone.
    const near = await push(token, [record({ id: 'r2', updatedAt: Date.now() + 60_000 })]);
    expect(((await near.json()) as { clamped: unknown[] }).clamped).toHaveLength(0);
  });

  it('rate limits household creation per IP, as friction', async () => {
    const claimAs = (ip: string, hid: string) =>
      SELF.fetch(`https://sync.test/v1/claim?hid=${hid}`, {
        method: 'POST',
        headers: { 'cf-connecting-ip': ip },
        body: JSON.stringify({ scopes: ['personal:her'] }),
      });
    for (let i = 0; i < 5; i++) {
      expect((await claimAs('198.51.100.7', `f${i}`.repeat(16))).status).toBe(200);
    }
    const throttled = await claimAs('198.51.100.7', 'f6'.repeat(16));
    expect(throttled.status).toBe(429);
    expect(((await throttled.json()) as { code: string }).code).toBe('rate_limited');
    // Another address is another bucket; the neighbour is not paying for the flood.
    expect((await claimAs('198.51.100.8', 'f7'.repeat(16))).status).toBe(200);
  });
});

describe('the PWA it serves', () => {
  it('rides a content-security-policy header on asset responses', async () => {
    const res = await SELF.fetch('https://sync.test/');
    expect(res.headers.get('content-security-policy')).toContain("default-src 'self'");
    expect(res.headers.get('content-security-policy')).toContain("script-src 'self'");
  });
});
describe('bounded resumable pulls', () => {
  it('rejects invalid page limits and caps oversized integer limits', async () => {
    const token = await claim('e101'.repeat(8), ['personal:her']);
    for (const limit of ['-1', '0', '1.5', 'NaN', 'Infinity', '']) {
      const response = await SELF.fetch(`https://sync.test/v1/sync?limit=${limit}`, {
        headers: { authorization: `Bearer ${token}` },
      });
      expect(response.status).toBe(400);
      expect(await response.json()).toEqual({ code: 'invalid_limit' });
    }
    const response = await SELF.fetch('https://sync.test/v1/sync?limit=2000', {
      headers: { authorization: `Bearer ${token}` },
    });
    expect(response.status).toBe(200);
  });

  it('reports remaining pages even when the current page has no visible rows', async () => {
    const owner = await claim('e102'.repeat(8), ['personal:her', 'family:home']);
    await push(owner, [record({ id: 'private' }), record({ id: 'shared', scope: 'family:home' })]);
    const invitation = await SELF.fetch('https://sync.test/v1/invite', {
      method: 'POST', headers: { authorization: `Bearer ${owner}` },
      body: JSON.stringify({ scopes: ['family:home'] }),
    });
    const { code } = await invitation.json() as { code: string };
    const pairing = await SELF.fetch('https://sync.test/v1/pair', {
      method: 'POST', headers: { authorization: `Bearer ${owner}` },
      body: JSON.stringify({ code }),
    });
    const { secret } = await pairing.json() as { secret: string };
    const token = `${owner.split('.')[0]}.${secret}`;
    const first = await SELF.fetch('https://sync.test/v1/sync?limit=1', {
      headers: { authorization: `Bearer ${token}` },
    });
    const page = await first.json() as { seq: number; hasMore: boolean; records: unknown[] };
    expect(page).toMatchObject({ seq: 1, hasMore: true, records: [] });
    const next = await SELF.fetch(`https://sync.test/v1/sync?since=${page.seq}&limit=1`, {
      headers: { authorization: `Bearer ${token}` },
    });
    expect(await next.json()).toMatchObject({ seq: 2, hasMore: false, records: [{ id: 'shared' }] });
  });

  it('stays below the Android response budget and resumes without skipping large rows', async () => {
    const token = await claim('e103'.repeat(8), ['personal:her']);
    expect((await push(token, Array.from({ length: 20 }, (_, i) => record({
      id: `large-${i}`, body: 'a'.repeat(64 * 1024),
    })))).status).toBe(200);
    const first = await SELF.fetch('https://sync.test/v1/sync?limit=1000', {
      headers: { authorization: `Bearer ${token}` },
    });
    const text = await first.text();
    expect(new TextEncoder().encode(text).length).toBeLessThan(1024 * 1024);
    const page = JSON.parse(text) as { seq: number; hasMore: boolean; records: { id: string }[] };
    expect(page.hasMore).toBe(true);
    const second = await SELF.fetch(`https://sync.test/v1/sync?since=${page.seq}&limit=1000`, {
      headers: { authorization: `Bearer ${token}` },
    });
    const remainder = await second.json() as { hasMore: boolean; records: { id: string }[] };
    expect(remainder.hasMore).toBe(false);
    expect(new Set([...page.records, ...remainder.records].map(r => r.id)).size).toBe(20);
  });
});

describe('shared conflict ordering', () => {
  it('uses member ordering even when device ordering is reversed, regardless of arrival order', async () => {
    const first = await claimDevice('e104'.repeat(8), ['family:home'], {
      memberId: '1'.repeat(32), deviceId: 'f'.repeat(32),
    });
    const second = await pairDevice(first.token, 'f'.repeat(32), '1'.repeat(32));
    for (const kind of ['category', 'note', 'goal']) {
      for (const reversed of [false, true]) {
        const id = `${kind}:${reversed}`;
        const low = record({ id, scope: 'family:home', kind, body: 'low-member' });
        const high = record({ id, scope: 'family:home', kind, body: 'high-member' });
        const writes = reversed
          ? [[second.token, high], [first.token, low]] as const
          : [[first.token, low], [second.token, high]] as const;
        for (const [token, row] of writes) expect((await push(token, [row])).status).toBe(200);
      }
    }
    const result = await pull(first.token);
    expect(result.json.records).toHaveLength(6);
    for (const row of result.json.records) {
      expect(row.body).toBe('high-member');
      expect(row.authorMemberId).toBe(second.memberId);
    }
  });
});

describe('recoverable token rotation', () => {
  it('allows only one replacement when the old credential rotates concurrently', async () => {
    const token = await claim('e107'.repeat(8), ['personal:her']);
    const secrets = ['a'.repeat(64), 'b'.repeat(64)];
    const results = await Promise.all(secrets.map(secret => SELF.fetch('https://sync.test/v1/rotate', {
      method: 'POST', headers: { authorization: `Bearer ${token}` }, body: JSON.stringify({ secret }),
    })));
    expect(results.map(response => response.status).sort()).toEqual([200, 401]);
    const accepted = results.findIndex(response => response.status === 200);
    const replacement = `${token.split('.')[0]}.${secrets[accepted]}`;
    expect((await pull(replacement)).status).toBe(200);
    expect((await pull(token)).status).toBe(401);
  });

  it('retries the persisted replacement after a lost response without retaining old-token access', async () => {
    const device = await claimDevice('e105'.repeat(8), ['personal:her']);
    const token = device.token;
    const secret = 'd'.repeat(64);
    const rotate = (auth: string) => SELF.fetch('https://sync.test/v1/rotate', {
      method: 'POST', headers: { authorization: `Bearer ${auth}` }, body: JSON.stringify({ secret }),
    });
    expect((await rotate(token)).status).toBe(200);
    expect((await rotate(token)).status).toBe(401);
    const replacement = `${token.split('.')[0]}.${secret}`;
    expect((await rotate(replacement)).status).toBe(200);
    expect((await rotate(replacement)).status).toBe(200);
    expect((await pull(token)).status).toBe(401);
    expect((await pull(replacement)).status).toBe(200);
    const revoked = await SELF.fetch('https://sync.test/v1/revoke', {
      method: 'POST', headers: { authorization: `Bearer ${replacement}` },
      body: JSON.stringify({ member: device.memberId }),
    });
    expect(revoked.status).toBe(200);
    expect((await rotate(token)).status).toBe(401);
    expect((await rotate(replacement)).status).toBe(401);
  });

  it('rejects malformed replacements without invalidating the working credential', async () => {
    const token = await claim('e106'.repeat(8), ['personal:her']);
    for (const secret of ['', 'short', 42, 'g'.repeat(64)]) {
      const response = await SELF.fetch('https://sync.test/v1/rotate', {
        method: 'POST', headers: { authorization: `Bearer ${token}` }, body: JSON.stringify({ secret }),
      });
      expect(response.status).toBe(400);
    }
    expect((await pull(token)).status).toBe(200);
  });
});
