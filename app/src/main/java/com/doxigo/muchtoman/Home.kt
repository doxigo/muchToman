package com.doxigo.muchtoman

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The home screen's answer, under the total.
 *
 * One meaningful change, this month's flow, the buffer, and at most one thing asking for her —
 * and then it stops. The old home put twelve holdings under the number; the portfolio is still
 * one tap away, but what she opens the app for is the story, not the inventory.
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
fun MonthFlow(story: HomeStory, modifier: Modifier = Modifier) {
    val month = story.month
    val gain = Color(0xFF2E9E5B)
    val spend = MaterialTheme.colorScheme.onSurfaceVariant
    Panel(modifier) {
        Row(Modifier.fillMaxWidth()) {
            FlowHalf("درآمد این ماه", month.incomeRial, gain, Modifier.weight(1f))
            Spacer(Modifier.width(Space.m))
            FlowHalf("خرج این ماه", month.spentRial, MaterialTheme.colorScheme.onSurface, Modifier.weight(1f))
        }
        if (month.incomeRial > 0 || month.spentRial > 0) {
            Spacer(Modifier.height(Space.l))
            FlowBar(month.incomeRial, month.spentRial, gain, spend)
        }
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

/**
 * The same month, on the hero field, where it is now the second half of the first screen.
 *
 * Not [MonthFlow] with a different parent: its greens are surface greens, and one of them —
 * `0xFF2E9E5B` — is a green sitting on a green up here. [InsightCard] refused that same swap for
 * the same reason. Income speaks in `Hero.mint`, which is what every bank app in the country
 * uses for it and what the field already uses on the change pill; spend speaks in the field's
 * plain foreground. So the pair reads as one amount weighed against another rather than as good
 * against bad, and the bar underneath does the comparing, exactly as it does on the card.
 *
 * One accessibility node, not five. Read out separately these are four loose numbers and two
 * labels; read out together they are the sentence the sighted eye gets in one glance.
 */
@Composable
fun HeroMonth(story: HomeStory, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val month = story.month
    val income = tomanOf(month.incomeRial)
    val spent = tomanOf(month.spentRial)
    Column(
        modifier
            .fillMaxWidth()
            // No corner clip. The band runs the full width with the label flush to one corner
            // and the bar flush to the opposite edge, so any radius at all eats them: a 24dp
            // one sliced the alef off «این ماه» and took a diagonal bite out of both ends of
            // the bar. A rectangular ripple across a full-width band is the correct shape here
            // anyway — this is a band, not a card sitting on one.
            .clickable(role = Role.Button, onClick = onOpen)
            .semantics(mergeDescendants = true) {
                contentDescription = "این ماه: درآمد ${faCompact(income)} تومان، " +
                    "خرج ${faCompact(spent)} تومان. برای گزارش کامل باز کن"
            },
    ) {
        // The band is the report's own opening paragraph — گزارش دارایی leads with these two
        // figures and this bar — so the band is the door to the rest of it. Tapping the summary
        // to get the long form is the one gesture on this screen that needs no explaining, and
        // it is why the report does not need a tab: it is already on the front page, in short.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("این ماه", fontSize = 12.sp, color = Hero.muted)
            Icon(
                Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = null,
                tint = Hero.muted,
                modifier = Modifier.size(16.dp),
            )
        }
        Spacer(Modifier.height(Space.s))
        Row(Modifier.fillMaxWidth()) {
            HeroFlowHalf("درآمد", income, Hero.mint, Modifier.weight(1f))
            Spacer(Modifier.width(Space.m))
            HeroFlowHalf("خرج", spent, Hero.strong, Modifier.weight(1f))
        }
        if (month.incomeRial > 0 || month.spentRial > 0) {
            Spacer(Modifier.height(Space.l))
            // Not `Hero.muted` for the spend leg: against mint at this height it has to hold
            // its own, and muted is the colour of things that recede.
            FlowBar(month.incomeRial, month.spentRial, Hero.mint, Hero.strong.copy(alpha = 0.55f))
        }
    }
}

@Composable
private fun HeroFlowHalf(label: String, toman: Double, tone: Color, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label, fontSize = 12.sp, color = Hero.muted)
        Spacer(Modifier.height(Space.xs))
        // The same ceiling and floor as the card's, so the two halves step down together and a
        // milliard beside a thousand does not knock the pair out of alignment.
        BasicText(
            text = bidi(faCompact(toman)),
            maxLines = 1,
            autoSize = TextAutoSize.StepBased(minFontSize = 16.sp, maxFontSize = 26.sp),
            style = figureStyle(tone, FontWeight.ExtraBold),
        )
    }
}

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
 * leaves the screen, filled in the same gold the ledger and the tab bar use for exactly that;
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
        Insight.Tone.GOOD -> Color(0xFF2E9E5B)
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
            Box(
                Modifier
                    .clip(RoundedCornerShape(Radius.pill))
                    .background(MaterialTheme.colorScheme.primary)
                    .clickable(role = Role.Button, onClick = onAction)
                    // The label sets its own height: at 12sp with 4dp of padding the target was
                    // half the floor, on the only control these cards have.
                    .heightIn(min = 48.dp)
                    .padding(horizontal = Space.l),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    it,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            }
        }
    }
}

/**
 * Insights two to a row.
 *
 * One short sentence per full-width card was a column of mostly empty space, and the report had
 * eight of them to scroll past. The row measures to the taller of its pair and both cards fill
 * it, so a long sentence beside a short one still reads as two cards and not as a step.
 */
@Composable
fun InsightGrid(insights: List<Insight>, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space.m)) {
        for (pair in insights.chunked(2)) {
            Row(
                Modifier.height(IntrinsicSize.Min),
                horizontalArrangement = Arrangement.spacedBy(Space.m),
            ) {
                for (insight in pair) {
                    InsightCard(insight, Modifier.weight(1f).fillMaxHeight(), why = true)
                }
                // An odd count leaves the last card in its half rather than stretched across a
                // row of its own: the grid keeps one edge to read down.
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

/** Before there is any history, say so plainly rather than showing a zero. */
@Composable
fun QuietStart(smsEnabled: Boolean, modifier: Modifier = Modifier) {
    Panel(modifier) {
        Text(
            if (smsEnabled) "هنوز برای این ماه تراکنشی ثبت نشده" else "پیامک‌های بانکی خاموشن",
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
