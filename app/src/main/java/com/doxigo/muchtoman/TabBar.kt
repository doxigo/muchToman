package com.doxigo.muchtoman

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.inset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The five places the app goes, and the way back from any of them.
 *
 * Before this there was no way home except the system back button: every screen was an overlay
 * with a «بستن» in the corner, and overlays over overlays meant she could get somewhere she
 * could not get out of. A bar that is always there is not only tidier, it is the fix.
 *
 * Goals and the companion ledger are roots because they hold things she manages. The report is
 * a read-only drill-down from the total on Home, so it does not take a permanent slot here.
 *
 * ## Why it looks like this
 *
 * An island, not a floor. A bar welded edge to edge is a wall the page ends against; the bars
 * worth copying float clear of both edges and let the page run underneath, so the phone reads
 * as one continuous surface with a control resting on it.
 *
 * There is exactly one loud thing on it: the gold slug under the tab she is on. Everything
 * else — the slab, the labels, the glyphs — is held quiet so that one shape is unmissable
 * across the room, which is the only job this bar has. Gold is already the app's colour for
 * "this is the answer"; here it answers *where am I*.
 */
enum class Tab(val fa: String) {
    HOME("خانه"),
    LEDGER("دفتر"),
    GOALS("هدف‌ها"),
    ASSETS("دارایی"),
    COMPANION("همراه"),
}

/**
 * The places *this edition* goes — the whole difference between the two APKs, in one line.
 *
 * The lite build is دارایی and nothing else, which is the app as it shipped through v1.0.4: a
 * list of what you own and what it is worth today. So it is a list of one, [TabBar] draws nothing
 * for a list of one, and the four screens the other entries lead to are simply never reachable.
 *
 * Every `tab = …` in the app has to go through a member of this list, or the lite build can be
 * navigated somewhere it has no way out of. There is exactly one such assignment that could
 * ([Tab.COMPANION], from a pairing deep link), and it is guarded at the source.
 */
val tabs: List<Tab> = if (BuildConfig.LITE) listOf(Tab.ASSETS) else Tab.entries

@Composable
fun TabBar(
    selected: Tab,
    ledgerBadge: Int,
    onSelect: (Tab) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            // The island's own ground. It floats, but not over a hole: the screens that take
            // the bar's height as an inset and draw under it would otherwise show a list
            // scrolling through the strip beside the island.
            .background(MaterialTheme.colorScheme.background)
            .navigationBarsPadding()
            .padding(horizontal = Space.m, vertical = Space.s),
        shape = RoundedCornerShape(Radius.group),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        // Lifted, not outlined. A hairline would put a fifth line weight on a bar that already
        // carries four; a real shadow says "on top of" without adding one.
        shadowElevation = 12.dp,
    ) {
        NavigationBar(
            // The island owns the shape, the colour and the gesture inset, so the component is
            // asked for its behaviour only: the sliding slug, the ripple, the tab semantics.
            containerColor = Color.Transparent,
            tonalElevation = 0.dp,
            windowInsets = WindowInsets(0),
            modifier = Modifier.height(72.dp),
        ) {
            for (tab in tabs) {
                val badge = if (tab == Tab.LEDGER) ledgerBadge else 0
                NavigationBarItem(
                    selected = tab == selected,
                    onClick = { onSelect(tab) },
                    icon = {
                        BadgedBox(
                            badge = {
                                if (badge > 0) {
                                    // Gold again, and deliberately: the slug says where she is,
                                    // the slug-coloured dot says where she is wanted next. Two
                                    // sizes of one idea reads as a system. Red would read as a
                                    // fault, and twelve receipts to sort is not one.
                                    Badge(
                                        containerColor = MaterialTheme.colorScheme.primary,
                                        contentColor = MaterialTheme.colorScheme.onPrimary,
                                    ) {
                                        Text(
                                            faNumber(badge.coerceAtMost(99).toDouble()),
                                            style = figureStyle(MaterialTheme.colorScheme.onPrimary),
                                            fontSize = 10.sp,
                                        )
                                    }
                                }
                            },
                        ) {
                            // The item hands its animated selected/unselected colour down
                            // through LocalContentColor, so the drawn glyph tints with it.
                            val tint = LocalContentColor.current
                            Canvas(
                                Modifier
                                    .size(24.dp)
                                    // Its own layer, so the two glyphs that cut a hole in
                                    // themselves cut it in the glyph and not in the slug
                                    // underneath. See [knockOut].
                                    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen },
                            ) { drawTabIcon(tab, tint) }
                        }
                    },
                    label = { Text(tab.fa, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    colors = NavigationBarItemDefaults.colors(
                        // Full-strength gold, not the container tint the component defaults to:
                        // that default is a brown wash on this palette, and a wash is exactly
                        // what a "you are here" mark must not be.
                        indicatorColor = MaterialTheme.colorScheme.primary,
                        selectedIconColor = MaterialTheme.colorScheme.onPrimary,
                        selectedTextColor = MaterialTheme.colorScheme.primary,
                        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                )
            }
        }
    }
}

/**
 * Drawn, not imported.
 *
 * The icons artifact this project pins is the core set and has no house, no receipt, no coin
 * and no second device, so four of the five would have had to be drawn anyway — and four drawn
 * glyphs beside one imported one is the mismatch you notice without being able to name.
 *
 * What makes them a family is mechanical, and it is what the first cut got wrong: one pen
 * ([pen]) for all five, and one 18dp box inside the 24dp canvas that no glyph is allowed to
 * touch. The old set drew at four different stroke weights and ran to the edge of its box,
 * which is why it read as rough against the slug's curve.
 */
private fun DrawScope.drawTabIcon(tab: Tab, tint: Color) {
    inset(3.dp.toPx()) {
        when (tab) {
            Tab.HOME -> drawHome(tint)
            Tab.LEDGER -> drawLedger(tint)
            Tab.GOALS -> drawGoals(tint)
            Tab.ASSETS -> drawAssets(tint)
            Tab.COMPANION -> drawCompanion(tint)
        }
    }
}

/**
 * The one pen. Round on every cap and every corner, so nothing in the set ends in a chisel.
 *
 * Thinner for the category glyphs ([CategoryIcon]), which draw into an 18dp box rather than a
 * 24dp one: the same nib in a smaller box is a heavier drawing, and heavier is what a mark
 * beside 14sp type must not be.
 */
internal fun DrawScope.pen(width: Dp = 2.dp) = Stroke(
    width = width.toPx(),
    cap = StrokeCap.Round,
    join = StrokeJoin.Round,
)

/**
 * How far a knocked-out hole reaches past the near shape it belongs to: half a stroke to swallow
 * the near outline's own width, then a stroke of daylight. Two outlines that pass within less
 * than that read as interlocked rather than as one in front of the other, which is the whole
 * illusion these two glyphs are built on.
 */
private fun DrawScope.knockOut() = 1.dp.toPx() + 1.4.dp.toPx()

private fun DrawScope.drawHome(tint: Color) {
    val w = size.width
    val h = size.height
    // One closed outline rather than a roof floating over a box: the old glyph left a gap
    // between the two, and at this size a gap reads as a mistake rather than as depth.
    val walls = Path().apply {
        moveTo(w * 0.04f, h * 0.44f)
        lineTo(w * 0.5f, h * 0.04f)
        lineTo(w * 0.96f, h * 0.44f)
        lineTo(w * 0.96f, h * 0.96f)
        lineTo(w * 0.04f, h * 0.96f)
        close()
    }
    drawPath(walls, tint, style = pen())
    // An arched door. A rectangle would have done the same work, but the arch is the one line
    // in the set that belongs to this app rather than to every icon sheet on the shelf.
    val door = Path().apply {
        moveTo(w * 0.34f, h * 0.96f)
        lineTo(w * 0.34f, h * 0.76f)
        quadraticTo(w * 0.5f, h * 0.56f, w * 0.66f, h * 0.76f)
        lineTo(w * 0.66f, h * 0.96f)
    }
    drawPath(door, tint, style = pen())
}

/**
 * Ruled lines, shortest at the top.
 *
 * A list icon normally runs longest-first. This one is upside down on purpose: the review count
 * lands on the top corner nearest the screen edge — the start corner, which in Persian is the
 * left — and a full-width top rule is precisely what that badge would sit on top of. Turning
 * the stack over hands the badge an empty corner instead of fighting it for one.
 */
private fun DrawScope.drawLedger(tint: Color) {
    val rows = listOf(0.4f, 0.72f, 1f)
    for ((i, len) in rows.withIndex()) {
        val y = size.height * (0.16f + i * 0.34f)
        drawLine(
            tint,
            Offset(size.width * (1f - len), y),
            Offset(size.width, y),
            strokeWidth = 2.dp.toPx(),
            cap = StrokeCap.Round,
        )
    }
}

private fun DrawScope.drawGoals(tint: Color) {
    val centre = Offset(size.width / 2f, size.height / 2f)
    val r = size.minDimension / 2f
    drawCircle(tint, radius = r * 0.88f, center = centre, style = pen())
    drawCircle(tint, radius = r * 0.44f, center = centre, style = pen())
    drawCircle(tint, radius = r * 0.1f, center = centre)
}

/**
 * Two coins, the near one in front of the far one.
 *
 * Three attempts to draw this as a stack seen from the side all failed for the same reason:
 * an ellipse with arcs under it is the database glyph, and no amount of spacing talks a reader
 * out of a shape they already know. Face-on, a coin is a disc, and two discs with one occluding
 * the other is money in every icon set there is — and it borrows the same occlusion the
 * companion cards use, which is what makes those two read as one hand.
 */
private fun DrawScope.drawAssets(tint: Color) {
    val r = size.minDimension * 0.3f
    val near = Offset(size.width * 0.36f, size.height * 0.64f)
    drawCircle(tint, r, Offset(size.width * 0.64f, size.height * 0.36f), style = pen())
    drawCircle(Color.Black, r + knockOut(), near, blendMode = BlendMode.Clear)
    drawCircle(tint, r, near, style = pen())
}

/**
 * Her phone, and the one she set up for someone else.
 *
 * Two whole rounded rectangles overlapping is what this was, and two strokes crossing at 24dp
 * is a blob. Same fix as the coins: the near card cuts its own silhouette out of the far one,
 * so the far card is genuinely behind rather than tangled with it.
 */
private fun DrawScope.drawCompanion(tint: Color) {
    val w = size.width
    val h = size.height
    val card = Size(w * 0.7f, h * 0.7f)
    val radius = CornerRadius(w * 0.16f)
    val near = Offset(w * 0.02f, h * 0.28f)
    val cut = knockOut()
    drawRoundRect(tint, Offset(w * 0.28f, h * 0.02f), card, radius, style = pen())
    drawRoundRect(
        Color.Black,
        topLeft = Offset(near.x - cut, near.y - cut),
        size = Size(card.width + cut * 2f, card.height + cut * 2f),
        cornerRadius = CornerRadius(radius.x + cut),
        blendMode = BlendMode.Clear,
    )
    drawRoundRect(tint, near, card, radius, style = pen())
}
