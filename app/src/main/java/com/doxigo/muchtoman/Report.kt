package com.doxigo.muchtoman

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
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
fun ReportScreen(history: Map<Long, Double>, current: Double, onBack: () -> Unit) {
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
                .systemBarsPadding()
                .padding(horizontal = Space.xl),
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
                TextButton(onClick = onBack) { Text("بازگشت", fontSize = 17.sp) }
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
                    "نمودار از روزهایی ساخته می‌شود که برنامه باز شده و نرخ گرفته است.",
                    fontSize = 12.sp,
                    lineHeight = 19.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = Space.m, start = Space.xs, end = Space.xs),
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
            "از امروز، هر روزی که برنامه را باز کنید یک نقطه ثبت می‌شود و نمودار روزبه‌روز کامل‌تر می‌شود.",
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
