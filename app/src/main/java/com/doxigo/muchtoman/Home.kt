package com.doxigo.muchtoman

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.round

/**
 * The home screen's answer, under the total.
 *
 * Two groups and then it stops: the ledger's (the month, at most two sentences about it, the
 * newest rows) and the total's (against the dollar, and what it is made of). The old home put
 * twelve holdings under the number; the portfolio is still one tap away, but what she opens the
 * app for is the story, not the inventory — every card here is a reading, never a list to manage.
 *
 * Nothing here computes anything. Every figure arrives already worked out by [buildStory], so
 * what she reads and what the tests check are the same function.
 */

/**
 * What came in against what went out, as one object rather than two.
 *
 * They were two identical cards side by side, and two figures sitting next to each other do not
 * compare themselves — she had to do the arithmetic to know which month she was having. The bar
 * is the comparison: two lengths off the same baseline, so the answer arrives before either
 * number is read. Nothing new is computed for it; it is the two figures already above it.
 */
@Composable
fun MonthFlow(story: HomeStory, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val month = story.month
    val gain = MaterialTheme.colorScheme.tertiary
    val spend = MaterialTheme.colorScheme.onSurfaceVariant
    val income = tomanOf(month.incomeRial)
    val spent = tomanOf(month.spentRial)
    // Where the خرج went, three deep: the report's own category rows, so «where did it go?» is
    // answered on the glance surface and the long list stays one tap away.
    val top = if (month.spentRial > 0) month.spendingByCategory.take(3) else emptyList()
    HomeCard(
        title = "این ماه",
        // One accessibility node, not five: read out separately these are loose numbers and
        // labels; together they are the sentence the eye gets in a glance.
        description = "این ماه: درآمد ${faCompact(income)} $moneyUnit، خرج ${faCompact(spent)} $moneyUnit" +
            top.joinToString("") { (name, rial) -> "، $name ${faCompact(tomanOf(rial))} $moneyUnit" } +
            ". برای گزارش کامل باز کن",
        // The card is the cash-flow report's own opening paragraph, so the card is the door to
        // the rest of it — tapping the summary for the long form needs no explaining.
        onOpen = onOpen,
        modifier = modifier,
    ) {
        Row(Modifier.fillMaxWidth()) {
            FlowHalf("درآمد", month.incomeRial, gain, Modifier.weight(1f))
            Spacer(Modifier.width(Space.m))
            FlowHalf("خرج", month.spentRial, MaterialTheme.colorScheme.onSurface, Modifier.weight(1f))
        }
        if (month.incomeRial > 0 || month.spentRial > 0) {
            Spacer(Modifier.height(Space.l))
            FlowBar(month.incomeRial, month.spentRial, gain, spend)
        }
        if (top.isNotEmpty()) {
            Spacer(Modifier.height(Space.l))
            Text("بیشترین خرج‌ها", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            // Said once, in the card's sentence above; each row speaking for itself as well would
            // read every category twice.
            Column(Modifier.clearAndSetSemantics {}) {
                for ((name, rial) in top) {
                    CategoryShare(name, rial, month.spentRial, tint = null, track = MaterialTheme.colorScheme.background)
                }
            }
        }
    }
}

/**
 * The make every card on خانه shares: `surfaceVariant`, [Radius.card], a 15sp title with the
 * chevron that says the card is a door, and one spoken sentence for the whole of it.
 */
@Composable
private fun HomeCard(
    title: String,
    description: String,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.card))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(role = Role.Button, onClick = onOpen)
            .padding(Space.l)
            .semantics(mergeDescendants = true) { contentDescription = description },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                title,
                fontSize = 15.sp,
                fontWeight = FontWeight.ExtraBold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Icon(
                Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
        Spacer(Modifier.height(Space.m))
        content()
    }
}

/**
 * A card's answer, before its evidence: the figure at [FlowHalf]'s size, the sentence it finishes
 * a step down beside it.
 */
@Composable
private fun LeadFigure(figure: String, tone: Color, tail: String, up: Boolean? = null) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        up?.let {
            TrendCaret(up = it, tint = tone, box = 10.dp)
            Spacer(Modifier.width(6.dp))
        }
        Text(figure, style = figureStyle(tone, FontWeight.ExtraBold), fontSize = 26.sp, maxLines = 1)
        Spacer(Modifier.width(Space.s))
        Text(
            tail,
            fontSize = 13.sp,
            lineHeight = 20.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f, fill = false),
        )
    }
}

/**
 * The comparison itself: two lengths off one baseline, in whichever two colours the surface
 * under them calls for.
 *
 * **The shares are floored, and that is the point of the function.** Weighted by the raw
 * figures, ۲٫۵ میلیون beside ۱۰۰ میلیون is two per cent of the row — which at 8dp tall draws as
 * a dot, and a dot reads as *nothing here* rather than as *a little*. Both exact figures are
 * stated in full directly above, so the floor costs no honesty and buys back the one thing the
 * bar exists for: seeing which is the larger before reading either.
 */
@Composable
private fun FlowBar(incomeRial: Long, spentRial: Long, income: Color, spend: Color) {
    if (incomeRial <= 0 && spentRial <= 0) return
    // A gap and two pills, not one split bar: joined, the two shares read as a single quantity
    // filling up rather than as two amounts being weighed.
    Row(
        Modifier.fillMaxWidth().height(8.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        val share = when {
            incomeRial <= 0 -> 0f
            spentRial <= 0 -> 1f
            else -> (incomeRial.toFloat() / (incomeRial + spentRial))
                .coerceIn(FLOW_FLOOR, 1f - FLOW_FLOOR)
        }
        if (incomeRial > 0) {
            Box(
                Modifier
                    .weight(share)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(Radius.pill))
                    .background(income),
            )
        }
        if (spentRial > 0) {
            Box(
                Modifier
                    .weight(1f - share)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(Radius.pill))
                    .background(spend),
            )
        }
    }
}

/** The shortest a segment may be drawn while still reading as a bar rather than a dot. */
private const val FLOW_FLOOR = 0.06f

@Composable
private fun FlowHalf(label: String, rial: Long, tone: Color, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(Space.xs))
        // Must not wrap and must not shrink the pair out of step, so both halves step down
        // together from the same ceiling.
        BasicText(
            text = bidi(faCompact(tomanOf(rial))),
            maxLines = 1,
            autoSize = TextAutoSize.StepBased(minFontSize = 16.sp, maxFontSize = 26.sp),
            style = figureStyle(tone, FontWeight.ExtraBold),
        )
    }
}

/**
 * One statement, and — only where there is somewhere to go — the control that goes there.
 *
 * «چرا این را می‌بینم؟» is not a nicety: a number she cannot trace is a number she has to take on
 * faith. But every card asking «این عدد از کجا اومده؟» was the wrong way to answer it — on the
 * report the question did nothing at all, and on the home screen it made her tap for a sentence
 * we could simply say. [why] says it, unasked. The pill is left to the one card that actually
 * leaves the screen, filled in the same brand green the ledger and the tab bar use for that;
 * [action] names where it goes.
 */
@Composable
fun InsightCard(
    insight: Insight,
    modifier: Modifier = Modifier,
    why: Boolean = false,
    action: String? = null,
    onAction: () -> Unit = {},
) {
    val accent = when (insight.tone) {
        Insight.Tone.GOOD -> MaterialTheme.colorScheme.tertiary
        // Not `tertiary`: in dark theme that is the same green income speaks in, so the one
        // thing asking for her read as a deposit.
        Insight.Tone.ATTENTION -> MaterialTheme.colorScheme.primary
        Insight.Tone.NEUTRAL -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Panel(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(accent),
            )
            Spacer(Modifier.width(Space.s))
            Text(
                insight.text,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
                lineHeight = 26.sp,
            )
        }
        if (why) {
            Spacer(Modifier.height(Space.xs))
            Text(
                insight.why,
                fontSize = 12.sp,
                lineHeight = 20.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        action?.let {
            Spacer(Modifier.height(Space.xs))
            PillButton(it, onAction, voice = ButtonVoice.PRIMARY)
        }
    }
}

/**
 * The narrowest a card may be drawn and still be read as a sentence rather than as a column of
 * words. Sixteen of the card's width is the dot and its gap and thirty-two is the panel's own
 * padding, so the sentence itself gets what is left.
 */
private val PAIRABLE = 168.dp

/**
 * Insights two to a row — wherever two of them fit.
 *
 * One short sentence per full-width card was a column of mostly empty space, and the report had
 * eight of them to scroll past. The row measures to the taller of its pair and both cards fill
 * it, so a long sentence beside a short one still reads as two cards and not as a step.
 *
 * On a small phone the pair stops paying for itself: at 320dp of screen each column leaves the
 * sentence about eighty to say itself in, which wraps every two words and turns a two-line
 * finding into ten — the tall, narrow, hyphen-less columns Persian handles worst. So the grid
 * measures the width it was actually given rather than assuming a pair will fit in it, and drops
 * to one card per row before it shrinks a card past [PAIRABLE].
 */
@Composable
fun InsightGrid(insights: List<Insight>, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val columns = if ((maxWidth - Space.m) / 2 >= PAIRABLE) 2 else 1
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space.m)) {
            for (row in insights.chunked(columns)) {
                Row(
                    Modifier.height(IntrinsicSize.Min),
                    horizontalArrangement = Arrangement.spacedBy(Space.m),
                ) {
                    for (insight in row) {
                        InsightCard(insight, Modifier.weight(1f).fillMaxHeight(), why = true)
                    }
                    // An odd count leaves the last card in its half rather than stretched across
                    // a row of its own: the grid keeps one edge to read down.
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

/** Before there is any history, say so plainly rather than showing a zero. */
@Composable
fun QuietStart(
    smsEnabled: Boolean,
    modifier: Modifier = Modifier,
    /** The window the claim is about — home's is the month, the report's is whatever it reads. */
    named: String = "این ماه",
) {
    Panel(modifier) {
        Text(
            if (smsEnabled) "هنوز برای $named تراکنشی ثبت نشده" else "پیامک‌های بانکی خاموشن",
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(Space.xs))
        Text(
            if (smsEnabled) {
                "اولین پیامک بانکی که برسه، اینجا خودش پر می‌شه."
            } else {
                "از تنظیمات روشنشون کن تا دفترت خودش پر بشه."
            },
            fontSize = 13.sp,
            lineHeight = 22.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Whether she is any richer, or the dollar simply went up: the month's change in Toman beside the
 * dollar's own over the same thirty days, and what the two leave her with in dollars.
 *
 * The answer leads, the evidence follows: two lengths off one edge, hers in the bar colour the
 * report gives spending shares and the dollar's in the muted one, because the dollar is the yard
 * stick and not a second account. The verdict wears the asset report's colours, the report this
 * card opens — green for more, red for less, muted for none.
 */
@Composable
fun DollarLensCard(lens: DollarLens, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val flat = abs(lens.real) < 0.05
    val tone = when {
        flat -> MaterialTheme.colorScheme.onSurfaceVariant
        lens.real > 0 -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.error
    }
    val figure = if (flat) "بدون تغییر" else "${faDecimal(abs(lens.real), 1)}٪"
    val tail = when {
        flat -> "در ۳۰ روز گذشته"
        lens.real > 0 -> "بیشتر از ۳۰ روز پیش"
        else -> "کمتر از ۳۰ روز پیش"
    }
    HomeCard(
        title = "به دلار",
        description = "به دلار: $figure $tail. دارایی‌ات ${lensWords(lens.wealth)}، " +
            "نرخ دلار ${lensWords(lens.dollar)}. برای گزارش دارایی باز کن",
        onOpen = onOpen,
        modifier = modifier,
    ) {
        LeadFigure(figure, tone, tail, up = if (flat) null else lens.real > 0)
        Spacer(Modifier.height(Space.l))
        // One scale for both, so the longer bar is the bigger move whichever it belongs to.
        val top = maxOf(abs(lens.wealth), abs(lens.dollar))
        LensBar("دارایی‌ات", lens.wealth, top, MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(Space.s))
        LensBar("نرخ دلار", lens.dollar, top, MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun lensWords(percent: Double): String = when {
    abs(percent) < 0.05 -> "بدون تغییر"
    percent > 0 -> "${faDecimal(percent, 1)} درصد بیشتر"
    else -> "${faDecimal(-percent, 1)} درصد کمتر"
}

/** The shortest a lens bar is drawn while it still reads as a move rather than as none. */
private const val LENS_FLOOR = 0.04f

@Composable
private fun LensBar(label: String, percent: Double, top: Double, tint: Color) {
    val flat = abs(percent) < 0.05
    Row(verticalAlignment = Alignment.CenterVertically) {
        // A floor rather than a width, so the two tracks start on one edge and a large system
        // font can still widen the label.
        Text(
            label,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.widthIn(min = 64.dp),
        )
        Spacer(Modifier.width(Space.s))
        Box(
            Modifier
                .weight(1f)
                .height(8.dp)
                .clip(RoundedCornerShape(Radius.pill))
                .background(MaterialTheme.colorScheme.background),
        ) {
            if (!flat && top > 0.0) {
                Box(
                    Modifier
                        .fillMaxWidth((abs(percent) / top).toFloat().coerceIn(LENS_FLOOR, 1f))
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(Radius.pill))
                        .background(tint),
                )
            }
        }
        Spacer(Modifier.width(Space.s))
        Row(
            Modifier.widthIn(min = 56.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (!flat) {
                TrendCaret(up = percent > 0, tint = MaterialTheme.colorScheme.onSurfaceVariant, box = 8.dp)
                Spacer(Modifier.width(Space.xs))
            }
            Text(
                "${faDecimal(abs(percent), 1)}٪",
                style = figureStyle(MaterialTheme.colorScheme.onSurface, FontWeight.Bold),
                fontSize = 13.sp,
            )
        }
    }
}

/**
 * What the total is made of, largest first — the asset report's composition read at a glance,
 * led by the one share that matters most in a rial economy: how much of it is still Toman.
 * Absent with a single kind, where the bar would be the hero total said twice.
 */
@Composable
fun WhereMoneyIs(
    composition: List<Pair<Kind, Double>>,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    /** With today's dollar, what the Toman share lost over the lens's window — see [heldTomanCostUsd]. */
    lens: DollarLens? = null,
    usdNow: Double? = null,
) {
    val total = composition.sumOf { it.second }
    if (composition.size < 2 || total <= 0.0) return
    val parts = composition.sortedByDescending { it.second }
    val toman = composition.firstOrNull { it.first == Kind.CASH }?.second
    // The share's price: today's Toman read at the dollar of the lens's first day. Under a cent of
    // a percent it is noise, and a «۰ دلار» line would be a sentence about nothing.
    val cost = if (toman != null && lens != null && usdNow != null && usdNow > 0.0 && abs(lens.dollar) >= 0.05) {
        lens.heldTomanCostUsd(toman, usdNow).takeIf { abs(it) >= 1.0 }
    } else {
        null
    }
    val costWords = cost?.let {
        "با نرخ دلار ۳۰ روز پیش، همین پول تومنی ${bidi(faUsd(abs(it)))} ${if (it > 0) "بیشتر" else "کمتر"} می‌ارزید."
    }
    HomeCard(
        title = "پولت کجاست",
        description = "پولت کجاست: " +
            parts.joinToString("، ") { (kind, value) -> "${kind.fa} ${sharePercent(value / total)} درصد" } +
            ". " + (costWords?.let { "$it " } ?: "") + "برای گزارش دارایی باز کن",
        onOpen = onOpen,
        modifier = modifier,
    ) {
        toman?.let {
            LeadFigure("${sharePercent(it / total)}٪", MaterialTheme.colorScheme.onSurface, "از دارایی‌ات به تومانه")
            costWords?.let { words ->
                Text(
                    words,
                    fontSize = 13.sp,
                    lineHeight = 21.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = Space.xs),
                )
            }
            Spacer(Modifier.height(Space.l))
        }
        Row(
            Modifier
                .fillMaxWidth()
                .height(12.dp)
                .clip(RoundedCornerShape(Radius.pill)),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            parts.forEach { (kind, value) ->
                // Floored like the month's bar: a sliver under the gap between segments would
                // be a kind she holds drawn as no kind at all. The legend states the real share.
                Box(
                    Modifier
                        .weight((value / total).coerceAtLeast(0.015).toFloat())
                        .fillMaxHeight()
                        .background(barTint(kind)),
                )
            }
        }
        Spacer(Modifier.height(Space.m))
        Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
            for (row in parts.chunked(2)) {
                Row {
                    row.forEach { (kind, value) -> KindShare(kind, value / total, Modifier.weight(1f)) }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

/** A share as she would say it: whole percents, with a decimal only where the whole would be «۰». */
private fun sharePercent(share: Double): String =
    if (share < 0.01) faDecimal(share * 100, 1) else faNumber(round(share * 100))

@Composable
private fun KindShare(kind: Kind, share: Double, modifier: Modifier = Modifier) {
    Row(modifier.padding(vertical = 2.dp, horizontal = Space.xs), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(10.dp)
                .clip(RoundedCornerShape(Radius.pill))
                .background(barTint(kind)),
        )
        Spacer(Modifier.width(Space.s))
        Text(kind.fa, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f, fill = false))
        Spacer(Modifier.width(Space.s))
        Text(
            "${sharePercent(share)}٪",
            style = figureStyle(MaterialTheme.colorScheme.onSurfaceVariant, FontWeight.Bold),
            fontSize = 13.sp,
        )
    }
}

/**
 * The newest few rows of دفتر, on the paper as دفتر has them — activity flows, it does not sit in
 * a card. Each row opens its own transaction; the section head above is the door to the rest.
 *
 * Under a plain day name and never the ledger's day total: four rows are not a whole day, and a
 * figure summed over part of one would state a net the day never had.
 */
@Composable
fun RecentActivity(entries: List<LedgerEntry>, onOpen: (LedgerEntry) -> Unit, modifier: Modifier = Modifier) {
    if (entries.isEmpty()) return
    val today = rememberTehranDay()
    Column(modifier) {
        entries.groupBy { it.txn.day }.forEach { (day, rows) ->
            Text(
                faDay(day, today),
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(top = Space.s, start = Space.xs, end = Space.xs)
                    .semantics { heading() },
            )
            rows.forEach { entry -> TimelineRow(entry) { onOpen(entry) } }
        }
    }
}

/**
 * The head of one of خانه's sections: the tab's own mark from the bar, its name, and the chevron
 * that makes the three one door to that tab. A size over the cards' titles beneath it, so a section
 * reads as the container of its cards and not as another one of them. [trailing] is that tab's own
 * one act, where it has one worth bringing out — دفتر's review pill.
 */
@Composable
fun TabSectionHead(tab: Tab, onOpen: () -> Unit, modifier: Modifier = Modifier, trailing: @Composable () -> Unit = {}) {
    Row(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier
                .clip(RoundedCornerShape(Radius.pill))
                .clickable(role = Role.Button, onClickLabel = "${tab.fa} رو باز کن", onClick = onOpen)
                .heightIn(min = 48.dp)
                .padding(end = Space.s),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TabGlyph(tab, MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.width(Space.s))
            Text(
                tab.fa,
                fontSize = 18.sp,
                fontWeight = FontWeight.ExtraBold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.semantics { heading() },
            )
            Icon(
                Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
        trailing()
    }
}

/** How many of آینده's dues and caps خانه lists — the soonest and the furthest along. */
private const val FUTURE_ROWS = 4
private const val FUTURE_CAPS = 3

/**
 * آینده at a glance: what falls due soon — the cheques with their banks' logos as آینده draws
 * them, the next payment of each plan — and then the caps furthest along. Every row is a way into
 * آینده, the same door as the head over it.
 */
@Composable
fun FutureSummary(due: List<DueItem>, budgets: List<BudgetProgress>, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val today = rememberTehranDay()
    Column(modifier) {
        due.take(FUTURE_ROWS).forEach { DueRow(it, today, onOpen) }
        val caps = budgets.sortedByDescending { it.percent }.take(FUTURE_CAPS)
        if (caps.isNotEmpty()) {
            if (due.isNotEmpty()) Spacer(Modifier.height(Space.s))
            caps.forEach { BudgetLine(it, onOpen) }
        }
    }
}

/**
 * One due: what, when and how much. A warning — the cheque its account cannot cover, the payment
 * behind — takes the line under the name in ink, in the cheque card's own words, and the day moves
 * beside the figure in caution amber: آینده's rule, the sentence in ink and amber on the countdown.
 * Bold `error` only once the day has passed.
 */
@Composable
private fun DueRow(item: DueItem, today: Long, onOpen: () -> Unit) {
    val late = item.day < today
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.field))
            .clickable(role = Role.Button, onClick = onOpen)
            .padding(vertical = Space.s, horizontal = Space.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (item.bank != null) {
            BankLogo(item.bank, size = 36.dp)
        } else {
            Box(
                Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) { GlyphIcon(CategoryGlyph.INSTALMENT, MaterialTheme.colorScheme.onSurfaceVariant, size = 20.dp) }
        }
        Spacer(Modifier.width(Space.m))
        Column(Modifier.weight(1f)) {
            Text(
                item.title,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                item.warning ?: chequeWhenFa(item.day, today),
                fontSize = 13.sp,
                lineHeight = 20.sp,
                fontWeight = if (late && item.warning == null) FontWeight.Bold else FontWeight.Normal,
                color = when {
                    item.warning != null -> MaterialTheme.colorScheme.onSurface
                    late -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Spacer(Modifier.width(Space.s))
        Column(horizontalAlignment = Alignment.End) {
            Text(
                bidi(faCompact(tomanOf(item.rial))),
                style = figureStyle(MaterialTheme.colorScheme.onSurface, FontWeight.Bold),
                fontSize = 15.sp,
            )
            // With a warning on the line under the name, the day still has to be said somewhere.
            if (item.warning != null) {
                Text(
                    chequeWhenFa(item.day, today),
                    fontSize = 12.sp,
                    fontWeight = if (late) FontWeight.Bold else FontWeight.Normal,
                    color = if (late) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

/**
 * One cap, as far along as it is: the category's disc, the share of the cap gone, its bar, and the
 * budget card's own sentence under it ([budgetNoteFa]) — the words that say why the bar is amber,
 * «تا آخر ماه روزی … جا داری», so colour only confirms: the selection colour while it is on pace,
 * caution amber once it runs ahead of its days or nears the cap, `error` once over.
 */
@Composable
private fun BudgetLine(budget: BudgetProgress, onOpen: () -> Unit) {
    val over = budget.overRial > 0L
    val tone = when {
        over -> MaterialTheme.colorScheme.error
        budget.level >= BudgetLevel.NEAR || budget.outpacing -> MaterialTheme.colorScheme.secondary
        else -> MaterialTheme.colorScheme.primary
    }
    val hue = categoryHue(budget.categoryFa)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.field))
            .clickable(role = Role.Button, onClick = onOpen)
            .padding(vertical = Space.s, horizontal = Space.xs)
            .semantics(mergeDescendants = true) {
                contentDescription = "${budget.categoryFa}: " +
                    (if (over) "از سقف گذشتی" else "${faNumber(budget.percent.toDouble())} درصد از سقف رفته") +
                    budgetNoteFa(budget).let { if (it.isBlank()) "" else ". $it" }
            },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(hue.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center,
            ) { CategoryIcon(budget.categoryFa, hue, size = 17.dp, stroke = 1.5.dp) }
            Spacer(Modifier.width(Space.m))
            Text(
                budget.categoryFa,
                fontSize = 15.sp,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                if (over) "از سقف گذشتی" else "${faNumber(budget.percent.toDouble())}٪",
                style = figureStyle(if (over) tone else MaterialTheme.colorScheme.onSurfaceVariant, FontWeight.Bold),
                fontSize = 13.sp,
            )
        }
        Spacer(Modifier.height(Space.xs))
        Box(
            Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(budget.share.coerceIn(0.02f, 1f))
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(3.dp))
                    .background(tone),
            )
        }
        budgetNoteFa(budget).takeIf { it.isNotBlank() }?.let {
            Text(
                it,
                fontSize = 12.sp,
                lineHeight = 19.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Space.xs),
            )
        }
    }
}

/** How many rows خانه shows of دفتر — enough to see the day moving, few enough to stay a glance. */
const val RECENT_ROWS = 4

/** The newest [RECENT_ROWS] of the ledger, as دفتر lists them: hidden duplicates left out. */
fun recentEntries(entries: List<LedgerEntry>): List<LedgerEntry> =
    entries.filterNot { it.duplicate }.sortedByDescending { it.txn.at }.take(RECENT_ROWS)
