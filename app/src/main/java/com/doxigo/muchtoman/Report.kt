package com.doxigo.muchtoman

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.round

/**
 * The report answers one follow-up question: "بیشتر شده یا کمتر؟" — over a window she picks.
 * It is deliberately balance-change, not profit-and-loss: the same thing comparing two bank
 * statements shows. Deposits count as growth here, exactly as they do at the bank.
 */
private val WINDOWS: List<Pair<String, Int?>> = listOf(
    "۱ ماه" to 30,
    "۳ ماه" to 91,
    "۶ ماه" to 182,
    "۱ سال" to 365,
    "همه" to null, // since the first recorded day
)

private fun today(): Long = System.currentTimeMillis() / DAY_MS

@Composable
fun ReportScreen(
    history: Map<Long, Double>,
    current: Double,
    composition: List<Pair<Kind, Double>>,
    story: HomeStory,
    bottomInset: androidx.compose.ui.unit.Dp,
    onBack: (() -> Unit)? = null,
) {
    val now = today()
    val sorted = remember(history) { history.toSortedMap() }

    fun available(days: Int?): Boolean = when {
        sorted.isEmpty() -> days == null
        days == null -> true
        // Covered when the history reaches (almost) back to the window's edge.
        else -> sorted.firstKey() <= now - days + 3
    }

    var selected by remember {
        mutableStateOf(WINDOWS.firstOrNull { available(it.second) }?.first ?: "همه")
    }
    val days = WINDOWS.first { it.first == selected }.second

    val change: Change? = when {
        sorted.isEmpty() -> null
        days == null -> sorted.firstKey().let { d ->
            val base = sorted.getValue(d)
            Change(current - base, if (base > 0) (current - base) / base * 100 else null, d)
        }
        else -> changeOver(history, now, days, current)
    }

    // Chart points: the window's snapshots, closed with the live total as today's point.
    val points: List<Pair<Long, Double>> = change?.let { c ->
        sorted.filterKeys { it >= c.sinceDay && it < now }.map { it.key to it.value } +
            (now to current)
    } ?: emptyList()

    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(horizontal = Space.xl)
                // The composition legend can push past one screen on short phones.
                .verticalScroll(rememberScrollState()),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "گزارش دارایی",
                    fontSize = 28.sp,
                    fontWeight = FontWeight.ExtraBold,
                    modifier = Modifier
                        .weight(1f)
                        .semantics { heading() },
                )
                onBack?.let { back ->
                    TextButton(onClick = back) { Text("برگشت") }
                }
            }

            Spacer(Modifier.height(Space.l))
            WindowPicker(selected, ::available) { selected = it }

            Spacer(Modifier.height(Space.xxl))
            if (change == null || points.size < 2) {
                EmptyReport()
            } else {
                ChangeFigure(change, selected, now)
                Spacer(Modifier.height(Space.xl))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(Radius.group))
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(Space.l),
                ) {
                    Column {
                        HistoryChart(
                            points,
                            tone = changeTone(change),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(220.dp),
                        )
                        Spacer(Modifier.height(Space.l))
                        // Time flows left to right inside the chart, so in this RTL row the
                        // first caption lands on the right — under the newest end of the line.
                        Row(Modifier.fillMaxWidth()) {
                            ChartCaption("اکنون", current)
                            Spacer(Modifier.weight(1f))
                            ChartCaption("شروع", sorted.getValue(change.sinceDay))
                        }
                    }
                }
                Text(
                    "هر روز یک نقطه ثبت می‌شه، حتی اگه برنامه رو باز نکنی.",
                    fontSize = 12.sp,
                    lineHeight = 19.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = Space.m, start = Space.xs, end = Space.xs),
                )
            }
            // One kind is the hero total said twice — same rule the list's section heads use.
            if (composition.size > 1) {
                Spacer(Modifier.height(Space.xxl))
                CompositionBar(composition)
            }

            // The month, which is a different question from the chart above it: that one is
            // "is there more than there was", this one is "where did it go".
            if (story.month.transactions > 0) {
                Spacer(Modifier.height(Space.xxl))
                MonthStory(story)
            }
            Spacer(Modifier.height(bottomInset + Space.xl))
        }
    }
}

/**
 * The bar's own palette, not the icon discs' container tints: those are meant to sit quietly
 * behind an emoji, and in the dark theme they all but vanish against the background — an 89%
 * crypto segment read as an empty track. These are the app's green-to-gold family, fixed so
 * they hold on both themes; gold stays reserved for the gold kinds.
 */
private fun barTint(kind: Kind): Color = when (kind) {
    Kind.CASH -> Color(0xFF2E7D6B)
    Kind.FIAT -> Color(0xFF6FAF9F)
    Kind.CRYPTO -> Color(0xFF93B7B0)
    Kind.GOLD -> Color(0xFFF7C948)
    Kind.SILVER -> Color(0xFFAEB6BD)
    Kind.COIN -> Color(0xFFC99B2C)
    Kind.STOCK -> Color(0xFF4E8A7B)
}

/**
 * What the total is made of, right now. Groups are the home list's own sections, so this is
 * the same money in the same words — just side by side instead of stacked.
 */
@Composable
private fun CompositionBar(composition: List<Pair<Kind, Double>>) {
    val total = composition.sumOf { it.second }
    Column {
        Text(
            "ترکیب دارایی",
            fontSize = 15.sp,
            fontWeight = FontWeight.ExtraBold,
            modifier = Modifier.semantics { heading() },
        )
        Spacer(Modifier.height(Space.m))
        Row(
            Modifier
                .fillMaxWidth()
                .height(14.dp)
                .clip(RoundedCornerShape(Radius.pill)),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            composition.forEach { (kind, value) ->
                Box(
                    Modifier
                        .weight(value.toFloat())
                        .fillMaxHeight()
                        .background(barTint(kind)),
                )
            }
        }
        Spacer(Modifier.height(Space.m))
        composition.forEach { (kind, value) ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(vertical = Space.xs),
            ) {
                Box(
                    Modifier
                        .size(10.dp)
                        .clip(RoundedCornerShape(Radius.pill))
                        .background(barTint(kind)),
                )
                Spacer(Modifier.width(Space.s))
                Text(kind.fa, fontSize = 14.sp, modifier = Modifier.weight(1f))
                Text(
                    // Parentheses, not a middot: beside Persian digits «·» reads as a zero.
                    "${faCompact(value, 3, pad = true)} تومان (${faNumber(round(value / total * 100))}٪)",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * The verdict's colour, shared by the figure, its pill and the chart line, so the whole screen
 * agrees at a glance about which way the money went. Words carry it; colour confirms.
 */
@Composable
private fun changeTone(change: Change): Color = when {
    abs(change.delta) < 1 -> MaterialTheme.colorScheme.onSurfaceVariant
    // tertiary is the gain role: borrowing primary made "بیشتر" green in light mode but
    // gold — the colour of every button — in dark.
    change.delta > 0 -> MaterialTheme.colorScheme.tertiary
    else -> MaterialTheme.colorScheme.error
}

/** A window the history is too short to answer is dimmed, not hidden — its absence is data. */
@Composable
private fun WindowPicker(
    selected: String,
    available: (Int?) -> Boolean,
    onSelect: (String) -> Unit,
) = SegmentedChoice(
    options = WINDOWS.map { it.first },
    selected = selected,
    label = { it },
    enabled = { label -> available(WINDOWS.first { it.first == label }.second) },
    onSelect = onSelect,
    role = Role.Tab,
    fontSize = 14.sp,
)

/**
 * Bigger or smaller, by how much, since when — stacked the way the hero total is, because it
 * answers the same kind of question and has earned the same kind of answer.
 */
@Composable
private fun ChangeFigure(change: Change, windowLabel: String, now: Long) {
    val gained = change.delta > 0
    val flat = abs(change.delta) < 1
    val tone = changeTone(change)
    val since = if (windowLabel == "همه") {
        "از ${faNumber((now - change.sinceDay).toDouble())} روز پیش"
    } else {
        "نسبت به $windowLabel پیش"
    }

    Column {
        Text(since, fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(Space.xs))
        // Auto-shrinks like the hero figure: a big delta must never wrap.
        BasicText(
            text = if (flat) "بدون تغییر" else "${faCompact(abs(change.delta), 3, pad = true)} تومان",
            maxLines = 1,
            autoSize = TextAutoSize.StepBased(minFontSize = 22.sp, maxFontSize = 44.sp),
            style = figureStyle(tone, FontWeight.Black),
        )
        if (!flat) {
            Spacer(Modifier.height(Space.m))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(
                    Modifier
                        .clip(RoundedCornerShape(Radius.pill))
                        .background(tone.copy(alpha = 0.14f))
                        .padding(horizontal = Space.m, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TrendCaret(up = gained, tint = tone, box = 10.dp)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        change.percent?.let { "${faDecimal(abs(it), 1)}٪" }
                            ?: if (gained) "بیشتر" else "کمتر",
                        fontSize = 14.sp,
                        fontFamily = ModamFigures,
                        fontWeight = FontWeight.Bold,
                        color = tone,
                    )
                }
                Spacer(Modifier.width(Space.s))
                Text(
                    if (gained) "بیشتر شده" else "کمتر شده",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                "${faNumber(abs(change.delta))} تومان",
                fontSize = 12.sp,
                fontFamily = ModamFigures,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Space.s),
            )
        }
    }
}

@Composable
private fun ChartCaption(label: String, value: Double) {
    Column {
        Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            "${faCompact(value)} تومان",
            fontSize = 14.sp,
            fontFamily = ModamFigures,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

@Composable
private fun EmptyReport() {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 52.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(84.dp)
                .clip(RoundedCornerShape(Radius.pill))
                .background(MaterialTheme.colorScheme.surfaceContainer),
            contentAlignment = Alignment.Center,
        ) { Text("📈", fontSize = 40.sp) }
        Spacer(Modifier.height(Space.xl))
        Text("هنوز نموداری نیست", fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
        Spacer(Modifier.height(Space.s))
        Text(
            "از امروز هر روز یک نقطه ثبت می‌شه و نمودار کم‌کم کامل می‌شه.",
            fontSize = 15.sp,
            lineHeight = 25.sp,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * A plain area line — the shape of the money over time, nothing else. Time flows left to
 * right (charts keep that direction even in RTL interfaces; every Iranian bank app agrees).
 * It takes the verdict's colour, so the line and the figure above it can never disagree.
 */
@Composable
private fun HistoryChart(
    points: List<Pair<Long, Double>>,
    tone: Color,
    modifier: Modifier = Modifier,
) {
    val surface = MaterialTheme.colorScheme.surface
    Canvas(modifier) {
        val minV = points.minOf { it.second }
        val maxV = points.maxOf { it.second }
        val span = (maxV - minV).takeIf { it > 0.0 } ?: 1.0 // a flat line draws mid-height
        val d0 = points.first().first
        val dSpan = (points.last().first - d0).coerceAtLeast(1L).toFloat()
        val padY = size.height * 0.12f

        fun px(day: Long) = (day - d0) / dSpan * size.width
        // A flat history really does draw mid-height: without the guard every point of an
        // unchanged total sat at the bottom edge, reading exactly like a crash to zero.
        fun py(v: Double) =
            if (maxV == minV) size.height / 2f
            else (size.height - padY) - (((v - minV) / span).toFloat() * (size.height - 2 * padY))

        val path = Path()
        points.forEachIndexed { i, (d, v) ->
            if (i == 0) path.moveTo(px(d), py(v)) else path.lineTo(px(d), py(v))
        }
        val area = Path().apply {
            addPath(path)
            lineTo(size.width, size.height)
            lineTo(0f, size.height)
            close()
        }
        drawPath(
            area,
            Brush.verticalGradient(listOf(tone.copy(alpha = 0.28f), tone.copy(alpha = 0f))),
        )
        drawPath(
            path,
            tone,
            style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
        // Today's point, ringed in the card's own colour so it reads as a marker sitting on
        // the line rather than as the line gone briefly fat.
        val end = Offset(px(points.last().first), py(points.last().second))
        drawCircle(tone.copy(alpha = 0.22f), radius = 11.dp.toPx(), center = end)
        drawCircle(surface, radius = 6.dp.toPx(), center = end)
        drawCircle(tone, radius = 4.dp.toPx(), center = end)
    }
}

/**
 * The month's story — what came in, what went out, where, and how long the cash would last.
 *
 * Every figure comes from [buildStory]; nothing here computes anything, so what she reads and
 * what the tests assert are the same function. Categories are shown as a share of spending
 * rather than in Toman: with this inflation a smaller number of Toman is not a smaller amount
 * of money, and reporting it as progress would be a lie.
 */
@Composable
private fun MonthStory(story: HomeStory) {
    val month = story.month
    Column(Modifier.fillMaxWidth()) {
        Text(
            "${MONTHS[month.month - 1]} امسال",
            fontSize = 20.sp,
            fontWeight = FontWeight.ExtraBold,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.semantics { heading() },
        )
        Spacer(Modifier.height(Space.m))
        MonthFlow(story)

        story.bufferDays?.let { days ->
            Spacer(Modifier.height(Space.m))
            Panel {
                Text(
                    "پولت برای چند روز می‌رسه",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(Space.xs))
                Text(
                    "${faNumber(days.toDouble())} روز",
                    style = figureStyle(MaterialTheme.colorScheme.onSurface, FontWeight.ExtraBold),
                    fontSize = 22.sp,
                )
            }
        }

        if (month.byCategory.isNotEmpty() && month.spentRial > 0) {
            Spacer(Modifier.height(Space.xl))
            Text(
                "خرج‌هات",
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(Modifier.height(Space.s))
            for ((name, rial) in month.byCategory.take(6)) {
                CategoryShare(name, rial.toFloat() / month.spentRial, rial)
            }
        }

        for (insight in story.insights) {
            Spacer(Modifier.height(Space.m))
            InsightCard(insight, onWhy = {})
        }
        for (win in story.wins) {
            Spacer(Modifier.height(Space.m))
            InsightCard(win, onWhy = {})
        }
    }
}

@Composable
private fun CategoryShare(name: String, share: Float, rial: Long) {
    Column(Modifier.fillMaxWidth().padding(vertical = Space.s)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CategoryIcon(name, MaterialTheme.colorScheme.onSurfaceVariant, size = 16.dp, stroke = 1.5.dp)
            Spacer(Modifier.width(Space.s))
            Text(name, Modifier.weight(1f), color = MaterialTheme.colorScheme.onBackground)
            Text(
                bidi(faCompact(tomanOf(rial))),
                style = figureStyle(MaterialTheme.colorScheme.onSurfaceVariant, FontWeight.Bold),
                fontSize = 14.sp,
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
                    .fillMaxWidth(share.coerceIn(0f, 1f))
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(3.dp))
                    .background(MaterialTheme.colorScheme.primary),
            )
        }
    }
}
