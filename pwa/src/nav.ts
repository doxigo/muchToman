/**
 * Where she is: the tab, the pages stacked over it, the one sheet open, the one notice showing —
 * AppScreens (Ui.kt:398) as data. Screens register themselves by id, so a screen file owns its
 * route and nothing central has to import every screen to know it exists.
 *
 * The browser's back gesture is the phone's back button: every page or sheet opened pushes one
 * history entry, and popping it closes the top-most thing. Closing from the UI goes through
 * `history.back()` for the same reason, so the two can never disagree about how deep she is.
 */
import type { ComponentType } from 'preact';
import { useEffect, useState } from 'preact/hooks';
import type { TabId } from './icons';

export type Tab = TabId;
export const TABS: Array<{ id: Tab; fa: string }> = [
  { id: 'HOME', fa: 'خانه' },
  { id: 'LEDGER', fa: 'دفتر' },
  { id: 'BUDGET', fa: 'آینده' },
  { id: 'ASSETS', fa: 'دارایی' },
  { id: 'REPORT', fa: 'گزارش' },
];

export interface Route { id: string; props: Record<string, unknown> }
export interface Notice { key: number; text: string; action?: { label: string; run: () => void } }

interface NavState { tab: Tab; pages: Route[]; sheet: Route | null; notice: Notice | null }
const state: NavState = { tab: 'HOME', pages: [], sheet: null, notice: null };
let version = 0;
const listeners = new Set<() => void>();
function changed(): void { version++; for (const l of listeners) l(); }

export function useNav(): Readonly<NavState> {
  const [, set] = useState(version);
  useEffect(() => { const l = () => set(version); listeners.add(l); return () => { listeners.delete(l); }; }, []);
  return state;
}
export const navState = (): Readonly<NavState> => state;

// ---- registries -----------------------------------------------------------------------------

export interface TabSpec { Component: ComponentType; badge?: () => number }
export const tabs = new Map<Tab, TabSpec>();
export const pages = new Map<string, ComponentType<any>>();
export const sheets = new Map<string, ComponentType<any>>();
/** Screens that stand in front of everything while they apply: the lock, then onboarding. */
export interface Gate { order: number; active: () => boolean; Component: ComponentType }
export const gates: Gate[] = [];

export const registerTab = (tab: Tab, spec: TabSpec): void => { tabs.set(tab, spec); };
export const registerPage = <P>(id: string, C: ComponentType<P>): void => { pages.set(id, C as ComponentType<any>); };
export const registerSheet = <P>(id: string, C: ComponentType<P>): void => { sheets.set(id, C as ComponentType<any>); };
export const registerGate = (gate: Gate): void => { gates.push(gate); gates.sort((a, b) => a.order - b.order); };

// ---- moves ----------------------------------------------------------------------------------

const hasHistory = typeof history !== 'undefined' && typeof addEventListener !== 'undefined';
let depth = 0;
/** Back events this code caused itself, already applied to the state: they only settle history. */
let skips = 0;
function push(): void { if (hasHistory) { depth++; history.pushState({ depth }, ''); } }
/** Closing from the UI takes effect now; the history entry is given back after. */
function giveBack(entries = 1): void {
  const n = Math.min(entries, depth);
  if (!hasHistory || n <= 0) return;
  depth -= n; skips++; history.go(-n);
}
function popTop(): boolean {
  if (state.sheet) state.sheet = null;
  else if (state.pages.length) state.pages = state.pages.slice(0, -1);
  else return false;
  changed();
  return true;
}
if (hasHistory) {
  // The browser's back — the phone's back button — closes the top-most thing.
  addEventListener('popstate', () => {
    if (skips > 0) { skips--; return; }
    depth = Math.max(0, depth - 1);
    popTop();
  });
}
/** Everything over the tabs, closed at once, and the history entries they held given back. */
function unwindAll(): void {
  state.sheet = null;
  state.pages = [];
  if (hasHistory && depth > 0) { skips++; history.go(-depth); depth = 0; }
}

export function selectTab(tab: Tab): void {
  if (state.tab === tab && !state.pages.length && !state.sheet) return;
  // A tab is a root: choosing one leaves whatever was stacked over the old one.
  unwindAll();
  state.tab = tab;
  changed();
}

/**
 * A page over whatever is there. Opened from a sheet, the page takes over the sheet's history
 * entry rather than stacking one more — the sheet goes and the page comes in one move, so back
 * from the page lands where the sheet was opened from, and no pending pop can close the page.
 */
export function openPage(id: string, props: Record<string, unknown> = {}): void {
  const fromSheet = state.sheet != null;
  state.sheet = null;
  state.pages = [...state.pages, { id, props }];
  if (!fromSheet) push();
  changed();
}
/** Swap the top page for another without growing the stack (a sheet leading to a page). */
export function replacePage(id: string, props: Record<string, unknown> = {}): void {
  if (!state.pages.length) return openPage(id, props);
  state.pages = [...state.pages.slice(0, -1), { id, props }];
  changed();
}
/** The top page, and a sheet open over it if there is one. */
export function closePage(): void {
  if (!state.pages.length) return;
  const entries = state.sheet ? 2 : 1;
  state.sheet = null;
  state.pages = state.pages.slice(0, -1);
  changed();
  giveBack(entries);
}

export function openSheet(id: string, props: Record<string, unknown> = {}): void {
  const replacing = state.sheet != null;
  state.sheet = { id, props };
  if (!replacing) push();
  changed();
}
export function closeSheet(): void { if (state.sheet && popTop()) giveBack(); }

let noticeTimer: ReturnType<typeof setTimeout> | undefined;
/** TransientNoticeBand: one at a time, six seconds, at most one action. */
export function showNotice(text: string, action?: Notice['action']): void {
  state.notice = { key: Date.now(), text, action };
  clearTimeout(noticeTimer);
  noticeTimer = setTimeout(() => { state.notice = null; changed(); }, 6000);
  changed();
}
export function dismissNotice(): void { clearTimeout(noticeTimer); state.notice = null; changed(); }

// ---- the report's way in (Ui.kt openReport) --------------------------------------------------

export type ReportMode = 'CASH_FLOW' | 'ASSETS';
/** Read by the report screen when it mounts or the intent changes; `stamp` says it is new. */
export const reportIntent: { mode: ReportMode; stamp: number } = { mode: 'CASH_FLOW', stamp: 0 };
export function openReport(mode: ReportMode): void {
  reportIntent.mode = mode;
  reportIntent.stamp++;
  selectTab('REPORT');
  changed();
}
