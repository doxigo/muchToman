package com.doxigo.muchtoman

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Groups the integer part as she types and shows Persian digits, caret intact. */
private val GroupedNumber = VisualTransformation { text ->
    val g = groupDigits(text.text)
    TransformedText(
        AnnotatedString(g.text),
        object : OffsetMapping {
            override fun originalToTransformed(offset: Int) =
                g.origToDisp[offset.coerceIn(0, g.origToDisp.lastIndex)]

            override fun transformedToOriginal(offset: Int) =
                g.dispToOrig[offset.coerceIn(0, g.dispToOrig.lastIndex)]
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRoot(vm: AppVm, activity: FragmentActivity) {
    val state by vm.state.collectAsState()
    var adding by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<String?>(null) }
    var settings by remember { mutableStateOf(false) }
    var report by remember { mutableStateOf(false) }
    var banks by remember { mutableStateOf(false) }

    // Leaving the app re-arms the lock, so coming back asks again — and coming back also
    // refetches rates if the ones on screen are older than the Worker's cache window.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> vm.relock()
                Lifecycle.Event.ON_START -> vm.refreshIfStale()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    if (state.locked) {
        // Prompt immediately; the button is there for when it is dismissed.
        LaunchedEffect(Unit) { promptUnlock(activity) { vm.unlock() } }
        Surface(color = MaterialTheme.colorScheme.background) {
            LockScreen(onUnlock = { promptUnlock(activity) { vm.unlock() } })
        }
        return
    }

    if (settings) {
        // The system back button must step back to the list, not out of the app.
        BackHandler { settings = false }
        SettingsScreen(
            name = state.name,
            themeMode = state.themeMode,
            lockEnabled = state.lockEnabled,
            smsEnabled = state.smsEnabled,
            bankAccounts = state.bankAccounts,
            disabledBanks = state.disabledBanks,
            activity = activity,
            onNameChange = vm::setName,
            onThemeChange = vm::setThemeMode,
            onSmsChange = vm::setSmsEnabled,
            onBankChange = vm::setBankEnabled,
            onLockChange = { on ->
                // Confirm identity before arming it, so the lock is never switched on by
                // someone who could not then get back in.
                if (on) promptUnlock(activity) { vm.setLockEnabled(true) }
                else vm.setLockEnabled(false)
            },
            onBack = { settings = false },
        )
        return
    }

    if (report) {
        BackHandler { report = false }
        ReportScreen(
            history = state.history,
            current = state.totals.toman,
            onBack = { report = false },
        )
        return
    }

    // Merging 250 coin rates with the overrides is not free, and it is the same map for every
    // row and every section head on screen. Read it once per pass, not once per card.
    val effective = state.effective

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            Button(
                onClick = { adding = true },
                shape = RoundedCornerShape(Radius.field),
                modifier = Modifier
                    .fillMaxWidth()
                    // A bare Button in bottomBar does not consume the navigation-bar inset
                    // the way Material's own bars do; without this it sits under the pill /
                    // 3-button bar on real phones.
                    .navigationBarsPadding()
                    .padding(horizontal = Space.xl, vertical = Space.m)
                    .height(62.dp),
            ) {
                Text("＋  افزودن دارایی", fontSize = 19.sp, fontWeight = FontWeight.Bold)
            }
        },
    ) { pad ->
        // Pulling the list down refetches, exactly like the به‌روزرسانی button — the button
        // stays, because a labelled control is still the one she can be told about by phone.
        PullToRefreshBox(
            isRefreshing = state.loading,
            onRefresh = vm::refresh,
            modifier = Modifier
                .padding(pad)
                .fillMaxSize(),
        ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = Space.xl, end = Space.xl, top = Space.l, bottom = Space.s,
            ),
            verticalArrangement = Arrangement.spacedBy(Space.m),
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        // The name turns the header into a greeting; without one the app
                        // just says what it is.
                        if (state.name.isNotBlank()) "سلام، ${state.name}" else "چقدر تومن",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { report = true }) {
                        BarsIcon(MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { settings = true }) {
                        Icon(
                            Icons.Filled.Settings,
                            contentDescription = "تنظیمات",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            item { TotalCard(state, effective["usd"], vm::refresh) }

            if (state.listHoldings.isEmpty()) {
                item { EmptyHint() }
            } else {
                // Dollars, gold, coins and crypto used to arrive as one undifferentiated stack
                // of cards. Banding them by kind separates them without moving anything: the
                // sections come out in the order the holdings are already stored in.
                val sections = holdingsByKind(state.listHoldings, state.coins)
                sections.forEach { (kind, held) ->
                    // A single section is not a separation, and its subtotal would only be the
                    // hero total said twice.
                    if (sections.size > 1) {
                        item(key = "kind_${kind.name}") {
                            // The same function the hero total uses, so the sections always add
                            // up to it: set-aside and unpriced holdings fall out of both alike,
                            // and no section can show money the total above it does not have.
                            val sum = computeTotals(held, effective)
                            // …which is also why the figure counts contributors and not rows.
                            // A sum of one is not a sum, it is the card below it said twice —
                            // and one live row beside a set-aside one is still a sum of one.
                            val counted = held.count { !it.excluded } - sum.missing.size
                            SectionHead(
                                title = kind.fa,
                                subtotal = sum.toman.takeIf { counted > 1 },
                                modifier = Modifier.animateItem(),
                            )
                        }
                    }
                    items(held, key = { it.typeId }) { h ->
                        HoldingRow(
                            type = resolveType(h.typeId, state.coins),
                            amount = h.amount,
                            rate = effective[h.typeId],
                            excluded = h.excluded,
                            // The bank row is not hers to edit — its amount comes from the
                            // messages — so it opens the accounts behind it instead.
                            onClick = { if (h.typeId == BANK_ID) banks = true else editing = h.typeId },
                            note = if (h.typeId == BANK_ID) bankNote(state) else null,
                            modifier = Modifier.animateItem(),
                        )
                    }
                }
            }

            if (state.totals.missing.isNotEmpty()) {
                item { MissingNote(state.totals.missing, state.coins) }
            }
        }
        }
    }

    if (banks) {
        BankSheet(
            accounts = state.bankAccounts,
            disabled = state.disabledBanks,
            strangers = state.strangeSenders,
            onAnchor = vm::setBankBalance,
            onForget = vm::forgetBankAccount,
            onAddNumber = vm::addBankNumber,
            onDismissSender = vm::dismissSender,
            onRescan = vm::rescanSms,
            onDismiss = { banks = false },
        )
    }

    if (adding) {
        PickTypeSheet(
            all = catalog(state.coins),
            already = state.holdings.map { it.typeId }.toSet(),
            onDismiss = { adding = false },
            onPick = { adding = false; editing = it },
        )
    }

    editing?.let { typeId ->
        val held = state.holdings.firstOrNull { it.typeId == typeId }
        EditSheet(
            type = resolveType(typeId, state.coins),
            current = held?.amount,
            rate = effective[typeId],
            isOverridden = state.overrides.containsKey(typeId),
            excluded = held?.excluded ?: false,
            onExcluded = { on -> vm.setExcluded(typeId, on) },
            onSave = { amount -> vm.setHolding(typeId, amount); editing = null },
            onDelete = { vm.removeHolding(typeId); editing = null },
            onRate = { r -> vm.setOverride(typeId, r) },
            onDismiss = { editing = null },
        )
    }
}

/** The reason the app exists. Everything else on screen defers to it. */
@Composable
private fun TotalCard(state: UiState, usdRate: Double?, onRefresh: () -> Unit) {
    val total = state.totals.toman

    // The same money in the one other unit everyone here already thinks in. It comes from the
    // *effective* dollar rate, so a hand-typed override moves this figure with everything else,
    // and it is simply absent when there is no dollar rate — a converted total is only ever as
    // honest as the rate underneath it.
    val usd = usdRate?.takeIf { it > 0.0 && total > 0.0 }?.let { total / it }

    // "همین الان" must not still say that half an hour later. A slow tick keeps the label
    // honest; the minute granularity of faAgo means nothing finer would ever show anyway.
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(state.rates.updatedAt) {
        while (true) {
            now = System.currentTimeMillis()
            delay(30_000)
        }
    }
    // A day-old rate silently shown as current is the "quietly wrong total" this app is
    // built to avoid. Words carry the warning, not colour alone.
    val stale = state.rates.updatedAt > 0L && now - state.rates.updatedAt > 24 * 60 * 60_000L

    Card(
        shape = RoundedCornerShape(Radius.hero),
        colors = CardDefaults.cardColors(containerColor = HeroBg, contentColor = HeroMuted),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = Space.xxl - Space.xs, vertical = Space.xxl - Space.xs)) {
            // Label and dollar figure share the top line, so the corner reads as one sentence
            // — "مجموع دارایی شما ≈ $۵۱٬۵۰۰" — and the dollars stay an aside rather than a
            // second headline. Muted, one step down in size: the gold figure below is still
            // the answer, this only says it again in another unit.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "مجموع دارایی شما",
                    fontSize = 15.sp,
                    color = HeroMuted,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
                usd?.let {
                    // The isolate keeps the number one opaque run; the "$" sits outside it so
                    // bidi puts it on the reading side of the figure whether faRate returns
                    // digits ("$۵۱٬۵۰۰") or a magnitude ("$۱٫۲ میلیون").
                    Text(
                        "≈ \$${bidi(faRate(it))}",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = HeroMuted,
                        maxLines = 1,
                        // "$" is read out as punctuation or skipped entirely; the unit has to
                        // survive for anyone listening rather than looking.
                        modifier = Modifier
                            .padding(start = Space.s)
                            .semantics { contentDescription = "حدود ${faRate(it)} دلار" },
                    )
                }
            }
            Spacer(Modifier.height(Space.m))
            // Must never wrap: "۳٫۲ میلیارد تومان" broken across two lines reads as a layout
            // bug, and the figure can grow by orders of magnitude. Shrink to fit instead.
            // The fade on change is the visible receipt that a refresh actually did something.
            // Three decimals here, one everywhere else: the headline is the one figure she
            // watches move, so "۹٫۶۴۳ میلیارد" beats a ۹٫۶ that hides a day's change.
            AnimatedContent(targetState = "${faCompact(total, 3, pad = true)} تومان", label = "total") { figure ->
                BasicText(
                    text = figure,
                    maxLines = 1,
                    autoSize = TextAutoSize.StepBased(minFontSize = 24.sp, maxFontSize = 44.sp),
                    style = TextStyle(
                        fontFamily = Vazir,
                        fontWeight = FontWeight.Bold,
                        color = HeroAccent,
                    ),
                )
            }
            // Digits are quick to scan but easy to misread by a factor of ten. The words
            // are the check on that.
            faWordsToman(total)?.let { words ->
                Text(
                    words,
                    fontSize = 15.sp,
                    lineHeight = 24.sp,
                    color = Color.White.copy(alpha = 0.92f),
                    modifier = Modifier.padding(top = Space.s),
                )
            }
            Text(
                "${faNumber(total)} تومان",
                fontSize = 13.sp,
                color = HeroMuted,
                modifier = Modifier.padding(top = Space.xs),
            )

            Spacer(Modifier.height(Space.xl))
            HorizontalDivider(color = HeroMuted.copy(alpha = 0.22f))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = Space.xs),
            ) {
                Text(
                    when {
                        state.error != null && state.rates.updatedAt == 0L -> "نرخ‌ها دریافت نشد"
                        stale -> "⚠️ نرخ‌ها قدیمی است: " + faAgo(state.rates.updatedAt, now)
                        else -> "نرخ‌ها: " + faAgo(state.rates.updatedAt, now)
                    },
                    fontSize = 14.sp,
                    color = if (stale) HeroAccent else HeroMuted,
                    modifier = Modifier.weight(1f),
                )
                Crossfade(targetState = state.loading, label = "refresh") { loading ->
                    if (loading) {
                        Box(Modifier.height(40.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                                color = HeroAccent,
                            )
                        }
                    } else {
                        TextButton(
                            onClick = onRefresh,
                            colors = ButtonDefaults.textButtonColors(contentColor = HeroAccent),
                        ) { Text("به‌روزرسانی", fontSize = 15.sp, fontWeight = FontWeight.Bold) }
                    }
                }
            }
            if (state.error != null && state.rates.updatedAt > 0L) {
                Text("اتصال برقرار نشد؛ نرخ‌های قبلی نمایش داده می‌شود.", fontSize = 13.sp, color = HeroMuted)
            }
        }
    }
}

/**
 * Three rising bars for the report button — drawn, because the icon set material3 already
 * ships has no chart glyph, and one emoji in a row of monochrome icons reads as a sticker.
 */
@Composable
private fun BarsIcon(tint: Color) {
    Canvas(
        Modifier
            .size(22.dp)
            .semantics { contentDescription = "گزارش دارایی" },
    ) {
        val w = size.width * 0.2f
        val r = CornerRadius(w / 2f)
        drawRoundRect(tint, Offset(0f, size.height * 0.45f), Size(w, size.height * 0.55f), r)
        drawRoundRect(tint, Offset((size.width - w) / 2f, size.height * 0.2f), Size(w, size.height * 0.8f), r)
        drawRoundRect(tint, Offset(size.width - w, 0f), Size(w, size.height), r)
    }
}

/**
 * A real logo where one exists, an emoji for the fixed assets, and the ticker as a last
 * resort. The letters sit underneath and are covered once the logo loads, so a slow network
 * shows an identifiable badge rather than a hole.
 */
@Composable
private fun AssetIcon(type: AssetType, size: Dp = 44.dp) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        val letters = @Composable {
            Text(
                type.id.take(4).uppercase(),
                fontSize = if (type.id.length > 3) 9.sp else 11.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        when {
            // The real currency mark for the one asset that IS the currency — an emoji bank
            // was always a stand-in.
            type.id == TOMAN_ID -> Icon(
                painterResource(R.drawable.ic_toman),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(size * 0.52f),
            )
            type.iconUrl != null -> {
                var loaded by remember(type.iconUrl) { mutableStateOf(false) }
                if (!loaded) letters()
                AsyncImage(
                    model = type.iconUrl,
                    contentDescription = null,
                    modifier = Modifier.size(size * 0.62f),
                    onState = { loaded = it is AsyncImagePainter.State.Success },
                )
            }
            type.emoji != null -> Text(type.emoji, fontSize = (size.value * 0.52f).sp)
            else -> letters()
        }
    }
}

/**
 * The bank row's second line. It names how many accounts are behind the figure and, when any
 * of them is still a guess, says so there rather than only inside the sheet — a number she has
 * to open something to distrust is a number she will trust.
 */
private fun bankNote(state: UiState): String {
    val live = state.bankAccounts.filterNot { it.bank in state.disabledBanks }
    val off = state.bankAccounts.size - live.size
    // Just "۴ حساب": the row is already titled حساب‌های بانکی, and the longer wording pushed
    // the warning off the end of a single line, which is the half it cannot afford to lose.
    val counted = "${faNumber(live.size.toDouble())} حساب"
    return when {
        live.any { !it.trusted } -> "$counted  ·  نیاز به بررسی"
        off > 0 -> "$counted  ·  ${faNumber(off.toDouble())} خاموش"
        else -> counted
    }
}

/**
 * Every tracked account, one line each, with the raw figure and when it last moved. This is
 * the audit surface: a balance read out of a message is only safe to act on if the message it
 * came from can be found and the number corrected, so nothing here is hidden behind a summary.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BankSheet(
    accounts: List<BankAccount>,
    disabled: Set<String>,
    strangers: List<StrangeSender>,
    onAnchor: (String, Double) -> Unit,
    onForget: (String) -> Unit,
    onAddNumber: (String, String) -> Unit,
    onDismissSender: (String) -> Unit,
    onRescan: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    var fixing by remember { mutableStateOf<String?>(null) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = Radius.sheet, topEnd = Radius.sheet),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            Modifier
                .fillMaxHeight(0.88f)
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = Space.xl),
        ) {
            Text("حساب‌های بانکی", fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text(
                "این مبلغ‌ها از پیامک بانک‌ها خوانده می‌شود. برای روشن و خاموش کردن هر بانک، به تنظیمات بروید.",
                fontSize = 13.sp,
                color = muted,
                lineHeight = 20.sp,
                modifier = Modifier.padding(top = Space.xs),
            )

            LazyColumn(
                contentPadding = PaddingValues(top = Space.l, bottom = Space.xxl),
                verticalArrangement = Arrangement.spacedBy(Space.s),
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
                items(accounts, key = { it.key }) { acc ->
                    BankAccountRow(
                        account = acc,
                        off = acc.bank in disabled,
                        now = now,
                        fixing = fixing == acc.key,
                        onFix = { fixing = if (fixing == acc.key) null else acc.key },
                        onAnchor = { v -> onAnchor(acc.key, v); fixing = null },
                        onForget = { onForget(acc.key) },
                    )
                }

                // A message that names one of her banks, arrived from a number the list does
                // not have — the one silence worth breaking, since without it a bank that adds
                // a shortcode simply freezes and looks like a reading bug. Her tap is what adds
                // the number; the message's own text only ever suggests.
                items(strangers, key = { "s_${it.sender}" }) { stranger ->
                    val bankFa = runCatching { Bank.valueOf(stranger.bank) }
                        .getOrDefault(Bank.OTHER).fa
                    Card(
                        shape = RoundedCornerShape(Radius.card),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(Modifier.padding(Space.l)) {
                            Text(
                                "پیامکی مانند پیامک‌های $bankFa از شماره‌ای آمده که در فهرست نیست:",
                                fontSize = 13.sp,
                                lineHeight = 20.sp,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                            Text(
                                bidi(stranger.sender),
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.padding(top = Space.xs),
                            )
                            Text(
                                "«${stranger.snippet}»",
                                fontSize = 12.sp,
                                lineHeight = 19.sp,
                                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.75f),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = Space.xs),
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                TextButton(
                                    onClick = { onAddNumber(stranger.bank, stranger.sender) },
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Text(
                                        "این شمارهٔ $bankFa است — افزودن",
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                // Two taps, as for deleting a holding. This sits right beside
                                // the confirm button and there is no undo: a mistap here
                                // silences that bank for good.
                                var sure by remember(stranger.sender) { mutableStateOf(false) }
                                TextButton(onClick = {
                                    if (sure) onDismissSender(stranger.sender) else sure = true
                                }) {
                                    Text(
                                        if (sure) "مطمئن هستید؟" else "نادیده گرفتن",
                                        fontSize = 14.sp,
                                        fontWeight = if (sure) FontWeight.Bold else FontWeight.Normal,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.75f),
                                    )
                                }
                            }
                        }
                    }
                }

                // A message is read once and skipped ever after, so a balance that has gone
                // wrong stays wrong on its own. This is the way back: forget the lot and read
                // her messages again from the start.
                item {
                    var sure by remember { mutableStateOf(false) }
                    TextButton(
                        onClick = { if (sure) { onRescan(); sure = false } else sure = true },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = Space.l),
                    ) {
                        Text(
                            if (sure) "همهٔ مبلغ‌ها دوباره از پیامک‌ها خوانده شود؟ دوباره بزنید"
                            else "خواندن دوبارهٔ همهٔ پیامک‌ها",
                            fontSize = 14.sp,
                            fontWeight = if (sure) FontWeight.Bold else FontWeight.Normal,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BankAccountRow(
    account: BankAccount,
    off: Boolean,
    now: Long,
    fixing: Boolean,
    onFix: () -> Unit,
    onAnchor: (Double) -> Unit,
    onForget: () -> Unit,
) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    var draft by remember(account.key) { mutableStateOf("") }

    Card(
        shape = RoundedCornerShape(Radius.card),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(Space.l).alpha(if (off) 0.55f else 1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    RowTitle(
                        account.bankFa,
                        color = MaterialTheme.colorScheme.onSurface,
                        max = 17.sp,
                    )
                    Text(
                        listOfNotNull(
                            account.mask.takeIf { it.isNotBlank() }?.let { bidi(it) },
                            faAgo(account.updatedAt, now),
                            "خاموش".takeIf { off },
                        ).joinToString("  ·  "),
                        fontSize = 12.sp,
                        color = muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                Column(horizontalAlignment = Alignment.Start) {
                    RowAmount(account.balance, color = MaterialTheme.colorScheme.onSurface)
                    Text("تومان", fontSize = 11.sp, color = muted)
                }
            }

            // The whole point of the flag: say what is uncertain and offer the one thing that
            // fixes it, rather than leaving a number on screen that quietly might be wrong.
            if (!account.trusted) {
                Text(
                    if (!account.anchored)
                        "این عدد فقط جمع تراکنش‌هاست، نه موجودی واقعی، و در مجموع حساب نشده " +
                            "است. مانده‌ی حساب را وارد کنید تا شمرده شود."
                    else "این عدد از پیامکی خوانده شده که مطمئن نبودیم. اگر درست نیست، اصلاحش کنید.",
                    fontSize = 12.sp,
                    lineHeight = 19.sp,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = Space.s),
                )
            }

            if (fixing) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    singleLine = true,
                    visualTransformation = GroupedNumber,
                    placeholder = { Text("مانده به تومان", fontSize = 14.sp) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    shape = RoundedCornerShape(Radius.field),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = Space.s),
                )
            }

            Row(Modifier.padding(top = Space.xs)) {
                if (fixing) {
                    TextButton(
                        onClick = { parseAmount(draft)?.let(onAnchor) },
                        enabled = parseAmount(draft) != null,
                    ) { Text("ثبت مانده", fontSize = 14.sp) }
                } else {
                    TextButton(onClick = onFix) { Text("اصلاح مانده", fontSize = 14.sp) }
                }
                Spacer(Modifier.weight(1f))
                TextButton(
                    onClick = onForget,
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) { Text("حذف این حساب", fontSize = 14.sp) }
            }
        }
    }
}

/**
 * The band title for one kind, carrying that kind's own total — the question the flat list
 * could never answer: how much of this is dollars, how much is crypto.
 *
 * Type and air do the separating. No rule, no badge, no colour: the heads have to read as
 * quieter than the cards they introduce, or the list gains structure and loses its subject.
 * Same size, weight and muted colour as the picker's headings, so the two lists agree about
 * what a kind looks like; the number takes Medium so it reads as the payload without
 * competing with the label for the eye.
 */
@Composable
private fun SectionHead(title: String, subtotal: Double?, modifier: Modifier = Modifier) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier
            .fillMaxWidth()
            // A heading belongs to what follows it — more air above than below, as in the
            // picker. The 4dp inset is optical: flush against a 22dp corner radius, the label
            // reads as hanging outside the cards it heads.
            .padding(top = Space.l, bottom = 0.dp, start = Space.xs, end = Space.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            color = muted,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        subtotal?.let {
            Text(
                // Three decimals, like the rows it sits above: a header reading ۱٫۴ over rows
                // that add up to ۱٫۴۰۹ looks like one of the two is wrong.
                "${faCompact(it, 3, pad = true)} تومان",
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = muted,
                maxLines = 1,
            )
        }
    }
}

/**
 * A row's value in Toman, at the same three decimals as the hero total — the same money
 * should not read as "۹٫۷ میلیارد" in one place and "۹٫۷۴۳ میلیارد" in the other.
 *
 * Shrink-to-fit like the hero, and bounded: three decimals make the figure noticeably wider,
 * and the asset's name (which shares the row) must not be the thing that gets ellipsised for
 * it on a narrow phone.
 */
/**
 * A row's title. Gives up a point or two of size before it gives up letters: the name of the
 * thing is what the row is for, and "حساب‌های بان…" is worse to read than the same words a
 * little smaller. Ellipsis stays as the last resort, below which shrinking would not help.
 */
@Composable
private fun RowTitle(text: String, color: Color, max: TextUnit = 18.sp) {
    BasicText(
        text = text,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        autoSize = TextAutoSize.StepBased(minFontSize = 13.sp, maxFontSize = max),
        style = TextStyle(fontFamily = Vazir, fontWeight = FontWeight.Bold, color = color),
    )
}

@Composable
private fun RowAmount(toman: Double, color: Color, struck: Boolean = false) {
    BasicText(
        text = faCompact(toman, 3, pad = true),
        maxLines = 1,
        autoSize = TextAutoSize.StepBased(minFontSize = 13.sp, maxFontSize = 18.sp),
        // Bounded so the figure, not the asset's name, is what gives way on a narrow phone:
        // "۵۵۹٫۵۰۰ میلیون" is half again as wide as "۵۵۹٫۵ میلیون" was.
        modifier = Modifier.widthIn(max = 116.dp),
        style = TextStyle(
            fontFamily = Vazir,
            fontWeight = FontWeight.Bold,
            color = color,
            textDecoration = if (struck) TextDecoration.LineThrough else null,
        ),
    )
}

@Composable
private fun HoldingRow(
    type: AssetType,
    amount: Double,
    rate: Double?,
    excluded: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    /** Replaces the amount-and-rate line, for a row whose amount did not come from her. */
    note: String? = null,
) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant

    // No gesture here: setting an asset aside lives behind the row's edit sheet, as a
    // labelled switch. Swipes proved too easy to trigger scrolling the list and too hard
    // to discover on purpose.
    Card(
        shape = RoundedCornerShape(Radius.card),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.card))
            .clickable(onClick = onClick),
    ) {
        Row(
            Modifier
                .padding(horizontal = Space.l, vertical = Space.l - Space.xs)
                .alpha(if (excluded) 0.55f else 1f),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AssetIcon(type)
            Spacer(Modifier.width(Space.m))

            Column(Modifier.weight(1f)) {
                RowTitle(type.fa, color = MaterialTheme.colorScheme.onSurface)
                val held = "${faDecimal(amount, type.dec)} ${bidi(type.unitFa)}"
                Text(
                    // "نرخ" rather than "هر <unit>": one less latin run, and short enough
                    // that a long ticker cannot push the line onto a second row.
                    when {
                        note != null -> note
                        // Says how the figure got here, which is the whole difference between
                        // this row and the bank one right below it.
                        type.id == TOMAN_ID -> "دستی وارد می‌شود"
                        rate == null -> held
                        else -> "$held  ·  نرخ ${faRate(rate)}"
                    },
                    fontSize = 13.sp,
                    color = muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }

            Spacer(Modifier.width(Space.s))
            Column(horizontalAlignment = Alignment.Start) {
                when {
                    excluded -> {
                        if (rate != null) {
                            RowAmount(amount * rate, color = muted, struck = true)
                        }
                        Text("حساب نشده", fontSize = 12.sp, color = muted)
                    }
                    rate == null ->
                        Text("نرخ ندارد", fontSize = 14.sp, color = MaterialTheme.colorScheme.error)
                    else -> {
                        RowAmount(amount * rate, color = MaterialTheme.colorScheme.onSurface)
                        Text("تومان", fontSize = 12.sp, color = muted)
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyHint() {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 52.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("👛", fontSize = 60.sp)
        Spacer(Modifier.height(Space.l))
        Text("هنوز چیزی اضافه نکرده‌اید", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(Space.s))
        Text(
            "با دکمهٔ پایین، پول نقد یا دلار یا طلا و هر دارایی دیگری را اضافه کنید.",
            fontSize = 15.sp,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun MissingNote(missing: List<String>, coins: List<Coin>) {
    val names = missing.map { resolveType(it, coins).fa }.distinct().joinToString("، ")
    Text(
        "نرخ $names در دسترس نیست و در مجموع حساب نشده است. می‌توانید نرخ را دستی وارد کنید.",
        fontSize = 13.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = Space.xs, vertical = Space.s),
    )
}

/**
 * A drag-to-dismiss sheet rather than a dialog: the list is long, the phone is held one
 * handed, and the controls belong near the thumb.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PickTypeSheet(
    all: List<AssetType>,
    already: Set<String>,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val q = query.trim()

    val sections: List<Pair<String?, List<AssetType>>> = remember(q, all) {
        if (q.isNotEmpty()) {
            // Persian name, latin name or ticker — matchesSearch owns which of them count.
            listOf<Pair<String?, List<AssetType>>>(null to all.filter { matchesSearch(it, q) })
        } else {
            // Capped, or 250 tickers bury the assets she actually owns.
            Kind.entries.map { kind ->
                val items = all.filter { it.kind == kind }
                kind.fa to if (kind == Kind.CRYPTO) items.take(12) else items
            }
        }
    }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    // Play the sheet out before removing it from composition, or the selection just blinks.
    fun close(then: () -> Unit) = scope.launch { sheetState.hide(); then() }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = Radius.sheet, topEnd = Radius.sheet),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            // A bounded height is what makes the drag smooth: with fillMaxSize on the list
            // the sheet grows to the whole screen, loses its corners and scrim, and every
            // drag re-measures the content.
            Modifier
                .fillMaxHeight(0.88f)
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = Space.xl)
        ) {
            Text("چه چیزی اضافه می‌کنید؟", fontSize = 22.sp, fontWeight = FontWeight.Bold)

            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                placeholder = { Text("جستجو…", fontSize = 15.sp) },
                shape = RoundedCornerShape(Radius.field),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = Space.m, bottom = Space.xs),
            )

            LazyColumn(
                contentPadding = PaddingValues(bottom = Space.xxl),
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
                if (q.isNotEmpty() && sections.all { it.second.isEmpty() }) {
                    item {
                        Text(
                            "چیزی پیدا نشد.",
                            fontSize = 16.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = Space.xxl),
                        )
                    }
                }

                sections.forEach { (title, items) ->
                    if (items.isEmpty()) return@forEach
                    if (title != null) {
                        item(key = "h_$title") {
                            Text(
                                title,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                // more air above a heading than below it
                                modifier = Modifier.padding(top = Space.xl, bottom = Space.xs),
                            )
                        }
                    }
                    items(items, key = { it.id }) { type ->
                        PickRow(type, type.id in already) { close { onPick(type.id) } }
                    }
                }

                if (q.isEmpty()) {
                    item {
                        Text(
                            "برای دیدن بقیهٔ رمزارزها، نام آن را جستجو کنید.",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = Space.l),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PickRow(type: AssetType, already: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.field))
            .clickable(onClick = onClick)
            .padding(horizontal = Space.s, vertical = Space.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AssetIcon(type, size = 40.dp)
        Spacer(Modifier.width(Space.m))
        Column(Modifier.weight(1f)) {
            Text(type.fa, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (type.kind == Kind.CRYPTO) {
                Text(
                    type.id.uppercase(),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (already) {
            Text(
                "افزوده شده",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditSheet(
    type: AssetType,
    current: Double?,
    rate: Double?,
    isOverridden: Boolean,
    excluded: Boolean,
    onExcluded: (Boolean) -> Unit,
    onSave: (Double) -> Unit,
    onDelete: () -> Unit,
    onRate: (Double?) -> Unit,
    onDismiss: () -> Unit,
) {
    // Raw digits live in state; grouping is purely visual so parsing stays exact.
    var text by remember { mutableStateOf(current?.let { trimNumber(it, type.dec) } ?: "") }
    var rateText by remember { mutableStateOf(rate?.let { trimNumber(it, 0) } ?: "") }
    var editingRate by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    val amount = parseAmount(text)
    val typedRate = parseAmount(rateText)?.takeIf { it > 0 }
    // While she is typing a manual rate, the "می‌شود …" preview follows what she types, so
    // the consequence of the override is visible before it is committed.
    val previewRate = if (editingRate) typedRate ?: rate else rate
    val muted = MaterialTheme.colorScheme.onSurfaceVariant

    // Adding is always followed by typing an amount, so bring the keyboard along. Editing
    // starts with reading, not typing — there the keyboard would only cover the numbers.
    val amountFocus = remember { FocusRequester() }
    if (current == null) {
        LaunchedEffect(Unit) {
            delay(250) // let the sheet finish sliding in before the IME starts moving it
            amountFocus.requestFocus()
        }
    }

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
                .padding(horizontal = Space.xl)
                .navigationBarsPadding()
                .imePadding(),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AssetIcon(type, size = 42.dp)
                Spacer(Modifier.width(Space.m))
                Text(
                    type.fa,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Spacer(Modifier.height(Space.xl))
            Text("چقدر ${bidi(type.unitFa)} دارید؟", fontSize = 15.sp, color = muted)
            Spacer(Modifier.height(Space.s))
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                isError = text.isNotBlank() && amount == null,
                visualTransformation = GroupedNumber,
                textStyle = TextStyle(
                    fontFamily = Vazir,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                ),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                shape = RoundedCornerShape(Radius.field),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(amountFocus),
            )

            // The figure she just typed, in words — the guard against an extra zero.
            if (amount != null && amount % 1.0 == 0.0 && amount >= 1000) {
                Text(
                    "${faWords(amount.toLong())} ${bidi(type.unitFa)}",
                    fontSize = 14.sp,
                    lineHeight = 22.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = Space.s, start = Space.xs, end = Space.xs),
                )
            }

            if (amount != null && previewRate != null && type.id != TOMAN_ID) {
                Spacer(Modifier.height(Space.m))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(Radius.field))
                        .background(MaterialTheme.colorScheme.primaryContainer)
                        .padding(Space.l),
                ) {
                    // Same figure the row shows, so it carries the same three decimals — and
                    // shrinks rather than wrapping, since it is one sentence.
                    BasicText(
                        text = "می‌شود ${faCompact(amount * previewRate, 3, pad = true)} تومان",
                        maxLines = 1,
                        autoSize = TextAutoSize.StepBased(minFontSize = 14.sp, maxFontSize = 18.sp),
                        style = TextStyle(
                            fontFamily = Vazir,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        ),
                    )
                }
            }

            if (type.id != TOMAN_ID) {
                Spacer(Modifier.height(Space.m))
                if (!editingRate) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (rate == null) "نرخ در دسترس نیست"
                            else "نرخ هر ${bidi(type.unitFa)}: ${faNumber(rate)} تومان" +
                                if (isOverridden) "  (دستی)" else "",
                            fontSize = 14.sp,
                            color = muted,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { editingRate = true }) {
                            Text("تغییر نرخ", fontSize = 14.sp)
                        }
                    }
                } else {
                    // She tapped "تغییر نرخ" to type a rate; typing must land in this field,
                    // not silently keep appending to the amount above.
                    val rateFocus = remember { FocusRequester() }
                    LaunchedEffect(Unit) { rateFocus.requestFocus() }
                    Text("نرخ هر ${bidi(type.unitFa)} به تومان", fontSize = 14.sp, color = muted)
                    Spacer(Modifier.height(Space.xs))
                    OutlinedTextField(
                        value = rateText,
                        onValueChange = { rateText = it },
                        singleLine = true,
                        visualTransformation = GroupedNumber,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        shape = RoundedCornerShape(Radius.field),
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(rateFocus),
                    )
                    Row {
                        TextButton(onClick = { onRate(typedRate); editingRate = false }) {
                            Text("ثبت نرخ", fontSize = 14.sp)
                        }
                        if (isOverridden) {
                            TextButton(onClick = {
                                onRate(null); rateText = ""; editingRate = false
                            }) { Text("نرخ خودکار", fontSize = 14.sp) }
                        }
                    }
                }
            }

            if (current != null) {
                Spacer(Modifier.height(Space.m))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("در مجموع حساب شود", fontSize = 15.sp)
                        Text(
                            // e.g. savings she does not want staring back from the total
                            "برای کنار گذاشتن موقت، خاموشش کنید.",
                            fontSize = 12.sp,
                            color = muted,
                        )
                    }
                    Switch(checked = !excluded, onCheckedChange = { on -> onExcluded(!on) })
                }
            }

            Spacer(Modifier.height(Space.xl))
            Button(
                onClick = { amount?.let { a -> close { onSave(a) } } },
                enabled = amount != null,
                shape = RoundedCornerShape(Radius.field),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(58.dp),
            ) { Text("ذخیره", fontSize = 18.sp, fontWeight = FontWeight.Bold) }

            if (current != null) {
                // One stray tap must not erase a holding: the first tap only changes the
                // label to a question, the second actually deletes.
                TextButton(
                    onClick = { if (confirmDelete) close(onDelete) else confirmDelete = true },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = Space.xs),
                ) {
                    Text(
                        if (confirmDelete) "مطمئن هستید؟ برای حذف دوباره بزنید"
                        else "حذف این دارایی",
                        fontSize = 15.sp,
                        fontWeight = if (confirmDelete) FontWeight.Bold else FontWeight.Normal,
                    )
                }
            }

            Spacer(Modifier.height(Space.l))
        }
    }
}
