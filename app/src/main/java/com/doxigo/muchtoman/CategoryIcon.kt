package com.doxigo.muchtoman

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.inset
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * A mark per category, because nineteen Persian pills in one grid is nineteen words to read.
 *
 * The words stay — a glyph alone would make her guess — but the mark is what the eye lands on
 * when she is looking for the one she already knows, and it is the same mark in the grid, in the
 * timeline and in the month's report, which is what makes it worth learning once.
 *
 * Drawn, not imported, for the reason the tab icons already are: the icons artifact this project
 * pins is the core set and has no basket, no receipt and no banknote, so most of these would have
 * had to be drawn anyway — and a drawn set beside an imported one is the mismatch you notice
 * without being able to name. Same pen as the tab bar ([pen]), so they are one family.
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
    INSTALMENT, SMOKE, ENVELOPE, STAR, SHOP, CHART, ASTERISK,
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
    "پس‌انداز و سرمایه" -> CategoryGlyph.STACK
    "انتقال وجه" -> CategoryGlyph.PLANE
    "هدیه و نیکوکاری" -> CategoryGlyph.GIFT
    "زیبایی" -> CategoryGlyph.BLOOM
    "مد و پوشاک" -> CategoryGlyph.SHIRT
    "فرهنگی و هنری" -> CategoryGlyph.MUSIC
    "خانه و کاشانه" -> CategoryGlyph.HOUSE
    "خرج اتینا" -> CategoryGlyph.PERSON
    "قرض" -> CategoryGlyph.LEND
    "پس‌گرفتن قرض" -> CategoryGlyph.PAYBACK
    "قسط و وام" -> CategoryGlyph.INSTALMENT
    "دخانیات" -> CategoryGlyph.SMOKE
    "برداشت نقدی" -> CategoryGlyph.NOTE
    "کارمزد" -> CategoryGlyph.PERCENT
    "درآمد" -> CategoryGlyph.TRAY
    "حقوق" -> CategoryGlyph.ENVELOPE
    "پاداش" -> CategoryGlyph.STAR
    "فروش" -> CategoryGlyph.SHOP
    "سود سرمایه‌گذاری" -> CategoryGlyph.CHART
    "سایر" -> CategoryGlyph.ASTERISK
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
    categories.mapNotNull { c -> glyphNamed(c.glyph)?.let { c.nameFa to it } }.toMap()

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
        CategoryGlyph.STACK -> if (dark) Color(0xFF85D993) else Color(0xFF2F8544)
        CategoryGlyph.PLANE -> if (dark) Color(0xFF6FCFDE) else Color(0xFF14798C)
        // ── row 3: the row that forced the whole scheme ──
        CategoryGlyph.GIFT -> if (dark) Color(0xFFA5AEF2) else Color(0xFF4A52B8)
        CategoryGlyph.BLOOM -> if (dark) Color(0xFFF2AF92) else Color(0xFFA85A38)
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

        // ── the income grid, which is its own four columns and shares no cell with the above ──
        // درآمد is [TRAY] below, and پس‌گرفتن قرض [PAYBACK]; these four fill in around them, each
        // at least 60° from whatever ends up beside or under it once the seven are laid out.
        CategoryGlyph.ENVELOPE -> if (dark) Color(0xFF9FB2F2) else Color(0xFF3A5BC0)
        CategoryGlyph.STAR -> if (dark) Color(0xFFE8C46A) else Color(0xFFA07A12)
        CategoryGlyph.SHOP -> if (dark) Color(0xFFF29BBB) else Color(0xFFB43F6B)
        CategoryGlyph.CHART -> if (dark) Color(0xFFDCA0F0) else Color(0xFFA33FBE)
        // «سایر» is «none of the others», which is the one thing کارمزد's grey already says: a
        // category with no character of its own. It never shares a grid with کارمزد — one is
        // income and one is spending — so the two can honestly say it in the same colour.
        CategoryGlyph.ASTERISK -> if (dark) Color(0xFFB4C0C6) else Color(0xFF66737A)
        // Income and transfer. `categoryChoices` never puts these in the same *grid* as anything
        // above, so reusing a hue costs nothing there — but the timeline mixes income and
        // spending row by row, and there درآمد and برداشت نقدی do land side by side in the same
        // green. Left as it is on purpose: seventeen expense categories over 360° average 21°
        // apart, so there is no free slot to move either one into that does not collide with
        // something worse, and on that surface the category's name is set beside the mark at the
        // same size — the colour narrows it, the word and the shape finish it. The grid, where
        // the label is 12sp underneath, is the surface that had to be solved exactly.
        CategoryGlyph.TRAY -> if (dark) Color(0xFF6FD5A0) else Color(0xFF17805A)
        CategoryGlyph.PAYBACK -> if (dark) Color(0xFFEFC177) else Color(0xFFA9761F)
        CategoryGlyph.SWAP -> if (dark) Color(0xFF6FCFDE) else Color(0xFF14798C)
        // Nothing is known about this one, so it borrows the colour of muted text and claims
        // nothing — the same honesty the DOTS mark itself carries.
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

private fun DrawScope.drawGlyph(glyph: CategoryGlyph, tint: Color, stroke: Dp) {
    val w = size.width
    val h = size.height
    val ink = pen(stroke)
    when (glyph) {
        // A basket: a trapezoid that is wider at the mouth, and the handle over the top.
        CategoryGlyph.BASKET -> {
            drawPath(
                Path().apply {
                    moveTo(w * 0.28f, h * 0.36f)
                    quadraticTo(w * 0.5f, h * 0.04f, w * 0.72f, h * 0.36f)
                },
                tint,
                style = ink,
            )
            drawPath(
                Path().apply {
                    moveTo(w * 0.08f, h * 0.4f)
                    lineTo(w * 0.92f, h * 0.4f)
                    lineTo(w * 0.76f, h * 0.92f)
                    lineTo(w * 0.24f, h * 0.92f)
                    close()
                },
                tint,
                style = ink,
            )
        }
        // A cup on a saucer. Not a fork and knife: two crossed strokes at this size is a blob,
        // and the category is «رستوران و کافه» — the cup covers both halves of it.
        CategoryGlyph.CUP -> {
            drawPath(
                Path().apply {
                    moveTo(w * 0.14f, h * 0.24f)
                    lineTo(w * 0.68f, h * 0.24f)
                    lineTo(w * 0.6f, h * 0.74f)
                    lineTo(w * 0.22f, h * 0.74f)
                    close()
                },
                tint,
                style = ink,
            )
            drawPath(
                Path().apply {
                    moveTo(w * 0.68f, h * 0.36f)
                    quadraticTo(w * 0.96f, h * 0.46f, w * 0.66f, h * 0.58f)
                },
                tint,
                style = ink,
            )
            drawLine(tint, Offset(w * 0.08f, h * 0.9f), Offset(w * 0.84f, h * 0.9f), stroke.toPx(), StrokeCap.Round)
        }
        // A bus, and the wheels are the whole point: they are what keeps it from reading as the
        // banknote, which is otherwise the same rounded box.
        CategoryGlyph.BUS -> {
            drawRoundRect(
                tint,
                Offset(w * 0.12f, h * 0.14f),
                Size(w * 0.76f, h * 0.58f),
                CornerRadius(w * 0.14f),
                style = ink,
            )
            drawLine(tint, Offset(w * 0.14f, h * 0.46f), Offset(w * 0.86f, h * 0.46f), stroke.toPx(), StrokeCap.Butt)
            drawCircle(tint, w * 0.09f, Offset(w * 0.3f, h * 0.8f), style = ink)
            drawCircle(tint, w * 0.09f, Offset(w * 0.7f, h * 0.8f), style = ink)
        }
        // A torn-off receipt. The zigzag is the one thing that says «قبض» rather than «سند».
        CategoryGlyph.RECEIPT -> {
            drawPath(
                Path().apply {
                    moveTo(w * 0.2f, h * 0.92f)
                    lineTo(w * 0.2f, h * 0.08f)
                    lineTo(w * 0.8f, h * 0.08f)
                    lineTo(w * 0.8f, h * 0.92f)
                    lineTo(w * 0.65f, h * 0.78f)
                    lineTo(w * 0.5f, h * 0.92f)
                    lineTo(w * 0.35f, h * 0.78f)
                    close()
                },
                tint,
                style = ink,
            )
            drawLine(tint, Offset(w * 0.36f, h * 0.32f), Offset(w * 0.64f, h * 0.32f), stroke.toPx(), StrokeCap.Round)
            drawLine(tint, Offset(w * 0.36f, h * 0.5f), Offset(w * 0.64f, h * 0.5f), stroke.toPx(), StrokeCap.Round)
        }
        // A plus. A heart would have been «favourite» in every app she has ever used.
        CategoryGlyph.CROSS -> {
            drawLine(tint, Offset(w * 0.5f, h * 0.14f), Offset(w * 0.5f, h * 0.86f), stroke.toPx(), StrokeCap.Round)
            drawLine(tint, Offset(w * 0.14f, h * 0.5f), Offset(w * 0.86f, h * 0.5f), stroke.toPx(), StrokeCap.Round)
        }
        // A price tag, for «خرید روزانه» — a shopping bag would have been a second trapezoid with a
        // second handle, and next to the grocery basket that is two of the same drawing.
        CategoryGlyph.TAG -> {
            drawPath(
                Path().apply {
                    moveTo(w * 0.06f, h * 0.52f)
                    lineTo(w * 0.52f, h * 0.06f)
                    lineTo(w * 0.94f, h * 0.06f)
                    lineTo(w * 0.94f, h * 0.48f)
                    lineTo(w * 0.48f, h * 0.94f)
                    close()
                },
                tint,
                style = ink,
            )
            drawCircle(tint, w * 0.075f, Offset(w * 0.75f, h * 0.25f), style = ink)
        }
        // A banknote: what «برداشت نقدی» physically is.
        CategoryGlyph.NOTE -> {
            drawRoundRect(
                tint,
                Offset(w * 0.04f, h * 0.26f),
                Size(w * 0.92f, h * 0.48f),
                CornerRadius(w * 0.09f),
                style = ink,
            )
            drawCircle(tint, w * 0.12f, Offset(w * 0.5f, h * 0.5f), style = ink)
        }
        // A percent sign, which is what a کارمزد always is.
        CategoryGlyph.PERCENT -> {
            drawLine(tint, Offset(w * 0.8f, h * 0.16f), Offset(w * 0.2f, h * 0.84f), stroke.toPx(), StrokeCap.Round)
            drawCircle(tint, w * 0.11f, Offset(w * 0.3f, h * 0.27f), style = ink)
            drawCircle(tint, w * 0.11f, Offset(w * 0.7f, h * 0.73f), style = ink)
        }
        // Money landing in a tray. Not an upward arrow: up is «more» everywhere else in the app,
        // and درآمد is a direction, not a verdict.
        CategoryGlyph.TRAY -> {
            drawLine(tint, Offset(w * 0.5f, h * 0.08f), Offset(w * 0.5f, h * 0.56f), stroke.toPx(), StrokeCap.Round)
            drawPath(
                Path().apply {
                    moveTo(w * 0.32f, h * 0.38f)
                    lineTo(w * 0.5f, h * 0.58f)
                    lineTo(w * 0.68f, h * 0.38f)
                },
                tint,
                style = ink,
            )
            drawPath(
                Path().apply {
                    moveTo(w * 0.12f, h * 0.66f)
                    lineTo(w * 0.12f, h * 0.9f)
                    lineTo(w * 0.88f, h * 0.9f)
                    lineTo(w * 0.88f, h * 0.66f)
                },
                tint,
                style = ink,
            )
        }
        // Two arrows past each other: her own money, moving, counting as neither side.
        CategoryGlyph.SWAP -> {
            drawLine(tint, Offset(w * 0.32f, h * 0.1f), Offset(w * 0.32f, h * 0.86f), stroke.toPx(), StrokeCap.Round)
            drawPath(
                Path().apply {
                    moveTo(w * 0.16f, h * 0.68f)
                    lineTo(w * 0.32f, h * 0.88f)
                    lineTo(w * 0.48f, h * 0.68f)
                },
                tint,
                style = ink,
            )
            drawLine(tint, Offset(w * 0.68f, h * 0.9f), Offset(w * 0.68f, h * 0.14f), stroke.toPx(), StrokeCap.Round)
            drawPath(
                Path().apply {
                    moveTo(w * 0.52f, h * 0.32f)
                    lineTo(w * 0.68f, h * 0.12f)
                    lineTo(w * 0.84f, h * 0.32f)
                },
                tint,
                style = ink,
            )
        }
        // A stack of coins, edge on. Money that stayed, drawn as the one shape that only makes
        // sense in a pile — the banknote is a single note, and this is deliberately never one.
        CategoryGlyph.STACK -> {
            for (y in listOf(0.16f, 0.42f, 0.68f)) {
                drawOval(tint, Offset(w * 0.12f, h * y), Size(w * 0.76f, h * 0.18f), style = ink)
            }
        }
        // A paper plane: money leaving for someone else. The «انتقال بین حساب‌ها» mark is two
        // arrows going nowhere in particular, and that is exactly the difference — this one
        // goes out and does not come back.
        CategoryGlyph.PLANE -> {
            drawPath(
                Path().apply {
                    moveTo(w * 0.94f, h * 0.08f)
                    lineTo(w * 0.06f, h * 0.46f)
                    lineTo(w * 0.44f, h * 0.58f)
                    lineTo(w * 0.56f, h * 0.94f)
                    close()
                },
                tint,
                style = ink,
            )
            drawLine(tint, Offset(w * 0.94f, h * 0.08f), Offset(w * 0.44f, h * 0.58f), stroke.toPx(), StrokeCap.Round)
        }
        // A box with a bow. Charity has no picture of its own that is not a heart, and a heart
        // is «favourite» — the gift covers both halves of «هدیه و نیکوکاری» honestly enough.
        CategoryGlyph.GIFT -> {
            drawRoundRect(
                tint,
                Offset(w * 0.1f, h * 0.4f),
                Size(w * 0.8f, h * 0.5f),
                CornerRadius(w * 0.1f),
                style = ink,
            )
            drawLine(tint, Offset(w * 0.5f, h * 0.4f), Offset(w * 0.5f, h * 0.9f), stroke.toPx(), StrokeCap.Butt)
            drawPath(
                Path().apply {
                    moveTo(w * 0.5f, h * 0.38f)
                    quadraticTo(w * 0.14f, h * 0.32f, w * 0.28f, h * 0.12f)
                    quadraticTo(w * 0.46f, h * 0.06f, w * 0.5f, h * 0.38f)
                    quadraticTo(w * 0.54f, h * 0.06f, w * 0.72f, h * 0.12f)
                    quadraticTo(w * 0.86f, h * 0.32f, w * 0.5f, h * 0.38f)
                },
                tint,
                style = ink,
            )
        }
        // Four petals. A hand mirror is a circle on a stick, which at this size is the search
        // glass every other app trained her to read it as.
        CategoryGlyph.BLOOM -> {
            drawCircle(tint, w * 0.19f, Offset(w * 0.5f, h * 0.26f), style = ink)
            drawCircle(tint, w * 0.19f, Offset(w * 0.5f, h * 0.74f), style = ink)
            drawCircle(tint, w * 0.19f, Offset(w * 0.26f, h * 0.5f), style = ink)
            drawCircle(tint, w * 0.19f, Offset(w * 0.74f, h * 0.5f), style = ink)
            drawCircle(tint, w * 0.075f, Offset(w * 0.5f, h * 0.5f))
        }
        // A t-shirt. The sleeves are what carry it: without them the body alone is a rectangle,
        // and this grid already has enough of those.
        CategoryGlyph.SHIRT -> {
            drawPath(
                Path().apply {
                    moveTo(w * 0.3f, h * 0.12f)
                    lineTo(w * 0.06f, h * 0.32f)
                    lineTo(w * 0.2f, h * 0.5f)
                    lineTo(w * 0.28f, h * 0.42f)
                    lineTo(w * 0.28f, h * 0.9f)
                    lineTo(w * 0.72f, h * 0.9f)
                    lineTo(w * 0.72f, h * 0.42f)
                    lineTo(w * 0.8f, h * 0.5f)
                    lineTo(w * 0.94f, h * 0.32f)
                    lineTo(w * 0.7f, h * 0.12f)
                    quadraticTo(w * 0.5f, h * 0.34f, w * 0.3f, h * 0.12f)
                    close()
                },
                tint,
                style = ink,
            )
        }
        // A note, for «فرهنگی و هنری». A palette or a film reel says only one of the arts; this
        // one says «هنر» to anyone at a glance, and it is the only filled head in the set.
        CategoryGlyph.MUSIC -> {
            drawLine(tint, Offset(w * 0.62f, h * 0.76f), Offset(w * 0.62f, h * 0.12f), stroke.toPx(), StrokeCap.Round)
            drawPath(
                Path().apply {
                    moveTo(w * 0.62f, h * 0.12f)
                    quadraticTo(w * 0.92f, h * 0.2f, w * 0.86f, h * 0.44f)
                },
                tint,
                style = ink,
            )
            drawCircle(tint, w * 0.16f, Offset(w * 0.46f, h * 0.76f))
        }
        // A house with its door. The roof is the whole mark — a body without it is the bus
        // again, minus the wheels.
        CategoryGlyph.HOUSE -> {
            drawPath(
                Path().apply {
                    moveTo(w * 0.06f, h * 0.46f)
                    lineTo(w * 0.5f, h * 0.08f)
                    lineTo(w * 0.94f, h * 0.46f)
                    lineTo(w * 0.94f, h * 0.92f)
                    lineTo(w * 0.06f, h * 0.92f)
                    close()
                },
                tint,
                style = ink,
            )
            drawPath(
                Path().apply {
                    moveTo(w * 0.38f, h * 0.92f)
                    lineTo(w * 0.38f, h * 0.62f)
                    lineTo(w * 0.62f, h * 0.62f)
                    lineTo(w * 0.62f, h * 0.92f)
                },
                tint,
                style = ink,
            )
        }
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
        // Three columns stepping down as they go left — a debt paid off in parts, read in the
        // direction Persian is read. Not another coin: قرض beside it is already a coin, and the
        // two categories that live next to each other are the two that must not share a drawing.
        CategoryGlyph.INSTALMENT -> {
            drawLine(tint, Offset(w * 0.08f, h * 0.9f), Offset(w * 0.92f, h * 0.9f), stroke.toPx(), StrokeCap.Round)
            drawLine(tint, Offset(w * 0.74f, h * 0.9f), Offset(w * 0.74f, h * 0.2f), stroke.toPx(), StrokeCap.Round)
            drawLine(tint, Offset(w * 0.5f, h * 0.9f), Offset(w * 0.5f, h * 0.42f), stroke.toPx(), StrokeCap.Round)
            drawLine(tint, Offset(w * 0.26f, h * 0.9f), Offset(w * 0.26f, h * 0.64f), stroke.toPx(), StrokeCap.Round)
        }
        // A coin, and an arrow leaving it. The pair below is the same coin with the arrow coming
        // home, which is the whole of what a قرض is: one drawing read twice.
        CategoryGlyph.LEND -> {
            drawCircle(tint, w * 0.16f, Offset(w * 0.26f, h * 0.74f), style = ink)
            drawLine(tint, Offset(w * 0.44f, h * 0.58f), Offset(w * 0.86f, h * 0.16f), stroke.toPx(), StrokeCap.Round)
            drawPath(
                Path().apply {
                    moveTo(w * 0.6f, h * 0.14f)
                    lineTo(w * 0.88f, h * 0.14f)
                    lineTo(w * 0.88f, h * 0.42f)
                },
                tint,
                style = ink,
            )
        }
        // The same coin, and the money coming back around to it. Not a straight arrow pointing
        // the other way — mirrored at this size is a mark she has to stop and read, and the
        // U-turn is the one shape that says «returned» before the word beside it does.
        CategoryGlyph.PAYBACK -> {
            drawCircle(tint, w * 0.16f, Offset(w * 0.28f, h * 0.76f), style = ink)
            drawPath(
                Path().apply {
                    moveTo(w * 0.88f, h * 0.72f)
                    quadraticTo(w * 0.98f, h * 0.08f, w * 0.44f, h * 0.12f)
                    quadraticTo(w * 0.24f, h * 0.14f, w * 0.28f, h * 0.42f)
                },
                tint,
                style = ink,
            )
            drawPath(
                Path().apply {
                    moveTo(w * 0.14f, h * 0.28f)
                    lineTo(w * 0.28f, h * 0.46f)
                    lineTo(w * 0.42f, h * 0.28f)
                },
                tint,
                style = ink,
            )
        }
        // A cigarette with its lit end, and the smoke off it. The smoke is what carries it: the
        // body alone at this size is the banknote lying down.
        //
        // Two wisps rather than one, and they take the whole upper half. Drawn once with a single
        // curl off the corner it was the only mark in the set whose ink sat in one corner of its
        // box — every other one here runs edge to edge, and beside them it read as a smaller icon
        // rather than a different one.
        CategoryGlyph.SMOKE -> {
            drawRoundRect(
                tint,
                Offset(w * 0.06f, h * 0.7f),
                Size(w * 0.6f, h * 0.22f),
                CornerRadius(w * 0.05f),
                style = ink,
            )
            // Filled, and the only filled thing in the mark: an outlined tip is another chamber
            // of the same box, and what says «lit» is that this end is solid. The gap between the
            // two is what makes it read as an end rather than as a lid. Kept to the area of the
            // note-head in فرهنگی و هنری, which is the largest fill the set otherwise carries.
            drawRoundRect(
                tint,
                Offset(w * 0.72f, h * 0.7f),
                Size(w * 0.22f, h * 0.22f),
                CornerRadius(w * 0.05f),
            )
            for (x in listOf(0.46f, 0.82f)) {
                drawPath(
                    Path().apply {
                        moveTo(w * x, h * 0.6f)
                        quadraticTo(w * (x - 0.16f), h * 0.46f, w * x, h * 0.32f)
                        quadraticTo(w * (x + 0.14f), h * 0.18f, w * x, h * 0.06f)
                    },
                    tint,
                    style = ink,
                )
            }
        }
        // An envelope, for حقوق — a فیش, and the one shape that says «this arrives every month»
        // rather than «money came in», which is درآمد's tray and already taken.
        CategoryGlyph.ENVELOPE -> {
            drawRoundRect(
                tint,
                Offset(w * 0.06f, h * 0.22f),
                Size(w * 0.88f, h * 0.56f),
                CornerRadius(w * 0.08f),
                style = ink,
            )
            drawPath(
                Path().apply {
                    moveTo(w * 0.08f, h * 0.28f)
                    lineTo(w * 0.5f, h * 0.58f)
                    lineTo(w * 0.92f, h * 0.28f)
                },
                tint,
                style = ink,
            )
        }
        // A star. پاداش is the one category in the app that is money as a compliment, and the
        // star is the only mark anybody has ever drawn for that.
        CategoryGlyph.STAR -> {
            val cx = w * 0.5f
            val cy = h * 0.52f
            drawPath(
                Path().apply {
                    for (i in 0 until 10) {
                        // Alternating radii, from straight up: the outer points are the star and
                        // the inner ones are where its edges meet.
                        val r = if (i % 2 == 0) 0.44f else 0.18f
                        val a = (-PI / 2 + i * PI / 5).toFloat()
                        val x = cx + cos(a) * w * r
                        val y = cy + sin(a) * h * r
                        if (i == 0) moveTo(x, y) else lineTo(x, y)
                    }
                    close()
                },
                tint,
                style = ink,
            )
        }
        // A stall's awning on two posts. Not a handshake, which at 1.6dp is a knot — and not a
        // coin, because فروش is where the money came *from*, not what it is.
        //
        // Open underneath, and that is the whole mark. Drawn with a body under the awning it came
        // out as خانه و کاشانه with a different roof; what says «shop» rather than «house» is the
        // scalloped edge and the fact that you can see through it.
        CategoryGlyph.SHOP -> {
            drawPath(
                Path().apply {
                    moveTo(w * 0.14f, h * 0.16f)
                    lineTo(w * 0.86f, h * 0.16f)
                    lineTo(w * 0.96f, h * 0.4f)
                    quadraticTo(w * 0.85f, h * 0.52f, w * 0.73f, h * 0.4f)
                    quadraticTo(w * 0.62f, h * 0.52f, w * 0.5f, h * 0.4f)
                    quadraticTo(w * 0.39f, h * 0.52f, w * 0.27f, h * 0.4f)
                    quadraticTo(w * 0.16f, h * 0.52f, w * 0.04f, h * 0.4f)
                    close()
                },
                tint,
                style = ink,
            )
            drawLine(tint, Offset(w * 0.22f, h * 0.5f), Offset(w * 0.22f, h * 0.92f), stroke.toPx(), StrokeCap.Round)
            drawLine(tint, Offset(w * 0.78f, h * 0.5f), Offset(w * 0.78f, h * 0.92f), stroke.toPx(), StrokeCap.Round)
        }
        // A line climbing, and it climbs to the left — the direction این صفحه is read, and the
        // same direction قسط و وام's columns already step in.
        CategoryGlyph.CHART -> {
            drawPath(
                Path().apply {
                    moveTo(w * 0.9f, h * 0.08f)
                    lineTo(w * 0.9f, h * 0.9f)
                    lineTo(w * 0.08f, h * 0.9f)
                },
                tint,
                style = ink,
            )
            drawPath(
                Path().apply {
                    moveTo(w * 0.78f, h * 0.74f)
                    lineTo(w * 0.58f, h * 0.5f)
                    lineTo(w * 0.42f, h * 0.6f)
                    lineTo(w * 0.18f, h * 0.24f)
                },
                tint,
                style = ink,
            )
            // The head, squared about the direction the line arrives from. Barbs picked off that
            // angle rather than drawn level: a level pair reads as a flag on a pole.
            drawPath(
                Path().apply {
                    moveTo(w * 0.19f, h * 0.44f)
                    lineTo(w * 0.18f, h * 0.24f)
                    lineTo(w * 0.36f, h * 0.32f)
                },
                tint,
                style = ink,
            )
        }
        // An asterisk, which is «and the rest» in every language that has footnotes. Six arms
        // and not سلامت's four: a plus is a cross standing up, and this one never does.
        CategoryGlyph.ASTERISK -> {
            drawLine(tint, Offset(w * 0.5f, h * 0.08f), Offset(w * 0.5f, h * 0.92f), stroke.toPx(), StrokeCap.Round)
            drawLine(tint, Offset(w * 0.14f, h * 0.29f), Offset(w * 0.86f, h * 0.71f), stroke.toPx(), StrokeCap.Round)
            drawLine(tint, Offset(w * 0.14f, h * 0.71f), Offset(w * 0.86f, h * 0.29f), stroke.toPx(), StrokeCap.Round)
        }
        // Nothing known yet. Three dots say «unset» without the alarm a «؟» carries.
        CategoryGlyph.DOTS -> {
            for (x in listOf(0.22f, 0.5f, 0.78f)) {
                drawCircle(tint, w * 0.085f, Offset(w * x, h * 0.5f))
            }
        }
    }
}
