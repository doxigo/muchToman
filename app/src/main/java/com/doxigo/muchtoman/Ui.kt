package com.doxigo.muchtoman

import androidx.lifecycle.compose.collectAsStateWithLifecycle

import android.Manifest
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
 * The screen's name, in one display voice for every root page. Black, not ExtraBold: the
 * heading is the one line on a page allowed to be heavy the way the hero figure is, and six
 * screens each picking their own 28sp was six slightly different apps.
 */
@Composable
internal fun ScreenTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        fontSize = 30.sp,
        fontWeight = FontWeight.Black,
        color = MaterialTheme.colorScheme.onBackground,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier.semantics { heading() },
    )
}

/**
 * The door to writing something down, beside a root page's [ScreenTitle] — دفتر's «تراکنش» and
 * دارایی's «دارایی», one object so the two title lines cannot drift apart.
 *
 * It says the word. A bare «+» in a disc is a guess she has to spend a tap to check, and an
 * action that writes money is not the place to make her guess; the word after the mark costs a
 * few millimetres and answers it outright. The pill is the neutral well the چیپ‌ها below دفتر's
 * title wear rather than the review pill's filled green — two solid fills on one line would both
 * be claiming «this one» — and the mark alone carries the interactive colour, which is what keeps
 * it from reading as a filter.
 *
 * [labelled] is false only where the line genuinely cannot hold the word — دفتر's crowded line.
 * Square then, so it reads as the mark's own disc rather than as a pill that lost its label, and
 * [onClickLabel] becomes its name: a mark says nothing to TalkBack.
 */
@Composable
internal fun TitleAddButton(word: String, onClickLabel: String, onClick: () -> Unit, labelled: Boolean = true) {
    Row(
        Modifier
            .clip(RoundedCornerShape(Radius.pill))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .clickable(role = Role.Button, onClickLabel = onClickLabel, onClick = onClick)
            .then(
                if (labelled) Modifier.heightIn(min = 48.dp).padding(horizontal = Space.l)
                else Modifier.size(48.dp).semantics { contentDescription = onClickLabel },
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        PlusMark(MaterialTheme.colorScheme.primary, size = 20.dp)
        if (labelled) {
            Spacer(Modifier.width(Space.xs))
            Text(
                word,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
        }
    }
}

/** A sheet's question, in the same voice — ten sheets had drifted to three sizes of it. */
@Composable
internal fun SheetTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        fontSize = 26.sp,
        fontWeight = FontWeight.Black,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = modifier.semantics { heading() },
    )
}

/**
 * The one inset from the screen edge. The hero field ignores it on purpose — it is the only
 * thing in the app that runs to the glass — so every other item in the list applies it itself.
 */
internal val edge = PaddingValues(horizontal = Space.xl)

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
        // The transient words live above every page, not inside the tab Scaffold: an undo has
        // to survive her walking into تنظیمات or a pushed screen while the band is still up.
        val notices = remember { TransientNotices() }
        Box(Modifier.fillMaxSize()) {
            AppScreens(vm, state, activity, notices)
            // Never over the lock — the band carries words about her money.
            if (!state.locked) {
                TransientNoticeBand(notices, Modifier.align(Alignment.BottomCenter))
            }
        }
    }
}

/**
 * One transient notice at a time, app-wide: the words, and at most one way to act on them.
 * Posted by the two deletes (undo is their second net — the two-tap arming stays) and by the
 * rescan row's acknowledgement. The newest post takes the slot; a queue of old news is not
 * worth the machinery.
 */
internal class TransientNotices {
    var current by mutableStateOf<TransientNotice?>(null)
        private set
    private var stamp = 0

    fun show(words: String, action: String? = null, onAction: () -> Unit = {}) {
        current = TransientNotice(++stamp, words, action, onAction)
    }

    /** Only the notice that asked leaves — a stale timer must not take down a newer band. */
    fun dismiss(of: TransientNotice) {
        if (current?.id == of.id) current = null
    }
}

internal data class TransientNotice(
    val id: Int,
    val words: String,
    val action: String?,
    val onAction: () -> Unit,
)

/**
 * The band itself: the tab bar's own furniture colours, held to the page by a hairline the way
 * the floor is — no shadow, no island — floating where the floor's top edge sits. It leaves on
 * its own after ~6 seconds; the words are a polite live region so the notice is heard as well
 * as seen, and the one action wears the CTA pill because «برگردون» is genuinely *press this*.
 */
@Composable
private fun TransientNoticeBand(notices: TransientNotices, modifier: Modifier = Modifier) {
    val notice = notices.current
    // Keyed on the id, not on presence: a second delete restarts the six seconds.
    LaunchedEffect(notice?.id) {
        if (notice != null) {
            delay(6_000)
            notices.dismiss(notice)
        }
    }
    // Held through the exit so the band does not blank while sliding away.
    var shown by remember { mutableStateOf(notice) }
    if (notice != null) shown = notice
    AnimatedVisibility(
        visible = notice != null,
        enter = slideInVertically(tween(Motion.medium, easing = Motion.enter)) { it } +
            fadeIn(tween(Motion.medium)),
        exit = slideOutVertically(tween(Motion.fast, easing = Motion.exit)) { it } +
            fadeOut(tween(Motion.fast)),
        modifier = modifier,
    ) {
        shown?.let { n ->
            val shape = RoundedCornerShape(Radius.group)
            Row(
                Modifier
                    .widthIn(max = 640.dp)
                    .padding(edge)
                    .navigationBarsPadding()
                    // Clear of the tab bar's 64dp floor. The pushed pages have no bar, but one
                    // fixed home beats a band that lands somewhere different on every page.
                    .padding(bottom = 64.dp + Space.m)
                    .fillMaxWidth()
                    .clip(shape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
                    .padding(horizontal = Space.l, vertical = Space.m),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    n.words,
                    fontSize = 14.sp,
                    lineHeight = 22.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .weight(1f)
                        // Announced: the band comes and goes on its own schedule.
                        .semantics { liveRegion = LiveRegionMode.Polite },
                )
                n.action?.let { label ->
                    Spacer(Modifier.width(Space.m))
                    PillButton(
                        label,
                        { n.onAction(); notices.dismiss(n) },
                        voice = ButtonVoice.PRIMARY,
                    )
                }
            }
        }
    }
}

/**
 * One phone column on any width: the app installs on tablets too, and a ledger row stretched
 * across 900dp is a line nobody's eye can track back. Content is capped and centred; the
 * paper behind it and the tab bar below stay full-bleed. Under the cap the constraints pass
 * through untouched, so a phone never feels this exists.
 */
@Composable
private fun WidthCap(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .align(Alignment.TopCenter)
                .widthIn(max = 640.dp),
        ) { content() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppScreens(
    vm: AppVm,
    state: UiState,
    activity: FragmentActivity,
    notices: TransientNotices,
) {
    val backupState by vm.backup.collectAsStateWithLifecycle()
    var adding by remember { mutableStateOf(false) }
    // خانه's circle and دارایی's title pill: one door to the picker.
    val addAsset: () -> Unit = { adding = true; vm.refreshStocksForPicker() }
    var editing by remember { mutableStateOf<Editing?>(null) }
    // Saveable, like tab and transactionRef below: these four are *where she is standing*, and
    // a process death that threw her from دسته‌بندی‌ها back to the asset list read as the app
    // restarting itself. (The manifest's ponytail about half-typed sheet text still stands for a
    // process death: the drafts are saveable now, for the lock — see `unlocked` — but the plain
    // flags here that open their sheets are not, so the sheet does not come back to hold them.)
    var settings by rememberSaveable { mutableStateOf(false) }
    // تنظیمات opened from the report's note on what it leaves out, straight onto that choice.
    var exclusionsSheet by remember { mutableStateOf(false) }
    // A page of تنظیمات, not a peer of it: `companion` is only ever read while `settings` is
    // true, so closing it lands back on the settings page she opened it from rather than on
    // whatever tab is underneath both.
    var companion by rememberSaveable { mutableStateOf(false) }
    // Another page of تنظیمات, same one-level rule: closing دسته‌بندی‌ها lands on the settings
    // page that opened it.
    var categoriesPage by rememberSaveable { mutableStateOf(false) }
    var banks by remember { mutableStateOf(false) }
    // One account's page, opened from its row in that sheet — where she is standing, like the pages
    // below, so it outlives a process death.
    var bankPage by rememberSaveable { mutableStateOf<String?>(null) }
    var deck by rememberSaveable { mutableStateOf(false) }
    // The hand-entered transaction sheet, over دفتر — the room where its row will land.
    var addingTxn by remember { mutableStateOf(false) }
    var transactionRef by rememberSaveable { mutableStateOf<String?>(null) }
    // طلب و بدهی: the page of people, and one person over it — one level, like every pushed page
    // here. The sheets are held apart because four places open them: the pages, آینده's door, the
    // hero's strip and a holding's own sheet.
    var loansPage by rememberSaveable { mutableStateOf(false) }
    var loanPerson by rememberSaveable { mutableStateOf<String?>(null) }
    var loanAsk by remember { mutableStateOf<LoanAsk?>(null) }
    val loanType: (String) -> AssetType = { resolveType(it, state.coins, state.stocks) }

    // One tab, not three booleans. Three could be true at once, and were: every screen was an
    // overlay with its own «بستن», so an overlay opened from an overlay left her somewhere the
    // only way out of was the system back button. A single value cannot represent that state.
    // First of [tabs], not Tab.HOME: in the lite edition there is no home to start on, and the
    // one tab it has is where it opens.
    var tab by rememberSaveable { mutableStateOf(tabs.first()) }
    val portfolio = tab == Tab.ASSETS
    // دارایی as a room of its own: a title line and the compact hero. Not in the lite edition —
    // there دارایی is the only screen, so its card is the one place the words and the exact
    // digits can live, and the greeting is the only door to تنظیمات.
    val compact = portfolio && tabs.size > 1

    // Which report, and which month of it. Both live up here because every door into گزارش‌ها
    // says which of the two reports it is a door to — the asset change pill opens دارایی, the
    // month band on the field opens دخل و خرج — and the destination cannot answer that itself.
    //
    // The month is a start day rather than a [ReportMonth] so it survives a process death in a
    // Bundle, and null means «the month containing today»: an app reopened tomorrow must not
    // still be pinned to yesterday's month because it was once the current one.
    var reportMode by rememberSaveable { mutableStateOf(ReportMode.CASH_FLOW) }
    var reportMonth by rememberSaveable { mutableStateOf<Long?>(null) }
    // How far back دخل و خرج reaches from that month. A door into the report opens on this month,
    // so it resets with the month: arriving from the field's month band and finding a year's
    // figures because that is where she left the picker last week is the door having lied.
    var reportSpan by rememberSaveable { mutableStateOf(ReportSpan.MONTH) }
    fun openReport(mode: ReportMode) {
        reportMode = mode
        reportMonth = null
        reportSpan = ReportSpan.MONTH
        tab = Tab.REPORT
    }

    // Whether a budget note posted right now would actually reach her. Local state rather than
    // something in UiState, because the answer changes *outside* the app — in Android's own
    // settings — and a value re-read on every ON_START is one that cannot be stale on screen.
    val context = LocalContext.current
    var canNote by remember { mutableStateOf(canNotify(context)) }
    val askNote = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        canNote = granted
        // Denied twice is denied for good: Android stops showing the dialog and hands back a
        // refusal immediately, so the only remaining fix is the page that has the switch on it.
        if (!granted) openNotificationSettings(context)
    }
    val askNotify = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            askNote.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            // Below 33 there is nothing to ask for — notifications are on unless she switched them
            // off, and only the system's own page can switch them back.
            openNotificationSettings(context)
        }
    }

    // A tapped budget note lands here. Every overlay is closed on the way, or the tab underneath
    // changes behind a sheet and the notification reads as having done nothing.
    LaunchedEffect(state.openTab) {
        state.openTab?.let { wanted ->
            settings = false
            companion = false
            categoriesPage = false
            deck = false
            transactionRef = null
            loansPage = false
            loanPerson = null
            bankPage = null
            tab = wanted
            if (state.openAddTxn) addingTxn = true
            vm.consumeOpenTab()
        }
    }

    // And a tapped filing note here, which wants the deck rather than a tab. The tab underneath is
    // set as well as the deck: closing the deck has to leave her on دفتر — the room the deck
    // belongs to — and not back on whatever she was looking at last week.
    //
    // Waited on `ready`, and the deck only opens when something actually asks for her. A note can
    // be about a transaction the rules already filed — that is the note reporting an answer, not
    // asking a question — and opening the deck on it landed her on an empty room announcing that
    // nothing needs her. The transaction the note was about is at the top of دفتر, so دفتر is
    // where its tap goes.
    LaunchedEffect(state.openDeck, state.ledger.ready) {
        if (state.openDeck && state.ledger.ready) {
            settings = false
            companion = false
            categoriesPage = false
            transactionRef = null
            loansPage = false
            loanPerson = null
            bankPage = null
            tab = Tab.LEDGER
            deck = state.ledger.review.isNotEmpty()
            vm.consumeOpenDeck()
        }
    }

    LaunchedEffect(state.family.pendingPairing, state.family.pendingRejoin) {
        // Scanning the invite must still land on the join card — or, on a phone that already
        // has a household, the replace question — and that card is two taps deep now. A pairing
        // link can also arrive at the lite build — the intent filter is in the shared manifest
        // — and that edition has no household to join it to.
        if (!BuildConfig.LITE &&
            (state.family.pendingPairing != null || state.family.pendingRejoin != null)
        ) {
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
                Lifecycle.Event.ON_START -> {
                    vm.refreshIfStale()
                    // She may have come back from Android's own notification settings, which is
                    // the one way this answer changes without the app being told.
                    canNote = canNotify(context)
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // The clock and the signal icons simply follow the theme now: the hero is a card floating
    // on the background rather than a field running under the glass, so every screen starts at
    // the paper. The one full-bleed forest surface left is the lock screen, which wants light
    // icons in both themes.
    val listState = rememberLazyListState()
    // Why the home list's state is hoisted this far up, generalised for the tabs that are their
    // own screens. Every pushed page above — a transaction, the deck, تنظیمات — returns before
    // the Scaffold, which takes the tab underneath out of composition and its state with it: the
    // scroll, the lens, the category filter she set. Back then landed her at the top of a ledger
    // that had quietly widened to «همه», which reads as the app having lost her place. The holder
    // keeps each tab's saveable state while it is off screen and hands it back on the way in.
    val screens = rememberSaveableStateHolder()
    // The other holder: what [AppScreens] drops while locked, keyed by the one thing it ever holds.
    val unlocked = rememberSaveableStateHolder()
    val lightScheme = MaterialTheme.colorScheme.background.luminance() > 0.5f
    LaunchedEffect(state.locked, lightScheme) {
        WindowCompat.getInsetsController(activity.window, activity.window.decorView).apply {
            isAppearanceLightStatusBars = !state.locked && lightScheme
            isAppearanceLightNavigationBars = lightScheme
        }
    }

    if (state.locked) {
        val open = { promptUnlock(activity, onNothingToCheck = { vm.setLockEnabled(false) }) { vm.unlock() } }
        // Prompt immediately; the button is there for when it is dismissed.
        LaunchedEffect(Unit) { open() }
        Surface(color = MaterialTheme.colorScheme.background) {
            LockScreen(onUnlock = open)
        }
        return
    }

    // Everything past the lock, saved as it leaves. relock() runs on every ON_STOP and the lock
    // returns before any page or sheet, so every sheet she had open — reopened after the unlock by
    // the plain flags above, which outlive the lock — used to come back blank, its half-typed figure
    // gone. Not an overlay that keeps it all composed instead: a ModalBottomSheet is a window of its
    // own and would stay readable over one. The drafts in the sheets are rememberSaveable for this;
    // passphrases stay plain on purpose. The body keeps its indent so the wrap moves no lines.
    unlocked.SaveableStateProvider("unlocked") {
    // Before anything else this app has to show, and exactly once in its life. Below the lock
    // rather than above it only for tidiness — a phone that has a lock on it has been used, and a
    // phone that has been used is already past this. No BackHandler: the way out is «الان نه»,
    // which is on the screen, and a back gesture that dropped her into an app with no permissions
    // and no explanation is the state this screen exists to prevent.
    if (!state.onboarded) {
        OnboardingScreen(
            onSmsGranted = vm::setSmsEnabled,
            onDone = vm::finishOnboarding,
        )
        return@SaveableStateProvider
    }

    // Not a return: the sheet floats over whichever screen comes next. Below the lock, so a
    // locked phone shows nothing of the app, the report included, until it is opened.
    state.crashReport?.let { CrashSheet(it, onSend = vm::sendCrashReport, onDismiss = vm::dropCrashReport) }

    if (categoriesPage) {
        // Back steps to تنظیمات, the page it was opened from — same one-level rule as companion.
        BackHandler { categoriesPage = false }
        CategoriesScreen(
            categories = state.ledger.managedCategories,
            onAdd = vm::addCategory,
            onEdit = vm::editCategory,
            onArchive = vm::toggleCategoryArchived,
            onBack = { categoriesPage = false },
        )
        return@SaveableStateProvider
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
            onScan = vm::acceptPairing,
            onRejoin = vm::confirmRejoin,
            onDismissRejoin = vm::dismissRejoin,
            onNameChange = vm::setFamilyName,
            onAvatarChange = vm::setFamilyAvatar,
            onShareSmsChange = vm::setFamilySmsSharing,
            onShareAssetsChange = vm::setFamilyAssetSharing,
            // A note switch: switching it on is the moment to ask for notifications, as the
            // reminder's does.
            onNotesChange = { on ->
                vm.setFamilyNotes(on)
                if (on && !canNote) askNotify()
            },
            banks = remember(state.bankAccounts) { state.bankAccounts.map { it.bank }.distinct() },
            onExcludedBankToggle = vm::toggleFamilyExcludedBank,
            onInvite = vm::inviteDevice,
            onSync = vm::syncFamily,
            onLeave = vm::leaveFamily,
            onRenew = vm::renewFamily,
            contributions = remember(state.ledger.entries) {
                state.ledger.entries.filterNot { it.duplicate }
                    .groupingBy { it.ownerMemberId }
                    .eachCount()
            },
            onBack = { companion = false },
        )
        return@SaveableStateProvider
    }

    if (settings) {
        // The system back button must step back to the list, not out of the app.
        BackHandler { settings = false; exclusionsSheet = false }
        SettingsScreen(
            categories = state.ledger.categories,
            reportExcluded = state.reportExcluded,
            onReportExcluded = vm::setReportExcluded,
            exclusionsSheet = exclusionsSheet,
            onExclusionsSheet = { exclusionsSheet = it },
            name = state.name,
            themeMode = state.themeMode,
            textSize = state.textSize,
            lockEnabled = state.lockEnabled,
            widgetLock = state.widgetLock,
            smsEnabled = state.smsEnabled,
            bankAccounts = state.bankAccounts,
            disabledBanks = state.disabledBanks,
            family = state.family,
            activity = activity,
            onCompanion = { companion = true },
            onNameChange = vm::setName,
            onThemeChange = vm::setThemeMode,
            onTextSizeChange = vm::setTextSize,
            onShowRialChange = vm::setShowRial,
            installmentReminder = state.installmentReminder,
            // Switching it on is the moment to ask for notifications if they are off, as the
            // messages switch does for its own permissions.
            onInstallmentReminderChange = { days ->
                vm.setInstallmentReminder(days)
                if (days >= 0 && !canNote) askNotify()
            },
            quipTone = state.quipTone,
            // A voice is only heard in notifications, so picking one is the moment to ask for them.
            onQuipToneChange = { tone ->
                vm.setQuipTone(tone)
                if (tone != QuipTone.PLAIN && !canNote) askNotify()
            },
            onSmsChange = vm::setSmsEnabled,
            onBankChange = vm::setBankEnabled,
            onAddBank = vm::addManualBank,
            onLockChange = { on ->
                // Confirm identity before arming it, so the lock is never switched on by
                // someone who could not then get back in.
                if (on) promptUnlock(activity) { vm.setLockEnabled(true) }
                else vm.setLockEnabled(false)
            },
            onWidgetLockChange = vm::setWidgetLock,
            onCategories = { categoriesPage = true },
            onClearCache = vm::clearCaches,
            // The callback carries the acknowledgement too: only this call site holds the
            // scaffold-level notices, which is why it is a parameter and not a VM fetch.
            onRescanInbox = {
                vm.rescanInbox()
                notices.show("در حال بازخوانی…")
            },
            onBack = { settings = false; exclusionsSheet = false },
        )
        return@SaveableStateProvider
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
                onNote = vm::setNote,
                notes = state.ledger.notes,
                onDelete = { entry ->
                    vm.deleteTxn(entry)
                    // The undo is a second net under the two-tap arming, not a replacement.
                    val deletedRef = entry.txn.ref
                    notices.show("تراکنش پاک شد", "برگردون") { vm.restoreTxn(deletedRef) }
                },
                onCreateCategory = vm::addCategory,
                onEdit = vm::editTxn,
                onRevertEdits = vm::revertTxnEdits,
                members = state.family.members,
                onSplit = vm::splitTxn,
                onLearnSimilar = vm::learnSimilar,
                installments = state.ledger.installments,
                onInstallmentPayment = (vm::setInstallmentPayment).takeIf { installmentPayable(entry, state.ledger.mineId) },
                onCreateInstallment = (vm::addInstallmentFrom).takeIf { installmentPayable(entry, state.ledger.mineId) },
                loanViews = state.loanViews,
                loanLinks = state.ledger.loanLinks,
                loanType = loanType,
                onLoanLink = (vm::setLoanLink).takeIf { loanLinkable(entry, state.ledger.mineId) },
                onCreateLoanPerson = (vm::addLoanPersonFrom).takeIf { loanLinkable(entry, state.ledger.mineId) },
            )
            return@SaveableStateProvider
        }
    }

    // Below the transaction page, so a row opened from a person's trail comes back to that person.
    if (loansPage || loanPerson != null) {
        val person = loanPerson?.let { id -> state.loanViews.firstOrNull { it.person.id == id } }
        if (person != null) {
            BackHandler { loanPerson = null }
            LoanPersonScreen(
                view = person,
                type = loanType,
                onEdit = { loanAsk = LoanAsk.EditPerson(person.person.id) },
                onMove = { giving -> loanAsk = LoanAsk.Move(person.person.id, giving) },
                onOpenEntry = { transactionRef = it.txn.ref },
                holdings = state.holdings,
                onEditMove = { move, rial, amount -> vm.editLoanMove(move.id, rial, amount) },
                onDeleteMove = { move ->
                    vm.deleteLoanMove(move.id)?.let { gone -> notices.show("حذف شد", "برگردون") { vm.restoreLoanMove(gone) } }
                },
                afterEdit = { move, edited -> state.loanViewIf(move.personId, add = edited, drop = move) },
                // A person carries every line she wrote about them, so their delete leaves an undo.
                onDelete = {
                    vm.deleteLoanPerson(person.person.id)?.let { (gone, moves) ->
                        loanPerson = null
                        notices.show("${gone.name} حذف شد", "برگردون") { vm.restoreLoanPerson(gone, moves) }
                    }
                },
                onBack = { loanPerson = null },
            )
        } else {
            BackHandler { loansPage = false; loanPerson = null }
            LoansScreen(
                views = state.loanViews,
                totals = state.loanTotals,
                type = loanType,
                onOpen = { loanPerson = it },
                onAdd = { loanAsk = LoanAsk.AddPerson },
                onBack = { loansPage = false; loanPerson = null },
            )
        }
        LoanAsks(
            ask = loanAsk, state = state, vm = vm, notices = notices, type = loanType,
            onClose = { loanAsk = null },
            onPerson = { loansPage = true; loanPerson = it },
        )
        return@SaveableStateProvider
    }

    // Opened from the accounts sheet, which goes as the page comes — the PWA's openPage from a sheet
    // does the same — so back lands where the sheet was opened from, as it does after a person made in
    // a loan sheet opens their page.
    bankPage?.let { key ->
        val account = countedBankAccounts(state.bankAccounts, state.smsEnabled, state.notified)
            .firstOrNull { it.key == key }
        if (account == null) {
            // Forgotten, or no longer read: there is nothing left to stand on.
            LaunchedEffect(key) { bankPage = null }
            return@let
        }
        BackHandler { bankPage = null }
        BankPage(
            account = account,
            off = account.bank in state.disabledBanks,
            onAnchor = { balance -> vm.setBankBalance(key, balance) },
            onToggle = { on -> vm.setBankEnabled(account.bank, on) },
            onOpenSms = account.sender.takeIf { it.isNotBlank() }?.let { sender -> { openSmsThread(context, sender) } },
            onForget = {
                bankPage = null
                vm.forgetBankAccount(key)
            },
            onBack = { bankPage = null },
        )
        return@SaveableStateProvider
    }

    if (deck) {
        BackHandler { deck = false }
        ReviewDeck(
            ledger = state.ledger,
            onDecide = { entry, categoryId, always ->
                vm.categorise(entry, categoryId, always)
            },
            onWorthIt = vm::answerWorthIt,
            onLoadSource = vm::sourceOf,
            onDone = { deck = false },
            onAutoFile = vm::categoriseAll,
            onCreateCategory = vm::addCategory,
            onNote = vm::setNote,
            onInstallmentPayment = vm::setInstallmentPayment,
            onCreateInstallment = vm::addInstallmentFrom,
            loanViews = state.loanViews,
            loanType = loanType,
            onLoanLink = vm::setLoanLink,
            onCreateLoanPerson = vm::addLoanPersonFrom,
        )
        return@SaveableStateProvider
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
    // خانه's two readings of the total that the month cannot give — see [DollarLensCard] and
    // [WhereMoneyIs]. Hers alone, like the change pill: the history never recorded the household's.
    val lens = remember(state.history, state.rateHistory, state.totals.toman, effective["usd"]) {
        dollarLens(
            state.history, state.rateHistory, System.currentTimeMillis() / DAY_MS,
            state.totals.toman, effective["usd"],
        )
    }
    val composition = remember(state.listHoldings, state.coins, effective, state.stocks) {
        compositionByKind(state.listHoldings, state.coins, effective, state.stocks)
    }
    val recent = remember(state.ledger.entries) { recentEntries(state.ledger.entries) }
    // آینده's next two weeks — or the reminder's own window, where she has set a longer one.
    val due = remember(state.ledger, state.bankAccounts, state.installmentReminder) {
        dueSoon(
            state.ledger.cheques, state.ledger.installments, state.bankAccounts,
            tehranDay(System.currentTimeMillis()), state.installmentReminder,
            days = maxOf(14, state.installmentReminder),
        )
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
            // them a green slab against a green slug, and the bar is where she looks anyway.
            if (tabs.size > 1) {
                TabBar(
                    selected = tab,
                    ledgerBadge = state.ledger.review.size,
                    // The bar's گزارش opens دخل و خرج: دارایی already has a root tab of its own,
                    // so opening the reports on the asset side would be the second door to a
                    // room she is already standing next to.
                    onSelect = { if (it == Tab.REPORT) openReport(ReportMode.CASH_FLOW) else tab = it },
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
        // Keyed to the tab so the ledger comes back as she left it: the same rows under the
        // same lens, at the same offset. See [screens].
        screens.SaveableStateProvider(Tab.LEDGER) {
          WidthCap {
            TimelineScreen(
              ledger = state.ledger,
              bottomInset = pad.calculateBottomPadding(),
              // Only where there is something to be quiet about, exactly as on آینده: a ledger that
              // fills itself is the only one that can have a transaction land while she is away.
              notifyBlocked = state.readsBanks && !canNote,
              onReview = { deck = true },
              onAskNotify = askNotify,
              onOpen = { transactionRef = it.txn.ref },
              onAddTxn = { addingTxn = true },
              syncing = state.family.syncing,
              // Silent for the family screen's state, but the pull itself answers with one toast —
              // nothing new, n new, or no connection — because an empty result and a dead network
              // would otherwise both look like the spinner going away.
              onSync = ({ vm.pullFamilySync() }).takeIf { state.family.paired },
              accounts = state.bankAccounts,
            )
          }
        }
        return@Scaffold
      }
      if (tab == Tab.BUDGET) {
        screens.SaveableStateProvider(Tab.BUDGET) {
          WidthCap {
            BudgetScreen(
              budgets = state.ledger.budgets,
              goals = state.ledger.goals,
              categories = state.ledger.categories,
              // The same set دخل و خرج reads — the figures on these cards were measured against
              // it, and the total's sheet says so in words. See [budgetTotalNoteFa].
              excluded = state.reportExcluded,
              // Only ever raised where there is something to be quiet about: a phone with no budget
              // has nothing to notify her of, and asking for the permission then would be the nag
              // the first-run sheet refuses to be.
              notifyBlocked = (
                  state.ledger.budgets.isNotEmpty() ||
                      (
                          state.installmentReminder >= 0 &&
                              (state.ledger.installments.any { !it.done } || state.ledger.cheques.isNotEmpty())
                          )
                  ) && !canNote,
              // Paired, not «has a member id»: an unpaired phone keeps its identity from a
              // household it has left, and offering to share with it would be offering to share
              // with nobody.
              hasHousehold = state.family.paired,
              onAddBudget = vm::addBudget,
              onEditBudget = vm::editBudget,
              onKeepBudget = vm::keepBudget,
              onAddGoal = vm::addGoal,
              onEditGoal = vm::editGoal,
              installments = state.ledger.installments,
              onAddInstallment = vm::addInstallment,
              onInstallmentPayment = vm::setInstallmentPayment,
              cheques = state.ledger.cheques,
              bankAccounts = state.bankAccounts,
              reminderDays = state.installmentReminder,
              onSaveCheque = vm::saveCheque,
              onPassCheque = { id ->
                  vm.setChequePassed(id, true)
                  notices.show("چک پاس شد", "برگردون") { vm.setChequePassed(id, false) }
              },
              loans = state.loanTotals,
              loanPeople = state.loans.people.size,
              onOpenLoans = { loansPage = true },
              onAddLoan = { loanAsk = LoanAsk.AddPerson },
              onDelete = vm::deleteGoal,
              onAskNotify = askNotify,
              bottomInset = pad.calculateBottomPadding(),
            )
          }
        }
        return@Scaffold
      }
      if (tab == Tab.REPORT) {
        val composition = remember(state.listHoldings, state.coins, state.effective, state.stocks) {
            compositionByKind(state.listHoldings, state.coins, state.effective, state.stocks)
        }
        // One walk over the ledger for the whole report: the totals, the six bars, the category
        // lists and every sentence come out of it together, so the month she taps and the figure
        // she reads cannot be two different months.
        val cash = remember(
            state.ledger,
            state.bankToman,
            reportMonth,
            reportSpan,
            state.reportExcluded,
            state.rateHistory,
        ) {
            val today = tehranDay(System.currentTimeMillis())
            buildCashFlow(
                entries = state.ledger.entries,
                liquidRial = Math.round(state.bankToman * 10.0),
                today = today,
                selected = reportMonth?.let { reportMonthOf(it) } ?: reportMonthOf(today),
                span = reportSpan,
                // The exclusion set is the one gate now: قرض و همسر ride it as the shipped
                // default rather than through a second mechanism of their own.
                countPassThrough = true,
                excluded = state.reportExcluded,
                // The same remembered day the month above was read from — «۱ هفته» is the one
                // span that needs the day itself.
                anchorDay = reportMonth,
                mineId = state.ledger.mineId,
                rateHistory = state.rateHistory,
            )
        }
        // Her «می‌ارزید؟» answers, over exactly the window the rest of the report reads — a
        // summary that ignored the month picker would be the one section describing different
        // months from every figure around it.
        val worthIt = remember(cash, state.ledger) {
            worthItSummary(
                state.ledger.entries.filter { it.txn.day in cash.range },
                state.ledger.worthIt,
            )
        }
        screens.SaveableStateProvider(Tab.REPORT) {
          WidthCap {
            ReportScreen(
              history = state.history,
              current = state.totals.toman,
              composition = composition,
              cash = cash,
              smsEnabled = state.readsBanks,
              mode = reportMode,
              onMode = { reportMode = it },
              onWindow = { day, span -> reportMonth = day; reportSpan = span },
              bottomInset = pad.calculateBottomPadding(),
              categories = state.ledger.categories,
              excluded = state.reportExcluded,
              // Chosen in تنظیمات, which opens on the choice: the report only says what it leaves out.
              onEditExclusions = { exclusionsSheet = true; settings = true },
              // Paired is what makes the exclusion set a household setting — the sheet says so.
              householdShared = state.family.paired,
              // The one figure off this screen that the same set governs — see [budgetRows].
              capsTotal = state.ledger.budgets.any { it.total },
              // Today's rate, for the window she is standing in. Every window that is already
              // over is frozen at the rates recorded while it ran — see [ReportRange.usdRate].
              usdRate = effective["usd"],
              rateHistory = state.rateHistory,
              worthIt = worthIt,
              entries = state.ledger.entries,
              // The drill-down's rows open the transaction's own page; closing it lands back on
              // this report, because the tab underneath never moved.
              onOpenEntry = { transactionRef = it.txn.ref },
              // The bar is the way out wherever there is one. The lite edition has no bar and
              // still opens this from the گزارش button on its field, so there it keeps the
              // «برگشت» it had as an overlay — see [tabs].
              onBack = ({ tab = tabs.first() }).takeIf { tabs.size == 1 },
              inUsd = state.reportUsd,
              onUsd = vm::setReportUsd,
            )
          }
        }
        return@Scaffold
      }

      Box(Modifier.fillMaxSize()) {
        // Pulling the list down refetches, exactly like tapping the freshness line on the card —
        // the line stays, because a labelled control is still the one she can be told about by
        // phone. The pull indicator belongs to the pull alone: init and refreshIfStale() also set
        // loading, and ungated they dropped an uninvited spinner over the header on every
        // cold open. The line's own «در حال تازه کردن…» is the receipt for those fetches.
        var pulled by remember { mutableStateOf(false) }
        LaunchedEffect(state.refreshing) { if (!state.refreshing) pulled = false }
        val pullState = rememberPullToRefreshState()
        WidthCap {
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
                    containerColor = Hero.field,
                    color = Hero.accent,
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
            item(key = "top") {
                if (compact) {
                    // دارایی is a room like دفتر and آینده, so it opens the way they do: its name,
                    // and the one thing it writes beside it. Same insets as دفتر's title line, so
                    // the title stays put as she moves between the tabs.
                    Row(
                        Modifier
                            .statusBarsPadding()
                            .padding(horizontal = Space.xl, vertical = Space.m)
                            .fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(Space.s),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ScreenTitle("دارایی", modifier = Modifier.weight(1f))
                        TitleAddButton("دارایی", "اضافه کردن دارایی", onClick = addAsset)
                    }
                } else {
                    // Her, above the money, on the paper — the greeting is the door to تنظیمات,
                    // the way every phone on the planet gets you to your own account. خانه writes
                    // one thing, and it writes it where every room does: the add pill at the end of
                    // its top line. Cash paid at the till belongs to no tab, so it stays one tap
                    // from here; an asset is دارایی's to add. Not in the lite edition, which keeps
                    // no ledger to write in.
                    Row(
                        Modifier
                            .statusBarsPadding()
                            .padding(edge)
                            .padding(top = Space.s)
                            .fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        HomeTopBar(
                            name = state.name,
                            onSettings = { settings = true },
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        if (!portfolio) {
                            Spacer(Modifier.width(Space.s))
                            TitleAddButton("تراکنش", "تراکنش دستی", onClick = { addingTxn = true })
                        }
                    }
                }
            }

            if (backupReminderDue(
                    backupState.reminderEnabled, backupState.lastExportAt, System.currentTimeMillis(),
                    holdsCodes = backupState.holdsCodes,
                )
            ) {
                item(key = "backup-reminder") {
                    // A door, not a caption: it leads to تنظیمات, so it wears the card and the
                    // chevron every other door does. As a line of green text it read as a
                    // greeting under her name.
                    Row(
                        Modifier
                            .padding(edge)
                            .padding(top = Space.s)
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(Radius.card))
                            .background(MaterialTheme.colorScheme.surface)
                            .clickable(role = Role.Button) { settings = true }
                            .padding(start = Space.l, end = Space.s, top = Space.m, bottom = Space.m),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "وقت پشتیبان جدیده. از تنظیمات یک فایل پشتیبان بساز.",
                            fontSize = 14.sp,
                            lineHeight = 22.sp,
                            modifier = Modifier.weight(1f),
                        )
                        Icon(
                            Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            // The answer, as the one deep-green object on the page: a card, not a field. The
            // paper around it is what makes it read as *the* thing rather than as wallpaper.
            item(key = "hero") {
                Box(Modifier.padding(edge).padding(top = Space.s)) {
                    HeroCard(
                        state = state,
                        usdRate = effective["usd"],
                        onReport = { openReport(it) },
                        portfolio = portfolio,
                        onFamilyTotal = vm::setFamilyTotal,
                        onLoans = { loansPage = true },
                        compact = compact,
                        onRefresh = vm::refreshAll,
                    )
                }
            }

            // The circles stay where a page has no title line to hold its verbs: the lite edition's
            // only screen («اضافه کردن», and «گزارش», its one door to the report), and خانه before she
            // holds anything, where adding the first asset is the one thing the page can ask for.
            // Otherwise خانه is a reading — its one verb is the pill on the greeting row, and the
            // asset is دارایی's to add.
            val firstAsset = !portfolio && state.listHoldings.isEmpty()
            if (!compact && (portfolio || firstAsset)) item(key = "actions") {
                // Fixed cells clustered to the centre, not thirds of the screen: two circles
                // spread to three slots' positions read as a row with a button missing, and
                // the sibling heroes have to share one rhythm whatever their count.
                Row(
                    Modifier.padding(edge).padding(top = Space.l).fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Space.s, Alignment.CenterHorizontally),
                ) {
                    val cell = Modifier.width(108.dp)
                    ActionCircle(
                        // Alone on خانه, beside the greeting's «+ تراکنش», «اضافه کردن» would not say what
                        // it adds.
                        label = if (firstAsset) "اضافه کردن دارایی" else "اضافه کردن",
                        icon = Icons.Rounded.Add,
                        onClick = addAsset,
                        modifier = cell,
                    )
                    if (tabs.size == 1) {
                        // The lite edition has no bar, so this is the report's only door.
                        ActionCircle(
                            label = "گزارش",
                            onClick = { openReport(ReportMode.ASSETS) },
                            modifier = cell,
                        ) { tint -> BarsIcon(tint) }
                    }
                    // No «تازه کردن» circle: the freshness line on the card above is that control,
                    // and the pull on this list is its gesture.
                }
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
                // خانه is the app's index: under the total, one section per tab, in the bar's own
                // order, each headed by that tab's mark and name. The head is the door to the tab and
                // what sits under it is that tab at a glance. A tab with nothing to say yet has no
                // section, rather than a heading over an empty space — except گزارش, whose quiet
                // start is the thing to do on a ledger that has nothing in it.
                val story = state.story
                val waiting = state.ledger.review.size

                // دفتر: the newest rows, and the review queue on the head's line — the pill دفتر's
                // own title line carries, opening the same deck.
                if (recent.isNotEmpty()) {
                    item(key = "tab_ledger") {
                        Column(Modifier.padding(edge).padding(top = Space.xxl)) {
                            TabSectionHead(Tab.LEDGER, onOpen = { tab = Tab.LEDGER }) {
                                if (waiting > 0) ReviewPill(waiting) { deck = true }
                            }
                            RecentActivity(recent, onOpen = { transactionRef = it.txn.ref })
                        }
                    }
                }
                // آینده: what falls due soon, with the warnings آینده's own cards carry, and the caps
                // furthest along. The one thing asking for her used to be a card of its own here;
                // now it is the row it is about, said in that row.
                if (due.isNotEmpty() || state.ledger.budgets.isNotEmpty()) {
                    item(key = "tab_future") {
                        Column(Modifier.padding(edge).padding(top = Space.xxl)) {
                            TabSectionHead(Tab.BUDGET, onOpen = { tab = Tab.BUDGET })
                            FutureSummary(due, state.ledger.budgets, onOpen = { tab = Tab.BUDGET })
                        }
                    }
                }
                // دارایی: the total against the dollar, and what it is made of — with what its
                // Toman share cost. The cards open the asset report, where both are drawn in full.
                if (lens != null || composition.size > 1) {
                    item(key = "tab_assets") {
                        Column(Modifier.padding(edge).padding(top = Space.xxl)) {
                            TabSectionHead(Tab.ASSETS, onOpen = { tab = Tab.ASSETS })
                            Spacer(Modifier.height(Space.s))
                            lens?.let { DollarLensCard(it, onOpen = { openReport(ReportMode.ASSETS) }) }
                            if (composition.size > 1) {
                                if (lens != null) Spacer(Modifier.height(Space.m))
                                WhereMoneyIs(
                                    composition,
                                    onOpen = { openReport(ReportMode.ASSETS) },
                                    lens = lens,
                                    usdNow = effective["usd"],
                                )
                            }
                        }
                    }
                }
                // گزارش: the month — the cash-flow report's opening paragraph and the door to the rest
                // of it — and the one sentence worth leading with, its «why» left to the report.
                // Nothing recorded yet is not a status but a thing to do: the quiet start.
                item(key = "tab_report") {
                    Column(Modifier.padding(edge).padding(top = Space.xxl)) {
                        TabSectionHead(Tab.REPORT, onOpen = { openReport(ReportMode.CASH_FLOW) })
                        Spacer(Modifier.height(Space.s))
                        if (story.month.transactions > 0) {
                            MonthFlow(story, onOpen = { openReport(ReportMode.CASH_FLOW) })
                        } else {
                            QuietStart(state.readsBanks)
                        }
                        story.headline?.let {
                            Spacer(Modifier.height(Space.m))
                            InsightCard(it)
                        }
                    }
                }
                if (state.totals.missing.isNotEmpty()) {
                    item(key = "missing") {
                        MissingNote(state.totals.missing, state.coins, state.stocks)
                    }
                }
            } else if (state.listHoldings.isEmpty()) {
                item { EmptyHint(pill = compact) }
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

            // What the family shares back, under her own inventory: one band per member, their
            // names and their prices exactly as sent. Absent entirely until somebody shares.
            if (portfolio && state.familyAssets.isNotEmpty()) {
                state.familyAssets.forEach { shared ->
                    item(key = "family_asset_head_${shared.memberId}") {
                        SectionHead(
                            title = "دارایی ${shared.name}",
                            subtotal = shared.totalToman.takeIf { shared.items.size > 1 },
                            modifier = Modifier.animateItem(),
                        )
                    }
                    item(key = "family_asset_${shared.memberId}") {
                        FamilyAssetBand(shared, Modifier.animateItem())
                    }
                }
            }
        }
        }
        }

        // The list scrolls under a transparent status bar, and its headings need ground to
        // slide beneath rather than colliding with the clock. The strip is the background's
        // own colour, so at rest it is invisible and mid-scroll it is simply the paper
        // continuing behind the glass.
        Box(
            Modifier
                .fillMaxWidth()
                .windowInsetsTopHeight(WindowInsets.statusBars)
                .background(MaterialTheme.colorScheme.background),
        )
      }
    }

    // At this level rather than inside دفتر: the sheet opens from the ledger's own header and
    // from the pill on خانه's greeting row — paying cash at the till is not a moment she is standing in
    // any particular tab for — and either way its row lands in the same book.
    if (addingTxn) {
        ManualTxnSheet(
            categories = state.ledger.categories,
            categoryUse = state.ledger.categoryUse,
            notes = state.ledger.notes,
            onSave = { rial, categoryId, merchant, note, at ->
                vm.addManualTxn(rial, categoryId, merchant, note, at)
                addingTxn = false
            },
            onDismiss = { addingTxn = false },
        )
    }

    if (banks) {
        BankSheet(
            accounts = countedBankAccounts(state.bankAccounts, state.smsEnabled, state.notified),
            reads = state.readsBanks,
            disabled = state.disabledBanks,
            onOpen = { key -> banks = false; bankPage = key },
            onAddNumber = vm::addBankNumber,
            loadSenders = vm::loadSenderCandidates,
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
            onDelete = {
                // The whole Holding, captured before the removal — amount, label, wallet link
                // and the set-aside flag are what «برگردون» has to put back.
                held?.let { h ->
                    vm.removeHolding(h.key)
                    notices.show("دارایی پاک شد", "برگردون") { vm.reinstateHolding(h) }
                }
                editing = null
            },
            onRate = { r -> vm.setOverride(typeId, r) },
            onLabel = { name -> vm.setLabel(key, name) },
            onDismiss = { editing = null },
            // Something she can hand over by the unit — cash, coins, gold, a currency — that she
            // counts by hand; a wallet's figure is the chain's, and a house is not lent by the gram.
            onLend = {
                editing = null
                loanAsk = LoanAsk.Move(null, giving = true, preset = if (typeId == TOMAN_ID) "" else typeId)
            }.takeIf {
                held != null && held.wallet == null && held.amount > 0.0 &&
                    (typeId == TOMAN_ID || !resolveType(typeId, dynamicTypes).valuedInToman)
            },
        )
    }

    LoanAsks(
        ask = loanAsk, state = state, vm = vm, notices = notices, type = loanType,
        onClose = { loanAsk = null },
        onPerson = { loansPage = true; loanPerson = it },
    )
    }
}

/**
 * The smallest of the three choices: the chosen word on a small pill, the others bare. Below
 * [ChipChoice] on purpose — it does not slice what follows, it re-reads one figure (whose total,
 * which unit), so it sits on that figure's own line instead of over the page.
 *
 * On paper the chosen pill is `primary`, the selection role, never `Cta`; the hero passes its
 * field's colours, because up there the bright green is spoken for by the figure itself.
 */
@Composable
internal fun <T> PillToggle(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    fill: Color = MaterialTheme.colorScheme.primary,
    ink: Color = MaterialTheme.colorScheme.onPrimary,
    idle: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Row(modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
        options.forEach { option ->
            val active = option == selected
            Box(
                Modifier
                    .clip(RoundedCornerShape(Radius.pill))
                    .background(if (active) fill else Color.Transparent)
                    // Drawn ~31dp, touched 48dp: Compose extends any sub-48dp clickable to the
                    // minimum touch target, and the line it sits on must not grow a control's worth.
                    .selectable(selected = active, role = Role.RadioButton) { onSelect(option) }
                    .padding(horizontal = Space.m, vertical = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label(option),
                    fontSize = 13.sp,
                    maxLines = 1,
                    fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                    color = if (active) ink else idle,
                )
            }
        }
    }
}

// Material3's sheet hands whatever fling velocity its content leaves over to settle(), even
// when the sheet is already fully expanded and the leftover points further up — which kicks
// the settle spring from a standstill and bounces the sheet past its anchor and back. That is
// the jump every list gives at the end of its scroll. Upward leftovers have nowhere to go
// here (onPreFling already pulls a half-dragged sheet back up), so swallow them; downward
// ones still fling the sheet closed.
// ponytail: attach to every sheet's scrolling content — the category sheet went without it and
// jumped at the end of its icon grid. Still no wrapper: the sheets share this one line and not a
// body. Extract when two sheets want the same body, not the same guard.
internal val SheetFlingGuard = object : NestedScrollConnection {
    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity =
        if (available.y < 0f) available else Velocity.Zero
}

/**
 * A label over a group inside a sheet. It owns the air above itself, so the sections of a long
 * sheet are separated by one rule rather than by whatever `Spacer` each of them happened to be
 * given — three of them had drifted to three different gaps.
 *
 * More space above than below, because a heading belongs to what follows it.
 */
@Composable
internal fun SheetLabel(text: String, modifier: Modifier = Modifier) {
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

/** How loudly a [PillButton] speaks. */
internal enum class ButtonVoice {
    /** The one commit on a surface: [Cta] green. */
    PRIMARY,

    /** A real but quieter act. */
    TONAL,

    /** A tonal act on the green field, where the ordinary surfaces vanish. */
    HERO,

    /** An act that loses something: the tonal pill in error ink, quiet until it is armed. */
    DANGER,

    /** The second tap of an [ArmedButton]: filled error — the one moment a button shouts. */
    ARMED,
}

/**
 * The app's one shape for «press this» wherever a control stands on its own.
 *
 * A bare TextButton reads as a caption until it is tried: a bank card's «دیدن پیامک‌ها ·
 * اصلاح موجودی · حذف حساب» was a line of green and red words, and half the app's ways out —
 * «برگشت», «بستن», «بعداً» — were captions. The pill is the shape the review pill, the sheet
 * answers and the tab badge already speak in, so a control wearing it is recognisable as one
 * before it is touched.
 *
 * [block] is the full-width answer at the foot of a sheet, a size up. There is no third size: a
 * commit at 60dp over a «انصراف» at 44 is two apps arguing on one sheet.
 */
@Composable
internal fun PillButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    voice: ButtonVoice = ButtonVoice.TONAL,
    enabled: Boolean = true,
    block: Boolean = false,
) {
    // Two sizes and no others (DESIGN.md § Buttons): every sheet once picked its own height.
    val fontSize = if (block) 16.sp else 14.sp
    val minHeight = if (block) 52.dp else 44.dp
    // A wash of ink rather than a fixed surface, so the pill stands off whatever it sits on.
    // surfaceVariant all but vanished on a card (1.08:1 on the light card colour), which is where
    // most of the in-place acts live. Forest in the light keeps the green whisper; the dark stays
    // neutral — the room the green only visits.
    val wash = if (MaterialTheme.colorScheme.background.luminance() < 0.5f) {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)
    } else {
        Hero.forest.copy(alpha = 0.09f)
    }
    val fill = when (voice) {
        ButtonVoice.PRIMARY -> Cta.fill
        ButtonVoice.TONAL, ButtonVoice.DANGER -> wash
        ButtonVoice.HERO -> Hero.well
        ButtonVoice.ARMED -> MaterialTheme.colorScheme.error
    }
    val ink = when (voice) {
        ButtonVoice.PRIMARY -> Cta.ink
        ButtonVoice.TONAL -> MaterialTheme.colorScheme.onSurface
        ButtonVoice.HERO -> Hero.strong
        ButtonVoice.DANGER -> MaterialTheme.colorScheme.error
        ButtonVoice.ARMED -> MaterialTheme.colorScheme.onError
    }
    // The give under the thumb, from the frame the finger lands — response is the whole of
    // what makes a flat pill feel like a button rather than a printed one.
    val press = remember { MutableInteractionSource() }
    val pressed by press.collectIsPressedAsState()
    val give by animateFloatAsState(if (pressed) 0.97f else 1f, Motion.press(), label = "give")
    Box(
        modifier
            .scale(give)
            .alpha(if (enabled) 1f else 0.38f)
            .clip(RoundedCornerShape(Radius.pill))
            .background(fill)
            .clickable(
                enabled = enabled,
                role = Role.Button,
                interactionSource = press,
                indication = LocalIndication.current,
                onClick = onClick,
            )
            .heightIn(min = minHeight)
            // Vertical air only shows when a label wraps: an armed consequence is a sentence, and
            // a sentence cut off at one line is a warning nobody finished reading.
            .padding(horizontal = Space.l, vertical = Space.s),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            fontSize = fontSize,
            lineHeight = fontSize * 1.45f,
            fontWeight = FontWeight.Bold,
            color = ink,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * The app's two-tap, for every act that cannot be taken back: the first tap only turns the label
 * into the consequence and the pill into the one red-filled thing on screen, so the armed state
 * is seen rather than only read — and announced, or the safeguard is invisible to TalkBack and a
 * second double-tap acts with no confirmation ever perceived. [key] resets it, so a recycled row
 * never wakes up armed.
 */
@Composable
internal fun ArmedButton(
    label: String,
    armedLabel: String,
    onConfirmed: () -> Unit,
    modifier: Modifier = Modifier,
    voice: ButtonVoice = ButtonVoice.DANGER,
    enabled: Boolean = true,
    block: Boolean = false,
    key: Any? = null,
) {
    var armed by remember(key) { mutableStateOf(false) }
    PillButton(
        if (armed) armedLabel else label,
        onClick = { if (armed) { armed = false; onConfirmed() } else armed = true },
        modifier = modifier.semantics { liveRegion = LiveRegionMode.Polite },
        voice = if (armed) ButtonVoice.ARMED else voice,
        enabled = enabled,
        block = block,
    )
}

/**
 * A field's own error line, announced. A commit never greys out (DESIGN.md § Buttons): its tap
 * raises this while the keyboard is going away, so it has to be heard as well as seen.
 */
@Composable
internal fun FieldError(text: String) {
    Text(text, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
}

/** What a commit tap raises under a choice that cannot flag itself — a grid, a list of names. */
@Composable
internal fun MissingText(text: String) {
    Text(
        text,
        fontSize = 13.sp,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier
            .padding(top = Space.s, start = Space.xs)
            .semantics { liveRegion = LiveRegionMode.Polite },
    )
}

/**
 * The quieter sibling of [SegmentedChoice]: loose chips instead of a full-width track.
 *
 * Two tiers, so a screen never stacks twins. The tracked segment is the screen switcher — which
 * report, which side of a form — and there is at most one of it in view; anything that merely
 * slices the data below it wears these, the same small pills دفتر's filter button and category
 * chips already speak in. Same colours, same roles, a size down: the hierarchy is the shape.
 */
@Composable
internal fun <T> ChipChoice(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    enabled: (T) -> Boolean = { true },
) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
        options.forEach { option ->
            val active = option == selected
            val usable = enabled(option)
            Box(
                Modifier
                    .clip(RoundedCornerShape(Radius.pill))
                    .background(
                        if (active) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceContainer,
                    )
                    .selectable(
                        selected = active,
                        enabled = usable,
                        role = Role.RadioButton,
                        onClick = { onSelect(option) },
                    )
                    // 40dp drawn, 48dp touched: Compose hit-tests any sub-48dp clickable in
                    // the full minimum touch target and reports those bounds to TalkBack, so
                    // the finger already has its 48dp. Growing the *layout* instead
                    // (minimumInteractiveComponentSize) would push every row these sit in
                    // 8dp taller for no gain.
                    .heightIn(min = 40.dp)
                    .padding(horizontal = Space.l),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label(option),
                    fontSize = 13.sp,
                    maxLines = 1,
                    fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
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
