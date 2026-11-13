import { readFileSync, readdirSync } from 'node:fs';
import { join } from 'node:path';
import { describe, expect, it } from 'vitest';
import { BUDGET_CLOSE_PERCENT, BUDGET_NEAR_PERCENT } from '../src/budget';
import { BANK_TYPE, KINDS, KIND_FA, STATIC_CATALOG, TOMAN_ID } from '../src/catalog';
import { CATEGORY_GLYPHS, categoryGlyph } from '../src/categoryIcon';
import { MONTHS, WEEKDAYS } from '../src/format';
import { INSTALLMENT_REMINDER_DAYS, INSTALLMENT_REMINDER_DEFAULT, remindAwake } from '../src/installments';
import { DAY_MS, TEHRAN_OFFSET_MS } from '../src/jalali';
import {
  DUPLICATE_BALANCE_WINDOW_MS, DUPLICATE_REFNO_WINDOW_MS, DUPLICATE_WINDOW_MS, SLOW_RAIL_WINDOW_MS, TRANSFER_FEE_MAX_RIAL,
  TRANSFER_WINDOW_MS,
} from '../src/links';
import { TEXT_SIZES } from '../src/model';
import { QUIET_AFTER_DAYS } from '../src/quips';
import { BUILTIN_CATEGORIES, BUILTIN_RULES, PASS_THROUGH_CATEGORIES } from '../src/rules';

/**
 * The app and the PWA held to each other, table for table and string for string.
 *
 * AGENTS.md's first hard rule is that every feature ships on both, same numbers and same Persian
 * copy. Until this file that was discipline: nothing failed when one side moved. The Kotlin is
 * read as text — regexes over the source, the way paste.test.ts reads Sms.kt's sender table — and
 * the PWA side is the real exported value wherever there is one. A Kotlin table that cannot be
 * found throws rather than yielding zero rows, so a refactor that moves one breaks this file
 * instead of quietly passing it.
 */

const KT_DIR = join(__dirname, '../../app/src/main/java/com/doxigo/muchtoman');
const SRC_DIR = join(__dirname, '../src');

// ─────────────────────────── reading Kotlin ───────────────────────────

/**
 * One pass over a Kotlin file: the source with its comments blanked (so a «…» quoted in a KDoc
 * neither counts as copy nor breaks a paren count), and the literal text of every string in it,
 * cut at each `${…}` and `$name` template.
 */
function ktScan(src: string): { code: string; fragments: string[] } {
  const fragments: string[] = [];
  let code = '';
  let i = 0;

  // Code until the `}` that closes a template, or to the end of the file when [nested] is false.
  function codeUntil(nested: boolean): void {
    let depth = 0;
    while (i < src.length) {
      if (src.startsWith('//', i)) {
        const eol = src.indexOf('\n', i);
        i = eol < 0 ? src.length : eol;
        if (!nested) code += ' ';
        continue;
      }
      if (src.startsWith('/*', i)) {
        // Kotlin block comments nest.
        let d = 0;
        do {
          if (src.startsWith('/*', i)) { d++; i += 2; } else if (src.startsWith('*/', i)) { d--; i += 2; } else i++;
        } while (d > 0 && i < src.length);
        if (!nested) code += ' ';
        continue;
      }
      const start = i;
      const c = src[i];
      if (c === '"') string();
      else if (c === "'") i += src[i + 1] === '\\' ? (src[i + 2] === 'u' ? 8 : 4) : 3;
      else {
        if (nested && c === '{') depth++;
        if (nested && c === '}' && depth-- === 0) { i++; return; }
        i++;
      }
      if (!nested) code += src.slice(start, i);
    }
    if (nested) throw new Error('unterminated Kotlin template');
  }

  function string(): void {
    const raw = src.startsWith('"""', i);
    i += raw ? 3 : 1;
    let buf = '';
    for (;;) {
      if (i >= src.length) throw new Error('unterminated Kotlin string');
      if (raw ? src.startsWith('"""', i) : src[i] === '"') {
        if (raw) while (src[i + 3] === '"') { buf += '"'; i++; }
        i += raw ? 3 : 1;
        // `"…" +` on one line and `"…"` on the next is one sentence wrapped, and is read as one.
        const joined = raw ? null : /^\s*\+\s*"(?!"")/.exec(src.slice(i, i + 200));
        if (joined) { i += joined[0].length; continue; }
        fragments.push(buf);
        return;
      }
      const c = src[i];
      if (c === '\\' && !raw) {
        const e = src[i + 1];
        if (e === 'u') { buf += String.fromCharCode(parseInt(src.slice(i + 2, i + 6), 16)); i += 6; } else {
          buf += ({ n: '\n', t: '\t', r: '\r', b: '\b' } as Record<string, string>)[e] ?? e;
          i += 2;
        }
      } else if (c === '$' && src[i + 1] === '{') {
        fragments.push(buf);
        buf = '';
        i += 2;
        codeUntil(true);
      } else if (c === '$' && /[A-Za-z_]/.test(src[i + 1] ?? '')) {
        fragments.push(buf);
        buf = '';
        i++;
        while (/\w/.test(src[i] ?? '')) i++;
      } else {
        buf += c;
        i++;
      }
    }
  }

  codeUntil(false);
  return { code, fragments };
}

const ktFiles = readdirSync(KT_DIR).filter((f) => f.endsWith('.kt')).sort();
const scanned = new Map(ktFiles.map((f) => [f, ktScan(readFileSync(join(KT_DIR, f), 'utf8'))]));
const kt = (file: string): string => {
  const s = scanned.get(file);
  if (!s) throw new Error(`${file} is not in ${KT_DIR} any more — point this test at wherever it went`);
  return s.code;
};

/** Index just past the bracket that closes the one opening at [open]; strings are skipped whole. */
function close(code: string, open: number): number {
  let depth = 0;
  for (let i = open; i < code.length; i++) {
    const c = code[i];
    if (c === '"') {
      i++;
      while (code[i] !== '"') i += code[i] === '\\' ? 2 : 1;
    } else if ('([{'.includes(c)) depth++;
    else if (')]}'.includes(c) && --depth === 0) return i + 1;
  }
  throw new Error(`unbalanced bracket at ${open}`);
}

/** The top-level, comma-separated arguments inside the brackets opening at [open]. */
function args(code: string, open: number): string[] {
  const body = code.slice(open + 1, close(code, open) - 1);
  const out: string[] = [];
  let depth = 0;
  let from = 0;
  for (let i = 0; i < body.length; i++) {
    const c = body[i];
    if (c === '"') {
      i++;
      while (body[i] !== '"') i += body[i] === '\\' ? 2 : 1;
      // `Map<String, Int>` in a parameter list: a `<` hugging a name opens, `->` and ` > ` do not.
    } else if ('([{<'.includes(c) && !(c === '<' && body[i - 1] === ' ')) depth++;
    else if (')]}'.includes(c) || (c === '>' && body[i - 1] !== '-' && depth > 0 && body[i - 1] !== ' ')) depth--;
    else if (c === ',' && depth === 0) {
      out.push(body.slice(from, i).trim());
      from = i + 1;
    }
  }
  out.push(body.slice(from).trim());
  return out.filter((a) => a !== ''); // a trailing comma
}

/** The `(` of the value [decl] is set to — `val X: List<T> = listOf(` — or a loud failure. */
function valueOpen(file: string, decl: string): number {
  const code = kt(file);
  const at = code.indexOf(decl);
  if (at < 0) throw new Error(`«${decl}» is not in ${file} any more — point this test at wherever it went`);
  return code.indexOf('(', code.indexOf('=', at + decl.length));
}

/** Every `const val`, keyed by its name and, inside an `object`, by `Object.NAME` too. */
const ktConsts = (() => {
  const raw = new Map<string, string[]>();
  const put = (k: string, v: string) => raw.set(k, [...(raw.get(k) ?? []), v]);
  for (const f of ktFiles) {
    const code = kt(f);
    for (const m of code.matchAll(/\bconst val (\w+)\s*(?::\s*\w+\s*)?=\s*([^\n]+)/g)) put(m[1], m[2].trim());
    for (const m of code.matchAll(/\bobject (\w+)\s*\{/g)) {
      const body = code.slice(m.index, close(code, m.index + m[0].length - 1));
      for (const c of body.matchAll(/\bconst val (\w+)\s*(?::\s*\w+\s*)?=\s*([^\n]+)/g)) put(`${m[1]}.${c[1]}`, c[2].trim());
    }
  }
  return raw;
})();

/** A Kotlin expression from a table, as the value it stands for. */
function ktValue(expr: string): unknown {
  const e = expr.trim().replace(/,$/, '');
  if (/^"""[\s\S]*"""$|^"[\s\S]*"$/.test(e)) {
    const s = ktScan(e);
    if (s.fragments.length !== 1) throw new Error(`a templated string is not a table value: ${e}`);
    return s.fragments[0];
  }
  if (e === 'null') return null;
  if (e === 'true' || e === 'false') return e === 'true';
  if (/^(emptyList|listOf|emptySet|setOf)\(\)$/.test(e)) return [];
  // Numbers and the arithmetic Kotlin constants are written in: `7L * 24 * 60 * 60 * 1000`.
  if (/^[-+*/()\d\s_L.]+$/.test(e) && /\d/.test(e)) return Function(`return (${e.replace(/_/g, '').replace(/(\d)L\b/g, '$1')})`)();
  if (/^[A-Za-z_][\w.]*$/.test(e)) {
    const defs = [...new Set(ktConsts.get(e) ?? [])];
    if (defs.length === 1) return ktValue(defs[0]);
    throw new Error(defs.length ? `${e} is defined twice in app/ with different values: ${defs}` : `no const val ${e} in app/`);
  }
  throw new Error(`not a table value this test can read: ${e}`);
}

/** `const val [name]` from app/, as its value; throws if it is gone, so a rename is a red test. */
const ktConst = (name: string): unknown => ktValue(name);

/** A data class's constructor parameters in order, each with its default expression if it has one. */
function ktParams(file: string, cls: string): { name: string; dflt?: string }[] {
  const code = kt(file);
  const at = code.search(new RegExp(`\\bdata class ${cls}\\(`));
  if (at < 0) throw new Error(`data class ${cls} is not in ${file} any more`);
  return args(code, code.indexOf('(', at)).map((p) => {
    const m = /\bva[lr]\s+(\w+)\s*:\s*[^=]*?(?:=\s*([\s\S]+))?$/.exec(p);
    if (!m) throw new Error(`cannot read the ${cls} parameter «${p}»`);
    return { name: m[1], dflt: m[2]?.trim() };
  });
}

/**
 * Every `[ctor](…)` inside the value of [decl], as raw expressions by parameter name — positional
 * and named arguments both, defaults filled in from the data class itself.
 */
function ktRows(file: string, decl: string, ctor: string, params: { name: string; dflt?: string }[]): Record<string, string>[] {
  const code = kt(file);
  const open = valueOpen(file, decl);
  // From the `=`, so a value that is itself one call — `val BANK_TYPE = AssetType(…)` — is a row.
  const block = code.slice(code.lastIndexOf('=', open), close(code, open));
  return [...block.matchAll(new RegExp(`\\b${ctor}\\(`, 'g'))].map((m) => {
    const row: Record<string, string> = {};
    args(block, m.index + m[0].length - 1).forEach((a, n) => {
      const named = /^(\w+)\s*=(?!=)\s*([\s\S]+)$/.exec(a);
      if (named) row[named[1]] = named[2];
      else row[params[n].name] = a;
    });
    for (const p of params) if (!(p.name in row) && p.dflt !== undefined) row[p.name] = p.dflt;
    return row;
  });
}

const resolve = (row: Record<string, string>, names: string[]): Record<string, unknown> =>
  Object.fromEntries(names.map((n) => [n, ktValue(row[n])]));

const pick = (o: object, names: string[]): Record<string, unknown> =>
  Object.fromEntries(names.map((n) => [n, (o as Record<string, unknown>)[n]]));

/** `val [decl] = listOf(…)` from [file], as values. */
const ktList = (file: string, decl: string): unknown[] => args(kt(file), valueOpen(file, decl)).map(ktValue);

// ─────────────────────────── reading the PWA's own source ───────────────────────────

const tsSource = (file: string): string => readFileSync(join(SRC_DIR, file), 'utf8');

/**
 * A TypeScript file with its comments cut out, its common escapes decoded and its `'…' + '…'`
 * joins closed up: what the copy check searches, so a Persian line quoted in a doc comment cannot
 * stand in for one on screen. JSX text is code here, which is what lets it be found at all.
 */
function tsCode(src: string): string {
  let out = '';
  let i = 0;

  function code(nested: boolean): void {
    let depth = 0;
    while (i < src.length) {
      const c = src[i];
      if (src.startsWith('//', i)) {
        const eol = src.indexOf('\n', i);
        i = eol < 0 ? src.length : eol;
      } else if (src.startsWith('/*', i)) {
        const end = src.indexOf('*/', i + 2);
        i = end < 0 ? src.length : end + 2;
        out += ' ';
      } else if (c === '"' || c === "'") quoted(c);
      else if (c === '`') template();
      // A regex can hold a quote or a `//`; it is told from a division by what came before it.
      else if (c === '/' && /(?:^|[(,=:[!&|?{};+\-*%~^>]|\b(?:return|typeof|case|in|of|void|throw|new|else))\s*$/.test(out.slice(-12))) regex();
      else {
        if (nested && c === '{') depth++;
        if (nested && c === '}' && depth-- === 0) { out += c; i++; return; }
        out += c;
        i++;
      }
    }
  }
  // A JSX text apostrophe opens no string: a quote that reaches the end of its line was not one.
  function quoted(q: string): void {
    const from = i;
    for (i++; i < src.length && src[i] !== q && src[i] !== '\n'; i += src[i] === '\\' ? 2 : 1);
    i = Math.min(i + (src[i] === q ? 1 : 0), src.length);
    out += src.slice(from, i);
  }
  function template(): void {
    out += '`';
    for (i++; i < src.length;) {
      if (src[i] === '\\') { out += src.slice(i, i + 2); i += 2; } else if (src[i] === '`') { out += '`'; i++; return; } else if (src.startsWith('${', i)) {
        out += '${';
        i += 2;
        code(true);
      } else out += src[i++];
    }
  }
  function regex(): void {
    let j = i + 1;
    let inClass = false;
    for (; j < src.length && src[j] !== '\n'; j++) {
      if (src[j] === '\\') j++;
      else if (src[j] === '[') inClass = true;
      else if (src[j] === ']') inClass = false;
      else if (src[j] === '/' && !inClass) break;
    }
    const end = src[j] === '/' ? j + 1 : i + 1; // no closing slash on the line: a division after all
    out += src.slice(i, end);
    i = end;
  }

  code(false);
  return out
    .replace(/\\u\{([0-9a-fA-F]+)\}|\\u([0-9a-fA-F]{4})/g, (_, a: string | undefined, b: string | undefined) => String.fromCodePoint(parseInt(a ?? b!, 16)))
    .replace(/\\n/g, '\n')
    .replace(/\\(['"`\\])/g, '$1')
    .replace(/(['"`])\s*\+\s*(['"`])/g, '');
}

// ─────────────────────────── tables ───────────────────────────

describe('the tables both sides ship', () => {
  it("built-in categories are Rules.kt's, field for field", () => {
    const params = ktParams('Rules.kt', 'Category');
    const names = params.map((p) => p.name);
    const android = ktRows('Rules.kt', 'val BUILTIN_CATEGORIES', 'Category', params).map((r) => resolve(r, names));
    expect(android.length).toBeGreaterThanOrEqual(39);
    expect(BUILTIN_CATEGORIES.map((c) => pick(c, names))).toEqual(android);
  });

  it("built-in rules are Rules.kt's, field for field, the retired ones included", () => {
    const params = ktParams('Rules.kt', 'Rule');
    const names = params.map((p) => p.name);
    const android = ktRows('Rules.kt', 'val BUILTIN_RULES', 'Rule', params).map((r) => resolve(r, names));
    expect(android.length).toBeGreaterThanOrEqual(5);
    expect(BUILTIN_RULES.map((r) => pick(r, names))).toEqual(android);
  });

  it('pass-through categories name the same ids by the same words, in reading order', () => {
    const android = args(kt('Rules.kt'), valueOpen('Rules.kt', 'val PASS_THROUGH_CATEGORIES')).map((pair) => {
      const [k, v] = pair.split(/\s+to\s+/);
      return [ktValue(k), ktValue(v)];
    });
    expect(android.length).toBeGreaterThanOrEqual(3);
    expect([...PASS_THROUGH_CATEGORIES]).toEqual(android);
  });

  it("the asset catalogue is Catalog.kt's, row for row", () => {
    const params = ktParams('Catalog.kt', 'AssetType');
    const names = params.map((p) => p.name);
    // `Kind.GOLD` is an enum entry, which the PWA spells as its bare name; a null is an absent field.
    const read = (r: Record<string, string>) => Object.fromEntries(names.map((n) =>
      [n, n === 'kind' ? r[n].replace(/^Kind\./, '') : ktValue(r[n]) ?? undefined]));
    const android = ktRows('Catalog.kt', 'val STATIC_CATALOG', 'AssetType', params).map(read);
    expect(android.length).toBeGreaterThanOrEqual(21);
    expect(STATIC_CATALOG.map((t) => pick(t, names))).toEqual(android);
    const [bankRow] = ktRows('Catalog.kt', 'val BANK_TYPE', 'AssetType', params);
    expect(pick(BANK_TYPE, names)).toEqual(read(bankRow));
    expect(TOMAN_ID).toBe(ktConst('TOMAN_ID'));
  });

  it("asset kinds are Catalog.kt's, in order and by name", () => {
    const code = kt('Catalog.kt');
    const at = code.indexOf('enum class Kind(');
    expect(at, 'enum class Kind is not in Catalog.kt any more').toBeGreaterThanOrEqual(0);
    const body = code.slice(code.indexOf('{', at), close(code, code.indexOf('{', at)));
    const android = [...body.matchAll(/\b([A-Z_]+)\(\s*"([^"]*)"\s*\)/g)].map((m) => [m[1], m[2]]);
    expect(android.length).toBeGreaterThanOrEqual(8);
    expect(KINDS.map((k) => [k, KIND_FA[k]])).toEqual(android);
  });

  it("month and weekday names are Timeline.kt's", () => {
    const months = ktList('Timeline.kt', 'val MONTHS');
    const weekdays = ktList('Timeline.kt', 'val WEEKDAYS');
    expect(months.length).toBe(12);
    expect(weekdays.length).toBe(7);
    expect(MONTHS).toEqual(months);
    expect(WEEKDAYS).toEqual(weekdays);
  });

  it("text sizes are Theme.kt's", () => {
    const android = ktList('Theme.kt', 'val TEXT_SIZES');
    expect(android.length).toBeGreaterThanOrEqual(3);
    expect(TEXT_SIZES).toEqual(android);
  });

  it("the category-mark table is CategoryIcon.kt's, name for name, and so is the set of marks", () => {
    const code = kt('CategoryIcon.kt');
    const at = code.indexOf('fun categoryGlyph(nameFa: String)');
    expect(at, 'fun categoryGlyph is not in CategoryIcon.kt any more').toBeGreaterThanOrEqual(0);
    const body = code.slice(code.indexOf('{', at), close(code, code.indexOf('{', at)));
    const android = new Map<string, string>();
    for (const m of body.matchAll(/^\s*((?:"[^"]*"\s*,\s*)*"[^"]*")\s*->\s*CategoryGlyph\.(\w+)/gm)) {
      for (const name of m[1].matchAll(/"([^"]*)"/g)) android.set(name[1], m[2]);
    }
    expect(android.size).toBeGreaterThanOrEqual(40);
    expect(/\belse\s*->\s*CategoryGlyph\.DOTS\b/.test(body), 'categoryGlyph no longer falls back to DOTS').toBe(true);
    for (const [name, glyph] of android) expect(categoryGlyph(name), name).toBe(glyph);
    // And no name the PWA knows that the app does not: its table is private, so it is read as text.
    const ts = tsSource('categoryIcon.tsx');
    const from = ts.indexOf('const BY_NAME');
    expect(from, 'BY_NAME is not in categoryIcon.tsx any more').toBeGreaterThanOrEqual(0);
    const pwa = [...ts.slice(from, ts.indexOf(']);', from)).matchAll(/\['([^']+)',\s*'(\w+)'\]/g)].map((m) => m[1]);
    expect(pwa.sort()).toEqual([...android.keys()].sort());

    const glyphs = code.slice(code.indexOf('enum class CategoryGlyph'), close(code, code.indexOf('{', code.indexOf('enum class CategoryGlyph'))));
    const entries = [...glyphs.slice(glyphs.indexOf('{')).matchAll(/\b([A-Z][A-Z_]+)\b/g)].map((m) => m[1]);
    expect(entries.length).toBeGreaterThanOrEqual(40);
    expect([...CATEGORY_GLYPHS]).toEqual(entries);
  });
});

// ─────────────────────────── constants ───────────────────────────

describe('the constants both sides decide by', () => {
  it('are the same numbers', () => {
    const pwa: Record<string, unknown> = {
      BUDGET_NEAR_PERCENT, BUDGET_CLOSE_PERCENT, QUIET_AFTER_DAYS,
      TRANSFER_FEE_MAX_RIAL, TRANSFER_WINDOW_MS, SLOW_RAIL_WINDOW_MS,
      DUPLICATE_WINDOW_MS, DUPLICATE_REFNO_WINDOW_MS, DUPLICATE_BALANCE_WINDOW_MS,
      INSTALLMENT_REMINDER_DEFAULT, TEHRAN_OFFSET_MS, DAY_MS,
    };
    for (const [name, value] of Object.entries(pwa)) expect(value, name).toBe(ktConst(name));
    expect(INSTALLMENT_REMINDER_DAYS).toEqual(ktList('Installments.kt', 'val INSTALLMENT_REMINDER_DAYS'));
  });

  it('and the ones the PWA keeps private, read off its source', () => {
    const tsConst = (file: string, name: string): string => {
      const m = new RegExp(`\\bconst ${name}\\s*=\\s*([^;\\n]+)`).exec(tsSource(file));
      if (!m) throw new Error(`const ${name} is not in ${file} any more`);
      return m[1].trim();
    };
    expect(Number(tsConst('quips.ts', 'QUIET_GIVE_UP_DAYS')), 'QUIET_GIVE_UP_DAYS').toBe(ktConst('QUIET_GIVE_UP_DAYS'));
    const rails = (s: string) => [...s.matchAll(/["']([^"']+)["']/g)].map((m) => m[1]).sort();
    const android = /\bval SLOW_RAILS\s*=\s*setOf\(([^)]*)\)/.exec(kt('Links.kt'));
    expect(android, 'val SLOW_RAILS is not in Links.kt any more').not.toBeNull();
    expect(rails(tsConst('links.ts', 'SLOW_RAILS'))).toEqual(rails(android![1]));
    expect(rails(android![1]).length).toBeGreaterThanOrEqual(2);
  });

  it('remind in the same Tehran hours', () => {
    const m = /\bval REMIND_HOURS\s*=\s*(\d+)\s*\.\.\s*(\d+)/.exec(kt('Installments.kt'));
    expect(m, 'val REMIND_HOURS is not in Installments.kt any more').not.toBeNull();
    const [from, to] = [Number(m![1]), Number(m![2])];
    const day = 20_000 * DAY_MS - TEHRAN_OFFSET_MS;
    for (let h = 0; h < 24; h++) expect(remindAwake(day + h * 3_600_000 + 1), `${h}:00`).toBe(h >= from && h <= to);
  });
});

// ─────────────────────────── Persian copy ───────────────────────────

/**
 * The files that implement only what a browser cannot do — AGENTS.md's standing exceptions — and
 * so have no PWA twin to hold their copy to.
 */
const EXEMPT_FILES: Record<string, string> = {
  'Sms.kt': 'reads the SMS inbox; its Persian is bank wording to match, not copy',
  'SmsInbox.kt': 'reads the SMS inbox automatically',
  'BankNotifications.kt': 'reads bank app notifications',
  'Widget.kt': 'the home-screen widget',
  'Notify.kt': 'notifications posted while the app is closed',
  'Daily.kt': 'background work while the app is closed',
  'Tse.kt': 'TSETMC prices: geo-blocked, no CORS',
  'Diagnostics.kt': 'the crash sheet, not ported yet',
  'UpdateUi.kt': 'the APK update card; a PWA updates itself',
  // filing.ts says it in so many words: «The notification half of Filing.kt has no browser twin».
  // Every Persian string in Filing.kt is that half — the filing notification's title and body.
  'Filing.kt': 'the filing notification, posted while the app is closed',
};

const SMS = 'reads the SMS inbox; the PWA takes a pasted message instead';
const NOTIFY = 'notifications while the app is closed';
const BACKGROUND = 'battery optimisation, for background work while the app is closed';
const BLU = "reads a bank app's notifications (Blu)";
const WIDGET = 'the home-screen widget';
const LITE = 'the lite edition and its upgrade page; the PWA is the full app';
const OTP = 'one-time codes an older inbox reader kept and a sweep removed; a pasted message never held one';
// Not one of AGENTS.md's exceptions but a platform one: Compose's onClickLabel is what TalkBack
// says after «double-tap to», and a web button has no such slot — it is announced by its text.
const TALKBACK = "TalkBack's action label (onClickLabel); a web button is announced by its own text";
// DESIGN.md: «The PWA has no pull, so the line is its only control» — and so nothing answers one.
const PULL = "the toast after a pull to refresh; the PWA has no pull, its sync reports on the family screen";

/** Copy the app shows that the PWA has no place for, by AGENTS.md's exceptions, with why. */
const ANDROID_ONLY: Record<string, string> = {
  // AppVm.kt
  'پشتیبان ساخته شد. اگه فایل پشتیبان قبلی هنوز هست، پاکش کن.': OTP,
  'مورد تازه‌ای از خانواده نبود.': PULL,
  'مورد تازه از خانواده گرفتیم.': PULL,
  // AssetsUi.kt
  'از دکمه پایین پول نقد، دلار، طلا، سکه یا رمزارز اضافه کن تا جمعشون رو ببینی.': LITE,
  // BanksUi.kt: finding a bank's sender among the messages already in the inbox
  'اضافه کردن شمارهٔ بانک از پیامک‌ها': SMS,
  'در حال خوندن پیامک‌ها…': SMS,
  'برای این کار باید اجازهٔ خوندن پیامک رو بدی.': SMS,
  'پیامکی با مبلغ از شمارهٔ تازه‌ای پیدا نشد.': SMS,
  'این شماره‌ها پیامکی با مبلغ فرستادن ولی هنوز توی فهرست نیستن. بانکش رو انتخاب کنی، پیامک‌هاش خونده می‌شه.': SMS,
  'دوباره خوندن همه پیامک‌ها': SMS,
  'همه مبلغ‌ها دوباره از پیامک‌ها خونده بشن؟ دوباره بزن': SMS,
  'باز کردن پیامک‌ها': SMS,
  'این شماره مال': SMS,
  'هست؛ اضافه‌اش کن': SMS,
  'انتخاب بانک': SMS,
  'دیدن پیامک‌ها': SMS,
  // BudgetUi.kt
  'تغییر قسط': TALKBACK,
  'وصل کردن به قسط': TALKBACK,
  // EditsUi.kt
  'بستن دسته‌ها': TALKBACK,
  // Export.kt
  'این پشتیبان مال نسخهٔ مرورگره و توی اپ اندروید باز نمی‌شه.': "the phone refusing the browser's backup; the PWA words its own side",
  // Family.kt
  'دوربین باز نشد.': "the camera app failing to launch; the PWA's capture input has no such failure",
  'تراکنش‌های بقیه': NOTIFY,
  'برای هر کدوم یه اعلان میاد': NOTIFY,
  'اعلانی نمیاد': NOTIFY,
  'ممکنه چند ساعت دیر برسن: این گوشی فقط هر از گاهی از خانواده خبر می‌گیره.': NOTIFY,
  // Home.kt
  'پیامک‌های بانکی خاموشن': SMS,
  'اولین پیامک بانکی که برسه، اینجا خودش پر می‌شه.': SMS,
  'از تنظیمات روشنشون کن تا دفترت خودش پر بشه.': SMS,
  // LoansUi.kt
  'تغییر قرار': TALKBACK,
  // Lock.kt
  'قفل چقدر تومن': "the system biometric sheet's title; WebAuthn's sheet is the browser's and takes none",
  'برای دیدن دارایی‌هات، هویتت رو تأیید کن': "the system biometric sheet's subtitle; WebAuthn's takes none",
  // Onboarding.kt
  'چقدر تومن خرجت رو از روی پیامک بانک می‌خونه. برای این کار به دو تا اجازه احتیاج داره': SMS,
  'خواندن پیامک‌های بانک': SMS,
  'فقط پیامک بانک‌ها، فقط روی همین گوشی. هیچ‌جا فرستاده نمی‌شه.': SMS,
  'تا لحظه‌ای که خرجی ثبت می‌شه خبردار بشی و بگی چی بوده.': NOTIFY,
  'گوشی برای اینکه باتری بیشتر بمونه، برنامه‌هایی رو که یه مدت باز نکردی می‌خوابونه — و برنامهٔ خواب پیامک بانک رو همون لحظه نمی‌بینه. اگه اجازه بدی چقدر تومن بیدار بمونه، هر پیامک همون لحظه خونده می‌شه، وگرنه ممکنه تا چند ساعت بعد طول بکشه.': BACKGROUND,
  'باشه، اجازه می‌دم': BACKGROUND,
  // Report.kt
  'دفترت از وقتی پیامک‌ها روشن شدن پر می‌شه، پس ممکنه این ماه قبل از اون باشه.': `${SMS} — its line says «اولین پیامکی که چسبوندی»`,
  'تغییر در تنظیمات': TALKBACK,
  // Settings.kt
  'نسخهٔ کامل چقدر تومن': LITE,
  'دفتر خرج‌ها، دخل و خرج ماه، بودجه و قسط، طلب و بدهی و دفتر خانوادگی، کنار همین دارایی‌ها. رایگانه.': LITE,
  'ببین چی داره': LITE,
  'هر خرج و درآمد از روی پیامک بانک، با دسته‌بندی.': LITE,
  'هر ماه چقدر اومد، چقدر رفت و کجا رفت.': LITE,
  'بودجه و قسط': LITE,
  'سقف خرج هر دسته، هدف پس‌انداز و قسط‌ها.': LITE,
  'کی بهت بدهکاره و تو به کی.': LITE,
  'یه دفتر مشترک بین چند گوشی که سرور هم نمی‌تونه بخونتش.': LITE,
  'نسخهٔ کامل': LITE,
  'همین برنامه‌ست، با این صفحه‌ها کنار دارایی‌ها. رایگانه و کنار همین نسخه نصب می‌شه، پس لازم نیست اینو پاک کنی.': LITE,
  'نسخهٔ کامل یه برنامهٔ جداست و دارایی‌هایی که اینجا نوشتی خودشون بهش نمی‌رن. از اینجا پشتیبان بگیر، بعد توی نسخهٔ کامل از تنظیمات ← پشتیبان‌گیری، «بازگردانی از پشتیبان» رو بزن.': LITE,
  'همهٔ دارایی‌ها و تنظیمات، توی یک فایل رمزدار': LITE,
  'گرفتن نسخهٔ کامل': LITE,
  'با این گزینه، موجودی حساب‌ها از روی پیامک بانک به‌روز می‌شه.': SMS,
  'اون هم روی همین گوشی، خونده می‌شن.': SMS,
  'هنوز تراکنش بانکی نرسیده. اولین پیامک یا اعلان بانک که بیاد، بانک اینجا نشون داده می‌شه.': SMS,
  'بازخوانی همهٔ پیامک‌ها': SMS,
  'اگر پیامکی جا مونده یا از پشتیبان برگشتی، از اول می‌خونه؛ موجودی‌هایی که خودت نوشتی سر جاشون می‌مونن.': SMS,
  'مرز خواندن': SMS,
  'هنوز شروع نشده': SMS,
  'وارد کردن پیامک‌های قدیمی': SMS,
  'صندوق پیامک از اول خونده می‌شه؛ موجودی‌هایی که خودت نوشتی سر جاشون می‌مونن.': SMS,
  'این گوشی چقدر تومن رو می‌خوابونه، برای همین ممکنه پیامک بانک با چند ساعت تأخیر خونده بشه. برای این‌که همون لحظه خونده بشه، اجازه بده بیدار بمونه.': BACKGROUND,
  'اجازه بده بیدار بمونه': BACKGROUND,
  'اعلان بانک': BLU,
  'خواندن اعلان‌های بلو بانک': BLU,
  'برای وقتی که بلو تراکنش‌ها رو به جای پیامک با اعلان می‌فرسته.': BLU,
  'اعلان‌های بلو از همین حالا، همون لحظه که برسن، خونده می‌شن. اعلانی که قبلاً پاکش کردی دیگه خوندنی نیست.': BLU,
  'روشنش که کنی، صفحهٔ «دسترسی به اعلان» اندروید باز می‌شه؛ اون‌جا چقدر تومن رو روشن کن. اندروید می‌گه این برنامه همهٔ اعلان‌ها رو می‌بینه، ولی فقط اعلان‌های اپ بلو خونده می‌شن و بقیه دست‌نخورده رد می‌شن. برای این لازم نیست پیامک‌ها رو روشن کنی.': BLU,
  'اگه کلیدش خاکستری بود': BLU,
  'تنظیمات اندروید ← برنامه‌ها ← چقدر تومن، از منوی سه‌نقطهٔ بالا تنظیمات محدودشده رو آزاد کن و دوباره امتحان کن.': BLU,
  'قفل ویجت': WIDGET,
  'مبلغ صفحهٔ اصلی رو با ٭٭٭ پنهان می‌کنه': WIDGET,
  'فایل پشتیبان قبلی پیامک‌هایی از بانک رو داره که برنامه دیگه نگه نمی‌داره، مثل رمزهای یک‌بار مصرف. یه پشتیبان تازه بساز و فایل قبلی رو پاک کن.': OTP,
  'برای تمام شدن بازگردانی، برنامه رو ببند و دوباره باز کن.': 'a restored database file needs the process restarted; the PWA restores into IndexedDB in place',
  // Timeline.kt
  'تراکنش‌ها همین‌جا جمع می‌شن، ولی تا اعلان روشن نباشه خبری از تراکنش تازه بهت نمی‌رسه — و دسته‌بندی همون روز خیلی راحت‌تره.': NOTIFY,
  'روشن کردن پیامک‌ها': SMS,
  'در حال خواندن پیامک اصلی...': `${SMS} — the body is fetched from the inbox; the PWA keeps the pasted one`,
  'نوشتن همین یادداشت': TALKBACK,
  'نوشتن همین اسم': TALKBACK,
  'تغییر خرج ثابت': TALKBACK,
  // Ui.kt
  'در حال بازخوانی…': SMS,
};

/**
 * Not drift and not Android-only: the PWA renders exactly these words, but builds them from a
 * template or a JSX expression that a substring check cannot see through. Each says where.
 */
const SAME_WORDS: Record<string, string> = {
  'به نظرت چند ریال می‌ارزه؟': 'homeSheets.tsx: `به نظرت چند ${…} می‌ارزه؟`',
  'به نظرت چند تومن می‌ارزه؟': 'homeSheets.tsx: `به نظرت چند ${…} می‌ارزه؟`',
  'هر ماه چقدره، به': "budgetUi.tsx: {bill ? 'هر ماه چقدره' : …}، به {moneyUnit()}",
  'هر قسط چقدره، به': "budgetUi.tsx: {… : 'هر قسط چقدره'}، به {moneyUnit()}",
};

/**
 * Copy known to differ while a change in flight brings the two sides back in line. Keep it empty on
 * main: an entry here is a fix someone owes, with its reason.
 */
const KNOWN_DRIFT: Record<string, string> = {
};

const persianLetters = (s: string): number => (s.match(/(?=\p{L})[؀-ۿ]/gu) ?? []).length;
const spaced = (s: string): string => s.replace(/\s+/g, ' ').trim();
/** A fragment's words, without the «، » or « • » that joined it to a template on either side. */
const copyOf = (s: string): string => spaced(s).replace(/^[\s،؛:•»)]+|[\s،؛:•«(]+$/g, '');
/** ZWNJ, spacing and Arabic ي/ك folded away: what tells a near miss from a missing line. */
const folded = (s: string): string => s.replace(/[\s​-‏⁠ ]/g, '').replace(/[يى]/g, 'ی').replace(/ك/g, 'ک');

describe('Persian copy', () => {
  const pwa = spaced(readdirSync(SRC_DIR, { recursive: true }).map(String)
    .filter((f) => /\.tsx?$/.test(f)).sort().map((f) => tsCode(tsSource(f))).join('\n'));
  const pwaFolded = folded(pwa);

  /** Every Persian fragment of app/'s copy, with the files it is in. */
  const android = new Map<string, Set<string>>();
  for (const [file, { fragments }] of scanned) {
    if (file in EXEMPT_FILES) continue;
    for (const f of fragments.map(copyOf)) {
      if (persianLetters(f) < 3) continue;
      android.set(f, (android.get(f) ?? new Set()).add(file));
    }
  }
  const where = (f: string) => `${[...android.get(f) ?? []].join(', ')}: «${f}»`;
  const missing = [...android.keys()].filter((f) => !pwa.includes(f));
  const nearMiss = missing.filter((f) => pwaFolded.includes(folded(f)));

  it('reads enough of the app to mean something', () => {
    expect(android.size).toBeGreaterThan(1000);
    for (const f of Object.keys(EXEMPT_FILES)) expect(scanned.has(f), `${f} is exempt but no longer in app/`).toBe(true);
  });

  it('every line the app shows is somewhere in the PWA, word for word', () => {
    const unexplained = missing.filter((f) => !nearMiss.includes(f) && !(f in ANDROID_ONLY) && !(f in SAME_WORDS) && !(f in KNOWN_DRIFT));
    expect(unexplained.map(where), 'app/ copy the PWA does not have — port it, or say why in ANDROID_ONLY').toEqual([]);
  });

  it('and not merely close: a ZWNJ, a space or an Arabic ي/ك apart is drift too', () => {
    expect(nearMiss.filter((f) => !(f in KNOWN_DRIFT)).map(where), 'differs from the PWA only by ZWNJ, spacing or ي/ك').toEqual([]);
  });

  it('keeps its allowlists honest', () => {
    const stale = [ANDROID_ONLY, SAME_WORDS, KNOWN_DRIFT].flatMap(Object.keys).filter((f) => !missing.includes(f));
    expect(stale, 'no longer in app/, or now in the PWA — delete these entries').toEqual([]);
  });
});
