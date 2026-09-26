/**
 * «دسته‌بندی‌ها» (CategoriesUi.kt) — the categories she files under, shipped and hers, and the
 * sheet that makes or edits one: a name and a mark from the app's own pen.
 */
import { useState } from 'preact/hooks';
import './timeline.css';
import './categoriesUi.css';
import { useLedger } from './derived';
import { addCategory, editCategory, toggleCategoryArchived } from './ledger';
import { CAT_UNCATEGORISED, CategoryKind, offeredBothWays } from './rules';
import { CategoryIcon, PICKABLE_GLYPHS, categoryGlyph, customGlyphs, glyphOf, hueCss } from './categoryIcon';
import type { CategoryGlyph } from './categoryIcon';
import { faLetters } from './catalog';
import { closeSheet, openSheet, registerPage, registerSheet } from './nav';
import { Screen, SegmentedChoice, Sheet, SheetDelete, SheetLabel, SheetTitle } from './ui';
import { usePageTop } from './timeline';
import type { Category, CategoryKindId } from './model';

function CategoriesScreen() {
  usePageTop();
  const view = useLedger();
  const [side, setSide] = useState<CategoryKindId>(CategoryKind.EXPENSE);
  const custom = customGlyphs(view.managedCategories);
  // «دسته‌بندی نشده» is the absence of an answer and «انتقال» the escape hatch; neither is a thing
  // to manage. Hers show on both tabs, since the picker offers them both ways; the ones she deleted
  // sit in a band of their own, where they can still be brought back.
  const shown = view.managedCategories.filter((c) =>
    c.id !== CAT_UNCATEGORISED && c.id !== 'cat_send' && (c.kind === side || offeredBothWays(c)));
  const live = shown.filter((c) => !c.archived);
  const deleted = shown.filter((c) => c.archived);
  return (
    <Screen title="دسته‌بندی‌ها" back tabs={false}>
      <div style={{ marginBottom: 'var(--m)' }}>
        <SegmentedChoice options={[CategoryKind.EXPENSE, CategoryKind.INCOME] as CategoryKindId[]} selected={side}
          label={(it) => (it === CategoryKind.INCOME ? 'دخل' : 'خرج')} onSelect={setSide} />
      </div>
      <div class="band cat-band">
        {live.map((c) => <CategoryRow key={c.id} category={c} custom={custom} onAction={() => openSheet('category', { id: c.id })} />)}
      </div>
      {deleted.length > 0 && (
        <>
          <div class="cat-deleted-head">
            <h2>حذف‌شده‌ها</h2>
            {/* «حذف» here is not what it is elsewhere: the rows filed under it keep its name. */}
            <p class="muted small">دیگه موقع دسته‌بندی پیشنهاد نمی‌شن. تراکنش‌های قبلی با همین اسم می‌مونن.</p>
          </div>
          <div class="band cat-band">
            {deleted.map((c) => <CategoryRow key={c.id} category={c} custom={custom} onAction={() => toggleCategoryArchived(c)} />)}
          </div>
        </>
      )}
      {/* The one loud control on the page, pinned where her thumb already is. */}
      <div class="cat-add">
        <button type="button" class="pill primary wide" onClick={() => openSheet('category', { kind: side })}>افزودن دسته</button>
      </div>
    </Screen>
  );
}

function CategoryRow({ category, custom, onAction }: { category: Category; custom: Record<string, CategoryGlyph>; onAction: () => void }) {
  const glyph = glyphOf(category.nameFa, custom);
  return (
    <div class="band-row cat-row">
      <span class="disc" style={{ '--hue': hueCss(glyph) }}><CategoryIcon glyph={glyph} size={22} color="var(--hue)" /></span>
      <span class={`grow ellipsis cat-name${category.archived ? ' muted' : ''}`}>{category.nameFa}</span>
      <button type="button" class="pill cat-action" onClick={onAction}>{category.archived ? 'برگردون' : 'ویرایش'}</button>
    </div>
  );
}

/** ZWNJ and spaces vary by keyboard, so «پس‌انداز» typed three ways is one name. */
const nameKey = (s: string): string => faLetters(s).replaceAll('‌', '').replaceAll(' ', '').trim();

/**
 * A category of her own, or an edit of any one. No side to pick — hers are offered both ways, so
 * [kind] is only recorded. Until she picks a mark it follows the name she types; renaming one she
 * already knows by its mark does not swap it. [grid] says it was opened from a picker, where the
 * names it must not repeat are the live ones.
 */
function CategorySheet({ id, kind = CategoryKind.EXPENSE, grid = false }: { id?: string; kind?: CategoryKindId; grid?: boolean }) {
  const view = useLedger();
  const editing = id ? view.managedCategories.find((c) => c.id === id) ?? null : null;
  const [draft, setDraft] = useState(editing?.nameFa ?? '');
  // The mark it wears now, which on a shipped category is looked up by name rather than stored.
  const [glyph, setGlyph] = useState<CategoryGlyph>(() => (editing ? glyphOf(editing.nameFa, customGlyphs(view.managedCategories)) : PICKABLE_GLYPHS[0]));
  const [glyphChosen, setGlyphChosen] = useState(editing != null);
  // Its own name is not a clash — keeping it while changing the mark is a real edit.
  const taken = (grid ? view.categories : view.managedCategories).filter((c) => c.id !== editing?.id).map((c) => c.nameFa);
  const clash = draft.trim() !== '' && taken.some((t) => nameKey(t) === nameKey(draft));
  const usable = draft.trim() !== '' && !clash;
  const save = (): void => {
    if (!usable) return;
    if (editing) editCategory(editing, draft.trim(), glyph);
    else addCategory(draft.trim(), kind, glyph);
    closeSheet();
  };
  return (
    <Sheet label={editing ? 'ویرایش دسته' : 'دستهٔ تازه'}>
      <SheetTitle>{editing ? 'ویرایش دسته' : 'دستهٔ تازه'}</SheetTitle>
      <SheetLabel>اسمش چی باشه؟</SheetLabel>
      <label class={`field${clash ? ' error' : ''}`}>
        <input value={draft} maxLength={24} placeholder="مثلاً باشگاه" aria-label="اسم دسته" aria-invalid={clash} enterKeyHint="done"
          onKeyDown={(e) => { if (e.key === 'Enter') { e.preventDefault(); save(); } }}
          onInput={(e) => {
            const next = (e.currentTarget as HTMLInputElement).value.slice(0, 24);
            setDraft(next);
            if (!glyphChosen) {
              const guess = categoryGlyph(next.trim());
              setGlyph(guess === 'DOTS' ? PICKABLE_GLYPHS[0] : guess);
            }
          }} />
      </label>
      {clash && <p class="grid-error" role="status">یه دسته با همین اسم داری.</p>}

      <SheetLabel>نشونه‌اش</SheetLabel>
      <div class="glyph-picker" role="radiogroup" aria-label="نشونه‌اش">
        {PICKABLE_GLYPHS.map((option) => {
          const chosen = option === glyph;
          // Selected wears the app's one «this one» colour; the hue steps aside rather than intensifying.
          return (
            <button type="button" key={option} role="radio" aria-checked={chosen} aria-label={option.toLowerCase()} class="glyph-option"
              style={{ '--hue': hueCss(option) }} onClick={() => { setGlyph(option); setGlyphChosen(true); }}>
              <CategoryIcon glyph={option} size={22} color={chosen ? 'var(--on-primary)' : 'var(--hue)'} />
            </button>
          );
        })}
      </div>

      <div class="sheet-actions">
        <button type="button" class={`pill wide${usable ? ' primary' : ''}`} style={{ minHeight: '52px', fontSize: '16px' }} onClick={save}>
          {editing ? 'ذخیره تغییرات' : 'اضافه کن'}
        </button>
        <button type="button" class="pill wide" onClick={closeSheet}>انصراف</button>
      </div>
      {editing && <SheetDelete label="حذف این دسته" onConfirmed={() => { toggleCategoryArchived(editing); closeSheet(); }} />}
    </Sheet>
  );
}

registerPage('categories', CategoriesScreen);
registerSheet('category', CategorySheet);
