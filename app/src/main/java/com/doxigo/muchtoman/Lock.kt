package com.doxigo.muchtoman

import android.Manifest
import android.content.Context
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

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
            .background(Brush.verticalGradient(listOf(Hero.top, Hero.bottom))),
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
                    tint = Hero.gold,
                    modifier = Modifier.size(40.dp),
                )
            }
            Spacer(Modifier.height(Space.xxl))
            Text(
                "چقدر تومن قفل شده",
                fontSize = 26.sp,
                fontWeight = FontWeight.ExtraBold,
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
                    containerColor = Hero.gold,
                    contentColor = Hero.bottom,
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
    /** Only ever called from the dev build's own section at the bottom of this page. */
    onDemoData: (Boolean) -> Unit,
    /** The door to «دسته‌بندی‌ها», which is a room of its own now rather than a strip here. */
    onCategories: () -> Unit,
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
                Text(
                    "تنظیمات",
                    fontSize = 28.sp,
                    fontWeight = FontWeight.ExtraBold,
                    modifier = Modifier
                        .weight(1f)
                        .semantics { heading() },
                )
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
                emoji = "✉️",
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
                            // VS16 pinned: bare U+1F3DB defaults to the monochrome text
                            // glyph on spec-following renderers, unlike the ✉️ card above.
                            emoji = "🏛️",
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
                emoji = "🔒",
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
                emoji = "🙈",
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

            // Only ever on the dev build — see [AppVm.setDemoData]. Last on the page, under the
            // real settings and above the version line, because it is a bench tool and not a
            // thing about her money.
            if (BuildConfig.DEMO) {
                SectionLabel("داده‌های نمایشی")
                DemoDataRow(onDemoData)
            }

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
 * The bench tool: fourteen months of invented household, in and out again.
 *
 * Two buttons rather than a switch, and the second one is a confirm, because «پاک کردن» is the
 * only control on this page that deletes transactions — and on a dev build the ledger it is
 * pointed at is usually the one somebody is halfway through testing against.
 *
 * It says what it writes. A demo generator whose output is a mystery is a thing you end up
 * debugging instead of the feature you built it for.
 */
@Composable
private fun DemoDataRow(onDemoData: (Boolean) -> Unit) {
    var confirming by remember { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.group))
            .background(MaterialTheme.colorScheme.surface)
            .padding(Space.l),
    ) {
        Text(
            "${faNumber(DEMO_MONTHS.toDouble())} ماه تراکنش ساختگی، با حقوق و اجاره و قسط و خرید " +
                "روزانه — برای اینکه بازه‌های «۳ ماه» و «۶ ماه» و «۱ سال» گزارش‌ها را بشود دید. " +
                "همهٔ ردیف‌ها نشان‌دار هستند و «پاک کردن» دقیقاً همان‌ها را برمی‌دارد.",
            fontSize = 13.sp,
            lineHeight = 22.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(Space.s))
        // Asking replaces the row rather than growing it: three buttons and a question do not
        // fit across a 320dp phone, and «مطمئنم» broken over two lines is a control that looks
        // like a bug at the exact moment it is asking permission to delete something.
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (confirming) {
                Text(
                    "پاک بشه؟",
                    fontSize = 15.sp,
                    maxLines = 1,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { confirming = false }) {
                    Text("بی‌خیال", fontSize = 15.sp, maxLines = 1)
                }
                TextButton(onClick = { confirming = false; onDemoData(false) }) {
                    Text("آره", fontSize = 15.sp, maxLines = 1, color = MaterialTheme.colorScheme.error)
                }
            } else {
                TextButton(onClick = { onDemoData(true) }) {
                    Text("ساختن", fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { confirming = true }) {
                    Text("پاک کردن", fontSize = 15.sp, maxLines = 1)
                }
            }
        }
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

/** One switched setting: emoji, what it does, what it currently means, and the switch. */
@Composable
fun SettingCard(
    emoji: String,
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = RoundedCornerShape(Radius.group),
    divided: Boolean = false,
    /** Replaces the emoji plate where the row has a real mark of its own. */
    badge: (@Composable () -> Unit)? = null,
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
                ) { Text(emoji, fontSize = 20.sp) }
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
