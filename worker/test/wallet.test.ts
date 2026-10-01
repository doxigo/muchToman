import { afterEach, describe, expect, it, vi } from 'vitest';
import worker, { isTronAddress } from '../src/index';

/**
 * /wallet-balance against canned chain answers. The amount the phone gets is persisted over
 * whatever it held, so the line these tests hold is: a zero only when the chain said zero —
 * an error body is `unavailable` (the phone keeps the last amount), never a confident 0.
 */

const TRON = 'TAcN9gFvXZAxsWQQzWWbydBmscBHnEi5nZ'; // base58check-valid, 0x41 + 20 × 0x07
const USDT_TRC20 = 'TR7NHqjeKQxGTCi8q8ZY4pL8otSzgjLj6t';
const BTC = 'bc1qar0srrr7xfkvy5l643lydnw9re59gtzzwf5mdq';

let ip = 0;

async function lookup(routes: Record<string, () => Response>, body: Record<string, string>) {
  vi.stubGlobal('fetch', async (input: RequestInfo | URL) => {
    const url = input instanceof Request ? input.url : String(input);
    const route = Object.entries(routes).find(([prefix]) => url.startsWith(prefix))?.[1];
    if (!route) throw new Error('unexpected fetch: ' + url);
    return route();
  });
  const res = await worker.fetch(
    new Request('https://rates.muchtoman.com/wallet-balance', {
      method: 'POST',
      // A fresh address each time, so the per-IP limiter never decides a test.
      headers: { 'content-type': 'application/json', 'cf-connecting-ip': `10.0.0.${++ip}` },
      body: JSON.stringify(body),
    }),
    { ASSETS: {} as Fetcher },
    { waitUntil: () => {} } as unknown as ExecutionContext,
  );
  return { status: res.status, body: (await res.json()) as { amount?: number; code?: string } };
}

afterEach(() => vi.unstubAllGlobals());

describe('Tron', () => {
  const account = (payload: unknown) => ({
    'https://api.trongrid.io/wallet/getaccount': () => Response.json(payload),
  });

  it('checks the base58check sum, so a one-letter typo is a wrong address, not an empty one', async () => {
    expect(await isTronAddress(TRON)).toBe(true);
    expect(await isTronAddress(USDT_TRC20)).toBe(true);
    // No route: a typo must be refused before anything is asked of the chain.
    expect(await lookup({}, { network: 'tron', address: `${TRON.slice(0, -1)}Y` }))
      .toEqual({ status: 400, body: { code: 'invalid_address' } });
    expect(await lookup({}, { network: 'tron', address: TRON, contract: `${USDT_TRC20.slice(0, -1)}u` }))
      .toEqual({ status: 400, body: { code: 'invalid_contract' } });
  });

  it('reads TRX, and a zero only for an account the chain says is empty', async () => {
    expect((await lookup(account({ address: TRON, balance: 2_500_000 }), { network: 'tron', address: TRON })).body.amount).toBe(2.5);
    // never activated: getaccount answers {}
    expect((await lookup(account({}), { network: 'tron', address: TRON })).body.amount).toBe(0);
    // activated, holding no TRX: protobuf drops the zero balance
    expect((await lookup(account({ address: TRON, create_time: 1 }), { network: 'tron', address: TRON })).body.amount).toBe(0);
  });

  it('answers unavailable, not 0, for an error body trongrid sends with a 200', async () => {
    const res = await lookup(account({ Error: 'class org.tron.core.exception.BadItemException' }), { network: 'tron', address: TRON });
    expect(res).toEqual({ status: 502, body: { code: 'unavailable' } });
  });

  const trc20 = (balances: unknown) => ({
    'https://api.trongrid.io/v1/accounts/': () => Response.json(balances),
    'https://api.trongrid.io/wallet/triggerconstantcontract': () => Response.json({ constant_result: ['6'.padStart(64, '0')] }),
  });

  it('reads a TRC-20 balance, and an empty list as the chain saying none', async () => {
    const held = await lookup(trc20({ success: true, data: [{ [USDT_TRC20]: '12500000' }] }), { network: 'tron', address: TRON, contract: USDT_TRC20 });
    expect(held.body.amount).toBe(12.5);
    const none = await lookup(trc20({ success: true, data: [] }), { network: 'tron', address: TRON, contract: USDT_TRC20 });
    expect(none.body.amount).toBe(0);
  });

  it('answers unavailable, not 0, when the TRC-20 lookup did not succeed', async () => {
    const res = await lookup(trc20({ success: false, error: 'rate limited', statusCode: 429 }), { network: 'tron', address: TRON, contract: USDT_TRC20 });
    expect(res).toEqual({ status: 502, body: { code: 'unavailable' } });
  });
});

describe('Bitcoin', () => {
  const mempool = (address: unknown) => ({
    'https://mempool.space/api/v1/validate-address/': () => Response.json({ isvalid: true }),
    'https://mempool.space/api/address/': () => Response.json(address),
  });

  it('adds confirmed and pending', async () => {
    const res = await lookup(mempool({
      chain_stats: { funded_txo_sum: 150_000_000, spent_txo_sum: 50_000_000 },
      mempool_stats: { funded_txo_sum: 0, spent_txo_sum: 25_000_000 },
    }), { network: 'bitcoin', address: BTC });
    expect(res.body.amount).toBe(0.75);
  });

  it('answers unavailable, not 0, for a body without chain_stats', async () => {
    const res = await lookup(mempool({ error: 'upstream timeout' }), { network: 'bitcoin', address: BTC });
    expect(res).toEqual({ status: 502, body: { code: 'unavailable' } });
  });
});
