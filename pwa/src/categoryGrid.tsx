/**
 * CategoryGrid (Timeline.kt:1849) — the one picker for «which category», on a transaction, in the
 * deck and on a new budget. A fixed four columns, so the mark and its colour do the finding rather
 * than the words; equal cells and equal rows (a two-line label lifts its whole row), and the short
 * last row keeps the column width.
 *
 * The chosen tile wears `primary` with `on-primary` ink — the app says «this one» in exactly one
 * colour, so selecting takes the hue away rather than intensifying it. The label stays one weight
 * in every state: a bolder word is a wider one, and it would rewrap under the tap that chose it.
 */
import type { Category } from './model';
import { CategoryIcon, customGlyphs, glyphOf, hueCss } from './categoryIcon';
import { PlusMark } from './icons';
import './categoryGrid.css';

export function CategoryGrid({ categories, selected, onSelect, addTile, selectedLabel = 'دسته فعلی' }: {
  categories: Category[];
  /** The selected category's id, or null. */
  selected: string | null;
  onSelect: (category: Category) => void;
  /**
   * The way to a category that is not here yet, as the grid's own last cell — usually
   * `() => openSheet('category')`. The moment she finds one missing is the moment she is here.
   */
  addTile?: () => void;
  /** What «selected» means on this screen: filed as, or picked and not yet confirmed. */
  selectedLabel?: string;
}) {
  const custom = customGlyphs(categories);
  return (
    <div class="cat-grid">
      {categories.map((category) => {
        const on = category.id === selected;
        const glyph = glyphOf(category.nameFa, custom);
        return (
          <button type="button" class={`cat-tile${on ? ' selected' : ''}`} style={{ '--hue': hueCss(glyph) }}
            aria-label={on ? `${category.nameFa}، ${selectedLabel}` : category.nameFa} onClick={() => onSelect(category)}>
            <span class="disc"><CategoryIcon glyph={glyph} size={20} color="var(--mark)" /></span>
            <span class="cat-label">{category.nameFa}</span>
          </button>
        );
      })}
      {addTile && (
        // A ghost cell, not a filled tile: the categories are answers and this is a door.
        <button type="button" class="cat-tile add" onClick={addTile}>
          <span class="disc"><PlusMark size={18} /></span>
          <span class="cat-label">دستهٔ تازه</span>
        </button>
      )}
    </div>
  );
}
