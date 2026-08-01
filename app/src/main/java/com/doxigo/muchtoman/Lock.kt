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
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
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
import androidx.compose.ui.text.font.FontWeight
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
            .setSubtitle("برای دیدن دارایی‌هایتان، هویت خود را تأیید کنید")
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
                "چقدر تومن قفل است",
                fontSize = 26.sp,
                fontWeight = FontWeight.ExtraBold,
                color = Hero.strong,
            )
            Spacer(Modifier.height(Space.m))
            Text(
                "برای دیدن دارایی‌هایتان، اثر انگشت یا رمز گوشی را وارد کنید.",
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
    smsEnabled: Boolean,
    bankAccounts: List<BankAccount>,
    disabledBanks: Set<String>,
    activity: FragmentActivity,
    onNameChange: (String) -> Unit,
    onThemeChange: (ThemeMode) -> Unit,
    onSmsChange: (Boolean) -> Unit,
    onBankChange: (String, Boolean) -> Unit,
    onLockChange: (Boolean) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val available = remember { canLock(context) }

    // Switching it on is the moment to ask for the permission — not at launch, where she has
    // no idea what an app about money wants with her messages. Denied leaves it switched off.
    var granted by remember { mutableStateOf(canReadSms(context)) }
    val askSms = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { ok -> granted = ok; onSmsChange(ok) }
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
                TextButton(onClick = { onNameChange(draft); onBack() }) {
                    Text("بازگشت", fontSize = 17.sp)
                }
            }

            SectionLabel("نام شما")
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
                    .semantics { contentDescription = "نام شما" },
            )
            Text(
                "برای سلام گفتن در بالای برنامه استفاده می‌شود.",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Space.s, start = Space.xs),
            )

            SectionLabel("نمایش")
            ThemePicker(themeMode, onThemeChange)

            SectionLabel("پیامک بانک")
            SettingCard(
                emoji = "✉️",
                title = "خواندن پیامک بانک",
                subtitle = "موجودی حساب‌ها از روی پیامک‌های بانک به‌روز می‌شود.",
                checked = smsEnabled && granted,
                onChange = { on ->
                    if (on && !granted) askSms.launch(Manifest.permission.READ_SMS)
                    else onSmsChange(on)
                },
            )
            // Naming the banks is not decoration: only messages from their own numbers are read
            // now, so a bank missing from this line is the one and only reason its balance
            // never appears — and without the line there is nothing to tell her that.
            val watched = Bank.entries.filter { it.numbers.isNotEmpty() }.joinToString("، ") { it.fa }
            Text(
                if (smsEnabled && granted) {
                    "پیامک $watched خوانده می‌شود. پیامک‌ها فقط روی همین گوشی خوانده می‌شوند و هیچ‌جا فرستاده نمی‌شوند."
                } else {
                    "فقط پیامک $watched خوانده می‌شود، آن هم روی همین گوشی."
                },
                fontSize = 13.sp,
                lineHeight = 20.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Space.s, start = Space.xs, end = Space.xs),
            )

            if (smsEnabled && granted) {
                // One switch per bank actually seen, not per bank we know how to read: a list
                // of fifteen banks she has no account at is a list nobody reads.
                val banks = bankAccounts.map { it.bank }.distinct()
                if (banks.isEmpty()) {
                    Text(
                        "هنوز پیامک بانکی پیدا نشده است. با رسیدن اولین پیامک، بانک آن اینجا می‌آید.",
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

            SectionLabel("امنیت")
            LockSetting(enabled = lockEnabled, available = available, onChange = onLockChange)

            if (!available) {
                Text(
                    "برای استفاده از قفل، ابتدا در تنظیمات گوشی رمز یا اثر انگشت تعریف کنید.",
                    fontSize = 13.sp,
                    lineHeight = 20.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = Space.m, start = Space.xs, end = Space.xs),
                )
            }

            // The answer to "which version do you have?" over the phone, without her having
            // to find the system app-info page. A fixed gap, not weight(1f): inside a scrolling
            // column a weighted spacer has nothing to push against.
            Spacer(Modifier.height(Space.xxl))
            Text(
                "چقدر تومن • نسخهٔ ${faVersion(BuildConfig.VERSION_NAME)}",
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

@Composable
fun LockSetting(enabled: Boolean, available: Boolean, onChange: (Boolean) -> Unit) =
    SettingCard(
        emoji = "🔒",
        title = "قفل برنامه",
        // Always what the setting does. When it is unavailable, the helper text below the
        // card already carries the fix — and says where, which this line never did.
        subtitle = "باز کردن با اثر انگشت یا رمز گوشی",
        checked = enabled,
        onChange = onChange,
        enabled = available,
    )
