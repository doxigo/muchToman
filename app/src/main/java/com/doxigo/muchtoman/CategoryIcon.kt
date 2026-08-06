package com.doxigo.muchtoman

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.inset
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * A mark per category, because eleven Persian pills in one grid is eleven words to read.
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
enum class CategoryGlyph { BASKET, CUP, BUS, RECEIPT, CROSS, TAG, NOTE, PERCENT, TRAY, SWAP, DOTS }

fun categoryGlyph(nameFa: String): CategoryGlyph = when (nameFa) {
    "خواربار" -> CategoryGlyph.BASKET
    "رستوران و کافه" -> CategoryGlyph.CUP
    "حمل و نقل" -> CategoryGlyph.BUS
    "قبض‌ها" -> CategoryGlyph.RECEIPT
    "سلامت" -> CategoryGlyph.CROSS
    "خرید" -> CategoryGlyph.TAG
    "برداشت نقدی" -> CategoryGlyph.NOTE
    "کارمزد" -> CategoryGlyph.PERCENT
    "درآمد" -> CategoryGlyph.TRAY
    "انتقال بین حساب‌ها" -> CategoryGlyph.SWAP
    else -> CategoryGlyph.DOTS
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
) {
    Canvas(modifier.size(size)) {
        // The same proportion the tab bar keeps: nothing touches the edge of its own box.
        inset(this.size.minDimension * 0.06f) { drawGlyph(categoryGlyph(nameFa), tint, stroke) }
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
        // A price tag, for «خرید» — a shopping bag would have been a second trapezoid with a
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
        // Nothing known yet. Three dots say «unset» without the alarm a «؟» carries.
        CategoryGlyph.DOTS -> {
            for (x in listOf(0.22f, 0.5f, 0.78f)) {
                drawCircle(tint, w * 0.085f, Offset(w * x, h * 0.5f))
            }
        }
    }
}
