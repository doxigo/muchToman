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
            Row(
                Modifier.fillMaxWidth().height(8.dp),
                horizontalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                // A gap and two pills, not one split bar: joined, the two shares read as a
                // single quantity filling up rather than as two amounts being weighed.
                if (month.incomeRial > 0) {
                    Box(
                        Modifier
                            .weight(month.incomeRial.toFloat())
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(Radius.pill))
                            .background(gain),
                    )
                }
                if (month.spentRial > 0) {
                    Box(
                        Modifier
                            .weight(month.spentRial.toFloat())
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(Radius.pill))
                            .background(spend),
                    )
                }
            }
        }
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

/**
 * The way to the holdings, which are no longer the first thing on the screen.
 *
 * It used to end in the portfolio's total, which is `state.totals.toman` — the same number the
 * hero states at the top of this very screen, in gold, at four times the size. A row that
 * repeats the headline is not telling her anything; a row that goes somewhere should say so, so
 * the figure is gone and the chevron is the point.
 */
@Composable
fun PortfolioEntry(
    count: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.card))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(Space.l),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                "دارایی‌ها",
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                "${faNumber(count.toDouble())} مورد",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(
            Icons.AutoMirrored.Rounded.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
