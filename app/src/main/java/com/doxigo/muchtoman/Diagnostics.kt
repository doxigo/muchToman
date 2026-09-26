package com.doxigo.muchtoman

import android.app.Application
import android.content.Context
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/*
 * Everything the app ever tells its author about itself: one count a day, and a crash report she
 * chose to send. README § Privacy lists both, and muchtoman.com/usage shows everyone the same
 * numbers the author sees.
 */

// ───────────────────────── the daily count ─────────────────────────

/**
 * The UTC day number. The Worker files each count under the UTC day, so "once a day" here is the
 * same day the dashboard draws. A Tehran day would put one phone in two bars around 03:30.
 */
fun utcDay(now: Long = System.currentTimeMillis()): Long = now / 86_400_000L

/**
 * The one header the day's first rates request carries: the version, and the store that installed
 * the app. No id of any kind. Two phones on the same version from the same store send the same
 * bytes, which is the point.
 */
fun dailyPing(context: Context): String = "${BuildConfig.VERSION_NAME} ${installerOf(context)}"

/** `com.farsitel.bazaar`, `ir.mservice.market`, a package installer for a GitHub APK, or `none`. */
private fun installerOf(context: Context): String = runCatching {
    val pm = context.packageManager
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        pm.getInstallSourceInfo(context.packageName).installingPackageName
    } else {
        @Suppress("DEPRECATION")
        pm.getInstallerPackageName(context.packageName)
    }
}.getOrNull() ?: "none"

/**
 * A store that installed the app also updates it, and only once its own review has passed. The
 * update card links the GitHub build, which is out hours or days before that — so for these
 * installs the card would either race the store or quietly move her off it.
 */
fun installedByStore(context: Context): Boolean =
    installerOf(context) in setOf("com.farsitel.bazaar", "ir.mservice.market", "com.android.vending")

// ───────────────────────── crash reports ─────────────────────────

/**
 * Keeps the last uncaught crash on disk for the next launch to offer, then hands the crash to the
 * platform, which kills the process exactly as it would have. Nothing leaves the phone here:
 * [CrashSheet] asks first, every time.
 *
 * ponytail: uncaught JVM exceptions only. ANRs and native crashes are not seen; Android 11+'s
 * getHistoricalProcessExitReasons is the upgrade if they turn up. A crash before the first frame
 * never reaches the sheet either, which is what release.yml's emulator smoke test is for.
 */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        val platform = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            runCatching {
                val header = "${BuildConfig.VERSION_NAME} · Android ${Build.VERSION.SDK_INT} · " +
                    "${Build.MANUFACTURER} ${Build.MODEL}"
                crashFile(this).writeText(crashReport(e, header))
            }
            platform?.uncaughtException(thread, e)
        }
    }
}

private const val MAX_FRAMES = 30
private const val MAX_CAUSES = 8
private const val MAX_CRASH_CHARS = 4_000

/**
 * Class names and stack frames, and nothing else. Exception messages are left out on purpose:
 * that is where data leaks. A NumberFormatException quotes the amount it choked on, a parse error
 * quotes the SMS. A frame is only a class, a method and a line in this APK. R8 has renamed it, so
 * `retrace` with that release's mapping.txt turns it back (DEVELOPMENT.md § Releasing).
 *
 * Capped per exception as well as overall: a stack overflow is a thousand copies of one frame, and
 * the cause at the bottom is usually the useful part. The Worker refuses anything over 8 KB.
 */
fun crashReport(e: Throwable, header: String): String = buildString {
    appendLine(header)
    generateSequence(e) { t -> t.cause?.takeIf { it !== t } }.take(MAX_CAUSES).forEachIndexed { i, t ->
        appendLine(if (i == 0) t.javaClass.name else "Caused by: ${t.javaClass.name}")
        t.stackTrace.take(MAX_FRAMES).forEach { appendLine("\tat $it") }
    }
}.take(MAX_CRASH_CHARS)

fun crashFile(context: Context): File = File(context.filesDir, "crash.txt")

/** The report the last crash left behind, or null. */
fun pendingCrash(context: Context): String? =
    runCatching { crashFile(context).takeIf { it.exists() }?.readText() }.getOrNull()?.ifBlank { null }

/** Sent to the same Worker as the prices, derived from its origin the way the wallet lookup is. */
suspend fun postCrashReport(ratesUrl: String, report: String): Result<Unit> = withContext(Dispatchers.IO) {
    runCatching {
        val rates = URL(ratesUrl)
        val endpoint = URL(rates.protocol, rates.host, rates.port, "/crash")
        val conn = (endpoint.openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 10_000
            requestMethod = "POST"
            instanceFollowRedirects = false
            doOutput = true
            setRequestProperty("Content-Type", "text/plain; charset=utf-8")
            setRequestProperty("Connection", "close")
        }
        try {
            conn.outputStream.use { it.write(report.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code !in 200..299) error("HTTP $code")
        } finally {
            conn.disconnect()
        }
    }
}

/**
 * The ask, once per crash. The report sits in the sheet exactly as it will be sent, so "only this
 * text" is something she can check rather than take on trust. A swipe down counts as «نه»: like
 * the first-run screen, a refusal does not come back.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CrashSheet(report: String, onSend: () -> Unit, onDismiss: () -> Unit) {
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
            SheetTitle("دفعهٔ قبل برنامه یهو بسته شد")
            Spacer(Modifier.height(Space.m))
            Text(
                "اگه این گزارش رو بفرستی، می‌فهمیم کجاش خراب شده و درستش می‌کنیم. فقط همین متن " +
                    "پایین فرستاده می‌شه: نسخهٔ برنامه، مدل گوشی و خطی از کد که خراب شد. " +
                    "نه اسمی، نه مبلغی، نه پیامکی.",
                fontSize = 15.sp,
                lineHeight = 26.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(Space.l))
            Text(
                report,
                style = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                    textAlign = TextAlign.Left,
                    textDirection = TextDirection.Ltr,
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 180.dp)
                    .clip(RoundedCornerShape(Radius.field))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .verticalScroll(rememberScrollState())
                    .padding(Space.m),
            )
            Spacer(Modifier.height(Space.xl))
            PillButton(
                "بفرست",
                { close(onSend) },
                voice = ButtonVoice.PRIMARY,
                modifier = Modifier.fillMaxWidth(),
                minHeight = 52.dp,
            )
            Spacer(Modifier.height(Space.s))
            PillButton("نه، نفرست", { close(onDismiss) }, modifier = Modifier.fillMaxWidth())
        }
    }
}
