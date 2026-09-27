import { describe, expect, it } from 'vitest';
import RULES from '../../app/src/main/java/com/doxigo/muchtoman/Rules.kt?raw';
import QUIPS from '../src/quips.json';

/**
 * quips.json is edited by hand and goes to every phone on the next deploy, with no app release in
 * between to catch a slip. The phones drop what they cannot place, so a mistake here is not a
 * crash — it is a line that silently never shows. This is where it gets caught instead.
 */

// Each moment and the placeholders the apps fill for it (Quips.kt, quips.ts). A placeholder not
// listed here is one no phone will ever fill, so the line would never be said.
const EVENTS: Record<string, string[]> = {
  budget_near: ['name', 'cat'],
  budget_over: ['name', 'cat'],
  installment: ['name', 'plan'],
  quiet: ['name', 'days', 'usd'],
};

// The built-in category ids, read off the app's own table, so a typo like cat_dinning fails here
// rather than making a line that no budget ever matches.
const CATEGORY_IDS = new Set([...RULES.matchAll(/Category\("(cat_[a-z_]+)"/g)].map((m) => m[1]));

interface Line { event: string; tone: string; text: string; category?: string }

const lines: Line[] = Object.entries(QUIPS as Record<string, Array<Omit<Line, 'event'>>>)
  .flatMap(([event, list]) => list.map((quip) => ({ event, ...quip })));

describe('quips.json', () => {
  it('only uses moments the apps know', () => {
    expect(Object.keys(QUIPS).filter((event) => !(event in EVENTS))).toEqual([]);
  });

  it('reads the category table', () => {
    expect(CATEGORY_IDS.has('cat_dining')).toBe(true);
  });

  it.each(lines)('$event: $text', (quip) => {
    expect(Object.keys(quip).sort()).toEqual(quip.category == null ? ['event', 'text', 'tone'] : ['category', 'event', 'text', 'tone']);
    expect(['witty', 'roast']).toContain(quip.tone);
    if (quip.category != null) expect(CATEGORY_IDS).toContain(quip.category);
    expect(quip.text.trim()).toBe(quip.text);
    // The apps' own cap (MAX_QUIP_LENGTH), less room for a long name or category in a placeholder.
    expect(quip.text.length).toBeLessThanOrEqual(100);
    // Persian letters and digits, and the app's punctuation — the lint the rest of its copy passes.
    expect(quip.text).not.toMatch(/[يك0-9٠-٩—–]/);
    for (const [, name] of quip.text.matchAll(/\{([^}]*)\}/g)) expect(EVENTS[quip.event]).toContain(name);
  });

  it('says nothing twice', () => {
    const texts = lines.map((quip) => quip.text);
    expect(texts.filter((text, i) => texts.indexOf(text) !== i)).toEqual([]);
  });
});
