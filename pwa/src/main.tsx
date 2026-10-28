/**
 * The page's one entry — AppVm.init, for the browser. Loads the ledger into memory, asks the
 * browser to keep it (Safari evicts an origin's storage after weeks of disuse unless it is
 * persisted or installed — for a ledger that lives nowhere else, that is the one thing that must
 * not happen), picks up the household, then mounts the app. Each screen module registers its own
 * tab, pages and sheets on import.
 */
import { render } from 'preact';
import './app.css';
import { App } from './app';
import { pref, rows, setPref, setWriteErrorHandler, subscribe } from './state';
import { ensureSeeded } from './rules';
import { ledger } from './derived';
import { configureWealth, refreshIfStale } from './data';
import { announce } from './plans';
import { readShowRialFrom } from './format';
import { acceptPairing, familyState, loadFamily, requestFamilySync } from './family';
import { isTab, landingFrom, openPage, openSheet, selectTab, showNotice } from './nav';
import './screens';

setWriteErrorHandler(() => showNotice('ذخیره نشد. فضای مرورگر رو چک کن و دوباره امتحان کن.'));
readShowRialFrom(() => pref('showRial'));

/** A pairing link's fragment carries the household key; it leaves the address bar at once. */
async function takePairing(): Promise<void> {
  const hash = location.hash;
  if (!/[#&]pair=/.test(hash)) return;
  history.replaceState(null, '', '/');
  await acceptPairing(hash);
  // The phone opens خانواده on a deep link (Ui.kt:511), where the join card is waiting.
  openPage('family');
}

async function start(): Promise<void> {
  if ('serviceWorker' in navigator) navigator.serviceWorker.register('/sw.js').catch(() => {});
  void navigator.storage?.persist?.().catch(() => false);
  await ensureSeeded();
  if (import.meta.env.DEV) {
    if (new URLSearchParams(location.search).has('demo')) (await import('./demo')).seedDemo();
    // The app's own module instances for the console: after an HMR update Vite serves edited
    // modules under `?t=` URLs, so `import('/src/state.ts')` typed by hand can be a second, empty copy.
    Object.assign(window, { mt: {
      state: await import('./state'), derived: await import('./derived'), ledger: await import('./ledger'),
      data: await import('./data'), plans: await import('./plans'), family: await import('./family'), nav: await import('./nav'),
    } });
  }
  await loadFamily();
  // Someone who used the companion before this build is already past the first run.
  if (familyState().paired && !pref('onboarded')) setPref('onboarded', true);

  // The bank row on the asset list is the ledger's anchored balances (AppVm's bankTotal).
  configureWealth({
    bankToman: () => (ledger().bankAccounts.length ? ledger().bankTotalRial / 10 : null),
    afterSnapshot: () => { if (familyState().paired && familyState().sharesAssets) requestFamilySync(true); },
  });

  // AppVm.refreshIfStale on every return to the foreground: prices and wallets. The household syncs
  // on the same return from loadFamily's own listener; a second request here queued a second lap.
  void refreshIfStale();
  document.addEventListener('visibilitychange', () => { if (document.visibilityState === 'visible') void refreshIfStale(); });

  let announcing: ReturnType<typeof setTimeout> | undefined;
  let publishing: ReturnType<typeof setTimeout> | undefined;
  subscribe((external, bySync) => {
    // Another tab's write, reloaded here: that tab announces and syncs it.
    if (external) return;
    // publishLedger's announce step: budget and instalment notes on every change, coalesced.
    clearTimeout(announcing);
    announcing = setTimeout(() => { const view = ledger(); void announce(view.allEntries, view.entries, rows('goals')); }, 1500);
    // Every edit the household could see asks for a silent sync, as the phone's view model does
    // after each one — one made while a sync runs is queued as one more lap, since that run
    // gathered what it sends before she made it. What a sync writes itself does not ask again —
    // that would never settle.
    // ponytail: one trigger on any change rather than a call in every action; unchanged rows cost
    // nothing on the wire because the publisher skips them by content hash.
    if (bySync || !familyState().paired) return;
    clearTimeout(publishing);
    publishing = setTimeout(() => requestFamilySync(true), 2000);
  });

  // Where a tapped notification or a launcher shortcut asked to land: the address when it opened
  // the app (a shortcut always does — the browser navigates even an open window), a message when
  // a notification found the app already open. Out of the address before anything opens, so a
  // reload does not open it again and the sheet's own history entry is pushed on a clean one. The
  // lock's gate is already standing over all of it.
  const landing = landingFrom(location.search);
  if (landing) {
    history.replaceState(null, '', location.pathname + location.hash);
    selectTab(landing.tab);
    if (landing.open === 'manualTxn') openSheet('manualTxn');
    // As on the phone: the deck only when something waits for her, otherwise دفتر is the answer.
    else if (landing.open === 'deck' && ledger().review.length) openPage('deck');
  }
  navigator.serviceWorker?.addEventListener('message', (e) => { if (e.data?.type === 'open-tab' && isTab(e.data.tab)) selectTab(e.data.tab); });

  render(<App />, document.getElementById('app')!);
  await takePairing();
}

void start();
