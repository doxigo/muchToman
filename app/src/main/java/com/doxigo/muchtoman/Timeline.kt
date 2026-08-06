package com.doxigo.muchtoman

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

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
    else -> jalaliOf(day).let { "${faNumber(it.day.toDouble())} ${MONTHS[it.month - 1]} ${faYear(it.year)}" }
}

private fun faYear(year: Int): String = buildString {
    for (c in year.toString()) append('۰' + (c - '0'))
}

@Composable
fun TimelineScreen(
    ledger: LedgerView,
    bottomInset: androidx.compose.ui.unit.Dp,
    onReview: () -> Unit,
    onOpen: (LedgerEntry) -> Unit,
) {
    val today = remember { tehranDay(System.currentTimeMillis()) }
    val visible = ledger.entries.filterNot { it.duplicate }
    val grouped = remember(visible) { visible.groupBy { it.txn.day }.toSortedMap(compareByDescending { it }) }
    val waiting = ledger.review.size

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

            if (visible.isEmpty()) {
                EmptyLedger()
                return@Column
            }

            LazyColumn(
                Modifier.fillMaxSize(),
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
    val net = rows.filterNot { it.transfer }.sumOf { it.txn.signedRial ?: 0L }
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
        if (net != 0L) {
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
                txn.merchant.ifBlank {
                    if (txn.sourceKind == "manual") "مورد دستی" else bankNameOf(txn.bank)
                },
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                val categoryFa = if (entry.transfer) "انتقال بین حساب‌ها" else entry.categoryFa
                // Same mark as the grid she chose it in. A hundred rows of grey 12sp are read by
                // the shape at the start of the line long before the word is.
                CategoryIcon(
                    categoryFa,
                    MaterialTheme.colorScheme.onSurfaceVariant,
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

@Composable
fun TransactionScreen(
    entry: LedgerEntry,
    categories: List<Category>,
    onCategorise: (LedgerEntry, String, Boolean) -> Unit,
    onLoadSource: suspend (LedgerEntry) -> String,
    onBack: () -> Unit,
) {
    val txn = entry.txn
    val incoming = txn.direction == "in"
    var learnSimilar by rememberSaveable(txn.ref) { mutableStateOf(false) }
    var chosen by remember(txn.ref) { mutableStateOf(entry.categoryId) }
    var source by remember(txn.ref) { mutableStateOf<String?>(null) }

    LaunchedEffect(txn.ref) { source = onLoadSource(entry) }
    LaunchedEffect(entry.categoryId) { chosen = entry.categoryId }

    val details = buildList {
        add("نوع" to when (txn.direction) {
            "in" -> "واریز"
            "out" -> "برداشت"
            else -> "نامشخص"
        })
        entry.ownerName.takeIf { it.isNotBlank() }?.let { add("صاحب تراکنش" to it) }
        entry.categoryEditorName.takeIf { it.isNotBlank() }?.let { add("دسته‌بندی توسط" to it) }
        // No «تاریخ» row: the field above states the day, and «زمان ثبت» carries the exact one.
        add("ثبت شده از" to if (txn.sourceKind == "manual") "ورود دستی" else bankNameOf(txn.bank))
        txn.printedAt.takeIf { it.isNotBlank() }?.let { add("زمان ثبت" to bidi(it)) }
        txn.mask.takeIf { it.isNotBlank() }?.let { add("کارت یا حساب" to bidi(it)) }
        txn.refNo.takeIf { it.isNotBlank() }?.let { add("شماره پیگیری" to bidi(it)) }
        txn.balanceRial?.let { add("مانده بعد از تراکنش" to bidi("${faCompact(tomanOf(it))} تومان")) }
        txn.feeRial?.takeIf { it > 0 }?.let { add("کارمزد" to bidi("${faCompact(tomanOf(it))} تومان")) }
    }
    val choices = categories.filter {
        it.kind != CategoryKind.TRANSFER && it.id != CAT_UNCATEGORISED
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
                // Pills that wrap, not a stack of full-width rows: ten categories are three
                // lines instead of ten screens' worth of scrolling, and the one already on
                // this transaction is visible without hunting for it.
                FlowRow(
                    Modifier.fillMaxWidth().then(gutter),
                    horizontalArrangement = Arrangement.spacedBy(Space.s),
                    verticalArrangement = Arrangement.spacedBy(Space.s),
                ) {
                    choices.forEach { category ->
                        CategoryChip(
                            category = category,
                            selected = category.id == chosen,
                            onClick = {
                                chosen = category.id
                                onCategorise(entry, category.id, learnSimilar)
                            },
                        )
                    }
                }
            }

            item(key = "details") {
                Column(gutter) {
                    Spacer(Modifier.height(Space.xxl))
                    Text(
                        "جزئیات",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.semantics { heading() },
                    )
                    Spacer(Modifier.height(Space.m))
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
            }

            item(key = "source") {
                Column(gutter) {
                    Spacer(Modifier.height(Space.xxl))
                    Text(
                        "منبع",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.semantics { heading() },
                    )
                    Spacer(Modifier.height(Space.m))
                    SelectionContainer {
                        Text(
                            when {
                                txn.sourceKind == "manual" -> "این مورد دستی ثبت شده."
                                txn.ownerMemberId.isNotBlank() ->
                                    "متن خام پیامک فقط روی گوشی ${entry.ownerName.ifBlank { "صاحب تراکنش" }} نگه داشته می‌شه."
                                source == null -> "در حال خواندن پیامک اصلی..."
                                source!!.isBlank() -> "متن پیامک اصلی پیدا نشد."
                                else -> source!!
                            },
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
                TextButton(onClick = onBack) {
                    Text(backLabel, color = Hero.strong, fontWeight = FontWeight.SemiBold)
                }
            }

            Spacer(Modifier.height(Space.s))
            Text(
                txn.merchant.ifBlank {
                    if (txn.sourceKind == "manual") "مورد دستی" else bankNameOf(txn.bank)
                },
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

/**
 * The chosen category wears the same filled pill the selected tab does — the app already says
 * "this one" in `primary`, and the screen whose whole job is choosing was saying it in a
 * container colour a shade off the unselected ones.
 *
 * Tapping writes immediately, so the colour landing over [Motion.fast] is the only receipt the
 * choice gets. It is the one authored moment on the screen; a snap reads as a redraw.
 */
@Composable
private fun CategoryChip(
    category: Category,
    selected: Boolean,
    onClick: () -> Unit,
    // What «selected» means differs by screen: on a transaction it is what the entry is filed
    // as; in the deck it is what she has just picked and not yet confirmed.
    selectedLabel: String = "دسته فعلی",
) {
    val fill by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.surfaceVariant,
        tween(Motion.fast, easing = Motion.enter),
        label = "chipFill",
    )
    val ink by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.onPrimary
        else MaterialTheme.colorScheme.onSurface,
        tween(Motion.fast, easing = Motion.enter),
        label = "chipInk",
    )
    Box(
        Modifier
            .clip(RoundedCornerShape(Radius.pill))
            .background(fill)
            .clickable(role = Role.Button, onClick = onClick)
            // The pill is sized by its label, so the tap target needs a floor of its own.
            .heightIn(min = 48.dp)
            .padding(horizontal = Space.m)
            .semantics {
                contentDescription = category.nameFa + if (selected) ", $selectedLabel" else ""
            },
        contentAlignment = Alignment.Center,
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(Space.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The mark, in the same ink as the label: a second colour here would make the grid
            // eleven competing objects rather than one grid she reads at a glance.
            CategoryIcon(category.nameFa, ink)
            Text(
                category.nameFa,
                fontSize = 14.sp,
                // One weight for every state. Modam's weight axis changes the advance, so a bold
                // selected label is a wider pill — and the whole grid reflowed under her finger on
                // the tap that chose it. The fill says «this one» louder than a weight step could.
                fontWeight = FontWeight.SemiBold,
                color = ink,
            )
        }
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
    onDone: () -> Unit,
) {
    var index by remember { mutableStateOf(0) }
    var skipped by remember { mutableStateOf(setOf<String>()) }
    val pending = ledger.review.filterNot { it.txn.ref in skipped }
    val entry = pending.getOrNull(index.coerceAtMost(pending.lastIndex.coerceAtLeast(0)))

    // The picked category, cleared whenever the deck moves on: the two answers below are about
    // the card on screen, and carrying a highlight onto the next one would offer to file a
    // transaction she has not looked at yet.
    var picked by remember(entry?.txn?.ref) { mutableStateOf<String?>(null) }

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

            // «دسته‌بندی نشده» is the absence of an answer, not one of them, and a transfer is
            // something the detector settles rather than something she files by hand.
            val choices = ledger.categories.filter {
                it.kind != CategoryKind.TRANSFER && it.id != CAT_UNCATEGORISED
            }
            // The question and the choices scroll; the answers do not. Only the grid can grow
            // past a short screen, and an answer she has to go looking for is one she will not
            // give.
            Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())) {
                Spacer(Modifier.height(Space.xxl))
                Text(
                    "این خرج رو چی حساب کنم؟",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.semantics { heading() },
                )
                Spacer(Modifier.height(Space.m))
                FlowRow(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Space.s),
                    verticalArrangement = Arrangement.spacedBy(Space.s),
                ) {
                    choices.forEach { category ->
                        CategoryChip(
                            category = category,
                            selected = category.id == picked,
                            onClick = { picked = category.id },
                            selectedLabel = "انتخاب‌شده",
                        )
                    }
                }
                Spacer(Modifier.height(Space.l))
            }

            // Three answers, in one block at the foot of the screen, weighted the way they
            // actually differ. «همیشه» means she is never asked this again, which is the
            // entire point of the product, so it is the filled one. «فعلاً نه» writes nothing
            // at all — but it was a bare text button stranded below a screen of empty space,
            // which made the cheapest answer the hardest one to find. It belongs with the
            // other two: skipping must cost her nothing, including the cost of hunting for it.
            //
            // The two that commit need a category to commit, so they arrive with one.
            Column(Modifier.fillMaxWidth().padding(bottom = Space.m).navigationBarsPadding()) {
                AnimatedVisibility(
                    visible = picked != null,
                    enter = expandVertically(tween(Motion.medium, easing = Motion.enter)) +
                        fadeIn(tween(Motion.medium)),
                    exit = shrinkVertically(tween(Motion.fast, easing = Motion.exit)) +
                        fadeOut(tween(Motion.fast)),
                ) {
                    Column {
                        DeckAnswer(
                            label = "از این به بعد هم همین دسته",
                            weight = AnswerWeight.PRIMARY,
                            onClick = {
                                picked?.let { onDecide(entry, it, true) }
                                index = 0
                            },
                        )
                        Spacer(Modifier.height(Space.s))
                        DeckAnswer(
                            label = "فقط همین یکی",
                            weight = AnswerWeight.SECONDARY,
                            onClick = {
                                picked?.let { onDecide(entry, it, false) }
                                index = 0
                            },
                        )
                        Spacer(Modifier.height(Space.s))
                    }
                }
                DeckAnswer(
                    label = "فعلاً نه",
                    weight = AnswerWeight.QUIET,
                    onClick = { skipped = skipped + entry.txn.ref },
                )
            }
            }
        }
    }
}

/** How much of an answer it is: two of these write something, and one deliberately does not. */
private enum class AnswerWeight { PRIMARY, SECONDARY, QUIET }

/** One of the three answers at the foot of the deck. */
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
