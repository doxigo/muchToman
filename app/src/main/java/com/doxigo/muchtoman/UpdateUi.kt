package com.doxigo.muchtoman

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/**
 * The update card and its sheet, and the two doors out of the app they share a file with: a link
 * in her browser, and Android's own notification page.
 *
 * Every one of them ends somewhere the app does not own, so nothing here acts on its own. The new
 * build is a file she downloads and installs herself; the notification switch is one only Android
 * can flip, and the most the app may do is open the page it is on.
 */

/**
 * Android's own notification page for this app — the only place the switch actually lives.
 *
 * Needed on two paths that look unrelated and are the same dead end: below API 33 there is no
 * runtime permission to ask for, and above it a second refusal makes the dialog stop appearing.
 * Either way the app cannot turn its own notifications on, and pointing at the page that can is
 * the whole of what it may do about it. Failure is silent on purpose — an OEM build with no such
 * activity is a phone where the card simply stays on screen.
 */
internal fun openNotificationSettings(context: Context) {
    val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
    } else {
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.fromParts("package", context.packageName, null))
    }
    runCatching { context.startActivity(intent) }
        .onFailure { android.util.Log.w("muchtoman", "notification settings unavailable: $it") }
}

internal fun openUrl(context: Context, url: String): Boolean =
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }.isSuccess

/**
 * That there is a newer build, and nothing else. The card is one tap target: what changed and
 * both of the decisions live in the sheet behind it, because a line of card is no place to read
 * a changelog and a dismiss button sitting beside the thing it dismisses gets pressed by
 * accident.
 */
@Composable
internal fun UpdateNote(release: Release, onLater: () -> Unit) {
    // Keyed on the version: a newer release arriving while she has the card open must not
    // inherit the sheet state of the one it replaced.
    var open by remember(release.name) { mutableStateOf(false) }

    Card(
        onClick = { open = true },
        shape = RoundedCornerShape(Radius.card),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = Space.l, vertical = Space.m)) {
            Text(
                // Latin digits, and bidi() so the runs do not reorder inside the sentence:
                // this is the tag she will see on the page the card opens, not a quantity.
                "نسخه ${bidi(release.name)} اومد",
                fontSize = 16.sp,
                fontWeight = FontWeight.ExtraBold,
            )
            Text(
                "بزن ببین چی عوض شده.",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f),
                modifier = Modifier.padding(top = Space.xs),
            )
        }
    }

    if (open) {
        UpdateSheet(
            release = release,
            onClose = { open = false },
            onLater = { open = false; onLater() },
        )
    }
}

/**
 * What the new build changes, and the two things she can do about it.
 *
 * Nothing updates a sideloaded app on its own, so the button is the entire update path: it hands
 * the file to whatever she downloads with and she installs it herself. The app never fetches the
 * APK, never asks for the installer permission, and nothing touches the phone without her. The
 * link is the Worker's proxied copy, which answers with a Content-Disposition attachment, so the
 * download starts on the tap instead of landing her on a github.com page that, from Iran, most
 * often does not load at all.
 *
 * The two ways out are deliberately not the same. «بعداً» is an answer — the card goes away and
 * this version is not mentioned again. Swiping the sheet down or tapping the scrim is not an
 * answer, so the card is still there afterwards; nobody should lose the update by brushing it
 * away before reading it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UpdateSheet(release: Release, onClose: () -> Unit, onLater: () -> Unit) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    // Play the sheet out before removing it from composition, or the choice just blinks.
    fun close(then: () -> Unit) = scope.launch { sheetState.hide(); then() }

    AppSheet(
        onDismissRequest = onClose,
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
            SheetTitle("نسخه ${bidi(release.name)} اومد")
            Text(
                // A release cut without a note is not an error worth showing her as one.
                if (release.notes.isEmpty()) "توضیحی همراهش نیومده، ولی از این که داری تازه‌تره."
                else "این چیزهاییه که عوض شده:",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 20.sp,
                modifier = Modifier.padding(top = Space.xs),
            )

            // weight(fill = false) rather than a fixed max height: the list takes what is left
            // above the buttons and no more, so a short changelog sizes the sheet to itself and
            // a long one scrolls instead of pushing the button that acts on it off the bottom.
            // A dp cap does neither — it clips four ordinary lines mid-sentence on a tall screen
            // and still overflows a short one.
            Column(
                Modifier
                    .weight(1f, fill = false)
                    .nestedScroll(SheetFlingGuard)
                    .verticalScroll(rememberScrollState()),
            ) {
                release.notes.forEach { line ->
                    Row(Modifier.padding(top = Space.m)) {
                        Text("•", fontSize = 15.sp, color = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(Space.s))
                        Text(line, fontSize = 15.sp, lineHeight = 26.sp)
                    }
                }
            }

            Spacer(Modifier.height(Space.xl))
            PillButton(
                "گرفتن فایل نصب",
                { close { onClose(); openUrl(context, release.downloadUrl) } },
                Modifier.fillMaxWidth(),
                voice = ButtonVoice.PRIMARY,
                block = true,
            )

            PillButton(
                "بعداً",
                { close(onLater) },
                Modifier
                    .fillMaxWidth()
                    .padding(top = Space.s),
                block = true,
            )
        }
    }
}
