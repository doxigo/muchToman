package com.doxigo.muchtoman

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
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
 * Goals is a root because it holds things she manages. The report is here because "بیشتر شده یا
 * کمتر؟" is a question she asks as often as "چقدر دارم؟", and as an overlay every door into it
 * was on خانه — a screen she has to be standing on to remember the report exists at all.
 *
 * The household is not here. It is the one thing on this list she sets up once and then never
 * opens again, and a slot on this bar is rent paid every day; it lives at the top of تنظیمات
 * now, which is where the other once-and-done things already are.
 *
 * ## Why it looks like this
 *
 * An island, not a floor — clear of both edges, the page running underneath. And translucent,
 * which is Telegram's move: enough of the list ghosts through the slab to read "resting on
 * the page" rather than "end of the page", and the shadow holds the edge the alpha gives up.
 * A green-field version of this bar existed for a day; it made the bar a second hero, and an
 * app gets one face. The bar is furniture.
 *
 * Compact on purpose, which is why it is drawn by hand. Material's bars are 80 and 64dp tall
 * and will not be talked below it; Telegram's is ~56, a glyph and a word with no shelf under
 * them, and five words she reads every day do not need more. The pill still measures exactly
 * what M3's indicator measures (56×32), so the one loud mark keeps its licensed proportions.
 *
 * There is exactly one loud thing on it: the pill under the tab she is on — gold in the dark,
 * the deep green in the light. Everything else — the slab, the labels, the glyphs — is held
 * quiet so that one shape is unmissable across the room, which is the only job this bar has.
 * That colour is already the app's colour for "this is the answer"; here it answers *where am I*.
 */
enum class Tab(val fa: String) {
    HOME("خانه"),
    LEDGER("دفتر"),
    GOALS("هدف‌ها"),
    ASSETS("دارایی"),
    REPORT("گزارش"),
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
 * ([Tab.REPORT], from the گزارش button on the دارایی field), and the screen it lands on keeps
 * its own «برگشت» for precisely the edition that has no bar to leave by.
 */
val tabs: List<Tab> = if (BuildConfig.LITE) listOf(Tab.ASSETS) else Tab.entries

@Composable
fun TabBar(
    selected: Tab,
    ledgerBadge: Int,
    onSelect: (Tab) -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(Radius.group)
    Row(
        modifier
            .navigationBarsPadding()
            .padding(horizontal = Space.m, vertical = Space.s)
            // Lifted, not outlined. A hairline would put a fifth line weight on a bar that
            // already carries four; a real shadow says "on top of" without adding one — and
            // over a moving list it is the shadow, not the outline, that reads as height.
            .shadow(12.dp, shape)
            // 0.92, not lower: translucency here is a hint, not a window. Any thinner and the
            // labels start competing with whatever row happens to be passing underneath.
            .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.92f), shape)
            .height(56.dp)
            .selectableGroup(),
    ) {
        for (tab in tabs) {
            val here = tab == selected
            // Not on the tab she is standing on: there the same count is already on screen,
            // in the «مرور ۱۲ مورد» pill at the top of the ledger. A 56dp bar has no room
            // to hang a numbered chip clear of a 32dp pill, and the version that tried put
            // it *inside* the pill — a hole punched in the one mark that must stay whole.
            val badge = if (tab == Tab.LEDGER && !here) ledgerBadge else 0
            // One animator per channel, so a tab change is a crossfade rather than a jump —
            // the whole of what NavigationBarItem was being paid for.
            val pill by animateColorAsState(
                if (here) MaterialTheme.colorScheme.primary else Color.Transparent,
                label = "pill",
            )
            val glyph by animateColorAsState(
                if (here) MaterialTheme.colorScheme.onPrimary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                label = "glyph",
            )
            val ink by animateColorAsState(
                if (here) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                label = "ink",
            )
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .selectable(selected = here, role = Role.Tab) { onSelect(tab) },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                BadgedBox(
                    badge = {
                        if (badge > 0) {
                            // The pill's own colour, deliberately: the pill says where she is,
                            // the chip says where she is wanted next. Two sizes of one idea
                            // reads as a system. Red would read as a fault, and twelve
                            // receipts to sort is not one.
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
                    Box(
                        // M3's exact indicator: 24dp glyph + 16dp beside, 4dp above = 56×32.
                        Modifier
                            .background(pill, RoundedCornerShape(Radius.pill))
                            .padding(horizontal = Space.l, vertical = Space.xs),
                    ) {
                        Canvas(
                            Modifier
                                .size(24.dp)
                                // Its own layer, so the two glyphs that cut a hole in
                                // themselves cut it in the glyph and not in the pill
                                // underneath. See [knockOut].
                                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen },
                        ) { drawTabIcon(tab, glyph) }
                    }
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    tab.fa,
                    fontSize = 11.sp,
                    lineHeight = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    // The chosen one is the only bold one, which is the rule every other
                    // "pick exactly one" in the app already follows (SegmentedChoice).
                    // Colour alone would leave the mark to a single channel.
                    fontWeight = if (here) FontWeight.ExtraBold else FontWeight.Medium,
                    color = ink,
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
            Tab.REPORT -> drawReport(tint)
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
 * A receipt: a slip with a torn foot and two rules on it.
 *
 * This was three ruled lines, and three ruled lines is the menu glyph — every icon sheet on the
 * shelf uses it for "a list of anything", which is precisely the wrong word for a book of money.
 * They were also stacked shortest-first to hand the review badge an empty corner, and the badge
 * landed on the short rule anyway. So: a shape with a noun. The torn foot is the whole tell —
 * cover it and this is a document; show it and it is a receipt — and the badge now sits over a
 * corner, the way a badge sits over every other icon ever drawn.
 */
private fun DrawScope.drawLedger(tint: Color) {
    val w = size.width
    val h = size.height
    // Nearly the full box. Narrower, it read as a ruler: a tall thin outline with rungs in it.
    val left = w * 0.06f
    val right = w * 0.94f
    val top = h * 0.08f
    val foot = h * 0.92f
    val teeth = 4
    val step = (right - left) / teeth
    val slip = Path().apply {
        moveTo(left, foot)
        lineTo(left, top)
        lineTo(right, top)
        lineTo(right, foot)
        for (i in 0 until teeth) {
            val x = right - step * i
            lineTo(x - step / 2f, foot - h * 0.11f)
            lineTo(x - step, foot)
        }
    }
    drawPath(slip, tint, style = pen())
    // Two rules, the lower one short — a line item and its amount, not a paragraph.
    for ((i, len) in listOf(1f, 0.55f).withIndex()) {
        val y = h * (0.34f + i * 0.19f)
        val inset = (right - left) * 0.16f
        drawLine(
            tint,
            Offset(left + inset, y),
            Offset(left + inset + (right - left - inset * 2f) * len, y),
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
    // The radius and the separation are load-bearing, and the first pairing had them wrong.
    // The near coin clears a hole of r + knockOut around itself; at r = 0.30 and centres 0.28
    // apart that hole was 7.8dp against a 7.1dp separation, so it reached past the far coin's
    // own centre and the two discs fused into one continuous curve. It drew a «۶», not money.
    // Smaller and further apart: the hole now stops well short, and the far coin keeps the
    // three quarters of its outline that make it legible as a second coin.
    val r = size.minDimension * 0.26f
    val near = Offset(size.width * 0.32f, size.height * 0.68f)
    drawCircle(tint, r, Offset(size.width * 0.68f, size.height * 0.32f), style = pen())
    drawCircle(Color.Black, r + knockOut(), near, blendMode = BlendMode.Clear)
    drawCircle(tint, r, near, style = pen())
}

/**
 * Three rising bars, in the pen.
 *
 * The same three bars [BarsIcon] draws for the گزارش button on the field, and deliberately not
 * the same drawing: that one is filled rounded rects, because it stands beside two filled
 * Material glyphs on the hero. Here it stands beside four stroked ones. A shape belongs to the
 * family it sits in, and the family here is the pen — a solid glyph among four outlines is the
 * one that looks like it was pasted in from somewhere else.
 *
 * Rising to the right, matching the report's own chart, where time runs left to right and the
 * newest point is the right-hand end regardless of the page's direction.
 */
private fun DrawScope.drawReport(tint: Color) {
    val w = size.width
    val h = size.height
    val stroke = pen()
    // Inset half a stroke top and bottom, so the round caps land inside the box rather than
    // half outside it — the same reason nothing else in this set draws to its own edge.
    val cap = stroke.width / 2f
    for ((i, top) in listOf(0.55f, 0.28f, 0.0f).withIndex()) {
        val x = w * (0.14f + i * 0.36f)
        drawLine(
            tint,
            Offset(x, h * top + cap),
            Offset(x, h - cap),
            strokeWidth = stroke.width,
            cap = StrokeCap.Round,
        )
    }
}
