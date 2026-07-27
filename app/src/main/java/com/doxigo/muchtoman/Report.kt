package com.doxigo.muchtoman

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onBack) { Text("بازگشت", fontSize = 17.sp) }
            }

            Spacer(Modifier.height(Space.xl))
            WindowPicker(selected, ::available) { selected = it }

            Spacer(Modifier.height(Space.xxl))
            if (change == null || points.size < 2) {
                EmptyReport()
            } else {
                ChangeFigure(change, selected, now)
                Spacer(Modifier.height(Space.xxl))
                Card(
                    shape = RoundedCornerShape(Radius.card),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(Space.l)) {
                        HistoryChart(
                            points,
                            Modifier
                                .fillMaxWidth()
                                .height(200.dp),
                        )
                        Spacer(Modifier.height(Space.m))
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
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = Space.m, start = Space.xs, end = Space.xs),
                )
            }
        }
    }
}

/** Same shape as the theme picker in settings: plain buttons, the chosen one filled. */
@Composable
private fun WindowPicker(
    selected: String,
    available: (Int?) -> Boolean,
    onSelect: (String) -> Unit,
) {
    Row(Modifier.fillMaxWidth()) {
        WINDOWS.forEachIndexed { i, (label, days) ->
            val enabled = available(days)
            val isSelected = label == selected
            Surface(
                onClick = { if (enabled) onSelect(label) },
                enabled = enabled,
                shape = RoundedCornerShape(Radius.field),
                color = if (isSelected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.surface,
                contentColor = if (isSelected) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else 0.35f),
                modifier = Modifier
                    .weight(1f)
                    .padding(start = if (i == 0) 0.dp else Space.xs)
                    .height(50.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        label,
                        fontSize = 14.sp,
                        maxLines = 1,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                    )
                }
            }
        }
    }
}

/** The verdict: bigger or smaller, by how much, since when. Words carry it, colour confirms. */
@Composable
private fun ChangeFigure(change: Change, windowLabel: String, now: Long) {
    val gained = change.delta > 0
    val flat = abs(change.delta) < 1
    val colour = when {
        flat -> MaterialTheme.colorScheme.onSurfaceVariant
        gained -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.error
    }
    val headline = when {
        flat -> "بدون تغییر"
        else -> "${faCompact(abs(change.delta))} تومان " + if (gained) "بیشتر" else "کمتر"
    }

    Column {
        // Auto-shrinks like the hero figure: a big delta must never wrap.
        BasicText(
            text = headline,
            maxLines = 1,
            autoSize = TextAutoSize.StepBased(minFontSize = 18.sp, maxFontSize = 30.sp),
            style = TextStyle(
                fontFamily = Vazir,
                fontWeight = FontWeight.Bold,
                color = colour,
            ),
        )
        val since = if (windowLabel == "همه") {
            "از ${faNumber((now - change.sinceDay).toDouble())} روز پیش"
        } else {
            "نسبت به $windowLabel پیش"
        }
        val percent = change.percent?.let { "  ·  ${faDecimal(abs(it), 1)}٪" } ?: ""
        Text(
            since + percent,
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = Space.xs),
        )
        if (!flat) {
            Text(
                "${faNumber(abs(change.delta))} تومان",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

@Composable
private fun ChartCaption(label: String, value: Double) {
    Column {
        Text(
            label,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "${faCompact(value)} تومان",
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
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
        Text("📈", fontSize = 56.sp)
        Spacer(Modifier.height(Space.l))
        Text("هنوز نموداری نیست", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(Space.s))
        Text(
            "از امروز، هر روزی که برنامه را باز کنید یک نقطه ثبت می‌شود و نمودار روزبه‌روز کامل‌تر می‌شود.",
            fontSize = 15.sp,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * A plain area line — the shape of the money over time, nothing else. Time flows left to
 * right (charts keep that direction even in RTL interfaces; every Iranian bank app agrees).
 */
@Composable
private fun HistoryChart(points: List<Pair<Long, Double>>, modifier: Modifier = Modifier) {
    val line = MaterialTheme.colorScheme.primary
    val fill = line.copy(alpha = 0.18f)
    Canvas(modifier) {
        val minV = points.minOf { it.second }
        val maxV = points.maxOf { it.second }
        val span = (maxV - minV).takeIf { it > 0.0 } ?: 1.0 // a flat line draws mid-height
        val d0 = points.first().first
        val dSpan = (points.last().first - d0).coerceAtLeast(1L).toFloat()
        val padY = size.height * 0.10f

        fun px(day: Long) = (day - d0) / dSpan * size.width
        fun py(v: Double) =
            (size.height - padY) - (((v - minV) / span).toFloat() * (size.height - 2 * padY))

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
        drawPath(area, Brush.verticalGradient(listOf(fill, fill.copy(alpha = 0f))))
        drawPath(
            path,
            line,
            style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
        drawCircle(line, radius = 4.dp.toPx(), center = Offset(px(points.last().first), py(points.last().second)))
    }
}
