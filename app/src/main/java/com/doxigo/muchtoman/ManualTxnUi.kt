package com.doxigo.muchtoman

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/**
 * «۱۴:۰۳» read back into minutes since Tehran midnight, from either digit set, or null.
 *
 * The one format [faClock] itself prints, so what the field opens with is always parseable and
 * the round trip is exact. A lone hour («۱۴») is not accepted: guessing «:۰۰» silently is how a
 * transaction lands at the top of the day's band instead of where it happened.
 */
fun parseFaClock(text: String): Long? {
    val ascii = buildString {
        for (c in text.trim()) when (c) {
            in '0'..'9' -> append(c)
            in '۰'..'۹' -> append('0' + (c - '۰'))
            in '٠'..'٩' -> append('0' + (c - '٠'))
            else -> append(c)
        }
    }
    val match = Regex("^(\\d{1,2}):(\\d{2})$").find(ascii) ?: return null
    val hour = match.groupValues[1].toInt()
    val minute = match.groupValues[2].toInt()
    if (hour > 23 || minute > 59) return null
    return (hour * 60L + minute) * 60_000L
}

/**
 * «تراکنش دستی» — the door for money no message will ever report: cash handed over, a دنگ paid
 * back, the fruit seller with no terminal.
 *
 * The answers come in the order she knows them: which way, how much, what for, then the two the
 * bank normally stamps — the day and the minute — already filled with now so the common case is
 * zero extra taps. The grid is the picker every other filing surface uses, in her own most-used
 * order, so the suggestion she wants is under her thumb here for the same reason it is there.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManualTxnSheet(
    categories: List<Category>,
    categoryUse: Map<String, Double>,
    onSave: (signedRial: Long, categoryId: String?, merchant: String, note: String, at: Long) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    fun close(then: () -> Unit) = scope.launch { sheetState.hide(); then() }

    var outgoing by rememberSaveable { mutableStateOf(true) }
    var amount by rememberSaveable { mutableStateOf("") }
    var merchant by rememberSaveable { mutableStateOf("") }
    var note by rememberSaveable { mutableStateOf("") }
    var categoryId by rememberSaveable { mutableStateOf<String?>(null) }

    // The day and the minute, seeded from now: entered at the till, nothing here is touched.
    val openedAt = remember { System.currentTimeMillis() }
    val today = remember(openedAt) { tehranDay(openedAt) }
    var day by rememberSaveable { mutableStateOf(today) }
    var clock by rememberSaveable { mutableStateOf(faClock(openedAt)) }

    val rial = remember(amount) { tomanFieldToRial(amount) }
    val sinceMidnight = remember(clock) { parseFaClock(clock) }
    val choices = remember(categories, outgoing, categoryUse) {
        categoryChoices(categories, if (outgoing) "out" else "in", categoryUse)
    }
    // Flipping the direction swaps the grid, and a pick from the other side must not survive
    // invisibly — a خرج filed under «حقوق» is money on the wrong side of every report.
    androidx.compose.runtime.LaunchedEffect(choices) {
        if (categoryId != null && choices.none { it.id == categoryId }) categoryId = null
    }
    // Raised by a save tap with no category picked. The grid cannot flag itself the way the
    // amount and clock fields do, so the refusal's words live under it instead.
    var missingCategory by remember { mutableStateOf(false) }
    // And by one with no amount at all: the field's own error only speaks over a malformed
    // figure, so an untouched field refused in silence.
    var missingAmount by remember { mutableStateOf(false) }

    val usable = rial != null && categoryId != null && sinceMidnight != null

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = Radius.sheet, topEnd = Radius.sheet),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            Modifier
                .nestedScroll(SheetFlingGuard)
                .navigationBarsPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Space.xl)
                .padding(bottom = Space.l),
        ) {
            SheetTitle("تراکنش دستی")

            SheetLabel("خرج بود یا دخل؟")
            SegmentedChoice(
                options = listOf(true, false),
                selected = outgoing,
                label = { if (it) "خرج" else "دخل" },
                onSelect = { outgoing = it },
            )

            SheetLabel("چقدر، به تومان")
            OutlinedTextField(
                value = amount,
                onValueChange = { amount = it },
                label = { Text("مثلاً ۴۵۰ هزار") },
                singleLine = true,
                visualTransformation = GroupedNumber,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                supportingText = when {
                    amount.isBlank() && missingAmount -> ({
                        Text(
                            "مبلغش رو بنویس.",
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        )
                    })
                    amount.isNotBlank() && rial == null -> ({
                        Text("این عدد قابل خوندن نیست. فقط عدد وارد کن.")
                    })
                    // Spelled out, like every other amount field: digits are quick to scan and
                    // easy to misread by a factor of ten.
                    rial != null -> ({ Text(faWordsToman(tomanOf(rial)).orEmpty()) })
                    else -> null
                },
                shape = RoundedCornerShape(Radius.field),
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = "مبلغ به تومان" },
            )

            SheetLabel("بابت چی؟")
            OutlinedTextField(
                value = merchant,
                onValueChange = { merchant = it.take(60) },
                singleLine = true,
                label = { Text("مثلاً میوه‌فروشی — خالی هم می‌شه") },
                shape = RoundedCornerShape(Radius.field),
                modifier = Modifier.fillMaxWidth(),
            )

            SheetLabel("دسته‌بندی")
            CategoryGrid(
                choices = choices,
                selectedId = categoryId,
                onPick = { categoryId = it.id },
                selectedLabel = "انتخاب‌شده",
            )
            if (missingCategory && categoryId == null) {
                Text(
                    "دسته‌اش رو انتخاب کن.",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier
                        .padding(top = Space.s, start = Space.xs)
                        // Announced, so the refusal exists for someone listening too — the
                        // words appearing under a grid mid-sheet are easy to never reach.
                        .semantics { liveRegion = LiveRegionMode.Polite },
                )
            }

            SheetLabel("کِی؟")
            Row(verticalAlignment = Alignment.CenterVertically) {
                PillButton("روز قبل", { day -= 1 }, fontSize = 12.sp, minHeight = 40.dp)
                Column(
                    Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        faDay(day, today),
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center,
                    )
                    // «امروز» is an answer, not a date — the date it stands for is stated under
                    // it, so what is about to be stored is on screen before it is stored.
                    if (day >= today - 1) {
                        Text(
                            faDate(day),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
                // The ledger records what happened, and tomorrow has not — the stepper simply
                // stops at today rather than dimming into a control that needs explaining.
                PillButton(
                    "روز بعد",
                    { if (day < today) day += 1 },
                    fontSize = 12.sp,
                    minHeight = 40.dp,
                )
            }

            Spacer(Modifier.height(Space.m))
            OutlinedTextField(
                value = clock,
                onValueChange = { clock = it.take(5) },
                singleLine = true,
                isError = sinceMidnight == null,
                label = { Text("ساعت") },
                supportingText = if (sinceMidnight == null) {
                    { Text("ساعت رو مثل ۱۴:۳۰ بنویس.") }
                } else {
                    null
                },
                shape = RoundedCornerShape(Radius.field),
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = "ساعت تراکنش" },
            )

            SheetLabel("توضیحات")
            OutlinedTextField(
                value = note,
                onValueChange = { note = it.take(200) },
                label = { Text("یادداشت — خالی هم می‌شه") },
                minLines = 2,
                shape = RoundedCornerShape(Radius.field),
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(Space.xl))
            PillButton(
                "ثبت تراکنش",
                {
                    // The pill never greys out — a dead button explains nothing — so a tap
                    // that cannot save has to say why. A malformed amount and the clock speak
                    // for themselves; a blank amount and the category need this tap to raise
                    // their words.
                    missingCategory = categoryId == null
                    missingAmount = amount.isBlank()
                    val cleanRial = rial ?: return@PillButton
                    val minute = sinceMidnight ?: return@PillButton
                    val category = categoryId ?: return@PillButton
                    val at = tehranDayStart(day) + minute
                    val signed = if (outgoing) -cleanRial else cleanRial
                    close { onSave(signed, category, merchant, note, at) }
                },
                voice = if (usable) ButtonVoice.PRIMARY else ButtonVoice.TONAL,
                modifier = Modifier.fillMaxWidth(),
                fontSize = 16.sp,
                minHeight = 56.dp,
            )
            Spacer(Modifier.height(Space.s))
            PillButton(
                "انصراف",
                { close(onDismiss) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
