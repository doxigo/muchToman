package com.doxigo.muchtoman

import android.Manifest
import android.content.Context
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Combining BIOMETRIC_STRONG with DEVICE_CREDENTIAL is not supported below API 30, so the
 * older path asks for WEAK. Nothing here guards a cryptographic key — it only hides a
 * balance from whoever else picks up the phone — so weak biometrics are an acceptable trade
 * for the feature working at all on her phone.
 */
private val authenticators: Int
    get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        BIOMETRIC_STRONG or DEVICE_CREDENTIAL
    } else {
        BIOMETRIC_WEAK or DEVICE_CREDENTIAL
    }

/** False when there is no fingerprint AND no PIN/pattern — then the lock cannot be offered. */
fun canLock(context: Context): Boolean =
    BiometricManager.from(context).canAuthenticate(authenticators) == BiometricManager.BIOMETRIC_SUCCESS

/**
 * DEVICE_CREDENTIAL is always in the allowed set on purpose: a fingerprint that will not
 * read must never leave her locked out of her own balance. It also means no negative button
 * may be set — the framework rejects that combination.
 */
fun promptUnlock(activity: FragmentActivity, onSuccess: () -> Unit) {
    val prompt = BiometricPrompt(
        activity,
        ContextCompat.getMainExecutor(activity),
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                onSuccess()
            }
        },
    )
    prompt.authenticate(
        BiometricPrompt.PromptInfo.Builder()
            .setTitle("قفل چقدر تومن")
            .setSubtitle("برای دیدن دارایی‌هات، هویتت رو تأیید کن")
            .setAllowedAuthenticators(authenticators)
            .build()
    )
}

/** Shown instead of the balance while locked. Deliberately carries no figures. */
@Composable
fun LockScreen(onUnlock: () -> Unit) {
    // The same deep field as the total it is standing in front of, so the lock reads as this
    // app closed rather than as a system screen that happens to be in the way.
    Box(
        Modifier
            .fillMaxSize()
            // The fixed forest, not the theme-aware field: full-bleed with nothing behind
            // it, this screen is the brand moment and reads right in both themes.
            .background(Hero.forest),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .padding(Space.xxl),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                Modifier
                    .size(96.dp)
                    .clip(CircleShape)
                    .background(Hero.well),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Rounded.Lock,
                    contentDescription = null,
                    tint = Hero.accent,
                    modifier = Modifier.size(40.dp),
                )
            }
            Spacer(Modifier.height(Space.xxl))
            Text(
                "چقدر تومن قفل شده",
                fontSize = 26.sp,
                fontWeight = FontWeight.Black,
                color = Hero.strong,
            )
            Spacer(Modifier.height(Space.m))
            Text(
                "برای دیدن دارایی‌هات، اثر انگشت یا رمز گوشی‌ات رو وارد کن.",
                fontSize = 15.sp,
                lineHeight = 25.sp,
                textAlign = TextAlign.Center,
                color = Hero.muted,
            )
            Spacer(Modifier.height(Space.huge))
            Button(
                onClick = onUnlock,
                shape = RoundedCornerShape(Radius.pill),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Cta.fill,
                    contentColor = Cta.ink,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 60.dp),
            ) { Text("باز کردن", fontSize = 18.sp, fontWeight = FontWeight.Bold) }
        }
    }
}

/**
 * Its own page rather than a row in the asset list: a permanent switch sitting under her
 * holdings reads as one of them, and this is where anything else configurable will land.
 */
@Composable
fun SettingsScreen(
    name: String,
    themeMode: ThemeMode,
    lockEnabled: Boolean,
    widgetLock: Boolean,
    smsEnabled: Boolean,
    bankAccounts: List<BankAccount>,
    disabledBanks: Set<String>,
    categories: List<Category>,
    family: FamilyState,
    activity: FragmentActivity,
    onCompanion: () -> Unit,
    onNameChange: (String) -> Unit,
    onThemeChange: (ThemeMode) -> Unit,
    onSmsChange: (Boolean) -> Unit,
    onBankChange: (String, Boolean) -> Unit,
    onLockChange: (Boolean) -> Unit,
    onWidgetLockChange: (Boolean) -> Unit,
    /** The door to «دسته‌بندی‌ها», which is a room of its own now rather than a strip here. */
    onCategories: () -> Unit,
    /** Drops and rebuilds everything rebuildable — see [AppVm.clearCaches]. */
    onClearCache: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val available = remember { canLock(context) }

    // Switching it on is the moment to ask for the permissions — not at launch, where she has
    // no idea what an app about money wants with her messages. Denied leaves it switched off.
    // Asked as a pair, but only READ_SMS is load-bearing: it is what every balance is read from.
    // RECEIVE_SMS alone being denied just means notifications ride the six-hour sweep instead of
    // arriving with the message — see [SmsReceiver] — so it gets no say in [granted].
    var granted by remember { mutableStateOf(canReadSms(context)) }
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
    val askSms = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        val ok = grants[Manifest.permission.READ_SMS] == true
        granted = ok
        onSmsChange(ok)
    }
    // Held locally while typing so each keystroke is not a disk write; committed on leaving.
    var draft by remember { mutableStateOf(name) }

    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .imePadding()
                // A switch per bank makes this page taller than the screen on a phone that has
                // more than a couple of them.
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Space.xl),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ScreenTitle("تنظیمات", modifier = Modifier.weight(1f))
                PillButton(
                    "ذخیره",
                    { onNameChange(draft); onBack() },
                    voice = ButtonVoice.PRIMARY,
                    fontSize = 15.sp,
                )
            }

            // First on the page and above its own label, which is the whole compensation for
            // giving up a tab: everything below here is a switch she flips once, this is a
            // place she goes. A card directly under the title, with no heading over it, is the
            // page's headline item — the settings equivalent of the slot it used to hold.
            // Not in the lite edition, which has no household and never had the tab either.
            if (!BuildConfig.LITE) {
                Spacer(Modifier.height(Space.s))
                CompanionRow(family, onCompanion)
            }

            SectionLabel("اسمت")
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it.take(24) },
                singleLine = true,
                placeholder = { Text("مثلاً مریم") },
                shape = RoundedCornerShape(Radius.field),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onNameChange(draft) }),
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = "اسمت" },
            )
            Text(
                "بالای برنامه باهاش بهت سلام می‌کنیم.",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Space.s, start = Space.xs),
            )

            SectionLabel("ظاهر")
            ThemePicker(themeMode, onThemeChange)

            SectionLabel("پیامک‌های بانک")
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
                modifier = Modifier.padding(top = Space.s, start = Space.xs, end = Space.xs),
            )
            // The other way this can be set up wrong and still look like it is working — and the
            // one she has no way of guessing, because nothing on screen is missing. Every figure
            // is right; they just arrive hours after the spend, on a phone whose OEM suspended the
            // app that was going to read them. Only drawn when the phone is actually restricted
            // *and* she reads messages, so a stock phone never sees a warning about a problem it
            // does not have.
            if (smsEnabled && granted && !unrestricted) {
                Spacer(Modifier.height(Space.m))
                Text(
                    "این گوشی چقدر تومن رو می‌خوابونه، برای همین ممکنه پیامک بانک با چند ساعت " +
                        "تأخیر خونده بشه. برای این‌که همون لحظه خونده بشه، اجازه بده بیدار بمونه.",
                    fontSize = 13.sp,
                    lineHeight = 22.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = Space.s, start = Space.xs, end = Space.xs),
                )
                TextButton(onClick = { askBackgroundExemption(context) }) {
                    Text("اجازه بده بیدار بمونه", fontSize = 15.sp)
                }
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
                modifier = Modifier.padding(top = Space.m, start = Space.xs, end = Space.xs),
            )

            if (smsEnabled && granted) {
                // One switch per bank actually seen, not per bank we know how to read: a list
                // of fifteen banks she has no account at is a list nobody reads.
                val banks = bankAccounts.map { it.bank }.distinct()
                if (banks.isEmpty()) {
                    Text(
                        "هنوز پیامک بانکی نرسیده. اولین پیامک که بیاد، بانک اینجا نشون داده می‌شه.",
                        fontSize = 13.sp,
                        lineHeight = 20.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = Space.l, start = Space.xs, end = Space.xs),
                    )
                } else {
                    // One band, as on the asset list: these are N of the same thing, and N
                    // separate cards made each bank look like its own section.
                    Spacer(Modifier.height(Space.s))
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
            }

            SectionLabel("دسته‌بندی‌ها")
            CategoriesRow(
                mine = categories.count { !it.builtin && !it.archived },
                onClick = onCategories,
            )

            SectionLabel("امنیت")
            // One band of two rows, like the banks: the app's lock and the widget's mask are
            // siblings, not the same switch — she may want the app guarded but the number
            // glanceable on the home screen, or the other way round.
            SettingCard(
                mark = {
                    Icon(
                        Icons.Rounded.Lock,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(22.dp),
                    )
                },
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

            // The recovery path. Android backup is off on purpose, so without this file a lost
            // phone loses the messages, the anchors and every decision she ever made.
            SectionLabel("پشتیبان")
            BackupBand(activity)

            // Last of the real settings, above the version line: a repair tool, not a thing
            // about her money.
            SectionLabel("حافظهٔ موقت")
            CacheRow(onClearCache)

            // The answer to "which version do you have?" over the phone, without her having
            // to find the system app-info page. A fixed gap, not weight(1f): inside a scrolling
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
}

/**
 * The way back from a stale cache, and the sentence that makes it safe to press: everything it
 * drops is rebuilt — rates refetched, the ledger re-derived from the stored messages — and
 * nothing she typed, filed or received is touched. One tap, no confirm: a control that only
 * deletes the rebuildable has nothing to warn about.
 */
@Composable
private fun CacheRow(onClear: () -> Unit) {
    var cleared by remember { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.group))
            .background(MaterialTheme.colorScheme.surface)
            .padding(Space.l),
    ) {
        Text(
            "نرخ‌های ذخیره‌شده، نشان کوین‌ها و جدول‌هایی که از روی پیامک‌ها ساخته شدن پاک و از " +
                "نو ساخته می‌شن. پیامک‌ها، دسته‌بندی‌ها و هر چیزی که خودت وارد کردی دست نمی‌خوره.",
            fontSize = 13.sp,
            lineHeight = 22.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(Space.m))
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

/**
 * The door to «دسته‌بندی‌ها» — a door because the thing behind it is a screen now, and the one
 * fact worth carrying on the door itself is how many categories are hers: «هنوز هیچ» is the
 * invitation, a count is the receipt.
 */
@Composable
private fun CategoriesRow(mine: Int, onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.group))
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
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                contentAlignment = Alignment.Center,
            ) {
                GlyphIcon(
                    CategoryGlyph.TAG,
                    MaterialTheme.colorScheme.onSurface,
                    size = 22.dp,
                )
            }
            Column(Modifier.padding(horizontal = Space.m).weight(1f)) {
                Text("دسته‌بندی‌ها", fontSize = 17.sp, fontWeight = FontWeight.Bold)
                Text(
                    if (mine == 0) {
                        "دسته‌های خرج و دخل رو ببین و دستهٔ خودت رو بساز"
                    } else {
                        "${faNumber(mine.toDouble())} دستهٔ خودت، کنار دسته‌های برنامه"
                    },
                    fontSize = 13.sp,
                    lineHeight = 20.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
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

/** "1.0" -> "۱٫۰" so the one latin run on an otherwise Persian page disappears. */
private fun faVersion(v: String): String =
    v.map { c -> if (c in '0'..'9') '۰' + (c - '0') else if (c == '.') '٫' else c }
        .joinToString("")

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
            // Real headings, so TalkBack can jump between sections of this long page.
            .semantics { heading() },
    )
}

/**
 * The way into [CompanionScreen], and the only row on this page that is a door rather than a
 * switch — so it is the only one that says what is behind it instead of what it does.
 *
 * The subtitle is the promotion. «خانواده ›» on its own is a word she has no reason to press;
 * a household of one is an unfinished setup and says so, and an unpaired one makes the offer in
 * the words she would use for it herself. This is the line that has to do the work the tab was
 * doing, so it is never the same line twice.
 *
 * Titled «خانواده» and not «همراه»: the tab said همراه because 11sp in a 56dp bar has room for
 * one short word, and the page it opens has called itself خانواده all along. With the bar out
 * of the way there is no reason for the app to have two names for one thing.
 */
@Composable
private fun CompanionRow(family: FamilyState, onClick: () -> Unit) {
    val subtitle = when {
        !family.paired -> "خرج‌های خونه رو با هم توی یک دفتر ببینید"
        family.members.size < 2 -> "هنوز کسی اضافه نشده — دعوتش کن"
        else -> "${faNumber(family.members.size.toDouble())} عضو"
    }
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.group))
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
                    // The one row on the page with a real mark instead of an emoji, and the
                    // plate is what keeps that from reading as a missing emoji: same disc,
                    // same size, same place as the ✉️ and 🔒 below it.
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                contentAlignment = Alignment.Center,
            ) { CompanionGlyph(MaterialTheme.colorScheme.onSurface) }
            Column(Modifier.padding(horizontal = Space.m).weight(1f)) {
                Text("خانواده", fontSize = 17.sp, fontWeight = FontWeight.Bold)
                Text(
                    subtitle,
                    fontSize = 13.sp,
                    lineHeight = 20.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
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

/** Three choices, not a switch: a two-state toggle cannot express "follow the phone". */
@Composable
private fun ThemePicker(current: ThemeMode, onChange: (ThemeMode) -> Unit) = SegmentedChoice(
    options = ThemeMode.entries,
    selected = current,
    label = { it.fa },
    onSelect = onChange,
    fontSize = 16.sp,
)

/** One switched setting: its mark, what it does, what it currently means, and the switch. */
@Composable
fun SettingCard(
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
            // thing at least agree about what an icon looks like in this app.
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

/**
 * The two backup rows and everything behind them: the passphrase sheets, the file pickers, and
 * the words that report how it went.
 *
 * The ViewModel is fetched from the activity rather than passed down — [SettingsScreen]'s call
 * site is owned elsewhere, and the same instance MainActivity built is what the provider returns.
 */
@Composable
private fun BackupBand(activity: FragmentActivity) {
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
        modifier = Modifier.padding(top = Space.s, start = Space.xs, end = Space.xs),
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

/** A row that is a door, in the band the switches wear — [CategoriesRow]'s shape, groupable. */
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
