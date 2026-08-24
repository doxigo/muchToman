# PRODUCT.md — چقدر تومن (MuchToman)

Inferred from README.md, DEVELOPMENT.md, and the codebase on 2026-08-21 (no interview; the
redesign brief asked for one pass). Assumptions are marked.

## What it is

An Android app that answers one question: **how much is all my money, in Toman?** Persian UI,
RTL, Persian digits, big type. Bank balances are read from SMS on the phone; everything else
(fiat, gold, coins, crypto, stocks, property) is valued at free-market rates. A household
ledger, budgets, savings goals, and two reports (دخل و خرج, دارایی) sit on top.

## Who it is for

Built for a Persian-speaking parent. **Legibility beats density everywhere.** Figures are
truncated, never rounded up; every number can be traced back to a transaction; large amounts
are spelled out in words as a guard against misreading by a factor of ten.

## Platform

android

Material 3, as Jetpack Compose renders it. minSdk 24. Two flavours (full / lite) × dev
sandbox build type. One typeface: Modam variable (wght 200–900, wdth 70–100) — the only face
with the required Persian coverage; tightness comes off the width axis, never tracking.

## Non-negotiables (from code and README)

- No points, streaks, confetti, or comparison with others (FCA finding; deliberate).
- Missing rates are never zero; stale rates are named in words, not colour alone.
- Gain is green — the convention every Iranian bank app shares.
- Warnings are words first; colour only confirms.
- Every destructive action is two taps; TalkBack hears the armed state.
- Charts run left-to-right even in RTL (Iranian bank-app convention).
- Light and dark are both first-class, following the system by default.

## Brand commitment (2026-08-21 redesign)

**Wise design language, adapted to Persian** (user-pinned): forest green `#163300` +
bright green `#9FE870` as the identity pair, white/near-white light theme with gray-green
cards, forest-black dark theme, amber caution, pill buttons, circular icon chips, flat
colour instead of gloss, spring motion. The previous world (deep teal-green + gold on warm
paper) is retired. ASSUMPTION: the Wise reference is the Wise *mobile app* (Operate mode),
not their marketing site.
