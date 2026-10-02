package com.doxigo.muchtoman

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
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
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.sp

/** The clock field's keystrokes as it keeps them: ASCII digits, from either digit set, nothing else. */
fun clockDigits(text: String): String = buildString {
    for (c in text) when (c) {
        in '0'..'9' -> append(c)
        in '۰'..'۹' -> append('0' + (c - '۰'))
        in '٠'..'٩' -> append('0' + (c - '٠'))
    }
}

/**
 * «۱۴:۰۳» — or the field's own «۱۴۰۳» — read back into minutes since Tehran midnight, or null.
 *
 * Four digits, hour then minute; the colon is the field's to draw, never hers to find on a
 * keyboard. A lone hour («۱۴») or three digits («۹۳۰») is not accepted: guessing «:۰۰» silently
 * is how a transaction lands at the top of the day's band instead of where it happened.
 */
fun parseFaClock(text: String): Long? {
    val digits = clockDigits(text)
    if (digits.length != 4) return null
    val hour = digits.take(2).toInt()
    val minute = digits.takeLast(2).toInt()
    if (hour > 23 || minute > 59) return null
    return (hour * 60L + minute) * 60_000L
}

/**
 * «۱۴۰۳» drawn as «۱۴:۰۳»: Persian digits, and the colon once the minute has begun. Never a
 * trailing colon — a separator she could backspace into would be a keystroke that does nothing.
 */
private val ClockMask = VisualTransformation { text ->
    val colonAt = if (text.length > 2) 2 else -1
    val shown = buildString {
        text.forEachIndexed { i, c ->
            if (i == colonAt) append(':')
            append('۰' + (c - '0'))
        }
    }
    TransformedText(
        AnnotatedString(shown),
        object : OffsetMapping {
            override fun originalToTransformed(offset: Int) = if (colonAt in 0 until offset) offset + 1 else offset
            override fun transformedToOriginal(offset: Int) = if (colonAt in 0 until offset) offset - 1 else offset
        },
    )
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
    val focus = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    fun close(then: () -> Unit) = scope.hideThen(sheetState, then)

    var outgoing by rememberSaveable { mutableStateOf(true) }
    var amount by rememberSaveable { mutableStateOf("") }
    var merchant by rememberSaveable { mutableStateOf("") }
    var note by rememberSaveable { mutableStateOf("") }
    var categoryId by rememberSaveable { mutableStateOf<String?>(null) }

    // The day and the minute, seeded from now: entered at the till, nothing here is touched.
    val openedAt = remember { System.currentTimeMillis() }
    val today = remember(openedAt) { tehranDay(openedAt) }
    var day by rememberSaveable { mutableStateOf(today) }
    var clock by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(clockDigits(faClock(openedAt))))
    }
    val clockInteraction = remember { MutableInteractionSource() }
    val clockFocused by clockInteraction.collectIsFocusedAsState()
    // The usual edit is a different time, not a different digit: a tap selects all four, so
    // typing replaces them instead of landing beside them. An effect rather than the focus
    // callback, because the tap that focuses the field places its caret after that callback.
    LaunchedEffect(clockFocused) {
        if (clockFocused) clock = clock.copy(selection = TextRange(0, clock.text.length))
    }

    val rial = remember(amount) { tomanFieldToRial(amount) }
    val sinceMidnight = remember(clock.text) { parseFaClock(clock.text) }
    val choices = remember(categories, outgoing, categoryUse) {
        categoryChoices(categories, if (outgoing) "out" else "in", categoryUse)
    }
    // Flipping the direction swaps the grid, and a pick from the other side must not survive
    // invisibly — a خرج filed under «حقوق» is money on the wrong side of every report.
    LaunchedEffect(choices) {
        if (categoryId != null && choices.none { it.id == categoryId }) categoryId = null
    }
    // Raised by a save tap with no category picked. The grid cannot flag itself the way the
    // amount and clock fields do, so the refusal's words live under it instead.
    var missingCategory by remember { mutableStateOf(false) }
    // And by one with no amount at all: the field's own error only speaks over a malformed
    // figure, so an untouched field refused in silence.
    var missingAmount by remember { mutableStateOf(false) }

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
            if (missingCategory && categoryId == null) MissingText("دسته‌اش رو انتخاب کن.")

            SheetLabel("کِی؟")
            DayStepper(day, today) { day = it }

            Spacer(Modifier.height(Space.m))
            OutlinedTextField(
                value = clock,
                onValueChange = {
                    val digits = clockDigits(it.text)
                    // A fifth digit is refused rather than pushing one off the end; anything
                    // that is not a digit — a pasted «۱۴:۳۰»'s colon — just falls away.
                    clock = when {
                        digits.length > 4 -> clock
                        digits.length == it.text.length -> it.copy(text = digits)
                        else -> TextFieldValue(digits, TextRange(digits.length))
                    }
                },
                singleLine = true,
                // Red only once she has left it: every retyped time passes through «۱» and «۱۴»
                // on the way, and those are unfinished, not wrong.
                isError = sinceMidnight == null && !clockFocused,
                label = { Text("ساعت") },
                supportingText = if (sinceMidnight == null) {
                    {
                        Text(
                            "ساعت رو چهاررقمی بنویس، مثل ۰۹:۳۰.",
                            // Announced once it turns into a refusal, not while it is a hint.
                            modifier = if (clockFocused) Modifier
                            else Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        )
                    }
                } else {
                    null
                },
                visualTransformation = ClockMask,
                // Left to right, so the caret walks the digits the way they read and the hour
                // stays on the left of the colon while she types.
                textStyle = LocalTextStyle.current.copy(
                    textAlign = TextAlign.Right,
                    textDirection = TextDirection.Ltr,
                ),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                interactionSource = clockInteraction,
                shape = RoundedCornerShape(Radius.field),
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = "ساعت تراکنش" },
            )

            SheetLabel("توضیحات")
            OutlinedTextField(
                value = note,
                onValueChange = { note = it.take(MAX_NOTE_CHARS) },
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
                    // their words. Focus lets go first: the keyboard was covering them, and the
                    // clock only turns red once it is no longer being typed in.
                    focus.clearFocus()
                    missingCategory = categoryId == null
                    missingAmount = amount.isBlank()
                    val cleanRial = rial ?: return@PillButton
                    val minute = sinceMidnight ?: return@PillButton
                    val category = categoryId ?: return@PillButton
                    val at = tehranDayStart(day) + minute
                    val signed = if (outgoing) -cleanRial else cleanRial
                    close { onSave(signed, category, merchant, note, at) }
                },
                voice = ButtonVoice.PRIMARY,
                modifier = Modifier.fillMaxWidth(),
                block = true,
            )
            Spacer(Modifier.height(Space.s))
            PillButton(
                "انصراف",
                { close(onDismiss) },
                modifier = Modifier.fillMaxWidth(),
                block = true,
            )
        }
    }
}

/**
 * A transaction's day, one step at a time.
 *
 * Two surfaces pick one now: the sheet that enters a transaction, and the page that corrects a
 * day she typed in wrong. A stepper rather than a calendar because the answer is almost always
 * today or one of the few days behind it — and because the platform's own picker is a Gregorian
 * grid, which is the wrong calendar to ask an Iranian household a question in.
 *
 * The ledger records what happened, and tomorrow has not, so «روز بعد» dims at [today]. Beside
 * «امروز» the dimmed pill needs no words: a stepper at its end is the one disabled control
 * everybody already reads, and a live-looking pill that did nothing when pressed was worse.
 */
@Composable
internal fun DayStepper(day: Long, today: Long, onDay: (Long) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        PillButton("روز قبل", { onDay(day - 1) })
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
            // «امروز» is an answer, not a date — the date it stands for is stated under it, so
            // what is about to be stored is on screen before it is stored. Two days back and
            // further, the line above already *is* the written-out date and this one would
            // repeat it.
            if (day >= today - 1) {
                Text(
                    faWeekdayDate(day),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
        PillButton("روز بعد", { onDay(day + 1) }, enabled = day < today)
    }
}
