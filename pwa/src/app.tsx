/**
 * AppRoot and AppScreens (Ui.kt:268-760): gates first (the lock, then onboarding), then the top
 * page of the stack if one is open, else the tab she is on with the bar under it; the one sheet
 * and the one notice float over whichever that is.
 *
 * Every visited tab stays mounted and hidden rather than torn down, so each keeps its own state the
 * way `rememberSaveableStateHolder` keeps it on the phone; `useScrollPerView` keeps each one's place.
 */
import { useEffect, useLayoutEffect, useRef, useState } from 'preact/hooks';
import { TabIcon } from './icons';
import { TABS, dismissNotice, gates, pages, selectTab, sheets, tabs, useNav } from './nav';
import type { Tab } from './nav';
import { faNumber } from './format';
import { pref, useData } from './state';

function useTheme(): void {
  useData();
  const mode = pref('themeMode');
  useEffect(() => {
    const root = document.documentElement;
    if (mode === 'SYSTEM') delete root.dataset.theme; else root.dataset.theme = mode.toLowerCase();
    // The browser chrome follows the page, as the manifest's colours do.
    const dark = mode === 'DARK' || (mode === 'SYSTEM' && matchMedia('(prefers-color-scheme: dark)').matches);
    document.querySelectorAll('meta[name="theme-color"]').forEach((m) => m.setAttribute('content', dark ? '#121511' : '#FFFFFF'));
  }, [mode]);
}

function TabBar({ selected }: { selected: Tab }) {
  return (
    <nav class="tabbar" role="tablist">
      {TABS.map(({ id, fa }) => {
        const here = id === selected;
        // Not on the tab she is standing on: there the same count is already on screen.
        const badge = here ? 0 : Math.min(99, tabs.get(id)?.badge?.() ?? 0);
        return (
          <button type="button" role="tab" aria-selected={here} onClick={() => selectTab(id)}>
            <span class="indicator">
              <TabIcon tab={id} />
              {badge > 0 && <span class="badge figure">{faNumber(badge)}</span>}
            </span>
            <span>{fa}</span>
          </button>
        );
      })}
    </nav>
  );
}

/**
 * Where each view was scrolled to. The page scrolls the window, so without this a page opened
 * from 200 rows down opens 200 rows down, and a tab comes back wherever the last one was left.
 */
const scrolls = new Map<string, number>();
function useScrollPerView(key: string, depth: number): void {
  const current = useRef(key);
  useEffect(() => {
    const on = () => scrolls.set(current.current, scrollY);
    addEventListener('scroll', on, { passive: true });
    return () => removeEventListener('scroll', on);
  }, []);
  useLayoutEffect(() => {
    if (current.current === key) return;
    current.current = key;
    // A closed page forgets its place; the next page at that depth starts at the top.
    for (const k of scrolls.keys()) if (k.startsWith('page:') && Number(k.split(':')[2]) > depth) scrolls.delete(k);
    scrollTo(0, scrolls.get(key) ?? 0);
  }, [key]);
}

export function App() {
  useData();
  useTheme();
  const nav = useNav();
  const topPage = nav.pages.at(-1);
  useScrollPerView(topPage ? `page:${topPage.id}:${nav.pages.length}` : `tab:${nav.tab}`, nav.pages.length);
  const [visited, setVisited] = useState<Tab[]>([nav.tab]);
  useEffect(() => { if (!visited.includes(nav.tab)) setVisited([...visited, nav.tab]); }, [nav.tab]);

  // A gate stands over the app rather than replacing it, so a lock coming down on a half-typed
  // sheet does not throw the sheet away.
  const gate = gates.find((g) => g.active());

  const top = nav.pages.at(-1);
  const Page = top ? pages.get(top.id) : undefined;
  const sheet = nav.sheet;
  const Sheet = sheet ? sheets.get(sheet.id) : undefined;

  return (
    <>
      {gate && <gate.Component />}
      <div hidden={!!gate} aria-hidden={gate ? 'true' : undefined}>
        {TABS.filter(({ id }) => visited.includes(id)).map(({ id }) => {
          const C = tabs.get(id)?.Component;
          return (
            <div key={id} hidden={!!Page || id !== nav.tab}>
              {C ? <C /> : <div class="screen"><p class="muted">…</p></div>}
            </div>
          );
        })}
        {Page && top && <div key={`${top.id}:${nav.pages.length}`}><Page {...top.props} /></div>}
        {!Page && <TabBar selected={nav.tab} />}
        {Sheet && sheet && <Sheet key={sheet.id} {...sheet.props} />}
        {nav.notice && (
          <div class={`notice${Page ? ' over-page' : ''}`} role="status" key={nav.notice.key}>
            <span class="grow">{nav.notice.text}</span>
            {nav.notice.action && (
              <button type="button" class="pill" onClick={() => { nav.notice?.action?.run(); dismissNotice(); }}>{nav.notice.action.label}</button>
            )}
          </div>
        )}
      </div>
    </>
  );
}
