package com.doxigo.muchtoman

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
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
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/**
 * The activity timeline — what happened, grouped by the day it happened in Tehran.
 *
 * Every row traces back to the message it came from, because "every number can be traced back
 * to a transaction" is not a feature that can be added later: it is either true of every row on
 * the screen or it is not true at all.
 */

internal val MONTHS = listOf(
    "فروردین", "اردیبهشت", "خرداد", "تیر", "مرداد", "شهریور",
    "مهر", "آبان", "آذر", "دی", "بهمن", "اسفند",
)

/** «۳ مرداد ۱۴۰۵», or «امروز» and «دیروز» for the two days she is actually thinking about. */
fun faDay(day: Long, today: Long = tehranDay(System.currentTimeMillis())): String = when (day) {
    today -> "امروز"
    today - 1 -> "دیروز"
    else -> faDate(day)
}

/** The same day, always written out — where «امروز» would be less information rather than more. */
fun faDate(day: Long): String =
    jalaliOf(day).let { "${faNumber(it.day.toDouble())} ${MONTHS[it.month - 1]} ${faYear(it.year)}" }

/**
 * «۱۴:۰۳» — the wall clock in Tehran, zero-padded so two of them line up under each other.
 *
 * Read off the same day arithmetic the ledger files the transaction under, so the hour can never
 * disagree with the date printed beside it.
 */
fun faClock(epochMillis: Long): String {
    val sinceMidnight = epochMillis - tehranDayStart(tehranDay(epochMillis))
    val hour = sinceMidnight / 3_600_000L
    val minute = sinceMidnight / 60_000L % 60
    return faDigits("$hour".padStart(2, '0') + ":" + "$minute".padStart(2, '0'))
}

/**
 * «۳ مرداد ۱۴۰۵، ۱۴:۰۳» — the day a transaction happened, and the minute where one is known.
 *
 * Every writer stamps `day` as `tehranDay(at)`, so the two halves cannot disagree. What they can
 * differ in is how much they know: a message carries the instant the network stamped it, while a
 * row entered from a date alone — a hand-entered one back-dated to last Tuesday — lands on that
 * day's Tehran midnight exactly. Printing «۰۰:۰۰» there would state a minute nobody recorded, so
 * the date stands on its own instead. A message that genuinely arrived at midnight is stamped to
 * the millisecond and keeps its clock.
 */
fun faMoment(at: Long, day: Long): String =
    if (at == tehranDayStart(day)) faDate(day) else "${faDate(day)}، ${bidi(faClock(at))}"

private fun faYear(year: Int): String = faDigits(year.toString())

private fun faDigits(s: String): String = buildString {
    for (c in s) append(if (c in '0'..'9') '۰' + (c - '0') else c)
}

/**
 * Which side of the ledger she is looking at.
 *
 * Not a search and not a date range — three lenses on the same rows, in the order she reads them:
 * «همه» first because it is the one she is in unless she asked for otherwise.
 *
 * [matches] does not invent a second definition of money moving. A transfer leg is neither income
 * nor spending — that is the whole reason both legs are struck through and left out of every total
 * — so it appears under «همه» and nowhere else. A row the parser read no amount from is a stated
 * balance rather than a movement, and it goes the same way.
 */
internal enum class LedgerLens(val fa: String, val emptyFa: String) {
    ALL("همه", ""),
    INCOME("درآمد", "هنوز درآمدی ثبت نشده"),
    EXPENSE("خرج", "هنوز خرجی ثبت نشده"),
    ;

    fun matches(entry: LedgerEntry): Boolean {
        if (this == ALL) return true
        if (entry.transfer) return false
        val signed = entry.txn.signedRial ?: return false
        return if (this == INCOME) signed > 0 else signed < 0
    }
}

@Composable
fun TimelineScreen(
    ledger: LedgerView,
    bottomInset: androidx.compose.ui.unit.Dp,
    /** True when messages are being read and this phone could not tell her one had landed. */
    notifyBlocked: Boolean,
    onReview: () -> Unit,
    onAskNotify: () -> Unit,
    onOpen: (LedgerEntry) -> Unit,
    /** A transaction no message will ever report, typed in by hand. */
    onAddTxn: (() -> Unit)? = null,
) {
    val today = remember { tehranDay(System.currentTimeMillis()) }
    var lens by rememberSaveable { mutableStateOf(LedgerLens.ALL) }
    val listState = rememberLazyListState()
    // Two lists, not one: «nothing here at all» and «nothing on this side» are different facts
    // and get different answers, and telling her the ledger is empty when she has merely filtered
    // it to a side she has none of would be the screen lying about her money.
    val everything = remember(ledger.entries) { ledger.entries.filterNot { it.duplicate } }
    val visible = remember(everything, lens) { everything.filter { lens.matches(it) } }
    val grouped = remember(visible) { visible.groupBy { it.txn.day }.toSortedMap(compareByDescending { it }) }
    val waiting = ledger.review.size

    // The newest of what she just asked for, not wherever the old offset happened to land — two
    // hundred rows into «همه» is the middle of nothing in a list that is now nine rows long.
    // Instant rather than animated: the content was replaced, not moved, so there is no distance
    // to travel and scrolling it would only be a delay wearing choreography.
    LaunchedEffect(lens) { if (visible.isNotEmpty()) listState.scrollToItem(0) }

    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        // statusBars only: the tab bar below owns the bottom, and taking systemBars here
        // would leave a gap the width of the navigation bar above it.
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            BoxWithConstraints(
                Modifier.fillMaxWidth().padding(horizontal = Space.xl, vertical = Space.m),
            ) {
                // Three things want this line and a narrow phone has room for two and a half, so
                // the order they yield in is decided here rather than by whichever measured last.
                // The word on the add pill goes first — its mark still says «افزودن» without it.
                // The count never goes: «مرور ۵۳ مورد» is the one control on the line she has to
                // act on, and a truncated number is worse than no pill at all.
                val labelled = waiting == 0 || maxWidth >= 300.dp
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "دفتر",
                        fontSize = 28.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.onBackground,
                        maxLines = 1,
                        // The backstop under the rule above, for a system font scale no dp
                        // threshold can see coming. It should never be reached on a real phone.
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).semantics { heading() },
                    )
                    if (onAddTxn != null) {
                        AddTxnButton(onAddTxn, labelled)
                        Spacer(Modifier.width(Space.s))
                    }
                    if (waiting > 0) ReviewPill(waiting, onReview)
                }
            }

            if (everything.isEmpty()) {
                EmptyLedger()
                return@Column
            }

            // Between the pill and the list, and only while something is actually waiting: this is
            // an offer to be told about a backlog, and on a ledger with none of one it would be the
            // app asking for a permission it has no use for — which is the launch-time prompt this
            // app deliberately does not do, moved down a screen.
            if (notifyBlocked && waiting > 0) {
                Box(Modifier.padding(horizontal = Space.xl, vertical = Space.s)) {
                    NotifyBlockedCard(
                        "تراکنش‌ها همین‌جا جمع می‌شن، ولی تا اعلان روشن نباشه خبری از تراکنش " +
                            "تازه بهت نمی‌رسه — و دسته‌بندی همون روز خیلی راحت‌تره.",
                        onAskNotify,
                    )
                }
            }

            // Pinned above the list rather than scrolled with it. A filter she cannot see is a
            // filter she forgets she set, and on this screen that reads as money having gone
            // missing — the one thing the ledger must never look like.
            LensPicker(lens) { lens = it }

            if (visible.isEmpty()) {
                LensEmpty(lens) { lens = LedgerLens.ALL }
                return@Column
            }

            LazyColumn(
                Modifier.fillMaxSize(),
                state = listState,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = Space.xl, end = Space.xl, bottom = bottomInset + Space.l,
                ),
            ) {
                for ((day, rows) in grouped) {
                    item(key = "day-$day") {
                        DayBand(day, today, rows, onOpen)
                    }
                }
            }
        }
    }
 * because the moment she paid cash for something is not a moment she is standing in تنظیمات.
 *
 * It says the word. A bare «+» in a disc is a guess she has to spend a tap to check, and the one
 * action on this screen that writes money is not the place to make her guess; «تراکنش» after the
 * mark costs a few millimetres and answers it outright. The pill is the neutral well the chips
 * below wear rather than the review pill's filled gold — two solid golds on one line would both
 * be claiming «this one» — and the mark alone carries the interactive colour, which is what keeps
 * it from reading as a third filter.
 *
 * [labelled] is false only where the line genuinely cannot hold the word — see the header.
 */
@Composable
private fun AddTxnButton(onClick: () -> Unit, labelled: Boolean) {
    Row(
        Modifier
            .clip(RoundedCornerShape(Radius.pill))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .clickable(role = Role.Button, onClickLabel = "تراکنش دستی", onClick = onClick)
            // Square when the word is gone, so it reads as the mark's own disc rather than as a
            // pill that lost its label.
            .then(
                if (labelled) Modifier.heightIn(min = 48.dp).padding(horizontal = Space.l)
                else Modifier.size(48.dp),
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        PlusMark(MaterialTheme.colorScheme.primary, size = 20.dp)
        if (labelled) {
            Spacer(Modifier.width(Space.xs))
            Text(
                "تراکنش",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
        }
    }
}

/**
 * The plus, drawn with the pen every other mark in this app is drawn with. A typed «+» is text
 * doing an icon's job: it sits on a baseline, inherits a weight axis, and lands off-centre in
 * any disc that holds it.
 */
@Composable
internal fun PlusMark(tint: Color, size: androidx.compose.ui.unit.Dp = 20.dp) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val ink = pen(2.2.dp)
        drawPath(Path().apply { moveTo(w * 0.5f, h * 0.14f); lineTo(w * 0.5f, h * 0.86f) }, tint, style = ink)
        drawPath(Path().apply { moveTo(w * 0.14f, h * 0.5f); lineTo(w * 0.86f, h * 0.5f) }, tint, style = ink)
    }
}

/**
}

/**
 * A day, as one object.
 *
 * The rows used to float on the background as a single undifferentiated stack, and the day they
 * belonged to was a 13sp grey line easy to scroll straight past. Banding them is what `group` is
 * for — "the container a band of rows sits in, as one object rather than a stack of cards" — and
 * it lets the heading carry the day's net, which is the one figure the ledger owns and no other
 * screen shows.
 *
 * The figure is the sum of the rows actually in the band, so under a [LedgerLens] it is that day's
 * income or that day's spending rather than its net — which is what the heading of a filtered day
 * should say, and why the picker stays on screen to name which of the three she is reading.
 */
@Composable
private fun DayBand(
    day: Long,
    today: Long,
    rows: List<LedgerEntry>,
    onOpen: (LedgerEntry) -> Unit,
) {
    // Transfers move money between her own accounts. Counting them would show a day that only
    // shuffled funds as a day she earned or spent, which is the whole reason they are struck
    // through in the rows below.
    val moved = rows.filterNot { it.transfer }.mapNotNull { it.txn.signedRial }.filter { it != 0L }
    val net = moved.sum()
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = Space.xl, bottom = Space.s, start = Space.s, end = Space.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            faDay(day, today),
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.weight(1f).semantics { heading() },
        )
        // Only when it is actually summarising. A day holding one movement already prints this
        // exact figure two lines down, and under a [LedgerLens] most days hold exactly one — so
        // the filter turned a rare bit of redundancy into the dominant pattern on the screen. A
        // heading that repeats the row beneath it is noise wearing the costume of a summary.
        if (net != 0L && moved.size > 1) {
            Text(
                faSignedCompact(tomanOf(net), net > 0),
                // A step under the rows it summarises: the day's figure is context for them,
                // not another entry competing with them.
                style = figureStyle(
                    if (net > 0) Color(0xFF2E9E5B) else MaterialTheme.colorScheme.onSurfaceVariant,
                    FontWeight.Bold,
                ),
                fontSize = 13.sp,
            )
        }
    }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.group))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        rows.forEach { entry ->
            TimelineRow(entry, onClick = { onOpen(entry) })
        }
    }
}

/**
 * The one thing asking for her, on the title line rather than across the page.
 *
 * It was a full-width card, and a card is what the app uses for something to read — so the only
 * control on the screen was wearing the costume of content, taking a whole band of the scroll to
 * say what the tab bar already says with a badge. A pill in the empty half of the title line
 * says it in the app's own «this one» gold, names its action instead of describing a state, and
 * gives the day bands the top of the list back.
 */
@Composable
private fun ReviewPill(waiting: Int, onReview: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(Radius.pill))
            .background(MaterialTheme.colorScheme.primary)
            .clickable(role = Role.Button, onClick = onReview)
            .heightIn(min = 48.dp)
            .padding(horizontal = Space.l),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "مرور ${faNumber(waiting.toDouble())} مورد",
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onPrimary,
        )
    }
}

/**
 * Which side she is looking at, in the app's own shape for picking exactly one of a few.
 *
 * Deliberately not a new control. [SegmentedChoice] already answers this question on the report
 * and in settings, and a filter that invented its own chips would be a fourth answer to a question
 * the app settled once. `Role.Tab` and 14sp are the report's window picker exactly, because this is
 * the same kind of question — which slice of the same data am I reading — and it has earned the
 * same kind of answer.
 */
@Composable
private fun LensPicker(lens: LedgerLens, onSelect: (LedgerLens) -> Unit) {
    SegmentedChoice(
        options = LedgerLens.entries,
        selected = lens,
        label = { it.fa },
        onSelect = onSelect,
        role = Role.Tab,
        fontSize = 14.sp,
    )
}

/**
 * A side of the ledger she has nothing on.
 *
 * Not [EmptyLedger]: that one says the ledger is empty and tells her how to fill it, and saying
 * either here would be false. This one names the absence and hands back the only thing that
 * undoes it — a filter with no way out is a dead end she has to guess her way off.
 */
@Composable
private fun LensEmpty(lens: LedgerLens, onClear: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(Space.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            lens.emptyFa,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(Space.m))
        PillButton("همه رو نشون بده", onClear)
    }
}

@Composable
private fun TimelineRow(entry: LedgerEntry, onClick: () -> Unit) {
    val txn = entry.txn
    val incoming = txn.direction == "in"
    val tone = when {
        entry.transfer -> MaterialTheme.colorScheme.onSurfaceVariant
        incoming -> Color(0xFF2E9E5B)
        else -> MaterialTheme.colorScheme.onBackground
    }
    Row(
        Modifier
            .fillMaxWidth()
            // No clip of its own: the band above owns the corners, and a row that rounded its
            // own would cut a notch out of the object it sits inside.
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = Space.m, horizontal = Space.l),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                txnTitleFa(txn),
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                val categoryFa = if (entry.transfer) "انتقال بین حساب‌ها" else entry.categoryFa
                // Same mark *and* same colour as the grid she chose it in. A hundred rows of grey
                // 12sp are read by the shape at the start of the line long before the word is,
                // and in colour they are read before the shape.
                //
                // Nothing had to be special-cased for «دسته‌بندی نشده»: it has no mark of its own,
                // so it falls to DOTS, and DOTS is the one entry in [categoryHue] that returns
                // muted text rather than a hue. A row still waiting for her stays grey while every
                // filed row beside it carries colour — which is the distinction this line is for.
                CategoryIcon(
                    categoryFa,
                    categoryHue(categoryFa),
                    size = 14.dp,
                    stroke = 1.4.dp,
                )
                Spacer(Modifier.width(Space.xs))
                Text(
                    categoryFa,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (entry.ownerName.isNotBlank()) {
                    Spacer(Modifier.width(Space.xs))
                    Text(
                        "از ${entry.ownerName}",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                if (entry.needsReview && !entry.transfer) {
                    Spacer(Modifier.width(Space.xs))
                    // A dot, not a warning. Nothing here is wrong; something is merely unconfirmed.
                    Box(
                        Modifier
                            .size(6.dp)
                            .clip(RoundedCornerShape(3.dp))
                            // Not `tertiary`: in dark theme that is the same green income is
                            // already speaking in, so every unconfirmed row read as a deposit.
                            .background(MaterialTheme.colorScheme.primary),
                    )
                }
            }
        }
        val rial = txn.signedRial ?: txn.amountRial
        if (rial != null) {
            Text(
                faSignedCompact(tomanOf(rial), incoming),
                style = figureStyle(tone, FontWeight.Bold),
                fontSize = 15.sp,
                textDecoration = if (entry.transfer) TextDecoration.LineThrough else null,
            )
        } else {
            Text(
                "مانده",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun EmptyLedger() {
    Column(
        Modifier.fillMaxSize().padding(Space.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
                "هنوز هیچ تراکنشی نیست",
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(Space.s))
        Text(
            "از تنظیمات، پیامک‌های بانکی رو روشن کن تا دفترت خودش پر بشه.",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

internal fun bankNameOf(bank: String): String =
    runCatching { Bank.valueOf(bank) }.getOrDefault(Bank.OTHER).fa

/**
 * What one transaction is called wherever it is named on its own: the merchant, or the bank that
 * reported it when the message named nobody.
 *
 * One function because the row, the hero at the top of a card and the notification that asks her to
 * file it are all naming the same thing, and three copies of the fallback would be three chances for
 * one of them to call it «بانک» while the others call it «بانک سامان».
 */
internal fun txnTitleFa(txn: Txn): String = txn.merchant.ifBlank {
    if (txn.sourceKind == "manual") "مورد دستی" else bankNameOf(txn.bank)
}

@Composable
fun TransactionScreen(
    entry: LedgerEntry,
    categories: List<Category>,
    onCategorise: (LedgerEntry, String, Boolean) -> Unit,
    onLoadSource: suspend (LedgerEntry) -> String,
    onBack: () -> Unit,
    /** What she files things under, which decides the order of the grid. */
    categoryUse: Map<String, Double> = emptyMap(),
    /** Her note on this transaction. Null hides the section — the deck has no room for prose. */
    onNote: ((LedgerEntry, String) -> Unit)? = null,
    /** Takes back a hand-entered row. Only ever offered on one — a message is evidence. */
    onDelete: ((LedgerEntry) -> Unit)? = null,
) {
    val txn = entry.txn
    val incoming = txn.direction == "in"
    var learnSimilar by rememberSaveable(txn.ref) { mutableStateOf(false) }
    var chosen by remember(txn.ref) { mutableStateOf(entry.categoryId) }
    var source by remember(txn.ref) { mutableStateOf<String?>(null) }

    LaunchedEffect(txn.ref) { source = onLoadSource(entry) }
    LaunchedEffect(entry.categoryId) { chosen = entry.categoryId }

    val details = transactionDetails(entry)
    // Worked out once for this transaction and held there. The order follows what she files
    // things under, and filing this one changes that — so recomputing on every recomposition
    // would rearrange the grid under her thumb in the instant after she tapped it. It settles
    // into its new order the next time she opens a transaction, which is the first moment the
    // rearrangement can help rather than startle.
    val choices = remember(txn.ref, categories) {
        categoryChoices(categories, txn.direction, categoryUse)
    }

    // The hero bleeds to both edges and under the status bar, so the gutter belongs to the
    // items rather than to the list.
    val gutter = Modifier.padding(horizontal = Space.xl)

    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .navigationBarsPadding(),
            contentPadding = PaddingValues(bottom = Space.xxl),
        ) {
            item(key = "hero") {
                TransactionHero(
                    entry = entry,
                    incoming = incoming,
                    onBack = onBack,
                )
            }

            item(key = "category_heading") {
                Column(gutter) {
                    Spacer(Modifier.height(Space.xxl))
                    Text(
                        "دسته‌بندی",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.semantics { heading() },
                    )
                    Spacer(Modifier.height(Space.xs))
                    Text(
                        when {
                            entry.transfer -> "این مورد انتقال تشخیص داده شده. با انتخاب یک دسته می‌تونی اصلاحش کنی."
                            entry.needsReview -> "دسته درست رو بزن تا این مورد تایید بشه."
                            else -> "دسته فعلی ${entry.categoryFa} است. برای تغییر، دسته تازه رو بزن."
                        },
                        fontSize = 13.sp,
                        lineHeight = 22.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(Space.m))
                    LearnSimilarToggle(txn, learnSimilar) { learnSimilar = it }
                    Spacer(Modifier.height(Space.l))
                }
            }

            item(key = "categories") {
                // A grid, not a stack of full-width rows: seventeen categories are five lines
                // instead of seventeen screens' worth of scrolling, and the one already on this
                // transaction is visible without hunting for it.
                CategoryGrid(
                    choices = choices,
                    selectedId = chosen,
                    onPick = { category ->
                        chosen = category.id
                        onCategorise(entry, category.id, learnSimilar)
                    },
                    modifier = gutter,
                )
            }

            if (onNote != null) {
                item(key = "note") {
                    Column(gutter) {
                        Spacer(Modifier.height(Space.xxl))
                        SectionHeading("یادداشت")
                        Spacer(Modifier.height(Space.m))
                        NoteField(entry, onNote)
                    }
                }
            }

            item(key = "details") {
                Column(gutter) {
                    Spacer(Modifier.height(Space.xxl))
                    SectionHeading("جزئیات")
                    Spacer(Modifier.height(Space.m))
                    DetailsPanel(details)
                }
            }

            item(key = "source") {
                Column(gutter) {
                    Spacer(Modifier.height(Space.xxl))
                    SectionHeading("منبع")
                    Spacer(Modifier.height(Space.m))
                    // Whole and selectable here, where the message is the subject of the screen
                    // rather than one of the things on it. The deck shows the same text with a
                    // floor under it, because there the grid has to stay reachable.
                    SelectionContainer {
                        Text(
                            sourceText(entry, source),
                            fontSize = 13.sp,
                            lineHeight = 23.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.panel(),
                        )
                    }
                }
            }

            // Only on a row she typed in: a message is evidence, and evidence is not deletable.
            // The two-tap confirm is the budget sheet's own, because one stray touch down here
            // must not erase a transaction.
            if (onDelete != null && txn.sourceKind == "manual") {
                item(key = "delete") {
                    Column(gutter) {
                        Spacer(Modifier.height(Space.xl))
                        SheetDelete("حذف این تراکنش") {
                            onDelete(entry)
                            onBack()
                        }
                    }
                }
            }
/**
 * «برای موارد مشابه هم همین دسته» — the one switch both filing surfaces share.
 *
 * The deck used to ask this as a two-button sheet after every pick, which was this switch wearing
 * a heavier costume — and «فقط همین یکی» is the answer nine times out of ten, so the sheet was a
 * tax on the common case. One control, one default, both screens: off, because a standing rule is
 * the exception she opts into, never the price of filing one receipt.
 *
 * Switched on, the caption becomes [learnedRule]'s exact promise for this transaction — «همیشه»
 * must never be a surprise afterwards, and what it keys on differs card by card.
 */
@Composable
private fun LearnSimilarToggle(txn: Txn, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.field))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .toggleable(
                value = checked,
                role = Role.Switch,
                onValueChange = onChange,
            )
            .padding(Space.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                "برای موارد مشابه هم همین دسته",
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                if (checked) learnedRule(txn) else "موارد قبلی و بعدی مشابه هم اصلاح می‌شن",
                fontSize = 12.sp,
                lineHeight = 19.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(Space.m))
        Switch(checked = checked, onCheckedChange = null)
    }
}
/**
 * Her own words about the row, saved when she says so rather than on every keystroke.
 *
 * The save pill only exists while the draft and the stored note disagree, and its disappearance
 * after the tap is the receipt: what is on screen is what the ledger holds.
 */
@Composable
private fun NoteField(entry: LedgerEntry, onNote: (LedgerEntry, String) -> Unit) {
    var draft by rememberSaveable(entry.txn.ref) { mutableStateOf(entry.note) }
    Column {
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it.take(200) },
            placeholder = { Text("مثلاً کادوی تولد مامان") },
            shape = RoundedCornerShape(Radius.field),
            minLines = 2,
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = "یادداشت تراکنش" },
        )
        if (draft.trim() != entry.note) {
            Spacer(Modifier.height(Space.s))
            PillButton("ذخیره یادداشت", { onNote(entry, draft) }, voice = ButtonVoice.PRIMARY)
        }
    }
}

/**
 * Everything the app knows about one transaction that is not its figure, in reading order.
 *
 * Built here rather than on the screen that shows it, because two screens now show it: the
 * transaction page and the deck. A card in the deck is the same transaction it would be one tap
 * further in, and «which of the two am I looking at» must never change what the app says about it.
 */
private fun transactionDetails(entry: LedgerEntry): List<Pair<String, String>> {
    val txn = entry.txn
    return buildList {
        add("نوع" to when (txn.direction) {
            "in" -> "واریز"
            "out" -> "برداشت"
            else -> "نامشخص"
        })
        entry.ownerName.takeIf { it.isNotBlank() }?.let { add("صاحب تراکنش" to it) }
        entry.categoryEditorName.takeIf { it.isNotBlank() }?.let { add("دسته‌بندی توسط" to it) }
        // The field above says «امروز» for the two days she is thinking about, so the written-out
        // date belongs here — with the minute beside it, which nothing else on the screen states.
        // «زمان ثبت» below is a different fact: the stamp the bank itself printed in the message,
        // which is often the date alone and can lag the minute the message actually arrived.
        add("تاریخ" to faMoment(txn.at, txn.day))
        add("ثبت شده از" to if (txn.sourceKind == "manual") "ورود دستی" else bankNameOf(txn.bank))
        txn.printedAt.takeIf { it.isNotBlank() }?.let { add("زمان ثبت" to bidi(it)) }
        txn.mask.takeIf { it.isNotBlank() }?.let { add("کارت یا حساب" to bidi(it)) }
        txn.refNo.takeIf { it.isNotBlank() }?.let { add("شماره پیگیری" to bidi(it)) }
        txn.balanceRial?.let { add("مانده بعد از تراکنش" to bidi("${faCompact(tomanOf(it))} تومان")) }
        txn.feeRial?.takeIf { it > 0 }?.let { add("کارمزد" to bidi("${faCompact(tomanOf(it))} تومان")) }
    }
}

/** «جزئیات», «منبع» — the one heading a band of a transaction sits under, on either screen. */
@Composable
private fun SectionHeading(text: String) {
    Text(
        text,
        fontSize = 18.sp,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onBackground,
        modifier = Modifier.semantics { heading() },
    )
}

/** [transactionDetails], as the panel both screens set it in. */
@Composable
private fun DetailsPanel(details: List<Pair<String, String>>) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.card))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = Space.l, vertical = Space.m),
    ) {
        details.forEach { (label, value) -> DetailRow(label, value) }
    }
}

/**
 * The message the row was read out of, or the reason it is not here to be read.
 *
 * One function for two screens: the transaction page files it under «منبع», and the deck asks its
 * whole question *about* it. «Why can I not see the text» must not have two different answers
 * depending on which screen she happens to be standing on.
 *
 * [LedgerEntry.ownerMemberId] is not the field to test. That one is filled in with the local
 * member when the transaction is this phone's own, so it is non-blank for almost every row here;
 * `txn.ownerMemberId` is blank unless the row arrived from somebody else's phone, which is the
 * only case where the body genuinely is not on this device.
 */
private fun sourceText(entry: LedgerEntry, source: String?): String = when {
    entry.txn.sourceKind == "manual" -> "این مورد دستی ثبت شده."
    entry.txn.ownerMemberId.isNotBlank() ->
        "متن خام پیامک فقط روی گوشی ${entry.ownerName.ifBlank { "صاحب تراکنش" }} نگه داشته می‌شه."
    source == null -> "در حال خواندن پیامک اصلی..."
    source.isBlank() -> "متن پیامک اصلی پیدا نشد."
    else -> source
}

/**
 * The message the card was read out of, in the panel the transaction page sets it in.
 *
 * The deck used to ask «این خرج رو چی حساب کنم؟» over a bank name, a figure, and nothing else —
 * and the parser reads no merchant out of a transfer, so on those cards the bank name *was* the
 * whole card. A hundred and fifty million Toman from بانک سامان is not a question anybody can
 * answer. The message is, and it was already on the phone: the transaction page has printed it
 * since the beginning, one tap away in a screen the deck does not go through.
 *
 * The one difference from that page is a floor of four lines. Banks write with hard line breaks
 * and Blu takes seven of them to say a hundred million Toman arrived, so printed whole under a
 * grid that already fills the screen it is a scroll with no end in sight. Four lines and a tap
 * for the rest; nothing is hidden, only folded.
 */
@Composable
private fun SourcePreview(entry: LedgerEntry, source: String?) {
    val txn = entry.txn
    var expanded by rememberSaveable(txn.ref) { mutableStateOf(false) }
    // Set by the layout rather than guessed from the string: a message wraps differently at every
    // system font size, and «متن کامل» on a message already showing all of itself is a promise of
    // something that is not there.
    var clipped by remember(txn.ref, source) { mutableStateOf(false) }
    Column(
        Modifier
            .then(
                if (clipped || expanded) {
                    Modifier.clickable(role = Role.Button) { expanded = !expanded }
                } else {
                    Modifier
                }
            )
            .panel(),
    ) {
        Text(
            sourceText(entry, source),
            fontSize = 13.sp,
            lineHeight = 23.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = if (expanded) Int.MAX_VALUE else 4,
            overflow = TextOverflow.Ellipsis,
            // Sticky: expanded, nothing overflows any more, and the way back would vanish under
            // her finger the moment she took it.
            onTextLayout = { if (it.hasVisualOverflow) clipped = true },
        )
        if (clipped || expanded) {
            Spacer(Modifier.height(Space.s))
            Text(
                if (expanded) "کوتاه‌تر" else "متن کامل پیامک",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/**
 * The transaction, in the field the app keeps for a figure that stands on its own.
 *
 * This screen is about one number, and it was setting that number two sizes down from the home
 * total inside a grey card — opting out of the app's strongest device on the one screen with
 * the strongest claim to it. Gold stays the net worth's alone: up here the figure speaks in the
 * ledger's own in/out colours, because whether money came or went is the first thing to read on
 * a transaction and the last thing that should need a second glance.
 */
@Composable
private fun TransactionHero(
    entry: LedgerEntry,
    incoming: Boolean,
    onBack: () -> Unit,
    backLabel: String = "برگشت",
    /** Progress, when the transaction is one of a run being worked through. */
    note: String? = null,
) {
    val txn = entry.txn
    val tone = if (incoming) Hero.mint else Hero.strong
    HeroPanel {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    faDay(txn.day),
                    fontSize = 15.sp,
                    color = Hero.muted,
                    modifier = Modifier.weight(1f),
                )
                note?.let {
                    Text(
                        it,
                        fontSize = 13.sp,
                        fontFamily = ModamFigures,
                        color = Hero.muted,
                    )
                }
                Spacer(Modifier.width(Space.m))
                PillButton(backLabel, onBack, voice = ButtonVoice.HERO)
            }

            Spacer(Modifier.height(Space.s))
            Text(
                txnTitleFa(txn),
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = Hero.strong,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.semantics { heading() },
            )

            Spacer(Modifier.height(Space.xs))
            txn.amountRial?.let { rial ->
                val toman = tomanOf(rial)
                BasicText(
                    text = heroFigure(buildAnnotatedString {
                        // Three steps down, and the first one is what keeps the sign a sign:
                        // set the same size as the digits, the magnitude turns «−» into a dash
                        // joining two words. The sign travels with the digits; «میلیون» and
                        // «تومان» stay in the RTL line so the phrase still ends where a Persian
                        // sentence ends.
                        val (digits, magnitude) = faSignedParts(toman, incoming)
                        append(digits)
                        // Each word space carries a size of its own; inside a shrunk span it
                        // shrinks with it and the words end up touching.
                        magnitude?.let {
                            withStyle(SpanStyle(fontSize = 0.9.em)) { append(" ") }
                            withStyle(
                                SpanStyle(fontSize = 0.58.em, fontWeight = FontWeight.Bold),
                            ) { append(it) }
                        }
                    }, tone),
                    maxLines = 1,
                    autoSize = TextAutoSize.StepBased(minFontSize = 24.sp, maxFontSize = 52.sp),
                    style = figureStyle(tone, FontWeight.Black),
                )
                // faCompact truncates, so the words are the only exact figure on this screen —
                // and the check against reading a میلیون as a میلیارد.
                faWordsToman(toman)?.let { words ->
                    Text(
                        words,
                        fontSize = 14.sp,
                        lineHeight = 24.sp,
                        color = Hero.strong.copy(alpha = 0.93f),
                        modifier = Modifier.padding(top = Space.s),
                    )
                }
            } ?: Text(
                "مبلغ در پیامک مشخص نشده",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                // The field's one caution colour: gold up here already means «the answer».
                color = Hero.warn,
                modifier = Modifier.padding(top = Space.s),
            )
    }
}

/** Four across. Three wastes the row on «خواربار»; five puts «پس‌انداز و سرمایه» on three lines. */
private const val CATEGORY_COLUMNS = 4

/**
 * The categories, as a grid of equal cells rather than a wrap of pills.
 *
 * Pills are sized by their labels, and Persian category names run from three characters to
 * seventeen — so the wrap came out three-two-three-three-two with both edges ragged, and the
 * only thing the eye could do with it was read every word in order. Nothing about it said these
 * are seventeen of one kind of thing. A fixed column does, and it is what lets the mark and its
 * colour do the finding instead of the text.
 *
 * [Modifier.weight] is what makes the cells equal, which is also why the short last row has to
 * be padded: four tiles of one width and then one tile of four widths is not a grid.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CategoryGrid(
    choices: List<Category>,
    selectedId: String?,
    onPick: (Category) -> Unit,
    modifier: Modifier = Modifier,
    // What «selected» means differs by screen: on a transaction it is what the entry is filed
    // as; in the deck it is what she has just picked and not yet confirmed.
    selectedLabel: String = "دسته فعلی",
) {
    FlowRow(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Space.s),
        verticalArrangement = Arrangement.spacedBy(Space.s),
        maxItemsInEachRow = CATEGORY_COLUMNS,
    ) {
        choices.forEach { category ->
            CategoryTile(
                category = category,
                selected = category.id == selectedId,
                onClick = { onPick(category) },
                selectedLabel = selectedLabel,
                // fillMaxRowHeight so a two-line label in one cell lifts its whole row instead
                // of leaving its neighbours short and the row bottom broken.
                modifier = Modifier.weight(1f).fillMaxRowHeight(),
            )
        }
        repeat((CATEGORY_COLUMNS - choices.size % CATEGORY_COLUMNS) % CATEGORY_COLUMNS) {
            Spacer(Modifier.weight(1f))
        }
    }
}

/**
 * The chosen category wears the same filled block the selected tab does — the app already says
 * "this one" in `primary`, and the screen whose whole job is choosing was saying it in a
 * container colour a shade off the unselected ones.
 *
 * The mark carries the category's own hue, on a disc of the same hue: a 1.6dp stroke is too
 * little ink to read as colour on its own, and the disc is what gives it area. The label stays
 * `onSurface` throughout — the colour is for finding the cell, never for reading it, so nothing
 * legible depends on a hue.
 *
 * Selecting takes the hue away rather than intensifying it, which looks backwards written down
 * and is right on the screen: «this one» is a thing the app says in exactly one colour, and a
 * grid where the chosen cell is merely a stronger version of its own colour has seventeen cells
 * all claiming it at once.
 *
 * Tapping writes immediately, so the colour landing over [Motion.fast] is the only receipt the
 * choice gets. It is the one authored moment on the screen; a snap reads as a redraw.
 */
@Composable
private fun CategoryTile(
    category: Category,
    selected: Boolean,
    onClick: () -> Unit,
    selectedLabel: String,
    modifier: Modifier = Modifier,
) {
    val hue = categoryHue(category.nameFa)
    val fill by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.surfaceVariant,
        tween(Motion.fast, easing = Motion.enter),
        label = "tileFill",
    )
    val ink by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.onPrimary
        else MaterialTheme.colorScheme.onSurface,
        tween(Motion.fast, easing = Motion.enter),
        label = "tileInk",
    )
    val mark by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.onPrimary else hue,
        tween(Motion.fast, easing = Motion.enter),
        label = "tileMark",
    )
    Column(
        modifier
            .clip(RoundedCornerShape(Radius.field))
            .background(fill)
            .clickable(role = Role.Button, onClick = onClick)
            // A floor, not a height: at a large system font the label takes a third line and the
            // cell has to grow with it rather than clip it.
            .heightIn(min = 92.dp)
            .padding(horizontal = Space.xs, vertical = Space.m)
            .semantics {
                contentDescription = category.nameFa + if (selected) ", $selectedLabel" else ""
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(mark.copy(alpha = 0.18f)),
            contentAlignment = Alignment.Center,
        ) {
            CategoryIcon(category.nameFa, mark, size = 20.dp)
        }
        Spacer(Modifier.height(Space.s))
        Text(
            category.nameFa,
            fontSize = 12.sp,
            lineHeight = 16.sp,
            // One weight for every state. Modam's weight axis changes the advance, so a bold
            // selected label is a wider word — and in a fixed cell that is a word that rewraps
            // under her finger on the tap that chose it.
            fontWeight = FontWeight.SemiBold,
            color = ink,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = Space.s),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            label,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            value,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1.4f),
        )
    }
}

/**
 * The exception deck.
 *
 * One card at a time, and tapping a category files it on the spot — the same gesture, the same
 * switch and the same default as opening the row from دفتر, because «which of the two screens am
 * I on» must never change what a tap does. «فعلاً نه» writes nothing at all, on purpose: skipping
 * must cost her nothing and must not be recorded as an opinion she does not have.
 *
 * A rule is opted into with [LearnSimilarToggle] *before* the tap, never extracted by a sheet
 * after it: the old two-button question landed on every single card, and «فقط همین یکی» was the
 * answer nine times out of ten.
 */
@Composable
fun ReviewDeck(
    ledger: LedgerView,
    onDecide: (LedgerEntry, String, Boolean) -> Unit,
    onWorthIt: (LedgerEntry, String) -> Unit,
    onLoadSource: suspend (LedgerEntry) -> String,
    onDone: () -> Unit,
    /** The whole backlog at once — see [autoFilePlan]. Null hides the offer. */
    onAutoFile: ((List<Pair<LedgerEntry, String>>) -> Unit)? = null,
    /** Her note on the card, so the deck and the transaction page stay one screen. */
    onNote: ((LedgerEntry, String) -> Unit)? = null,
) {
    var index by remember { mutableStateOf(0) }
    var skipped by remember { mutableStateOf(setOf<String>()) }
    var autoFiling by remember { mutableStateOf(false) }
    val pending = ledger.review.filterNot { it.txn.ref in skipped }
    val entry = pending.getOrNull(index.coerceAtMost(pending.lastIndex.coerceAtLeast(0)))

    // Off on every new card, exactly as on the transaction page: a standing rule is opted into
    // per transaction, and a switch that stayed on across cards would write rules she never read.
    var learnSimilar by rememberSaveable(entry?.txn?.ref) { mutableStateOf(false) }

    // The tap's receipt: filing runs off-thread and the card stays up until the ledger comes
    // back, so the tile she chose holds the app's «this one» for that beat. Keyed by card, so
    // the highlight can never survive onto a transaction she has not read.
    var picked by remember(entry?.txn?.ref) { mutableStateOf<String?>(null) }

    // Read off the database per card rather than carried in [LedgerView]: the deck holds one
    // card at a time and the view holds three hundred rows, so keeping every message body in
    // memory would be paying for two hundred and ninety-nine she will not read.
    var source by remember(entry?.txn?.ref) { mutableStateOf<String?>(null) }

    // A new card starts at its own top. The deck answers one question and immediately replaces
    // everything under the field with a different transaction, and leaving the old offset in
    // place opened the next one half way down its own message — the two lines that say what it
    // is scrolled off the screen, above a grid asking her to file it. Instant, not animated:
    // this is a replacement, not a movement, and there is no distance to travel.
    val cardScroll = rememberScrollState()
    LaunchedEffect(entry?.txn?.ref) {
        cardScroll.scrollTo(0)
        entry?.let { source = onLoadSource(it) }
    }

    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            if (entry != null) {
                TransactionHero(
                    entry = entry,
                    incoming = entry.txn.direction == "in",
                    onBack = onDone,
                    backLabel = "بستن",
                    note = "${faNumber((index + 1).toDouble())} از ${faNumber(pending.size.toDouble())}",
                )
            }

            Column(
                Modifier
                    .fillMaxSize()
                    .then(if (entry == null) Modifier.systemBarsPadding() else Modifier)
                    .padding(horizontal = Space.xl),
            ) {
            if (entry == null) {
                // The exceptions are done, so the one optional question gets asked — at most
                // two a week, and only about something large she chose to buy. Asked here
                // rather than mixed into the deck because it is not a correction: nothing is
                // wrong with these, and she is not obliged to have an opinion.
                val asking = remember(ledger) {
                    worthItCandidates(
                        ledger.entries,
                        ledger.worthIt.keys,
                        tehranDay(System.currentTimeMillis()),
                        largeSpendThreshold(ledger.entries),
                    )
                }
                if (asking.isEmpty()) {
                    DeckDone(ledger, onDone)
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "مرور هفتگی",
                            fontSize = 24.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = MaterialTheme.colorScheme.onBackground,
                            modifier = Modifier.semantics { heading() },
                        )
                        Spacer(Modifier.weight(1f))
                        PillButton("بستن", onDone)
                    }
                    Spacer(Modifier.height(Space.l))
                    for (candidate in asking) {
                        WorthItCard(candidate, onAnswer = { onWorthIt(candidate, it) })
                        Spacer(Modifier.height(Space.m))
                    }
                    PillButton("بعداً", onDone)
                }
                return@Column
            }

            // Per card, and held for as long as that card is up: the deck files as she answers,
            // and a grid that reshuffles between two taps of the same card is one she has to read
            // again from the top.
            val choices = remember(entry.txn.ref, ledger.categories) {
                categoryChoices(ledger.categories, entry.txn.direction, ledger.categoryUse)
            }
            // The card scrolls; the one answer that is always available does not. It is the
            // transaction page's own order under a different heading — the question where that
            // page says «دسته‌بندی», then the same grid, the same «جزئیات», the same «منبع» — so
            // that opening a row and being handed one in the deck are the same screen, and what
            // she learns to look for in either place is where she left it in the other.
            Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(cardScroll)) {
                Spacer(Modifier.height(Space.xl))
                Text(
                    // «خرج» on money that arrived is the question asking her to agree with
                    // something untrue before she has answered it.
                    when (entry.txn.direction) {
                        "in" -> "این واریز رو چی حساب کنم؟"
                        "out" -> "این خرج رو چی حساب کنم؟"
                        else -> "این تراکنش رو چی حساب کنم؟"
                    },
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.semantics { heading() },
                )
                Spacer(Modifier.height(Space.m))
                LearnSimilarToggle(entry.txn, learnSimilar) { learnSimilar = it }
                Spacer(Modifier.height(Space.m))
                CategoryGrid(
                    choices = choices,
                    selectedId = picked,
                    onPick = { category ->
                        picked = category.id
                        onDecide(entry, category.id, learnSimilar)
                        index = 0
                    },
                    selectedLabel = "انتخاب‌شده",
                )

                if (onNote != null) {
                    Spacer(Modifier.height(Space.xxl))
                    SectionHeading("یادداشت")
                    Spacer(Modifier.height(Space.m))
                    NoteField(entry, onNote)
                }

                Spacer(Modifier.height(Space.xxl))
                SectionHeading("جزئیات")
                Spacer(Modifier.height(Space.m))
                DetailsPanel(remember(entry.txn.ref) { transactionDetails(entry) })

                Spacer(Modifier.height(Space.xxl))
                SectionHeading("منبع")
                Spacer(Modifier.height(Space.m))
                SourcePreview(entry, source)
                Spacer(Modifier.height(Space.l))
            }

            // What stands at the foot needs no category: «فعلاً نه» writes nothing at all, and
            // skipping must cost her nothing. Beside it, only while the backlog is real, is the
            // way out of the whole chore — behind its own sheet, because fifty-three filings in
            // one tap deserve one look at what will happen first.
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(bottom = Space.m)
                    .navigationBarsPadding(),
                horizontalArrangement = Arrangement.spacedBy(Space.s),
            ) {
                DeckAnswer(
                    // Named by what it does — it sets this card aside unanswered and moves on,
                    // which «نه» alone read as a verdict on the question above the grid.
                    label = "بمونه برای بعد",
                    weight = AnswerWeight.QUIET,
                    onClick = { skipped = skipped + entry.txn.ref },
                    modifier = Modifier.weight(1f),
                )
                if (onAutoFile != null && pending.size >= 5) {
                    DeckAnswer(
                        label = "خودکار برای همه",
                        weight = AnswerWeight.SECONDARY,
                        onClick = { autoFiling = true },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            }
        }
    }

    if (autoFiling) {
        AutoFileSheet(
            plan = remember(pending) { autoFilePlan(pending) },
            onConfirm = { assignments ->
                onAutoFile?.invoke(assignments)
                autoFiling = false
                index = 0
            },
            onDismiss = { autoFiling = false },
        )
    }

    if (makingCategory && onCreateCategory != null) {
        AddCategorySheet(
            taken = ledger.categories.map { it.nameFa },
            initialKind = if (entry?.txn?.direction == "in") CategoryKind.INCOME else CategoryKind.EXPENSE,
            onAdd = onCreateCategory,
            onDismiss = { makingCategory = false },
        )
    }
}

/**
 * The deck with nothing left in it — the state the whole screen exists to reach, so it earns a
 * real moment rather than a line floating on an empty page.
 *
 * Calm on purpose: no confetti and no score, because finishing the homework is meant to be
 * ordinary here. What it does earn is the figure the app is judged on — how much of the month
 * never needed her at all — and the check is drawn in the ledger's own green, the colour a
 * settled figure already speaks in. One number, one sentence, one way back.
 */
@Composable
private fun DeckDone(ledger: LedgerView, onDone: () -> Unit) {
    val month = remember(ledger.entries) {
        currentMonthReport(ledger.entries, tehranDay(System.currentTimeMillis()))
    }
    val met = Color(0xFF2E9E5B)
    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier.weight(1f).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Box(
                Modifier
                    .size(96.dp)
                    .clip(CircleShape)
                    .background(met.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center,
            ) { CheckMark(met, size = 46.dp) }
            Spacer(Modifier.height(Space.xl))
            Text(
                "همه‌چی بررسی شد",
                fontSize = 26.sp,
                fontWeight = FontWeight.ExtraBold,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.semantics { heading() },
            )
            Spacer(Modifier.height(Space.s))
            Text(
                "هر رقمی که توی گزارش‌هاست، حالا تأییدشده‌ست.",
                fontSize = 14.sp,
                lineHeight = 23.sp,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // The receipt for all that tapping, and only when the month has enough rows for a
            // share to mean anything — the same floor the report's own automation line keeps.
            month.automaticShare?.takeIf { month.transactions >= 5 }?.let { share ->
                Spacer(Modifier.height(Space.xxl))
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(Radius.group))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(Space.xl),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        "${faNumber(Math.round(share * 100.0).toDouble())}٪",
                        style = figureStyle(MaterialTheme.colorScheme.onSurface, FontWeight.Black),
                        fontSize = 36.sp,
                    )
                    Spacer(Modifier.height(Space.xs))
                    Text(
                        "از ${faNumber(month.transactions.toDouble())} تراکنش این ماه " +
                            "بدون پرسیدن ازت دسته‌بندی شد",
                        fontSize = 13.sp,
                        lineHeight = 21.sp,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        PillButton(
            "برگشت به دفتر",
            onDone,
            voice = ButtonVoice.PRIMARY,
            modifier = Modifier.fillMaxWidth().padding(bottom = Space.m),
            fontSize = 16.sp,
            minHeight = 56.dp,
        )
    }
}

/** The one check mark in the app, drawn with the same pen every other glyph is. */
@Composable
private fun CheckMark(tint: Color, size: androidx.compose.ui.unit.Dp = 40.dp) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        drawPath(
            Path().apply {
                moveTo(w * 0.22f, h * 0.56f)
                lineTo(w * 0.42f, h * 0.74f)
                lineTo(w * 0.78f, h * 0.30f)
            },
            tint,
            style = pen(3.dp),
        )
    }
}

/**
 * The plan spelled out before anything is written: how many keep the app's own suggestion, how
 * many fall back to «خرید روزانه», how many to «سایر». Nothing here is a rule — every filing is
 * an ordinary answer she can undo one row at a time from دفتر, and the sheet says so, because
 * that sentence is what makes one tap over fifty-three transactions a relief instead of a risk.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AutoFileSheet(
    plan: AutoFilePlan,
    onConfirm: (List<Pair<LedgerEntry, String>>) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    fun close(then: () -> Unit) = scope.launch { sheetState.hide(); then() }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = Radius.sheet, topEnd = Radius.sheet),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            Modifier
                .navigationBarsPadding()
                .padding(horizontal = Space.xl)
                .padding(bottom = Space.l),
        ) {
            Text(
                "دسته‌بندی خودکار",
                fontSize = 24.sp,
                fontWeight = FontWeight.ExtraBold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                "${faNumber(plan.total.toDouble())} تراکنش منتظر، در یک حرکت:",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Space.s),
            )

            Spacer(Modifier.height(Space.l))
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(Radius.field))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = Space.l, vertical = Space.s),
            ) {
                if (plan.suggested > 0) {
                    AutoFileLine(plan.suggested, "با همون پیشنهاد خود برنامه تأیید می‌شن")
                }
                if (plan.shopping > 0) {
                    AutoFileLine(plan.shopping, "برداشتِ بی‌نشونه می‌رن توی «خرید روزانه»")
                }
                if (plan.other > 0) {
                    AutoFileLine(plan.other, "مورد نامشخص می‌رن توی «سایر»")
                }
            }
            Text(
                "هیچ قانونی ساخته نمی‌شه — هر کدوم رو بعداً می‌تونی از دفتر باز کنی و عوض کنی.",
                fontSize = 13.sp,
                lineHeight = 21.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Space.m),
            )

            Spacer(Modifier.height(Space.xl))
            DeckAnswer(
                label = "دسته‌بندی کن",
                weight = AnswerWeight.PRIMARY,
                onClick = { close { onConfirm(plan.assignments) } },
            )
            Spacer(Modifier.height(Space.s))
            DeckAnswer(
                label = "انصراف",
                weight = AnswerWeight.QUIET,
                onClick = { close(onDismiss) },
            )
        }
    }
}

@Composable
private fun AutoFileLine(count: Int, what: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = Space.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            faNumber(count.toDouble()),
            style = figureStyle(MaterialTheme.colorScheme.onSurface, FontWeight.ExtraBold),
            fontSize = 17.sp,
        )
        Spacer(Modifier.width(Space.m))
        Text(
            what,
            fontSize = 14.sp,
            lineHeight = 22.sp,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/**
 * What «از این به بعد» will actually key on, in her own words rather than the rule engine's.
 *
 * It is not the same promise on every card, and the difference is the whole reason this line
 * exists: with a merchant in the message the rule is that merchant and nothing else, and without
 * one — which is most transfers, and every card where the deck can only print a bank name — it
 * keys on the sender, the bank and the channel together. That is a far wider net, and «همیشه» is
 * the answer that must never be a surprise afterwards.
 *
 * Both apply backwards as well as forwards: rules are re-run over every stored message on the
 * next derive, and only a transaction she has answered by hand is immune to them.
 */
private fun learnedRule(txn: Txn): String {
    if (txn.merchant.isNotBlank()) {
        return "«از این به بعد» یعنی هر تراکنش دیگه‌ای از «${txn.merchant}» هم خودکار همین دسته " +
            "می‌شه، و مشابه‌های قبلی هم اصلاح می‌شن."
    }
    val kind = when (txn.direction) {
        "in" -> "واریزهای"
        "out" -> "برداشت‌های"
        else -> "تراکنش‌های"
    }
    return "این پیامک اسم فروشنده نداره، پس «از این به بعد» روی $kind شبیه این از " +
        "${bankNameOf(txn.bank)} اعمال می‌شه، و مشابه‌های قبلی هم اصلاح می‌شن."
}

/** How much of an answer it is: two of these write something, and one deliberately does not. */
private enum class AnswerWeight { PRIMARY, SECONDARY, QUIET }

/** One of the answers, whether it stands at the foot of the deck or inside the sheet. */
@Composable
private fun DeckAnswer(
    label: String,
    weight: AnswerWeight,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.pill))
            .background(
                when (weight) {
                    AnswerWeight.PRIMARY -> MaterialTheme.colorScheme.primary
                    AnswerWeight.SECONDARY -> MaterialTheme.colorScheme.surfaceVariant
                    // Not a surface of its own: it is the answer that changes nothing, and a
                    // filled shape would promise it does.
                    AnswerWeight.QUIET -> Color.Transparent
                },
            )
            .clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = 52.dp)
            .padding(horizontal = Space.l),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            fontSize = 15.sp,
            fontWeight = if (weight == AnswerWeight.QUIET) FontWeight.Medium else FontWeight.Bold,
            color = when (weight) {
                AnswerWeight.PRIMARY -> MaterialTheme.colorScheme.onPrimary
                AnswerWeight.SECONDARY -> MaterialTheme.colorScheme.onSurface
                AnswerWeight.QUIET -> MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}
