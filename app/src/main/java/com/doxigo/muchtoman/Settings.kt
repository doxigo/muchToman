package com.doxigo.muchtoman

import android.Manifest
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.platform.LocalContext
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
private enum class SettingsRoom { INDEX, SMS, SECURITY, BACKUP, CACHE }

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
            smsOn = smsEnabled && granted,
            family = family,
            onCompanion = onCompanion,
            onNameChange = onNameChange,
            onThemeChange = onThemeChange,
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
    }
}

/**
 * The index itself: her, then three bands of doors, then the version line.
 *
 * Three bands and not one, because the six doors are three different questions — where the
 * money comes from and how it is filed, how the app looks and who may look at it, and what
 * happens to all of it if the phone is lost. A single band of six is a list to read; three of
 * two is a shape to recognise.
 */
@Composable
private fun SettingsIndex(
    name: String,
    themeMode: ThemeMode,
    lockEnabled: Boolean,
    smsOn: Boolean,
    family: FamilyState,
    onCompanion: () -> Unit,
    onNameChange: (String) -> Unit,
    onThemeChange: (ThemeMode) -> Unit,
    onCategories: () -> Unit,
    onOpen: (SettingsRoom) -> Unit,
    onBack: () -> Unit,
) {
    var renaming by remember { mutableStateOf(false) }
    var themeSheet by remember { mutableStateOf(false) }

    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .verticalScroll(rememberScrollState())
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
            IdentityCard(
                name = name,
                family = family,
                onRename = { renaming = true },
                onCompanion = onCompanion,
            )

            SectionLabel("دفترت")
            IndexRow(
                title = "پیامک‌های بانک",
                value = if (smsOn) "روشن" else "خاموش",
                shape = bandShape(0, 2),
                divided = true,
                onClick = { onOpen(SettingsRoom.SMS) },
            ) { GlyphIcon(CategoryGlyph.ENVELOPE, MaterialTheme.colorScheme.onPrimaryContainer, size = 22.dp) }
            IndexRow(
                title = "دسته‌بندی‌ها",
                shape = bandShape(1, 2),
                onClick = onCategories,
            ) { GlyphIcon(CategoryGlyph.TAG, MaterialTheme.colorScheme.onPrimaryContainer, size = 22.dp) }

            SectionLabel("برنامه")
            IndexRow(
                title = "ظاهر برنامه",
                value = themeMode.fa,
                shape = bandShape(0, 2),
                divided = true,
                onClick = { themeSheet = true },
            ) { AppearanceGlyph(MaterialTheme.colorScheme.onPrimaryContainer) }
            IndexRow(
                title = "قفل و امنیت",
                value = if (lockEnabled) "روشن" else "خاموش",
                shape = bandShape(1, 2),
                onClick = { onOpen(SettingsRoom.SECURITY) },
            ) { LockGlyph(MaterialTheme.colorScheme.onPrimaryContainer) }

            SectionLabel("نگهداری")
            IndexRow(
                title = "پشتیبان‌گیری",
                shape = bandShape(0, 2),
                divided = true,
                onClick = { onOpen(SettingsRoom.BACKUP) },
            ) { GlyphIcon(CategoryGlyph.STACK, MaterialTheme.colorScheme.onPrimaryContainer, size = 22.dp) }
            IndexRow(
                title = "حافظهٔ موقت",
                shape = bandShape(1, 2),
                onClick = { onOpen(SettingsRoom.CACHE) },
            ) { GlyphIcon(CategoryGlyph.SWAP, MaterialTheme.colorScheme.onPrimaryContainer, size = 22.dp) }

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
}

/**
 * Her, at the top of her own settings — the one card on the page that is about a person rather
 * than about a switch.
 *
 * It carries two things because they are one thing: the name the app greets her by, and the
 * household that name appears under. The card opens خانواده, which is the room; the ✎ opens the
 * name, which is a field. The household line is never the same sentence twice — an unpaired
 * phone gets the offer in the words she would use for it, a household of one is an unfinished
 * setup and says so, and a real household just reports its size. That promotion is the whole
 * compensation for خانواده giving up its tab, and it used to be a row of its own; on the card it
 * is the first thing on the page instead of the second.
 *
 * In the lite edition there is no household at all, so the card is only the name and opens it.
 */
@Composable
private fun IdentityCard(
    name: String,
    family: FamilyState,
    onRename: () -> Unit,
    onCompanion: () -> Unit,
) {
    val household = when {
        BuildConfig.LITE -> "بالای برنامه باهاش بهت سلام می‌کنیم"
        !family.paired -> "خرج‌های خونه رو با هم توی یک دفتر ببینید"
        family.members.size < 2 -> "هنوز کسی اضافه نشده — دعوتش کن"
        else -> "${faNumber(family.members.size.toDouble())} عضو خانواده"
    }
    val named = name.isNotBlank()

    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.group))
            .background(MaterialTheme.colorScheme.surface),
    ) {
        Row(
            Modifier
                .clickable(
                    role = Role.Button,
                    onClickLabel = if (BuildConfig.LITE) "تغییر اسم" else "خانواده",
                    onClick = if (BuildConfig.LITE) onRename else onCompanion,
                )
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
                Text(
                    household,
                    fontSize = 13.sp,
                    lineHeight = 20.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            // Its own target inside the card, because the card leads somewhere else. In the
            // lite edition the card *is* this, so a second way in would be two buttons for one
            // action sitting next to each other.
            if (!BuildConfig.LITE) {
                IconButton(onClick = onRename) {
                    Icon(
                        Icons.Rounded.Edit,
                        contentDescription = "تغییر اسم",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                }
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
private fun SettingsPage(
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
            Text(
                "بالای برنامه باهاش بهت سلام می‌کنیم.",
                fontSize = 13.sp,
                lineHeight = 22.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Space.xs),
            )
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
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) {
                unrestricted = backgroundUnrestricted(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Switching it on is the moment to ask for the permissions — not at launch, where she has
    // no idea what an app about money wants with her messages. Denied leaves it switched off.
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

        // The one way this can be set up wrong and still look like it is working. A bank
        // whose alerts arrive as its own app's notifications sends no message at all, so
        // there is nothing to read and nothing to report — the balance simply stops where
        // the last real پیامک left it, which reads as the app being wrong rather than as a
        // setting being off. First clause at full strength: it is the sentence that has to
        // survive being skimmed.
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurface)) {
                    append("فقط پیامک‌های بانکی خونده می‌شن، نه اعلان‌های اپ بانک. ")
                }
                append(
                    "بعضی بانک‌ها مثل بلو بانک به جای پیامک اعلان می‌فرستن. این‌جوری " +
                        "چیزی برای خوندن نیست و موجودی به‌روز نمی‌شه. از تنظیمات اپ بانک، " +
                        "پیامک تراکنش رو روشن کن.",
                )
            },
            fontSize = 13.sp,
            lineHeight = 20.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = Space.xl, start = Space.xs, end = Space.xs),
        )

        if (smsEnabled && granted) {
            // One switch per bank actually seen, not per bank we know how to read: a list
            // of fifteen banks she has no account at is a list nobody reads.
            val banks = bankAccounts.map { it.bank }.distinct()
            SectionLabel("بانک‌ها")
            if (banks.isEmpty()) {
                Text(
                    "هنوز پیامک بانکی نرسیده. اولین پیامک که بیاد، بانک اینجا نشون داده می‌شه.",
                    fontSize = 13.sp,
                    lineHeight = 20.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = Space.xs, end = Space.xs),
                )
            } else {
                // One band, as on the asset list: these are N of the same thing, and N
                // separate cards made each bank look like its own section.
                banks.forEachIndexed { i, bank ->
                    val accounts = bankAccounts.filter { it.bank == bank }
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
    // Held only across the file-picker round trip, then cleared. Never written anywhere.
    var exportPass by remember { mutableStateOf("") }
    var importUri by remember { mutableStateOf<Uri?>(null) }

    val createFile = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri ->
        val pass = exportPass
        exportPass = ""
        if (uri != null && pass.isNotEmpty()) vm.exportBackup(uri, pass)
    }
    val openFile = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> if (uri != null) importUri = uri }

    SettingsPage("پشتیبان‌گیری", onBack) {
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
            "فایل پشتیبان رمز داره و بدون رمزش هیچ‌کس نمی‌تونه بخوندش — حتی خود برنامه. " +
                "رمز رو یه جای مطمئن نگه دار.",
            fontSize = 13.sp,
            lineHeight = 20.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = Space.m, start = Space.xs, end = Space.xs),
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
private fun SettingCard(
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
private fun DoorRow(
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
                Text(
                    subtitle,
                    fontSize = 13.sp,
                    lineHeight = 20.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
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
                // on the second — and announced, so the armed state exists for ears too.
                var armed by remember { mutableStateOf(false) }
                TextButton(
                    onClick = { if (armed) { armed = false; onConfirm() } else armed = true },
                    enabled = !backup.working,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp),
                ) {
                    Text(
                        if (armed) "مطمئنی؟ همه‌چیز با نسخهٔ پشتیبان عوض می‌شه — دوباره بزن"
                        else "بازگردانی از این پشتیبان",
                        fontSize = 15.sp,
                        fontWeight = if (armed) FontWeight.Bold else FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
                TextButton(
                    onClick = onDismiss,
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp),
                ) { Text("بی‌خیال", fontSize = 15.sp) }
            }
        }
    }
}
