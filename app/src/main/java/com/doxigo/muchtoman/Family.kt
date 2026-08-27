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
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.inset
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
import androidx.compose.ui.text.style.LineHeightStyle
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
 * The two of them, on the row in تنظیمات that leads here.
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
    /** The banks this phone tracks, for the set-aside chips. Enum names, not Persian. */
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
    bottomInset: androidx.compose.ui.unit.Dp,
    onBack: () -> Unit,
) {
    var name by remember(state.memberId, state.memberName, state.pendingPairing, suggestedName) {
        mutableStateOf(state.memberName.ifBlank { suggestedName })
    }
    val cleanName = name.trim()

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

    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(horizontal = Space.xl)
                .verticalScroll(rememberScrollState())
                .padding(bottom = bottomInset + Space.l),
        ) {
            // This was a tab, and the bar was its way out. Pushed from تنظیمات it needs one of
            // its own, in the same corner every other pushed page in the app puts it.
            Row(verticalAlignment = Alignment.CenterVertically) {
                ScreenTitle(
                    "خانواده",
                    modifier = Modifier
                        .weight(1f)
                        .padding(vertical = Space.m),
                )
                PillButton("برگشت", onBack)
            }
            Text(
                "تراکنش‌های اعضا در یک دفتر دیده می‌شن و اسم صاحب هر مورد همیشه کنارش میاد.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 25.sp,
            )
            Spacer(Modifier.height(Space.l))

            when {
                // Before the plain join: a link on a phone that already belongs somewhere is
                // this question, whatever the rest of the state says.
                state.pendingRejoin != null -> RejoinCard(
                    working = state.working,
                    onConfirm = onRejoin,
                    onDismiss = onDismissRejoin,
                )
                state.pendingPairing != null -> JoinCard(
                    name = name,
                    working = state.working,
                    onNameChange = { name = it.take(32) },
                    onJoin = { onJoin(cleanName) },
                )
                !state.paired -> StartCard(
                    name = name,
                    working = state.working,
                    onNameChange = { name = it.take(32) },
                    onStart = { onStart(cleanName) },
                )
                else -> {
                    // The family and my place in it are one subject, not a list plus a settings
                    // section further down the page: the controls on my own row are exactly the
                    // ones that change what the other rows see. Mine leads because it is the
                    // only one anybody can act on.
                    val others = state.members.filterNot { it.id == state.memberId }
                    val count = others.size + 1
                    SectionHeading("اعضای خانواده", count)
                    Spacer(Modifier.height(Space.s))
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        OwnMemberBlock(
                            member = state.members.firstOrNull { it.id == state.memberId },
                            name = name,
                            savedName = state.memberName,
                            contributions = contributions[state.memberId] ?: 0,
                            sharing = state.sharesSms,
                            sharingAssets = state.sharesAssets,
                            primary = state.memberId == state.primaryMemberId,
                            banks = banks,
                            excludedBanks = state.excludedBanks,
                            working = state.working,
                            onNameChange = { name = it.take(32) },
                            onSave = { onNameChange(cleanName) },
                            onAvatarChange = onAvatarChange,
                            onSharingChange = onShareSmsChange,
                            onSharingAssetsChange = onShareAssetsChange,
                            onExcludedBankToggle = onExcludedBankToggle,
                            shape = bandShape(0, count),
                        )
                        others.forEachIndexed { index, member ->
                            FamilyMemberRow(
                                member = member,
                                contributions = contributions[member.id] ?: 0,
                                // The founder's row carries no removal for anyone else — the
                                // server would refuse it, so the button would be a lie.
                                primary = member.id == state.primaryMemberId,
                                shape = bandShape(index + 1, count),
                                enabled = !state.working && !acting,
                                onRemove = {
                                    familyAction("حذف نشد. اینترنتت رو چک کن.", after = onSync) { session, durable ->
                                        removeFamilyMember(session, durable, member.id)
                                    }
                                },
                            )
                        }
                    }
                    Spacer(Modifier.height(Space.l))

                    state.pairingUrl?.let { url ->
                        val bitmap = remember(url) { qrBitmap(url) }
                        Text(
                            "عضو جدید این کد رو اسکن کنه",
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onBackground,
                        )
                        Spacer(Modifier.height(Space.m))
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(Radius.card))
                                .background(androidx.compose.ui.graphics.Color.White)
                                .padding(Space.l),
                            contentAlignment = Alignment.Center,
                        ) {
                            Image(
                                bitmap = bitmap.asImageBitmap(),
                                contentDescription = null,
                                contentScale = ContentScale.Fit,
                                modifier = Modifier
                                    .size(240.dp)
                                    .semantics { contentDescription = "کد دعوت خانواده" },
                            )
                        }
                        Spacer(Modifier.height(Space.s))
                        Text(
                            "در اندروید، صفحه بازشده رو با اپ چقدر تومن باز کن. کد ده دقیقه اعتبار داره و یک‌بار مصرفه.",
                            fontSize = 13.sp,
                            lineHeight = 22.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(Space.l))
                    }

                    Button(
                        onClick = onInvite,
                        enabled = !state.working,
                        shape = RoundedCornerShape(Radius.pill),
                        colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                            containerColor = Cta.fill,
                            contentColor = Cta.ink,
                        ),
                        // Material's own default is 40dp, under the floor for a touch target.
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                    ) {
                        Text(
                            if (state.pairingUrl == null) "دعوت عضو جدید" else "ساختن کد تازه",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }

                    Spacer(Modifier.height(Space.s))
                    TextButton(
                        onClick = onSync,
                        enabled = !state.working,
                        shape = RoundedCornerShape(Radius.pill),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                    ) {
                        Text(
                            if (state.working) "در حال همگام‌سازی..." else "همگام‌سازی الان",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }

            state.lastSync?.takeIf { state.paired }?.let {
                Spacer(Modifier.height(Space.s))
                Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            state.error?.let {
                Spacer(Modifier.height(Space.m))
                Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
            }
            actionError?.let {
                Spacer(Modifier.height(Space.m))
                Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
            }

            Spacer(Modifier.height(Space.xxl))
            SectionHeading("حریم خصوصی")
            Spacer(Modifier.height(Space.s))
            Text(
                "متن خام پیامک هیچ‌وقت از گوشی صاحبش خارج نمی‌شه. فقط مبلغ، زمان، بانک، فروشنده و دسته‌بندیِ استخراج‌شده، رمز‌شده جابه‌جا می‌شن. " +
                    "از دارایی‌ها هم فقط اسم و ارزش تومنی می‌ره؛ مقدار، آدرس کیف پول و شماره حساب هیچ‌وقت نه. " +
                    "خاموش کردن اشتراک، موارد قبلی رو بعد از همگام‌سازی از دفتر بقیه حذف می‌کنه؛ چیزی که قبلاً دیده یا کپی شده قابل پس‌گرفتن نیست.",
                fontSize = 13.sp,
                lineHeight = 22.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (state.paired) {
                Spacer(Modifier.height(Space.xxl))
                SectionHeading("خروج از خانواده")
                Spacer(Modifier.height(Space.s))
                Text(
                    "دسترسی همین گوشی قطع می‌شه و دفتر مشترک از روش پاک می‌شه؛ تراکنش‌های خودت سر جاشون می‌مونن. " +
                        "چیزی که بقیه قبلاً دیدن پس گرفته نمی‌شه.",
                    fontSize = 13.sp,
                    lineHeight = 22.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(Space.s))
                ArmedAction(
                    label = "خروج از خانواده",
                    armedLabel = "مطمئنی؟ برای خروج دوباره بزن",
                    enabled = !state.working && !acting,
                    onConfirmed = onLeave,
                )
            }

            if (state.paired) {
                Spacer(Modifier.height(Space.xxl))
                SectionHeading("نو کردن خانواده")
                Spacer(Modifier.height(Space.s))
                Text(
                    // Plain about the mechanism, because the whole point of the action is a
                    // promise about keys: removing someone does not take back the key they
                    // already hold; this does.
                    "یک خانواده تازه با کلید تازه ساخته می‌شه و فقط تراکنش‌های همین گوشی دوباره فرستاده می‌شن. " +
                        "بقیه اعضا باید کد تازه رو دوباره اسکن کنن؛ خانواده قبلی دیگه به‌روز نمی‌شه و دفتر مشترک از نو شروع می‌شه. " +
                        "برای وقتی که کسی رو حذف کردی و می‌خوای مطمئن باشی چیز تازه‌ای بهش نمی‌رسه.",
                    fontSize = 13.sp,
                    lineHeight = 22.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(Space.s))
                ArmedAction(
                    label = "نو کردن خانواده",
                    armedLabel = "مطمئنی؟ برای نو کردن دوباره بزن",
                    enabled = !state.working && !acting,
                    onConfirmed = onRenew,
                )
            }
        }
    }
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
 * A section heading, at the size every other screen sets one.
 *
 * They were 16sp here — one Bold, one SemiBold, neither announced as a heading — so the page
 * read as a stack of cards with labels rather than as sections anyone could navigate by.
 */
@Composable
private fun SectionHeading(title: String, count: Int? = null) {
    Text(
        if (count == null) title else "$title (${faNumber(count.toDouble())})",
        fontSize = 18.sp,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onBackground,
        modifier = Modifier.semantics { heading() },
    )
}

@Composable
private fun StartCard(
    name: String,
    working: Boolean,
    onNameChange: (String) -> Unit,
    onStart: () -> Unit,
) {
    Panel {
        Text("ساختن خانواده", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.height(Space.s))
        Text(
            "اسم خودت رو بنویس. اعضای بعدی با کد دعوت وارد می‌شن.",
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(Space.m))
        NameField(name, onNameChange)
        Spacer(Modifier.height(Space.m))
        Button(onClick = onStart, enabled = name.isNotBlank() && !working, modifier = Modifier.fillMaxWidth()) {
            Text(if (working) "در حال ساختن..." else "ساختن خانواده")
        }
    }
}

@Composable
private fun JoinCard(
    name: String,
    working: Boolean,
    onNameChange: (String) -> Unit,
    onJoin: () -> Unit,
) {
    Panel {
        Text("پیوستن به خانواده", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.height(Space.s))
        Text("این اسم کنار تراکنش‌های تو دیده می‌شه.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(Space.m))
        NameField(name, onNameChange)
        Spacer(Modifier.height(Space.m))
        Button(onClick = onJoin, enabled = name.isNotBlank() && !working, modifier = Modifier.fillMaxWidth()) {
            Text(if (working) "در حال پیوستن..." else "پیوستن")
        }
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
private fun RejoinCard(
    working: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    Panel {
        Text(
            "پیوستن به خانواده جدید",
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(Space.s))
        Text(
            "این کد مال یک خانواده دیگه‌ست. با پیوستن، خانواده قبلی روی این گوشی کنار می‌ره: " +
                "موارد مشترک اعضای قبلی دیگه به‌روز نمی‌شن و دفتر مشترک از نو شروع می‌شه. " +
                "تراکنش‌های خود این گوشی سر جاشون می‌مونن و با همون اسم قبلی وارد می‌شی.",
            fontSize = 13.sp,
            lineHeight = 22.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(Space.s))
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
@Composable
private fun MemberContribution(count: Int) {
    Text(
        if (count > 0) "${faNumber(count.toDouble())} تراکنش" else "هنوز تراکنشی نفرستاده",
        fontSize = 12.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * Somebody else in the family: what they are called, what they contribute, what they share —
 * and the way out. Removal sits on the row it removes, behind the same two-tap confirm as every
 * other destructive thing here, with the honest sentence under the question: it cuts their
 * phone's sync, and it cannot un-see anything.
 */
@Composable
private fun FamilyMemberRow(
    member: FamilyMember,
    contributions: Int,
    /** The founder. Their row wears the tag and carries no removal — the server refuses it. */
    primary: Boolean,
    shape: Shape,
    enabled: Boolean,
    onRemove: () -> Unit,
) {
    var armed by remember(member.id) { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(Space.l),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MemberFace(member.name, member.avatar)
            Spacer(Modifier.size(Space.m))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        member.name,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    if (primary) {
                        Spacer(Modifier.size(Space.s))
                        FounderTag()
                    }
                }
                Spacer(Modifier.height(2.dp))
                MemberContribution(contributions)
            }
            Spacer(Modifier.size(Space.m))
            ShareStatus(member.sharesSms)
        }
        if (!primary) {
            TextButton(
                onClick = { if (armed) { armed = false; onRemove() } else armed = true },
                enabled = enabled,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.align(Alignment.End),
            ) {
                Text(
                    if (armed) "مطمئنی؟ برای حذف دوباره بزن" else "حذف از خانواده",
                    fontSize = 13.sp,
                    fontWeight = if (armed) FontWeight.Bold else FontWeight.Normal,
                    // Announced, or the safeguard is invisible to TalkBack.
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
        }
        if (armed) {
            Text(
                "همگام‌سازی گوشی این عضو قطع می‌شه، ولی چیزی که قبلاً دیده یا کپی کرده پس گرفته نمی‌شه.",
                fontSize = 12.sp,
                lineHeight = 20.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * My own place in the family, and everything I can actually change about it.
 *
 * The name field and the sharing switch used to be two more cards in a settings section further
 * down the page, which meant «who is in this family» and «what I share with them» were two
 * different subjects on one screen. They are one subject: this row is me, and the controls on it
 * are the ones that change what the other rows see.
 */
@Composable
private fun OwnMemberBlock(
    member: FamilyMember?,
    name: String,
    savedName: String,
    contributions: Int,
    sharing: Boolean,
    sharingAssets: Boolean,
    primary: Boolean,
    banks: List<String>,
    excludedBanks: Set<String>,
    working: Boolean,
    onNameChange: (String) -> Unit,
    onSave: () -> Unit,
    onAvatarChange: (String) -> Unit,
    onSharingChange: (Boolean) -> Unit,
    onSharingAssetsChange: (Boolean) -> Unit,
    onExcludedBankToggle: (String) -> Unit,
    shape: Shape,
) {
    val appContext = LocalContext.current.applicationContext
    val photoScope = rememberCoroutineScope()
    val photoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            photoScope.launch {
                withContext(Dispatchers.IO) { avatarThumbnail(appContext, uri) }
                    ?.let(onAvatarChange)
            }
        }
    }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(Space.l),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MemberFace(name.ifBlank { member?.name.orEmpty() }, member?.avatar.orEmpty())
            Spacer(Modifier.size(Space.m))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        savedName.ifBlank { name },
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
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
                    if (primary) {
                        Spacer(Modifier.size(Space.s))
                        FounderTag()
                    }
                }
                Spacer(Modifier.height(2.dp))
                MemberContribution(contributions)
            }
        }

        Spacer(Modifier.height(Space.l))
        NameField(name, onNameChange)
        // Only once it is actually a change: a save button that is always there reads as work
        // she has left undone.
        if (name.trim().isNotBlank() && name.trim() != savedName) {
            Spacer(Modifier.height(Space.s))
            TextButton(
                onClick = onSave,
                enabled = !working,
                modifier = Modifier.align(Alignment.End),
            ) { Text("ذخیره اسم") }
        }

        Spacer(Modifier.height(Space.l))
        // The face choices, on my row only: everyone picks their own on their own phone, the
        // way everyone types their own name. Four discs, not a sheet — the whole space of
        // choices fits on one line, and the one in use wears the ring.
        Text(
            "چهره",
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(Space.xs))
        Text(
            "کنار تراکنش‌هات و توی دفتر مشترک دیده می‌شه.",
            fontSize = 12.sp,
            lineHeight = 20.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(Space.m))
        val avatar = member?.avatar.orEmpty()
        val display = name.ifBlank { member?.name.orEmpty() }
        Row(horizontalArrangement = Arrangement.spacedBy(Space.m)) {
            FaceChoice(selected = avatar.isBlank(), label = "حرف اول اسم", onPick = { onAvatarChange("") }) {
                MemberFace(display, "")
            }
            FaceChoice(selected = avatar == AVATAR_MAN, label = "مرد", onPick = { onAvatarChange(AVATAR_MAN) }) {
                MemberFace(display, AVATAR_MAN)
            }
            FaceChoice(selected = avatar == AVATAR_WOMAN, label = "زن", onPick = { onAvatarChange(AVATAR_WOMAN) }) {
                MemberFace(display, AVATAR_WOMAN)
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
                // The slot shows the photo in use, or the camera as the invitation to pick one.
                if (avatar.startsWith(AVATAR_PHOTO_PREFIX)) MemberFace(display, avatar)
                else MemberFace("", "📷")
            }
        }

        Spacer(Modifier.height(Space.l))
        ShareSwitch(
            title = "اشتراک تراکنش‌های پیامکی",
            caption = if (sharing) "موارد استخراج‌شده برای خانواده فرستاده می‌شن."
            else "پیامک‌های تو فقط روی همین گوشی می‌مونن.",
            checked = sharing,
            onChange = onSharingChange,
        )
        Spacer(Modifier.height(Space.s))
        ShareSwitch(
            title = "اشتراک دارایی‌ها",
            caption = if (sharingAssets) "فهرست دارایی‌هات با ارزش تومنی‌شون برای خانواده فرستاده می‌شه."
            else "دارایی‌هات فقط روی همین گوشی می‌مونن.",
            checked = sharingAssets,
            onChange = onSharingAssetsChange,
        )

        // The set-aside banks, only while something is being shared for them to be set aside
        // from. One veto for both directions: an excluded bank's transactions and its balance
        // stay home together.
        if (banks.isNotEmpty() && (sharing || sharingAssets)) {
            Spacer(Modifier.height(Space.l))
            Text(
                "بانک‌های کنارگذاشته",
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(Space.xs))
            Text(
                "تراکنش‌ها و موجودی بانکی که علامت بزنی هیچ‌وقت فرستاده نمی‌شن.",
                fontSize = 12.sp,
                lineHeight = 20.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(Space.m))
            FlowRow(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Space.s),
                verticalArrangement = Arrangement.spacedBy(Space.s),
            ) {
                banks.forEach { bank ->
                    FilterCategoryChip(
                        name = bankNameOf(bank),
                        chosen = bank in excludedBanks,
                        onToggle = { onExcludedBankToggle(bank) },
                    )
                }
            }
        }
    }
}

/** «سرپرست» — the founder's mark, on their row wherever it appears. */
@Composable
private fun FounderTag() {
    Text(
        "سرپرست",
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.tertiary,
        modifier = Modifier
            .clip(RoundedCornerShape(Radius.pill))
            .background(MaterialTheme.colorScheme.tertiary.copy(alpha = 0.18f))
            .padding(horizontal = Space.s, vertical = 2.dp),
    )
}

/** One sharing switch, said the way the block's first one always said it. */
@Composable
private fun ShareSwitch(
    title: String,
    caption: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            // Concentric with the band it sits in: 28dp group radius less the 16dp
            // of padding around it. Equal radii would make the inner surface look pinched.
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .padding(Space.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                caption,
                fontSize = 12.sp,
                lineHeight = 20.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.size(Space.m))
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun NameField(value: String, onValueChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text("اسم") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
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
