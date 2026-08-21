package com.doxigo.muchtoman

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
            Row(
                Modifier.fillMaxWidth().padding(horizontal = Space.xl, vertical = Space.m),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "دفتر",
                    fontSize = 28.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.semantics { heading() },
                )
                Spacer(Modifier.weight(1f))
                if (waiting > 0) ReviewPill(waiting, onReview)
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
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(Radius.field))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .toggleable(
                                value = learnSimilar,
                                role = Role.Switch,
                                onValueChange = { learnSimilar = it },
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
                                "موارد قبلی و بعدی مشابه هم اصلاح می‌شن",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.width(Space.m))
                        Switch(checked = learnSimilar, onCheckedChange = null)
                    }
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
 * One card at a time, three answers, and «همیشه» is the one that matters — it is the difference
 * between an app that asks the same question every month and one that learns. «حالا نه» writes
 * nothing at all, on purpose: skipping must cost her nothing and must not be recorded as an
 * opinion she does not have.
 */
@Composable
fun ReviewDeck(
    ledger: LedgerView,
    onDecide: (LedgerEntry, String, Boolean) -> Unit,
    onWorthIt: (LedgerEntry, String) -> Unit,
    onLoadSource: suspend (LedgerEntry) -> String,
    onDone: () -> Unit,
) {
    var index by remember { mutableStateOf(0) }
    var skipped by remember { mutableStateOf(setOf<String>()) }
    val pending = ledger.review.filterNot { it.txn.ref in skipped }
    val entry = pending.getOrNull(index.coerceAtMost(pending.lastIndex.coerceAtLeast(0)))

    // The picked category, cleared whenever the deck moves on: the answers it opens are about
    // the card on screen, and carrying a highlight onto the next one would offer to file a
    // transaction she has not looked at yet.
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "مرور هفتگی",
                        fontSize = 24.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.semantics { heading() },
                    )
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onDone) { Text("بستن") }
                }
            }

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
                Spacer(Modifier.height(Space.l))
                if (asking.isEmpty()) {
                    Column(
                        Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            "همه‌چی بررسی شد",
                            fontSize = 22.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = MaterialTheme.colorScheme.onBackground,
                        )
                        Spacer(Modifier.height(Space.s))
                        Text(
                            "دیگه چیزی نمونده.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    for (candidate in asking) {
                        WorthItCard(candidate, onAnswer = { onWorthIt(candidate, it) })
                        Spacer(Modifier.height(Space.m))
                    }
                    TextButton(onClick = onDone) { Text("بعداً") }
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
                CategoryGrid(
                    choices = choices,
                    selectedId = picked,
                    onPick = { picked = it.id },
                    selectedLabel = "انتخاب‌شده",
                )

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

            // What is left at the foot is the answer that needs no category: «فعلاً نه» writes
            // nothing at all, and it stays a tap away on the page rather than behind the sheet,
            // because skipping must cost her nothing — including the cost of first choosing
            // something she is not sure of just to reach the way out.
            Column(Modifier.fillMaxWidth().padding(bottom = Space.m).navigationBarsPadding()) {
                DeckAnswer(
                    label = "فعلاً نه",
                    weight = AnswerWeight.QUIET,
                    onClick = { skipped = skipped + entry.txn.ref },
                )
            }

            // The two answers that commit, over the card they are about.
            choices.firstOrNull { it.id == picked }?.let { category ->
                DecisionSheet(
                    entry = entry,
                    category = category,
                    onDecide = { always ->
                        onDecide(entry, category.id, always)
                        index = 0
                        picked = null
                    },
                    onDismiss = { picked = null },
                )
            }
            }
        }
    }
}

/**
 * The two answers that commit, in the drawer the app opens for every other decision.
 *
 * They used to expand into the page underneath the grid, which is the one place they could not be
 * read: the grid scrolls, so a tap in its lower half pushed the answer to the question off the
 * bottom of the screen, and a block that grows out of the middle of a scrolling column is a popup
 * wearing a page's clothes. A sheet arrives with the scrim, the drag handle, the back gesture and
 * the navigation-bar inset already correct, and it is the shape she has already met in
 * «حساب‌های بانکی» and everywhere else the app needs an answer before anything else happens.
 *
 * The card behind it keeps her pick lit under the scrim, so the sheet never has to read the choice
 * back to her — it names it once, at the top, and spends the rest of its room on the only thing
 * the two buttons differ in. Dismissing un-picks: a highlighted tile with nothing left to press is
 * a dead end she would have to guess her way off.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DecisionSheet(
    entry: LedgerEntry,
    category: Category,
    onDecide: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    // Play the sheet out before acting on it, or the answer just blinks.
    fun close(then: () -> Unit) = scope.launch { sheetState.hide(); then() }
    val hue = categoryHue(category.nameFa)

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
            Row(verticalAlignment = Alignment.CenterVertically) {
                // The same mark on the same disc she just tapped, at the size the tile drew it.
                // The tile is two hundred pixels below and behind a scrim; this is what says the
                // sheet is about that tap and not about the card in general.
                Box(
                    Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(hue.copy(alpha = 0.18f)),
                    contentAlignment = Alignment.Center,
                ) {
                    CategoryIcon(category.nameFa, hue, size = 24.dp)
                }
                Spacer(Modifier.width(Space.m))
                Text(
                    category.nameFa,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.semantics { heading() },
                )
            }
            Text(
                learnedRule(entry.txn),
                fontSize = 13.sp,
                lineHeight = 21.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Space.m),
            )

            Spacer(Modifier.height(Space.xl))
            DeckAnswer(
                label = "از این به بعد هم همین دسته",
                weight = AnswerWeight.PRIMARY,
                onClick = { close { onDecide(true) } },
            )
            Spacer(Modifier.height(Space.s))
            DeckAnswer(
                label = "فقط همین یکی",
                weight = AnswerWeight.SECONDARY,
                onClick = { close { onDecide(false) } },
            )
            // Space.s throughout, never tighter: 8dp between touch targets is the floor, and
            // «انصراف» sitting closer to «فقط همین یکی» than that is a mis-tap that files money.
            Spacer(Modifier.height(Space.s))
            // The scrim and the swipe already close it; this is for the finger that is nowhere
            // near either, and for TalkBack, where "dismiss the sheet" is a gesture and this is
            // a button.
            DeckAnswer(
                label = "انصراف",
                weight = AnswerWeight.QUIET,
                onClick = { close(onDismiss) },
            )
        }
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
private fun DeckAnswer(label: String, weight: AnswerWeight, onClick: () -> Unit) {
    Box(
        Modifier
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
