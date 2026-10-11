package com.doxigo.muchtoman

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.abs

/**
 * دفتر as a spreadsheet — the one file this app writes that is *not* sealed.
 *
 * It is a different act from the backup and sits apart from it: the backup is everything, locked
 * with her passphrase, for this app to read back; this is her ledger in the clear, for Excel to
 * read and for her to sum, sort and hand to an accountant. So it says so before it is made.
 *
 * Transactions alone say where money went and nothing about what is left, so the file opens with
 * what each member has as it is made — the hero's own lines, typed «موجودی» — and every row carries
 * the مانده its bank stated after it. The rows are دفتر's rows ([ledgerRows], duplicates out, a split
 * payment as its parts, transfers in and marked), never a second derivation of the ledger. The file
 * is what Excel needs to open Persian without asking: UTF-8 with a BOM, CRLF lines, RFC 4180
 * quoting, and Latin digits in the figures so a number lands as a number. Mirrored in
 * `pwa/src/csv.ts`.
 */
private val CSV_HEADER = listOf(
    "تاریخ", "ساعت", "مبلغ (ریال)", "مبلغ (تومان)", "موجودی (ریال)", "موجودی (تومان)",
    "بانک", "طرف حساب", "دسته", "یادداشت", "نوع", "صاحب تراکنش",
)

/**
 * The whole file, BOM first: a line for each thing each of [balances] holds, stamped [now], then
 * one line per row دفتر lists, in the order it lists them.
 */
internal fun ledgerCsv(
    entries: List<LedgerEntry>,
    balances: List<FamilyAssetView> = emptyList(),
    now: Long = System.currentTimeMillis(),
): String = buildString {
    append('\uFEFF')
    csvLine(CSV_HEADER.map(::csvText))
    // Nothing moved, so no مبلغ: a figure there would be summed with the spending under it.
    for (member in balances) for (item in member.items) {
        if (!item.toman.isFinite()) continue
        val rial = csvRial(item.toman)
        csvLine(
            listOf(
                csvDate(tehranDay(now)), csvClock(now), "", "", rial.toString(), csvToman(rial),
                csvText(item.name), "", "", "", "موجودی", csvText(member.name),
            ),
        )
    }
    var previous = ""
    for (e in ledgerRows(entries.filterNot { it.duplicate }, LedgerLens.ALL, emptyList(), "")) {
        val txn = e.txn
        // As دفتر draws it: a message that did not say which way prints with the minus there,
        // so it does here — and its نوع stays blank rather than claim a side nobody read.
        val rial = txn.signedRial ?: txn.amountRial?.let { -it }
        // The parts of a split payment sit together under the one message's ref, and the مانده
        // that message stated goes on the first of them, not once per part.
        val balance = txn.balanceRial.takeIf { txn.ref != previous }
        previous = txn.ref
        csvLine(
            listOf(
                csvDate(txn.day),
                // A row entered from a bare date sits on its midnight; «00:00» would be a minute
                // nobody recorded — faMoment's rule.
                if (txn.at == tehranDayStart(txn.day)) "" else csvClock(txn.at),
                rial?.toString().orEmpty(),
                rial?.let(::csvToman).orEmpty(),
                balance?.toString().orEmpty(),
                balance?.let(::csvToman).orEmpty(),
                csvText(if (txn.sourceKind == "manual") "ورود دستی" else bankNameOf(txn.bank)),
                csvText(txn.merchant),
                csvText(ledgerCategoryFa(e)),
                csvText(e.note),
                when {
                    e.transfer -> "انتقال"
                    rial == null -> "مانده"
                    txn.signedRial == null -> ""
                    rial < 0 -> "خرج"
                    else -> "درآمد"
                },
                csvText(e.ownerName),
            ),
        )
    }
}

/**
 * What each member has right now, for the file's «موجودی» lines: hers as her hero total counts it —
 * [assetShareItems] with nothing held back from the family, the file being hers — then what the
 * others chose to share, as the asset tab lists it. Her own record is never read twice.
 */
internal fun csvBalances(state: UiState): List<FamilyAssetView> {
    val mine = assetShareItems(
        holdings = state.holdings,
        rates = state.effective,
        coins = state.coins,
        stocks = state.stocks,
        smsEnabled = state.smsEnabled,
        bankAccounts = state.bankAccounts,
        disabledBanks = state.disabledBanks,
        familyExcluded = emptySet(),
        notified = state.notified,
    )
    return listOf(FamilyAssetView(state.family.memberId, state.family.memberName, mine, mine.sumOf { it.toman })) +
        state.familyAssets.filter { it.memberId != state.family.memberId }
}

/** `muchtoman-1405-07-11.csv` — today in Tehran, Jalali, Latin digits. */
internal fun ledgerCsvName(now: Long = System.currentTimeMillis()): String =
    jalaliOf(tehranDay(now)).let { "muchtoman-${it.year}-${two(it.month)}-${two(it.day)}.csv" }

/** Rial ÷ 10, exactly: the decimal only when there is one, and never a digit rounded away. */
internal fun csvToman(rial: Long): String {
    val m = abs(rial)
    return (if (rial < 0) "-" else "") + (m / 10) + (if (m % 10 == 0L) "" else ".${m % 10}")
}

/**
 * A Toman value as whole Rial, cut and never rounded up, as every figure the app shows is. A
 * holding is its amount times a rate and seldom whole; settled in decimal first, as [faCompact]
 * does, so a binary hair under a Rial she really has does not eat it.
 */
internal fun csvRial(toman: Double): Long =
    BigDecimal(toman * 10).setScale(9, RoundingMode.HALF_UP).setScale(0, RoundingMode.DOWN).toLong()

/**
 * A text cell. Merchant names and notes are somebody else's words — a bank's gateway, another
 * member's note — and a spreadsheet runs a cell that starts with = + - @ as a formula, so those
 * (and a leading tab or CR) get a leading ' and stay text. Never applied to the figures, where a
 * leading minus is the sign.
 */
internal fun csvText(s: String): String =
    csvField(if (s.isNotEmpty() && s[0] in "=+-@\t\r") "'$s" else s)

/** RFC 4180: quoted when it holds a comma, a quote, CR or LF, inner quotes doubled. */
internal fun csvField(s: String): String =
    if (s.any { it == ',' || it == '"' || it == '\r' || it == '\n' }) "\"" + s.replace("\"", "\"\"") + "\"" else s

private fun StringBuilder.csvLine(cells: List<String>) {
    cells.joinTo(this, ",")
    append("\r\n")
}

private fun csvDate(day: Long): String = jalaliOf(day).let { "${it.year}/${two(it.month)}/${two(it.day)}" }

private fun csvClock(at: Long): String {
    val sinceMidnight = at - tehranDayStart(tehranDay(at))
    return two((sinceMidnight / 3_600_000L).toInt()) + ":" + two((sinceMidnight / 60_000L % 60).toInt())
}

private fun two(n: Int): String = n.toString().padStart(2, '0')

/**
 * The backup page's second act: «خروجی اکسل» as a door in its own band, the warning in words under
 * it where the backup band keeps its own caption. The full edition's only
 * — lite has no ledger to export. Written through the same Storage Access Framework picker the
 * backup uses, so the file goes where she chooses and nowhere else.
 */
@Composable
internal fun LedgerCsvExport(activity: FragmentActivity) {
    val vm = remember(activity) { ViewModelProvider(activity)[AppVm::class.java] }
    val scope = rememberCoroutineScope()
    var notice by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }
    val createFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val state = vm.state.value
        scope.launch {
            val written = withContext(Dispatchers.IO) {
                runCatching {
                    val bytes = ledgerCsv(state.ledger.entries, csvBalances(state)).toByteArray(Charsets.UTF_8)
                    // "wt" truncates a file she chose to overwrite, as the backup's write does.
                    val stream = runCatching { activity.contentResolver.openOutputStream(uri, "wt") }
                        .getOrNull() ?: activity.contentResolver.openOutputStream(uri)
                    (stream ?: error("no stream for $uri")).use { it.write(bytes) }
                }.onFailure { android.util.Log.w("muchtoman", "csv export failed: $it") }.isSuccess
            }
            failed = !written
            notice = if (written) "فایل اکسل ساخته شد." else "فایل اکسل ساخته نشد. دوباره امتحان کن."
        }
    }
    // A band of its own, a section down from the backup's: one more of the page's acts, worn the
    // way the other two are, but not one of them — this file is not sealed, and the caption says so.
    Spacer(Modifier.height(Space.xxl))
    DoorRow(
        title = "خروجی اکسل",
        subtitle = "تراکنش‌های دفتر و موجودی‌ها، توی یک فایل CSV",
        glyph = CategoryGlyph.SHEET,
        shape = bandShape(0, 1),
        divided = false,
        enabled = true,
        onClick = { createFile.launch(ledgerCsvName()) },
    )
    Text(
        "فایل اکسل رمز نداره؛ هر کی بهش برسه همهٔ تراکنش‌ها و موجودی‌هات رو می‌تونه بخونه.",
        fontSize = 13.sp,
        lineHeight = 20.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = Space.m, start = Space.xs, end = Space.xs),
    )
    notice?.let { words ->
        Text(
            words,
            fontSize = 13.sp,
            lineHeight = 20.sp,
            color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .padding(top = Space.m, start = Space.xs, end = Space.xs)
                .semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
}
