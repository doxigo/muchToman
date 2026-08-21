package com.doxigo.muchtoman

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.inset
import androidx.compose.ui.unit.Dp

/**
 * A mark per fixed asset, in the pen everything else already writes with.
 *
 * The دارایی rows were the one surface still wearing emoji: a list where every سکه was the same
 * 🪙, four currencies were flags, and all of it sat beside tab and category icons drawn by hand.
 * These are the same drawings at the row's own scale — one stroke, round caps, nothing filled.
 *
 * Two rules carried over from the category set. A mark must not mean two things: the currencies
 * are their own signs ($, €, £, ₺, kr, the new درهم mark) rather than flags, and دلار کانادا
 * cannot be a second $ — it is written C$, the way the money itself is. And where the
 * categories draw the same object (خانه, the خودرو wheel, the سود سرمایه‌گذاری chart), the
 * asset reuses that exact drawing rather than a sibling of it.
 *
 * Real logos stay: crypto and bank rows never reach this table, and the toman keeps its own mark.
 */
enum class AssetGlyph {
    CARD, DOLLAR, EURO, POUND, LIRA, KRONE, DIRHAM, CDOLLAR,
    INGOT, SLABS, COIN, PLOT,
    WHEEL, HOUSE, CHART,
}

/** The drawn mark for a fixed asset, or null where a logo, the toman mark or letters do the job. */
fun assetGlyph(type: AssetType): AssetGlyph? = when {
    type.id == BANK_ID -> AssetGlyph.CARD
    type.id == "usd" -> AssetGlyph.DOLLAR
    type.id == "eur" -> AssetGlyph.EURO
    type.id == "gbp" -> AssetGlyph.POUND
    type.id == "try" -> AssetGlyph.LIRA
    type.id == "nok" -> AssetGlyph.KRONE
    type.id == "aed" -> AssetGlyph.DIRHAM
    type.id == "cad" -> AssetGlyph.CDOLLAR
    type.id == "car" -> AssetGlyph.WHEEL
    type.id == "house" -> AssetGlyph.HOUSE
    type.id == "land" -> AssetGlyph.PLOT
    type.kind == Kind.GOLD -> AssetGlyph.INGOT
    type.kind == Kind.SILVER -> AssetGlyph.SLABS
    type.kind == Kind.COIN -> AssetGlyph.COIN
    type.kind == Kind.STOCK -> AssetGlyph.CHART
    else -> null
}

/**
 * Decorative like [CategoryIcon]: the asset's name sits beside it everywhere this is drawn.
 *
 * The default stroke keeps the tab bar's nib — 2 in a box of 24 — at whatever size the row sets,
 * which is what lets these sit in a list over the category marks without reading as a second pen.
 */
@Composable
fun AssetGlyphIcon(
    glyph: AssetGlyph,
    tint: Color,
    size: Dp,
    stroke: Dp = size / 12,
    modifier: Modifier = Modifier,
) = when (glyph) {
    // The categories already draw these three objects; one object, one drawing.
    AssetGlyph.WHEEL -> GlyphIcon(CategoryGlyph.WHEEL, tint, size, stroke, modifier)
    AssetGlyph.HOUSE -> GlyphIcon(CategoryGlyph.HOUSE, tint, size, stroke, modifier)
    AssetGlyph.CHART -> GlyphIcon(CategoryGlyph.CHART, tint, size, stroke, modifier)
    else -> Canvas(modifier.size(size)) {
        inset(this.size.minDimension * 0.06f) { drawAssetGlyph(glyph, tint, stroke) }
    }
}

private fun DrawScope.drawAssetGlyph(glyph: AssetGlyph, tint: Color, stroke: Dp) {
    val w = size.width
    val h = size.height
    val ink = pen(stroke)
    when (glyph) {
        // A bank card: the stripe is what says «کارت» rather than the banknote's rounded box.
        AssetGlyph.CARD -> {
            drawRoundRect(
                tint,
                Offset(w * 0.108f, h * 0.217f),
                Size(w * 0.783f, h * 0.567f),
                CornerRadius(w * 0.125f),
                style = ink,
            )
            drawLine(tint, Offset(w * 0.108f, h * 0.392f), Offset(w * 0.892f, h * 0.392f), stroke.toPx(), StrokeCap.Butt)
            drawLine(tint, Offset(w * 0.258f, h * 0.617f), Offset(w * 0.442f, h * 0.617f), stroke.toPx(), StrokeCap.Round)
        }
        AssetGlyph.DOLLAR -> {
            drawLine(tint, Offset(w * 0.5f, h * 0.121f), Offset(w * 0.5f, h * 0.879f), stroke.toPx(), StrokeCap.Round)
            drawPath(
                Path().apply {
                    moveTo(w * 0.696f, h * 0.238f)
                    lineTo(w * 0.413f, h * 0.238f)
                    arcTo(Rect(w * 0.273f, h * 0.238f, w * 0.552f, h * 0.517f), 270f, -180f, false)
                    lineTo(w * 0.588f, h * 0.517f)
                    arcTo(Rect(w * 0.448f, h * 0.517f, w * 0.727f, h * 0.796f), 270f, 180f, false)
                    lineTo(w * 0.296f, h * 0.796f)
                },
                tint,
                style = ink,
            )
        }
        AssetGlyph.EURO -> {
            drawPath(
                Path().apply {
                    moveTo(w * 0.733f, h * 0.221f)
                    cubicTo(w * 0.679f, h * 0.171f, w * 0.613f, h * 0.142f, w * 0.542f, h * 0.142f)
                    cubicTo(w * 0.371f, h * 0.142f, w * 0.233f, h * 0.304f, w * 0.233f, h * 0.5f)
                    cubicTo(w * 0.233f, h * 0.696f, w * 0.371f, h * 0.858f, w * 0.542f, h * 0.858f)
                    cubicTo(w * 0.613f, h * 0.858f, w * 0.679f, h * 0.829f, w * 0.733f, h * 0.779f)
                },
                tint,
                style = ink,
            )
            drawLine(tint, Offset(w * 0.15f, h * 0.413f), Offset(w * 0.55f, h * 0.413f), stroke.toPx(), StrokeCap.Round)
            drawLine(tint, Offset(w * 0.15f, h * 0.588f), Offset(w * 0.496f, h * 0.588f), stroke.toPx(), StrokeCap.Round)
        }
        AssetGlyph.POUND -> {
            drawLine(tint, Offset(w * 0.263f, h * 0.846f), Offset(w * 0.746f, h * 0.846f), stroke.toPx(), StrokeCap.Round)
            drawPath(
                Path().apply {
                    moveTo(w * 0.304f, h * 0.846f)
                    cubicTo(w * 0.375f, h * 0.775f, w * 0.408f, h * 0.704f, w * 0.408f, h * 0.608f)
                    lineTo(w * 0.408f, h * 0.35f)
                    cubicTo(w * 0.408f, h * 0.238f, w * 0.483f, h * 0.154f, w * 0.588f, h * 0.154f)
                    cubicTo(w * 0.663f, h * 0.154f, w * 0.717f, h * 0.192f, w * 0.742f, h * 0.25f)
                },
                tint,
                style = ink,
            )
            drawLine(tint, Offset(w * 0.279f, h * 0.517f), Offset(w * 0.592f, h * 0.517f), stroke.toPx(), StrokeCap.Round)
        }
        AssetGlyph.LIRA -> {
            drawPath(
                Path().apply {
                    moveTo(w * 0.417f, h * 0.125f)
                    lineTo(w * 0.417f, h * 0.583f)
                    cubicTo(w * 0.417f, h * 0.767f, w * 0.542f, h * 0.858f, w * 0.683f, h * 0.842f)
                    cubicTo(w * 0.775f, h * 0.829f, w * 0.833f, h * 0.758f, w * 0.833f, h * 0.658f)
                },
                tint,
                style = ink,
            )
            drawLine(tint, Offset(w * 0.229f, h * 0.471f), Offset(w * 0.596f, h * 0.279f), stroke.toPx(), StrokeCap.Round)
            drawLine(tint, Offset(w * 0.229f, h * 0.654f), Offset(w * 0.596f, h * 0.463f), stroke.toPx(), StrokeCap.Round)
        }
        // «kr» written out: the krone has no sign of its own, and a flag is a country.
        AssetGlyph.KRONE -> {
            drawLine(tint, Offset(w * 0.225f, h * 0.204f), Offset(w * 0.225f, h * 0.796f), stroke.toPx(), StrokeCap.Round)
            drawLine(tint, Offset(w * 0.471f, h * 0.392f), Offset(w * 0.233f, h * 0.592f), stroke.toPx(), StrokeCap.Round)
            drawLine(tint, Offset(w * 0.338f, h * 0.504f), Offset(w * 0.488f, h * 0.796f), stroke.toPx(), StrokeCap.Round)
            drawLine(tint, Offset(w * 0.613f, h * 0.458f), Offset(w * 0.613f, h * 0.796f), stroke.toPx(), StrokeCap.Round)
            drawPath(
                Path().apply {
                    moveTo(w * 0.613f, h * 0.579f)
                    cubicTo(w * 0.629f, h * 0.504f, w * 0.692f, h * 0.458f, w * 0.775f, h * 0.467f)
                },
                tint,
                style = ink,
            )
        }
        // The mark the UAE gave the dirham in 2025: a D crossed twice, the way the euro is.
        AssetGlyph.DIRHAM -> {
            drawLine(tint, Offset(w * 0.321f, h * 0.175f), Offset(w * 0.321f, h * 0.825f), stroke.toPx(), StrokeCap.Round)
            drawPath(
                Path().apply {
                    moveTo(w * 0.321f, h * 0.175f)
                    lineTo(w * 0.442f, h * 0.175f)
                    cubicTo(w * 0.646f, h * 0.175f, w * 0.788f, h * 0.308f, w * 0.788f, h * 0.5f)
                    cubicTo(w * 0.788f, h * 0.692f, w * 0.646f, h * 0.825f, w * 0.442f, h * 0.825f)
                    lineTo(w * 0.321f, h * 0.825f)
                },
                tint,
                style = ink,
            )
            drawLine(tint, Offset(w * 0.175f, h * 0.404f), Offset(w * 0.733f, h * 0.404f), stroke.toPx(), StrokeCap.Round)
            drawLine(tint, Offset(w * 0.175f, h * 0.596f), Offset(w * 0.733f, h * 0.596f), stroke.toPx(), StrokeCap.Round)
        }
        // $ is taken, and one mark must not mean two monies. A maple leaf was drawn and tried
        // first: sixteen short outline segments fuse into a flower at real size — what
        // survives being small is the silhouette, and an outline leaf has none. C$ is the
        // notation the currency actually goes by, in the same two-letterform anatomy the
        // krone's «kr» already proved out at 24.
        AssetGlyph.CDOLLAR -> {
            drawPath(
                Path().apply {
                    arcTo(Rect(w * 0.133f, h * 0.321f, w * 0.492f, h * 0.679f), -45f, -270f, true)
                },
                tint,
                style = ink,
            )
            drawLine(tint, Offset(w * 0.708f, h * 0.258f), Offset(w * 0.708f, h * 0.792f), stroke.toPx(), StrokeCap.Round)
            drawPath(
                Path().apply {
                    moveTo(w * 0.842f, h * 0.333f)
                    lineTo(w * 0.654f, h * 0.333f)
                    arcTo(Rect(w * 0.558f, h * 0.333f, w * 0.75f, h * 0.525f), 270f, -180f, false)
                    lineTo(w * 0.758f, h * 0.525f)
                    arcTo(Rect(w * 0.663f, h * 0.525f, w * 0.854f, h * 0.717f), 270f, 180f, false)
                    lineTo(w * 0.575f, h * 0.717f)
                },
                tint,
                style = ink,
            )
        }
        // Wider at the base — the basket is the trapezoid that opens the other way.
        AssetGlyph.INGOT -> {
            drawPath(
                Path().apply {
                    moveTo(w * 0.338f, h * 0.321f)
                    lineTo(w * 0.663f, h * 0.321f)
                    lineTo(w * 0.813f, h * 0.679f)
                    lineTo(w * 0.188f, h * 0.679f)
                    close()
                },
                tint,
                style = ink,
            )
            drawLine(tint, Offset(w * 0.425f, h * 0.5f), Offset(w * 0.575f, h * 0.5f), stroke.toPx(), StrokeCap.Round)
        }
        // Silver shares gold's disc colour, so shape is the only difference it gets: two flat
        // sheets where gold is one deep ingot, a pairing read at list distance. A full stroke
        // of daylight between them, for the reason the coin's rim needed one.
        AssetGlyph.SLABS -> {
            drawPath(
                Path().apply {
                    moveTo(w * 0.371f, h * 0.263f)
                    lineTo(w * 0.629f, h * 0.263f)
                    lineTo(w * 0.692f, h * 0.429f)
                    lineTo(w * 0.308f, h * 0.429f)
                    close()
                },
                tint,
                style = ink,
            )
            drawPath(
                Path().apply {
                    moveTo(w * 0.288f, h * 0.571f)
                    lineTo(w * 0.713f, h * 0.571f)
                    lineTo(w * 0.783f, h * 0.738f)
                    lineTo(w * 0.217f, h * 0.738f)
                    close()
                },
                tint,
                style = ink,
            )
        }
        // One coin face-on with its rim. Two coins is the دارایی tab itself, and the rim well
        // inside the edge is what keeps this off the budget tab's donut, whose ring sits at
        // half. The first rim sat 2.2 units in — with a 2-unit stroke that is a fifth of a
        // unit of daylight, and the two circles fused into one fat ring on a real screen.
        AssetGlyph.COIN -> {
            drawCircle(tint, w * 0.371f, Offset(w * 0.5f, h * 0.5f), style = ink)
            drawCircle(tint, w * 0.229f, Offset(w * 0.5f, h * 0.5f), style = ink)
        }
        // A surveyed قطعه with a sprout on it: the asset, not the landscape.
        AssetGlyph.PLOT -> {
            drawPath(
                Path().apply {
                    moveTo(w * 0.179f, h * 0.746f)
                    lineTo(w * 0.333f, h * 0.529f)
                    lineTo(w * 0.821f, h * 0.529f)
                    lineTo(w * 0.667f, h * 0.746f)
                    close()
                },
                tint,
                style = ink,
            )
            drawLine(tint, Offset(w * 0.467f, h * 0.529f), Offset(w * 0.467f, h * 0.388f), stroke.toPx(), StrokeCap.Round)
            drawPath(
                Path().apply {
                    moveTo(w * 0.467f, h * 0.388f)
                    cubicTo(w * 0.388f, h * 0.396f, w * 0.333f, h * 0.35f, w * 0.325f, h * 0.267f)
                    cubicTo(w * 0.404f, h * 0.258f, w * 0.458f, h * 0.304f, w * 0.467f, h * 0.388f)
                    close()
                },
                tint,
                style = ink,
            )
            drawPath(
                Path().apply {
                    moveTo(w * 0.467f, h * 0.388f)
                    cubicTo(w * 0.475f, h * 0.304f, w * 0.529f, h * 0.258f, w * 0.608f, h * 0.267f)
                    cubicTo(w * 0.6f, h * 0.35f, w * 0.546f, h * 0.396f, w * 0.467f, h * 0.388f)
                    close()
                },
                tint,
                style = ink,
            )
        }
        // Handled above by delegation; kept exhaustive so a new entry cannot compile unmapped.
        AssetGlyph.WHEEL, AssetGlyph.HOUSE, AssetGlyph.CHART -> Unit
    }
}
