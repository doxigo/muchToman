package com.doxigo.muchtoman

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The one screen that asks for everything, shown once, before anything else.
 *
 * ## This is a deliberate reversal, and it is worth saying why
 *
 * Every permission in this app used to be asked for at the moment it became useful and never
 * before: `READ_SMS` when she switched the messages on, `POST_NOTIFICATIONS` when she saved her
 * first بودجه. That rule was right for a phone she already trusted with a ledger, and wrong for a
 * phone that has just installed the thing. What it produced was an app that opened onto an empty
 * ledger, said nothing, and gave no indication that it was waiting for three separate switches in
 * two different rooms — the most common way a new install ends up looking broken.
 *
 * So the rule is now: ask once, at the start, having said what each one is for — and never again.
 * A refusal is final as far as this screen is concerned. Everything here stays reachable from
 * تنظیمات afterwards, which is where a mind changed a week later goes; a sheet that reappeared
 * until it got the answer it wanted would be a nag with a permission dialog attached.
 *
 * ## Two steps, because they are two different kinds of asking
 *
 * The first is Android's own runtime dialogs, which are one gesture each and are what the app
 * genuinely needs. The second is the battery exemption, which is a different room, a scarier
 * sentence, and buys freshness rather than function — so it is asked for separately, after the
 * first has been answered, and only on a phone that is actually restricted. On a phone that is
 * already unrestricted this screen has one step and she never learns the second existed.
 */
@Composable
fun OnboardingScreen(onSmsGranted: (Boolean) -> Unit, onDone: () -> Unit) {
    val context = LocalContext.current

    // Which of the two asks is on screen. Not rememberSaveable: this screen is one gesture long,
    // and a rotation part-way through is better spent back at the start than restored into a step
    // whose dialog is no longer open.
    var step by remember { mutableStateOf(0) }

    val ask = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        // READ_SMS is the only one that decides anything: it is what every balance is read from.
        // RECEIVE_SMS without it reads nothing, and it with no RECEIVE_SMS is the six-hour sweep,
        // which is the arrangement this app shipped with for a year.
        onSmsGranted(grants[Manifest.permission.READ_SMS] == true)
        // Straight past the battery step on a phone that has nothing to fix.
        if (backgroundUnrestricted(context)) onDone() else step = 1
    }

    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .systemBarsPadding()
                // Four rows of Persian at a large font scale is taller than a short phone.
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Space.xl),
            verticalArrangement = Arrangement.Center,
        ) {
            Spacer(Modifier.height(Space.xxl))
            Box(
                Modifier
                    .size(72.dp)
                    .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text("۱۰۰", fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
            }
            Spacer(Modifier.height(Space.xl))

            if (step == 0) {
                Text(
                    "قبل از شروع",
                    fontSize = 28.sp,
                    fontWeight = FontWeight.ExtraBold,
                    modifier = Modifier.semantics { heading() },
                )
                Spacer(Modifier.height(Space.m))
                Text(
                    "چقدر تومن خرجت رو از روی پیامک بانک می‌خونه. برای این کار به دو تا اجازه " +
                        "احتیاج داره:",
                    fontSize = 15.sp,
                    lineHeight = 26.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(Space.xl))
                AskRow(
                    title = "خواندن پیامک‌های بانک",
                    // The privacy promise, said where it matters most — at the moment she is
                    // deciding, not on a page she would have to go looking for.
                    body = "فقط پیامک بانک‌ها، فقط روی همین گوشی. هیچ‌جا فرستاده نمی‌شه.",
                )
                AskRow(
                    title = "اطلاع‌رسانی",
                    body = "تا لحظه‌ای که خرجی ثبت می‌شه خبردار بشی و بگی چی بوده.",
                )
                Spacer(Modifier.height(Space.xxl))
                Primary("اجازه بده") {
                    ask.launch(
                        buildList {
                            add(Manifest.permission.READ_SMS)
                            add(Manifest.permission.RECEIVE_SMS)
                            // Below 33 there is nothing to ask for — notifications are on unless
                            // she turns them off, and asking would throw rather than no-op.
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                add(Manifest.permission.POST_NOTIFICATIONS)
                            }
                        }.toTypedArray(),
                    )
                }
                Secondary("الان نه", onDone)
            } else {
                Text(
                    "یک قدم آخر",
                    fontSize = 28.sp,
                    fontWeight = FontWeight.ExtraBold,
                    modifier = Modifier.semantics { heading() },
                )
                Spacer(Modifier.height(Space.m))
                Text(
                    "این گوشی برنامه‌های بازنشده رو می‌خوابونه. اگه اجازه بدی چقدر تومن بیدار " +
                        "بمونه، پیامک بانک همون لحظه خونده می‌شه — وگرنه ممکنه تا چند ساعت بعد " +
                        "طول بکشه.",
                    fontSize = 15.sp,
                    lineHeight = 26.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(Space.xxl))
                Primary("باشه، اجازه می‌دم") {
                    askBackgroundExemption(context)
                    // Finished either way. The system dialog hands back no result, and a screen
                    // that waited for one would be a screen with no way off it.
                    onDone()
                }
                Secondary("بی‌خیال", onDone)
            }
            Spacer(Modifier.height(Space.xxl))
        }
    }
}

/** One permission, said as what it does for her rather than as what it is called. */
@Composable
private fun AskRow(title: String, body: String) {
    Row(Modifier.fillMaxWidth().padding(bottom = Space.l)) {
        Box(
            Modifier
                .padding(top = Space.s)
                .size(8.dp)
                .background(MaterialTheme.colorScheme.primary, CircleShape),
        )
        Column(Modifier.padding(start = Space.m)) {
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Text(
                body,
                fontSize = 13.sp,
                lineHeight = 22.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Space.xs),
            )
        }
    }
}

@Composable
private fun Primary(label: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        shape = RoundedCornerShape(Radius.pill),
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
    ) { Text(label, fontSize = 17.sp, fontWeight = FontWeight.Bold) }
}

/** Always present, always a real way out: nothing here may be a wall. */
@Composable
private fun Secondary(label: String, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        colors = ButtonDefaults.textButtonColors(
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
    ) { Text(label, fontSize = 15.sp) }
}
