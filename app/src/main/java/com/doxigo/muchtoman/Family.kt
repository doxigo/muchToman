package com.doxigo.muchtoman

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlinx.coroutines.launch

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
    onShareSmsChange: (Boolean) -> Unit,
    onInvite: () -> Unit,
    onSync: () -> Unit,
    /** How many ledger entries each member id has put in, for the rows to report. */
    contributions: Map<String, Int>,
    bottomInset: androidx.compose.ui.unit.Dp,
    onBack: () -> Unit,
) {
    var name by remember(state.memberId, state.memberName, state.pendingPairing, suggestedName) {
        mutableStateOf(state.memberName.ifBlank { suggestedName })
    }
    val cleanName = name.trim()

    // Removal and renewal run from here rather than through the ViewModel: they are leaf
    // actions — read the session, speak to the server, write the ledger — with nothing the
    // ViewModel computes, and they finish by handing control back to the two callbacks this
    // screen already has: onSync to re-read what the household looks like, onInvite to put the
    // fresh QR up. That keeps the member list and the only things that shrink it in one file.
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
                            working = state.working,
                            onNameChange = { name = it.take(32) },
                            onSave = { onNameChange(cleanName) },
                            onSharingChange = onShareSmsChange,
                            shape = bandShape(0, count),
                        )
                        others.forEachIndexed { index, member ->
                            FamilyMemberRow(
                                member = member,
                                contributions = contributions[member.id] ?: 0,
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
                "متن خام پیامک هیچ‌وقت از گوشی صاحبش خارج نمی‌شه. فقط مبلغ، زمان، بانک، فروشنده و دسته‌بندیِ استخراج‌شده، رمز‌شده جابه‌جا می‌شن. خاموش کردن اشتراک، موارد قبلی رو بعد از همگام‌سازی از دفتر بقیه حذف می‌کنه؛ چیزی که قبلاً دیده یا کپی شده قابل پس‌گرفتن نیست.",
                fontSize = 13.sp,
                lineHeight = 22.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

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
                ) {
                    familyAction(
                        "نو نشد. اینترنتت رو چک کن.",
                        // The fresh QR first, so the reason she did this is on screen; the sync
                        // then re-pushes this phone's records under the new key.
                        after = { onInvite(); onSync() },
                    ) { _, durable -> renewHousehold(durable) }
                }
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
 * Whose it is, at a glance. The initial, not a photo: there is no avatar anywhere in this app
 * and a family of four does not need four downloads to tell itself apart.
 *
 * Every disc is the same colour, including mine. Gold in this app means the action or the
 * answer, and an accent disc the size of a thumb sat next to the accent «من» chip, the accent
 * switch and the accent invite button — four of them down one column, none of which was what she
 * had come to press. Identity is not an action; the chip says which row is mine in a word.
 */
@Composable
private fun MemberAvatar(name: String) {
    Box(
        Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            name.trim().firstOrNull()?.toString().orEmpty(),
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
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
            MemberAvatar(member.name)
            Spacer(Modifier.size(Space.m))
            Column(Modifier.weight(1f)) {
                Text(
                    member.name,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(2.dp))
                MemberContribution(contributions)
            }
            Spacer(Modifier.size(Space.m))
            ShareStatus(member.sharesSms)
        }
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
    working: Boolean,
    onNameChange: (String) -> Unit,
    onSave: () -> Unit,
    onSharingChange: (Boolean) -> Unit,
    shape: Shape,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(Space.l),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MemberAvatar(name.ifBlank { member?.name.orEmpty() })
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
        Row(
            Modifier
                .fillMaxWidth()
                // Concentric with the band it sits in: 28dp group radius less the 16dp
                // of padding around it. Equal radii would make the inner surface look pinched.
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                .toggleable(value = sharing, role = Role.Switch, onValueChange = onSharingChange)
                .padding(Space.m),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "اشتراک تراکنش‌های پیامکی",
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    if (sharing) "موارد استخراج‌شده برای خانواده فرستاده می‌شن."
                    else "پیامک‌های تو فقط روی همین گوشی می‌مونن.",
                    fontSize = 12.sp,
                    lineHeight = 20.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.size(Space.m))
            Switch(checked = sharing, onCheckedChange = null)
        }
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
    val pairingUrl: String? = null,
    val lastSync: String? = null,
    val working: Boolean = false,
    val error: String? = null,
)
