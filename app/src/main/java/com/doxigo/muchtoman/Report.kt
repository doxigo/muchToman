package com.doxigo.muchtoman

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.round
import kotlinx.coroutines.launch

/**
 * The report answers one follow-up question: "بیشتر شده یا کمتر؟" — over a window she picks.
 * It is deliberately balance-change, not profit-and-loss: the same thing comparing two bank
 * statements shows. Deposits count as growth here, exactly as they do at the bank.
 */
private val WINDOWS: List<Pair<String, Int?>> = listOf(
    "۱ هفته" to 7,
    "۱ ماه" to 30,
    "۳ ماه" to 91,
    "۶ ماه" to 182,
    "۱ سال" to 365,
    "همه" to null, // since the first recorded day
)

private fun today(): Long = System.currentTimeMillis() / DAY_MS

/**
 * The two questions this tab answers, as peers.
 *
 * They were not peers: the whole screen called itself «گزارش دارایی» and the month's money was
 * appended underneath it, which told anyone reading the tab that the app reports on what she
 * owns and not on what she earns and spends. It reports on both, and a full app that has a
 * دارایی root tab of its own has no reason to open the reports on the asset side.
 */
enum class ReportMode(val fa: String) {
    CASH_FLOW("دخل و خرج"),
    ASSETS("دارایی"),
}

/**
 * The report modes *this edition* has.
 *
 * Read off [tabs] rather than off `BuildConfig.LITE`, because the edition flag lives in exactly
 * one place and this is not it: the lite build is a list of what you own, so the only report it
 * can honestly offer is the one about what you own.
 */
val reportModes: List<ReportMode> =
    if (tabs.size > 1) ReportMode.entries.toList() else listOf(ReportMode.ASSETS)

@Composable
fun ReportScreen(
    history: Map<Long, Double>,
    current: Double,
    composition: List<Pair<Kind, Double>>,
    cash: CashFlowReport,
    smsEnabled: Boolean,
    mode: ReportMode,
    onMode: (ReportMode) -> Unit,
    /**
     * The window دخل و خرج should read: the day it is anchored on, and how far back it reaches.
     * A day rather than a month, because the anchor has to be able to name a week as well as a
     * month — [buildCashFlow] reads the month out of it for every month-made span.
     */
    onWindow: (Long, ReportSpan) -> Unit,
    bottomInset: Dp,
    onBack: (() -> Unit)? = null,
    /** The live categories, for naming and choosing what دخل و خرج leaves out. */
    categories: List<Category> = emptyList(),
    /** Category ids she has told this report to leave out. */
    excluded: Set<String> = emptySet(),
    onExcluded: (Set<String>) -> Unit = {},
    /** Her «می‌ارزید؟» answers, summed over this report's own window. */
    worthIt: WorthItSummary = WorthItSummary(0, 0, 0),
    /** The ledger itself, for the category drill-down — the rows behind every share bar. */
    entries: List<LedgerEntry> = emptyList(),
    /** Opens one transaction's own page, from the drill-down's list. */
    onOpenEntry: ((LedgerEntry) -> Unit)? = null,
) {
    val modes = reportModes
    // The lite edition is handed CASH_FLOW by any caller that does not know which build it is
    // in; it has no such report, so the mode it can show is the mode it shows.
    val shown = if (mode in modes) mode else modes.first()

    // One scroll position per report, kept apart on purpose: switching to دارایی and finding it
    // already scrolled to wherever دخل و خرج happened to be is the mode selector losing the
    // reader's place in a screen she never left.
    val cashScroll = rememberScrollState()
    val assetScroll = rememberScrollState()

    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(horizontal = Space.xl),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Neutral where there are two of them: naming the screen after one of its
                // two halves is what hid the other one for a year.
                ScreenTitle(
                    if (modes.size > 1) "گزارش‌ها" else "گزارش دارایی",
                    modifier = Modifier.weight(1f),
                )
                onBack?.let { back ->
                    PillButton("برگشت", back)
                }
            }

            // Pinned above the scroll, not inside it: which report she is reading has to stay
            // answerable at the bottom of a long one. A one-option segment is not a choice, so
            // the edition that has one does not draw it.
            if (modes.size > 1) {
                Spacer(Modifier.height(Space.l))
                SegmentedChoice(
                    options = modes,
                    selected = shown,
                    label = { it.fa },
                    onSelect = onMode,
                    role = Role.Tab,
                )
            }

            // Outside the scroll, unlike every other gap on this screen. A pinned control has to
            // keep its air once the report is scrolled, and this was the one place where content
            // slid up until it touched the thing that governs it. Wider where there is no segment
            // to sit under, because then what is above is the 28sp heading, and a heading owns
            // more room beneath it than a control does.
            Spacer(Modifier.height(if (modes.size > 1) Space.m else Space.l))

            when (shown) {
                ReportMode.CASH_FLOW -> CashFlowReportContent(
                    cash = cash,
                    smsEnabled = smsEnabled,
                    onWindow = onWindow,
                    bottomInset = bottomInset,
                    categories = categories,
                    excluded = excluded,
                    onExcluded = onExcluded,
                    worthIt = worthIt,
                    entries = entries,
                    onOpenEntry = onOpenEntry,
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(cashScroll),
                )
                ReportMode.ASSETS -> AssetReportContent(
                    history = history,
                    current = current,
                    composition = composition,
                    bottomInset = bottomInset,
                    modifier = Modifier
                        .weight(1f)
                        // The composition legend can push past one screen on short phones.
                        .verticalScroll(assetScroll),
                )
            }
        }
    }
}

// ─────────────────────────── دارایی ───────────────────────────

@Composable
private fun AssetReportContent(
    history: Map<Long, Double>,
    current: Double,
    composition: List<Pair<Kind, Double>>,
    bottomInset: Dp,
    modifier: Modifier = Modifier,
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
        // The shortest window that is at least a month: opening on «۱ هفته» would lead with the
        // noisiest read of the money, and a week was never what the door was opened to ask.
        mutableStateOf(
            WINDOWS.firstOrNull { (_, days) -> days != 7 && available(days) }?.first ?: "همه",
        )
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

    Column(modifier) {
        WindowPicker(selected, ::available) { selected = it }

        // One rhythm across both reports: [Space.m] inside a group, [Space.xxl] between them.
        // Every gap on this screen used to be [Space.l] or [Space.xl], which is the same distance
        // either side of a heading — so nothing was grouped and the eye had to read the words to
        // find out where one section stopped.
        Spacer(Modifier.height(Space.xxl))
        if (change == null || points.size < 2) {
            EmptyReport()
        } else {
            // The figure is the chart's headline, so it sits inside the group with it rather than
            // a section apart from the shape it describes.
            ChangeFigure(change, selected, now)
            Spacer(Modifier.height(Space.m))
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
                // No inset of its own. A 4dp indent nothing else on the screen shares is a
                // fourth leading edge in a column that already has enough of them.
                modifier = Modifier.padding(top = Space.m),
            )
        }
        // One kind is the hero total said twice — same rule the list's section heads use.
        if (composition.size > 1) {
            Spacer(Modifier.height(Space.xxl))
            CompositionBar(composition)
        }
        Spacer(Modifier.height(bottomInset + Space.xxl))
    }
}

/**
 * The bar's own palette, not the icon discs' container tints: those are meant to sit quietly
 * behind an emoji, and in the dark theme they all but vanish against the background — an 89%
 * crypto segment read as an empty track. These are the app's green-to-gold family, fixed so
 * they hold on both themes; gold stays reserved for the gold kinds.
 */
private fun barTint(kind: Kind): Color = when (kind) {
    Kind.CASH -> Color(0xFF2F7D3F)
    Kind.FIAT -> Color(0xFF79B989)
    Kind.CRYPTO -> Color(0xFFA3BBA6)
    Kind.GOLD -> Color(0xFFF7C948)
    Kind.SILVER -> Color(0xFFAEB6BD)
    Kind.COIN -> Color(0xFFC99B2C)
    Kind.STOCK -> Color(0xFF4C8A5E)
    Kind.PROPERTY -> Color(0xFF8C7B6B)
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
    // tertiary is the gain role: borrowing primary would set "بیشتر" in the colour of every
    // button on screen, and a verdict must not look like a control.
    change.delta > 0 -> MaterialTheme.colorScheme.tertiary
    else -> MaterialTheme.colorScheme.error
}

/**
 * A window the history is too short to answer is dimmed, not hidden — its absence is data.
 *
 * The same chips, in the same place, as دخل و خرج's [SpanPicker]: it is the same question asked
 * of the other half of her money, and it wore a full-width track while the cash report wore
 * pills — two shapes for one control, met one tab apart. Chips also honour the two-tier rule
 * here: the segment directly above is the report switcher, and slicers wear the small pill.
 * Scrollable because six lengths outgrow a narrow phone; the row starts where the page reads.
 */
@Composable
private fun WindowPicker(
    selected: String,
    available: (Int?) -> Boolean,
    onSelect: (String) -> Unit,
) = ChipChoice(
    options = WINDOWS.map { it.first },
    selected = selected,
    label = { it },
    enabled = { label -> available(WINDOWS.first { it.first == label }.second) },
    onSelect = onSelect,
    modifier = Modifier.horizontalScroll(rememberScrollState()),
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
        // The tab bar's own three bars, not an emoji — the empty state introduces the chart,
        // and it should speak in the pen the chart's own door is drawn with.
        val ink = MaterialTheme.colorScheme.onPrimaryContainer
        Box(
            Modifier
                .size(84.dp)
                .clip(RoundedCornerShape(Radius.pill))
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) { Canvas(Modifier.size(36.dp)) { drawReport(ink) } }
        Spacer(Modifier.height(Space.xl))
        Text("هنوز نموداری نیست", fontSize = 22.sp, fontWeight = FontWeight.Black)
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

// ─────────────────────────── دخل و خرج ───────────────────────────

/** Growth, in the colour every bank app in the country uses for it — the scheme's gain role. */
private val INCOME_TINT: Color
    @Composable get() = MaterialTheme.colorScheme.tertiary

/**
 * The month she picked: what came in, what went out, what remained, how it sits against the
 * months around it, where each side came from, and what changed.
 *
 * Every figure arrives already worked out by [buildCashFlow] — including which month this is
 * and whether it is allowed to say «این ماه» — so nothing on this screen can disagree with
 * anything else on it, and what she reads is what the tests assert.
 */
@Composable
private fun CashFlowReportContent(
    cash: CashFlowReport,
    smsEnabled: Boolean,
    onWindow: (Long, ReportSpan) -> Unit,
    bottomInset: Dp,
    categories: List<Category> = emptyList(),
    excluded: Set<String> = emptySet(),
    onExcluded: (Set<String>) -> Unit = {},
    worthIt: WorthItSummary = WorthItSummary(0, 0, 0),
    entries: List<LedgerEntry> = emptyList(),
    onOpenEntry: ((LedgerEntry) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val period = cash.period
    Column(modifier) {
        // How long a window, directly under which report — one control group, and the picker is
        // the quieter half of it at 14sp against the mode segment's 15. The anchor handed over
        // is the window's last day: for month spans that is a day of the month she is reading
        // (so the anchor month stands still, as it always did), and for «۱ هفته» it is the day
        // whose week she should land in — the report clamps a half-written month's future tail
        // back to today on its own.
        SpanPicker(cash) { onWindow(cash.range.endDay - 1, it) }

        // The window's name is the heading every figure below is filed under, so it takes the
        // section gap above it and the group gap below — the same rhythm as [AssetReportContent].
        // It used to be wedged against the picker with no gap at all, which read as a third row
        // of the same control.
        Spacer(Modifier.height(Space.xxl))
        WindowNavigator(cash, onWindow)

        Spacer(Modifier.height(Space.m))
        if (period.transactions == 0) {
            // A window with nothing in it is two different facts. This month with nothing in it
            // yet is a thing to do; a past one with nothing in it may simply be older than the
            // ledger, and printing zeros for it would report months nobody watched.
            if (cash.current) {
                // The window's own name: an empty current week must not claim the month is empty.
                QuietStart(smsEnabled, named = cash.range.recentFa)
            } else {
                EmptyMonth(cash.range)
            }
        } else {
            MonthSummary(period, cash.current)
        }

        // Two months *with something in them* is the least a comparison can be made of. One
        // lived-in month beside empty neighbours is a chart that answers «is this month unusual»
        // with the month itself — and the empty neighbours draw as bare floating numerals.
        if (cash.series.count { it.incomeRial > 0 || it.spentRial > 0 } > 1) {
            Spacer(Modifier.height(Space.xxl))
            MonthBars(cash, onWindow)
        }

        if (period.transactions > 0) {
            Spacer(Modifier.height(Space.xxl))
            CategoryDetail(period, entries, onOpenEntry)
        }

        // Only on a ledger the family actually shares — see [memberShares], which returns
        // nothing when every row in the window belongs to the same person.
        if (cash.members.isNotEmpty()) {
            Spacer(Modifier.height(Space.xxl))
            MemberDetail(cash.members, period, entries, excluded, onOpenEntry)
        }

        // Under the categories it governs, and present even when the window is empty while an
        // exclusion stands — a report quietly missing a category with no way to see why is the
        // friendlier kind of lie this screen refuses everywhere else.
        if (categories.isNotEmpty()) {
            Spacer(Modifier.height(Space.l))
            ReportExclusions(categories, excluded, onExcluded)
        }

        if (worthIt.total > 0) {
            Spacer(Modifier.height(Space.xxl))
            WorthItDetail(worthIt, cash.range)
        }

        // Findings and wins in one grid, not two runs of one: paired by kind, each run could end
        // on a lone card and leave a hole mid-page.
        val cards = cash.insights + cash.wins
        if (cards.isNotEmpty()) {
            Spacer(Modifier.height(Space.xxl))
            InsightGrid(cards)
        }
        Spacer(Modifier.height(bottomInset + Space.xxl))
    }
}

/**
 * How much of the ledger she is reading — the same control, in the same place, as the one over
 * گزارش دارایی, because it is the same question asked of the other half of her money.
 *
 * A length the ledger cannot fill is dimmed rather than hidden, which is the asset picker's rule
 * and matters more here: «۱ سال» greyed out on a two-month-old install says *the app has not
 * watched you for a year yet*, and «۱ سال» simply missing says nothing at all.
 */
@Composable
private fun SpanPicker(cash: CashFlowReport, onSelect: (ReportSpan) -> Unit) = ChipChoice(
    // Chips, not a second full-width track: the segment directly above this is the screen
    // switcher, and two identical tracks stacked read as one control drawn twice. Slicers wear
    // the small pill; there is exactly one big track per screen. Scrollable for the same
    // reason the asset picker is — five lengths outgrow a 320dp phone before they outgrow
    // their usefulness.
    options = ReportSpan.entries.toList(),
    selected = cash.span,
    label = { it.fa },
    enabled = { it in cash.spans },
    onSelect = onSelect,
    modifier = Modifier.horizontalScroll(rememberScrollState()),
)

/**
 * Which window she is reading, and the two steps either side of it.
 *
 * The name leads and the stepper trails, rather than a centred caption held between two arrows at
 * the far edges of the glass. Three things came out of that arrangement, and all three were
 * wrong. The name is the heading of everything below it and sat on an edge nothing else on the
 * screen shares, so the one line that says *what you are reading* was the only line not aligned
 * with what you are reading. It had a phone's width of empty air either side of it and still had
 * to shrink to 13sp, because the space it was actually given was what two 48dp buttons left over.
 * And the arrows themselves were bare glyphs pressed against the layout margin — the only
 * controls in the app with nothing under them, in a screen whose every other control sits on a
 * shaped ground.
 *
 * So: the name takes the leading edge at the weight a heading deserves, and the two arrows become
 * one stepper at the trailing end, each on the same [surfaceContainer] well the picker above them
 * is cut from. The pair reads as a single object that moves the window, which is what it is.
 *
 * The arrows are auto-mirrored, so in this RTL app «ماه قبل» is still the one nearer the start of
 * the line — the direction the page itself reads backwards in. Neither is ever hidden: an arrow
 * that vanishes at the end of the ledger takes the answer «there is nothing before this» with it,
 * so it is dimmed and stays disabled to anyone listening as well as anyone looking.
 *
 * A step is one month even when the window is twelve. Stepping by the whole window would make
 * «قبل» jump a year at a time and put every month she wanted to look at in the middle of a
 * stride; a month at a time slides the year over the ledger, which is what the arrows are for.
 * The one exception is the one span not made of months: over «۱ هفته» the arrows step the week,
 * because a month-stride over a week-wide window would skip three of every four of them.
 */
@Composable
private fun WindowNavigator(cash: CashFlowReport, onWindow: (Long, ReportSpan) -> Unit) {
    val weekly = cash.range.week != null
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            Modifier
                .weight(1f)
                // The title is the way back. Walking home from خرداد is otherwise four taps of
                // an arrow she has to count, and the one thing on the screen that always names
                // where she is, is the thing she is already looking at when she wants to leave.
                // Clipped before the click so the ripple wears the screen's own rounding, and
                // inert where she is already standing in the window.
                .clip(RoundedCornerShape(Radius.field))
                .then(
                    if (cash.current) {
                        Modifier
                    } else {
                        Modifier.clickable(
                            role = Role.Button,
                            onClickLabel = if (weekly) "برگشت به این هفته" else "برگشت به این ماه",
                            // The day, not the month: «۱ هفته» anchors on it, and the first of
                            // the month would land her in a week she is not in.
                            onClick = { onWindow(cash.today, cash.span) },
                        )
                    },
                )
                .padding(vertical = Space.xs),
        ) {
            BasicText(
                // The window's own name, never the span's: «خرداد تا مرداد ۱۴۰۵» is the thing
                // every figure below is about, and «۳ ماه» is already said by the picker above.
                //
                // Auto-shrinking for the reason the hero figures do: «شهریور ۱۴۰۴ تا مرداد ۱۴۰۵»
                // is three times the width of «مرداد ۱۴۰۵», and a title that ends in «…» is a
                // window she cannot read the far end of. The floor is 16 rather than 13 now that
                // the line has the width of the screen less one stepper to say itself in.
                text = cash.range.fa,
                // Two lines where there are two ends to name: on a 320dp phone «شهریور ۱۴۰۴ تا
                // مرداد ۱۴۰۵» — or a week's «۳ تا ۹ شهریور ۱۴۰۵» — does not fit on one at any
                // size worth reading, and it breaks at «تا», which is where it would be read
                // aloud.
                maxLines = if (cash.range.count == 1 && !weekly) 1 else 2,
                autoSize = TextAutoSize.StepBased(minFontSize = 16.sp, maxFontSize = 26.sp),
                style = figureStyle(MaterialTheme.colorScheme.onSurface, FontWeight.ExtraBold),
                modifier = Modifier.semantics { heading() },
            )
            // The month she is standing in is half-written. Saying so is the difference between
            // a small month and a month that is not over — and it says *how much* of it is
            // written, because «تا امروز» over a title that reads شهریور ۱۴۰۵ is the one line on
            // the screen that names a day without ever saying which one.
            //
            // Off that window the same line carries the way back instead, in the accent every
            // other tappable word on the screen wears: a title that can be tapped has to say so
            // somewhere, and this is the line directly under it that is otherwise empty.
            val at = jalaliOf(cash.today)
            Text(
                if (cash.current) {
                    "تا امروز ${faNumber(at.day.toDouble())} ${MONTHS[at.month - 1]}"
                } else if (weekly) {
                    "برگشت به این هفته"
                } else {
                    "برگشت به این ماه"
                },
                fontSize = 12.sp,
                color = if (cash.current) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.primary
                },
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        // Clear of the longest month name before the stepper starts, so a two-line range and the
        // buttons never look like one run-on object.
        Spacer(Modifier.width(Space.m))
        StepButton(
            icon = Icons.AutoMirrored.Rounded.KeyboardArrowLeft,
            label = if (weekly) "هفتهٔ قبل" else "ماه قبل",
            enabled = cash.canGoBack,
            onClick = {
                onWindow(
                    if (weekly) cash.range.startDay - 7 else cash.selected.previous().startDay,
                    cash.span,
                )
            },
        )
        // The 48dp targets would otherwise touch, which is the one gap Android names a number for.
        Spacer(Modifier.width(Space.s))
        StepButton(
            icon = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
            label = if (weekly) "هفتهٔ بعد" else "ماه بعد",
            enabled = cash.canGoForward,
            onClick = {
                onWindow(
                    if (weekly) cash.range.startDay + 7 else cash.selected.next().startDay,
                    cash.span,
                )
            },
        )
    }
}

/**
 * One step of the window: a 40dp well inside a 48dp target.
 *
 * The well is what the picker's track, the chart's selected column and the empty report's disc are
 * all drawn on, so the stepper is made of the screen's own material rather than of a new one. It
 * dims with the button, because an arrow that has nowhere left to go has to look spent as well as
 * refuse the tap — at the end of the ledger the icon alone went grey against a ground that stayed
 * bright, and read as a control that had simply lost its label.
 */
@Composable
private fun StepButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick, enabled = enabled) {
        Box(
            Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(
                    MaterialTheme.colorScheme.surfaceContainer
                        .copy(alpha = if (enabled) 1f else 0.4f),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = label)
        }
    }
}

/** A window the ledger has nothing for — which is not the same as one in which nothing happened. */
@Composable
private fun EmptyMonth(range: ReportRange) {
    Panel {
        Text(
            "در ${range.fa} تراکنشی ثبت نشده",
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(Space.xs))
        Text(
            "دفترت از وقتی پیامک‌ها روشن شدن پر می‌شه، پس ممکنه این ماه قبل از اون باشه.",
            fontSize = 13.sp,
            lineHeight = 22.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * What came in against what went out, and then the one line that settles it.
 *
 * The figures and the bar are the home card's language — lengths off one baseline, so the answer
 * arrives before any number is read — with the exact Toman under each, which is the thing the
 * report has room for and the card did not. The net is stated separately because it is a
 * different question: not «which was bigger» but «what is left», and a deficit is never printed
 * as a negative amount under the word «مانده».
 *
 * What this card no longer carries is the pass-through machinery — the third قرض column and the
 * «حسابش کن» switch. Which categories the report counts is one question with one answer now,
 * the exclusion sheet under دسته‌ها, and قرض و همسر ride it as its shipped default rather than
 * as a second mechanism beside it.
 */
@Composable
private fun MonthSummary(month: PeriodReport, current: Boolean) {
    val spend = MaterialTheme.colorScheme.onSurfaceVariant
    // «درآمد این ماه» on the month she is standing in, because a half-written month has to say
    // so where the figure is. On a longer window it is «درآمد» and nothing else: «۶ ماه گذشته»
    // does not fit in half a phone's width beside the word, the title two lines up already names
    // the months, and «تا امروز» under it already says the last of them is not over.
    val named = if (current && month.range.count == 1) " ${month.range.recentFa}" else ""
    Panel {
        Row(Modifier.fillMaxWidth()) {
            FlowSide(
                "درآمد$named",
                month.incomeRial,
                INCOME_TINT,
                Modifier.weight(1f),
            )
            Spacer(Modifier.width(Space.m))
            FlowSide(
                "خرج$named",
                month.spentRial,
                MaterialTheme.colorScheme.onSurface,
                Modifier.weight(1f),
            )
        }
        if (month.incomeRial > 0 || month.spentRial > 0) {
            Spacer(Modifier.height(Space.l))
            FlowSplit(
                listOf(
                    month.incomeRial to INCOME_TINT,
                    month.spentRial to spend,
                ),
            )
        }

        Spacer(Modifier.height(Space.l))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Spacer(Modifier.height(Space.m))

        val short = month.netRial >= 0
        val tone = if (short) INCOME_TINT else MaterialTheme.colorScheme.error
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                // The word carries it, not the colour: «کسری» is a different thing from a
                // «مانده» that happens to be printed in red, and only one of the two is true.
                if (short) "مانده$named" else "کسری$named",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
            Spacer(Modifier.width(Space.m))
            // The weight is on the figure, not on the label, and that is the whole fix. A Row
            // measures its unweighted children first, so whichever of the two carries the weight
            // is the one that yields: with it on the label, a nine-figure «مانده» took the width
            // it wanted and «مانده این ماه» broke after «این». The label is a short fixed string
            // and the figure is the thing that grows by orders of magnitude, so the figure is
            // what shrinks — exactly as the two figures above it do.
            BasicText(
                text = bidi(faCompact(tomanOf(abs(month.netRial)))),
                maxLines = 1,
                autoSize = TextAutoSize.StepBased(minFontSize = 15.sp, maxFontSize = 22.sp),
                // Trailing, on the same edge as the exact figure printed under it.
                style = figureStyle(tone, FontWeight.ExtraBold).copy(textAlign = TextAlign.End),
                modifier = Modifier.weight(1f),
            )
        }
        Text(
            "${faNumber(tomanOf(abs(month.netRial)))} تومان",
            fontSize = 11.sp,
            fontFamily = ModamFigures,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = Space.xs),
            textAlign = TextAlign.End,
        )
    }
}

/**
 * One side of the month: the magnitude to read at a glance, the exact figure under it.
 *
 * Both halves step down from the same ceiling so a milliard beside a thousand does not knock
 * the pair out of alignment — the rule the home card's halves already follow.
 */
@Composable
private fun FlowSide(label: String, rial: Long, tone: Color, modifier: Modifier = Modifier) {
    Column(modifier) {
        BasicText(
            text = label,
            maxLines = 1,
            // Shrinks rather than ellipsising, which is the rule the figures under it already
            // follow and matters more here: a third of a 320dp phone renders «قرض و همسر» as
            // «قرض و ه…», and what the ellipsis takes off is the half of the label saying which
            // money this column is. 10sp is the floor because the exact figure below sits at 8.
            //
            // [LocalTextStyle], not [figureStyle]: these are words, and figureStyle is the
            // tabular-numeral face the amounts are set in.
            autoSize = TextAutoSize.StepBased(minFontSize = 10.sp, maxFontSize = 12.sp),
            style = LocalTextStyle.current.copy(
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            ),
        )
        Spacer(Modifier.height(Space.xs))
        BasicText(
            text = bidi(faCompact(tomanOf(rial))),
            maxLines = 1,
            // Down to 13sp before it gives up, not 16: at 16 a year's «۹۳۹٫۵ میلیون» is wider
            // than half a phone, and what it loses off the end is the word — «۹۳۹٫۵» of nothing,
            // silently, because auto-sizing clips rather than ellipsising when it runs out.
            autoSize = TextAutoSize.StepBased(minFontSize = 13.sp, maxFontSize = 26.sp),
            style = figureStyle(tone, FontWeight.ExtraBold),
        )
        // faCompact truncates rather than rounds, so «۱٫۲ میلیون» is never more than she has —
        // and the exact number is right here for when the difference matters. Which is the whole
        // reason it shrinks rather than ellipsising: «۹۳۹,۵۵۳,۹۳۳ تو…» is the exact figure with
        // its unit cut off, on the one line that exists to be exact.
        BasicText(
            text = "${faNumber(tomanOf(rial))} تومان",
            maxLines = 1,
            autoSize = TextAutoSize.StepBased(minFontSize = 8.sp, maxFontSize = 11.sp),
            style = figureStyle(MaterialTheme.colorScheme.onSurfaceVariant, FontWeight.Normal),
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

/**
 * The comparison itself: lengths off one baseline, in the order the figures above are read.
 *
 * The shares are floored for the reason the home card's are — at 8dp tall, two per cent of the
 * row draws as a dot, and a dot reads as *nothing here* rather than as *a little*. Every exact
 * figure is stated directly above, so the floor costs no honesty.
 *
 * Weights rather than one computed share, which is what lets a third band join without the two
 * that were here having to know about it: a floored weight steals its extra width from the other
 * bands in proportion, where the old `1f - share` had to hand all of it to whichever side was
 * not the small one.
 */
@Composable
private fun FlowSplit(parts: List<Pair<Long, Color>>) {
    val shown = parts.filter { it.first > 0L }
    if (shown.isEmpty()) return
    val total = shown.sumOf { it.first }.toFloat()
    Row(
        Modifier.fillMaxWidth().height(8.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        shown.forEach { (rial, tone) ->
            Box(
                Modifier
                    .weight((rial / total).coerceAtLeast(0.06f))
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(Radius.pill))
                    .background(tone),
            )
        }
    }
}

/** How tall the tallest bar in the window draws. */
private val BAR_BOX = 132.dp

/** The shortest a bar may be drawn while still reading as an amount rather than as nothing. */
private const val BAR_FLOOR = 0.03f

/**
 * Six months of دخل و خرج, side by side.
 *
 * One scale across every bar in the window, which is the entire point: scaling each month to
 * itself would draw a two-million month and a two-hundred-million month at the same height and
 * call it a comparison. Time runs right to left, the direction the page reads: the newest month
 * lands on the left — the same side its «ماه بعد» arrow sits on — and the category sheet's own
 * ماه به ماه strip already flows this way, so the row simply inherits the shell's direction.
 *
 * Each month is a control, not a picture: tapping one reads that month on its own — with the
 * span dropping back to «۱ ماه», visibly, so the report never changes what it covers without the
 * picker above saying so — and the whole group carries a single spoken sentence, because six bars
 * read out as twelve loose numbers are not a chart.
 */
@Composable
private fun MonthBars(cash: CashFlowReport, onWindow: (Long, ReportSpan) -> Unit) {
    val spend = MaterialTheme.colorScheme.onSurfaceVariant
    // Over «۱ هفته» every column is a week, so everything month-shaped below — the heading, the
    // selection test, the label, the spoken sentence — reads the week instead. Same chart, same
    // scale rule, one unit down.
    val weekly = cash.range.week != null
    val top = cash.series.maxOf { maxOf(it.incomeRial, it.spentRial) }.coerceAtLeast(1L)
    // A year of months is twice what this row was built for, so the bars thin out to keep twelve
    // of them on a narrow phone. Only the bars: the numerals below them fit a twelfth of a screen
    // at any count, which is the whole reason they are numerals.
    val crowded = cash.series.size > 8
    val barWidth = if (crowded) 7.dp else 12.dp
    // Only worth marking where there is something outside the window to tell it apart from.
    // Never over weeks: the window is exactly one of them, and that one is already the
    // selection.
    val markWindow = !weekly && cash.series.size > cash.range.count

    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                // Not «شش ماه اخیر»: the window is six *at most*, and near the start of the
                // ledger it slides forward, so a heading counting months would be a caption
                // that is only sometimes true.
                if (weekly) "هفته به هفته" else "ماه به ماه",
                fontSize = 15.sp,
                fontWeight = FontWeight.ExtraBold,
                modifier = Modifier
                    .weight(1f)
                    .semantics { heading() },
            )
            BarKey("درآمد", INCOME_TINT)
            Spacer(Modifier.width(Space.m))
            BarKey("خرج", spend)
        }
        Spacer(Modifier.height(Space.m))
        Row(
            Modifier
                .fillMaxWidth()
                .selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(Space.xs),
        ) {
            cash.series.forEach { report ->
                // Read as one month, or read as part of a longer window. Only the first is a
                // selection — at «۶ ماه» nothing here is selected, six months are simply
                // being counted together, and telling a screen reader otherwise would make
                // six bars announce themselves as six chosen tabs. A week is compared by its
                // own start day: several weeks share a month, so the month test that serves
                // every other span would light half the row.
                val only =
                    if (weekly) report.range.startDay == cash.range.startDay
                    else cash.range.count == 1 && report.month == cash.selected
                val inWindow = report.month in cash.range
                Column(
                    Modifier
                        .weight(1f)
                        // Shaped background rather than clip-then-fill: the clip cut off the
                        // month names, which at twelve bars are wider than their own column
                        // by design — see the label below.
                        .background(
                            color = if (only || (inWindow && markWindow)) {
                                MaterialTheme.colorScheme.surfaceContainer
                            } else {
                                Color.Transparent
                            },
                            shape = RoundedCornerShape(Radius.field),
                        )
                        .selectable(
                            selected = only,
                            role = Role.Tab,
                            // A week stays a week when tapped; every other span reads the
                            // tapped month on its own, with the span dropping to «۱ ماه»
                            // visibly, as it always has.
                            onClick = {
                                onWindow(
                                    report.range.startDay,
                                    if (weekly) ReportSpan.WEEK else ReportSpan.MONTH,
                                )
                            },
                        )
                        .heightIn(min = 48.dp)
                        .padding(vertical = Space.s)
                        .semantics(mergeDescendants = true) {
                            contentDescription = "${if (weekly) report.range.fa else report.month.fa}: " +
                                "درآمد ${faCompact(tomanOf(report.incomeRial))} تومان، " +
                                "خرج ${faCompact(tomanOf(report.spentRial))} تومان" +
                                if (inWindow && markWindow) "، در بازهٔ انتخاب‌شده" else ""
                        },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Row(
                        Modifier.height(BAR_BOX),
                        horizontalArrangement = Arrangement.spacedBy(3.dp),
                        verticalAlignment = Alignment.Bottom,
                    ) {
                        Bar(report.incomeRial, top, INCOME_TINT, barWidth)
                        Bar(report.spentRial, top, spend, barWidth)
                    }
                    Spacer(Modifier.height(Space.s))
                    // The month's number, not its name. «فروردین» and «اردیبهشت» are wider
                    // than a sixth of a phone, let alone a twelfth, so every window longer
                    // than a couple of months printed «فرورد…» — a label that has lost the
                    // one syllable telling it apart from «فروردین» of the year before, and
                    // that at twelve bars was only printed on every other month anyway.
                    // «۱» fits any column at any count, so every bar can be labelled again.
                    //
                    // Nothing is lost by it: the window's full name is set above the card,
                    // tapping a bar puts that month's name up there, and the spoken
                    // description on this very column has said «مرداد ۱۴۰۵» all along.
                    //
                    // Tabular figures, so «۱۰» and «۱۱» are the same width as each other and
                    // the row of numerals sits on one baseline grid rather than drifting.
                    //
                    // A week is named by the day of the month its شنبه falls on — «۳»، «۱۰»،
                    // «۱۷» — which is how the two ends of the title above already speak.
                    Text(
                        faNumber(
                            (if (weekly) jalaliOf(report.range.startDay).day
                            else report.month.month).toDouble(),
                        ),
                        style = figureStyle(
                            // Weight and a container, not a colour: the selected month has to
                            // be findable without seeing one.
                            color = if (only) MaterialTheme.colorScheme.onSurface
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            weight = if (only) FontWeight.ExtraBold else FontWeight.Normal,
                        ),
                        fontSize = 11.sp,
                        maxLines = 1,
                        // Measured outside its own column, because a twelfth of a 320dp phone
                        // is not reliably wide enough for two digits: at that width «۱۰»,
                        // «۱۱» and «۱۲» each lost their second digit and drew as a bare «۱»,
                        // which is not a clipped label but a wrong one — three months of the
                        // year silently claiming to be فروردین. A numeral overspills by a
                        // hair rather than by two thirds, so unlike the month names this
                        // never reaches the label beside it.
                        modifier = Modifier.wrapContentWidth(unbounded = true),
                    )
                }
            }
        }
    }
}

/** One bar, against the chart's own ceiling. Nothing at all is what nothing looks like. */
@Composable
private fun Bar(rial: Long, top: Long, tone: Color, width: Dp) {
    if (rial <= 0L) {
        Spacer(Modifier.width(width))
        return
    }
    Box(
        Modifier
            .width(width)
            .fillMaxHeight((rial.toFloat() / top).coerceIn(BAR_FLOOR, 1f))
            .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
            .background(tone),
    )
}

@Composable
private fun BarKey(label: String, tone: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(8.dp)
                .clip(RoundedCornerShape(Radius.pill))
                .background(tone),
        )
        Spacer(Modifier.width(Space.xs))
        Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * Where each side of the month came from.
 *
 * Both sides, not just spending: «حقوق» and «فروش» and «پس‌گرفتن قرض» are different answers to
 * "where did it come from", and the report used to throw all of them into one figure while
 * giving the other side six rows. The list is not truncated here either — this is the screen
 * she came to for the detail, so the sixth-largest category is not the app's to withhold.
 */
@Composable
private fun CategoryDetail(
    period: PeriodReport,
    entries: List<LedgerEntry>,
    onOpenEntry: ((LedgerEntry) -> Unit)? = null,
) {
    // خرج unless there is nothing on that side to show, which is the only case where opening on
    // it would be an empty screen with a full one one tap away.
    var side by rememberSaveable {
        mutableStateOf(
            if (period.spentRial <= 0 && period.incomeRial > 0) LedgerLens.INCOME
            else LedgerLens.EXPENSE,
        )
    }
    val income = side == LedgerLens.INCOME
    val rows = if (income) period.incomeByCategory else period.spendingByCategory
    val total = if (income) period.incomeRial else period.spentRial
    // The window written out, never «این ماه»: this line states which months were looked in and
    // found empty, and that is a fact about named months rather than about where she is standing.
    val named = period.range.fa
    // The category she has opened up, or null. Name and side together, because «خوراک» can in
    // principle exist on both sides of the ledger and the sheet must know which one she tapped.
    var opened by rememberSaveable { mutableStateOf<String?>(null) }

    Column {
        // Heading and lens on one line: the heading names the section and the chips slice it,
        // which is the whole two-tier rule — the screen's one full-width track is the mode
        // switcher at the top, and everything below it wears the small pill.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "دسته‌ها",
                fontSize = 15.sp,
                fontWeight = FontWeight.ExtraBold,
                modifier = Modifier.weight(1f).semantics { heading() },
            )
            ChipChoice(
                options = listOf(LedgerLens.EXPENSE, LedgerLens.INCOME),
                selected = side,
                label = { it.fa },
                onSelect = { side = it },
            )
        }
        Spacer(Modifier.height(Space.m))
        if (rows.isEmpty() || total <= 0L) {
            Text(
                if (income) "در $named درآمدی ثبت نشده." else "در $named خرجی ثبت نشده.",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = Space.m),
            )
        } else {
            for ((name, rial) in rows) {
                CategoryShare(
                    name,
                    rial,
                    total,
                    if (income) INCOME_TINT else null,
                    onOpen = { opened = name },
                )
            }
        }
    }

    opened?.let { name ->
        val window = remember(name, income, entries, period) {
            categoryWindow(entries, name, income, period.range, total)
        }
        CategorySheet(
            window = window,
            onOpenEntry = onOpenEntry,
            onDismiss = { opened = null },
        )
    }
}

/**
 * The window split by who each row belongs to — the same figures as «دسته‌ها», grouped the
 * other way, in the same two-lens construction so the two lists read as one chart asked two
 * questions: «خرج» by who paid, «درآمد» by who it reached.
 */
@Composable
private fun MemberDetail(
    members: List<MemberShare>,
    period: PeriodReport,
    entries: List<LedgerEntry>,
    excluded: Set<String>,
    onOpenEntry: ((LedgerEntry) -> Unit)? = null,
) {
    val named = period.range.fa
    var side by rememberSaveable {
        mutableStateOf(
            if (members.all { it.spentRial <= 0L } && members.any { it.incomeRial > 0L }) {
                LedgerLens.INCOME
            } else {
                LedgerLens.EXPENSE
            },
        )
    }
    val income = side == LedgerLens.INCOME
    val rows = members
        .map { it to if (income) it.incomeRial else it.spentRial }
        .filter { it.second > 0L }
        .sortedByDescending { it.second }
    val total = rows.sumOf { it.second }
    // The member she has opened up, by id — the one thing about a member that outlives a name.
    var opened by rememberSaveable { mutableStateOf<String?>(null) }

    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "اعضا",
                fontSize = 15.sp,
                fontWeight = FontWeight.ExtraBold,
                modifier = Modifier.weight(1f).semantics { heading() },
            )
            ChipChoice(
                options = listOf(LedgerLens.EXPENSE, LedgerLens.INCOME),
                selected = side,
                label = { it.fa },
                onSelect = { side = it },
            )
        }
        Spacer(Modifier.height(Space.m))
        if (rows.isEmpty() || total <= 0L) {
            Text(
                if (income) "در $named درآمدی از اعضا ثبت نشده." else "در $named خرجی از اعضا ثبت نشده.",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = Space.m),
            )
        } else {
            for ((member, rial) in rows) {
                MemberShareRow(member, rial, total, if (income) INCOME_TINT else null) {
                    opened = member.id
                }
            }
        }
    }

    // Her rows, on the side she is reading, in the sheet the category rows open — because «هر
    // عددی به یک تراکنش برمی‌گرده» is as true of a member's bar as of a category's.
    opened?.let { id ->
        members.firstOrNull { it.id == id }?.let { member ->
            val window = remember(member, income, entries, period, excluded) {
                memberWindow(
                    entries, member, income, period.range, total,
                    period.countedPassThrough, excluded,
                )
            }
            CategorySheet(
                window = window,
                onOpenEntry = onOpenEntry,
                onDismiss = { opened = null },
            )
        }
    }
}

/**
 * One member, as an amount and as a share of that side — [CategoryShare]'s construction with
 * the category disc swapped for the family's initial disc, so the row above and the row below
 * state their figures on the same edges and the same scale.
 */
@Composable
private fun MemberShareRow(
    member: MemberShare,
    rial: Long,
    total: Long,
    tint: Color?,
    onOpen: (() -> Unit)? = null,
) {
    val share = (rial.toFloat() / total).coerceIn(0f, 1f)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.field))
            .then(
                if (onOpen != null) {
                    Modifier.clickable(
                        role = Role.Button,
                        onClickLabel = "تراکنش‌های ${member.name}",
                        onClick = onOpen,
                    )
                } else {
                    Modifier
                },
            )
            .padding(vertical = Space.s)
            .semantics(mergeDescendants = true) {
                contentDescription = "${member.name}: ${faCompact(tomanOf(rial))} تومان، " +
                    "${faNumber(round(share * 100.0))} درصد"
            },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // The face they picked — the family screen's own disc, at the category disc's size.
            MemberFace(member.name, member.avatar, size = 32.dp)
            Spacer(Modifier.width(Space.m))
            Text(member.name, Modifier.weight(1f), color = MaterialTheme.colorScheme.onBackground)
            Spacer(Modifier.width(Space.s))
            Text(
                bidi(faCompact(tomanOf(rial))),
                style = figureStyle(MaterialTheme.colorScheme.onSurfaceVariant, FontWeight.Bold),
                fontSize = 14.sp,
            )
            Spacer(Modifier.width(Space.s))
            Text(
                "${faNumber(round(share * 100.0))}٪",
                style = figureStyle(MaterialTheme.colorScheme.onSurfaceVariant, FontWeight.Bold),
                fontSize = 12.sp,
                textAlign = TextAlign.End,
                modifier = Modifier.widthIn(min = 36.dp),
            )
            if (onOpen != null) {
                Icon(
                    Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
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
                    .fillMaxWidth(share)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(3.dp))
                    .background(tint ?: MaterialTheme.colorScheme.primary),
            )
        }
    }
}

/**
 * One category, as an amount and as a share of its own side.
 *
 * The share is the figure that survives inflation: a month's «خوراکی» in Toman says nothing
 * about last month's «خوراکی» in Toman, while a third of the month's spending is a third of the
 * month's spending in any year. The bar is on one scale within the side, so the rows are
 * comparable with each other and with nothing else.
 */
@Composable
private fun CategoryShare(
    name: String,
    rial: Long,
    total: Long,
    tint: Color?,
    onOpen: (() -> Unit)? = null,
) {
    val share = (rial.toFloat() / total).coerceIn(0f, 1f)
    Column(
        Modifier
            .fillMaxWidth()
            // Clipped before the click so the ripple wears the row's own rounding, and padded
            // inside it so the target reaches the full row height.
            .clip(RoundedCornerShape(Radius.field))
            .then(
                if (onOpen != null) {
                    Modifier.clickable(role = Role.Button, onClickLabel = "جزئیات $name", onClick = onOpen)
                } else {
                    Modifier
                },
            )
            .padding(vertical = Space.s)
            .semantics(mergeDescendants = true) {
                contentDescription = "$name: ${faCompact(tomanOf(rial))} تومان، " +
                    "${faNumber(round(share * 100.0))} درصد"
            },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // The mark says which category this is and the bar says how much of the side it
            // took — colouring both would have the same row answering one question twice, and
            // the bars stop being comparable the moment they stop being the same colour.
            // On its disc, as everywhere a category leads a row now.
            val hue = categoryHue(name)
            Box(
                Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(hue.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center,
            ) { CategoryIcon(name, hue, size = 17.dp, stroke = 1.5.dp) }
            Spacer(Modifier.width(Space.m))
            // Wraps rather than truncates: «دسته‌بندی نشده» beside a percentage and a figure is
            // one character over the line on a narrow phone, and a clipped category name is the
            // one thing in the row she cannot reconstruct from the rest of it.
            Text(name, Modifier.weight(1f), color = MaterialTheme.colorScheme.onBackground)
            // A long category name fills its weight and would otherwise butt straight against the
            // figure, which is the one pair on the row that must never read as a single string.
            Spacer(Modifier.width(Space.s))
            // The amount before the share, and the share in a box wide enough for «۱۰۰٪».
            //
            // With the amount last, its own width — «۴۰۲٫۱ میلیون» against «۲٫۶ میلیون» — moved
            // the share sideways on every row, so the percentages ran down the column on no edge
            // at all. Putting the fixed-width thing at the end pins both: the shares line up on
            // the box, and the amounts line up on where the box begins. It also reads in the
            // right order now, the figure first and the comparison after it, which is the order
            // the bar underneath states them in.
            Text(
                bidi(faCompact(tomanOf(rial))),
                style = figureStyle(MaterialTheme.colorScheme.onSurfaceVariant, FontWeight.Bold),
                fontSize = 14.sp,
            )
            Spacer(Modifier.width(Space.s))
            Text(
                "${faNumber(round(share * 100.0))}٪",
                style = figureStyle(MaterialTheme.colorScheme.onSurfaceVariant, FontWeight.Bold),
                fontSize = 12.sp,
                textAlign = TextAlign.End,
                // A floor rather than a width: at a large system font size the box has to give.
                modifier = Modifier.widthIn(min = 36.dp),
            )
            if (onOpen != null) {
                // The one mark that says the row opens. Auto-mirrored, so it points the way a
                // Persian page reads forward.
                Icon(
                    Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
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
                    .fillMaxWidth(share)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(3.dp))
                    .background(tint ?: MaterialTheme.colorScheme.primary),
            )
        }
    }
}

/**
 * One category, opened up: the figure, its share of its side, six months of it side by side,
 * and the transactions the figure is made of — each one a door to its own page, because a share
 * bar is as accountable to «چرا این را می‌بینم؟» as any other number in this app.
 *
 * A sheet rather than a page: she is mid-read on the report, and the question «خرید روزانه چرا
 * این‌قدر شد؟» is a glance down and back, not a journey.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CategorySheet(
    window: CategoryWindow,
    onOpenEntry: ((LedgerEntry) -> Unit)?,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    fun close(then: () -> Unit) = scope.launch { sheetState.hide(); then() }
    // A member has no category hue to borrow — a person's name through [categoryHue] would pick
    // a colour that means «خوراک» somewhere else on the screen — so the side she is reading
    // colours the sheet instead, which is the colour her bar already wore.
    val hue = when {
        window.face == null -> categoryHue(window.name)
        window.income -> INCOME_TINT
        else -> MaterialTheme.colorScheme.primary
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = Radius.sheet, topEnd = Radius.sheet),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        LazyColumn(
            Modifier.navigationBarsPadding(),
            contentPadding = PaddingValues(start = Space.xl, end = Space.xl, bottom = Space.l),
        ) {
            item(key = "head") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (window.face != null) {
                        MemberFace(window.name, window.face)
                    } else {
                        Box(
                            Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(hue.copy(alpha = 0.18f)),
                            contentAlignment = Alignment.Center,
                        ) { CategoryIcon(window.name, hue, size = 24.dp) }
                    }
                    Spacer(Modifier.width(Space.m))
                    Column(Modifier.weight(1f)) {
                        Text(
                            window.name,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.semantics { heading() },
                        )
                        Text(
                            window.range.fa,
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.height(Space.l))
                BasicText(
                    text = bidi("${faCompact(tomanOf(window.totalRial))} تومان"),
                    maxLines = 1,
                    autoSize = TextAutoSize.StepBased(minFontSize = 22.sp, maxFontSize = 34.sp),
                    style = figureStyle(
                        if (window.income) INCOME_TINT else MaterialTheme.colorScheme.onSurface,
                        FontWeight.Black,
                    ),
                )
                window.share?.let { share ->
                    Spacer(Modifier.height(Space.xs))
                    Text(
                        "${faNumber(round(share * 100.0))}٪ از " +
                            "${if (window.income) "درآمد" else "خرج"} این بازه",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (window.trend.any { it.second > 0 }) {
                item(key = "trend") {
                    SheetLabel("ماه به ماه")
                    CategoryTrend(window.trend, hue)
                }
            }

            if (window.rows.isNotEmpty()) {
                item(key = "rows_head") { SheetLabel("تراکنش‌های این بازه") }
                itemsIndexed(window.rows, key = { _, e -> e.txn.ref }) { i, e ->
                    // The timeline's own row, in the timeline's own band — a transaction looks
                    // the same wherever it is met, and tapping it goes where it always goes.
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .clip(bandShape(i, window.rows.size))
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                    ) {
                        // A category's rows are all the one category, so the repeated disc
                        // would be noise there — the header wears that mark once. A member's
                        // rows are not: the disc is the only thing on the line saying which
                        // category each one went to, which is the question a member's sheet is
                        // open for.
                        TimelineRow(e, showIcon = window.face != null) {
                            if (onOpenEntry != null) close { onDismiss(); onOpenEntry(e) }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Six months of one category on one scale, in the category's own hue — the answer to «همیشه
 * این‌قدر بود؟» before the question is asked. Only the two end months are named: six Persian
 * month names across a sheet is a caption war, and the two ends are the axis.
 */
@Composable
private fun CategoryTrend(trend: List<Pair<ReportMonth, Long>>, hue: Color) {
    val top = trend.maxOf { it.second }.coerceAtLeast(1L)
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Space.s),
        verticalAlignment = Alignment.Bottom,
    ) {
        trend.forEachIndexed { index, (month, rial) ->
            Column(
                Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(maxOf(4f, 80f * rial.toFloat() / top.toFloat()).dp)
                        .clip(RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp))
                        .background(
                            if (rial > 0) hue
                            else MaterialTheme.colorScheme.surfaceVariant,
                        ),
                )
                Spacer(Modifier.height(Space.xs))
                Text(
                    if (index == 0 || index == trend.lastIndex) MONTHS[month.month - 1] else "",
                    fontSize = 10.sp,
                    maxLines = 1,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * «به نظر خودت» — what she answered when the deck asked «ارزش داشت؟», summed over this window.
 *
 * It lived under آینده, where it was a stray card speaking for all time: a verdict on spending
 * belongs where spending is taken apart, and windowed to the same months as every figure above
 * it, or it would be the one section on the screen describing a different stretch of time.
 *
 * Zero rows are skipped outright — a Persian zero at this size is a floating dot, not a figure,
 * and three rows of which two are dots is exactly the card that read as broken.
 */
@Composable
private fun WorthItDetail(summary: WorthItSummary, range: ReportRange) {
    Column {
        Text(
            "به نظر خودت",
            fontSize = 15.sp,
            fontWeight = FontWeight.ExtraBold,
            modifier = Modifier.semantics { heading() },
        )
        Spacer(Modifier.height(Space.xs))
        Text(
            "جمع جواب‌هایی که به «ارزش داشت؟» دادی، در ${range.fa}.",
            fontSize = 13.sp,
            lineHeight = 20.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(Space.m))
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(Radius.card))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(horizontal = Space.l, vertical = Space.s),
        ) {
            if (summary.worth > 0) {
                WorthItLine("ارزش داشت", summary.worth, MaterialTheme.colorScheme.tertiary)
            }
            if (summary.needed > 0) {
                WorthItLine("لازم بود", summary.needed, MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (summary.regretted > 0) {
                // The caution amber, not the brand green: her own «نه» is the one figure here
                // that asks to be acted on, and the action colour would read as approval.
                WorthItLine("ارزش نداشت", summary.regretted, MaterialTheme.colorScheme.secondary)
            }
        }
        if (summary.regretted > 0) {
            Spacer(Modifier.height(Space.s))
            Text(
                // The whole reason for asking: "spend less" is advice nobody can act on; this is
                // a figure attached to decisions she herself called mistaken.
                "«ارزش نداشت» تنها رقمیه که می‌شه روش کاری کرد — خرجیه که خودت گفتی می‌شد نباشه.",
                fontSize = 13.sp,
                lineHeight = 21.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun WorthItLine(label: String, rial: Long, tone: Color) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = Space.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            Modifier.weight(1f),
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            bidi("${faCompact(tomanOf(rial))} تومان"),
            style = figureStyle(tone, FontWeight.Bold),
            fontSize = 14.sp,
        )
    }
}

/**
 * What this report is deliberately not counting, and the door to changing that.
 *
 * The note is the honesty half and it is not optional: every figure above — the sides, the
 * shares, the savings rate, the bars — is computed without these categories, and a report that
 * hid money without saying so would be lying in exactly the way the pass-through card exists to
 * prevent. The door stays quiet when nothing is excluded, because then there is nothing to
 * confess — only an offer.
 */
@Composable
private fun ReportExclusions(
    categories: List<Category>,
    excluded: Set<String>,
    onExcluded: (Set<String>) -> Unit,
) {
    var choosing by rememberSaveable { mutableStateOf(false) }
    val names = remember(categories, excluded) {
        categories.filter { it.id in excluded }.map { it.nameFa }
    }

    Column {
        if (names.isNotEmpty()) {
            Text(
                "دسته‌های ${names.joinToString("، ")} به خواست خودت توی هیچ‌کدوم از " +
                    "عددهای این گزارش حساب نشدن.",
                fontSize = 13.sp,
                lineHeight = 21.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = Space.m),
            )
        }
        PillButton(
            if (excluded.isEmpty()) "کنار گذاشتن دسته‌ها از گزارش" else "ویرایش دسته‌های کنارگذاشته",
            { choosing = true },
        )
    }

    if (choosing) {
        ExcludeSheet(
            categories = categories,
            excluded = excluded,
            onExcluded = onExcluded,
            onDismiss = { choosing = false },
        )
    }
}

/**
 * Which categories the report leaves out, chosen live: every tap recomputes the figures behind
 * the scrim, so what she is doing is visible as she does it and the sheet needs no apply button.
 *
 * Every category is offered, قرض و همسر included — they used to be governed by their own switch
 * on the month card, and two doors to the same money let the two disagree. They arrive here
 * pre-excluded on a fresh install instead, so the honest default survives with one mechanism.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExcludeSheet(
    categories: List<Category>,
    excluded: Set<String>,
    onExcluded: (Set<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    fun close(then: () -> Unit) = scope.launch { sheetState.hide(); then() }

    val offered = remember(categories) {
        categories.filter {
            !it.archived && it.kind != CategoryKind.TRANSFER && it.id != CAT_UNCATEGORISED
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = Radius.sheet, topEnd = Radius.sheet),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            Modifier
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Space.xl)
                .padding(bottom = Space.l),
        ) {
            SheetTitle("کدوم‌ها حساب نشن؟")
            Text(
                "هر دسته‌ای که بزنی از همهٔ عددهای دخل و خرج کنار گذاشته می‌شه — " +
                    "توی دفتر سر جاش می‌مونه.",
                fontSize = 13.sp,
                lineHeight = 21.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Space.s),
            )

            Spacer(Modifier.height(Space.l))
            FlowRow(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Space.s),
                verticalArrangement = Arrangement.spacedBy(Space.s),
            ) {
                offered.forEach { category ->
                    FilterCategoryChip(
                        name = category.nameFa,
                        chosen = category.id in excluded,
                        onToggle = {
                            onExcluded(
                                if (category.id in excluded) excluded - category.id
                                else excluded + category.id,
                            )
                        },
                    )
                }
            }

            Spacer(Modifier.height(Space.xl))
            PillButton(
                "بستن",
                { close(onDismiss) },
                voice = ButtonVoice.PRIMARY,
                modifier = Modifier.fillMaxWidth(),
                minHeight = 52.dp,
            )
            if (excluded.isNotEmpty()) {
                Spacer(Modifier.height(Space.s))
                PillButton(
                    "هیچ‌کدوم حذف نشه",
                    { onExcluded(emptySet()); close(onDismiss) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
