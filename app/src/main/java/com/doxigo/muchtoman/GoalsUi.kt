package com.doxigo.muchtoman

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToLong

/**
 * Goals, and the «آیا ارزشش را داشت؟» answers.
 *
 * These two are the whole reward system. There are no points, no streaks, no confetti and no
 * leaderboard, because the FCA's own experiments found those change how people take financial
 * risks — and this app has no business doing that to anybody. What is left is the encouragement
 * with evidence behind it: a concrete goal she set, and her own verdict on her own spending.
 */

@Composable
fun GoalsScreen(
    goals: List<GoalProgress>,
    summary: WorthItSummary,
    onAdd: (String, Long) -> Unit,
    onDelete: (String) -> Unit,
    bottomInset: androidx.compose.ui.unit.Dp,
) {
    var name by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    val targetRial = remember(amount) {
        parseAmount(amount)
            ?.takeIf { it > 0.0 && it <= Long.MAX_VALUE / 10.0 }
            ?.let { (it * 10.0).roundToLong() }
    }

    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(horizontal = Space.xl)
                .verticalScroll(rememberScrollState())
                .padding(bottom = bottomInset + Space.l),
        ) {
            Row(Modifier.fillMaxWidth().padding(vertical = Space.m), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "هدف‌ها",
                    fontSize = 28.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.weight(1f).semantics { heading() },
                )
            }

            if (goals.isEmpty()) {
                Text(
                    "اینجا فقط هدف‌هایی رو می‌بینی که خودت انتخاب کردی. " +
                        "خبری از امتیاز، زنجیره یا مقایسه با بقیه نیست.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 26.sp,
                )
                Spacer(Modifier.height(Space.l))
            }

            for (progress in goals) {
                GoalCard(progress, onDelete = { onDelete(progress.goal.id) })
                Spacer(Modifier.height(Space.m))
            }

            Spacer(Modifier.height(Space.l))
            Text(
                "هدف تازه",
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(Modifier.height(Space.s))
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("هدفت چیه؟") },
                singleLine = true,
                shape = RoundedCornerShape(Radius.field),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(Space.s))
            OutlinedTextField(
                value = amount,
                onValueChange = { amount = it },
                label = { Text("مبلغ به تومان") },
                singleLine = true,
                visualTransformation = GroupedNumber,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                supportingText = if (amount.isNotBlank() && targetRial == null) ({
                    Text("این عدد قابل خوندن نیست. فقط عدد وارد کن.")
                }) else null,
                shape = RoundedCornerShape(Radius.field),
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = "مبلغ هدف به تومان" },
            )
            Spacer(Modifier.height(Space.s))
            Button(
                onClick = {
                    val rial = targetRial ?: return@Button
                    if (name.isBlank()) return@Button
                    onAdd(name.trim(), rial)
                    name = ""; amount = ""
                },
                enabled = name.isNotBlank() && targetRial != null,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("ذخیره هدف") }

            if (summary.total > 0) {
                Spacer(Modifier.height(Space.xxl))
                WorthItSummaryCard(summary)
            }
            Spacer(Modifier.height(Space.huge))
        }
    }
}

@Composable
private fun GoalCard(progress: GoalProgress, onDelete: () -> Unit) {
    val tone = if (progress.done && !progress.cap) Color(0xFF2E9E5B) else MaterialTheme.colorScheme.primary
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                progress.goal.nameFa,
                Modifier.weight(1f),
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                "حذف",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .clip(RoundedCornerShape(Radius.pill))
                    .clickable(role = Role.Button, onClick = onDelete)
                    .padding(vertical = Space.xs, horizontal = Space.s),
            )
        }
        Spacer(Modifier.height(Space.s))
        Text(
            bidi("${faCompact(tomanOf(progress.currentRial))} از ${faCompact(tomanOf(progress.targetRial))}"),
            style = figureStyle(MaterialTheme.colorScheme.onSurface, FontWeight.ExtraBold),
            fontSize = 20.sp,
        )
        Spacer(Modifier.height(Space.s))
        Box(
            Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(MaterialTheme.colorScheme.surface),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(progress.share)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(4.dp))
                    .background(tone),
            )
        }
        when {
            progress.done && !progress.cap -> {
                Spacer(Modifier.height(Space.s))
                // Stated once, with the figure that earned it. Nothing animates, nothing pops.
                Text("به هدفت رسیدی.", fontWeight = FontWeight.Bold, color = tone)
            }
            progress.underWater -> {
                Spacer(Modifier.height(Space.s))
                // A fact, not a verdict. No "you failed" anywhere in this app.
                Text(
                    "از وقتی این هدف رو گذاشتی، هنوز چیزی پس‌انداز نشده.",
                    fontSize = 13.sp,
                    lineHeight = 22.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun WorthItSummaryCard(summary: WorthItSummary) {
    Panel {
        Text(
            "به نظر خودت",
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(Space.s))
        WorthItRow("ارزش داشت", summary.worth, Color(0xFF2E9E5B))
        WorthItRow("لازم بود", summary.needed, MaterialTheme.colorScheme.onSurfaceVariant)
        WorthItRow("ارزش نداشت", summary.regretted, MaterialTheme.colorScheme.primary)
        if (summary.regretted > 0) {
            Spacer(Modifier.height(Space.s))
            Text(
                // The whole reason for asking: "spend less" is advice nobody can act on.
                "این خرج‌ها رو می‌شه کم کرد، بدون اینکه چیزی از دست بدی.",
                fontSize = 13.sp,
                lineHeight = 22.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun WorthItRow(label: String, rial: Long, tone: Color) {
    Row(Modifier.fillMaxWidth().padding(vertical = Space.xs), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurface)
        Text(
            bidi(faCompact(tomanOf(rial))),
            style = figureStyle(tone, FontWeight.Bold),
            fontSize = 15.sp,
        )
    }
}

/**
 * The weekly question, asked about at most two large discretionary purchases.
 *
 * Never about rent, bills, fees or cash — "was it worth it" is not a question about the
 * electricity bill, and asking it would read as the app being smug.
 */
@Composable
fun WorthItCard(entry: LedgerEntry, onAnswer: (String) -> Unit, modifier: Modifier = Modifier) {
    Panel(modifier) {
        Text(
            faDay(entry.txn.day),
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(Space.xs))
        Text(
            entry.txn.merchant.ifBlank { entry.categoryFa },
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        entry.txn.amountRial?.let {
            Spacer(Modifier.height(Space.xs))
            Text(
                bidi(faCompact(tomanOf(it)) + " تومان"),
                style = figureStyle(MaterialTheme.colorScheme.onSurface, FontWeight.ExtraBold),
                fontSize = 24.sp,
            )
        }
        Spacer(Modifier.height(Space.l))
        Text(
            "ارزش داشت؟",
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(Space.s))
        Row(Modifier.fillMaxWidth()) {
            Answer("آره", Modifier.weight(1f)) { onAnswer(WorthIt.YES) }
            Answer("لازم بود", Modifier.weight(1f)) { onAnswer(WorthIt.NEEDED) }
            Answer("نه", Modifier.weight(1f)) { onAnswer(WorthIt.NO) }
        }
    }
}

@Composable
private fun Answer(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Text(
        label,
        textAlign = TextAlign.Center,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = modifier
            .padding(horizontal = Space.xs)
            .clip(RoundedCornerShape(Radius.pill))
            .background(MaterialTheme.colorScheme.surface)
            .clickable(role = Role.Button, onClick = onClick)
            // 44dp minimum: this is answered one-handed or it is not answered.
            .height(48.dp)
            .padding(vertical = Space.m),
    )
}
