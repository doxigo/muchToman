import { SELF } from 'cloudflare:test';
import { describe, expect, it } from 'vitest';

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
      headers: { authorization: `Bearer ${owner.token}` },
      body: JSON.stringify({ code }),
    });
    expect(refused.status).toBe(409);
    expect(((await refused.json()) as { code: string }).code).toBe('too_many_devices');
  });

  it('clamps a far-future stamp and returns the value it stored', async () => {
    // Without the bound, one device with a wrong clock — or a griefer — pins a record for
    // ever: nothing honest could ever outbid a stamp from the year 3000.
    const token = await claim('e5'.repeat(16), ['personal:her']);
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

});
