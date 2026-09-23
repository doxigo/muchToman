package com.doxigo.muchtoman

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.inset
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * A mark per category, because nineteen Persian pills in one grid is nineteen words to read.
 *
 * The words stay — a glyph alone would make her guess — but the mark is what the eye lands on
 * when she is looking for the one she already knows, and it is the same mark in the grid, in the
 * timeline and in the month's report, which is what makes it worth learning once.
 *
 * The drawings are Lucide's — `lucide-static` 1.47.0, ISC, its licence shipped in
 * `assets/licenses/lucide.txt` — kept here as path data ([LUCIDE]) rather than taken as a
 * dependency: forty-odd marks do not need an icon library, and the core Material set this project
 * pins has no basket, no receipt and no banknote. They are inked with this app's pen ([pen]), not
 * with Lucide's stroke, so they stay one family with the hand-drawn tab bar. Lucide draws 2 on a
 * 24 grid, which is the same weight the pen lays down over this box.
 *
 * Three stay drawn by hand, because no set has them: همسر's ring, خرج اتینا's daft face, and the
 * dots that mean «unknown». They are the branches left in [drawGlyph].
 */

/**
 * Keyed on the display name rather than the id: the month's report aggregates spending by name
 * and never sees an id, and the timeline row shows a settled transfer under a name that belongs
 * to no category row at all. Anything unrecognised — a category synced from a device that
 * renamed it — falls back to [CategoryGlyph.DOTS], which is honest about knowing nothing.
 */
enum class CategoryGlyph {
    BASKET, CUP, BUS, RECEIPT, CROSS, TAG, NOTE, PERCENT, TRAY, SWAP,
    STACK, PLANE, GIFT, BLOOM, SHIRT, MUSIC, HOUSE, PERSON, LEND, PAYBACK,
    INSTALMENT, SMOKE, WHEEL, WIFI, ENVELOPE, STAR, SHOP, CHART, ASTERISK,
    RING, AIRPLANE, SCISSORS, BOTTLE, PIN, MUSCLE,
    BALL, MIRROR, BOOK, DUMBBELL, BROOM,
    // A category that outgrew its old mark gets a new one rather than repurposing it: a category
    // she made stores the name, and PIN or STACK must go on drawing the pin and the stack she
    // picked. بازپرداخت اسنپ و تپسی is a taxi now, پس‌انداز و سرمایه a piggy bank. STACK stays a stack
    // of discs, which is also why Settings uses it for پشتیبان‌گیری.
    TAXI, PIGGY,
    DOTS,
}

/**
 * The marks she is offered for a category she makes herself.
 *
 * The set the app already draws, rather than a second set imported for the purpose: one pen, one
 * weight, and every one of them already carries a hue that the grid, the timeline and the month's
 * report agree on. [CategoryGlyph.DOTS] is not on it — three dots is what the app draws when it
 * knows nothing, and it must stay that and only that.
 *
 * ponytail: nothing stops her picking خواربار's basket for a category of her own, and then there
 * are two green baskets in the grid. She chose it, looking at it, and the alternative is a second
 * set of marks drawn for no other reason than to be unused by the first.
 */
val PICKABLE_GLYPHS: List<CategoryGlyph> = CategoryGlyph.entries - CategoryGlyph.DOTS

/** A stored [Category.glyph], or null when it names nothing this build draws. */
fun glyphNamed(name: String): CategoryGlyph? =
    CategoryGlyph.entries.firstOrNull { it.name == name }

fun categoryGlyph(nameFa: String): CategoryGlyph = when (nameFa) {
    "خواربار" -> CategoryGlyph.BASKET
    "رستوران و کافه" -> CategoryGlyph.CUP
    "حمل و نقل" -> CategoryGlyph.BUS
    "قبض‌ها" -> CategoryGlyph.RECEIPT
    "سلامت" -> CategoryGlyph.CROSS
    "خرید روزانه" -> CategoryGlyph.TAG
    "پس‌انداز و سرمایه" -> CategoryGlyph.PIGGY
    "انتقال وجه" -> CategoryGlyph.PLANE
    "هدیه و نیکوکاری" -> CategoryGlyph.GIFT
    "زیبایی" -> CategoryGlyph.MIRROR
    "آرایشگاه" -> CategoryGlyph.SCISSORS
    "آرایشی و بهداشتی" -> CategoryGlyph.BOTTLE
    "مد و پوشاک" -> CategoryGlyph.SHIRT
    "فرهنگی و هنری" -> CategoryGlyph.MUSIC
    "خانه و کاشانه" -> CategoryGlyph.HOUSE
    "خرج اتینا" -> CategoryGlyph.PERSON
    "ورزش" -> CategoryGlyph.BALL
    "آموزش" -> CategoryGlyph.BOOK
    "باشگاه" -> CategoryGlyph.DUMBBELL
    "نظافت" -> CategoryGlyph.BROOM
    "قرض" -> CategoryGlyph.LEND
    "پس‌گرفتن قرض" -> CategoryGlyph.PAYBACK
    "قسط و وام" -> CategoryGlyph.INSTALMENT
    "بازپرداخت اسنپ و تپسی" -> CategoryGlyph.TAXI
    "دخانیات" -> CategoryGlyph.SMOKE
    "خودرو" -> CategoryGlyph.WHEEL
    "اینترنت" -> CategoryGlyph.WIFI
    "سفر" -> CategoryGlyph.AIRPLANE
    "برداشت نقدی" -> CategoryGlyph.NOTE
    "کارمزد" -> CategoryGlyph.PERCENT
    "درآمد" -> CategoryGlyph.TRAY
    "حقوق" -> CategoryGlyph.ENVELOPE
    "پاداش" -> CategoryGlyph.STAR
    "فروش" -> CategoryGlyph.SHOP
    "سود سرمایه‌گذاری" -> CategoryGlyph.CHART
    "سایر" -> CategoryGlyph.ASTERISK
    "همسر" -> CategoryGlyph.RING
    "انتقال بین حساب‌ها" -> CategoryGlyph.SWAP
    else -> CategoryGlyph.DOTS
}

/**
 * The marks she chose for the categories she made, keyed by name like the table above.
 *
 * A composition local rather than a parameter because of where a mark gets drawn: the month's
 * report aggregates by name and never sees a row, and a timeline line has only the name it was
 * filed under. Threading a lookup into those two means threading it into every surface that will
 * ever show a category — and the one thing that must not happen is her mark appearing on one
 * screen and three dots on the next.
 *
 * Empty by default, so a preview, a test and the lite edition all draw the shipped table alone.
 */
val LocalCustomGlyphs = compositionLocalOf { emptyMap<String, CategoryGlyph>() }

/** Name → mark, for every category carrying one of its own. Feeds [LocalCustomGlyphs]. */
fun customGlyphs(categories: List<Category>): Map<String, CategoryGlyph> =
    categories.mapNotNull { c ->
        val stored = glyphNamed(c.glyph)
        val corrected = when {
            c.nameFa in setOf("آموزش", "باشگاه") && stored == CategoryGlyph.BASKET -> categoryGlyph(c.nameFa)
            c.nameFa == "نظافت" && stored == CategoryGlyph.ASTERISK -> CategoryGlyph.BROOM
            else -> stored
        }
        corrected?.let { c.nameFa to it }
    }.toMap()

@Composable
private fun glyphOf(nameFa: String): CategoryGlyph =
    LocalCustomGlyphs.current[nameFa] ?: categoryGlyph(nameFa)

/**
 * The colour a category is known by — one hue each, keyed through [categoryGlyph] so a mark and
 * its colour can never disagree and an unknown name falls back once, in one place.
 *
 * Seventeen pills of identical ink is seventeen Persian words to read, every time, and the mark
 * alone cannot carry it: these are 1.6dp strokes, and shape at that weight is not what the eye
 * catches first. Colour is. The hue is what makes the grid findable without reading, and it is
 * the same hue wherever the category appears next.
 *
 * Two sets rather than one, chosen off the scheme's own luminance rather than a flag threaded
 * down from the theme — the app has an explicit light/dark override, so `isSystemInDarkTheme` is
 * the wrong question here and would be wrong exactly for the person who set it. Both sets are
 * held at roughly one lightness so twenty hues read as a designed spectrum rather than a crayon
 * box, and the dark set is lifted because the same hue on `#26302E` is a hole, not a mark.
 *
 * **The hues are spaced against the grid, not against the colour wheel.** Walking the wheel in
 * category order is the obvious way to do this and it is what the first cut did: زیبایی, مد و
 * پوشاک and فرهنگی و هنری are consecutive, so they came out as three purples side by side in one
 * row and the row read as one smear. What matters is the gap between cells that sit *next to
 * each other*, which — at four columns — is a category and the one after it, and a category and
 * the fourth one after it. Every such pair below is at least 60° apart. Anything diagonal is
 * allowed to be close; the eye does not compare across a corner.
 *
 * Rearranging the categories would have been the other fix and is the wrong one: the order is
 * how she finds them, and it must not change because a colour was inconvenient.
 */
@Composable
fun categoryHue(nameFa: String): Color = glyphHue(glyphOf(nameFa))

/**
 * The same table, reached by the mark instead of the name — which is what the picker in settings
 * needs, since a mark she is choosing between belongs to no category yet.
 */
@Composable
fun glyphHue(glyph: CategoryGlyph): Color {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    return when (glyph) {
        // ── row 1 ──
        CategoryGlyph.BASKET -> if (dark) Color(0xFFA3D486) else Color(0xFF55893A)
        CategoryGlyph.CUP -> if (dark) Color(0xFFF0BE6E) else Color(0xFFB0731E)
        CategoryGlyph.BUS -> if (dark) Color(0xFF85BEF0) else Color(0xFF2A6EB4)
        CategoryGlyph.RECEIPT -> if (dark) Color(0xFFC2A8F0) else Color(0xFF6A45B0)
        // ── row 2 ──
        CategoryGlyph.CROSS -> if (dark) Color(0xFFF2989B) else Color(0xFFB94A4E)
        CategoryGlyph.TAG -> if (dark) Color(0xFFEC96CC) else Color(0xFFAC4586)
        CategoryGlyph.STACK, CategoryGlyph.PIGGY -> if (dark) Color(0xFF85D993) else Color(0xFF2F8544)
        CategoryGlyph.PLANE -> if (dark) Color(0xFF6FCFDE) else Color(0xFF14798C)
        // ── row 3: the row that forced the whole scheme ──
        CategoryGlyph.GIFT -> if (dark) Color(0xFFA5AEF2) else Color(0xFF4A52B8)
        CategoryGlyph.BLOOM, CategoryGlyph.MIRROR -> if (dark) Color(0xFFF2AF92) else Color(0xFFA85A38)
        CategoryGlyph.SHIRT -> if (dark) Color(0xFFCBA4EA) else Color(0xFF7B4AA8)
        CategoryGlyph.MUSIC -> if (dark) Color(0xFFF2A0BC) else Color(0xFFB04A6E)
        // ── row 4 ──
        CategoryGlyph.HOUSE -> if (dark) Color(0xFFC9CC7A) else Color(0xFF6E7526)
        CategoryGlyph.PERSON -> if (dark) Color(0xFF6ED4B8) else Color(0xFF14806A)
        CategoryGlyph.LEND -> if (dark) Color(0xFFF79C7E) else Color(0xFFBC5138)
        CategoryGlyph.INSTALMENT -> if (dark) Color(0xFF94B8E8) else Color(0xFF3C6390)
        // ── row 5 ──
        CategoryGlyph.NOTE -> if (dark) Color(0xFF6FD5A0) else Color(0xFF17805A)
        // A fee is a cost with no character of its own; giving it a hue would be inventing one.
        CategoryGlyph.PERCENT -> if (dark) Color(0xFFB4C0C6) else Color(0xFF66737A)
        // دخانیات, appended last, so its only neighbours are the grey fee beside it and قرض
        // above — which leaves most of the wheel free, and plum is the part of it this grid
        // never used.
        CategoryGlyph.SMOKE -> if (dark) Color(0xFFDDA2E0) else Color(0xFF96479B)
        // ── row 5 continued, and row 6 ──
        // خودرو lands beside دخانیات's plum with قسط و وام's blue directly above it, which rules out
        // both halves of the wheel from teal round to red and leaves the green nobody took: خواربار
        // is yellower and پس‌انداز bluer, and neither is within three cells of this one.
        CategoryGlyph.WHEEL -> if (dark) Color(0xFF89D486) else Color(0xFF348030)
        // اینترنت opens row 6, so برداشت نقدی's green sits directly above it and خودرو is at the far
        // end of the row above, not beside it. Blue-violet, which is what a wifi mark is everywhere;
        // a plain blue is what it wanted and cannot have, sitting 48° off that green — and حمل و نقل
        // and قسط و وام are both already using it anyway. سایر came to sit beside it later and
        // cleared it by 61°, which is the constraint that picked the orchid rather than this.
        CategoryGlyph.WIFI -> if (dark) Color(0xFFA59EEB) else Color(0xFF4538B2)
        // سفر sits between قرض and برداشت نقدی, with آرایشی و بهداشتی directly above it and سایر
        // directly below. Those four take the wheel apart between them: قرض's red-orange rules out
        // everything warm, آرایشی و بهداشتی's azure and برداشت نقدی's green rule out the greens,
        // teals and blues, and سایر's orchid rules out the purples — which leaves the yellow-green,
        // and it clears all four by 68° or more.
        //
        // What it does not clear is the two it never touches: همسر is 9° off it across a corner,
        // and خواربار 9° the other way, five rows up. That is what a free slot looks like now — the
        // spending grid is past twenty cells, so 360° divided by them is less than the gap this
        // table is tuned to, and the only hues left are ones somebody non-adjacent already has. The
        // corner is the cheapest place to spend that, which is the rule this table already keeps:
        // the eye compares along a row and down a column, never across a diagonal.
        CategoryGlyph.AIRPLANE -> if (dark) Color(0xFFA6D478) else Color(0xFF598A28)
        // بازپرداخت اسنپ و تپسی opens no row and closes none: it lands beside قسط و وام, under
        // سلامت's red and over هدیه و نیکوکاری's indigo. Those three rule out the warm half of the
        // wheel and everything from teal round to violet, which leaves the green — and the green
        // is where this grid is already thickest. Between پس‌انداز's leaf and برداشت نقدی's
        // jade, about 11° off each: neither comes within a cell of it, and it clears قسط و وام
        // beside it by 66°, which is the number that decides the cell.
        CategoryGlyph.PIN, CategoryGlyph.TAXI -> if (dark) Color(0xFF7ED39C) else Color(0xFF1E854B)
        // آرایشگاه sits beside زیبایی, with ورزش's gold above it and قرض's red-orange below.
        // Violet is what is left, and the free space in it is the gap between مد و پوشاک's purple
        // and دخانیات's plum — 12° off both, and neither is adjacent to this cell: مد و پوشاک is
        // two rows straight up this column, دخانیات a corner away in the row below. The four it
        // actually touches it clears by 84° and more.
        CategoryGlyph.SCISSORS -> if (dark) Color(0xFFDBA5EE) else Color(0xFF8F56A4)
        // آرایشی و بهداشتی sits beside آرایشگاه, under پس‌انداز and over سفر. Azure, which is the
        // one part of the wheel this grid never spent: کارمزد is at the same hue and is grey by
        // design, so the only real blues near it — حمل و نقل's in row one and قسط و وام's two
        // rows up — are ten degrees away and nowhere near this cell. The narrowest gap it keeps is
        // 65°, to پس‌انداز's green directly above it.
        CategoryGlyph.BOTTLE -> if (dark) Color(0xFF7BC9EA) else Color(0xFF1B78A7)
        // ورزش closes the monthly block, so it sits between خرج اتینا's teal and پس‌انداز's leaf
        // with مد و پوشاک's purple directly above it and آرایشگاه's violet directly below. Those
        // four leave the whole warm half of the wheel, and the one gap in it this grid never spent
        // is the gold between رستوران و کافه's amber and خانه و کاشانه's olive — 15° off each, and
        // neither is adjacent to this cell: رستوران is three rows straight up, خانه a corner away.
        // The four it does touch it clears by 80° and more.
        //
        // پاداش's gold in the income grid is 6° off it, which costs nothing where either grid is
        // drawn — they share no cell — and shows only in the timeline, where a bonus and a باشگاه
        // term are each set beside their own name. The same trade درآمد and برداشت نقدی already
        // make further down this table, and the cheapest one left on a wheel this grid has now
        // divided twenty-eight ways.
        CategoryGlyph.MUSCLE, CategoryGlyph.BALL -> if (dark) Color(0xFFDBC768) else Color(0xFF917D17)
        // ── the income grid, which is its own four columns and shares no cell with the above ──
        // درآمد is [TRAY] below, and پس‌گرفتن قرض [PAYBACK]; these four fill in around them, each
        // at least 60° from whatever ends up beside or under it once the seven are laid out.
        CategoryGlyph.ENVELOPE -> if (dark) Color(0xFF9FB2F2) else Color(0xFF3A5BC0)
        CategoryGlyph.STAR -> if (dark) Color(0xFFE8C46A) else Color(0xFFA07A12)
        CategoryGlyph.SHOP -> if (dark) Color(0xFFF29BBB) else Color(0xFFB43F6B)
        CategoryGlyph.CHART -> if (dark) Color(0xFFDCA0F0) else Color(0xFFA33FBE)
        // «سایر» borrowed کارمزد's grey while it was income only: both are «no character of its
        // own», and the two never met in a grid. Offering it on the spending side ended that —
        // it lands directly under کارمزد there, and two touching cells in the same grey is the
        // one thing this table exists to prevent. کارمزد keeps the grey, because a fee has no
        // character to lose; سایر is the one that had somewhere to go.
        //
        // Orchid, at the widest gap left on the wheel — دخانیات's plum is 15° below it and مد و
        // پوشاک's magenta 16° above, and neither comes within a cell of it in either grid. What
        // it does touch it clears by more than the 60° the grid is tuned to: کارمزد's grey above
        // it, اینترنت's blue-violet and همسر's yellow-green beside it in the spending grid, and
        // پاداش's gold above with پس‌گرفتن قرض's amber beside it in the income one. The dark
        // variant is pushed pinker than the plum it sits nearest, which is where the two would
        // otherwise be hardest to tell apart.
        CategoryGlyph.ASTERISK -> if (dark) Color(0xFFE892DB) else Color(0xFF9F4191)
        // Income and transfer. `categoryChoices` never puts these in the same *grid* as anything
        // above, so reusing a hue costs nothing there — but the timeline mixes income and
        // spending row by row, and there درآمد and برداشت نقدی do land side by side in the same
        // green. Left as it is on purpose: seventeen expense categories over 360° average 21°
        // apart, so there is no free slot to move either one into that does not collide with
        // something worse, and on that surface the category's name is set beside the mark at the
        // same size — the colour narrows it, the word and the shape finish it. The grid, where
        // the label is 12sp underneath, is the surface that had to be solved exactly.
        // همسر sits at the end of both grids, beside انتقال's teal in the spending one and beside
        // سایر's orchid in both. Adding سایر moved it one cell along the spending row, so what is
        // above it there is دخانیات's plum rather than کارمزد's grey, and فروش's pink in the income
        // grid. All four rule out everything from teal round to red and leave the yellow-green
        // between خانه و کاشانه's olive and خواربار's leaf — and neither of those two comes near
        // this cell in either grid.
        CategoryGlyph.RING -> if (dark) Color(0xFFB6D877) else Color(0xFF638A1B)
        CategoryGlyph.TRAY -> if (dark) Color(0xFF6FD5A0) else Color(0xFF17805A)
        CategoryGlyph.PAYBACK -> if (dark) Color(0xFFEFC177) else Color(0xFFA9761F)
        CategoryGlyph.SWAP -> if (dark) Color(0xFF6FCFDE) else Color(0xFF14798C)
        // Nothing is known about this one, so it borrows the colour of muted text and claims
        // nothing — the same honesty the DOTS mark itself carries.
        CategoryGlyph.BOOK -> if (dark) Color(0xFF94B8E8) else Color(0xFF3C6390)
        CategoryGlyph.DUMBBELL -> if (dark) Color(0xFFA3D486) else Color(0xFF55893A)
        CategoryGlyph.BROOM -> if (dark) Color(0xFF7ED3CB) else Color(0xFF267F77)
        CategoryGlyph.DOTS -> MaterialTheme.colorScheme.onSurfaceVariant
    }
}

/**
 * Decorative on purpose: every place this is used, the category's name is the text beside it, so
 * a content description would have a screen reader say the same word twice.
 */
@Composable
fun CategoryIcon(
    nameFa: String,
    tint: Color,
    size: Dp = 18.dp,
    stroke: Dp = 1.6.dp,
    modifier: Modifier = Modifier,
) = GlyphIcon(glyphOf(nameFa), tint, size, stroke, modifier)

/** The mark on its own, for the settings picker, where it names nothing yet. */
@Composable
fun GlyphIcon(
    glyph: CategoryGlyph,
    tint: Color,
    size: Dp = 18.dp,
    stroke: Dp = 1.6.dp,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier.size(size)) {
        // The same proportion the tab bar keeps: nothing touches the edge of its own box.
        inset(this.size.minDimension * 0.06f) { drawGlyph(glyph, tint, stroke) }
    }
}

/**
 * Lucide's drawing for every mark but the three drawn by hand, on its 24-unit grid, named at the
 * end of each line as `lucide-static` names it.
 *
 * Each icon's circles, rects and lines are folded into the one path, and every subpath starts with
 * an absolute M: an SVG path's leading relative m is absolute only while it comes first, and joined
 * after another subpath it would be measured from wherever that one stopped.
 *
 * Internal for [CategoryGlyphTest], which fails when a mark has no drawing here and no hand-drawn
 * branch in [drawGlyph] — it would otherwise draw the three dots, silently.
 */
internal val LUCIDE: Map<CategoryGlyph, String> = mapOf(
    CategoryGlyph.BASKET to "M15 11l-1 9 M19 11l-4-7 M2 11h20 M3.5 11l1.6 7.4a2 2 0 0 0 2 1.6h9.8a2 2 0 0 0 2-1.6l1.7-7.4 M4.5 15.5h15 M5 11l4-7 M9 11l1 9", // shopping-basket
    CategoryGlyph.CUP to "M10 2v2 M14 2v2 M16 8a1 1 0 0 1 1 1v8a4 4 0 0 1-4 4H7a4 4 0 0 1-4-4V9a1 1 0 0 1 1-1h14a4 4 0 1 1 0 8h-1 M6 2v2", // coffee
    CategoryGlyph.BUS to "M8 6v6 M15 6v6 M2 12h19.6 M18 18h3s.5-1.7.8-2.8c.1-.4.2-.8.2-1.2 0-.4-.1-.8-.2-1.2l-1.4-5C20.1 6.8 19.1 6 18 6H4a2 2 0 0 0-2 2v10h3 M5 18a2 2 0 1 0 4 0a2 2 0 1 0 -4 0 M9 18h5 M14 18a2 2 0 1 0 4 0a2 2 0 1 0 -4 0", // bus
    CategoryGlyph.RECEIPT to "M12 17V7 M16 8h-6a2 2 0 0 0 0 4h4a2 2 0 0 1 0 4H8 M4 3a1 1 0 0 1 1-1 1.3 1.3 0 0 1 .7.2l.933.6a1.3 1.3 0 0 0 1.4 0l.934-.6a1.3 1.3 0 0 1 1.4 0l.933.6a1.3 1.3 0 0 0 1.4 0l.933-.6a1.3 1.3 0 0 1 1.4 0l.934.6a1.3 1.3 0 0 0 1.4 0l.933-.6A1.3 1.3 0 0 1 19 2a1 1 0 0 1 1 1v18a1 1 0 0 1-1 1 1.3 1.3 0 0 1-.7-.2l-.933-.6a1.3 1.3 0 0 0-1.4 0l-.934.6a1.3 1.3 0 0 1-1.4 0l-.933-.6a1.3 1.3 0 0 0-1.4 0l-.933.6a1.3 1.3 0 0 1-1.4 0l-.934-.6a1.3 1.3 0 0 0-1.4 0l-.933.6a1.3 1.3 0 0 1-.7.2 1 1 0 0 1-1-1z", // receipt
    CategoryGlyph.CROSS to "M11 2v2 M5 2v2 M5 3H4a2 2 0 0 0-2 2v4a6 6 0 0 0 12 0V5a2 2 0 0 0-2-2h-1 M8 15a6 6 0 0 0 12 0v-3 M18 10a2 2 0 1 0 4 0a2 2 0 1 0 -4 0", // stethoscope
    CategoryGlyph.TAG to "M12.586 2.586A2 2 0 0 0 11.172 2H4a2 2 0 0 0-2 2v7.172a2 2 0 0 0 .586 1.414l8.704 8.704a2.426 2.426 0 0 0 3.42 0l6.58-6.58a2.426 2.426 0 0 0 0-3.42z M7 7.5a0.5 0.5 0 1 0 1 0a0.5 0.5 0 1 0 -1 0", // tag
    CategoryGlyph.NOTE to "M4 6h16a2 2 0 0 1 2 2v8a2 2 0 0 1 -2 2h-16a2 2 0 0 1 -2 -2v-8a2 2 0 0 1 2 -2z M10 12a2 2 0 1 0 4 0a2 2 0 1 0 -4 0 M6 12h.01M18 12h.01", // banknote
    CategoryGlyph.PERCENT to "M19 5L5 19 M4 6.5a2.5 2.5 0 1 0 5 0a2.5 2.5 0 1 0 -5 0 M15 17.5a2.5 2.5 0 1 0 5 0a2.5 2.5 0 1 0 -5 0", // percent
    CategoryGlyph.TRAY to "M12 15V3 M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4 M7 10l5 5 5-5", // download
    CategoryGlyph.SWAP to "M8 3 4 7l4 4 M4 7h16 M16 21l4-4-4-4 M20 17H4", // arrow-left-right
    CategoryGlyph.STACK to "M3 5a9 3 0 1 0 18 0a9 3 0 1 0 -18 0 M3 5V19A9 3 0 0 0 21 19V5 M3 12A9 3 0 0 0 21 12", // database
    CategoryGlyph.PLANE to "M14.536 21.686a.5.5 0 0 0 .937-.024l6.5-19a.496.496 0 0 0-.635-.635l-19 6.5a.5.5 0 0 0-.024.937l7.93 3.18a2 2 0 0 1 1.112 1.11z M21.854 2.147l-10.94 10.939", // send
    CategoryGlyph.GIFT to "M12 7v14 M20 11v8a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2v-8 M7.5 7a1 1 0 0 1 0-5A4.8 8 0 0 1 12 7a4.8 8 0 0 1 4.5-5 1 1 0 0 1 0 5 M4 7h16a1 1 0 0 1 1 1v2a1 1 0 0 1 -1 1h-16a1 1 0 0 1 -1 -1v-2a1 1 0 0 1 1 -1z", // gift
    CategoryGlyph.BLOOM to "M9 12a3 3 0 1 0 6 0a3 3 0 1 0 -6 0 M12 16.5A4.5 4.5 0 1 1 7.5 12 4.5 4.5 0 1 1 12 7.5a4.5 4.5 0 1 1 4.5 4.5 4.5 4.5 0 1 1-4.5 4.5 M12 7.5V9 M7.5 12H9 M16.5 12H15 M12 16.5V15 M8 8l1.88 1.88 M14.12 9.88 16 8 M8 16l1.88-1.88 M14.12 14.12 16 16", // flower
    CategoryGlyph.SHIRT to "M20.38 3.46 16 2a4 4 0 0 1-8 0L3.62 3.46a2 2 0 0 0-1.34 2.23l.58 3.47a1 1 0 0 0 .99.84H6v10c0 1.1.9 2 2 2h8a2 2 0 0 0 2-2V10h2.15a1 1 0 0 0 .99-.84l.58-3.47a2 2 0 0 0-1.34-2.23z", // shirt
    CategoryGlyph.MUSIC to "M9 18V5l12-2v13 M3 18a3 3 0 1 0 6 0a3 3 0 1 0 -6 0 M15 16a3 3 0 1 0 6 0a3 3 0 1 0 -6 0", // music
    CategoryGlyph.HOUSE to "M15 21v-8a1 1 0 0 0-1-1h-4a1 1 0 0 0-1 1v8 M3 10a2 2 0 0 1 .709-1.528l7-6a2 2 0 0 1 2.582 0l7 6A2 2 0 0 1 21 10v9a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z", // house
    CategoryGlyph.LEND to "M11 15h2a2 2 0 1 0 0-4h-3c-.6 0-1.1.2-1.4.6L3 17 M7 21l1.6-1.4c.3-.4.8-.6 1.4-.6h4c1.1 0 2.1-.4 2.8-1.2l4.6-4.4a2 2 0 0 0-2.75-2.91l-4.2 3.9 M2 16l6 6 M13.1 9a2.9 2.9 0 1 0 5.8 0a2.9 2.9 0 1 0 -5.8 0 M3 5a3 3 0 1 0 6 0a3 3 0 1 0 -6 0", // hand-coins
    CategoryGlyph.PAYBACK to "M12 18H4a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h16a2 2 0 0 1 2 2v5 M16 19l3 3 3-3 M18 12h.01 M19 16v6 M6 12h.01 M10 12a2 2 0 1 0 4 0a2 2 0 1 0 -4 0", // banknote-arrow-down
    CategoryGlyph.INSTALMENT to "M16 14v2.2l1.6 1 M16 2v3 M21 7.338V5a2 2 0 00-2-2H5a2 2 0 00-2 2v14a2 2 0 002 2h2.338 M3 9h5.859 M8 2v3 M10 16a6 6 0 1 0 12 0a6 6 0 1 0 -12 0", // calendar-clock
    CategoryGlyph.SMOKE to "M17 12H3a1 1 0 0 0-1 1v2a1 1 0 0 0 1 1h14 M18 8c0-2.5-2-2.5-2-5 M21 16a1 1 0 0 0 1-1v-2a1 1 0 0 0-1-1 M22 8c0-2.5-2-2.5-2-5 M7 12v4", // cigarette
    CategoryGlyph.WHEEL to "M19 17h2c.6 0 1-.4 1-1v-3c0-.9-.7-1.7-1.5-1.9C18.7 10.6 16 10 16 10s-1.3-1.4-2.2-2.3c-.5-.4-1.1-.7-1.8-.7H5c-.6 0-1.1.4-1.4.9l-1.4 2.9A3.7 3.7 0 0 0 2 12v4c0 .6.4 1 1 1h2 M5 17a2 2 0 1 0 4 0a2 2 0 1 0 -4 0 M9 17h6 M15 17a2 2 0 1 0 4 0a2 2 0 1 0 -4 0", // car
    CategoryGlyph.WIFI to "M12 20h.01 M2 8.82a15 15 0 0 1 20 0 M5 12.859a10 10 0 0 1 14 0 M8.5 16.429a5 5 0 0 1 7 0", // wifi
    CategoryGlyph.ENVELOPE to "M22 7l-8.991 5.727a2 2 0 0 1-2.009 0L2 7 M4 4h16a2 2 0 0 1 2 2v12a2 2 0 0 1 -2 2h-16a2 2 0 0 1 -2 -2v-12a2 2 0 0 1 2 -2z", // mail
    CategoryGlyph.STAR to "M11.525 2.295a.53.53 0 0 1 .95 0l2.31 4.679a2.123 2.123 0 0 0 1.595 1.16l5.166.756a.53.53 0 0 1 .294.904l-3.736 3.638a2.123 2.123 0 0 0-.611 1.878l.882 5.14a.53.53 0 0 1-.771.56l-4.618-2.428a2.122 2.122 0 0 0-1.973 0L6.396 21.01a.53.53 0 0 1-.77-.56l.881-5.139a2.122 2.122 0 0 0-.611-1.879L2.16 9.795a.53.53 0 0 1 .294-.906l5.165-.755a2.122 2.122 0 0 0 1.597-1.16z", // star
    CategoryGlyph.SHOP to "M15 21v-5a1 1 0 0 0-1-1h-4a1 1 0 0 0-1 1v5 M17.774 10.31a1.12 1.12 0 0 0-1.549 0 2.5 2.5 0 0 1-3.451 0 1.12 1.12 0 0 0-1.548 0 2.5 2.5 0 0 1-3.452 0 1.12 1.12 0 0 0-1.549 0 2.5 2.5 0 0 1-3.77-3.248l2.889-4.184A2 2 0 0 1 7 2h10a2 2 0 0 1 1.653.873l2.895 4.192a2.5 2.5 0 0 1-3.774 3.244 M4 10.95V19a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-8.05", // store
    CategoryGlyph.CHART to "M16 7h6v6 M22 7l-8.5 8.5-5-5L2 17", // trending-up
    CategoryGlyph.ASTERISK to "M12 5v14 M18.065 8.496l-12.125 7 M5.94 8.504l12.125 7", // asterisk
    CategoryGlyph.AIRPLANE to "M17.8 19.2 16 11l3.5-3.5C21 6 21.5 4 21 3c-1-.5-3 0-4.5 1.5L13 8 4.8 6.2c-.5-.1-.9.1-1.1.5l-.3.5c-.2.5-.1 1 .3 1.3L9 12l-2 3H4l-1 1 3 2 2 3 1-1v-3l3-2 3.5 5.3c.3.4.8.5 1.3.3l.5-.2c.4-.3.6-.7.5-1.2z", // plane
    CategoryGlyph.SCISSORS to "M3 6a3 3 0 1 0 6 0a3 3 0 1 0 -6 0 M8.12 8.12 12 12 M20 4 8.12 15.88 M3 18a3 3 0 1 0 6 0a3 3 0 1 0 -6 0 M14.8 14.8 20 20", // scissors
    CategoryGlyph.BOTTLE to "M10.5 2v4 M14 2H7a2 2 0 0 0-2 2 M19.29 14.76A6.67 6.67 0 0 1 17 11a6.6 6.6 0 0 1-2.29 3.76c-1.15.92-1.71 2.04-1.71 3.19 0 2.22 1.8 4.05 4 4.05s4-1.83 4-4.05c0-1.16-.57-2.26-1.71-3.19 M9.607 21H6a2 2 0 0 1-2-2v-7a2 2 0 0 1 2-2h7V7a1 1 0 0 0-1-1H9a1 1 0 0 0-1 1v3", // soap-dispenser-droplet
    CategoryGlyph.PIN to "M20 10c0 4.993-5.539 10.193-7.399 11.799a1 1 0 0 1-1.202 0C9.539 20.193 4 14.993 4 10a8 8 0 0 1 16 0 M9 10a3 3 0 1 0 6 0a3 3 0 1 0 -6 0", // map-pin
    CategoryGlyph.MUSCLE to "M12.409 13.017A5 5 0 0 1 22 15c0 3.866-4 7-9 7-4.077 0-8.153-.82-10.371-2.462-.426-.316-.631-.832-.62-1.362C2.118 12.723 2.627 2 10 2a3 3 0 0 1 3 3 2 2 0 0 1-2 2c-1.105 0-1.64-.444-2-1 M15 14a5 5 0 0 0-7.584 2 M9.964 6.825C8.019 7.977 9.5 13 8 15", // biceps-flexed
    CategoryGlyph.BALL to "M11 7a16 16 20 0 1 10.98 4.362 M12 12a13 13 0 0 1-8.66 5 M16.83 13.634a16 16 0 0 1-9.267 7.328 M20.66 17A13 13 0 0 0 12 12a13 13 0 0 1 0-10 M8.17 15.366a16 16 0 0 1-1.713-11.69 M2 12a10 10 0 1 0 20 0a10 10 0 1 0 -20 0", // volleyball
    CategoryGlyph.MIRROR to "M11.017 2.814a1 1 0 0 1 1.966 0l1.051 5.558a2 2 0 0 0 1.594 1.594l5.558 1.051a1 1 0 0 1 0 1.966l-5.558 1.051a2 2 0 0 0-1.594 1.594l-1.051 5.558a1 1 0 0 1-1.966 0l-1.051-5.558a2 2 0 0 0-1.594-1.594l-5.558-1.051a1 1 0 0 1 0-1.966l5.558-1.051a2 2 0 0 0 1.594-1.594z M20 2v4 M22 4h-4 M2 20a2 2 0 1 0 4 0a2 2 0 1 0 -4 0", // sparkles
    CategoryGlyph.BOOK to "M12 5v16 M20.001 19A2 2 0 0022 17V5a2 2 0 00-1.999-2L16 3.002A5 5 0 0012 5a5 5 0 00-4-2H4a2 2 0 00-2 2v12a2 2 0 001.999 2H8a5 5 0 014 2 5 5 0 014-2z", // book-open
    CategoryGlyph.DUMBBELL to "M17.596 12.768a2 2 0 1 0 2.829-2.829l-1.768-1.767a2 2 0 0 0 2.828-2.829l-2.828-2.828a2 2 0 0 0-2.829 2.828l-1.767-1.768a2 2 0 1 0-2.829 2.829z M2.5 21.5l1.4-1.4 M20.1 3.9l1.4-1.4 M5.343 21.485a2 2 0 1 0 2.829-2.828l1.767 1.768a2 2 0 1 0 2.829-2.829l-6.364-6.364a2 2 0 1 0-2.829 2.829l1.768 1.767a2 2 0 0 0-2.828 2.829z M9.6 14.4l4.8-4.8", // dumbbell
    CategoryGlyph.BROOM to "M16 22l-1-4 M19 14a1 1 0 0 0 1-1v-1a2 2 0 0 0-2-2h-3a1 1 0 0 1-1-1V4a2 2 0 0 0-4 0v5a1 1 0 0 1-1 1H6a2 2 0 0 0-2 2v1a1 1 0 0 0 1 1 M19 14H5l-1.973 6.767A1 1 0 0 0 4 22h16a1 1 0 0 0 .973-1.233z M8 22l1-4", // brush-cleaning
    CategoryGlyph.TAXI to "M10 2h4 M21 8l-2 2-1.5-3.7A2 2 0 0 0 15.646 5H8.4a2 2 0 0 0-1.903 1.257L5 10 3 8 M7 14h.01 M17 14h.01 M5 10h14a2 2 0 0 1 2 2v4a2 2 0 0 1 -2 2h-14a2 2 0 0 1 -2 -2v-4a2 2 0 0 1 2 -2z M5 18v2 M19 18v2", // car-taxi-front
    CategoryGlyph.PIGGY to "M11 17h3v2a1 1 0 0 0 1 1h2a1 1 0 0 0 1-1v-3a3.16 3.16 0 0 0 2-2h1a1 1 0 0 0 1-1v-2a1 1 0 0 0-1-1h-1a5 5 0 0 0-2-4V3a4 4 0 0 0-3.2 1.6l-.3.4H11a6 6 0 0 0-6 6v1a5 5 0 0 0 2 4v3a1 1 0 0 0 1 1h2a1 1 0 0 0 1-1z M16 10h.01 M2 8v1a2 2 0 0 0 2 2h1", // piggy-bank
)

/** Parsed once. The table never changes, and a mark is drawn on every row of every list. */
private val lucidePaths: Map<CategoryGlyph, Path> by lazy {
    LUCIDE.mapValues { PathParser().parsePathString(it.value).toPath() }
}

/**
 * A Lucide mark in this box. Its 2-unit margin is dropped so the mark fills the box the hand-drawn
 * ones fill, and the pen is divided by the scale so the ink comes out at this app's weight.
 */
private fun DrawScope.drawLucide(path: Path, tint: Color, stroke: Dp) {
    val k = size.minDimension / 20f
    withTransform({
        scale(k, k, pivot = Offset.Zero)
        translate(-2f, -2f)
    }) { drawPath(path, tint, style = pen(stroke / k)) }
}

private fun DrawScope.drawGlyph(glyph: CategoryGlyph, tint: Color, stroke: Dp) {
    lucidePaths[glyph]?.let {
        drawLucide(it, tint, stroke)
        return
    }
    val w = size.width
    val h = size.height
    val ink = pen(stroke)
    when (glyph) {
        // A face pulling a daft one, tongue out — the only glyph in the set that is a mood
        // rather than an object, because «خرج اتینا» is the only category that is a person.
        //
        // Head and shoulders is what it was, and head and shoulders is what «صاحب تراکنش» and
        // every family avatar in the app already is; the one category worth telling apart at a
        // glance was drawn as the app's most repeated shape.
        //
        // The asymmetric eyes are the whole trick. Two matched dots read as a plain smiley at
        // 20dp no matter what the mouth does — it is the mismatch that reads as daft, and it
        // survives being 1dp of ink where an expression drawn with eyebrows does not.
        //
        // The gap between the two has to be drawn much wider than it looks on paper. The first
        // pass used 0.055 and 0.09, a ratio of over one and a half, and at 20dp that is a
        // radius of 1.0dp against 1.7dp: both land as "a dot" and the face came out merely
        // friendly. Exaggerating until it looks wrong at 3x is what makes it read at 1x.
        CategoryGlyph.PERSON -> {
            drawCircle(tint, w * 0.42f, Offset(w * 0.5f, h * 0.46f), style = ink)
            drawCircle(tint, w * 0.045f, Offset(w * 0.35f, h * 0.4f))
            drawCircle(tint, w * 0.115f, Offset(w * 0.66f, h * 0.34f))
            // Mouth and tongue as one stroke: the curve is the smile, the tail drops out of its
            // right end. Two separate strokes left a visible join at this size.
            drawPath(
                Path().apply {
                    moveTo(w * 0.32f, h * 0.6f)
                    quadraticTo(w * 0.48f, h * 0.75f, w * 0.6f, h * 0.63f)
                    quadraticTo(w * 0.72f, h * 0.76f, w * 0.57f, h * 0.88f)
                },
                tint,
                style = ink,
            )
        }
        // A ring with its stone. Not two interlocking rings, which is what the word wants and what
        // the دارایی tab already draws — two overlapping circles is «coins» in this app, and a mark
        // must not mean two things. Not a heart either, for the reason سلامت is not one: a heart is
        // «favourite» in every app she has ever used.
        CategoryGlyph.RING -> {
            drawCircle(tint, w * 0.3f, Offset(w * 0.5f, h * 0.64f), style = ink)
            // The stone sits on the band rather than floating over it: a diamond with a gap under
            // it reads as an arrow above a circle.
            drawPath(
                Path().apply {
                    moveTo(w * 0.5f, h * 0.06f)
                    lineTo(w * 0.68f, h * 0.22f)
                    lineTo(w * 0.5f, h * 0.38f)
                    lineTo(w * 0.32f, h * 0.22f)
                    close()
                },
                tint,
                style = ink,
            )
        }
        // DOTS, and anything that ever reaches here without a drawing: three dots say «unset»
        // without the alarm a «؟» carries.
        else -> {
            for (x in listOf(0.22f, 0.5f, 0.78f)) {
                drawCircle(tint, w * 0.085f, Offset(w * x, h * 0.5f))
            }
        }
    }
}
