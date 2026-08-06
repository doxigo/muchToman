package com.doxigo.muchtoman

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
fun HeroMonth(story: HomeStory, modifier: Modifier = Modifier) {
    val month = story.month
    val income = tomanOf(month.incomeRial)
    val spent = tomanOf(month.spentRial)
    Column(
        modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                contentDescription =
                    "درآمد این ماه ${faCompact(income)} تومان، خرج این ماه ${faCompact(spent)} تومان"
            },
    ) {
        Row(Modifier.fillMaxWidth()) {
            HeroFlowHalf("درآمد این ماه", income, Hero.mint, Modifier.weight(1f))
            Spacer(Modifier.width(Space.m))
            HeroFlowHalf("خرج این ماه", spent, Hero.strong, Modifier.weight(1f))
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
 * One statement, and the control that answers for it.
 *
 * «چرا این را می‌بینم؟» is not a nicety — a number she cannot trace is a number she has to take
 * on faith, and this app has spent its whole life refusing to ask for that.
 *
 * The two kinds of insight were the same card down to the pixel, separated only by an 8dp dot,
 * and both offered «این عدد از کجا اومده؟» — which on the one asking for her did not answer that
 * question at all, it left the screen. A finding is something to read and its control is a quiet
 * link; a thing waiting on her is something to do, and its control is filled, in the same gold
 * the ledger and the tab bar use for exactly that. [action] names where it actually goes.
 */
@Composable
fun InsightCard(
    insight: Insight,
    onWhy: () -> Unit,
    modifier: Modifier = Modifier,
    action: String = "این عدد از کجا اومده؟",
) {
    val asking = insight.tone == Insight.Tone.ATTENTION
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
        Spacer(Modifier.height(Space.xs))
        Box(
            Modifier
                .clip(RoundedCornerShape(Radius.pill))
                .then(
                    if (asking) Modifier.background(MaterialTheme.colorScheme.primary)
                    else Modifier,
                )
                .clickable(role = Role.Button, onClick = onWhy)
                // The label sets its own height: at 12sp with 4dp of padding the target was
                // half the floor, on the only control these cards have.
                .heightIn(min = 48.dp)
                .padding(horizontal = if (asking) Space.l else Space.s),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                action,
                fontSize = 13.sp,
                fontWeight = if (asking) FontWeight.Bold else FontWeight.Medium,
                color = if (asking) MaterialTheme.colorScheme.onPrimary
                else MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/** The «why» panel: the sentence behind the sentence, and how many transactions are under it. */
@Composable
fun WhyNote(insight: Insight, modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.card))
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .padding(Space.l),
    ) {
        Text(
            insight.why,
            fontSize = 13.sp,
            lineHeight = 22.sp,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
        if (insight.refs.isNotEmpty()) {
            Spacer(Modifier.height(Space.xs))
            Text(
                "از ${faNumber(insight.refs.size.toDouble())} تراکنش",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.75f),
            )
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
