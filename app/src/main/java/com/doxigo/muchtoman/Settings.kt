package com.doxigo.muchtoman

import android.Manifest
import android.net.Uri
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch

/**
 * تنظیمات — an index of rooms, not a scroll of everything.
 *
 * It used to be one column five screens tall: her name, a theme picker, the messages switch and
 * the four paragraphs that qualify it, a switch per bank, two security switches, the backup
 * rows, the cache tool, and a version line at the bottom of it all. Every one of those was
 * findable only by scrolling past the ones before it, and the paragraphs — which are the part
 * that has to be *read*, not skimmed — sat between her and whatever she actually came for.
 *
 * So the page became an index and the settings moved into the rooms they belong to. Nothing was
 * deleted: every sentence that qualified the messages switch is on [SmsPage] beside it, where a
 * mind arriving to change that one thing is standing anyway. What is left here is a page she can
 * read in a glance — her at the top, then three bands of two doors — and a way in to each.
 *
 * The sub-pages are state, not routes: they are only ever reachable from this page and only ever
 * one level deep, so they live here rather than as four more booleans in `AppScreens`. Saveable,
 * because a process death that threw her from پشتیبان‌گیری back to the index would read as the
 * app restarting itself — the same reason `settings` itself is saveable upstairs.
 */
private enum class SettingsRoom { INDEX, SMS, SECURITY, BACKUP, CACHE, HEALTH, UPGRADE, FEEDBACK }

/** The site, which the «درباره» band opens and the full edition's download falls back to. */
private const val SITE = "https://muchtoman.com/"

@Composable
fun SettingsScreen(
    name: String,
    themeMode: ThemeMode,
    lockEnabled: Boolean,
    widgetLock: Boolean,
    smsEnabled: Boolean,
    bankAccounts: List<BankAccount>,
    disabledBanks: Set<String>,
    family: FamilyState,
    activity: FragmentActivity,
    onCompanion: () -> Unit,
    onNameChange: (String) -> Unit,
    onThemeChange: (ThemeMode) -> Unit,
    /** Days ahead an installment is reminded of, -1 for never — see [INSTALLMENT_REMINDER_DAYS]. */
    installmentReminder: Int,
    onInstallmentReminderChange: (Int) -> Unit,
    /** How notes talk — see [QuipTone]. */
    quipTone: QuipTone,
    onQuipToneChange: (QuipTone) -> Unit,
    onSmsChange: (Boolean) -> Unit,
    onBankChange: (String, Boolean) -> Unit,
    onLockChange: (Boolean) -> Unit,
    onWidgetLockChange: (Boolean) -> Unit,
    /** The door to «دسته‌بندی‌ها», which is a room of its own rather than a strip here. */
    onCategories: () -> Unit,
    /** Drops and rebuilds everything rebuildable — see [AppVm.clearCaches]. */
    onClearCache: () -> Unit,
    /** Reads the whole inbox again, keeping her hand-typed anchors — see [AppVm.rescanInbox]. */
    onRescanInbox: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    var page by rememberSaveable { mutableStateOf(SettingsRoom.INDEX) }

    // The index's scroll lives up here, not in [SettingsIndex]: a room replaces the index
    // outright, so a scroll remembered inside it died with every door opened, and backing out
    // of پشتیبان‌گیری — the last band — put her at the top, a screen away from where she was on
    // a short phone. Saveable for the same reason `page` is.
    val indexScroll = rememberScrollState()

    // Whether READ_SMS is actually held, kept here rather than on [SmsPage] because the index
    // row reports the same fact: a permission revoked in Android's settings leaves `smsEnabled`
    // true, and a row reading «روشن» over a page reading «خاموش» is the page lying.
    var granted by remember { mutableStateOf(canReadSms(context)) }

    // Only enabled off the index: on the index the handler upstairs owns Back and closes
    // تنظیمات, which is the one-level rule every pushed page here already keeps.
    BackHandler(enabled = page != SettingsRoom.INDEX) { page = SettingsRoom.INDEX }

    when (page) {
        SettingsRoom.INDEX -> SettingsIndex(
            name = name,
            themeMode = themeMode,
            lockEnabled = lockEnabled,
            // Asked afresh whenever the index comes back: Android's page is the only place that
            // changes notification access, and returning from it through پیامک‌های بانک lands here.
            smsOn = smsEnabled && granted || canReadNotifications(context),
            family = family,
            scroll = indexScroll,
            onCompanion = onCompanion,
            onNameChange = onNameChange,
            onThemeChange = onThemeChange,
            installmentReminder = installmentReminder,
            onInstallmentReminderChange = onInstallmentReminderChange,
            quipTone = quipTone,
            onQuipToneChange = onQuipToneChange,
            onCategories = onCategories,
            onOpen = { page = it },
            onBack = onBack,
        )

        SettingsRoom.SMS -> SmsPage(
            smsEnabled = smsEnabled,
            granted = granted,
            onGranted = { granted = it },
            bankAccounts = bankAccounts,
            disabledBanks = disabledBanks,
            onSmsChange = onSmsChange,
            onBankChange = onBankChange,
            onRescanInbox = onRescanInbox,
            onBack = { page = SettingsRoom.INDEX },
        )

        SettingsRoom.SECURITY -> SecurityPage(
            lockEnabled = lockEnabled,
            widgetLock = widgetLock,
            onLockChange = onLockChange,
            onWidgetLockChange = onWidgetLockChange,
            onBack = { page = SettingsRoom.INDEX },
        )

        SettingsRoom.BACKUP -> BackupPage(
            activity = activity,
            onBack = { page = SettingsRoom.INDEX },
        )

        SettingsRoom.CACHE -> CachePage(
            onClear = onClearCache,
            onBack = { page = SettingsRoom.INDEX },
        )

        SettingsRoom.HEALTH -> LedgerHealthPage(activity, onRescanInbox) { page = SettingsRoom.INDEX }

        SettingsRoom.UPGRADE -> UpgradePage(
            activity = activity,
            onBackup = { page = SettingsRoom.BACKUP },
            onBack = { page = SettingsRoom.INDEX },
        )

        SettingsRoom.FEEDBACK -> FeedbackPage { page = SettingsRoom.INDEX }
    }
}

/**
 * The index itself: her, then three bands of doors, then «درباره», then the version line.
 *
 * Three bands and not one, because the six doors are three different questions — where the
 * money comes from and how it is filed, how the app looks and who may look at it, and what
 * happens to all of it if the phone is lost. A single band of six is a list to read; three of
 * two is a shape to recognise. «درباره» stands apart: its doors are about the app, not her money.
 */
@Composable
private fun SettingsIndex(
    name: String,
    themeMode: ThemeMode,
    lockEnabled: Boolean,
    smsOn: Boolean,
    family: FamilyState,
    scroll: ScrollState,
    onCompanion: () -> Unit,
    onNameChange: (String) -> Unit,
    onThemeChange: (ThemeMode) -> Unit,
    installmentReminder: Int,
    onInstallmentReminderChange: (Int) -> Unit,
    quipTone: QuipTone,
    onQuipToneChange: (QuipTone) -> Unit,
    onCategories: () -> Unit,
    onOpen: (SettingsRoom) -> Unit,
    onBack: () -> Unit,
) {
    var renaming by remember { mutableStateOf(false) }
    var themeSheet by remember { mutableStateOf(false) }
    var reminderSheet by remember { mutableStateOf(false) }
    var toneSheet by remember { mutableStateOf(false) }
    val context = LocalContext.current

    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .verticalScroll(scroll)
                .padding(horizontal = Space.xl),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ScreenTitle("تنظیمات", modifier = Modifier.weight(1f))
                // «برگشت», not «ذخیره»: every setting on this page commits the moment it is
                // touched, and the one that did not — her name — now has a sheet with its own
                // ذخیره. A save button over eight already-saved settings was a button lying
                // about seven of them.
                PillButton("برگشت", onBack, fontSize = 15.sp)
            }

            Spacer(Modifier.height(Space.l))
            IdentityCard(name = name, onRename = { renaming = true })
            if (BuildConfig.LITE) UpgradeCard { onOpen(SettingsRoom.UPGRADE) }

            // The lite edition keeps only the doors its one screen reads from: the messages behind
            // the bank balances, the look, the lock, the backup and the cache. Categories, the
            // household, installment reminders and the ledger's health all belong to screens it
            // never shows, and a door into a room nothing uses is a setting that does nothing.
            val full = !BuildConfig.LITE

            // خانواده is the ledger shared, so it sits with the ledger's doors — one door among
            // them, not the card on top. Most people keep this book alone; the household is
            // there for whoever opens it, not the first thing everybody is asked about.
            SectionLabel("دفترت")
            IndexRow(
                title = "پیامک‌های بانک",
                value = if (smsOn) "روشن" else "خاموش",
                shape = bandShape(0, if (full) 3 else 1),
                divided = full,
                onClick = { onOpen(SettingsRoom.SMS) },
            ) { GlyphIcon(CategoryGlyph.ENVELOPE, MaterialTheme.colorScheme.onPrimaryContainer, size = 22.dp) }
            if (full) {
                IndexRow(
                    title = "دسته‌بندی‌ها",
                    shape = bandShape(1, 3),
                    divided = true,
                    onClick = onCategories,
                ) { GlyphIcon(CategoryGlyph.TAG, MaterialTheme.colorScheme.onPrimaryContainer, size = 22.dp) }
                IndexRow(
                    title = "خانواده",
                    // No value until there is a household: an unpaired phone is not a setting
                    // left «خاموش», it is simply somebody's own book.
                    value = if (family.paired) "${faNumber(family.members.size.toDouble())} عضو" else null,
                    shape = bandShape(2, 3),
                    onClick = onCompanion,
                ) { GlyphIcon(CategoryGlyph.HOUSE, MaterialTheme.colorScheme.onPrimaryContainer, size = 22.dp) }
            }

            SectionLabel("برنامه")
            IndexRow(
                title = "ظاهر برنامه",
                value = themeMode.fa,
                shape = bandShape(0, if (full) 4 else 2),
                divided = true,
                onClick = { themeSheet = true },
            ) { AppearanceGlyph(MaterialTheme.colorScheme.onPrimaryContainer) }
            IndexRow(
                title = "قفل و امنیت",
                value = if (lockEnabled) "روشن" else "خاموش",
                shape = bandShape(1, if (full) 4 else 2),
                divided = full,
                onClick = { onOpen(SettingsRoom.SECURITY) },
            ) { LockGlyph(MaterialTheme.colorScheme.onPrimaryContainer) }
            if (full) {
                IndexRow(
                    title = "یادآوری قسط",
                    value = installmentReminderFa(installmentReminder),
                    shape = bandShape(2, 4),
                    divided = true,
                    onClick = { reminderSheet = true },
                ) { GlyphIcon(CategoryGlyph.INSTALMENT, MaterialTheme.colorScheme.onPrimaryContainer, size = 22.dp) }
                // Lite has neither budgets nor installments, so no note a voice could be heard in.
                IndexRow(
                    title = "لحن اعلان‌ها",
                    value = quipTone.fa,
                    shape = bandShape(3, 4),
                    onClick = { toneSheet = true },
                ) { GlyphIcon(CategoryGlyph.MUSIC, MaterialTheme.colorScheme.onPrimaryContainer, size = 22.dp) }
            }

            SectionLabel("نگهداری")
            IndexRow(
                title = "پشتیبان‌گیری",
                shape = bandShape(0, if (full) 3 else 2),
                divided = true,
                onClick = { onOpen(SettingsRoom.BACKUP) },
            ) { GlyphIcon(CategoryGlyph.STACK, MaterialTheme.colorScheme.onPrimaryContainer, size = 22.dp) }
            IndexRow(
                title = "حافظهٔ موقت",
                shape = bandShape(1, if (full) 3 else 2),
                divided = full,
                onClick = { onOpen(SettingsRoom.CACHE) },
            ) { GlyphIcon(CategoryGlyph.SWAP, MaterialTheme.colorScheme.onPrimaryContainer, size = 22.dp) }
            if (full) {
                IndexRow(
                    title = "وضعیت دفتر",
                    shape = bandShape(2, 3),
                    onClick = { onOpen(SettingsRoom.HEALTH) },
                ) { GlyphIcon(CategoryGlyph.TRAY, MaterialTheme.colorScheme.onPrimaryContainer, size = 22.dp) }
            }

            // The address on the row, not just «سایت»: it says where the tap is going before it
            // goes there, which is the only thing a door out of the app owes her.
            SectionLabel("درباره")
            IndexRow(
                title = "سایت",
                value = "muchtoman.com",
                shape = bandShape(0, 2),
                divided = true,
                onClick = { openUrl(context, SITE) },
            ) { GlobeGlyph(MaterialTheme.colorScheme.onPrimaryContainer) }
            IndexRow(
                title = "بازخورد",
                shape = bandShape(1, 2),
                onClick = { onOpen(SettingsRoom.FEEDBACK) },
            ) { GlyphIcon(CategoryGlyph.NOTE, MaterialTheme.colorScheme.onPrimaryContainer, size = 22.dp) }

            // The answer to "which version do you have?" over the phone, without her having to
            // find the system app-info page. A fixed gap, not weight(1f): inside a scrolling
            // column a weighted spacer has nothing to push against.
            //
            // The build number and the build type are both here because the name alone does not
            // identify an install: every locally built app carries the placeholder 1.0, so a
            // sandbox build and a real 1.0 release read identically — which is exactly the
            // question this line exists to answer.
            Spacer(Modifier.height(Space.xxl))
            Text(
                buildString {
                    append("چقدر تومن • نسخهٔ ${faVersion(BuildConfig.VERSION_NAME)}")
                    append(" • ساخت ${faVersion(BuildConfig.VERSION_CODE.toString())}")
                    if (BuildConfig.BUILD_TYPE != "release") {
                        append(" • ${bidi(BuildConfig.BUILD_TYPE)}")
                    }
                },
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = Space.l),
            )
        }
    }

    if (renaming) {
        NameSheet(
            name = name,
            onDone = onNameChange,
            onDismiss = { renaming = false },
        )
    }
    if (themeSheet) {
        ThemeSheet(
            current = themeMode,
            onPick = { onThemeChange(it); themeSheet = false },
            onDismiss = { themeSheet = false },
        )
    }
    if (reminderSheet) {
        InstallmentReminderSheet(
            current = installmentReminder,
            onPick = { onInstallmentReminderChange(it); reminderSheet = false },
            onDismiss = { reminderSheet = false },
        )
    }
    if (toneSheet) {
        QuipToneSheet(
            current = quipTone,
            onPick = { onQuipToneChange(it); toneSheet = false },
            onDismiss = { toneSheet = false },
        )
    }
}

/**
 * Her, at the top of her own settings — the one card on the page that is about a person rather
 * than about a switch.
 *
 * Only her. It used to carry the household too and open خانواده, with the name behind a ✎,
 * which made every phone a family waiting to be set up — and most of them are one person who
 * wants to type a name and be done. The household is a door in «دفترت» now; this card is the
 * name, and it opens the name.
 */
@Composable
private fun IdentityCard(name: String, onRename: () -> Unit) {
    val named = name.isNotBlank()

    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.group))
            .background(MaterialTheme.colorScheme.surface),
    ) {
        Row(
            Modifier
                .clickable(role = Role.Button, onClickLabel = "تغییر اسم", onClick = onRename)
                .padding(horizontal = Space.l, vertical = Space.l),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) { CompanionGlyph(MaterialTheme.colorScheme.onPrimaryContainer, size = 28.dp) }
            Column(Modifier.padding(horizontal = Space.m).weight(1f)) {
                Text(
                    if (named) name else "اسمت رو بنویس",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Black,
                    color = if (named) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(
                Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * The lite edition's way to the full one, straight under her card.
 *
 * On the hero's own field rather than in a band: every band on this page is a setting, and this
 * is not one — it is the one thing here that asks her to go and look at something, so it wears
 * the brand's card and the page's only [ButtonVoice.PRIMARY] pill. The pill opens the preview,
 * not the download: nobody should be sent to install an app they have not yet seen.
 */
@Composable
private fun UpgradeCard(onOpen: () -> Unit) {
    HeroPanel(Modifier.padding(top = Space.l)) {
        Text(
            "نسخهٔ کامل چقدر تومن",
            fontSize = 20.sp,
            fontWeight = FontWeight.Black,
            color = Hero.accent,
        )
        Text(
            "دفتر خرج‌ها، دخل و خرج ماه، بودجه و قسط، طلب و بدهی و دفتر خانوادگی، کنار همین " +
                "دارایی‌ها. رایگانه.",
            fontSize = 14.sp,
            lineHeight = 22.sp,
            color = Hero.strong,
            modifier = Modifier.padding(top = Space.xs),
        )
        Spacer(Modifier.height(Space.l))
        PillButton("ببین چی داره", onOpen, voice = ButtonVoice.PRIMARY, fontSize = 15.sp)
    }
}

/**
 * One door on the index: a mark, a name, what it currently says, and the chevron.
 *
 * No subtitle, which is the whole difference between this page and the one it replaced. A row
 * here answers *what* and *what is it set to*; the *why* — every paragraph that qualifies a
 * switch — is on the page the row opens, next to the switch it qualifies.
 *
 * The disc is `primaryContainer` rather than the neutral the switch rows wear, and that is the
 * page's one rule: a green disc is a door, a grey disc is a control. Not the CTA green — this
 * is the quiet chip the action circles and the tab indicator already speak in, so «press this»
 * keeps meaning only one thing.
 */
@Composable
private fun IndexRow(
    title: String,
    shape: Shape,
    onClick: () -> Unit,
    value: String? = null,
    divided: Boolean = false,
    mark: @Composable () -> Unit,
) {
    Box(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface),
    ) {
        Row(
            Modifier
                .clickable(role = Role.Button, onClick = onClick)
                .padding(horizontal = Space.l, vertical = Space.l),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) { mark() }
            Text(
                title,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = Space.m).weight(1f),
            )
            if (value != null) {
                Text(
                    value,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    modifier = Modifier.padding(start = Space.s),
                )
            }
            Icon(
                Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
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
 * The frame every page behind the index wears: its name, the way back, and a scroll.
 *
 * «برگشت» in a pill rather than a bare arrow, because that is what دسته‌بندی‌ها and خانواده — the
 * two pages this one now sits beside — already use, and a settings tree with two different ways
 * out is two apps.
 */
@Composable
internal fun SettingsPage(
    title: String,
    onBack: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Space.xl),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ScreenTitle(title, modifier = Modifier.weight(1f))
                PillButton("برگشت", onBack, fontSize = 15.sp)
            }
            Spacer(Modifier.height(Space.xl))
            content()
            Spacer(Modifier.height(Space.xxl))
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        fontSize = 15.sp,
        fontWeight = FontWeight.ExtraBold,
        color = MaterialTheme.colorScheme.onBackground,
        // more air above a heading than below it
        modifier = Modifier
            .padding(top = Space.xxl, bottom = Space.m, start = Space.xs)
            // Real headings, so TalkBack can jump between the bands.
            .semantics { heading() },
    )
}

/**
 * The name, in the smallest room that fits it.
 *
 * It was an inline field with a «ذخیره» pill in the title bar — the one setting on the page
 * that did not commit itself, and the reason the page carried a save button at all. Here the
 * draft commits on every way out, the swipe-down included: the field it replaced took the same
 * care, and a name typed and lost to a gesture is worse than a name saved that she meant to
 * discard, which is one more tap to undo.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NameSheet(name: String, onDone: (String) -> Unit, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var draft by remember { mutableStateOf(name) }
    val focus = remember { FocusRequester() }
    // She opened a sheet in order to type; asking for a second tap to start is the sheet
    // pretending it does not know that.
    LaunchedEffect(Unit) { focus.requestFocus() }

    ModalBottomSheet(
        onDismissRequest = { onDone(draft); onDismiss() },
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = Radius.sheet, topEnd = Radius.sheet),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            Modifier
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = Space.xl)
                .padding(bottom = Space.l),
        ) {
            SheetTitle("اسمت")
            Spacer(Modifier.height(Space.l))
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it.take(24) },
                singleLine = true,
                placeholder = { Text("مثلاً مریم") },
                shape = RoundedCornerShape(Radius.field),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onDone(draft); onDismiss() }),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focus)
                    .semantics { contentDescription = "اسمت" },
            )
            Spacer(Modifier.height(Space.xl))
            Button(
                onClick = { onDone(draft); onDismiss() },
                shape = RoundedCornerShape(Radius.pill),
                colors = ButtonDefaults.buttonColors(containerColor = Cta.fill, contentColor = Cta.ink),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 60.dp),
            ) { Text("ذخیره", fontSize = 16.sp, fontWeight = FontWeight.Bold) }
        }
    }
}

/**
 * Three choices in a sheet, so the index row can be one line with the answer on it.
 *
 * The same [SegmentedChoice] the page used inline — a two-state switch cannot say "follow the
 * phone" — just moved somewhere it is not costing the index a row of its own. Picking closes
 * the sheet, because the whole app re-themes underneath it and that is the only receipt this
 * choice needs.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ThemeSheet(current: ThemeMode, onPick: (ThemeMode) -> Unit, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
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
            SheetTitle("ظاهر برنامه")
            Text(
                "«خودکار» یعنی هرچی گوشی‌ات می‌گه — روشن توی روز، تیره توی شب.",
                fontSize = 13.sp,
                lineHeight = 22.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Space.xs),
            )
            Spacer(Modifier.height(Space.l))
            SegmentedChoice(
                options = ThemeMode.entries,
                selected = current,
                label = { it.fa },
                onSelect = onPick,
                fontSize = 16.sp,
            )
        }
    }
}

/** How far ahead an installment is reminded of — [ThemeSheet]'s shape, four choices instead of three. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InstallmentReminderSheet(current: Int, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
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
            SheetTitle("یادآوری قسط")
            Text(
                "قسطی که پرداختش رو ثبت کرده باشی، یادآوری نمی‌شه.",
                fontSize = 13.sp,
                lineHeight = 22.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Space.xs),
            )
            Spacer(Modifier.height(Space.l))
            SegmentedChoice(
                options = INSTALLMENT_REMINDER_DAYS,
                selected = current,
                label = ::installmentReminderFa,
                onSelect = onPick,
                fontSize = 15.sp,
            )
        }
    }
}

/**
 * «لحن اعلان‌ها» — how the notes talk. One choice rather than a switch per kind of line: whether
 * a kind of note comes at all is Android's own per-channel switch already.
 *
 * The caption says what each step changes, including the one note that only exists past ساده,
 * because «بی‌تعارف» is a choice she should make knowing it can sting.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QuipToneSheet(current: QuipTone, onPick: (QuipTone) -> Unit, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
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
            SheetTitle("لحن اعلان‌ها")
            Text(
                QUIP_TONE_CAPTION,
                fontSize = 13.sp,
                lineHeight = 22.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Space.xs),
            )
            Spacer(Modifier.height(Space.l))
            SegmentedChoice(
                options = QuipTone.entries,
                selected = current,
                label = { it.fa },
                onSelect = onPick,
                fontSize = 15.sp,
            )
        }
    }
}

/**
 * «پیامک‌های بانک» — the switch, and every sentence that qualifies it.
 *
 * All four paragraphs came from the index, unchanged. They belong here rather than there for the
 * same reason the switch does: they are the small print of *this* decision, and on the index they
 * were four paragraphs standing between her and the six settings that have nothing to do with
 * messages. A mind that has walked into this room has already decided to think about this.
 */
@Composable
private fun SmsPage(
    smsEnabled: Boolean,
    granted: Boolean,
    onGranted: (Boolean) -> Unit,
    bankAccounts: List<BankAccount>,
    disabledBanks: Set<String>,
    onSmsChange: (Boolean) -> Unit,
    onBankChange: (String, Boolean) -> Unit,
    onRescanInbox: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current

    // Whether this phone suspends the app hard enough to delay a bank message. Re-read when she
    // comes back rather than answered once: the button below leaves for Android's own settings,
    // and a warning still standing after she has just fixed it reads as the fix having failed.
    // The same arrangement, for the same reason, as `canNote` on the home screen.
    var unrestricted by remember { mutableStateOf(backgroundUnrestricted(context)) }
    // Re-read on return for the same reason: notification access is only ever granted on
    // Android's own page, which the Blu card below leaves for.
    var listening by remember { mutableStateOf(canReadNotifications(context)) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) {
                unrestricted = backgroundUnrestricted(context)
                listening = canReadNotifications(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Switching it on asks for the permissions the first-run sheet did not get — that sheet asks
    // once, and this is where a mind changed later goes. Denied leaves it switched off.
    // Asked as a pair, but only READ_SMS is load-bearing: it is what every balance is read from.
    // RECEIVE_SMS alone being denied just means notifications ride the six-hour sweep instead of
    // arriving with the message — see [SmsReceiver] — so it gets no say in `granted`.
    val askSms = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        val ok = grants[Manifest.permission.READ_SMS] == true
        onGranted(ok)
        onSmsChange(ok)
    }

    SettingsPage("پیامک‌های بانک", onBack) {
        SettingCard(
            mark = { GlyphIcon(CategoryGlyph.ENVELOPE, MaterialTheme.colorScheme.onSurface, size = 22.dp) },
            title = "خواندن پیامک‌های بانک",
            subtitle = "با این گزینه، موجودی حساب‌ها از روی پیامک بانک به‌روز می‌شه.",
            checked = smsEnabled && granted,
            onChange = { on ->
                if (on && !granted) {
                    askSms.launch(
                        arrayOf(Manifest.permission.READ_SMS, Manifest.permission.RECEIVE_SMS),
                    )
                } else {
                    onSmsChange(on)
                }
            },
        )
        // Naming the banks is not decoration: only messages from their own numbers are read
        // now, so a bank missing from this line is the one and only reason its balance
        // never appears — and without the line there is nothing to tell her that.
        val watched = Bank.entries.filter { it.numbers.isNotEmpty() }.joinToString("، ") { it.fa }
        Text(
            if (smsEnabled && granted) {
                "پیامک‌های $watched فقط روی همین گوشی خونده می‌شن و جایی فرستاده نمی‌شن."
            } else {
                "فقط پیامک‌های $watched، اون هم روی همین گوشی، خونده می‌شن."
            },
            fontSize = 13.sp,
            lineHeight = 20.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = Space.m, start = Space.xs, end = Space.xs),
        )
        // The other way this can be set up wrong and still look like it is working — and the
        // one she has no way of guessing, because nothing on screen is missing. Every figure
        // is right; they just arrive hours after the spend, on a phone whose OEM suspended the
        // app that was going to read them. Only drawn when the phone is actually restricted
        // *and* she reads messages, so a stock phone never sees a warning about a problem it
        // does not have.
        if (smsEnabled && granted && !unrestricted) {
            Text(
                "این گوشی چقدر تومن رو می‌خوابونه، برای همین ممکنه پیامک بانک با چند ساعت " +
                    "تأخیر خونده بشه. برای این‌که همون لحظه خونده بشه، اجازه بده بیدار بمونه.",
                fontSize = 13.sp,
                lineHeight = 22.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Space.xl, start = Space.xs, end = Space.xs),
            )
            // A pill, not a bare TextButton: this is the one thing on the page she is being
            // asked to *press*, and at 15sp in green on its own line the text button read as a
            // heading for the paragraph under it. See [PillButton].
            Spacer(Modifier.height(Space.m))
            PillButton("اجازه بده بیدار بمونه", { askBackgroundExemption(context) }, fontSize = 15.sp)
        }

        // Blu can send its alerts as its app's notifications instead of SMS, and then there is no
        // message to read: the balance stops where the last real پیامک left it, which reads as the
        // app being wrong. This card is the way out, and the text under it is the how, because the
        // switch it flips is Android's and every step of it happens outside the app. Not behind the
        // SMS switch: someone whose only bank is Blu has no reason to grant her messages for it.
        SectionLabel("اعلان بانک")
        SettingCard(
            title = "خواندن اعلان‌های بلو بانک",
            subtitle = "برای وقتی که بلو تراکنش‌ها رو به جای پیامک با اعلان می‌فرسته.",
            // Either way it is Android's page: access is granted and taken back only there.
            checked = listening,
            onChange = { openNotificationAccess(context) },
            badge = { BankLogo(Bank.BLU.name, size = 44.dp) },
        )
        Text(
            buildAnnotatedString {
                if (listening) {
                    append(
                        "اعلان‌های بلو از همین حالا، همون لحظه که برسن، خونده می‌شن. اعلانی " +
                            "که قبلاً پاکش کردی دیگه خوندنی نیست.",
                    )
                    return@buildAnnotatedString
                }
                append(
                    "روشنش که کنی، صفحهٔ «دسترسی به اعلان» اندروید باز می‌شه؛ اون‌جا چقدر " +
                        "تومن رو روشن کن. اندروید می‌گه این برنامه همهٔ اعلان‌ها رو می‌بینه، " +
                        "ولی فقط اعلان‌های اپ بلو خونده می‌شن و بقیه دست‌نخورده رد می‌شن. " +
                        "برای این لازم نیست پیامک‌ها رو روشن کنی.",
                )
                // Android 13 greys the switch out for an app installed from a file rather than
                // a store, says «تنظیم محدودشده», and gives no hint of the way round, which is
                // in the app's own info page.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    withStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurface)) {
                        append("\nاگه کلیدش خاکستری بود: ")
                    }
                    append(
                        "تنظیمات اندروید ← برنامه‌ها ← چقدر تومن، از منوی سه‌نقطهٔ بالا " +
                            "تنظیمات محدودشده رو آزاد کن و دوباره امتحان کن.",
                    )
                }
            },
            fontSize = 13.sp,
            lineHeight = 20.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = Space.m, start = Space.xs, end = Space.xs),
        )
        if (smsEnabled && granted || listening) {
            // One switch per bank actually seen, not per bank we know how to read: a list
            // of fifteen banks she has no account at is a list nobody reads. And only the banks
            // something still reads — with SMS off, a bank last heard from by پیامک is frozen and
            // out of the total, so a switch for it would be a switch for nothing.
            val shown = countedBankAccounts(bankAccounts, smsEnabled && granted, listening)
            val banks = shown.map { it.bank }.distinct()
            SectionLabel("بانک‌ها")
            if (banks.isEmpty()) {
                Text(
                    "هنوز تراکنش بانکی نرسیده. اولین پیامک یا اعلان بانک که بیاد، بانک اینجا " +
                        "نشون داده می‌شه.",
                    fontSize = 13.sp,
                    lineHeight = 20.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = Space.xs, end = Space.xs),
                )
            } else {
                // One band, as on the asset list: these are N of the same thing, and N
                // separate cards made each bank look like its own section.
                banks.forEachIndexed { i, bank ->
                    val accounts = shown.filter { it.bank == bank }
                    SettingCard(
                        title = accounts.first().bankFa,
                        subtitle = "${faCompact(accounts.sumOf { it.balance })} تومان" +
                            if (accounts.any { !it.trusted }) "  •  نیاز به بررسی" else "",
                        checked = bank !in disabledBanks,
                        onChange = { on -> onBankChange(bank, on) },
                        shape = bandShape(i, banks.size),
                        divided = i < banks.size - 1,
                        // The bank's own mark, as in the accounts sheet. A row of identical
                        // 🏛️ told her nothing the name beside it did not already say.
                        badge = { BankLogo(bank, size = 44.dp) },
                    )
                }
            }
        }

        if (smsEnabled && granted) {
            // One tap, no confirm: it re-reads, it does not destroy — the subtitle says
            // exactly what it keeps, and the transient «در حال بازخوانی…» upstairs is the
            // receipt that the tap did something. See [AppVm.rescanInbox].
            Spacer(Modifier.height(Space.xxl))
            DoorRow(
                title = "بازخوانی همهٔ پیامک‌ها",
                subtitle = "اگر پیامکی جا مونده یا از پشتیبان برگشتی، از اول می‌خونه؛ " +
                    "موجودی‌هایی که خودت نوشتی سر جاشون می‌مونن.",
                glyph = CategoryGlyph.SWAP,
                shape = bandShape(0, 1),
                divided = false,
                enabled = true,
                onClick = onRescanInbox,
                // An action that stays on this page, so the door's chevron would lie.
                chevron = false,
            )
        }
    }
}

/**
 * «قفل و امنیت» — the app's lock and the widget's mask.
 *
 * One band of two rows, because they are siblings rather than the same switch: she may want the
 * app guarded but the number glanceable on the home screen, or the other way round.
 */
@Composable
private fun SecurityPage(
    lockEnabled: Boolean,
    widgetLock: Boolean,
    onLockChange: (Boolean) -> Unit,
    onWidgetLockChange: (Boolean) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val available = remember { canLock(context) }

    SettingsPage("قفل و امنیت", onBack) {
        SettingCard(
            mark = { LockGlyph(MaterialTheme.colorScheme.onSurface) },
            title = "قفل برنامه",
            // Always what the setting does. When it is unavailable, the helper text below
            // the band already carries the fix — and says where, which this line never did.
            subtitle = "با اثر انگشت یا رمز گوشی باز می‌شه",
            checked = lockEnabled,
            onChange = onLockChange,
            enabled = available,
            shape = bandShape(0, 2),
            divided = true,
        )
        SettingCard(
            // The mask itself: the three ٭ the widget will actually show, drawn as dots.
            mark = { MaskDots(MaterialTheme.colorScheme.onSurface) },
            title = "قفل ویجت",
            subtitle = "مبلغ صفحهٔ اصلی رو با ٭٭٭ پنهان می‌کنه",
            checked = widgetLock,
            onChange = onWidgetLockChange,
            shape = bandShape(1, 2),
        )
        if (!available) {
            Text(
                "برای فعال کردن قفل، اول توی تنظیمات گوشی رمز یا اثر انگشت بذار.",
                fontSize = 13.sp,
                lineHeight = 20.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Space.m, start = Space.xs, end = Space.xs),
            )
        }
    }
}

/**
 * «پشتیبان‌گیری» — the two rows and everything behind them: the passphrase sheets, the file
 * pickers, and the words that report how it went.
 *
 * The recovery path. Android backup is off on purpose, so without this file a lost phone loses
 * the messages, the anchors and every decision she ever made.
 *
 * The ViewModel is fetched from the activity rather than passed down — [SettingsScreen]'s call
 * site is owned elsewhere, and the same instance MainActivity built is what the provider returns.
 */
@Composable
private fun BackupPage(activity: FragmentActivity, onBack: () -> Unit) {
    val vm = remember { ViewModelProvider(activity)[AppVm::class.java] }
    val backup by vm.backup.collectAsStateWithLifecycle()

    var exportSheet by remember { mutableStateOf(false) }
    // Held only across the file-picker round trip, then cleared. Never written anywhere — not
    // even to saved state, so a process death in the picker loses it (the dropEmptyBackup case).
    var exportPass by remember { mutableStateOf("") }
    var importUri by remember { mutableStateOf<Uri?>(null) }

    val createFile = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri ->
        val pass = exportPass
        exportPass = ""
        when {
            uri == null -> Unit
            pass.isNotEmpty() -> vm.exportBackup(uri, pass)
            else -> vm.dropEmptyBackup(uri)
        }
    }
    val openFile = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> if (uri != null) importUri = uri }

    SettingsPage("پشتیبان‌گیری", onBack) {
        Text(
            if (backup.lastExportAt > 0) "آخرین پشتیبان: ${faDate(tehranDay(backup.lastExportAt))}"
            else "روی این گوشی هنوز پشتیبانی ساخته نشده.",
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(bottom = Space.m),
        )
        // Words only, no alarm colour: the file is sealed with her passphrase. What is left is a
        // step the app cannot take for her, because the file is out of its reach.
        if (backup.holdsCodes) {
            Text(
                "فایل پشتیبان قبلی پیامک‌هایی از بانک رو داره که برنامه دیگه نگه نمی‌داره، " +
                    "مثل رمزهای یک‌بار مصرف. یه پشتیبان تازه بساز و فایل قبلی رو پاک کن.",
                fontSize = 13.sp,
                lineHeight = 20.sp,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(bottom = Space.m, start = Space.xs, end = Space.xs),
            )
        }
        DoorRow(
            title = "پشتیبان‌گیری از همه‌چیز",
            subtitle = "پیامک‌ها، دسته‌بندی‌ها، موجودی‌ها و تنظیمات، توی یک فایل رمزدار",
            glyph = CategoryGlyph.STACK,
            shape = bandShape(0, 2),
            divided = true,
            enabled = !backup.working,
            onClick = { exportSheet = true },
        )
        DoorRow(
            title = "بازگردانی از پشتیبان",
            subtitle = "همه‌چیز از روی فایل پشتیبان برمی‌گرده",
            glyph = CategoryGlyph.TRAY,
            shape = bandShape(1, 2),
            divided = false,
            enabled = !backup.working,
            // The file first, so the passphrase is only ever asked about a file that exists.
            onClick = { openFile.launch(arrayOf("*/*")) },
        )
        Text(
            "فایل پشتیبان رمز داره. فایل و رمزش رو جای مطمئن نگه دار. " +
                "این تاریخ فقط زمان ساخت فایله؛ برنامه نمی‌تونه موندن فایل در محل ذخیره رو بررسی کنه.",
            fontSize = 13.sp,
            lineHeight = 20.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = Space.m, start = Space.xs, end = Space.xs),
        )
        Spacer(Modifier.height(Space.l))
        SettingCard(
            // The house, not a bell: the reminder lives on صفحهٔ خانه, and a bell would promise
            // the notification the line under it says never comes.
            mark = { GlyphIcon(CategoryGlyph.HOUSE, MaterialTheme.colorScheme.onSurface, size = 22.dp) },
            title = "یادآوری پشتیبان در برنامه",
            subtitle = "بعد از ۳۰ روز، صفحهٔ خانه یادآوری می‌کنه. اعلانی فرستاده نمی‌شه.",
            checked = backup.reminderEnabled,
            onChange = vm::setBackupReminder,
        )
        backup.notice?.let { words ->
            Text(
                words,
                fontSize = 13.sp,
                lineHeight = 20.sp,
                color = if (backup.failed) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .padding(top = Space.m, start = Space.xs, end = Space.xs)
                    // Said aloud too: the tap that caused this happened a sheet and a picker ago.
                    .semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        if (backup.restartNeeded) {
            Text(
                "برای تمام شدن بازگردانی، برنامه رو ببند و دوباره باز کن.",
                fontSize = 13.sp,
                lineHeight = 20.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .padding(top = Space.m, start = Space.xs, end = Space.xs)
                    .semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }

    if (exportSheet) {
        ExportPassSheet(
            onDismiss = { exportSheet = false },
            onDone = { pass ->
                exportPass = pass
                exportSheet = false
                createFile.launch(backupFileName())
            },
        )
    }
    importUri?.let { uri ->
        RestoreSheet(
            backup = backup,
            onRead = { pass -> vm.readBackupFile(uri, pass) },
            onConfirm = { vm.confirmRestore() },
            onDismiss = { importUri = null; vm.dismissRestore() },
        )
    }
}

/**
 * «وضعیت دفتر» — where the ledger starts, what it holds, and how far the messages behind it go.
 *
 * It was seven «label: value» lines down a column, a paragraph and a button, in a room whose
 * siblings are all bands. Now the one thing here she can change comes first — where the ledger
 * starts — and the facts follow as two bands she can read down, the ledger's and the messages',
 * each answer at the end of its row where the eye goes looking for one.
 */
@Composable
private fun LedgerHealthPage(activity: FragmentActivity, onImport: () -> Unit, onBack: () -> Unit) {
    val vm = remember(activity) { ViewModelProvider(activity)[AppVm::class.java] }
    val state by vm.state.collectAsStateWithLifecycle()
    val health = state.ledger.health
    val now = System.currentTimeMillis()
    var picking by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { vm.runLedger() }
    SettingsPage("وضعیت دفتر", onBack) {
        DoorRow(
            title = "شروع دفتر",
            subtitle = ledgerStartFa(health),
            glyph = CategoryGlyph.INSTALMENT,
            shape = bandShape(0, 1),
            divided = false,
            enabled = true,
            onClick = { picking = true },
        )

        SectionLabel("دفتر")
        Facts(
            "تراکنش‌ها" to faNumber(health.transactionCount.toDouble()),
            "قدیمی‌ترین تراکنش" to (health.oldestDay?.let(::faDate) ?: "هنوز ثبت نشده"),
            "آخرین آماده‌سازی" to (health.derivedAt?.let { faAgo(it, now) } ?: "هنوز آماده نشده"),
        )
        Text(
            "روزهای بدون پیامک لزوماً روزهای بدون خرج نیستن.",
            fontSize = 13.sp,
            lineHeight = 20.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = Space.m, start = Space.xs, end = Space.xs),
        )

        SectionLabel("پیامک‌ها")
        Facts(
            "نگه‌داشته‌شده" to faNumber(health.sourceCount.toDouble()),
            "قدیمی‌ترین پیامک" to (health.oldestSourceAt?.let { faDate(tehranDay(it)) } ?: "هنوز ثبت نشده"),
            "آخرین پیامک واردشده" to (health.lastIngestAt?.let { faAgo(it, now) } ?: "هنوز وارد نشده"),
            "مرز خواندن" to (health.scannedTo?.let { faDate(tehranDay(it)) } ?: "هنوز شروع نشده"),
        )
        if (state.smsEnabled) {
            // A row in the band vocabulary rather than the pill it was: it acts in place, like
            // «بازخوانی همهٔ پیامک‌ها» on پیامک‌های بانک, and the notice upstairs is its receipt.
            Spacer(Modifier.height(Space.l))
            DoorRow(
                title = "وارد کردن پیامک‌های قدیمی",
                subtitle = "صندوق پیامک از اول خونده می‌شه؛ موجودی‌هایی که خودت نوشتی سر جاشون می‌مونن.",
                glyph = CategoryGlyph.TRAY,
                shape = bandShape(0, 1),
                divided = false,
                enabled = true,
                onClick = onImport,
                chevron = false,
            )
        }
    }

    if (picking) {
        LedgerStartSheet(
            health = health,
            onPick = { vm.setLedgerStartsOn(it); picking = false },
            onDismiss = { picking = false },
        )
    }
}

/** A screen of the full edition, as [UpgradePage] shows it: its picture in both themes and its line. */
private class FullScreen(val light: Int, val dark: Int, val title: String, val line: String)

/**
 * «نسخهٔ کامل» — the screens the lite edition does not have, and the way to them.
 *
 * Pictures rather than a list of features: «دفتر» means nothing to someone who has only ever seen
 * a list of what she owns, and the screen answers what the word cannot. They are the site's own
 * crops (tools/site/assets.mjs), so a retake of the README captures reaches this page too.
 *
 * The full edition is a second app, not this one upgraded — its own package, so both sit on one
 * phone — and nothing typed here crosses over by itself. The backup is the bridge, so the page
 * says so and holds the door to it, above the button that leaves.
 */
@Composable
private fun UpgradePage(activity: FragmentActivity, onBackup: () -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val vm = remember(activity) { ViewModelProvider(activity)[AppVm::class.java] }
    val state by vm.state.collectAsStateWithLifecycle()
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    // ponytail: gated on the edition so R8 folds it away in the full build and the resource
    // shrinker drops the ten pictures from that APK — the same trick [demoLedger] rides.
    val screens = if (BuildConfig.LITE) {
        listOf(
            FullScreen(R.drawable.upgrade_ledger, R.drawable.upgrade_ledger_dark, "دفتر",
                "هر خرج و درآمد از روی پیامک بانک، با دسته‌بندی."),
            FullScreen(R.drawable.upgrade_report, R.drawable.upgrade_report_dark, "دخل و خرج",
                "هر ماه چقدر اومد، چقدر رفت و کجا رفت."),
            FullScreen(R.drawable.upgrade_budget, R.drawable.upgrade_budget_dark, "بودجه و قسط",
                "سقف خرج هر دسته، هدف پس‌انداز و قسط‌ها."),
            FullScreen(R.drawable.upgrade_loans, R.drawable.upgrade_loans_dark, "طلب و بدهی",
                "کی بهت بدهکاره و تو به کی."),
            FullScreen(R.drawable.upgrade_family, R.drawable.upgrade_family_dark, "خانواده",
                "یه دفتر مشترک بین چند گوشی که سرور هم نمی‌تونه بخونتش."),
        )
    } else {
        emptyList()
    }

    SettingsPage("نسخهٔ کامل", onBack) {
        Text(
            "همین برنامه‌ست، با این صفحه‌ها کنار دارایی‌ها. رایگانه و کنار همین نسخه نصب می‌شه، " +
                "پس لازم نیست اینو پاک کنی.",
            fontSize = 15.sp,
            lineHeight = 26.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = Space.xs),
        )
        Spacer(Modifier.height(Space.l))
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(Space.m),
        ) {
            screens.forEach { screen ->
                // One stop for TalkBack: the picture is the line under it, drawn.
                Column(Modifier.width(180.dp).semantics(mergeDescendants = true) {}) {
                    Image(
                        painterResource(if (dark) screen.dark else screen.light),
                        contentDescription = null,
                        contentScale = ContentScale.FillWidth,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(Radius.card))
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(Radius.card)),
                    )
                    Text(
                        screen.title,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = Space.m),
                    )
                    Text(
                        screen.line,
                        fontSize = 13.sp,
                        lineHeight = 20.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        SectionLabel("دارایی‌هات")
        Text(
            "نسخهٔ کامل یه برنامهٔ جداست و دارایی‌هایی که اینجا نوشتی خودشون بهش نمی‌رن. از اینجا " +
                "پشتیبان بگیر، بعد توی نسخهٔ کامل از تنظیمات ← پشتیبان‌گیری، «بازگردانی از پشتیبان» رو بزن.",
            fontSize = 13.sp,
            lineHeight = 20.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = Space.xs, end = Space.xs, bottom = Space.m),
        )
        DoorRow(
            title = "پشتیبان‌گیری",
            subtitle = "همهٔ دارایی‌ها و تنظیمات، توی یک فایل رمزدار",
            glyph = CategoryGlyph.STACK,
            shape = bandShape(0, 1),
            divided = false,
            enabled = true,
            onClick = onBackup,
        )

        Spacer(Modifier.height(Space.xxl))
        Button(
            // The Worker's proxied copy, as the update sheet uses: github.com, where the release
            // page lives, mostly does not load from Iran. No rates yet means no link to it, and
            // then the site's install section is the next best door.
            onClick = { openUrl(context, state.rates.latest?.downloadUrlFor(lite = false) ?: "${SITE}#install") },
            shape = RoundedCornerShape(Radius.pill),
            colors = ButtonDefaults.buttonColors(containerColor = Cta.fill, contentColor = Cta.ink),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 60.dp),
        ) { Text("گرفتن نسخهٔ کامل", fontSize = 16.sp, fontWeight = FontWeight.Bold) }
    }
}

private enum class Sending { IDLE, SENDING, SENT, FAILED }

/**
 * «بازخورد» — a message to hey@muchtoman.com from inside the app.
 *
 * A form rather than a mailto: link, because most phones here have no mail app signed in, and
 * the Worker that already answers from Iran is the one address the app knows it can reach. What
 * goes is said above the button — her words, the contact she chose to leave, the version — and
 * nothing else goes. The draft outlives a failed send and a rotated phone; only a sent one clears.
 */
@Composable
private fun FeedbackPage(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var message by rememberSaveable { mutableStateOf("") }
    var contact by rememberSaveable { mutableStateOf("") }
    var sending by rememberSaveable { mutableStateOf(Sending.IDLE) }

    SettingsPage("بازخورد", onBack) {
        Text(
            "مشکلی که دیدی، چیزی که کم داری، یا بانکی که پیامکش خونده نمی‌شه — هر چی هست بنویس.",
            fontSize = 15.sp,
            lineHeight = 26.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = Space.xs),
        )
        Spacer(Modifier.height(Space.l))
        OutlinedTextField(
            value = message,
            onValueChange = {
                message = it.take(MAX_FEEDBACK_CHARS)
                // A new word after «فرستاده شد» is a new message, and the receipt is for the old one.
                if (sending != Sending.SENDING) sending = Sending.IDLE
            },
            minLines = 6,
            placeholder = { Text("پیامت") },
            shape = RoundedCornerShape(Radius.field),
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = "پیامت" },
        )
        Spacer(Modifier.height(Space.m))
        OutlinedTextField(
            value = contact,
            onValueChange = { contact = it.take(100) },
            singleLine = true,
            label = { Text("اگه جواب می‌خوای: ایمیل یا آیدی تلگرام") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Done),
            shape = RoundedCornerShape(Radius.field),
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            "فقط همین‌ها و شمارهٔ نسخهٔ برنامه به ${bidi("hey@muchtoman.com")} فرستاده می‌شه.",
            fontSize = 13.sp,
            lineHeight = 20.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = Space.m, start = Space.xs, end = Space.xs),
        )
        Spacer(Modifier.height(Space.xl))
        Button(
            onClick = {
                sending = Sending.SENDING
                scope.launch {
                    val sent = postFeedback(BuildConfig.RATES_URL, message, contact).isSuccess
                    if (sent) message = ""
                    sending = if (sent) Sending.SENT else Sending.FAILED
                }
            },
            enabled = message.isNotBlank() && sending != Sending.SENDING,
            shape = RoundedCornerShape(Radius.pill),
            colors = ButtonDefaults.buttonColors(containerColor = Cta.fill, contentColor = Cta.ink),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 60.dp),
        ) {
            Text(
                if (sending == Sending.SENDING) "در حال فرستادن…" else "فرستادن",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        val outcome = when (sending) {
            Sending.SENT -> "فرستاده شد."
            Sending.FAILED -> "فرستاده نشد. اینترنت رو نگاه کن و دوباره بزن؛ متنت سر جاشه."
            else -> null
        }
        if (outcome != null) {
            Text(
                outcome,
                fontSize = 13.sp,
                lineHeight = 20.sp,
                color = if (sending == Sending.FAILED) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .padding(top = Space.m, start = Space.xs, end = Space.xs)
                    // Said aloud: the button she pressed is the last thing TalkBack read.
                    .semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }
}

/** «از اول», or the month the ledger starts at and what that leaves out — the start row's line. */
private fun ledgerStartFa(health: LedgerHealth): String = when {
    health.startsOn <= 0L -> "از اول"
    health.setAside > 0 ->
        "از ${reportMonthOf(health.startsOn).fa} • ${faNumber(health.setAside.toDouble())} تراکنش کنار رفته"
    else -> "از ${reportMonthOf(health.startsOn).fa}"
}

/**
 * Facts, read-only, as one band: the name at the start of each row and the answer at its end.
 * No disc — on these pages a disc marks something to press, and none of these is.
 */
@Composable
private fun Facts(vararg rows: Pair<String, String>) {
    rows.forEachIndexed { index, (label, value) ->
        Box(
            Modifier
                .fillMaxWidth()
                .clip(bandShape(index, rows.size))
                .background(MaterialTheme.colorScheme.surface),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .padding(horizontal = Space.l, vertical = Space.m)
                    // One stop for TalkBack — «تراکنش‌ها، ۱٬۲۳۴» — not two.
                    .semantics(mergeDescendants = true) {},
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    label,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.size(Space.m))
                Text(
                    value,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            if (index < rows.lastIndex) {
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(start = Space.l),
                )
            }
        }
    }
}

/**
 * Where the ledger starts: «از اول», or the first of a month, newest first — the month she wants
 * to start clean at is almost always a recent one — each saying what it would leave out before
 * she picks it. Picking closes the sheet, as the theme sheet's does: every screen behind it
 * re-reads at once, and «از اول» is always the top row, so the way back is never further than
 * the way in.
 *
 * The months run from this one back to the oldest the ledger holds, gaps included: a month with
 * nothing in it is still a month she may want to start from.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LedgerStartSheet(health: LedgerHealth, onPick: (Long) -> Unit, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val thisMonth = reportMonthOf(tehranDay(System.currentTimeMillis()))
    val oldest = listOfNotNull(
        health.months.firstOrNull()?.first?.let(::reportMonthOf),
        health.startsOn.takeIf { it > 0L }?.let(::reportMonthOf),
        thisMonth,
    ).min()
    val months = generateSequence(thisMonth) { if (it > oldest) it.previous() else null }.toList()
    val options = listOf(0L to "از اول") + months.map { it.startDay to it.fa }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = Radius.sheet, topEnd = Radius.sheet),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            Modifier
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Space.xl)
                .padding(bottom = Space.l),
        ) {
            SheetTitle("شروع دفتر")
            Text(
                "تراکنش‌های قبل از ماهی که انتخاب کنی، دیگه توی دفتر و گزارش‌ها و بودجه‌ها نمیان. " +
                    "چیزی پاک نمی‌شه؛ موجودی حساب‌ها و هدف‌ها دست نمی‌خورن و با «از اول» همه‌شون برمی‌گردن.",
                fontSize = 13.sp,
                lineHeight = 22.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Space.xs),
            )
            Spacer(Modifier.height(Space.l))
            options.forEachIndexed { index, (day, label) ->
                val setAside = health.months.sumOf { (start, count) -> if (start < day) count else 0 }
                StartChoice(
                    label = label,
                    detail = when {
                        day == 0L -> null
                        setAside > 0 -> "${faNumber(setAside.toDouble())} تراکنش قبلش کنار می‌ره"
                        else -> "چیزی کنار نمی‌ره"
                    },
                    selected = day == health.startsOn,
                    shape = bandShape(index, options.size),
                    divided = index < options.lastIndex,
                    onPick = { onPick(day) },
                )
            }
        }
    }
}

/** One choice in [LedgerStartSheet]: a radio row, the whole row the target. */
@Composable
private fun StartChoice(
    label: String,
    detail: String?,
    selected: Boolean,
    shape: Shape,
    divided: Boolean,
    onPick: () -> Unit,
) {
    Box(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .selectable(selected = selected, role = Role.RadioButton, onClick = onPick)
                .heightIn(min = 56.dp)
                .padding(horizontal = Space.l, vertical = Space.m),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    label,
                    fontSize = 16.sp,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (detail != null) {
                    Text(
                        detail,
                        fontSize = 13.sp,
                        lineHeight = 20.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            RadioButton(selected = selected, onClick = null)
        }
        if (divided) {
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = Space.l),
            )
        }
    }
}

/**
 * «حافظهٔ موقت» — the way back from a stale cache, and the sentence that makes it safe to press.
 *
 * Everything it drops is rebuilt — rates refetched, the ledger re-derived from the stored
 * messages — and nothing she typed, filed or received is touched. One tap, no confirm: a control
 * that only deletes the rebuildable has nothing to warn about. A repair tool, not a thing about
 * her money, which is why it is the last door on the index.
 */
@Composable
private fun CachePage(onClear: () -> Unit, onBack: () -> Unit) {
    var cleared by remember { mutableStateOf(false) }

    SettingsPage("حافظهٔ موقت", onBack) {
        Text(
            "نرخ‌های ذخیره‌شده، نشان کوین‌ها و جدول‌هایی که از روی پیامک‌ها ساخته شدن پاک و از " +
                "نو ساخته می‌شن. پیامک‌ها، دسته‌بندی‌ها و هر چیزی که خودت وارد کردی دست نمی‌خوره.",
            fontSize = 15.sp,
            lineHeight = 26.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = Space.xs, end = Space.xs),
        )
        Spacer(Modifier.height(Space.xl))
        PillButton(
            if (cleared) "پاک شد — در حال ساختن دوباره" else "پاک کردن حافظهٔ موقت",
            {
                if (!cleared) {
                    cleared = true
                    onClear()
                }
            },
        )
    }
}

/** One switched setting: its mark, what it does, what it currently means, and the switch. */
@Composable
internal fun SettingCard(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = RoundedCornerShape(Radius.group),
    divided: Boolean = false,
    /** Replaces the disc entirely, for a row with a full mark of its own (a bank's logo). */
    badge: (@Composable () -> Unit)? = null,
    /** The mark inside the standard disc — drawn with the app's own pen, never an emoji. */
    mark: @Composable () -> Unit = {},
) {
    Box(modifier.fillMaxWidth().clip(shape).background(MaterialTheme.colorScheme.surface)) {
        // toggleable on the row, not onCheckedChange on the switch: the whole card becomes
        // one control named by its title — four bare "switch, on" in a row told a TalkBack
        // user nothing — and the text is part of the hit target.
        Row(
            Modifier
                .toggleable(
                    value = checked,
                    enabled = enabled,
                    role = Role.Switch,
                    onValueChange = onChange,
                )
                .padding(horizontal = Space.l, vertical = Space.l),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The same circular badge the asset rows use, so a settable thing and an owned
            // thing at least agree about what an icon looks like in this app. Neutral, not the
            // index's green: on these pages a disc marks a control, not a door.
            if (badge != null) {
                badge()
            } else {
                Box(
                    Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                    contentAlignment = Alignment.Center,
                ) { mark() }
            }
            Column(Modifier.padding(horizontal = Space.m).weight(1f)) {
                Text(
                    title,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle.isNotBlank()) {
                    Text(
                        subtitle,
                        fontSize = 13.sp,
                        lineHeight = 20.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
            Switch(checked = checked, enabled = enabled, onCheckedChange = null)
        }
        // Inset to where the text starts, like the asset rows: a rule under the badge cuts
        // the row in half instead of separating it from the next one.
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

/** A row that is a door, in the band the switches wear — same shape, groupable, with a subtitle. */
@Composable
internal fun DoorRow(
    title: String,
    subtitle: String,
    glyph: CategoryGlyph,
    shape: Shape,
    divided: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    /** Off for the rows that act in place rather than lead somewhere. */
    chevron: Boolean = true,
) {
    Box(Modifier.fillMaxWidth().clip(shape).background(MaterialTheme.colorScheme.surface)) {
        Row(
            Modifier
                .clickable(role = Role.Button, enabled = enabled, onClick = onClick)
                .padding(horizontal = Space.l, vertical = Space.l),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                contentAlignment = Alignment.Center,
            ) { GlyphIcon(glyph, MaterialTheme.colorScheme.onSurface, size = 22.dp) }
            Column(Modifier.padding(horizontal = Space.m).weight(1f)) {
                Text(title, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                // Blank until there is something to say — خانواده's sync row before its first run.
                if (subtitle.isNotBlank()) {
                    Text(
                        subtitle,
                        fontSize = 13.sp,
                        lineHeight = 20.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
            if (chevron) {
                Icon(
                    Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
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
 * The lock, in the app's own pen.
 *
 * Material ships a solid one, and in a band of six line marks the single filled glyph reads as
 * a second icon set that wandered in. The lock *screen* keeps Material's — at 40dp on the forest
 * disc it is an emblem, not a row mark.
 */
@Composable
private fun LockGlyph(tint: Color, box: Dp = 22.dp) {
    Canvas(Modifier.size(box)) {
        val ink = pen(1.8.dp)
        val w = size.width
        val h = size.height
        // The shackle: the top half of a circle whose ends land on the body's top edge.
        drawArc(
            color = tint,
            startAngle = 180f,
            sweepAngle = 180f,
            useCenter = false,
            topLeft = Offset(w * 0.29f, h * 0.25f),
            size = Size(w * 0.42f, h * 0.42f),
            style = ink,
        )
        drawRoundRect(
            color = tint,
            topLeft = Offset(w * 0.18f, h * 0.44f),
            size = Size(w * 0.64f, h * 0.40f),
            cornerRadius = CornerRadius(w * 0.10f),
            style = ink,
        )
    }
}

/** The widget's own mask — the three ٭ it will actually draw — as three dots in the pen's ink. */
@Composable
private fun MaskDots(tint: Color) {
    Canvas(Modifier.size(22.dp)) {
        val r = 2.4.dp.toPx()
        val y = size.height / 2f
        for (i in 0..2) {
            drawCircle(tint, r, Offset(size.width * (0.18f + 0.32f * i), y))
        }
    }
}

/**
 * The appearance mark: a ring with one half filled — the same thing every platform draws for
 * light-against-dark, in this app's own pen rather than a borrowed icon set.
 */
@Composable
private fun AppearanceGlyph(tint: Color, box: Dp = 22.dp) {
    Canvas(Modifier.size(box)) {
        val r = size.minDimension * 0.38f
        val centre = Offset(size.width / 2f, size.height / 2f)
        drawCircle(tint, r, centre, style = pen(1.8.dp))
        drawArc(
            color = tint,
            startAngle = 90f,
            sweepAngle = 180f,
            useCenter = true,
            topLeft = Offset(centre.x - r, centre.y - r),
            size = Size(r * 2f, r * 2f),
        )
    }
}

/** The site's mark: a globe — its rim, one meridian and the equator — in the page's own pen. */
@Composable
private fun GlobeGlyph(tint: Color, box: Dp = 22.dp) {
    Canvas(Modifier.size(box)) {
        val ink = pen(1.8.dp)
        val r = size.minDimension * 0.40f
        drawCircle(tint, r, center, style = ink)
        drawOval(
            color = tint,
            topLeft = Offset(center.x - r * 0.45f, center.y - r),
            size = Size(r * 0.9f, r * 2f),
            style = ink,
        )
        drawLine(tint, Offset(center.x - r, center.y), Offset(center.x + r, center.y), ink.width, StrokeCap.Round)
    }
}

/** "1.0" -> "۱٫۰" so the one latin run on an otherwise Persian page disappears. */
private fun faVersion(v: String): String =
    v.map { c -> if (c in '0'..'9') '۰' + (c - '0') else if (c == '.') '٫' else c }
        .joinToString("")

/** SAF supplies the real name; this is the suggestion it opens with. */
private fun backupFileName(): String {
    val day = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
        .format(java.util.Date())
    return "muchtoman-$day.mtbak"
}

/**
 * The passphrase, twice, before the file picker ever opens — words-first errors, minimum six
 * characters, and the one warning that matters said up front: forgotten means gone.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExportPassSheet(onDismiss: () -> Unit, onDone: (String) -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var pass by remember { mutableStateOf("") }
    var again by remember { mutableStateOf("") }
    var problem by remember { mutableStateOf<String?>(null) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = Radius.sheet, topEnd = Radius.sheet),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            Modifier
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = Space.xl)
                .padding(bottom = Space.l),
        ) {
            SheetTitle("رمز فایل پشتیبان")
            Text(
                "فایل با همین رمز قفل می‌شه و بدون اون هیچ‌کس — حتی خود برنامه — نمی‌تونه " +
                    "بازش کنه. اگه یادت بره، هیچ راهی برای باز کردنش نیست.",
                fontSize = 13.sp,
                lineHeight = 22.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Space.xs),
            )
            Spacer(Modifier.height(Space.l))
            OutlinedTextField(
                value = pass,
                onValueChange = { pass = it; problem = null },
                singleLine = true,
                label = { Text("رمز — دست‌کم ۶ حرف") },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Next,
                ),
                shape = RoundedCornerShape(Radius.field),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(Space.m))
            OutlinedTextField(
                value = again,
                onValueChange = { again = it; problem = null },
                singleLine = true,
                label = { Text("دوباره همون رمز") },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done,
                ),
                shape = RoundedCornerShape(Radius.field),
                modifier = Modifier.fillMaxWidth(),
            )
            problem?.let { words ->
                Text(
                    words,
                    fontSize = 13.sp,
                    lineHeight = 20.sp,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier
                        .padding(top = Space.m, start = Space.xs)
                        .semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
            Spacer(Modifier.height(Space.xl))
            Button(
                onClick = {
                    when {
                        pass.length < BACKUP_MIN_PASSPHRASE -> problem = "رمز کوتاهه — دست‌کم ۶ حرف باشه."
                        again != pass -> problem = "دوتا رمز یکی نیستن."
                        else -> onDone(pass)
                    }
                },
                shape = RoundedCornerShape(Radius.pill),
                colors = ButtonDefaults.buttonColors(containerColor = Cta.fill, contentColor = Cta.ink),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 60.dp),
            ) { Text("ساختن فایل پشتیبان", fontSize = 16.sp, fontWeight = FontWeight.Bold) }
        }
    }
}

/**
 * The import sheet, in two moods: first the passphrase and «خواندن فایل», then — once the file
 * has decrypted and proved itself — the armed two-tap that actually replaces everything. The
 * armed label names the consequence and is a polite live region, so TalkBack hears the safeguard
 * instead of double-tapping through it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RestoreSheet(
    backup: BackupUi,
    onRead: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var pass by remember { mutableStateOf("") }
    var problem by remember { mutableStateOf<String?>(null) }
    // Staged: the line under the rows carries it from here, so the sheet bows out.
    LaunchedEffect(backup.restartNeeded) { if (backup.restartNeeded) onDismiss() }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = Radius.sheet, topEnd = Radius.sheet),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            Modifier
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = Space.xl)
                .padding(bottom = Space.l),
        ) {
            SheetTitle("بازگردانی از پشتیبان")
            if (!backup.ready) {
                Text(
                    "رمزی که موقع ساختن فایل گذاشتی رو بزن.",
                    fontSize = 13.sp,
                    lineHeight = 22.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = Space.xs),
                )
                Spacer(Modifier.height(Space.l))
                OutlinedTextField(
                    value = pass,
                    onValueChange = { pass = it; problem = null },
                    singleLine = true,
                    label = { Text("رمز فایل") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done,
                    ),
                    shape = RoundedCornerShape(Radius.field),
                    modifier = Modifier.fillMaxWidth(),
                )
                (problem ?: backup.notice)?.let { words ->
                    Text(
                        words,
                        fontSize = 13.sp,
                        lineHeight = 20.sp,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier
                            .padding(top = Space.m, start = Space.xs)
                            .semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
                Spacer(Modifier.height(Space.xl))
                Button(
                    onClick = {
                        if (pass.isEmpty()) problem = "اول رمز فایل رو بزن."
                        else onRead(pass)
                    },
                    enabled = !backup.working,
                    shape = RoundedCornerShape(Radius.pill),
                    colors = ButtonDefaults.buttonColors(containerColor = Cta.fill, contentColor = Cta.ink),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 60.dp),
                ) {
                    Text(
                        // The KDF is deliberately slow, so the wait is named rather than mute.
                        if (backup.working) "در حال خواندن…" else "خواندن فایل",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            } else {
                Text(
                    "${backup.readyWords} خونده شد و رمزش درسته.",
                    fontSize = 15.sp,
                    lineHeight = 24.sp,
                    modifier = Modifier.padding(top = Space.xs),
                )
                Text(
                    "با بازگردانی، همه‌چیزِ الانِ برنامه — پیامک‌ها، دسته‌بندی‌ها، موجودی‌ها و " +
                        "تنظیمات — با نسخهٔ پشتیبان عوض می‌شه و برنمی‌گرده.",
                    fontSize = 13.sp,
                    lineHeight = 22.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = Space.m),
                )
                Spacer(Modifier.height(Space.xl))
                // Two taps, as everywhere destructive in the app, with the consequence named
                // on the second.
                ArmedButton(
                    "بازگردانی از این پشتیبان",
                    "مطمئنی؟ همه‌چیز با نسخهٔ پشتیبان عوض می‌شه — دوباره بزن",
                    onConfirm,
                    Modifier.fillMaxWidth(),
                    enabled = !backup.working,
                    block = true,
                )
                PillButton(
                    "بی‌خیال",
                    onDismiss,
                    Modifier.fillMaxWidth().padding(top = Space.s),
                    block = true,
                )
            }
        }
    }
}
