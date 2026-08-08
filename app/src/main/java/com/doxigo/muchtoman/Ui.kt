package com.doxigo.muchtoman

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.inset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.decode.DataSource
import coil3.request.ImageRequest
import coil3.request.crossfade
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

/** Groups the integer part as she types and shows Persian digits, caret intact. */
internal val GroupedNumber = VisualTransformation { text ->
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

@Composable
internal fun Modifier.panel(): Modifier = this
    .fillMaxWidth()
    .clip(RoundedCornerShape(Radius.card))
    .background(MaterialTheme.colorScheme.surfaceVariant)
    .padding(Space.l)

@Composable
internal fun Panel(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.panel(), content = content)
}

/**
 * The one inset from the screen edge. The hero field ignores it on purpose — it is the only
 * thing in the app that runs to the glass — so every other item in the list applies it itself.
 */
private val edge = PaddingValues(horizontal = Space.xl)

/**
 * Where a row sits in its band. Rows in the middle are square so they read as one object with
 * its neighbours rather than as a card that happens to be adjacent to another card.
 */
internal fun bandShape(index: Int, count: Int): Shape {
    val r = Radius.group
    return when {
        count == 1 -> RoundedCornerShape(r)
        index == 0 -> RoundedCornerShape(topStart = r, topEnd = r)
        index == count - 1 -> RoundedCornerShape(bottomStart = r, bottomEnd = r)
        else -> RoundedCornerShape(0.dp)
    }
}

/**
 * What the edit sheet is open on: an existing row, by its [key], or — when she picked an asset
 * to add — a key nothing holds yet, which is what lets a second تتر be added beside the first
 * instead of opening it.
 */
private data class Editing(val typeId: String, val key: String)

/**
 * The marks she chose are put in scope once, here, rather than passed to every screen that draws
 * one — see [LocalCustomGlyphs] for why that is the shape of it.
 */
@Composable
fun AppRoot(vm: AppVm, state: UiState, activity: FragmentActivity) {
    CompositionLocalProvider(LocalCustomGlyphs provides state.ledger.marks) {
        AppScreens(vm, state, activity)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppScreens(vm: AppVm, state: UiState, activity: FragmentActivity) {
    var adding by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Editing?>(null) }
    var settings by remember { mutableStateOf(false) }
    // A page of تنظیمات, not a peer of it: `companion` is only ever read while `settings` is
    // true, so closing it lands back on the settings page she opened it from rather than on
    // whatever tab is underneath both.
    var companion by remember { mutableStateOf(false) }
    var banks by remember { mutableStateOf(false) }
    var deck by remember { mutableStateOf(false) }
    var transactionRef by rememberSaveable { mutableStateOf<String?>(null) }

    // One tab, not three booleans. Three could be true at once, and were: every screen was an
    // overlay with its own «بستن», so an overlay opened from an overlay left her somewhere the
    // only way out of was the system back button. A single value cannot represent that state.
    // First of [tabs], not Tab.HOME: in the lite edition there is no home to start on, and the
    // one tab it has is where it opens.
    var tab by rememberSaveable { mutableStateOf(tabs.first()) }
    val portfolio = tab == Tab.ASSETS

    LaunchedEffect(state.family.pendingPairing) {
        // Scanning the invite must still land on the join card, and that card is two taps deep
        // now. A pairing link can also arrive at the lite build — the intent filter is in the
        // shared manifest — and that edition has no household to join it to.
        if (!BuildConfig.LITE && state.family.pendingPairing != null) {
            settings = true
            companion = true
        }
    }

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

    // What is behind the status bar decides the colour of the clock and the signal icons, and
    // on this screen that changes as she scrolls: the field is dark in both themes, the paper
    // under it is not. Forcing light icons for the whole list put a white clock on cream once
    // the field scrolled away; following the theme put a black one on the field. So follow
    // whichever is actually up there. The report and settings pages start at the background
    // and simply follow the theme, as does the navigation bar, which never leaves it.
    val listState = rememberLazyListState()
    val heroUnderStatusBar by remember {
        derivedStateOf { listState.firstVisibleItemIndex == 0 }
    }
    // The tabs that start at the paper rather than at the field, plus every pushed screen.
    val paperUnderStatusBar = !state.locked && (
        settings || companion || deck || transactionRef != null ||
            tab == Tab.LEDGER || tab == Tab.GOALS || tab == Tab.REPORT ||
            !heroUnderStatusBar
        )
    val lightScheme = MaterialTheme.colorScheme.background.luminance() > 0.5f
    LaunchedEffect(paperUnderStatusBar, lightScheme) {
        WindowCompat.getInsetsController(activity.window, activity.window.decorView).apply {
            isAppearanceLightStatusBars = paperUnderStatusBar && lightScheme
            isAppearanceLightNavigationBars = lightScheme
        }
    }

    if (state.locked) {
        // Prompt immediately; the button is there for when it is dismissed.
        LaunchedEffect(Unit) { promptUnlock(activity) { vm.unlock() } }
        Surface(color = MaterialTheme.colorScheme.background) {
            LockScreen(onUnlock = { promptUnlock(activity) { vm.unlock() } })
        }
        return
    }

    if (companion) {
        // Back steps to تنظیمات, the page it was opened from — one level, like every other
        // pushed screen here. Only reachable with `settings` already true, so the page behind
        // it is always the one it belongs to.
        BackHandler { companion = false }
        CompanionScreen(
            state = state.family,
            suggestedName = state.name,
            onStart = vm::startFamily,
            onJoin = vm::joinFamily,
            onNameChange = vm::setFamilyName,
            onShareSmsChange = vm::setFamilySmsSharing,
            onInvite = vm::inviteDevice,
            onSync = vm::syncFamily,
            contributions = remember(state.ledger.entries) {
                state.ledger.entries.filterNot { it.duplicate }
                    .groupingBy { it.ownerMemberId }
                    .eachCount()
            },
            // No bar under this one any more: it is a pushed page, so it runs to the gesture
            // area and takes that inset itself, as the report and the settings page do.
            bottomInset = 0.dp,
            onBack = { companion = false },
        )
        return
    }

    if (settings) {
        // The system back button must step back to the list, not out of the app.
        BackHandler { settings = false }
        SettingsScreen(
            name = state.name,
            themeMode = state.themeMode,
            lockEnabled = state.lockEnabled,
            widgetLock = state.widgetLock,
            smsEnabled = state.smsEnabled,
            bankAccounts = state.bankAccounts,
            disabledBanks = state.disabledBanks,
            categories = state.ledger.categories,
            family = state.family,
            activity = activity,
            onCompanion = { companion = true },
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
            onWidgetLockChange = vm::setWidgetLock,
            onAddCategory = vm::addCategory,
            onRemoveCategory = vm::archiveCategory,
            onBack = { settings = false },
        )
        return
    }

    transactionRef?.let { ref ->
        state.ledger.entries.firstOrNull { it.txn.ref == ref }?.let { entry ->
            BackHandler { transactionRef = null }
            TransactionScreen(
                entry = entry,
                categories = state.ledger.categories,
                onCategorise = vm::categorise,
                onLoadSource = vm::sourceOf,
                onBack = { transactionRef = null },
                categoryUse = state.ledger.categoryUse,
            )
            return
        }
    }

    if (deck) {
        BackHandler { deck = false }
        ReviewDeck(
            ledger = state.ledger,
            onDecide = { entry, categoryId, always ->
                vm.categorise(entry, categoryId, always)
            },
            onWorthIt = vm::answerWorthIt,
            onDone = { deck = false },
        )
        return
    }

    // Anywhere but home, back goes home. This is the whole reason the bar exists: before it,
    // there was no way out of a screen except a «بستن» she had to find in the corner. The lite
    // edition is one tab, so it is already home and back means back, as it did in v1.0.4.
    if (tab != tabs.first()) BackHandler { tab = tabs.first() }

    // Merging 250 coin rates with the overrides is not free, and it is the same map for every
    // row and every section head on screen. Read it once per pass, not once per card.
    val effective = state.effective
    val dynamicTypes = remember(state.coins, state.stocks) {
        (state.coins.map { it.toAssetType() } + state.stocks.map { it.toAssetType() })
            .associateBy { it.id }
    }
    val holdingSections = remember(state.listHoldings, dynamicTypes) {
        holdingsByKind(state.listHoldings, dynamicTypes)
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        // The hero field runs off the top of the screen and under the status bar, so the
        // Scaffold must not hold the content down below it. Every child that needs the inset
        // takes it itself — the field with statusBarsPadding, the bar below with its own.
        contentWindowInsets = WindowInsets(0),
        bottomBar = {
            // A full-width «اضافه کردن دارایی» used to sit above this, and the + in the field
            // already opens the same picker on this tab. Two buttons for one action, one of
            // them a gold slab against a gold slug, and the bar is where she looks anyway.
            if (tabs.size > 1) {
                TabBar(
                    selected = tab,
                    ledgerBadge = state.ledger.review.size,
                    onSelect = { tab = it },
                )
            } else {
                // The lite edition has no bar, but the Scaffold's inset is the only thing keeping
                // the last holding clear of the gesture pill, so something still has to stand
                // there and be exactly that tall.
                Spacer(Modifier.fillMaxWidth().navigationBarsPadding())
            }
        },
    ) { pad ->
      // The tabs that are their own screens. They sit inside the Scaffold rather than
      // returning early, so the bar stays under them and there is always a way out.
      if (tab == Tab.LEDGER) {
        TimelineScreen(
            ledger = state.ledger,
            bottomInset = pad.calculateBottomPadding(),
            onReview = { deck = true },
            onOpen = { transactionRef = it.txn.ref },
        )
        return@Scaffold
      }
      if (tab == Tab.GOALS) {
        val summary = remember(state.ledger) {
            worthItSummary(state.ledger.entries, state.ledger.worthIt)
        }
        GoalsScreen(
            goals = state.ledger.goals,
            summary = summary,
            onAdd = vm::addGoal,
            onDelete = vm::deleteGoal,
            bottomInset = pad.calculateBottomPadding(),
        )
        return@Scaffold
      }
      if (tab == Tab.REPORT) {
        val composition = remember(state.listHoldings, state.coins, state.effective, state.stocks) {
            compositionByKind(state.listHoldings, state.coins, state.effective, state.stocks)
        }
        ReportScreen(
            history = state.history,
            current = state.totals.toman,
            composition = composition,
            story = state.story,
            bottomInset = pad.calculateBottomPadding(),
            // The bar is the way out wherever there is one. The lite edition has no bar and
            // still opens this from the گزارش button on its field, so there it keeps the
            // «برگشت» it had as an overlay — see [tabs].
            onBack = ({ tab = tabs.first() }).takeIf { tabs.size == 1 },
        )
        return@Scaffold
      }

      Box(Modifier.fillMaxSize()) {
        // Pulling the list down refetches, exactly like the به‌روزرسانی button — the button
        // stays, because a labelled control is still the one she can be told about by phone.
        // The pull indicator belongs to the pull alone: init and refreshIfStale() also set
        // loading, and ungated they dropped an uninvited spinner over the header on every
        // cold open. The card's own labelled spinner is the receipt for those fetches.
        var pulled by remember { mutableStateOf(false) }
        LaunchedEffect(state.refreshing) { if (!state.refreshing) pulled = false }
        val pullState = rememberPullToRefreshState()
        PullToRefreshBox(
            isRefreshing = state.refreshing && pulled,
            onRefresh = { pulled = true; vm.refreshAll() },
            state = pullState,
            // The default indicator is a light chip, and it lands on the dark green field.
            // Dressed in the field's own colours instead, and pushed clear of the status bar.
            indicator = {
                PullToRefreshDefaults.Indicator(
                    state = pullState,
                    isRefreshing = state.refreshing && pulled,
                    containerColor = Hero.top,
                    color = Hero.gold,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .statusBarsPadding(),
                )
            },
            modifier = Modifier.fillMaxSize(),
        ) {
        // No horizontal contentPadding and no vertical arrangement: the hero field is
        // full-bleed and the rows in a band have to sit flush against each other, so every
        // item owns its own inset. `edge` is the one place that number lives.
        //
        // The bar's height arrives as content padding rather than as padding on the list, which
        // is the whole difference between a bar the page stops above and one the page runs
        // under. Every other screen already did it this way; this one held its list up.
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = pad.calculateBottomPadding() + Space.l),
        ) {
            item(key = "hero") {
                HeroField(
                    state = state,
                    usdRate = effective["usd"],
                    onRefresh = vm::refreshAll,
                    onReport = { tab = Tab.REPORT },
                    onSettings = { settings = true },
                    onAdd = { adding = true; vm.refreshStocksForPicker() },
                    portfolio = portfolio,
                )
            }

            // Under the total, not above it: the money is what she opened the app for, and an
            // update is never the more urgent of the two.
            state.update?.let { release ->
                item(key = "update") {
                    Box(Modifier.padding(edge).padding(top = Space.l)) {
                        UpdateNote(release, onLater = { vm.dismissUpdate(release.name) })
                    }
                }
            }

            if (!portfolio) {
                // The story, and then it stops. One meaningful change, the month's flow, the
                // runway, and at most one thing asking for her — the inventory is behind the
                // row at the bottom, because what she opened the app for is how things are
                // going, not a list of what she owns.
                val story = state.story
                // The month itself is on the field now. What is left down here is the one case
                // the field cannot carry: nothing recorded yet, which is not a status but a
                // thing to do — switch the messages on — and a task does not belong on the hero.
                if (story.month.transactions == 0) {
                    item(key = "flow") {
                        Box(Modifier.padding(edge).padding(top = Space.l)) {
                            QuietStart(state.smsEnabled)
                        }
                    }
                }
                // Side by side, and each without its «why»: this is the glance surface, and the
                // sentence behind the sentence is on the report where there is room to read it.
                // A lone card of the two still takes the whole row.
                if (story.headline != null || story.attention != null) {
                    item(key = "story") {
                        Row(
                            Modifier
                                .padding(edge)
                                .padding(top = Space.m)
                                .height(IntrinsicSize.Min),
                            horizontalArrangement = Arrangement.spacedBy(Space.m),
                        ) {
                            story.headline?.let {
                                InsightCard(it, Modifier.weight(1f).fillMaxHeight())
                            }
                            story.attention?.let {
                                // This one leaves the screen, so it says where it goes.
                                InsightCard(
                                    it,
                                    Modifier.weight(1f).fillMaxHeight(),
                                    action = "دفتر رو باز کن",
                                    onAction = { tab = Tab.LEDGER },
                                )
                            }
                        }
                    }
                }
                if (state.totals.missing.isNotEmpty()) {
                    item(key = "missing") {
                        MissingNote(state.totals.missing, state.coins, state.stocks)
                    }
                }
            } else if (state.listHoldings.isEmpty()) {
                item { EmptyHint() }
            } else {
                // Dollars, gold, coins and crypto used to arrive as one undifferentiated stack
                // of cards. Banding them by kind separates them without moving anything: the
                // sections come out in the order the holdings are already stored in.
                val sections = holdingSections
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
                    } else {
                        item(key = "band_top") { Spacer(Modifier.height(Space.xl)) }
                    }
                    itemsIndexed(held, key = { _, h -> h.key }) { i, h ->
                        val type = resolveType(h.typeId, dynamicTypes)
                        HoldingRow(
                            type = type,
                            name = h.nameOr(type.fa),
                            amount = h.amount,
                            rate = effective[h.typeId],
                            excluded = h.excluded,
                            // The bank row is not hers to edit — its amount comes from the
                            // messages — so it opens the accounts behind it instead.
                            onClick = {
                                if (h.typeId == BANK_ID) banks = true
                                else editing = Editing(h.typeId, h.key)
                            },
                            note = if (h.typeId == BANK_ID) bankNote(state) else null,
                            wallet = h.wallet,
                            walletRefreshing = h.key in state.refreshingWallets,
                            walletError = state.walletErrors[h.key],
                            // A band is one object with rows inside it, not a stack of cards:
                            // only its outer corners are round, and the rows are divided by a
                            // hairline that starts where the text does.
                            shape = bandShape(i, held.size),
                            divided = i < held.size - 1,
                            modifier = Modifier.animateItem(),
                        )
                    }
                }
            }

            if (portfolio && state.totals.missing.isNotEmpty()) {
                item { MissingNote(state.totals.missing, state.coins, state.stocks) }
            }
        }
        }

        // Once the field has scrolled off, it is paper up there and the list's own headings
        // were sliding under the clock. A strip in the background colour, arriving exactly
        // when the status-bar icons flip, gives them something to sit on. It cannot simply
        // always be there: while the field is up top, this would be a pale band across it.
        val strip by animateFloatAsState(
            if (paperUnderStatusBar) 1f else 0f,
            animationSpec = tween(Motion.fast),
            label = "statusStrip",
        )
        Box(
            Modifier
                .fillMaxWidth()
                .windowInsetsTopHeight(WindowInsets.statusBars)
                .alpha(strip)
                .background(MaterialTheme.colorScheme.background),
        )
      }
    }

    if (banks) {
        BankSheet(
            accounts = state.bankAccounts,
            disabled = state.disabledBanks,
            strangers = state.strangeSenders,
            onAnchor = vm::setBankBalance,
            onToggle = vm::setBankEnabled,
            onForget = vm::forgetBankAccount,
            onAddNumber = vm::addBankNumber,
            onDismissSender = vm::dismissSender,
            onRescan = vm::rescanSms,
            onDismiss = { banks = false },
        )
    }

    if (adding) {
        val available = remember(state.coins, state.stocks) { catalog(state.coins, state.stocks) }
        PickTypeSheet(
            all = available,
            // How many she already holds, not merely whether — picking again adds a second one,
            // and "۲ تا اضافه شده" is the difference between that reading as allowed and as a
            // row she has already dealt with.
            already = state.holdings.groupingBy { it.typeId }.eachCount(),
            onDismiss = { adding = false },
            // A fresh key every time: picking تتر when she already holds some opens an empty
            // sheet for a second one, rather than her existing balance.
            onPick = { adding = false; editing = Editing(it, newHoldingId()) },
        )
    }

    editing?.let { (typeId, key) ->
        val held = state.holdings.firstOrNull { it.key == key }
        EditSheet(
            key = key,
            type = resolveType(typeId, dynamicTypes),
            holding = held,
            rate = effective[typeId],
            isOverridden = state.overrides.containsKey(typeId),
            excluded = held?.excluded ?: false,
            walletBusy = key in state.refreshingWallets,
            walletError = state.walletErrors[key],
            onWalletEdit = { vm.clearWalletError(key) },
            onExcluded = { on -> vm.setExcluded(key, on) },
            onSaveManual = { amount -> vm.setHolding(key, typeId, amount); editing = null },
            onSaveWallet = { option, address, onSuccess ->
                vm.connectWallet(key, typeId, option, address, onSuccess)
            },
            onDelete = { vm.removeHolding(key); editing = null },
            onRate = { r -> vm.setOverride(typeId, r) },
            onLabel = { name -> vm.setLabel(key, name) },
            onDismiss = { editing = null },
        )
    }
}

/**
 * The reason the app exists, and now the ground the app stands on rather than a card floating
 * on it: a deep green field that runs to both edges and off the top of the screen, with the
 * total set in gold across it. Everything below defers to it.
 */
@Composable
internal fun HeroPanel(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(bottomStart = Radius.hero, bottomEnd = Radius.hero))
            .background(Brush.verticalGradient(listOf(Hero.top, Hero.bottom))),
    ) {
        Canvas(Modifier.matchParentSize()) {
            drawRect(
                Brush.radialGradient(
                    colors = listOf(Hero.gold.copy(alpha = 0.13f), Color.Transparent),
                    center = Offset(size.width * 0.88f, size.height * -0.05f),
                    radius = size.width * 0.95f,
                ),
            )
        }
        Column(
            Modifier
                .statusBarsPadding()
                .padding(start = Space.xl, end = Space.xl, top = Space.s, bottom = Space.xl),
            content = content,
        )
    }
}

@Composable
internal fun HeroField(
    state: UiState,
    usdRate: Double?,
    onRefresh: () -> Unit,
    onReport: () -> Unit,
    onSettings: () -> Unit,
    onAdd: () -> Unit,
    /** Which screen this field is heading — the one thing that changes below the total. */
    portfolio: Boolean,
) {
    val total = state.totals.toman

    // The same money in the one other unit everyone here already thinks in. It comes from the
    // *effective* dollar rate, so a hand-typed override moves this figure with everything else,
    // and it is simply absent when there is no dollar rate — a converted total is only ever as
    // honest as the rate underneath it.
    val usd = usdRate?.takeIf { it > 0.0 && total > 0.0 }?.let { total / it }

    // "همین الان" must not still say that half an hour later. A slow tick keeps the label
    // honest; the minute granularity of faAgo means nothing finer would ever show anyway.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(state.rates.updatedAt) {
        while (true) {
            now = System.currentTimeMillis()
            delay(30_000)
        }
    }
    // A day-old rate silently shown as current is the "quietly wrong total" this app is
    // built to avoid. Words carry the warning, not colour alone.
    val stale = state.rates.updatedAt > 0L && now - state.rates.updatedAt > 24 * 60 * 60_000L

    // A month is the shortest window the daily snapshots can answer honestly, and the one she
    // is most likely to be asking about. Absent — not zeroed — until there is a snapshot that
    // old to compare against.
    val change = remember(state.history, total) {
        changeOver(state.history, System.currentTimeMillis() / DAY_MS, 30, total)
    }

    HeroPanel {
            HeroAccount(state.name, onSettings)

            Spacer(Modifier.height(Space.m))
            // Label and dollar figure share a line, so the top of the field reads as one
            // sentence — "مجموع دارایی شما ≈ $۵۱٬۵۰۰" — and the dollars stay an aside rather
            // than a second headline.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "جمع دارایی‌هات",
                    fontSize = 15.sp,
                    color = Hero.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                usd?.let {
                    // The isolate keeps the number one opaque run; the "$" sits outside it so
                    // bidi puts it on the reading side of the figure whether faRate returns
                    // digits ("$۵۱٬۵۰۰") or a magnitude ("$۱٫۲ میلیون").
                    Text(
                        "≈ \$${bidi(faRate(it))}",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = ModamFigures,
                        color = Hero.muted,
                        maxLines = 1,
                        // "$" is read out as punctuation or skipped entirely; the unit has to
                        // survive for anyone listening rather than looking.
                        modifier = Modifier
                            .padding(start = Space.s)
                            .semantics { contentDescription = "حدود ${faRate(it)} دلار" },
                    )
                }
            }

            Spacer(Modifier.height(Space.xs))
            // Must never wrap: "۳٫۲ میلیارد تومان" broken across two lines reads as a layout
            // bug, and the figure can grow by orders of magnitude. Shrink to fit instead.
            // Three decimals here, one everywhere else: the headline is the one figure she
            // watches move, so "۹٫۶۴۳ میلیارد" beats a ۹٫۶ that hides a day's change.
            //
            // Every refresh lifts the new figure into place from below. It is the visible
            // receipt that the fetch did something — and the reason the digits are tabular:
            // without that, one changed digit slides the whole number sideways as it lands.
            AnimatedContent(
                targetState = faCompact(total, 3, pad = true),
                transitionSpec = {
                    (
                        slideInVertically(tween(Motion.medium, easing = Motion.enter)) { it / 3 } +
                            fadeIn(tween(Motion.medium))
                        ).togetherWith(
                        slideOutVertically(tween(Motion.fast, easing = Motion.exit)) { -it / 3 } +
                            fadeOut(tween(Motion.fast))
                    )
                },
                label = "total",
            ) { figure ->
                BasicText(
                    text = heroFigure(figure, Hero.gold),
                    maxLines = 1,
                    autoSize = TextAutoSize.StepBased(minFontSize = 28.sp, maxFontSize = 60.sp),
                    style = figureStyle(Hero.gold, FontWeight.Black),
                )
            }

            // Digits are quick to scan but easy to misread by a factor of ten. The words
            // are the check on that.
            faWordsToman(total)?.let { words ->
                Text(
                    words,
                    fontSize = 15.sp,
                    lineHeight = 25.sp,
                    color = Hero.strong.copy(alpha = 0.93f),
                    modifier = Modifier.padding(top = Space.s),
                )
            }
            Text(
                "${faNumber(total)} تومان",
                fontSize = 13.sp,
                fontFamily = ModamFigures,
                color = Hero.muted,
                modifier = Modifier.padding(top = Space.xs),
            )

            // The month band is خانه's door to the report, so the field only needs a standalone
            // link when there is no band — a first month with nothing recorded in it yet.
            val band = !portfolio && state.story.month.transactions > 0
            when {
                change != null -> {
                    Spacer(Modifier.height(Space.m))
                    ChangePill(change, onClick = onReport)
                }
                // Before there are thirty days to compare against, the pill has nothing it can
                // honestly state. A door that only appears in the second month is a door nobody
                // finds, so the slot keeps its target and drops the figure.
                // No spacer: the link's own 48dp target already carries more air than the pill
                // needs, and stacked they left a hole under the total.
                !portfolio && !band -> ReportLink(onClick = onReport)
            }

            if (portfolio) {
                // Only real verbs here: اضافه کردن adds and تازه کردن refetches. On خانه every
                // one of these was navigation wearing a verb's icon — «اضافه کردن» went to this
                // very screen, and the tab bar was already going there — so there the band
                // carries the month instead. گزارش went the same way the moment the report took
                // a slot on the bar: a door two inches above another door to the same room.
                Spacer(Modifier.height(Space.xl))
                Row(Modifier.fillMaxWidth()) {
                    HeroAction(
                        label = "اضافه کردن",
                        icon = Icons.Rounded.Add,
                        onClick = onAdd,
                        modifier = Modifier.weight(1f),
                    )
                    // …except in the lite edition, which has no bar. There this is the report's
                    // only door, and [tabs] is what says so.
                    if (tabs.size == 1) {
                        HeroAction(
                            label = "گزارش",
                            onClick = onReport,
                            modifier = Modifier.weight(1f),
                        ) { BarsIcon(Hero.strong) }
                    }
                    HeroAction(
                        label = "تازه کردن",
                        icon = Icons.Rounded.Refresh,
                        spinning = state.refreshing,
                        enabled = !state.refreshing,
                        onClick = onRefresh,
                        modifier = Modifier.weight(1f),
                    )
                }
            } else if (band) {
                // What she has, and how the month is going, in one screen without scrolling.
                // This used to sit in a card below the fold, which meant the answer the home
                // screen exists to give arrived only if she thought to scroll for it.
                Spacer(Modifier.height(Space.l))
                HorizontalDivider(color = Hero.hairline)
                Spacer(Modifier.height(Space.l))
                HeroMonth(state.story, onOpen = onReport)
            }

            Spacer(Modifier.height(if (portfolio) Space.xl else Space.l))
            HorizontalDivider(color = Hero.hairline)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                // The control carries its own optical space, so the strip does not need the
                // full step above it when one is there.
                modifier = Modifier.padding(top = if (portfolio) Space.m else Space.xs),
            ) {
                val failed = state.error != null && state.rates.updatedAt == 0L
                // Colour confirms; the words carry it. A dot on its own would be the one
                // thing in this app that says "something is wrong" in hue alone.
                Box(
                    Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(
                            when {
                                failed -> Hero.warn
                                stale -> Hero.warn
                                else -> Hero.mint
                            },
                        ),
                )
                Spacer(Modifier.width(Space.s))
                Text(
                    when {
                        failed -> "نرخ‌ها به‌روز نشدن. اینترنتت رو چک کن"
                        stale -> "نرخ‌ها قدیمی‌ان؛ " + faAgo(state.rates.updatedAt, now)
                        else -> "نرخ‌ها: " + faAgo(state.rates.updatedAt, now)
                    },
                    fontSize = 13.sp,
                    // Not gold: inside this field gold is the answer and the action, and a
                    // warning wearing it read as a second call to act.
                    color = if (stale || failed) Hero.warn else Hero.muted,
                    modifier = Modifier.weight(1f),
                )
                // The circle that used to carry this is gone from خانه, and the strip is where
                // it belonged all along: this line is about how fresh the numbers are, and the
                // control that changes that answer now sits at the end of the sentence stating
                // it. Labelled, not a bare glyph — a control you can be told about over the
                // phone has to have a name.
                if (!portfolio) {
                    HeroRefresh(
                        spinning = state.refreshing,
                        onClick = onRefresh,
                    )
                }
            }
            if (state.error != null && state.rates.updatedAt > 0L) {
                Text(
                    "اتصال نشد. نرخ‌های قبلی نشون داده می‌شن.",
                    fontSize = 12.sp,
                    lineHeight = 19.sp,
                    color = Hero.muted,
                    modifier = Modifier.padding(top = Space.xs),
                )
            }
    }
}

/**
 * Her, and the door to تنظیمات — one object at the top of the field instead of two.
 *
 * تنظیمات is not on the tab bar and should not be: [Tab] is five money questions she asks daily,
 * تنظیمات is a room she visits twice a year, and a slot on that bar is rent paid every day —
 * the same argument that already moved the household off it. What was wrong was not the missing
 * slot, it was the door. A bare 24dp gear in `Hero.muted` was the only control on this field
 * with no container under it: the action circles, the change pill, the refresh chip all sit on
 * `Hero.well`, so the one thing she had to *find* was the one thing drawn as decoration.
 *
 * So the greeting becomes the door — the disc, the name and the chevron are one target, the way
 * every phone on the planet gets you to your own account. Built like the field's three other
 * quiet doors ([ChangePill], [ReportLink], [HeroRefresh]): pill-clipped, a 48dp target around a
 * shorter visible object, muted ink. It is louder than the gear was and still quieter than the
 * total, which is the only thing up here allowed to shout.
 *
 * In the disc, a drawn mark and not her initial. A monogram is a label for a row in a list of
 * people — which is exactly the job it does in خانواده and should keep doing there — and one
 * letter alone on a green field is a letter, not a picture of anybody. [CompanionGlyph] is the
 * app's one drawing of a person, and it is drawn with [pen], the same nib every category mark in
 * the ledger is drawn with: 2dp in 24 here against [CategoryIcon]'s 1.6 in 18 is the same ratio
 * to two figures, which is what makes it read as one hand rather than as an icon fetched in.
 *
 * 24dp in a 38dp disc, and not the 20 the gear sat at. The glyph insets an eighth of its own box
 * and its widest stroke spans 0.84 of what is left, so 20dp of box was 13dp of ink — a mark
 * floating in a circle. The box is bigger so the drawing lands where the gear's did.
 *
 * It is the same mark تنظیمات puts on the خانواده row one tap below, and that is the point
 * rather than a collision. It says *a person* in both places; the word beside it says which —
 * her name here, «خانواده» there. Two drawings of a head and shoulders that far apart is the
 * near-duplicate this app spends its icon comments refusing.
 *
 * Only the label carries the state. With a name it greets her; without one it reads «تنظیمات»,
 * because on a first run the app's own name at the top of its own screen tells her nothing,
 * while the empty state two inches below is at that very moment telling her to go «از تنظیمات»
 * and turn the پیامک‌ها on.
 */
@Composable
private fun HeroAccount(name: String, onClick: () -> Unit) {
    val her = name.trim()
    Row(
        Modifier
            .clip(RoundedCornerShape(Radius.pill))
            .clickable(role = Role.Button, onClick = onClick)
            // The disc is 38dp so it does not out-weigh the 54dp actions below it; the row
            // around it carries the target, exactly as the pills further down do.
            .heightIn(min = 48.dp)
            .padding(end = Space.s)
            .semantics(mergeDescendants = true) {
                contentDescription = if (her.isNotBlank()) "$her، تنظیمات" else "تنظیمات"
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(38.dp).clip(CircleShape).background(Hero.well),
            contentAlignment = Alignment.Center,
        ) {
            CompanionGlyph(Hero.strong, size = 24.dp)
        }
        Spacer(Modifier.width(Space.m))
        Text(
            if (her.isNotBlank()) "سلام، $her" else "تنظیمات",
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            color = Hero.muted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            // `fill = false` so a short name keeps the chevron against the word rather than
            // pushed to the far edge: the arrow belongs to the door, not to the field.
            modifier = Modifier.weight(1f, fill = false),
        )
        Icon(
            Icons.AutoMirrored.Rounded.KeyboardArrowRight,
            contentDescription = null,
            tint = Hero.muted,
            modifier = Modifier.size(18.dp),
        )
    }
}

/**
 * Refresh, at the end of the line that says how old the numbers are.
 *
 * Pull-to-refresh does the same thing and is the gesture most people will use. This is the one
 * that can be named down a telephone, which is why it survives the circle it used to live in.
 */
@Composable
private fun HeroRefresh(spinning: Boolean, onClick: () -> Unit) {
    val angle = if (spinning) {
        val spin = rememberInfiniteTransition(label = "strip-spin")
        spin.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(
                tween(900, easing = LinearEasing), RepeatMode.Restart,
            ),
            label = "strip-angle",
        ).value
    } else {
        0f
    }

    Row(
        Modifier
            .clip(RoundedCornerShape(Radius.pill))
            .clickable(role = Role.Button, enabled = !spinning, onClick = onClick)
            // The visible pill is 32dp; the row around it takes the press, so the target
            // clears the floor without a control that looks like it wants the whole line.
            .heightIn(min = 48.dp)
            .padding(start = Space.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier
                .clip(RoundedCornerShape(Radius.pill))
                .background(Hero.well)
                .padding(horizontal = Space.m, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Muted, not `strong`: this annotates the sentence beside it, and at full contrast
            // it became the brightest thing in the band — a second call to act, sitting
            // directly above the tab bar's gold one.
            Icon(
                Icons.Rounded.Refresh,
                contentDescription = null,
                tint = Hero.muted,
                modifier = Modifier
                    .size(15.dp)
                    // Counter-clockwise, like every other spinner in this app: the icon is
                    // drawn with its arrowhead leading anticlockwise.
                    .graphicsLayer { rotationZ = -angle },
            )
            Spacer(Modifier.width(Space.xs))
            Text(
                "تازه کردن",
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = Hero.muted,
            )
        }
    }
}

/**
 * The way into the report when the change pill has nothing to say yet.
 *
 * Same slot, same target, no figure — because inventing one before there are thirty days to
 * compare against is exactly the kind of confident wrong number this app exists to not show.
 */
@Composable
private fun ReportLink(onClick: () -> Unit) {
    Row(
        Modifier
            .clip(RoundedCornerShape(Radius.pill))
            .clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = 48.dp)
            .padding(end = Space.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Weighted like the pill's own tail, which is what it stands in for. At `strong` and
        // SemiBold it read as a heading — the loudest line under a total it is subordinate to.
        Text("گزارش دارایی", fontSize = 13.sp, color = Hero.muted)
        Icon(
            Icons.AutoMirrored.Rounded.KeyboardArrowRight,
            contentDescription = null,
            tint = Hero.muted,
            modifier = Modifier.size(18.dp),
        )
    }
}

/**
 * One circular action on the field. Revolut's device, and the reason it works: the things she
 * might do next sit directly under the number that prompts them, all the same size, so none of
 * them is the app arguing for itself.
 */
@Composable
private fun HeroAction(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    spinning: Boolean = false,
    enabled: Boolean = true,
    /** How many things are waiting. Zero shows nothing at all. */
    badge: Int = 0,
    content: @Composable () -> Unit = {},
) {
    // Only while it actually spins: an infinite transition left running costs a frame
    // callback for ever, on the one screen that is open all day.
    val angle = if (spinning) {
        val spin = rememberInfiniteTransition(label = "spin")
        spin.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(
                tween(900, easing = LinearEasing), RepeatMode.Restart,
            ),
            label = "angle",
        ).value
    } else {
        0f
    }

    // The circle takes the press. A ripple inside a 54dp disc is most of the disc, so the
    // action reads as flashing rather than as being pushed; the give is what makes it feel
    // like a button under the thumb.
    val press = remember { MutableInteractionSource() }
    val pressed by press.collectIsPressedAsState()
    val give by animateFloatAsState(
        if (pressed) 0.96f else 1f,
        tween(Motion.fast, easing = Motion.enter),
        label = "press",
    )

    Column(
        modifier = modifier
            .scale(give)
            .clip(RoundedCornerShape(Radius.card))
            .clickable(
                enabled = enabled,
                role = Role.Button,
                interactionSource = press,
                indication = LocalIndication.current,
                onClick = onClick,
            )
            .padding(vertical = Space.s)
            .semantics { contentDescription = label },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(contentAlignment = Alignment.TopEnd) {
            Box(
                Modifier
                    .size(54.dp)
                    .clip(CircleShape)
                    .background(Hero.well),
                contentAlignment = Alignment.Center,
            ) {
                if (icon != null) {
                    Icon(
                        icon,
                        contentDescription = null,
                        tint = Hero.strong,
                        modifier = Modifier
                            .size(24.dp)
                            .graphicsLayer { rotationZ = -angle },
                    )
                } else {
                    content()
                }
            }
            // A count, and only when there is one. Nothing here is overdue and nothing is
            // wrong, so it wears the field's own gold rather than a red alarm — the app is
            // reporting, not nagging.
            //
            // Gold on `bottom`, not on Hero.well: `well` is a 10%-white raised surface, and a
            // number drawn in it lands translucent-white on near-white and simply is not there.
            if (badge > 0) {
                Box(
                    Modifier
                        .size(18.dp)
                        .clip(CircleShape)
                        .background(Hero.gold),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        faNumber(badge.coerceAtMost(99).toDouble()),
                        style = figureStyle(Hero.bottom, FontWeight.ExtraBold),
                        fontSize = 11.sp,
                    )
                }
            }
        }
        Spacer(Modifier.height(Space.s))
        Text(
            label,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = Hero.muted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * How the total has moved in a month, and the way into the report. Percent leads because it is
 * the figure that survives being compared to last month's; the Toman amount is one tap away.
 */
@Composable
private fun ChangePill(change: Change, onClick: () -> Unit) {
    val flat = abs(change.delta) < 1
    val gained = change.delta > 0
    val tone = when {
        flat -> Hero.muted
        gained -> Hero.mint
        else -> Hero.warn
    }
    val figure = when {
        flat -> "بدون تغییر"
        change.percent != null -> "${faDecimal(abs(change.percent), 1)}٪"
        else -> faCompact(abs(change.delta))
    }
    val tail = when {
        flat -> "در ۳۰ روز گذشته"
        gained -> "بیشتر از ۳۰ روز پیش"
        else -> "کمتر از ۳۰ روز پیش"
    }

    Row(
        Modifier
            .clip(RoundedCornerShape(Radius.pill))
            .clickable(role = Role.Button, onClick = onClick)
            // The pill is 32dp tall on purpose — it must not compete with the action circles
            // below it — so the row around it carries the touch target.
            .heightIn(min = 48.dp)
            .padding(end = Space.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier
                .clip(RoundedCornerShape(Radius.pill))
                .background(tone.copy(alpha = 0.18f))
                .padding(horizontal = Space.m, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (!flat) {
                TrendCaret(up = gained, tint = tone)
                Spacer(Modifier.width(6.dp))
            }
            Text(
                figure,
                fontSize = 14.sp,
                fontFamily = ModamFigures,
                fontWeight = FontWeight.Bold,
                color = tone,
            )
        }
        Spacer(Modifier.width(Space.s))
        Text(tail, fontSize = 13.sp, color = Hero.muted, maxLines = 1)
        // On خانه this is the only way into the report, so it has to look like a way in. A
        // percentage sitting on a green field reads as a statistic; the chevron is what makes
        // it a door.
        Icon(
            Icons.AutoMirrored.Rounded.KeyboardArrowRight,
            contentDescription = null,
            tint = Hero.muted,
            modifier = Modifier.size(18.dp),
        )
    }
}

/** Modam has no ▲ or ▼, and a drawn one is crisper at this size than any font would be anyway. */
@Composable
internal fun TrendCaret(up: Boolean, tint: Color, box: Dp = 9.dp) {
    Canvas(Modifier.size(box)) {
        val w = size.width
        val h = size.height
        val path = Path().apply {
            if (up) {
                moveTo(w / 2f, 0f); lineTo(w, h); lineTo(0f, h)
            } else {
                moveTo(0f, 0f); lineTo(w, 0f); lineTo(w / 2f, h)
            }
            close()
        }
        drawPath(path, tint)
    }
}

/**
 * Three rising bars for the report button — drawn, because the icon set material3 already
 * ships has no chart glyph, and one emoji in a row of monochrome icons reads as a sticker.
 */
private fun openUrl(context: Context, url: String): Boolean =
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }.isSuccess

/**
 * That there is a newer build, and nothing else. The card is one tap target: what changed and
 * both of the decisions live in the sheet behind it, because a line of card is no place to read
 * a changelog and a dismiss button sitting beside the thing it dismisses gets pressed by
 * accident.
 */
@Composable
private fun UpdateNote(release: Release, onLater: () -> Unit) {
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

    ModalBottomSheet(
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
            Text(
                "نسخه ${bidi(release.name)} اومد",
                fontSize = 26.sp,
                fontWeight = FontWeight.ExtraBold,
                modifier = Modifier.semantics { heading() },
            )
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
            Button(
                onClick = { close { onClose(); openUrl(context, release.downloadUrl) } },
                shape = RoundedCornerShape(Radius.pill),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 60.dp),
            ) { Text("گرفتن فایل نصب", fontSize = 16.sp, fontWeight = FontWeight.Bold) }

            TextButton(
                onClick = { close(onLater) },
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp),
            ) { Text("بعداً", fontSize = 15.sp) }
        }
    }
}

@Composable
private fun BarsIcon(tint: Color) {
    // 24dp box, 2dp inset: Material icons keep ~2dp of built-in padding, so drawing
    // edge-to-edge at 22dp made the bars read heavier than the gear beside them.
    Canvas(
        Modifier
            .size(24.dp)
            .semantics { contentDescription = "گزارش دارایی" },
    ) {
        inset(2.dp.toPx()) {
            val w = size.width * 0.2f
            val r = CornerRadius(w / 2f)
            drawRoundRect(tint, Offset(0f, size.height * 0.45f), Size(w, size.height * 0.55f), r)
            drawRoundRect(tint, Offset((size.width - w) / 2f, size.height * 0.2f), Size(w, size.height * 0.8f), r)
            drawRoundRect(tint, Offset(size.width - w, 0f), Size(w, size.height), r)
        }
    }
}

/**
 * Three ruled lines: a page of the ledger, drawn rather than imported.
 *
 * The icons artifact this project pins is the core set, and it has no receipt or list glyph.
 * Three rounded rects cost less than a second dependency and match [BarsIcon] beside them,
 * which is the point — the two live in the same row and must read as one family.
 */
@Composable
private fun LedgerIcon(tint: Color) {
    Canvas(
        Modifier
            .size(24.dp)
            .semantics { contentDescription = "دفتر تراکنش‌ها" },
    ) {
        inset(2.dp.toPx()) {
            val h = size.height * 0.14f
            val r = CornerRadius(h / 2f)
            drawRoundRect(tint, Offset(0f, size.height * 0.10f), Size(size.width, h), r)
            drawRoundRect(tint, Offset(0f, size.height * 0.43f), Size(size.width * 0.78f, h), r)
            drawRoundRect(tint, Offset(0f, size.height * 0.76f), Size(size.width * 0.52f, h), r)
        }
    }
}

/**
 * A real logo where one exists, an emoji for the fixed assets, and the ticker as a last
 * resort. The letters sit underneath and are covered once the logo loads, so a slow network
 * shows an identifiable badge rather than a hole.
 *
 * [network] marks which chain the coin actually sits on. Tether on Tron and Tether on Ethereum
 * are the same logo and the same row, and until this the only thing telling them apart was a
 * 12sp line that a long address truncates.
 */
@Composable
private fun AssetIcon(type: AssetType, size: Dp = 44.dp, network: String? = null) {
    // Tinted by kind rather than one grey for all. It only ever shows behind the emoji and
    // letter fallbacks — a coin's own logo covers it — which is exactly where a list of
    // eleven identical grey discs was hardest to scan.
    val scheme = MaterialTheme.colorScheme
    val tint = when (type.kind) {
        Kind.CASH -> scheme.primaryContainer
        Kind.FIAT -> scheme.tertiaryContainer
        Kind.GOLD, Kind.SILVER, Kind.COIN -> scheme.secondaryContainer
        Kind.CRYPTO, Kind.STOCK -> scheme.surfaceVariant
        Kind.PROPERTY -> scheme.tertiaryContainer
    }
    Box(contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .size(size)
                .clip(CircleShape)
                .background(tint),
            contentAlignment = Alignment.Center,
        ) {
            val letters = @Composable {
                // Three letters at 12sp, never four at 9: this badge is the only identity a coin
                // has until its logo loads, and 9sp is below the floor an older eye can read.
                Text(
                    type.id.take(3).uppercase(),
                    fontSize = 12.sp,
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
                    var success by remember(type.iconUrl) { mutableStateOf(false) }
                    var loaded by remember(type.iconUrl) { mutableStateOf(false) }
                    if (!loaded) letters()
                    // crossfade: Coil swaps hard by default, and a list of logos popping in
                    // over their letters reads as flicker.
                    AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current)
                            .data(type.iconUrl)
                            .crossfade(true)
                            .build(),
                        contentDescription = null,
                        modifier = Modifier.size(size * 0.62f),
                        // A row scrolled off is thrown away, so scrolling back composes this
                        // afresh with loaded=false and the letters underneath — which is
                        // invisible under an opaque logo, and exactly visible under a
                        // transparent one. Ethereum's diamond covers a third of its canvas, so
                        // every pass over the list restamped "ETH" through the glyph for a
                        // quarter second. From the memory cache there is no fade for the
                        // letters to cover — Coil skips the transition — so they can leave the
                        // moment the logo lands.
                        onState = { st ->
                            if (st is AsyncImagePainter.State.Success) {
                                if (st.result.dataSource == DataSource.MEMORY_CACHE) loaded = true
                                else success = true
                            }
                        },
                    )
                    // Success is when the fade STARTS; the letters leave after it lands, or
                    // the circle shows exactly the hole they exist to cover.
                    LaunchedEffect(success) {
                        if (success) {
                            delay(250)
                            loaded = true
                        }
                    }
                }
                type.emoji != null -> Text(type.emoji, fontSize = (size.value * 0.52f).sp)
                else -> letters()
            }
        }
        // Bottom-end corner, sitting on the logo rather than beside it — the chain is a
        // property of that coin, and given its own slot in the row it reads as a second asset.
        // The ring is the row's own background, so the two discs stay two discs even where a
        // dark chain logo meets a dark coin logo.
        network?.let {
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(1.5.dp),
            ) { WalletNetworkLogo(it, size = size * 0.36f) }
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
        live.any { !it.trusted } -> "$counted  •  نیاز به بررسی"
        off > 0 -> "$counted  •  ${faNumber(off.toDouble())} خاموش"
        else -> counted
    }
}

// Material3's sheet hands whatever fling velocity its content leaves over to settle(), even
// when the sheet is already fully expanded and the leftover points further up — which kicks
// the settle spring from a standstill and bounces the sheet past its anchor and back. That is
// the jump every list gives at the end of its scroll. Upward leftovers have nowhere to go
// here (onPreFling already pulls a half-dragged sheet back up), so swallow them; downward
// ones still fling the sheet closed.
// ponytail: attach to each sheet's scrolling content. Four sheets in, still no wrapper: three
// of them bound a LazyColumn to 0.88 of the screen and the fourth wraps its content, so the
// shared part is this one line. Extract when two sheets want the same body, not the same guard.
internal val SheetFlingGuard = object : NestedScrollConnection {
    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity =
        if (available.y < 0f) available else Velocity.Zero
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
    onToggle: (String, Boolean) -> Unit,
    onForget: (String) -> Unit,
    onAddNumber: (String, String) -> Unit,
    onDismissSender: (String) -> Unit,
    onRescan: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val context = LocalContext.current
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var fixing by remember { mutableStateOf<String?>(null) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = Radius.sheet, topEnd = Radius.sheet),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            Modifier
                .nestedScroll(SheetFlingGuard)
                .fillMaxHeight(0.88f)
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = Space.xl),
        ) {
            Text(
                "حساب‌های بانکی",
                fontSize = 26.sp,
                fontWeight = FontWeight.ExtraBold,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                "موجودی این حساب‌ها از پیامک بانک‌ها خونده می‌شه. اگه بانکی رو خاموش کنی، موجودیش توی جمع نمیاد.",
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
                        onToggle = { on -> onToggle(acc.bank, on) },
                        onForget = { onForget(acc.key) },
                        onOpenSms = { openSmsThread(context, acc.sender) },
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
                        onClick = { openSmsThread(context, stranger.sender) },
                        shape = RoundedCornerShape(Radius.card),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(Modifier.padding(Space.l)) {
                            Row(verticalAlignment = Alignment.Top) {
                                BankLogo(stranger.bank)
                                Spacer(Modifier.width(Space.s))
                                Text(
                                    "یه پیامک شبیه پیامک‌های $bankFa از شماره‌ای رسیده که هنوز توی فهرست نیست:",
                                    fontSize = 13.sp,
                                    lineHeight = 20.sp,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                )
                            }
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
                            Text(
                                "باز کردن پیامک‌ها",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                textDecoration = TextDecoration.Underline,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.padding(top = Space.s),
                            )
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                // Air between the confirm and the irreversible dismiss —
                                // borderless buttons at zero gap invite the edge mis-tap.
                                horizontalArrangement = Arrangement.spacedBy(Space.m),
                            ) {
                                TextButton(
                                    onClick = { onAddNumber(stranger.bank, stranger.sender) },
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Text(
                                        "این شماره مال $bankFa هست؛ اضافه‌اش کن",
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
                                        // Named consequence, like every other second tap in
                                        // the app — and announced, so the armed state exists
                                        // for someone listening too.
                                        if (sure) "برای رد کردن، دوباره بزن" else "رد کردن",
                                        fontSize = 14.sp,
                                        fontWeight = if (sure) FontWeight.Bold else FontWeight.Normal,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.75f),
                                        modifier = Modifier.semantics {
                                            liveRegion = LiveRegionMode.Polite
                                        },
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
                            if (sure) "همه مبلغ‌ها دوباره از پیامک‌ها خونده بشن؟ دوباره بزن"
                            else "دوباره خوندن همه پیامک‌ها",
                            fontSize = 14.sp,
                            fontWeight = if (sure) FontWeight.Bold else FontWeight.Normal,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
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
    onToggle: (Boolean) -> Unit,
    onForget: () -> Unit,
    onOpenSms: () -> Unit,
) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    var draft by remember(account.key) { mutableStateOf("") }

    Card(
        shape = RoundedCornerShape(Radius.card),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(Space.l)) {
            // Off dims by colour, not by a blanket alpha: alpha over the whole row took the
            // 12sp caption — the line that explains the state — below every contrast floor.
            // Top, and the unit inline, for the same reason the list row outside this sheet
            // does both: the balance belongs on the account's own line, not floating beside
            // the caption under it.
            Row(verticalAlignment = Alignment.Top) {
                BankLogo(account.bank)
                Spacer(Modifier.width(Space.s))
                Column(Modifier.weight(1f)) {
                    RowTitle(
                        account.bankFa,
                        color = if (off) muted else MaterialTheme.colorScheme.onSurface,
                        max = 17.sp,
                    )
                    Text(
                        listOfNotNull(
                            account.mask.takeIf { it.isNotBlank() }?.let { bidi(it) },
                            faAgo(account.updatedAt, now),
                            "خاموش".takeIf { off },
                        ).joinToString("  •  "),
                        fontSize = 12.sp,
                        color = muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                Spacer(Modifier.width(Space.s))
                RowAmount(
                    account.balance,
                    color = if (off) muted else MaterialTheme.colorScheme.onSurface,
                    struck = off,
                    unit = "تومان",
                )
            }

            // The same switch, with the same words, as setting an asset aside in its edit
            // sheet — one habit covers both. toggleable on the row merges label and switch
            // into one named control, and makes the whole line the hit target. Only for an
            // anchored account: an unanchored one is never in the total, and a switch
            // saying "counted" right above the red caption saying "not counted" would be
            // the card contradicting itself.
            if (account.anchored) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .toggleable(value = !off, role = Role.Switch, onValueChange = onToggle)
                        // With onCheckedChange = null the Switch no longer carries its own
                        // 48dp minimum; the row has to.
                        .heightIn(min = 48.dp)
                        .padding(top = Space.xs),
                ) {
                    Text(
                        "توی جمع حساب بشه",
                        fontSize = 14.sp,
                        color = if (off) muted else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(checked = !off, onCheckedChange = null)
                }
            }

            // The whole point of the flag: say what is uncertain and offer the one thing that
            // fixes it, rather than leaving a number on screen that quietly might be wrong.
            if (!account.trusted) {
                Text(
                    if (!account.anchored)
                            "این عدد فقط جمع تراکنش‌هاست، نه موجودی واقعی، و توی جمع حساب نشده. " +
                            "موجودی حسابت رو وارد کن تا توی جمع بیاد."
                    else "این عدد از پیامکی خونده شده که از درست بودنش مطمئن نیستیم. اگه اشتباهه، اصلاحش کن.",
                    fontSize = 12.sp,
                    lineHeight = 19.sp,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = Space.s),
                )
            }

            if (fixing) {
                // A label that stays, not a placeholder that vanishes at the first digit —
                // while she types a balance, the unit must still be on screen.
                Text(
                    "موجودی به تومان",
                    fontSize = 14.sp,
                    color = muted,
                    modifier = Modifier.padding(top = Space.s),
                )
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    singleLine = true,
                    visualTransformation = GroupedNumber,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    supportingText = if (draft.isNotBlank() && parseAmount(draft) == null) ({
                        Text("این عدد قابل خوندن نیست. فقط عدد وارد کن.")
                    }) else null,
                    shape = RoundedCornerShape(Radius.field),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = Space.xs)
                        .semantics { contentDescription = "موجودی به تومان" },
                )
            }

            Row(Modifier.padding(top = Space.xs)) {
                if (account.sender.isNotBlank()) {
                    TextButton(onClick = onOpenSms) {
                        Text("دیدن پیامک‌ها", fontSize = 14.sp)
                    }
                }
                if (fixing) {
                    TextButton(
                        onClick = { parseAmount(draft)?.let(onAnchor) },
                        enabled = parseAmount(draft) != null,
                    ) { Text("ذخیره موجودی", fontSize = 14.sp) }
                } else {
                    TextButton(onClick = onFix) { Text("اصلاح موجودی", fontSize = 14.sp) }
                }
                Spacer(Modifier.weight(1f))
                TextButton(
                    onClick = onForget,
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) { Text("حذف حساب", fontSize = 14.sp) }
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
            .padding(edge)
            // A heading belongs to what follows it — more air above than below, as in the
            // picker. The 4dp inset is optical: flush against a 28dp corner radius, the label
            // reads as hanging outside the band it heads.
            .padding(top = Space.xxl, bottom = Space.m, start = Space.xs, end = Space.xs),
        verticalAlignment = Alignment.Bottom,
    ) {
        Text(
            title,
            fontSize = 15.sp,
            fontWeight = FontWeight.ExtraBold,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .semantics { heading() },
        )
        subtotal?.let {
            Text(
                // Three decimals, like the rows it sits above: a header reading ۱٫۴ over rows
                // that add up to ۱٫۴۰۹ looks like one of the two is wrong.
                "${faCompact(it, 3, pad = true)} تومان",
                fontSize = 13.sp,
                fontFamily = ModamFigures,
                fontWeight = FontWeight.SemiBold,
                color = muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
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
private fun RowTitle(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
    max: TextUnit = 18.sp,
) {
    BasicText(
        text = text,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        autoSize = TextAutoSize.StepBased(minFontSize = 13.sp, maxFontSize = max),
        modifier = modifier,
        style = TextStyle(fontFamily = Modam, fontWeight = FontWeight.Bold, color = color),
    )
}

@Composable
private fun RowAmount(
    toman: Double,
    color: Color,
    struck: Boolean = false,
    /**
     * Set inline, the way the hero card sets it, rather than stacked underneath. A caption on
     * its own line makes the figure two lines tall, and everything the row says about the
     * asset then starts a full line-height below the name it belongs to — the name ends up
     * stranded at the top of the card with a hole under it.
     */
    unit: String? = null,
) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    BasicText(
        text = buildAnnotatedString {
            append(faCompact(toman, 3, pad = true))
            // em, not sp: the whole line is shrink-to-fit, and the unit has to shrink with the
            // figure or it overtakes it on the row where the figure is smallest.
            unit?.let {
                // The space stays near full size, or it shrinks with the unit and the two
                // words touch. See the hero figure, which has the same seam.
                append(" ")
                withStyle(SpanStyle(fontSize = 0.7.em, fontWeight = FontWeight.Medium, color = muted)) {
                    append(it)
                }
            }
        },
        maxLines = 1,
        autoSize = TextAutoSize.StepBased(minFontSize = 13.sp, maxFontSize = 19.sp),
        // Bounded so the figure, not the asset's name, is what gives way on a narrow phone:
        // "۵۵۹٫۵۰۰ میلیون" is half again as wide as "۵۵۹٫۵ میلیون" was.
        modifier = Modifier.widthIn(max = if (unit == null) 116.dp else 140.dp),
        style = figureStyle(color, FontWeight.Bold).copy(
            textDecoration = if (struck) TextDecoration.LineThrough else null,
        ),
    )
}

private fun shortWalletAddress(address: String): String =
    if (address.length <= 15) address else "${address.take(7)}...${address.takeLast(5)}"

@Composable
private fun HoldingRow(
    type: AssetType,
    /** Her own name for this holding where she gave it one, the asset's own where she did not. */
    name: String,
    amount: Double,
    rate: Double?,
    excluded: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    /** Replaces the amount-and-rate line, for a row whose amount did not come from her. */
    note: String? = null,
    wallet: WalletLink? = null,
    walletRefreshing: Boolean = false,
    walletError: String? = null,
    shape: Shape = RoundedCornerShape(Radius.group),
    divided: Boolean = false,
) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val strong = MaterialTheme.colorScheme.onSurface

    // No card, no elevation, no gap: a band of holdings is one object with rows inside it.
    // Individual cards gave every row the same weight as the band it belonged to, and eleven
    // of them read as eleven separate announcements rather than one list.
    //
    // No gesture either: setting an asset aside lives behind the row's edit sheet, as a
    // labelled switch. Swipes proved too easy to trigger scrolling the list and too hard
    // to discover on purpose.
    Box(
        modifier
            .padding(edge)
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface)
            .clickable(role = Role.Button, onClick = onClick),
    ) {
        // Set-aside dims by colour, not a blanket alpha: alpha over the whole row took the
        // 12sp caption that explains the state below every contrast floor.
        // Top, not centre: the badge belongs to the name, and centring it against a row that
        // may carry two more lines under that name left it floating beside the wrong one.
        // 44dp is the height of the name and the line below it, so it spans exactly the pair.
        Row(
            Modifier.padding(horizontal = Space.l, vertical = Space.l),
            verticalAlignment = Alignment.Top,
        ) {
            Box(Modifier.alpha(if (excluded) 0.55f else 1f)) {
                AssetIcon(type, network = wallet?.network)
            }
            Spacer(Modifier.width(Space.m))

            Column(Modifier.weight(1f)) {
                // The hero's own device, one size down: the name and the figure share the top
                // line, so the row reads as one sentence instead of two blocks that happen to
                // be next to each other. It also hands every line below it the full width of
                // the block rather than the strip left over beside the figure — which is what
                // the rate used to be truncated into.
                Row(verticalAlignment = Alignment.Top) {
                    RowTitle(
                        name,
                        color = if (excluded) muted else strong,
                        // One step under the figure. The money is the answer this row exists
                        // to give; the name only says which money.
                        max = 17.sp,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(Space.s))
                    // End, not Start: the figures are already flush with the card's edge, so
                    // this is the only alignment that keeps a set-aside row's caption in the
                    // same column instead of hanging it off the ragged inner end of a number.
                    Column(horizontalAlignment = Alignment.End) {
                        when {
                            excluded -> {
                                if (rate != null) {
                                    RowAmount(amount * rate, color = muted, struck = true)
                                }
                                // Stacked, not inline: this one is the row's state, not the
                                // figure's unit, and it has to survive being read on its own.
                                Text("توی جمع حساب نشده", fontSize = 12.sp, color = muted)
                            }
                            rate == null ->
                                Text("نرخش پیدا نشد", fontSize = 14.sp, color = MaterialTheme.colorScheme.error)
                            else -> RowAmount(amount * rate, color = strong, unit = "تومان")
                        }
                    }
                }

                val held = "${faHeld(amount, type.dec)} ${bidi(type.unitFa)}"
                // null when this line is a sentence rather than an amount-and-rate pair.
                val shownRate = rate?.takeIf { note == null && !type.valuedInToman }?.let(::faRate)
                if (shownRate == null) {
                    Text(
                        when {
                            note != null -> note
                            // Says how the figure got here, which is the whole difference
                            // between this row and the bank one right below it — and the only
                            // thing there is to say about a car nobody quotes a price for.
                            type.valuedInToman -> "دستی وارد شده"
                            else -> held
                        },
                        fontSize = 13.sp,
                        color = muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                } else {
                    // The rate carries no weight, so Row measures it first and gives it the
                    // width it asks for: when the line cannot hold both, the amount she typed
                    // herself is what gives way, never the rate she opened the app to read.
                    // One string, not two Texts — the label and its figure have to stay in one
                    // bidi run or the separator drifts off the end of the line.
                    Row(Modifier.padding(top = 2.dp)) {
                        Text(
                            held,
                            fontSize = 13.sp,
                            color = muted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        Text(
                            buildAnnotatedString {
                                // "نرخ" rather than "هر <unit>": one less latin run, and short
                                // enough that a long ticker cannot push the line onto a second.
                                withStyle(SpanStyle(color = muted)) { append("•  نرخ ") }
                                // Full strength and a weight up — the one figure on this line
                                // worth finding at a glance, and the reason the line exists.
                                withStyle(SpanStyle(color = strong, fontWeight = FontWeight.Medium)) {
                                    append(shownRate)
                                }
                            },
                            fontSize = 13.sp,
                            maxLines = 1,
                            modifier = Modifier.padding(start = Space.s),
                        )
                    }
                }

                wallet?.let {
                    // One line, not two: the address is a "this is tracked for you" mark, not
                    // something she can check by reading — and stacked, it was the third loose
                    // block that made the card read as pieces rather than a row.
                    val visibleStatus = when {
                        walletRefreshing -> "در حال گرفتن از ${it.networkFa}"
                        walletError != null -> "به‌روز نشد  •  موجودی قبلی نشون داده می‌شه"
                        else -> "${it.networkFa}  •  ${bidi(shortWalletAddress(it.address))}"
                    }
                    val spokenStatus = when {
                        walletRefreshing -> "در حال گرفتن موجودی از ${it.networkFa}"
                        walletError != null -> "موجودی به‌روز نشد؛ موجودی قبلی نشون داده می‌شه"
                        else -> "خوندن خودکار از ${it.networkFa}، آدرس ${it.address}"
                    }
                    Text(
                        visibleStatus,
                        fontSize = 12.sp,
                        color = if (walletError != null) MaterialTheme.colorScheme.error else muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .padding(top = 2.dp)
                            .semantics { contentDescription = spokenStatus },
                    )
                }
            }
        }
        // Inset to where the text starts, not to the card edge: a rule that runs under the
        // badge cuts the row in half instead of separating it from the next one. Only between
        // rows — a rule under the last one is the band's own bottom edge drawn twice.
        if (divided) {
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = Space.l + 44.dp + Space.m),
            )
        }
    }
}

/**
 * It names the button rather than repeating it. The bar at the bottom is on screen the whole
 * time this hint is, and two identical green pills one above the other read as a mistake —
 * the reader's question becomes which of them is the real one.
 */
@Composable
private fun EmptyHint() {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(edge)
            .padding(top = Space.huge, bottom = Space.xxl),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(84.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainer),
            contentAlignment = Alignment.Center,
        ) { Text("👛", fontSize = 40.sp) }
        Spacer(Modifier.height(Space.xl))
        Text(
            "هنوز چیزی اضافه نکردی",
            fontSize = 22.sp,
            fontWeight = FontWeight.ExtraBold,
        )
        Spacer(Modifier.height(Space.s))
        Text(
            "از دکمه پایین پول نقد، دلار، طلا، سکه یا رمزارز اضافه کن تا جمعشون رو ببینی.",
            fontSize = 15.sp,
            lineHeight = 25.sp,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun MissingNote(missing: List<String>, coins: List<Coin>, stocks: List<Stock>) {
    val names = missing.map { resolveType(it, coins, stocks).fa }.distinct().joinToString("، ")
    Row(
        Modifier
            .padding(edge)
            .padding(top = Space.l)
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.card))
            .background(MaterialTheme.colorScheme.errorContainer)
            .padding(Space.l),
    ) {
        Text(
            "نرخ $names پیدا نشد و توی جمع حساب نشده. می‌تونی نرخش رو دستی وارد کنی.",
            fontSize = 13.sp,
            lineHeight = 21.sp,
            color = MaterialTheme.colorScheme.onErrorContainer,
        )
    }
}

/**
 * The kinds too numerous to list, mapped to what to call them when saying so. These come from
 * the network by the hundred, so the picker shows a first handful and leaves the rest to
 * search; every other kind is a short fixed list that belongs on screen in full.
 */
private val BROWSABLE_BY_SEARCH = mapOf(
    Kind.CRYPTO to "رمزارزها",
    Kind.STOCK to "نمادها",
)

/**
 * A drag-to-dismiss sheet rather than a dialog: the list is long, the phone is held one
 * handed, and the controls belong near the thumb.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PickTypeSheet(
    all: List<AssetType>,
    already: Map<String, Int>,
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
            // Capped, or 250 tickers bury the assets she actually owns — and بورس is four
            // times that again.
            Kind.entries.map { kind ->
                val items = all.filter { it.kind == kind }
                kind.fa to if (kind in BROWSABLE_BY_SEARCH) items.take(12) else items
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
                .nestedScroll(SheetFlingGuard)
                .fillMaxHeight(0.88f)
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = Space.xl)
        ) {
            Text(
                "چی می‌خوای اضافه کنی؟",
                fontSize = 26.sp,
                fontWeight = FontWeight.ExtraBold,
                modifier = Modifier.semantics { heading() },
            )

            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                // No fontSize on the placeholder: it sits in the same slot as the value and
                // must not shrink the moment she starts typing.
                placeholder = { Text("جستجو...") },
                shape = RoundedCornerShape(Radius.field),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = Space.m, bottom = Space.xs)
                    .semantics { contentDescription = "جستجو" },
            )

            LazyColumn(
                contentPadding = PaddingValues(bottom = Space.xxl),
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
                if (q.isNotEmpty() && sections.all { it.second.isEmpty() }) {
                    item {
                        // Names the query and points at the exit: search also matches the
                        // latin name and the ticker, and this is the one moment to say so.
                        Text(
                            // bidi(): a mixed query like "gold 18" would otherwise render
                            // with its runs reordered inside the guillemets.
                            "برای «${bidi(q)}» چیزی پیدا نشد. اسم انگلیسی یا نمادش رو هم امتحان کن.",
                            fontSize = 15.sp,
                            lineHeight = 24.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = Space.xxl),
                        )
                    }
                }

                sections.forEach { (title, items) ->
                    if (items.isEmpty()) return@forEach
                    if (title != null) {
                        item(key = "h_$title") {
                            // The same heading the asset list uses, so a kind looks like the
                            // same kind of thing whether she is reading her money or adding to it.
                            Text(
                                title,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = MaterialTheme.colorScheme.onSurface,
                                // more air above a heading than below it
                                modifier = Modifier
                                    .padding(top = Space.xxl, bottom = Space.s, start = Space.xs)
                                    .semantics { heading() },
                            )
                        }
                    }
                    items(items, key = { it.id }) { type ->
                        PickRow(type, already[type.id] ?: 0) { close { onPick(type.id) } }
                    }
                    // The cue that more exist sits under the rows it discloses — at the
                    // bottom of the whole sheet, after gold and سکه, a capped section just
                    // looked complete.
                    val more = BROWSABLE_BY_SEARCH.entries.firstOrNull { it.key.fa == title }
                    if (q.isEmpty() && more != null) {
                        item(key = "more_hint_$title") {
                            Text(
                                "بقیه ${more.value} رو می‌خوای؟ اسمش رو جستجو کن.",
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = Space.s, start = Space.xs),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PickRow(type: AssetType, already: Int, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.field))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = Space.s, vertical = Space.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AssetIcon(type, size = 40.dp)
        Spacer(Modifier.width(Space.m))
        Column(Modifier.weight(1f)) {
            Text(
                type.fa,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // The ticker tells one coin from another where the Persian names blur together;
            // for a نماد the same job is done by the company behind it, since its own id is
            // an instrument code that means nothing to anyone.
            val subtitle = when (type.kind) {
                Kind.CRYPTO -> type.id.uppercase()
                Kind.STOCK -> type.en
                else -> ""
            }
            if (subtitle.isNotBlank()) {
                Text(
                    subtitle,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (already > 0) {
            // A chip, not loose grey words: it is a state this row is in, and it has to read
            // as attached to the row rather than as a second, quieter label.
            Text(
                if (already == 1) "اضافه شده" else "${faNumber(already.toDouble())} تا اضافه شده",
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .clip(RoundedCornerShape(Radius.pill))
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                    .padding(horizontal = Space.m, vertical = 5.dp),
            )
        }
    }
}

private enum class AmountSource { WALLET, MANUAL }

/**
 * A label over a group inside a sheet. It owns the air above itself, so the sections of a long
 * sheet are separated by one rule rather than by whatever `Spacer` each of them happened to be
 * given — three of them had drifted to three different gaps.
 *
 * More space above than below, because a heading belongs to what follows it.
 */
@Composable
private fun SheetLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        fontSize = 14.sp,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
            .padding(top = Space.xxl, bottom = Space.s, start = Space.xs)
            .semantics { heading() },
    )
}

/**
 * One track, one filled pill — the app's only shape for "pick exactly one of a few". The
 * report's window picker, the theme picker and the two ways of recording a balance were three
 * different-looking answers to the same question.
 *
 * The fill is not the only signal: the chosen pill is also the only bold one, and it carries
 * `selected` for anyone listening rather than looking.
 */
@Composable
internal fun <T> SegmentedChoice(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    enabled: (T) -> Boolean = { true },
    role: Role = Role.RadioButton,
    fontSize: TextUnit = 15.sp,
) {
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.pill))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(Space.xs)
            .selectableGroup(),
    ) {
        options.forEach { option ->
            val active = option == selected
            val usable = enabled(option)
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(Radius.pill))
                    .background(
                        if (active) MaterialTheme.colorScheme.primary else Color.Transparent,
                    )
                    .selectable(
                        selected = active,
                        enabled = usable,
                        role = role,
                        onClick = { onSelect(option) },
                    )
                    .heightIn(min = 48.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label(option),
                    fontSize = fontSize,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                    // 0.5, not 0.35: at 0.35 a dark-mode label fell below the floor where
                    // disabled text stops being readable at all.
                    color = when {
                        active -> MaterialTheme.colorScheme.onPrimary
                        usable -> MaterialTheme.colorScheme.onSurface
                        else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    },
                )
            }
        }
    }
}

/**
 * Nine networks as nine full-width rows was half the sheet spent on one choice, and the logo —
 * the thing the eye actually looks for — sat after a label of variable length, so no two logos
 * shared an edge. Two columns, logo leading from a fixed inset, name after it: the column of
 * marks becomes the index, and the whole choice fits in a glance instead of a scroll.
 *
 * Chosen is a fill *and* a ring *and* a weight, because a fill alone is colour doing the work
 * of structure.
 */
@Composable
private fun WalletNetworkChoice(
    options: List<WalletOption>,
    selected: String,
    enabled: Boolean,
    onSelect: (String) -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.6f)
            .selectableGroup(),
        verticalArrangement = Arrangement.spacedBy(Space.m),
    ) {
        options.chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(Space.m)) {
                pair.forEach { option ->
                    val active = option.network == selected
                    Row(
                        Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(Radius.field))
                            .background(
                                if (active) MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surfaceContainer,
                            )
                            .border(
                                width = if (active) 2.dp else 0.dp,
                                color = if (active) MaterialTheme.colorScheme.primary
                                else Color.Transparent,
                                shape = RoundedCornerShape(Radius.field),
                            )
                            .selectable(
                                selected = active,
                                enabled = enabled,
                                role = Role.RadioButton,
                                onClick = { onSelect(option.network) },
                            )
                            .heightIn(min = 56.dp)
                            .padding(horizontal = Space.m),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        WalletNetworkLogo(option.network)
                        Spacer(Modifier.width(Space.s))
                        Text(
                            option.networkFa,
                            fontSize = 14.sp,
                            fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                // An odd count keeps the grid's columns rather than stretching the last tile
                // across both — the shared leading edge is the point of the layout.
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
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
private fun WalletNetworkLogo(network: String, size: Dp = 28.dp) {
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditSheet(
    // Which holding this sheet is about, not which asset: two rows can share an asset, so the
    // fields below have to be re-seeded per holding.
    key: String,
    type: AssetType,
    holding: Holding?,
    rate: Double?,
    isOverridden: Boolean,
    excluded: Boolean,
    walletBusy: Boolean,
    walletError: String?,
    onWalletEdit: () -> Unit,
    onExcluded: (Boolean) -> Unit,
    onSaveManual: (Double) -> Unit,
    onSaveWallet: (WalletOption, String, () -> Unit) -> Unit,
    onDelete: () -> Unit,
    onRate: (Double?) -> Unit,
    onLabel: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val current = holding?.amount
    val linkedWallet = holding?.wallet
    val linkedOption = linkedWallet?.let {
        WalletOption(it.network, it.networkFa, it.contract)
    }
    val walletOptions = remember(type.wallets, linkedOption) {
        (type.wallets + listOfNotNull(linkedOption)).distinctBy { it.network }
    }
    val startsWithWallet = type.kind == Kind.CRYPTO && walletOptions.isNotEmpty() &&
        (linkedWallet != null || holding == null)

    // Raw digits live in state; grouping is purely visual so parsing stays exact.
    var source by remember(key, linkedWallet != null, walletOptions.isNotEmpty()) {
        mutableStateOf(if (startsWithWallet) AmountSource.WALLET else AmountSource.MANUAL)
    }
    var text by remember(key) {
        mutableStateOf(current?.let { trimNumber(it, type.dec) } ?: "")
    }
    var rateText by remember { mutableStateOf(rate?.let { trimNumber(it, 0) } ?: "") }
    var editingRate by remember { mutableStateOf(false) }
    var labelText by remember(key) { mutableStateOf(holding?.label.orEmpty()) }
    var naming by remember(key) {
        mutableStateOf(holding == null && type.kind == Kind.PROPERTY)
    }
    var nameFocusWanted by remember(key) { mutableStateOf(false) }
    var adjusting by remember { mutableStateOf(false) }
    var deltaText by remember { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf(false) }
    var manualSubmitted by remember { mutableStateOf(false) }
    var walletAddress by remember(key, linkedWallet?.address) {
        mutableStateOf(linkedWallet?.address.orEmpty())
    }
    var selectedNetwork by remember(key, linkedWallet?.network, walletOptions) {
        mutableStateOf(linkedWallet?.network ?: walletOptions.firstOrNull()?.network.orEmpty())
    }
    var localWalletError by remember { mutableStateOf<String?>(null) }

    val manualAmount = parseAmount(text)
    val selectedWallet = walletOptions.firstOrNull { it.network == selectedNetwork }
    val linkedSelection = linkedWallet != null &&
        linkedWallet.network == selectedNetwork &&
        linkedWallet.address == walletAddress.trim() &&
        linkedWallet.contract == selectedWallet?.contract
    val amount = if (source == AmountSource.MANUAL) manualAmount
    else current?.takeIf { linkedSelection }
    val typedRate = parseAmount(rateText)?.takeIf { it > 0 }
    // While she is typing a manual rate, the "می‌شود …" preview follows what she types, so
    // the consequence of the override is visible before it is committed.
    val previewRate = if (editingRate) typedRate ?: rate else rate
    val muted = MaterialTheme.colorScheme.onSurfaceVariant

    val amountFocus = remember { FocusRequester() }
    val walletAddressFocus = remember { FocusRequester() }
    if (current == null && source == AmountSource.MANUAL) {
        LaunchedEffect(source) {
            delay(250) // let the sheet finish sliding in before the IME starts moving it
            amountFocus.requestFocus()
        }
    }

    // The name she typed, written to the row — from ذخیره, from ذخیره اسم, and from closing the
    // sheet. Typing a name and swiping the sheet away is not "cancel", it is how anyone would
    // expect a name to be given, and until this ran on dismiss too it was thrown away in silence.
    // Nothing to write to while adding: there is no row until ذخیره makes one, and that path
    // calls this itself, in order.
    val nameIt = { if (labelText.trim() != holding?.label.orEmpty()) onLabel(labelText) }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    fun close(then: () -> Unit) = scope.launch { sheetState.hide(); then() }

    ModalBottomSheet(
        // Nothing to write to while adding: there is no row until ذخیره makes one.
        onDismissRequest = { if (holding != null) nameIt(); onDismiss() },
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = Radius.sheet, topEnd = Radius.sheet),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            // Scrolls: with the keyboard up — and it comes up on its own when adding — the
            // expanded sections pushed ذخیره off the bottom of shorter phones with no way
            // to reach it.
            Modifier
                .nestedScroll(SheetFlingGuard)
                .navigationBarsPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Space.xl),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AssetIcon(type, size = 42.dp)
                Spacer(Modifier.width(Space.m))
                Column(Modifier.weight(1f)) {
                    Text(
                        holding?.nameOr(type.fa) ?: type.fa,
                        fontSize = 26.sp,
                        fontWeight = FontWeight.ExtraBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    // Only once the two differ: under "تتر" this line would just say "تتر".
                    if (!holding?.label.isNullOrBlank()) {
                        Text(
                            type.fa,
                            fontSize = 13.sp,
                            color = muted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            // Named, not renamed: the asset keeps its own name everywhere else, and this is
            // the label on this particular holding of it — "تتر شخصی" beside "تتر مشترک",
            // "پراید سفید" beside "ویلای شمال".
            //
            // Offered while adding too, not only when editing. The moment a name is needed is
            // the moment she is adding the *second* خودرو, and until now that meant saving a
            // row indistinguishable from the first one and coming back for it. Open from the
            // start for املاک و خودرو, where the asset's own name never tells two apart, and
            // behind a tap everywhere else, where it usually does.
            if (!naming) {
                TextButton(
                    onClick = { naming = true; nameFocusWanted = true },
                    modifier = Modifier.padding(top = Space.s),
                ) {
                    Text(
                        if (labelText.isBlank()) "اسم دلخواه بذار" else "تغییر اسم",
                        fontSize = 14.sp,
                    )
                }
            } else {
                val nameFocus = remember { FocusRequester() }
                // Only when she asked for the field. Opened on its own for a new ملک the amount
                // is still the first thing to type, and the caret must not be taken off it.
                LaunchedEffect(nameFocusWanted) {
                    if (nameFocusWanted) {
                        nameFocus.requestFocus()
                        nameFocusWanted = false
                    }
                }
                SheetLabel("اسم دلخواه")
                OutlinedTextField(
                    value = labelText,
                    onValueChange = { labelText = it.take(32) },
                    singleLine = true,
                    // A label that stays, not a placeholder that vanishes at the first
                    // letter — the example is the whole explanation of what this is for.
                    placeholder = {
                        Text(
                            if (type.kind == Kind.PROPERTY) "مثلاً ${type.fa} دوم"
                            else "مثلاً ${type.fa} شخصی",
                        )
                    },
                    shape = RoundedCornerShape(Radius.field),
                    // While adding, the amount is the next thing to type and the keyboard says
                    // so; there is no row yet for a Done to write to.
                    keyboardOptions = KeyboardOptions(
                        imeAction = if (holding == null) ImeAction.Next else ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = { onLabel(labelText); naming = false },
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(nameFocus)
                        .semantics { contentDescription = "اسم دلخواه" },
                )
                // A name can be saved on its own only once there is a row to hang it on. While
                // adding, ذخیره at the bottom carries it in together with the amount.
                if (holding != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(Space.m)) {
                        TextButton(onClick = { onLabel(labelText); naming = false }) {
                            Text("ذخیره اسم", fontSize = 14.sp)
                        }
                        if (holding.label.isNotBlank()) {
                            TextButton(onClick = {
                                labelText = ""
                                onLabel("")
                                naming = false
                            }) { Text("اسم اصلی", fontSize = 14.sp) }
                        }
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = { labelText = holding.label; naming = false }) {
                            Text("بستن", fontSize = 14.sp, color = muted)
                        }
                    }
                }
            }

            if (type.kind == Kind.CRYPTO) {
                SheetLabel("موجودی رو چطور وارد می‌کنی؟")
                if (walletOptions.isNotEmpty()) {
                    SegmentedChoice(
                        options = listOf(AmountSource.WALLET, AmountSource.MANUAL),
                        selected = source,
                        label = { if (it == AmountSource.WALLET) "خودکار" else "دستی" },
                        enabled = { !walletBusy },
                        onSelect = {
                            source = it
                            adjusting = false
                            localWalletError = null
                            onWalletEdit()
                        },
                    )
                    // One line under the track instead of a caption inside each pill: the
                    // detail only ever describes the chosen one, and printing both put the
                    // answer to a question she had not asked next to the one she had.
                    Text(
                        if (source == AmountSource.WALLET)
                            "موجودی از آدرس عمومی کیف پول خونده می‌شه."
                        else "مقدار رو خودت وارد می‌کنی.",
                        fontSize = 13.sp,
                        lineHeight = 20.sp,
                        color = muted,
                        modifier = Modifier.padding(top = Space.s, start = Space.xs),
                    )
                } else {
                    Surface(
                        shape = RoundedCornerShape(Radius.field),
                        color = MaterialTheme.colorScheme.surfaceContainer,
                    ) {
                        Text(
                            "برای این رمزارز، شبکه‌ای برای خوندن خودکار پیدا نشد. مقدار رو دستی وارد کن.",
                            fontSize = 14.sp,
                            lineHeight = 22.sp,
                            color = muted,
                            modifier = Modifier.padding(Space.l),
                        )
                    }
                }
            }

            if (source == AmountSource.MANUAL) {
                // "چقدر تومان داری؟" is the right question for cash and the wrong one for a
                // house: what is being asked for there is a valuation, and she is the only one
                // who can give it.
                val prompt =
                    if (type.kind == Kind.PROPERTY) "به نظرت چند تومن می‌ارزه؟"
                    else "چقدر ${bidi(type.unitFa)} داری؟"
                SheetLabel(prompt)
                val amountInvalid = manualAmount == null && (text.isNotBlank() || manualSubmitted)
                OutlinedTextField(
                value = text,
                onValueChange = { text = it; manualSubmitted = false },
                singleLine = true,
                isError = amountInvalid,
                visualTransformation = GroupedNumber,
                textStyle = TextStyle(
                    fontFamily = ModamFigures,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                ),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                // A red outline is colour alone; the words say what to fix, and why ذخیره
                // is not available yet.
                supportingText = if (amountInvalid) ({
                    Text(
                        if (text.isBlank()) "مقدار دارایی رو وارد کن."
                        else "این عدد قابل خوندن نیست. فقط عدد وارد کن.",
                    )
                }) else null,
                shape = RoundedCornerShape(Radius.field),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(amountFocus)
                    // The visible prompt above is not programmatically attached; this is.
                    .semantics { contentDescription = prompt },
                )

            // The figure she just typed, in words — the guard against an extra zero.
            if (manualAmount != null && manualAmount % 1.0 == 0.0 && manualAmount >= 1000) {
                Text(
                    "${faWords(manualAmount.toLong())} ${bidi(type.unitFa)}",
                    fontSize = 14.sp,
                    lineHeight = 22.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = Space.s, start = Space.xs, end = Space.xs),
                )
            }

            // Received some, spent some: she types the change and the field above becomes the
            // new total, instead of her doing the sum on paper. ذخیره commits it like any
            // other edit, so there is still exactly one moment money is written.
            if (current != null) {
                if (!adjusting) {
                    TextButton(onClick = { adjusting = true }) {
                        Text("اضافه یا کم کردن", fontSize = 14.sp)
                    }
                } else {
                    val deltaFocus = remember { FocusRequester() }
                    LaunchedEffect(Unit) { deltaFocus.requestFocus() }
                    // A label that stays, not a placeholder that vanishes at the first digit.
                    Text(
                        "مقدار تغییر رو وارد کن",
                        fontSize = 14.sp,
                        color = muted,
                        modifier = Modifier.padding(top = Space.s),
                    )
                    Spacer(Modifier.height(Space.xs))
                    // A delta finer than the asset's own precision is rejected, never
                    // rounded: trimNumber rounds half-up, and half a سکه rounded to a whole
                    // one is money invented — the one direction this app must never err in.
                    val typedDelta = parseAmount(deltaText)?.takeIf { it > 0 }
                    val delta = typedDelta?.takeIf { parseAmount(trimNumber(it, type.dec)) == it }
                    OutlinedTextField(
                        value = deltaText,
                        onValueChange = { deltaText = it },
                        singleLine = true,
                        visualTransformation = GroupedNumber,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        supportingText = if (typedDelta != null && delta == null) ({
                            Text(
                                if (type.dec == 0) "فقط عدد کامل وارد کن."
                                else "حداکثر ${faNumber(type.dec.toDouble())} رقم اعشار وارد کن.",
                            )
                        }) else null,
                        shape = RoundedCornerShape(Radius.field),
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(deltaFocus)
                            .semantics {
                                contentDescription = "مقدار تغییر ${type.unitFa}"
                            },
                    )
                    val base = amount ?: current
                    fun apply(next: Double) {
                        text = trimNumber(next, type.dec)
                        deltaText = ""
                        adjusting = false
                    }
                    // Air between the two opposite intents: an edge mis-tap here flips the
                    // sign of a money adjustment.
                    Row(horizontalArrangement = Arrangement.spacedBy(Space.m)) {
                        TextButton(
                            onClick = { delta?.let { apply(base + it) } },
                            enabled = delta != null,
                        ) { Text("＋ اضافه کن", fontSize = 14.sp, fontWeight = FontWeight.Bold) }
                        TextButton(
                            onClick = { delta?.let { apply(base - it) } },
                            // A holding cannot go below nothing — taking out more than is
                            // there is a typo, not a request.
                            enabled = delta != null && base - delta >= 0,
                        ) { Text("− کم کن", fontSize = 14.sp, fontWeight = FontWeight.Bold) }
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = { adjusting = false; deltaText = "" }) {
                            Text("بستن", fontSize = 14.sp, color = muted)
                        }
                    }
                }
            }

            } else {
                SheetLabel("شبکه رمزارز")
                WalletNetworkChoice(
                    options = walletOptions,
                    selected = selectedNetwork,
                    enabled = !walletBusy,
                    onSelect = {
                        if (selectedNetwork != it) walletAddress = ""
                        selectedNetwork = it
                        localWalletError = null
                        onWalletEdit()
                    },
                )

                SheetLabel("آدرس عمومی کیف پول")
                OutlinedTextField(
                    value = walletAddress,
                    onValueChange = {
                        walletAddress = it.take(128)
                        localWalletError = null
                        onWalletEdit()
                    },
                    singleLine = true,
                    enabled = !walletBusy,
                    isError = localWalletError != null,
                    textStyle = TextStyle(
                        fontSize = 16.sp,
                        textAlign = TextAlign.Left,
                        textDirection = TextDirection.Ltr,
                    ),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                    supportingText = localWalletError?.let { message ->
                        { Text(message) }
                    },
                    shape = RoundedCornerShape(Radius.field),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(walletAddressFocus)
                        .semantics { contentDescription = "آدرس عمومی کیف پول" },
                )
                Text(
                    "فقط آدرس عمومی رو وارد کن. عبارت بازیابی یا کلید خصوصی رو هیچ‌وقت وارد نکن.",
                    fontSize = 13.sp,
                    lineHeight = 20.sp,
                    color = muted,
                    modifier = Modifier.padding(horizontal = Space.xs),
                )
                Text(
                    "برای خوندن موجودی، آدرس به سرویس عمومی همون شبکه فرستاده می‌شه.",
                    fontSize = 12.sp,
                    lineHeight = 19.sp,
                    color = muted,
                    modifier = Modifier.padding(horizontal = Space.xs, vertical = Space.xs),
                )

                if (linkedSelection && current != null) {
                    Spacer(Modifier.height(Space.l))
                    // Full width, like everything else in the sheet. Wrapping its content made
                    // it a narrower card directly above a wider one, which read as two
                    // unrelated objects rather than one answer in two units.
                    Surface(
                        shape = RoundedCornerShape(Radius.card),
                        color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(Modifier.padding(Space.l)) {
                            Text(
                                "موجودی فعلی",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                            BasicText(
                                // faHeld, not the asset's full precision: the row outside this
                                // sheet says ۱۰٬۷۰۹٫۱۳ and this said ۱۰٬۷۰۹٫۱۳۵۶۸۱ — one
                                // number in two lengths reads as two different numbers.
                                text = "${faHeld(current, type.dec)} ${bidi(type.unitFa)}",
                                maxLines = 1,
                                autoSize = TextAutoSize.StepBased(
                                    minFontSize = 18.sp,
                                    maxFontSize = 30.sp,
                                ),
                                modifier = Modifier.padding(top = 2.dp),
                                style = figureStyle(
                                    MaterialTheme.colorScheme.onPrimaryContainer,
                                    FontWeight.ExtraBold,
                                ),
                            )
                            // The same money in Toman, inside the same card rather than in a
                            // second one below it. It is the conversion of the figure above,
                            // not a separate finding.
                            previewRate?.let { rate ->
                                BasicText(
                                    text = "≈ ${faCompact(current * rate, 3, pad = true)} تومان",
                                    maxLines = 1,
                                    autoSize = TextAutoSize.StepBased(
                                        minFontSize = 13.sp,
                                        maxFontSize = 17.sp,
                                    ),
                                    modifier = Modifier.padding(top = Space.xs),
                                    style = figureStyle(
                                        MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                                        FontWeight.SemiBold,
                                    ),
                                )
                            }
                            Text(
                                when {
                                    walletBusy -> "در حال گرفتن موجودی"
                                    walletError != null -> walletError
                                    else -> "آخرین بار: ${faAgo(linkedWallet.updatedAt, System.currentTimeMillis())}"
                                },
                                fontSize = 12.sp,
                                lineHeight = 19.sp,
                                color = if (walletError != null) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.semantics {
                                    if (walletError != null) liveRegion = LiveRegionMode.Polite
                                },
                            )
                        }
                    }
                } else if (walletError != null) {
                    Text(
                        walletError,
                        fontSize = 13.sp,
                        lineHeight = 20.sp,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier
                            .padding(horizontal = Space.xs, vertical = Space.s)
                            .semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
            }

            // Not when the read-balance card is up: that card already carries the Toman line,
            // and the two of them said the same conversion twice, in two differently sized
            // boxes, one under the other.
            val balanceCardShown = source == AmountSource.WALLET && linkedSelection && current != null
            if (amount != null && previewRate != null && !type.valuedInToman && !balanceCardShown) {
                Spacer(Modifier.height(Space.l))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(Radius.card))
                        .background(MaterialTheme.colorScheme.primaryContainer)
                        .padding(Space.l),
                ) {
                    // Same figure the row shows, so it carries the same three decimals — and
                    // shrinks rather than wrapping, since it is one sentence.
                    BasicText(
                        text = "یعنی ${faCompact(amount * previewRate, 3, pad = true)} تومان",
                        maxLines = 1,
                        autoSize = TextAutoSize.StepBased(minFontSize = 14.sp, maxFontSize = 18.sp),
                        style = TextStyle(
                            fontFamily = ModamFigures,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        ),
                    )
                }
            }

            if (!type.valuedInToman) {
                Spacer(Modifier.height(Space.m))
                if (!editingRate) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (rate == null) "نرخ پیدا نشد"
                            else "هر ${bidi(type.unitFa)}: ${faNumber(rate)} تومان" +
                                if (isOverridden) "  (دستی وارد شده)" else "",
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
                    Text("هر ${bidi(type.unitFa)} چند تومان؟", fontSize = 14.sp, color = muted)
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
                            .focusRequester(rateFocus)
                            .semantics { contentDescription = "هر ${type.unitFa} چند تومان؟" },
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(Space.m)) {
                        TextButton(onClick = { onRate(typedRate); editingRate = false }) {
                            Text("ذخیره نرخ", fontSize = 14.sp)
                        }
                        if (isOverridden) {
                            TextButton(onClick = {
                                onRate(null); rateText = ""; editingRate = false
                            }) { Text("برگشت به نرخ خودکار", fontSize = 14.sp) }
                        }
                    }
                }
            }

            if (current != null) {
                Spacer(Modifier.height(Space.m))
                // toggleable on the row: label and switch become one named control, and the
                // words are the hit target too.
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .toggleable(
                            value = !excluded,
                            role = Role.Switch,
                            onValueChange = { on -> onExcluded(!on) },
                        )
                        // With onCheckedChange = null the Switch no longer carries its own
                        // 48dp minimum; the row has to.
                        .heightIn(min = 48.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("توی جمع حساب بشه", fontSize = 15.sp)
                        Text(
                            // e.g. savings she does not want staring back from the total
                            "اگه فعلاً نمی‌خوای توی جمع بیاد، خاموشش کن.",
                            fontSize = 12.sp,
                            color = muted,
                        )
                    }
                    Switch(checked = !excluded, onCheckedChange = null)
                }
            }

            Spacer(Modifier.height(Space.xl))
            Button(
                onClick = {
                    // After the amount, never before: a name is written onto a row by its key,
                    // and while adding there is no such row until this save makes one.
                    if (source == AmountSource.MANUAL) {
                        manualSubmitted = true
                        if (manualAmount != null) close { onSaveManual(manualAmount); nameIt() }
                        else amountFocus.requestFocus()
                    } else {
                        val option = selectedWallet ?: return@Button
                        if (walletAddress.isBlank()) {
                            localWalletError = "آدرس عمومی کیف پول رو وارد کن."
                            walletAddressFocus.requestFocus()
                        } else if (!isWalletAddressFormatValid(option.network, walletAddress)) {
                            localWalletError = "این آدرس با شبکه انتخاب‌شده جور نیست."
                            walletAddressFocus.requestFocus()
                        } else {
                            onSaveWallet(option, walletAddress.trim()) { nameIt(); close(onDismiss) }
                        }
                    }
                },
                enabled = source == AmountSource.MANUAL || (!walletBusy && selectedWallet != null),
                shape = RoundedCornerShape(Radius.pill),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 60.dp),
            ) {
                if (source == AmountSource.WALLET && walletBusy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    Spacer(Modifier.width(Space.s))
                }
                Text(
                    when {
                        source == AmountSource.MANUAL && linkedWallet != null -> "ذخیره مقدار دستی"
                        source == AmountSource.MANUAL -> "ذخیره"
                        linkedSelection -> "گرفتن دوباره موجودی"
                        else -> "چک و ذخیره"
                    },
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                )
            }

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
                        if (confirmDelete) "مطمئنی؟ برای حذف دوباره بزن"
                        else "حذف این دارایی",
                        fontSize = 15.sp,
                        fontWeight = if (confirmDelete) FontWeight.Bold else FontWeight.Normal,
                        // Announced, or the two-tap safeguard is invisible to TalkBack — a
                        // second double-tap deletes with no confirmation ever perceived.
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
            }

            Spacer(Modifier.height(Space.l))
        }
    }
}
