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
import { acceptPairing, familyState, loadFamily, requestFamilySync } from './family';
import { openPage, selectTab, showNotice, TABS } from './nav';
import type { Tab } from './nav';
import './screens';

setWriteErrorHandler(() => showNotice('ذخیره نشد. فضای مرورگر رو چک کن و دوباره امتحان کن.'));

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

  // AppVm.refreshIfStale on every return to the foreground: prices, wallets, and the household.
  const foreground = (): void => {
    void refreshIfStale();
    if (familyState().paired) requestFamilySync(true);
  };
  foreground();
  document.addEventListener('visibilitychange', () => { if (document.visibilityState === 'visible') foreground(); });

  let announcing: ReturnType<typeof setTimeout> | undefined;
  let publishing: ReturnType<typeof setTimeout> | undefined;
  subscribe(() => {
    // publishLedger's announce step: budget and instalment notes on every change, coalesced.
    clearTimeout(announcing);
    announcing = setTimeout(() => void announce(ledger().allEntries, rows('goals')), 1500);
    // Every edit the household could see asks for a silent sync, as the phone's view model does
    // after each one. What a sync writes itself does not ask again — that would never settle.
    // ponytail: one trigger on any change rather than a call in every action; unchanged rows cost
    // nothing on the wire because the publisher skips them by content hash.
    const family = familyState();
    if (!family.paired || family.syncing) return;
    clearTimeout(publishing);
    publishing = setTimeout(() => { if (!familyState().syncing) requestFamilySync(true); }, 2000);
  });

  // Where a tapped notification asked to land: `?tab=` when it opened the app, a message when
  // the app was already open.
  const asked = new URLSearchParams(location.search).get('tab');
  const isTab = (t: unknown): t is Tab => TABS.some(({ id }) => id === t);
  if (isTab(asked)) { selectTab(asked); history.replaceState(null, '', location.pathname + location.hash); }
  navigator.serviceWorker?.addEventListener('message', (e) => { if (e.data?.type === 'open-tab' && isTab(e.data.tab)) selectTab(e.data.tab); });

  render(<App />, document.getElementById('app')!);
  await takePairing();
}

void start();
