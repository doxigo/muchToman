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
import kotlin.math.abs

/**
 * دفتر as a spreadsheet — the one file this app writes that is *not* sealed.
 *
 * It is a different act from the backup and sits apart from it: the backup is everything, locked
 * with her passphrase, for this app to read back; this is her ledger in the clear, for Excel to
 * read and for her to sum, sort and hand to an accountant. So it says so before it is made.
 *
 * The rows are دفتر's rows ([ledgerRows], duplicates out, a split payment as its parts, transfers
 * in and marked), never a second derivation of the ledger. The file is what Excel needs to open
 * Persian without asking: UTF-8 with a BOM, CRLF lines, RFC 4180 quoting, and Latin digits in the
 * figures so a number lands as a number. Mirrored in `pwa/src/csv.ts`.
 */
private val CSV_HEADER = listOf(
    "تاریخ", "ساعت", "مبلغ (ریال)", "مبلغ (تومان)", "بانک", "طرف حساب", "دسته", "یادداشت", "نوع", "صاحب تراکنش",
)

/** The whole file, BOM first, one line per row دفتر lists, in the order it lists them. */
internal fun ledgerCsv(entries: List<LedgerEntry>): String = buildString {
    append('\uFEFF')
    csvLine(CSV_HEADER.map(::csvText))
    for (e in ledgerRows(entries.filterNot { it.duplicate }, LedgerLens.ALL, emptyList(), "")) {
        val txn = e.txn
        // As دفتر draws it: a message that did not say which way prints with the minus there,
        // so it does here — and its نوع stays blank rather than claim a side nobody read.
        val rial = txn.signedRial ?: txn.amountRial?.let { -it }
        val date = jalaliOf(txn.day).let { "${it.year}/${two(it.month)}/${two(it.day)}" }
        csvLine(
            listOf(
                date,
                // A row entered from a bare date sits on its midnight; «00:00» would be a minute
                // nobody recorded — faMoment's rule.
                if (txn.at == tehranDayStart(txn.day)) "" else csvClock(txn.at),
                rial?.toString().orEmpty(),
                rial?.let(::csvToman).orEmpty(),
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

/** `muchtoman-1405-07-11.csv` — today in Tehran, Jalali, Latin digits. */
internal fun ledgerCsvName(now: Long = System.currentTimeMillis()): String =
    jalaliOf(tehranDay(now)).let { "muchtoman-${it.year}-${two(it.month)}-${two(it.day)}.csv" }

/** Rial ÷ 10, exactly: the decimal only when there is one, and never a digit rounded away. */
internal fun csvToman(rial: Long): String {
    val m = abs(rial)
    return (if (rial < 0) "-" else "") + (m / 10) + (if (m % 10 == 0L) "" else ".${m % 10}")
}

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

private fun csvClock(at: Long): String {
    val sinceMidnight = at - tehranDayStart(tehranDay(at))
    return two((sinceMidnight / 3_600_000L).toInt()) + ":" + two((sinceMidnight / 60_000L % 60).toInt())
}

private fun two(n: Int): String = n.toString().padStart(2, '0')

/**
 * The backup page's second act: the warning in words, then «خروجی اکسل». The full edition's only
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
        val entries = vm.state.value.ledger.entries
        scope.launch {
            val written = withContext(Dispatchers.IO) {
                runCatching {
                    val bytes = ledgerCsv(entries).toByteArray(Charsets.UTF_8)
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
    Spacer(Modifier.height(Space.xxl))
    Text(
        "فایل اکسل رمز نداره؛ هر کی بهش برسه همهٔ تراکنش‌هات رو می‌تونه بخونه.",
        fontSize = 13.sp,
        lineHeight = 20.sp,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(bottom = Space.m, start = Space.xs, end = Space.xs),
    )
    PillButton("خروجی اکسل", onClick = { createFile.launch(ledgerCsvName()) })
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
