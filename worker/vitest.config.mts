import { defineConfig } from 'vitest/config';

/**
 * Plain node, on purpose. Unlike sync/, this Worker holds no state — its risk is all in the
 * pure logic (plausibility invariants, ordering checks, the token bucket), which is exactly
 * what runs identically under node and workerd. Nothing here touches the network: a test
 * that scrapes bonbast is a test that fails whenever Iran's internet does.
 */
export default defineConfig({
  test: {
    include: ['test/**/*.test.ts'],
  },
});
