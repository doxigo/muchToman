package com.doxigo.muchtoman

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * حساب‌های بانکی on screen: the sheet with one row per tracked account, the page each row opens,
 * the senders the inbox offers to add, and the marks that name a bank or a chain.
 *
 * This is the audit surface — where she holds a balance up against the bank's own message — so
 * every figure here is the raw one the ledger keeps, with how fresh it is beside it, and an
 * account that has gone quiet or sits out of the total says so in words before any colour does.
 */

/** The bank row's second line — see [bankCountNoteFa]. */
internal fun bankNote(state: UiState): String = bankCountNoteFa(
    countedBankAccounts(state.bankAccounts, state.smsEnabled, state.notified),
    state.disabledBanks,
    System.currentTimeMillis(),
)

/**
 * Every tracked account, one row each, with the raw figure and how fresh it is — the audit surface's
 * index. A row opens the account's own page ([BankPage]), which holds what acts on it. It was a card
 * each, with the switch, the doubt and a foot of pills on every one, so a sheet she mostly opens to
 * read was four phones tall; the rows are a band, as the asset list they open from is.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BankSheet(
    accounts: List<BankAccount>,
    /** Whether anything reads her banks. Off, every account here is one she keeps by hand. */
    reads: Boolean,
    disabled: Set<String>,
    onOpen: (String) -> Unit,
    onAddNumber: (String, String) -> Unit,
    loadSenders: suspend () -> List<SenderCandidate>?,
    onRescan: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val context = LocalContext.current
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val now = rememberNow()
    // The picker reads the inbox only once she opens it; null afterwards means the app may not
    // read messages at all.
    var picking by remember { mutableStateOf(false) }
    var reading by remember { mutableStateOf(false) }
    var found by remember { mutableStateOf<List<SenderCandidate>?>(null) }
    LaunchedEffect(picking) {
        if (!picking) return@LaunchedEffect
        found = loadSenders()
        reading = false
    }

    AppSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = Radius.sheet, topEnd = Radius.sheet),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        // As tall as its rows, not a fixed 88%: with the cards gone, four banks are a third of that,
        // and a sheet of air under them reads as something still loading.
        Column(
            Modifier
                .nestedScroll(SheetFlingGuard)
                .navigationBarsPadding()
                .padding(horizontal = Space.xl),
        ) {
            SheetTitle("حساب‌های بانکی")

            LazyColumn(
                contentPadding = PaddingValues(top = Space.l, bottom = Space.xxl),
                modifier = Modifier.fillMaxWidth(),
            ) {
                itemsIndexed(accounts, key = { _, acc -> acc.key }) { i, acc ->
                    BankRow(
                        account = acc,
                        off = acc.bank in disabled,
                        now = now,
                        shape = bandShape(i, accounts.size),
                        divided = i < accounts.size - 1,
                        onClick = { onOpen(acc.key) },
                    )
                }

                // Everything below works on her messages, and while nothing reads them every row
                // above is a bank she keeps by hand: a number to add or an inbox to re-read would
                // be tools for something she said no to.
                if (!reads) return@LazyColumn

                // A bank that starts sending from a new number simply freezes, which looks like a
                // reading bug. This lists every unknown sender whose messages would be read, and
                // she names the bank.
                // Full-width pills, a gap below the band: these act on the whole list, not on the
                // row above them, and two floating green lines read as neither.
                if (!picking) {
                    item {
                        PillButton(
                            "اضافه کردن شمارهٔ بانک از پیامک‌ها",
                            { picking = true; reading = true },
                            Modifier
                                .fillMaxWidth()
                                .padding(top = Space.xl, bottom = Space.s),
                            block = true,
                        )
                    }
                } else {
                    val offered = found.orEmpty()
                    item {
                        Text(
                            when {
                                reading -> "در حال خوندن پیامک‌ها…"
                                found == null -> "برای این کار باید اجازهٔ خوندن پیامک رو بدی."
                                offered.isEmpty() -> "پیامکی با مبلغ از شمارهٔ تازه‌ای پیدا نشد."
                                else -> "این شماره‌ها پیامکی با مبلغ فرستادن ولی هنوز توی فهرست نیستن. " +
                                    "بانکش رو انتخاب کنی، پیامک‌هاش خونده می‌شه."
                            },
                            fontSize = 13.sp,
                            lineHeight = 20.sp,
                            color = muted,
                            modifier = Modifier
                                .padding(top = Space.xl, bottom = Space.s)
                                .semantics { liveRegion = LiveRegionMode.Polite },
                        )
                    }
                    items(offered, key = { "c_${it.sender}" }) { candidate ->
                        SenderCandidateCard(
                            candidate = candidate,
                            now = now,
                            onOpen = { openSmsThread(context, candidate.sender) },
                            onAdd = { bank ->
                                onAddNumber(bank.name, candidate.sender)
                                found = found?.minus(candidate)
                            },
                            modifier = Modifier.padding(bottom = Space.s),
                        )
                    }
                }

                // A message is read once and skipped ever after, so a balance that has gone
                // wrong stays wrong on its own. This is the way back: forget the lot and read
                // her messages again from the start.
                item {
                    ArmedButton(
                        "دوباره خوندن همه پیامک‌ها",
                        "همه مبلغ‌ها دوباره از پیامک‌ها خونده بشن؟ دوباره بزن",
                        onRescan,
                        Modifier
                            .fillMaxWidth()
                            .padding(top = if (picking) Space.s else 0.dp),
                        voice = ButtonVoice.TONAL,
                        block = true,
                    )
                }
            }
        }
    }

}

/**
 * «همین الان» must not still say that half an hour later — the same slow tick as the hero card keeps
 * every faAgo honest while a sheet or page sits open; nothing finer than the minute would ever show.
 */
@Composable
private fun rememberNow(): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(30_000)
        }
    }
    return now
}

/**
 * One sender the picker offers: the newest of its messages that reads as money, and which bank it
 * is. Nothing comes preselected — she names the bank. Adding is its own tap after that, because a
 * number cannot be taken back off a bank once it is on one.
 */
@Composable
private fun SenderCandidateCard(
    candidate: SenderCandidate,
    now: Long,
    onOpen: () -> Unit,
    onAdd: (Bank) -> Unit,
    modifier: Modifier = Modifier,
) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    var bank by remember(candidate.sender) { mutableStateOf<Bank?>(null) }
    var choosing by remember(candidate.sender) { mutableStateOf(false) }
    Card(
        onClick = onOpen,
        shape = RoundedCornerShape(Radius.card),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(Space.l)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    bidi(candidate.sender),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(Space.s))
                Text(faAgo(candidate.at, now), fontSize = 12.sp, color = muted)
            }
            Text(
                "«${candidate.snippet}»",
                fontSize = 12.sp,
                lineHeight = 19.sp,
                color = muted,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = Space.xs),
            )
            Text(
                "باز کردن پیامک‌ها",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                textDecoration = TextDecoration.Underline,
                modifier = Modifier.padding(top = Space.s),
            )
            // Stacked, not side by side: beside «بانک دیگه» a long name like خاورمیانه cut the
            // confirm off before «اضافه‌اش کن», the one word that says what the button does.
            Column(
                Modifier.padding(top = Space.m),
                verticalArrangement = Arrangement.spacedBy(Space.s),
            ) {
                bank?.let { b ->
                    PillButton(
                        "این شماره مال ${b.fa} هست؛ اضافه‌اش کن",
                        { onAdd(b) },
                        Modifier.fillMaxWidth(),
                        voice = ButtonVoice.PRIMARY,
                    )
                }
                Box {
                    PillButton(
                        if (bank == null) "انتخاب بانک" else "بانک دیگه",
                        { choosing = true },
                        Modifier.fillMaxWidth(),
                    )
                    // A menu is a window of its own too — see [AppSheet].
                    DropdownMenu(expanded = choosing, onDismissRequest = { choosing = false }) {
                        ScaledText {
                            for (b in PICKABLE_BANKS) {
                                DropdownMenuItem(
                                    text = { Text(b.fa) },
                                    leadingIcon = { BankLogo(b.name, size = 24.dp) },
                                    onClick = { bank = b; choosing = false },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * One account in the sheet: [HoldingRow]'s anatomy, the bank's logo in the icon's slot. Out of the
 * total — switched off, or never anchored — reads the way a set-aside holding does: the logo dimmed,
 * name and figure muted, the figure struck, and the caption saying which.
 */
@Composable
private fun BankRow(
    account: BankAccount,
    off: Boolean,
    now: Long,
    shape: Shape,
    divided: Boolean,
    onClick: () -> Unit,
) {
    val counted = !off && account.anchored
    val ink = if (counted) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface)
            .clickable(role = Role.Button, onClick = onClick),
    ) {
        // Top, as on the asset rows: the logo belongs to the name, and the caption may run to two lines.
        Row(Modifier.padding(Space.l), verticalAlignment = Alignment.Top) {
            Box(Modifier.alpha(if (counted) 1f else 0.55f)) { BankLogo(account.bank, size = 44.dp) }
            Spacer(Modifier.width(Space.m))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.Top) {
                    RowTitle(account.bankFa, color = ink, max = 17.sp, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(Space.s))
                    RowAmount(account.balance, color = ink, struck = !counted, unit = moneyUnit)
                }
                BankCaption(account, off, now, fontSize = 13.sp, modifier = Modifier.padding(top = 2.dp))
            }
        }
        if (divided) {
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant,
                modifier = Modifier.align(Alignment.BottomStart).padding(start = Space.l + 44.dp + Space.m),
            )
        }
    }
}

/**
 * [bankCaptionFa] on screen. A second line rather than an ellipsis, broken only at a bullet: each part
 * keeps its words together, so «۲ روز پیش» is never cut in half — with an ordinary space and a word
 * joiner after it, as [setAsideFa] holds «وضعیت دفتر»: Modam's no-break space draws three and a half
 * spaces wide, and the cards these rows replaced opened a hole between every word. A quiet bank's
 * sentence is set in amber and a weight up — the words say it, the colour only confirms.
 */
@Composable
private fun BankCaption(account: BankAccount, off: Boolean, now: Long, fontSize: TextUnit, modifier: Modifier = Modifier) {
    val quiet = bankStaleDays(account, off, now)?.let(::bankStaleFa)
    val amber = MaterialTheme.colorScheme.secondary
    Text(
        buildAnnotatedString {
            bankCaptionFa(account, off, now).forEachIndexed { i, part ->
                if (i > 0) append(" \u2060• ")
                val kept = part.replace(" ", " \u2060")
                if (part == quiet) withStyle(SpanStyle(color = amber, fontWeight = FontWeight.SemiBold)) { append(kept) }
                else append(kept)
            }
        },
        fontSize = fontSize,
        lineHeight = fontSize * 1.45f,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

/**
 * One account's own page, opened from its row: the figure, how fresh it is, and everything that acts
 * on it. Built the way a transaction's page and a person's in طلب و بدهی are — the reading on top, the
 * acts as a band at the foot, and the delete last in a band of its own, two taps, its armed line
 * naming what goes. The switch is the settings page's bank row ([SettingCard] with the logo), so the
 * one switch reads the same in both places it lives.
 */
@Composable
internal fun BankPage(
    account: BankAccount,
    off: Boolean,
    onAnchor: (Double) -> Unit,
    onToggle: (Boolean) -> Unit,
    /** Null where the account has no sender to open (one she keeps by hand). */
    onOpenSms: (() -> Unit)?,
    onForget: () -> Unit,
    onBack: () -> Unit,
) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val now = rememberNow()
    val counted = !off && account.anchored
    val focus = LocalFocusManager.current
    var fixing by rememberSaveable(account.key) { mutableStateOf(false) }
    var draft by rememberSaveable(account.key) { mutableStateOf("") }
    // Raised by a save tap with nothing typed: the field's own words only speak over a malformed
    // figure, so an empty one was refused in silence.
    var missingDraft by remember(account.key, fixing) { mutableStateOf(false) }
    val field = remember { FocusRequester() }
    LaunchedEffect(fixing) { if (fixing) field.requestFocus() }

    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = Space.xl)
                .padding(bottom = Space.huge),
        ) {
            PageHead(account.bankFa, onBack)

            // The figure at the size a person's page sets what is owed: digits large, the magnitude
            // and unit a step down, the words under it as the guard against a misread zero.
            val (digits, words) = faCompact(account.balance, 2, pad = true).split(' ', limit = 2)
                .let { it[0] to (listOfNotNull(it.getOrNull(1)) + moneyUnit).joinToString(" ") }
            Text(
                buildAnnotatedString {
                    append(digits)
                    withStyle(SpanStyle(fontSize = 0.58.em, fontWeight = FontWeight.ExtraBold)) { append(" $words") }
                },
                style = figureStyle(if (counted) MaterialTheme.colorScheme.onBackground else muted, FontWeight.Black)
                    .copy(textDecoration = if (counted) null else TextDecoration.LineThrough),
                maxLines = 1,
                autoSize = TextAutoSize.StepBased(minFontSize = 28.sp, maxFontSize = 46.sp),
                modifier = Modifier.padding(top = Space.s),
            )
            faWordsToman(account.balance)?.let {
                Text(it, fontSize = 15.sp, lineHeight = 25.sp, color = muted, modifier = Modifier.padding(top = Space.xs))
            }
            BankCaption(account, off, now, fontSize = 14.sp, modifier = Modifier.padding(top = Space.m))

            // The whole point of the flag: say what is uncertain and offer the one thing that
            // fixes it, rather than leaving a number on screen that quietly might be wrong.
            if (!account.trusted) {
                Text(
                    if (!account.anchored)
                        "این عدد فقط جمع تراکنش‌هاست، نه موجودی واقعی، و توی جمع حساب نشده. " +
                            "موجودی حسابت رو وارد کن تا توی جمع بیاد."
                    else "این عدد از پیامکی خونده شده که از درست بودنش مطمئن نیستیم. اگه اشتباهه، اصلاحش کن.",
                    fontSize = 13.sp,
                    lineHeight = 20.sp,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = Space.s),
                )
            }

            if (fixing) {
                // A label that stays, not a placeholder that vanishes at the first digit — while she
                // types a balance, the unit must still be on screen.
                Text(
                    "موجودی به $moneyUnit",
                    fontSize = 14.sp,
                    color = muted,
                    modifier = Modifier.padding(top = Space.xl),
                )
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    singleLine = true,
                    visualTransformation = GroupedNumber,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    supportingText = when {
                        draft.isBlank() && missingDraft -> ({
                            Text(
                                "مبلغش رو بنویس.",
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                            )
                        })
                        draft.isNotBlank() && parseAmount(draft) == null -> ({
                            Text("این عدد قابل خوندن نیست. فقط عدد وارد کن.")
                        })
                        else -> null
                    },
                    shape = RoundedCornerShape(Radius.field),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = Space.xs)
                        .focusRequester(field)
                        .semantics { contentDescription = "موجودی به $moneyUnit" },
                )
                // «ذخیره» first, under the thumb that asked for it; «بستن» is the way back out.
                Row(Modifier.padding(top = Space.m), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                    PillButton(
                        "ذخیره موجودی",
                        {
                            // Never greyed out — a dead button explains nothing — so a tap that
                            // cannot save says why, with the keyboard out of the way.
                            focus.clearFocus()
                            missingDraft = draft.isBlank()
                            parseAmount(draft)?.let {
                                onAnchor(tomanOfShown(it))
                                draft = ""
                                fixing = false
                            }
                        },
                        voice = ButtonVoice.PRIMARY,
                    )
                    PillButton("بستن", { focus.clearFocus(); fixing = false })
                }
            }

            // The acts: while the field is open its own pills are the correction, so the row that
            // opened it steps out rather than offering the same act twice.
            val acts = buildList<@Composable (Shape, Boolean) -> Unit> {
                if (!fixing) add { shape, divided -> ActRow("اصلاح موجودی", ActGlyph.PENCIL, shape, divided, { fixing = true }) }
                onOpenSms?.let { open -> add { shape, divided -> ActRow("دیدن پیامک‌ها", ActGlyph.MAIL, shape, divided, open) } }
            }
            if (acts.isNotEmpty()) Spacer(Modifier.height(Space.xxl))
            acts.forEachIndexed { i, act -> act(bandShape(i, acts.size), i < acts.size - 1) }

            // Only for an anchored account: an unanchored one is never in the total, and a switch
            // saying "counted" over the red line saying it is not would be the page contradicting
            // itself. The same switch, with the same words, as setting an asset aside.
            if (account.anchored) {
                Spacer(Modifier.height(if (acts.isEmpty()) Space.xxl else Space.l))
                SettingCard(
                    title = "توی جمع حساب بشه",
                    subtitle = bankSwitchFa(account.manual || BuildConfig.LITE),
                    checked = !off,
                    onChange = onToggle,
                    badge = { BankLogo(account.bank, size = 44.dp) },
                )
            }

            // Forgetting the account is the last word on a balance read wrong: on the page, where she
            // looks for it, never a stray tap on a row she is only reading. Two taps, as for deleting a
            // holding: a balance anchored by hand is a number nothing can rebuild, so the first tap
            // only names what is lost.
            Spacer(Modifier.height(Space.l))
            DeleteRow(
                account.key,
                onForget,
                "حذف حساب",
                // Nothing restarts a bank she keeps by hand; the number she wrote just goes.
                armedLabel = if (account.manual) "موجودی‌ای که نوشتی پاک می‌شه؛ برای حذف دوباره بزن"
                else "موجودیش از صفر شروع می‌شه؛ برای حذف دوباره بزن",
            )
        }
    }
}

/**
 * The bank's own mark, on a white plate.
 *
 * The plate is not decoration: these are the banks' real logos, drawn for a white page, and a
 * dozen of them are near-black — on the dark theme they would be a black shape on a dark card.
 * The `when` is exhaustive on purpose, so a bank added to the enum cannot ship without one;
 * every logo the list has is already in `res/drawable` as `ic_bank_<name>`.
 */
@Composable
internal fun BankLogo(bank: String, size: Dp = 32.dp) {
    val logo = when (runCatching { Bank.valueOf(bank) }.getOrDefault(Bank.OTHER)) {
        Bank.BLU -> R.drawable.ic_bank_blu
        Bank.SAMAN -> R.drawable.ic_bank_saman
        Bank.REFAH -> R.drawable.ic_bank_refah
        Bank.PASARGAD -> R.drawable.ic_bank_pasargad
        Bank.EGHTESAD_NOVIN -> R.drawable.ic_bank_eghtesad_novin
        Bank.KHAVARMIANEH -> R.drawable.ic_bank_khavar_mianeh
        Bank.SADERAT -> R.drawable.ic_bank_saderat
        Bank.RESALAT -> R.drawable.ic_bank_resalat
        Bank.PARSIAN -> R.drawable.ic_bank_parsian
        Bank.MELLAT -> R.drawable.ic_bank_mellat
        Bank.MELLI -> R.drawable.ic_bank_melli
        Bank.DEY -> R.drawable.ic_bank_dey
        Bank.TEJARAT -> R.drawable.ic_bank_tejarat
        Bank.SEPAH -> R.drawable.ic_bank_sepah
        Bank.AYANDEH -> R.drawable.ic_bank_ayandeh
        Bank.KESHAVARZI -> R.drawable.ic_bank_keshavarzi
        Bank.SHAHR -> R.drawable.ic_bank_shahr
        Bank.SINA -> R.drawable.ic_bank_sina
        Bank.POST_BANK -> R.drawable.ic_bank_post
        Bank.MASKAN -> R.drawable.ic_bank_maskan
        Bank.GARDESHGARI -> R.drawable.ic_bank_gardeshgari
        Bank.SARMAYEH -> R.drawable.ic_bank_sarmayeh
        Bank.KARAFARIN -> R.drawable.ic_bank_karafarin
        Bank.MEHR_IRAN -> R.drawable.ic_bank_mehr_iran
        Bank.TOSEE_TAAVON -> R.drawable.ic_bank_tosee_taavon
        Bank.IRAN_ZAMIN -> R.drawable.ic_bank_iran_zamin
        Bank.SANAT_MADAN -> R.drawable.ic_bank_sanat_madan
        Bank.OTHER -> null
    }

    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(
                if (logo != null) Color.White else MaterialTheme.colorScheme.surfaceVariant,
            )
            // The name is right beside it in text; a second reading of it is noise.
            .clearAndSetSemantics { },
        contentAlignment = Alignment.Center,
    ) {
        if (logo != null) {
            Image(
                painter = painterResource(logo),
                contentDescription = null,
                // The inset scales with the plate: a fixed 4dp that reads as breathing room at
                // 32dp is a hairline at 44dp, and the mark then touches the circle's edge.
                modifier = Modifier
                    .fillMaxSize()
                    .padding(size / 8),
            )
        } else {
            Text("💳", fontSize = (size.value / 2).sp)
        }
    }
}

@Composable
internal fun WalletNetworkLogo(network: String, size: Dp = 28.dp) {
    val logo = when (network.lowercase()) {
        "bitcoin" -> R.drawable.ic_network_bitcoin
        "ethereum" -> R.drawable.ic_network_ethereum
        "solana" -> R.drawable.ic_network_solana
        "tron" -> R.drawable.ic_network_tron
        "bsc" -> R.drawable.ic_network_bsc
        "arbitrum" -> R.drawable.ic_network_arbitrum
        "polygon" -> R.drawable.ic_network_polygon
        "optimism" -> R.drawable.ic_network_optimism
        "avalanche" -> R.drawable.ic_network_avalanche
        else -> null
    }

    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .clearAndSetSemantics { },
        contentAlignment = Alignment.Center,
    ) {
        if (logo != null) {
            Image(
                painter = painterResource(logo),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    network.take(2).uppercase(),
                    // Scaled, not fixed: at badge size two letters at 10sp are wider than the
                    // disc they sit in, and the fallback clipped instead of naming the chain.
                    fontSize = (size.value * 0.36f).sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
