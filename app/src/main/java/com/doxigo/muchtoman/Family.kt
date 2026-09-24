package com.doxigo.muchtoman

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color as AndroidColor
import android.graphics.Matrix
import android.media.ExifInterface
import android.media.ThumbnailUtils
import android.net.Uri
import android.util.Base64
import android.util.LruCache
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.inset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

fun qrBitmap(content: String, size: Int = 720): Bitmap {
    val hints = mapOf(
        EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
        EncodeHintType.MARGIN to 2,
        EncodeHintType.CHARACTER_SET to "UTF-8",
    )
    val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size, hints)
    val bitmap = Bitmap.createBitmap(matrix.width, matrix.height, Bitmap.Config.RGB_565)
    val pixels = IntArray(matrix.width * matrix.height)
    for (y in 0 until matrix.height) {
        val row = y * matrix.width
        for (x in 0 until matrix.width) {
            pixels[row + x] = if (matrix.get(x, y)) AndroidColor.BLACK else AndroidColor.WHITE
        }
    }
    bitmap.setPixels(pixels, 0, matrix.width, 0, 0, matrix.width, matrix.height)
    return bitmap
}

/**
 * Her — on the home bar's way into تنظیمات and the card at the top of it. It used to be the mark
 * on the way into خانواده, which is why it was drawn as a household first.
 *
 * This was a tab icon — two overlapping rounded rectangles first, her phone and the one she set
 * up for someone else, which is a perfectly good drawing of the mechanism and the universal
 * glyph for *copy*. Nobody reads a screen's plumbing off its label; they read who it is for.
 *
 * One figure, and it took three tries to get there. Two heads over a shared shoulder line was
 * the obvious drawing of a household, and it is also — at any head size, any spacing, any
 * curvature — a face. Two round marks above a symmetric arc is a face; small ones set wide over
 * a flat arc is a face wearing a frown. There is no tuning out of it, because the reading does
 * not come from the proportions, it comes from the arrangement.
 *
 * So: one person. A single circle over an arc cannot be misread as a face — there is only one
 * eye — and it cannot be misread as *copy*, which is the whole reason the two cards had to go.
 * It keeps the tab set's pen ([pen]) even though it left the bar: it is still the app's only
 * drawing of a person, and the row it sits on is beside rows wearing emoji, which makes the
 * one-pen discipline the only thing holding it to the app.
 */
@Composable
fun CompanionGlyph(tint: Color, size: Dp = 24.dp) {
    Canvas(Modifier.size(size)) {
        inset(this.size.minDimension * 0.125f) {
            val w = this.size.width
            val h = this.size.height
            drawCircle(tint, w * 0.2f, Offset(w * 0.5f, h * 0.26f), style = pen())
            drawArc(
                color = tint,
                startAngle = 180f,
                sweepAngle = 180f,
                useCenter = false,
                topLeft = Offset(w * 0.08f, h * 0.56f),
                // Only the top half of the box is swept, so a tall box draws a shallow curve.
                size = Size(w * 0.84f, h * 0.8f),
                style = pen(),
            )
        }
    }
}

/**
 * خانواده — a room behind تنظیمات, laid out the way its siblings are: the page frame, the section
 * labels, the bands and the switch rows are [SettingsPage]'s own, so walking in from پیامک‌های
 * بانک does not feel like changing apps.
 *
 * It used to be one tall column in its own dialect. My row in the member list carried the name
 * field, the face picker, both sharing switches and the bank chips, so «who is in this family»
 * was a form with somebody else's row dangling under it, and the page ended in three headed
 * paragraphs each with a red button. Now the order is the order she needs it in: who is here and
 * the way to add one, what this phone sends them, what never leaves it, and — last, quiet until
 * touched — the two ways out.
 *
 * The rarely-changed things moved one tap in. My name and face open from my row ([MeSheet]); a
 * member's removal opens from theirs ([MemberSheet]). Rows stay rows, and the page reads in a
 * glance instead of a scroll.
 */
@Composable
fun CompanionScreen(
    state: FamilyState,
    suggestedName: String,
    onStart: (String) -> Unit,
    onJoin: (String) -> Unit,
    /** The confirmed replace: bury this phone's household and join the scanned one. */
    onRejoin: () -> Unit,
    onDismissRejoin: () -> Unit,
    onNameChange: (String) -> Unit,
    /** The face I picked — blank, a stock emoji, or a `b64:` photo. See [FamilyMember.avatar]. */
    onAvatarChange: (String) -> Unit,
    onShareSmsChange: (Boolean) -> Unit,
    onShareAssetsChange: (Boolean) -> Unit,
    /** The banks this phone tracks, for the set-aside rows. Enum names, not Persian. */
    banks: List<String>,
    onExcludedBankToggle: (String) -> Unit,
    onInvite: () -> Unit,
    onSync: () -> Unit,
    /** Walks this phone out of the household. VM-side, because the cleanup needs a re-derive. */
    onLeave: () -> Unit,
    /** Re-keys the household. VM-side for the same re-derive; it ends with the fresh QR up. */
    onRenew: () -> Unit,
    /** How many ledger entries each member id has put in, for the rows to report. */
    contributions: Map<String, Int>,
    onBack: () -> Unit,
) {
    // The draft for the two forms that come before a household. Once there is one, the name is
    // edited in [MeSheet], which keeps a draft of its own.
    var name by remember(state.memberId, state.memberName, state.pendingPairing, suggestedName) {
        mutableStateOf(state.memberName.ifBlank { suggestedName })
    }

    // Removal runs from here rather than through the ViewModel: it is a leaf action — read
    // the session, speak to the server, write the ledger — with nothing the ViewModel
    // computes, and it finishes by handing control back to onSync to re-read what the
    // household looks like. That keeps the member list and the thing that shrinks it in one
    // file; leaving and renewal live VM-side, because their cleanup needs a re-derive.
    val appContext = LocalContext.current.applicationContext
    val actionScope = rememberCoroutineScope()
    var acting by remember { mutableStateOf(false) }
    var actionError by remember { mutableStateOf<String?>(null) }

    fun familyAction(failText: String, after: () -> Unit, act: suspend (SyncSession, DurableDb) -> Unit) {
        if (acting) return
        acting = true
        actionError = null
        actionScope.launch {
            val durable = DurableDb.get(appContext)
            runCatching {
                val session = loadSession(durable) ?: error("no session")
                act(session, durable)
            }.onSuccess {
                acting = false
                after()
            }.onFailure {
                acting = false
                actionError = failText
            }
        }
    }

    val me = state.members.firstOrNull { it.id == state.memberId }
    val myName = state.memberName.ifBlank { me?.name.orEmpty() }
    val others = state.members.filterNot { it.id == state.memberId }
    var editingMe by remember { mutableStateOf(false) }
    var openMember by remember { mutableStateOf<String?>(null) }
    val household = state.paired && state.pendingRejoin == null && state.pendingPairing == null

    SettingsPage("خانواده", onBack) {
        when {
            // Before the plain join: a link on a phone that already belongs somewhere is
            // this question, whatever the rest of the state says.
            state.pendingRejoin != null -> {
                Lead(FAMILY_LEAD)
                RejoinBlock(
                    working = state.working,
                    onConfirm = onRejoin,
                    onDismiss = onDismissRejoin,
                )
            }

            state.pendingPairing != null -> {
                Welcome("پیوستن به خانواده")
                NameForm(
                    name = name,
                    caption = "این اسم کنار تراکنش‌های تو دیده می‌شه.",
                    action = if (state.working) "در حال پیوستن..." else "پیوستن",
                    working = state.working,
                    onNameChange = { name = it.take(32) },
                    onSubmit = { onJoin(name.trim()) },
                )
            }

            !state.paired -> {
                Welcome("خرج‌های خونه رو با هم توی یک دفتر ببینید")
                NameForm(
                    name = name,
                    // Why the name is asked for is the sentence right above it; this says what comes next.
                    caption = "اعضای بعدی با کد دعوت وارد می‌شن.",
                    action = if (state.working) "در حال ساختن..." else "ساختن خانواده",
                    working = state.working,
                    onNameChange = { name = it.take(32) },
                    onSubmit = { onStart(name.trim()) },
                )
            }

            else -> {
                Lead(FAMILY_LEAD)
                // One band: me first, because mine is the only row anybody can change, then
                // everyone else, then the way to add one — a list and the way to grow it are
                // one object, as on بودجه.
                val rows = others.size + 2
                SectionHeading("اعضای خانواده", others.size + 1)
                MemberRow(
                    name = myName,
                    avatar = me?.avatar.orEmpty(),
                    mine = true,
                    founder = state.memberId == state.primaryMemberId,
                    contributions = contributions[state.memberId] ?: 0,
                    sharing = null,
                    shape = bandShape(0, rows),
                    divided = others.isNotEmpty(),
                    onClickLabel = "تغییر اسم و چهره",
                    onClick = { editingMe = true },
                )
                others.forEachIndexed { index, member ->
                    val founder = member.id == state.primaryMemberId
                    MemberRow(
                        name = member.name,
                        avatar = member.avatar,
                        mine = false,
                        founder = founder,
                        contributions = contributions[member.id] ?: 0,
                        sharing = member.sharesSms,
                        shape = bandShape(index + 1, rows),
                        divided = index < others.lastIndex,
                        onClickLabel = "حذف از خانواده",
                        // The founder's row opens nothing: removal is all the sheet offers,
                        // and the server refuses it for them, so the door would be a lie.
                        onClick = if (founder) null else ({ openMember = member.id }),
                    )
                }
                AddMemberRow(
                    label = if (state.pairingUrl == null) "دعوت عضو جدید" else "ساختن کد تازه",
                    enabled = !state.working,
                    shape = bandShape(rows - 1, rows),
                    onClick = onInvite,
                )
                state.pairingUrl?.let { InviteCode(it) }

                Spacer(Modifier.height(Space.l))
                DoorRow(
                    title = if (state.working) "در حال همگام‌سازی..." else "همگام‌سازی الان",
                    subtitle = state.lastSync.orEmpty(),
                    glyph = CategoryGlyph.SWAP,
                    shape = bandShape(0, 1),
                    divided = false,
                    enabled = !state.working,
                    onClick = onSync,
                    chevron = false,
                )
            }
        }

        listOfNotNull(state.error, actionError).forEach { problem ->
            Text(
                problem,
                color = MaterialTheme.colorScheme.error,
                fontSize = 13.sp,
                lineHeight = 20.sp,
                modifier = Modifier
                    .padding(top = Space.m, start = Space.xs, end = Space.xs)
                    .semantics { liveRegion = LiveRegionMode.Polite },
            )
        }

        if (household) {
            // What this phone sends, as the switch rows every other settings room uses. Titles
            // are what is sent — the label above already says «sharing», and «اشتراک …» twice
            // over ran the titles past one line on a small phone. The lines under them are the
            // switch's state in words, one vocabulary for this band and the banks under it —
            // «فرستاده می‌شه» or «فقط روی همین گوشی» — short enough to stay one line, so no
            // verb is left hanging alone on a second.
            SectionHeading("اشتراک‌گذاری")
            SettingCard(
                mark = { TabMark { drawLedger(it) } },
                title = "تراکنش‌های پیامکی",
                subtitle = if (state.sharesSms) "برای خانواده فرستاده می‌شن" else "فقط روی همین گوشی می‌مونن",
                checked = state.sharesSms,
                onChange = onShareSmsChange,
                shape = bandShape(0, 2),
                divided = true,
            )
            SettingCard(
                mark = { TabMark { drawAssets(it) } },
                title = "دارایی‌ها",
                subtitle = if (state.sharesAssets) "فقط اسم و ارزش تومنی‌شون می‌ره" else "فقط روی همین گوشی می‌مونن",
                checked = state.sharesAssets,
                onChange = onShareAssetsChange,
                shape = bandShape(1, 2),
            )

            // The set-aside banks, only while something is being shared for them to be set
            // aside from. One veto for both directions: an excluded bank's transactions and its
            // balance stay home together. Switches rather than the chips they were — «on» for
            // «goes to the family» — so the rows read the way پیامک‌های بانک's already do, and
            // the bank's own logo replaces the category mark the chips could only guess at.
            if (banks.isNotEmpty() && (state.sharesSms || state.sharesAssets)) {
                SectionHeading("بانک‌ها")
                banks.forEachIndexed { index, bank ->
                    val shared = bank !in state.excludedBanks
                    SettingCard(
                        title = bankNameOf(bank),
                        subtitle = if (shared) "برای خانواده فرستاده می‌شه" else "فقط روی همین گوشی می‌مونه",
                        checked = shared,
                        onChange = { onExcludedBankToggle(bank) },
                        shape = bandShape(index, banks.size),
                        divided = index < banks.lastIndex,
                        badge = { BankLogo(bank, size = 44.dp) },
                    )
                }
                Helper("از بانک خاموش، نه تراکنشی فرستاده می‌شه نه موجودی.")
            }
        }

        SectionHeading("حریم خصوصی")
        Text(
            "متن خام پیامک هیچ‌وقت از گوشی صاحبش خارج نمی‌شه. فقط مبلغ، زمان، بانک، فروشنده و دسته‌بندیِ استخراج‌شده، رمز‌شده جابه‌جا می‌شن. " +
                "از دارایی‌ها هم فقط اسم و ارزش تومنی می‌ره؛ مقدار، آدرس کیف پول و شماره حساب هیچ‌وقت نه. " +
                "خاموش کردن اشتراک، موارد قبلی رو بعد از همگام‌سازی از دفتر بقیه حذف می‌کنه؛ چیزی که قبلاً دیده یا کپی شده قابل پس‌گرفتن نیست.",
            fontSize = 13.sp,
            lineHeight = 22.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = Space.xs, end = Space.xs),
        )

        if (state.paired) {
            // The two ways out, in one band at the foot of the page. Each row says what it is
            // for; the first tap turns it into the question and adds what cannot be undone, so
            // the page is not three paragraphs of consequences for things nobody is doing.
            Spacer(Modifier.height(Space.xxl))
            DangerRow(
                title = "خروج از خانواده",
                armedTitle = "مطمئنی؟ برای خروج دوباره بزن",
                subtitle = "دسترسی همین گوشی قطع می‌شه و دفتر مشترک از روش پاک می‌شه؛ تراکنش‌های خودت سر جاشون می‌مونن.",
                detail = "چیزی که بقیه قبلاً دیدن پس گرفته نمی‌شه.",
                shape = bandShape(0, 2),
                divided = true,
                enabled = !state.working && !acting,
                onConfirmed = onLeave,
            )
            DangerRow(
                title = "نو کردن خانواده",
                armedTitle = "مطمئنی؟ برای نو کردن دوباره بزن",
                subtitle = "برای وقتی که کسی رو حذف کردی و می‌خوای مطمئن باشی چیز تازه‌ای بهش نمی‌رسه.",
                // Plain about the mechanism, because the whole point of the action is a
                // promise about keys: removing someone does not take back the key they
                // already hold; this does.
                detail = "یک خانواده تازه با کلید تازه ساخته می‌شه و فقط تراکنش‌های همین گوشی دوباره فرستاده می‌شن. " +
                    "بقیه اعضا باید کد تازه رو دوباره اسکن کنن؛ خانواده قبلی دیگه به‌روز نمی‌شه و دفتر مشترک از نو شروع می‌شه.",
                shape = bandShape(1, 2),
                divided = false,
                enabled = !state.working && !acting,
                onConfirmed = onRenew,
            )
        }
    }

    if (editingMe) {
        MeSheet(
            name = myName,
            avatar = me?.avatar.orEmpty(),
            onName = onNameChange,
            onAvatar = onAvatarChange,
            onDismiss = { editingMe = false },
        )
    }
    others.firstOrNull { it.id == openMember }?.let { member ->
        MemberSheet(
            member = member,
            contributions = contributions[member.id] ?: 0,
            enabled = !state.working && !acting,
            error = actionError,
            onRemove = {
                familyAction("حذف نشد. اینترنتت رو چک کن.", after = { openMember = null; onSync() }) { session, durable ->
                    removeFamilyMember(session, durable, member.id)
                }
            },
            onDismiss = { openMember = null },
        )
    }
}

private const val FAMILY_LEAD =
    "تراکنش‌های اعضا در یک دفتر دیده می‌شن و اسم صاحب هر مورد همیشه کنارش میاد."

/** The sentence under the page title, at the size the other rooms open with. */
@Composable
private fun Lead(text: String) {
    Text(
        text,
        fontSize = 15.sp,
        lineHeight = 26.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = Space.xs, end = Space.xs),
    )
}

/** A qualifying sentence under a band, where the settings rooms put theirs. */
@Composable
private fun Helper(text: String) {
    Text(
        text,
        fontSize = 13.sp,
        lineHeight = 20.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = Space.m, start = Space.xs, end = Space.xs),
    )
}

/**
 * The page before there is a household: what it is, in the shape every empty screen in the app
 * already has — the room's own mark on the quiet green disc, a line, and the sentence that says
 * what happens. The house is the mark on the تنظیمات row that led here.
 */
@Composable
private fun Welcome(heading: String) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = Space.l),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(84.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            GlyphIcon(
                CategoryGlyph.HOUSE,
                MaterialTheme.colorScheme.onPrimaryContainer,
                size = 40.dp,
                stroke = 2.dp,
            )
        }
        Spacer(Modifier.height(Space.xl))
        Text(
            heading,
            fontSize = 22.sp,
            lineHeight = 32.sp,
            fontWeight = FontWeight.ExtraBold,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.semantics { heading() },
        )
        Spacer(Modifier.height(Space.s))
        Text(
            FAMILY_LEAD,
            fontSize = 15.sp,
            lineHeight = 25.sp,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Her name and the one button, for starting a household or joining one. The field is
 * pre-filled with the name تنظیمات greets her by, so most of the time this is one tap.
 */
@Composable
private fun NameForm(
    name: String,
    caption: String,
    action: String,
    working: Boolean,
    onNameChange: (String) -> Unit,
    onSubmit: () -> Unit,
) {
    val ready = name.isNotBlank() && !working
    Spacer(Modifier.height(Space.xxl))
    OutlinedTextField(
        value = name,
        onValueChange = onNameChange,
        singleLine = true,
        label = { Text("اسمت") },
        shape = RoundedCornerShape(Radius.field),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { if (ready) onSubmit() }),
        modifier = Modifier.fillMaxWidth(),
    )
    Text(
        caption,
        fontSize = 13.sp,
        lineHeight = 20.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = Space.s, start = Space.xs, end = Space.xs),
    )
    Spacer(Modifier.height(Space.xl))
    CtaButton(action, enabled = ready, onClick = onSubmit)
}

/** The page's one «press this»: [Cta] green, pill-shaped, a thumb tall. */
@Composable
private fun CtaButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(Radius.pill),
        colors = ButtonDefaults.buttonColors(containerColor = Cta.fill, contentColor = Cta.ink),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp),
    ) { Text(label, fontSize = 16.sp, fontWeight = FontWeight.Bold) }
}

/**
 * The QR for a new member, under the band it adds a row to. The code sits on a white plate in
 * both themes — a scanner reads dark modules on light, and a dark card would invert it — inside
 * the same surface the rows above wear, so it reads as part of the list rather than a poster.
 */
@Composable
private fun InviteCode(url: String) {
    val bitmap = remember(url) { qrBitmap(url) }
    Spacer(Modifier.height(Space.l))
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.group))
            .background(MaterialTheme.colorScheme.surface)
            .padding(Space.l),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "عضو جدید این کد رو اسکن کنه",
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(Space.l))
        Box(
            Modifier
                .clip(RoundedCornerShape(Radius.card))
                .background(Color.White)
                .padding(Space.m),
        ) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "کد دعوت خانواده",
                contentScale = ContentScale.Fit,
                modifier = Modifier.size(232.dp),
            )
        }
        Spacer(Modifier.height(Space.l))
        Text(
            "در اندروید، صفحه بازشده رو با اپ چقدر تومن باز کن. کد ده دقیقه اعتبار داره و یک‌بار مصرفه.",
            fontSize = 13.sp,
            lineHeight = 20.sp,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * A pairing link scanned on a phone that already has a household — the other side of
 * «نو کردن خانواده»: one member renewed, and this phone's QR-in-hand is the invitation to
 * follow. Nothing replaces anything silently; the words say what stops, and the confirm is
 * the same armed two-tap every destructive thing here wears. Her display name rides along,
 * so there is no name field to fill twice.
 */
@Composable
private fun RejoinBlock(
    working: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    SectionHeading("پیوستن به خانواده جدید")
    Text(
        "این کد مال یک خانواده دیگه‌ست. با پیوستن، خانواده قبلی روی این گوشی کنار می‌ره: " +
            "موارد مشترک اعضای قبلی دیگه به‌روز نمی‌شن و دفتر مشترک از نو شروع می‌شه. " +
            "تراکنش‌های خود این گوشی سر جاشون می‌مونن و با همون اسم قبلی وارد می‌شی.",
        fontSize = 13.sp,
        lineHeight = 22.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = Space.xs, end = Space.xs),
    )
    Spacer(Modifier.height(Space.m))
    ArmedAction(
        label = if (working) "در حال پیوستن..." else "پیوستن به خانواده جدید",
        armedLabel = "مطمئنی؟ خانواده قبلی کنار می‌ره — دوباره بزن",
        enabled = !working,
        onConfirmed = onConfirm,
    )
    TextButton(
        onClick = onDismiss,
        enabled = !working,
        colors = ButtonDefaults.textButtonColors(
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) { Text("بی‌خیال", fontSize = 15.sp) }
}

/**
 * The app's two-tap confirm, on the page where the destructive things are people: the first tap
 * only turns the label into the question — the same device the asset sheet and the budgets use —
 * so a stray tap can never cut a phone off the household or re-key it.
 */
@Composable
private fun ArmedAction(
    label: String,
    armedLabel: String,
    enabled: Boolean,
    onConfirmed: () -> Unit,
) {
    var armed by remember { mutableStateOf(false) }
    TextButton(
        onClick = { if (armed) { armed = false; onConfirmed() } else armed = true },
        enabled = enabled,
        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            if (armed) armedLabel else label,
            fontSize = 14.sp,
            fontWeight = if (armed) FontWeight.Bold else FontWeight.SemiBold,
            // Announced, or the two-tap safeguard is invisible to TalkBack — a second
            // double-tap acts with no confirmation ever perceived.
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
}

/**
 * A band's name, in the voice the settings rooms give theirs — more air above than below, and
 * announced as a heading so TalkBack can jump between them. A copy of تنظیمات's `SectionLabel`
 * rather than that one shared: بودجه keeps a private `SectionLabel(text, top)`, and a shared
 * one-argument overload would quietly win every call there.
 */
@Composable
private fun SectionHeading(title: String, count: Int? = null) {
    Text(
        if (count == null) title else "$title (${faNumber(count.toDouble())})",
        fontSize = 15.sp,
        fontWeight = FontWeight.ExtraBold,
        color = MaterialTheme.colorScheme.onBackground,
        modifier = Modifier
            .padding(top = Space.xxl, bottom = Space.m, start = Space.xs)
            .semantics { heading() },
    )
}

/** A tab's own mark in a settings disc — the دفتر slip, the دارایی coins — in the pen's ink. */
@Composable
private fun TabMark(draw: DrawScope.(Color) -> Unit) {
    val ink = MaterialTheme.colorScheme.onSurface
    Canvas(
        Modifier
            .size(24.dp)
            // The coins knock a hole out of the glyph, not the disc under it.
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen },
    ) { inset(3.dp.toPx()) { draw(ink) } }
}

/**
 * Decoded photo thumbnails, keyed by their base64 — a handful of members whose faces sit on
 * thousands of ledger rows, so the bytes are decoded once, not once per row scrolled in.
 * 16 slots: the server refuses a household a seventeenth device before this can overflow.
 */
private val faceCache = LruCache<String, ImageBitmap>(16)

/**
 * Whose it is, at a glance: the face they picked, or the initial when they never picked one.
 *
 * The initial was the whole design once — no avatar anywhere in the app — until the household
 * asked to tell rows apart faster than a letter can. So three shapes now, in [FamilyMember.avatar]:
 * blank falls to the initial, an emoji is drawn as text (a stock face costs no drawing and no
 * download), and a `b64:` photo is a thumbnail small enough to ride the same encrypted record
 * the name does. Bytes that fail to decode fall back to the initial, which is never wrong.
 *
 * Every disc is the same colour, including mine. Gold in this app means the action or the
 * answer, and an accent disc the size of a thumb sat next to the accent «من» chip, the accent
 * switch and the accent invite button — four of them down one column, none of which was what she
 * had come to press. Identity is not an action; the chip says which row is mine in a word.
 */
@Composable
internal fun MemberFace(name: String, avatar: String, size: Dp = 44.dp) {
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center,
    ) {
        val photo = if (avatar.startsWith(AVATAR_PHOTO_PREFIX)) {
            remember(avatar) {
                faceCache.get(avatar) ?: runCatching {
                    val bytes = Base64.decode(avatar.removePrefix(AVATAR_PHOTO_PREFIX), Base64.DEFAULT)
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
                }.getOrNull()?.also { faceCache.put(avatar, it) }
            }
        } else {
            null
        }
        when {
            photo != null -> Image(
                bitmap = photo,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            avatar.isNotBlank() && !avatar.startsWith(AVATAR_PHOTO_PREFIX) -> Text(
                avatar,
                style = faceGlyphStyle(size * 0.62f),
            )
            else -> Text(
                name.trim().firstOrNull()?.toString().orEmpty(),
                style = faceGlyphStyle(size * 0.66f).copy(
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                ),
            )
        }
    }
}

/**
 * One glyph, actually centred in its disc and actually filling it.
 *
 * Both halves are things a plain [Text] gets wrong inside a circle.
 *
 * Centring first, because it was the worse of the two: a bare `Text` inherits the theme's line
 * height, and that is a fixed `sp` figure sized for a paragraph. A ten-point initial was being
 * laid out in a twenty-four-point line box and then centred *as that box* inside a
 * seventeen-point disc, which is why the letter sat high and looked adrift. The box is pinned to
 * the glyph here instead — the font's own padding off, the leading trimmed at both ends — so
 * what gets centred is the ink. The line height stays above the glyph's own ascent and descent
 * on purpose: trimming is what shrinks the box, and a line height *below* them would be a
 * descender clipped by the disc, which is a worse bug than the one being fixed.
 *
 * Size second: a caller-chosen `sp` bore no relation to the disc it sat in, so one face filled
 * two fifths of the family row and another three fifths of the ledger badge. The fraction of the
 * diameter is the thing that should be constant. The letter takes the larger fraction of the two
 * because it inks only the middle of its em box where an emoji inks the whole of one — equal
 * fractions would have drawn the letter half the size of the face beside it.
 *
 * Both come off `Dp` rather than scaling with the reader's font setting: the disc does not grow
 * with it, and a glyph that did would spill out of the circle it names.
 */
private fun faceGlyphStyle(glyph: Dp) = TextStyle(
    fontSize = glyph.value.sp,
    lineHeight = (glyph.value * 1.4f).sp,
    platformStyle = PlatformTextStyle(includeFontPadding = false),
    lineHeightStyle = LineHeightStyle(
        alignment = LineHeightStyle.Alignment.Center,
        trim = LineHeightStyle.Trim.Both,
    ),
)

/**
 * A picked photo, shrunk to the one size any screen ever draws it and packed into
 * [FamilyMember.avatar]'s `b64:` shape — small enough that the member record carrying it stays
 * far under the sync server's body cap. Null when the picked file is not an image.
 *
 * Sampled decode first, so a twelve-megapixel camera roll photo never materialises; then
 * turned upright by its EXIF flag, because a selfie stored sideways would crop sideways; then
 * centre-cropped square, which is what the circle mask shows of it anyway.
 */
fun avatarThumbnail(context: Context, uri: Uri): String? = runCatching {
    val resolver = context.contentResolver
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (minOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= AVATAR_PX) sample *= 2
    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
    val raw = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        ?: return null
    val turn = resolver.openInputStream(uri)?.use { stream ->
        when (
            @Suppress("DEPRECATION")
            ExifInterface(stream)
                .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        ) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
    } ?: 0f
    val upright = if (turn == 0f) raw else Bitmap.createBitmap(
        raw, 0, 0, raw.width, raw.height, Matrix().apply { postRotate(turn) }, true
    )
    val square = ThumbnailUtils.extractThumbnail(upright, AVATAR_PX, AVATAR_PX)
    val bytes = ByteArrayOutputStream()
        .also { square.compress(Bitmap.CompressFormat.JPEG, 78, it) }
        .toByteArray()
    (AVATAR_PHOTO_PREFIX + Base64.encodeToString(bytes, Base64.NO_WRAP))
        .takeIf { it.length <= AVATAR_B64_MAX }
}.getOrNull()

/**
 * One face on the picker row. A radio, not a button: the four choices are one exclusive set,
 * and TalkBack should say which one is in use, not just that four discs are pressable.
 */
@Composable
private fun FaceChoice(
    selected: Boolean,
    label: String,
    onPick: () -> Unit,
    face: @Composable () -> Unit,
) {
    Box(
        Modifier
            .clip(CircleShape)
            .then(
                if (selected) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, CircleShape)
                else Modifier
            )
            .selectable(selected = selected, role = Role.RadioButton, onClick = onPick)
            .padding(3.dp)
            .semantics { contentDescription = label },
    ) { face() }
}

/**
 * Whether this person's transactions reach the family ledger.
 *
 * It was a 9dp dot and a sentence, which put the one fact anyone scans this list for into the
 * smallest element on the row. A labelled pill says it in words and in colour at once — the
 * same tinted construction the hero's change pill uses — so it survives both a glance and a
 * reader who cannot tell the two hues apart.
 */
@Composable
private fun ShareStatus(sharing: Boolean) {
    val tone =
        if (sharing) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.outline
    Row(
        Modifier
            .clip(RoundedCornerShape(Radius.pill))
            .background(tone.copy(alpha = 0.18f))
            .padding(horizontal = Space.m, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(tone))
        Spacer(Modifier.size(6.dp))
        Text(
            if (sharing) "به اشتراک می‌ذاره" else "خصوصی",
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = tone,
        )
    }
}

/** How much of the family ledger came from this person. */
private fun contributionsFa(count: Int): String =
    if (count > 0) "${faNumber(count.toDouble())} تراکنش" else "هنوز تراکنشی نفرستاده"

/**
 * One person in the household, as a row: face, name, what they have put in, what they share.
 *
 * Every row is the same height and the same anatomy, mine included — the list is a list of
 * peers, and it only reads as one when no row is a form. The founder is said in words on the
 * second line rather than as a second chip beside «من»: two pills and a face on one line was
 * the busiest thing on the page, and neither pill was something to press.
 */
@Composable
private fun MemberRow(
    name: String,
    avatar: String,
    mine: Boolean,
    founder: Boolean,
    contributions: Int,
    /** Theirs, as the pill. Null on my own row, where the switches further down say it. */
    sharing: Boolean?,
    shape: Shape,
    divided: Boolean,
    onClickLabel: String,
    /** Null when the row leads nowhere — the founder's, seen by anyone else. */
    onClick: (() -> Unit)?,
) {
    Box(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface),
    ) {
        Row(
            Modifier
                .then(
                    if (onClick != null) {
                        Modifier.clickable(role = Role.Button, onClickLabel = onClickLabel, onClick = onClick)
                    } else {
                        Modifier
                    },
                )
                .padding(horizontal = Space.l, vertical = Space.l),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MemberFace(name, avatar)
            Column(Modifier.padding(horizontal = Space.m).weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        name,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (mine) {
                        Spacer(Modifier.size(Space.s))
                        Text(
                            "من",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier
                                .clip(RoundedCornerShape(Radius.pill))
                                .background(MaterialTheme.colorScheme.primary)
                                .padding(horizontal = Space.s, vertical = 2.dp),
                        )
                    }
                }
                Text(
                    if (founder) "سرپرست • ${contributionsFa(contributions)}" else contributionsFa(contributions),
                    fontSize = 13.sp,
                    lineHeight = 20.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            if (sharing != null) ShareStatus(sharing)
            if (onClick != null) {
                Icon(
                    Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        // Inset to where the text starts, like every band in the app: a rule under the face
        // cuts the row in half instead of separating it from the next one.
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
 * The member band's last row: the way to add one. A quiet well with the pen's «+», the shape
 * بودجه ends its bands with — the loud green is kept for the answer inside a sheet, and a filled
 * slab under the list read as the page's main event on a household that is already whole.
 */
@Composable
private fun AddMemberRow(label: String, enabled: Boolean, shape: Shape, onClick: () -> Unit) {
    val ink = if (enabled) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(role = Role.Button, enabled = enabled, onClick = onClick)
            .heightIn(min = 56.dp)
            .padding(horizontal = Space.l),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        PlusMark(ink, size = 18.dp)
        Spacer(Modifier.size(Space.s))
        Text(label, fontWeight = FontWeight.Bold, color = ink)
    }
}

/**
 * One of the two ways out, as a row that is its own confirm.
 *
 * At rest it says what it is for, in one line. The first tap turns the title into the question
 * and adds the part that cannot be taken back, so the consequence is on screen at the one moment
 * it is being decided — not as a standing paragraph over a button nobody is about to press. The
 * title is announced, or the armed state is invisible to TalkBack.
 */
@Composable
private fun DangerRow(
    title: String,
    armedTitle: String,
    subtitle: String,
    detail: String,
    shape: Shape,
    divided: Boolean,
    enabled: Boolean,
    onConfirmed: () -> Unit,
) {
    var armed by remember { mutableStateOf(false) }
    val tone = MaterialTheme.colorScheme.error.let { if (enabled) it else it.copy(alpha = 0.38f) }
    Box(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button, enabled = enabled) {
                    if (armed) {
                        armed = false
                        onConfirmed()
                    } else {
                        armed = true
                    }
                }
                .animateContentSize(Motion.settle())
                .padding(Space.l),
        ) {
            Text(
                if (armed) armedTitle else title,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                color = tone,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
            Text(
                subtitle,
                fontSize = 13.sp,
                lineHeight = 20.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
            if (armed) {
                Text(
                    detail,
                    fontSize = 13.sp,
                    lineHeight = 20.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(top = Space.s),
                )
            }
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
 * Somebody else in the household, and the one thing I can do about them.
 *
 * Removal used to sit on every row as a red line of its own, so a household of four was a list
 * with three warnings in it. It is here now, one tap in, behind the same two-tap confirm as
 * every other destructive thing on the page, with the honest sentence above it: it cuts their
 * phone's sync, and it cannot un-see anything.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MemberSheet(
    member: FamilyMember,
    contributions: Int,
    enabled: Boolean,
    error: String?,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
) {
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                MemberFace(member.name, member.avatar, size = 56.dp)
                Spacer(Modifier.size(Space.l))
                Column(Modifier.weight(1f)) {
                    SheetTitle(member.name)
                    Text(
                        contributionsFa(contributions),
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                ShareStatus(member.sharesSms)
            }
            Spacer(Modifier.height(Space.xl))
            Text(
                "همگام‌سازی گوشی این عضو قطع می‌شه، ولی چیزی که قبلاً دیده یا کپی کرده پس گرفته نمی‌شه.",
                fontSize = 13.sp,
                lineHeight = 22.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(Space.s))
            ArmedAction(
                label = "حذف از خانواده",
                armedLabel = "مطمئنی؟ برای حذف دوباره بزن",
                enabled = enabled,
                onConfirmed = onRemove,
            )
            error?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 13.sp,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
        }
    }
}

/** The faces in [MeSheet], a size up from the rows: here they are the thing being chosen. */
private val PICK_FACE = 56.dp

/**
 * My name and face as the household sees them, in a sheet off my own row.
 *
 * They were an always-open field and a picker in the middle of the member list — set once, and
 * then in the way of every visit after. The name commits on every way out, the swipe-down
 * included, the care تنظیمات's own name sheet takes; a face commits the moment it is picked,
 * so the ring moves under her finger. Everyone picks their own on their own phone, the way
 * everyone types their own name, which is why only my row opens this.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MeSheet(
    name: String,
    avatar: String,
    onName: (String) -> Unit,
    onAvatar: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var draft by remember { mutableStateOf(name) }
    // Blank is not a name, so it keeps the old one rather than clearing it.
    val close = {
        val clean = draft.trim()
        if (clean.isNotBlank() && clean != name) onName(clean)
        onDismiss()
    }
    val appContext = LocalContext.current.applicationContext
    val photoScope = rememberCoroutineScope()
    val photoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            photoScope.launch {
                withContext(Dispatchers.IO) { avatarThumbnail(appContext, uri) }
                    ?.let(onAvatar)
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = close,
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
            SheetTitle("اسم و چهره")
            Text(
                "کنار تراکنش‌هات و توی دفتر مشترک دیده می‌شه.",
                fontSize = 13.sp,
                lineHeight = 22.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Space.xs),
            )
            Spacer(Modifier.height(Space.l))
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it.take(32) },
                singleLine = true,
                label = { Text("اسم") },
                shape = RoundedCornerShape(Radius.field),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { close() }),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(Space.xl))
            // Four discs, not a list: the whole space of choices fits on one line, and the one
            // in use wears the ring. The initial previews the name as it is being typed.
            val display = draft.ifBlank { name }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Space.m, Alignment.CenterHorizontally),
            ) {
                FaceChoice(selected = avatar.isBlank(), label = "حرف اول اسم", onPick = { onAvatar("") }) {
                    MemberFace(display, "", size = PICK_FACE)
                }
                FaceChoice(selected = avatar == AVATAR_MAN, label = "مرد", onPick = { onAvatar(AVATAR_MAN) }) {
                    MemberFace(display, AVATAR_MAN, size = PICK_FACE)
                }
                FaceChoice(selected = avatar == AVATAR_WOMAN, label = "زن", onPick = { onAvatar(AVATAR_WOMAN) }) {
                    MemberFace(display, AVATAR_WOMAN, size = PICK_FACE)
                }
                FaceChoice(
                    selected = avatar.startsWith(AVATAR_PHOTO_PREFIX),
                    label = "انتخاب عکس از گالری",
                    onPick = {
                        photoPicker.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    },
                ) {
                    // The photo in use, or the pen's «+» as the invitation to pick one — it was a
                    // camera emoji, the one sticker left in a room drawn entirely in the pen.
                    if (avatar.startsWith(AVATAR_PHOTO_PREFIX)) {
                        MemberFace(display, avatar, size = PICK_FACE)
                    } else {
                        Box(
                            Modifier
                                .size(PICK_FACE)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                            contentAlignment = Alignment.Center,
                        ) { PlusMark(MaterialTheme.colorScheme.onSurfaceVariant, size = 20.dp) }
                    }
                }
            }
            Spacer(Modifier.height(Space.xxl))
            CtaButton("ذخیره", enabled = true, onClick = close)
        }
    }
}

data class FamilyState(
    val paired: Boolean = false,
    val pendingPairing: String? = null,
    /** A scanned link for a *different* household than this phone's, waiting on the confirm. */
    val pendingRejoin: String? = null,
    val memberId: String = "",
    val memberName: String = "",
    val members: List<FamilyMember> = emptyList(),
    val sharesSms: Boolean = false,
    val sharesAssets: Boolean = false,
    /** Banks kept out of sharing — transactions and balances both. */
    val excludedBanks: Set<String> = emptySet(),
    /** The founder, as the server names them. Nobody else can remove this member. */
    val primaryMemberId: String = "",
    val pairingUrl: String? = null,
    val lastSync: String? = null,
    val working: Boolean = false,
    /** A sync in flight, silent ones included — what the ledger's pull indicator watches. */
    val syncing: Boolean = false,
    val error: String? = null,
)
