package com.doxigo.muchtoman

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs

/**
 * طلب و بدهی on screen. `Loans.kt` says what the figures are; this file says where she meets them:
 * a strip on the hero beside the total, a door on آینده, one page of people, one page per person,
 * and the sheets that write to them.
 *
 * Words carry the side — «بهت بدهکاره», «بهش بدهکاری» — and colour never does: a debt is not an
 * error, and a loan is not a gain, so the figures stay in plain ink and only an overdue promise
 * wears the caution colour, beside the words that say it is overdue.
 */

/** Which sheet is open. Held by the host because the same three open from four places. */
internal sealed interface LoanAsk {
    data object AddPerson : LoanAsk
    data class EditPerson(val id: String) : LoanAsk

    /** [personId] null: she picks, or names, the person in the sheet — the holding sheet's way in. */
    data class Move(val personId: String?, val giving: Boolean, val preset: String? = null) : LoanAsk
}

/** «۴۷۵٫۵» large and «میلیون تومان» a size down, as one line that never wraps. */
@Composable
private fun LoanFigure(toman: Double, size: TextUnit, color: Color, modifier: Modifier = Modifier, suffix: String = "") {
    val parts = faCompact(abs(toman)).split(' ', limit = 2)
    val tail = listOfNotNull(parts.getOrNull(1), suffix.ifBlank { null }).joinToString(" ")
    Text(
        buildAnnotatedString {
            append(parts[0])
            if (tail.isNotEmpty()) withStyle(SpanStyle(fontSize = size * 0.72f, fontWeight = FontWeight.Bold)) { append(" $tail") }
        },
        style = figureStyle(color, FontWeight.ExtraBold),
        fontSize = size,
        maxLines = 1,
        modifier = modifier,
    )
}

// ─────────────────────────── on the hero ───────────────────────────

/**
 * «طلبت · بدهیت», beside the total and never in it. A well rather than a line, because it is a
 * door and the card's other doors are wells too; the page it opens starts with the same pair,
 * large, so the tap lands where it pointed.
 */
@Composable
internal fun HeroLoans(totals: LoanTotals, onOpen: () -> Unit) {
    val spoken = "طلب و بدهی: " + listOfNotNull(
        totals.owedToman.takeIf { totals.owedPeople > 0 }?.let { "${faCompact(it)} تومان طلب" },
        totals.oweToman.takeIf { totals.owePeople > 0 }?.let { "${faCompact(it)} تومان بدهی" },
    ).joinToString("، ")
    Row(
        Modifier
            .padding(top = Space.l)
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.card))
            .background(Hero.well)
            .clickable(role = Role.Button, onClick = onOpen)
            .padding(horizontal = Space.l, vertical = Space.m)
            .semantics(mergeDescendants = true) { contentDescription = spoken },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (totals.owedPeople > 0) HeroLoanFigure("طلبت", totals.owedToman, Modifier.weight(1f))
        if (totals.owePeople > 0) HeroLoanFigure("بدهیت", totals.oweToman, Modifier.weight(1f))
        Icon(
            Icons.AutoMirrored.Rounded.KeyboardArrowRight,
            contentDescription = null,
            tint = Hero.muted,
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
private fun HeroLoanFigure(label: String, toman: Double, modifier: Modifier) {
    Column(modifier) {
        Text(label, fontSize = 12.sp, color = Hero.muted)
        LoanFigure(toman, 17.sp, Hero.strong)
    }
}

// ─────────────────────────── on آینده ───────────────────────────

/**
 * The section's one row: where the two sides stand, and the door to the people. With nobody yet,
 * the add row itself — a section and the way to start it are one object, as on the other three.
 */
@Composable
internal fun LoansDoor(totals: LoanTotals, people: Int, onOpen: () -> Unit, onAdd: () -> Unit) {
    if (people == 0) {
        BandAddRow("اولین حساب", RoundedCornerShape(Radius.group), onAdd)
        return
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.group))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(role = Role.Button, onClick = onOpen)
            .padding(Space.l),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            val lines = listOfNotNull(
                totals.owedToman.takeIf { totals.owedPeople > 0 }?.let { "${faCompact(it)} تومان طلب داری" },
                totals.oweToman.takeIf { totals.owePeople > 0 }?.let { "${faCompact(it)} تومان بدهکاری" },
            ).ifEmpty { listOf("حساب همه صافه") }
            lines.forEach {
                Text(it, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
            }
            Text(
                "${faNumber(people.toDouble())} نفر",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Space.xs),
            )
        }
        Icon(
            Icons.AutoMirrored.Rounded.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** [AddRow]'s look, for the bands on these pages. */
@Composable
private fun BandAddRow(label: String, shape: Shape, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = 56.dp)
            .padding(horizontal = Space.l),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Text("+", style = figureStyle(MaterialTheme.colorScheme.primary, FontWeight.ExtraBold), fontSize = 20.sp)
        Spacer(Modifier.width(Space.s))
        Text(label, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
    }
}

// ─────────────────────────── the people ───────────────────────────

@Composable
private fun PageHead(title: String, onBack: () -> Unit, extra: (@Composable () -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = Space.m),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.s),
    ) {
        ScreenTitle(title, modifier = Modifier.weight(1f))
        extra?.invoke()
        PillButton("برگشت", onBack)
    }
}

@Composable
private fun LoanHeading(text: String) {
    Text(
        text,
        fontSize = 15.sp,
        fontWeight = FontWeight.ExtraBold,
        color = MaterialTheme.colorScheme.onBackground,
        modifier = Modifier
            .padding(top = Space.xxl, bottom = Space.m, start = Space.xs)
            .semantics { heading() },
    )
}

/** Her initial for him, in a door's disc — a person is somewhere to go, not a category. */
@Composable
private fun PersonDisc(name: String) {
    Box(
        Modifier.size(44.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            name.trim().take(1),
            fontSize = 18.sp,
            fontWeight = FontWeight.ExtraBold,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
}

@Composable
internal fun LoansScreen(
    views: List<LoanView>,
    totals: LoanTotals,
    type: (String) -> AssetType,
    onOpen: (String) -> Unit,
    onAdd: () -> Unit,
    onBack: () -> Unit,
) {
    val today = remember { tehranDay(System.currentTimeMillis()) }
    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = Space.xl)
                .padding(bottom = Space.huge),
        ) {
            PageHead("طلب و بدهی", onBack)

            if (views.isEmpty()) {
                Column(
                    Modifier.fillMaxWidth().padding(vertical = Space.xxl),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        Modifier.size(56.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center,
                    ) { CompanionGlyph(MaterialTheme.colorScheme.onPrimaryContainer) }
                    Spacer(Modifier.height(Space.l))
                    Text("هنوز حسابی نداری.", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
                    Text(
                        "قرضی که دادی یا گرفتی رو اینجا بنویس، یا وقتی یه واریز رو «قرض» می‌زنی بگو مال کی بوده.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 26.sp,
                        modifier = Modifier.padding(top = Space.xs),
                    )
                    Spacer(Modifier.height(Space.xl))
                    PillButton("حساب تازه", onAdd, voice = ButtonVoice.PRIMARY)
                }
                return@Column
            }

            // The pair the hero's strip is the small copy of — the same two figures, where the tap
            // on that strip lands.
            Row(Modifier.fillMaxWidth().padding(top = Space.s, start = Space.xs, end = Space.xs)) {
                LoanSideFigure("طلبت", totals.owedToman, "پیش ${faNumber(totals.owedPeople.toDouble())} نفر", Modifier.weight(1f))
                Spacer(Modifier.width(Space.xl))
                LoanSideFigure("بدهیت", totals.oweToman, "به ${faNumber(totals.owePeople.toDouble())} نفر", Modifier.weight(1f))
            }
            Text(
                (if (views.any { it.units.isNotEmpty() }) "به نرخ امروز. " else "") + "توی جمع دارایی‌هات حساب نمی‌شه.",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Space.l, start = Space.xs),
            )
            if (totals.missing.isNotEmpty()) {
                Text(
                    "نرخ ${totals.missing.joinToString("، ") { type(it).fa }} نرسیده؛ توی این جمع نیست.",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.padding(top = Space.xs, start = Space.xs),
                )
            }

            listOf(
                LoanSide.OWED to "بهت بدهکارن",
                LoanSide.OWE to "بهشون بدهکاری",
                LoanSide.SETTLED to "تسویه شده",
            ).forEach { (side, heading) ->
                val rows = views.filter { it.side == side }
                if (rows.isEmpty()) return@forEach
                LoanHeading(heading)
                rows.forEachIndexed { i, view ->
                    PersonRow(view, today, type, bandShape(i, rows.size), divided = i < rows.size - 1) { onOpen(view.person.id) }
                }
            }
            Spacer(Modifier.height(Space.m))
            BandAddRow("حساب تازه", RoundedCornerShape(Radius.group), onAdd)
        }
    }
}

@Composable
private fun LoanSideFigure(label: String, toman: Double, sub: String, modifier: Modifier) {
    Column(modifier) {
        Text(label, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
        LoanFigure(toman, 32.sp, MaterialTheme.colorScheme.onBackground, modifier = Modifier.padding(top = Space.xs))
        Text(sub, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun PersonRow(
    view: LoanView,
    today: Long,
    type: (String) -> AssetType,
    shape: Shape,
    divided: Boolean,
    onOpen: () -> Unit,
) {
    val sub = loanSubFa(view, today, type)
    Box(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(role = Role.Button, onClick = onOpen),
    ) {
        Row(Modifier.padding(Space.l), verticalAlignment = Alignment.CenterVertically) {
            PersonDisc(view.person.name)
            Spacer(Modifier.width(Space.m))
            Column(Modifier.weight(1f)) {
                Text(
                    view.person.name,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                sub?.let { (text, late) ->
                    Text(
                        text,
                        fontSize = 13.sp,
                        color = if (late) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = if (late) FontWeight.Bold else FontWeight.Normal,
                    )
                }
            }
            if (view.side != LoanSide.SETTLED && view.toman != 0.0) {
                Spacer(Modifier.width(Space.m))
                LoanFigure(view.toman, 16.sp, MaterialTheme.colorScheme.onSurface)
            }
        }
        if (divided) {
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant,
                modifier = Modifier.align(Alignment.BottomStart).padding(start = Space.l),
            )
        }
    }
}

// ─────────────────────────── one person ───────────────────────────

@Composable
internal fun LoanPersonScreen(
    view: LoanView,
    type: (String) -> AssetType,
    onEdit: () -> Unit,
    onMove: (giving: Boolean) -> Unit,
    onOpenEntry: (LedgerEntry) -> Unit,
    onDeleteMove: (LoanMove) -> Unit,
    onBack: () -> Unit,
) {
    val today = remember { tehranDay(System.currentTimeMillis()) }
    var confirming by remember { mutableStateOf<LoanEvent?>(null) }
    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = Space.xl)
                .padding(bottom = Space.huge),
        ) {
            PageHead(view.person.name, onBack) { PillButton("ویرایش", onEdit) }

            Text(
                loanSideFa(view.side),
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Space.s),
            )
            if (view.side != LoanSide.SETTLED) OwedInOwnUnits(view, type)

            // The act that settles is the loud one: a debtor's «پس داد», her own «پس دادم».
            val (primary, second) = when (view.side) {
                LoanSide.OWED -> ("پس داد" to false) to ("بیشتر دادم" to true)
                LoanSide.OWE -> ("پس دادم" to true) to ("بیشتر گرفتم" to false)
                LoanSide.SETTLED -> ("دادم" to true) to ("گرفتم" to false)
            }
            Row(Modifier.fillMaxWidth().padding(top = Space.xl), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                PillButton(
                    primary.first, { onMove(primary.second) }, Modifier.weight(1f),
                    voice = if (view.side == LoanSide.SETTLED) ButtonVoice.TONAL else ButtonVoice.PRIMARY,
                )
                PillButton(second.first, { onMove(second.second) }, Modifier.weight(1f))
            }

            if (view.side != LoanSide.SETTLED) {
                val promise = view.person.promise
                if (promise != null) {
                    val days = promise - today
                    Row(
                        Modifier
                            .padding(top = Space.l)
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(Radius.card))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .clickable(role = Role.Button, onClickLabel = "تغییر قرار", onClick = onEdit)
                            .padding(horizontal = Space.l, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            buildAnnotatedString {
                                append("قرار پس دادن: ")
                                withStyle(SpanStyle(fontWeight = FontWeight.ExtraBold)) { append(faDayMonth(promise, today)) }
                            },
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            when {
                                days > 0 -> "${faNumber(days.toDouble())} روز مونده"
                                days == 0L -> "امروز"
                                else -> "${faNumber((-days).toDouble())} روز گذشته"
                            },
                            fontSize = 13.sp,
                            fontWeight = if (days < 0) FontWeight.Bold else FontWeight.Normal,
                            color = if (days < 0) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    PillButton("+ قرار پس دادن", onEdit, Modifier.padding(top = Space.m))
                }
            }

            if (view.events.isNotEmpty() || view.olderRial != 0L) LoanHeading("ریز حساب")
            view.events.groupBy { it.day }.forEach { (day, events) ->
                Text(
                    faDay(day, today),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = Space.m, bottom = Space.xs, start = Space.xs),
                )
                events.forEach { event ->
                    EventRow(event, type) {
                        val entry = event.entry
                        if (entry != null) onOpenEntry(entry) else confirming = event
                    }
                }
            }
            if (view.olderRial != 0L) {
                Text(
                    "و ${loanRialFa(view.olderRial)} از تراکنش‌هایی که دیگه توی دفتر نیستن.",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = Space.m, start = Space.xs),
                )
            }
        }
    }

    confirming?.let { event ->
        val move = event.move ?: return@let
        DeleteMoveSheet(
            title = loanEventTitleFa(event, type),
            onDelete = { onDeleteMove(move); confirming = null },
            onDismiss = { confirming = null },
        )
    }
}

/**
 * The debt as it is owed: «۲ سکه امامی» large, then «و ۲٫۵ میلیون تومان». Toman only ever
 * appears here as a part of the debt or, under a line of its own, as today's value of the rest.
 */
@Composable
private fun OwedInOwnUnits(view: LoanView, type: (String) -> AssetType) {
    val parts = view.units.map { (id, amount) ->
        val t = type(id)
        val words = if (t.unitFa == "عدد" || t.fa.startsWith(t.unitFa)) t.fa else "${t.unitFa} ${t.fa}"
        faHeld(abs(amount), t.dec) to words
    } + listOfNotNull(
        view.rial.takeIf { it != 0L }?.let {
            val split = faCompact(tomanOf(abs(it))).split(' ', limit = 2)
            split[0] to (listOfNotNull(split.getOrNull(1)) + "تومان").joinToString(" ")
        },
    )
    parts.forEachIndexed { i, (digits, words) ->
        val big = if (i == 0) 46.sp else 30.sp
        Text(
            buildAnnotatedString {
                if (i > 0) append("و ")
                append(digits)
                withStyle(SpanStyle(fontSize = if (i == 0) 24.sp else 18.sp, fontWeight = FontWeight.ExtraBold)) { append(" $words") }
            },
            style = figureStyle(MaterialTheme.colorScheme.onBackground, FontWeight.Black),
            fontSize = big,
            lineHeight = big * 1.2f,
            modifier = Modifier.padding(top = if (i == 0) Space.xs else 0.dp),
        )
    }
    if (view.units.isEmpty()) {
        faWordsToman(tomanOf(abs(view.rial)))?.let {
            Text(it, fontSize = 15.sp, lineHeight = 25.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = Space.s))
        }
        return
    }
    Row(Modifier.padding(top = Space.m), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(MaterialTheme.colorScheme.tertiary))
        Spacer(Modifier.width(Space.s))
        Text(
            buildAnnotatedString {
                append("امروز روی هم ")
                withStyle(SpanStyle(fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.onBackground)) {
                    append("${faCompact(abs(view.toman))} تومان")
                }
            },
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (view.missing.isNotEmpty()) {
        Text(
            "نرخ ${view.missing.joinToString("، ") { type(it).fa }} نرسیده",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.secondary,
            modifier = Modifier.padding(top = Space.xs),
        )
    }
}

@Composable
private fun EventRow(event: LoanEvent, type: (String) -> AssetType, onClick: () -> Unit) {
    val out = if (event.typeId.isBlank()) event.rial > 0L else event.amount > 0.0
    val glyph = if (out) CategoryGlyph.LEND else CategoryGlyph.PAYBACK
    val hue = glyphHue(glyph)
    val ink = if (out) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.tertiary
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.card))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = Space.xs, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(44.dp).clip(CircleShape).background(hue.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center,
        ) { GlyphIcon(glyph, hue, size = 22.dp, stroke = 1.8.dp) }
        Spacer(Modifier.width(Space.m))
        Column(Modifier.weight(1f)) {
            Text(
                loanEventTitleFa(event, type),
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            loanEventSubFa(event)?.let {
                Text(it, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.width(Space.m))
        val figure = if (event.typeId.isBlank()) {
            signedFigure(faSignedParts(tomanOf(abs(event.rial)), positive = !out))
        } else {
            val t = type(event.typeId)
            val unit = if (t.unitFa == "عدد") t.fa.substringBefore(' ') else t.unitFa
            signedFigure(ltrFigure((if (out) "−" else "+") + faHeld(abs(event.amount), t.dec)) to unit)
        }
        Text(figure, style = figureStyle(ink, FontWeight.ExtraBold), fontSize = 16.sp, maxLines = 1)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DeleteMoveSheet(title: String, onDelete: () -> Unit, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = Radius.sheet, topEnd = Radius.sheet),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(Modifier.navigationBarsPadding().padding(horizontal = Space.xl).padding(bottom = Space.l)) {
            SheetTitle("این مورد رو پاک کنم؟")
            Text(title, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(Space.xl))
            SheetDelete("پاکش کن", onDelete)
        }
    }
}

// ─────────────────────────── the sheets ───────────────────────────

/**
 * What a loan can be in: cash first, then whatever this person already owes in, then what she
 * holds, then the three things Iranian households actually lend each other. Anything valued
 * straight in Toman — a car, a house — is not something one lends by the unit, so it is left out.
 */
private fun loanUnits(view: LoanView?, holdings: List<Holding>, preset: String?, type: (String) -> AssetType): List<String> =
    (
        listOf("") + listOfNotNull(preset) + view?.units?.keys.orEmpty() +
            holdings.filter { it.wallet == null }.map { it.typeId } +
            listOf("usd", "coin_emami", "gold18")
        )
        .filter { it.isBlank() || (it != BANK_ID && !type(it).valuedInToman) }
        .distinct()

/** Cash says «نقد»; an asset says its own name. */
private fun unitLabel(id: String, type: (String) -> AssetType): String = if (id.isBlank()) "نقد" else type(id).fa

@Composable
private fun UnitChips(units: List<String>, selected: String, type: (String) -> AssetType, onSelect: (String) -> Unit) {
    FlowRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Space.s),
        verticalArrangement = Arrangement.spacedBy(Space.s),
    ) {
        units.forEach { id -> Chip(unitLabel(id, type), id == selected) { onSelect(id) } }
    }
}

/**
 * [ChipChoice]'s pill, for rows that have to wrap. [lead] goes before the label — a bank's logo at
 * 24dp, as the PWA's paste sheet draws its bank chips — with the start padding drawn in to meet it.
 */
@Composable
internal fun Chip(label: String, active: Boolean, lead: (@Composable () -> Unit)? = null, onClick: () -> Unit) {
    Row(
        Modifier
            .clip(RoundedCornerShape(Radius.pill))
            .background(if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainer)
            .selectable(selected = active, role = Role.RadioButton, onClick = onClick)
            .heightIn(min = 40.dp)
            .padding(start = if (lead != null) Space.s else Space.l, end = Space.l),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.s),
    ) {
        lead?.invoke()
        Text(
            label,
            fontSize = 13.sp,
            maxLines = 1,
            fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
            color = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
        )
    }
}

/**
 * The sheet's one commit, in [Cta] green and never dimmed: a dead button explains nothing, so a
 * tap that cannot save yet raises the words under whatever is missing instead.
 */
@Composable
private fun CommitButton(label: String, onClick: () -> Unit) {
    PillButton(label, onClick, Modifier.fillMaxWidth(), voice = ButtonVoice.PRIMARY, block = true)
}

/** A quieter full-width answer under the commit. */
@Composable
private fun SheetPill(label: String, onClick: () -> Unit) {
    PillButton(label, onClick, Modifier.fillMaxWidth().padding(top = Space.s), block = true)
}

/** A blank amount, in the field's own word: Toman is a sum of money, a coin or a gram is not. */
private fun missingAmountFa(unit: String): String = if (unit.isBlank()) "مبلغش رو بنویس." else "مقدارش رو بنویس."

/** The amount field for a unit: Toman for cash, the asset's own unit otherwise. */
@Composable
private fun AmountField(unit: String, text: String, onText: (String) -> Unit, type: (String) -> AssetType, error: String?) {
    val cash = unit.isBlank()
    val rial = if (cash) tomanFieldToRial(text) else null
    // A figure that does not read says so itself: the commit no longer dims over it.
    val shown = error ?: if (text.isNotBlank() && (if (cash) rial == null else unitAmount(text) == null)) {
        "این عدد قابل خوندن نیست. فقط عدد وارد کن."
    } else {
        null
    }
    OutlinedTextField(
        value = text,
        onValueChange = onText,
        label = { Text(if (cash) "چقدر، به تومان" else "چقدر، به ${type(unit).unitFa}") },
        singleLine = true,
        visualTransformation = GroupedNumber,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        isError = shown != null,
        supportingText = when {
            shown != null -> ({ FieldError(shown) })
            rial != null -> ({ Text(faWordsToman(tomanOf(rial)).orEmpty()) })
            else -> null
        },
        shape = RoundedCornerShape(Radius.field),
        modifier = Modifier.fillMaxWidth(),
    )
}

/** Units she typed, or null when it is not a positive figure. */
private fun unitAmount(text: String): Double? = parseAmount(text)?.takeIf { it > 0.0 && it.isFinite() }

/**
 * Add or edit a person. Adding also asks what is owed — an account with nothing on it is only an
 * empty row under «تسویه شده» — and never moves a holding for it: that money most often left the
 * drawer long before the app was counting.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LoanPersonSheet(
    person: LoanPerson?,
    holdings: List<Holding>,
    type: (String) -> AssetType,
    onSave: (name: String, promise: Long?, opening: LoanMove?) -> Unit,
    onDelete: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val focus = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    fun close(then: () -> Unit) = scope.hideThen(sheetState, then)
    val today = remember { tehranDay(System.currentTimeMillis()) }
    var name by rememberSaveable { mutableStateOf(person?.name.orEmpty()) }
    var promise by rememberSaveable { mutableStateOf(person?.promise) }
    // 1 he owes her, 2 she owes him.
    var opening by rememberSaveable { mutableIntStateOf(1) }
    var unit by rememberSaveable { mutableStateOf("") }
    var amountText by rememberSaveable { mutableStateOf("") }
    val rial = if (unit.isBlank()) tomanFieldToRial(amountText) else null
    val units = remember(holdings) { loanUnits(null, holdings, null, type) }
    val amount = if (unit.isBlank()) null else unitAmount(amountText)
    val openingOk = person != null || rial != null || amount != null
    // Raised by a save tap that could not save: from then on every blank answer says so.
    var tried by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = Radius.sheet, topEnd = Radius.sheet),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            Modifier
                .nestedScroll(SheetFlingGuard)
                .navigationBarsPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Space.xl)
                .padding(bottom = Space.l),
        ) {
            SheetTitle(if (person == null) "حساب تازه" else "ویرایش حساب")
            SheetLabel("اسم")
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(MAX_LOAN_NAME) },
                label = { Text("مثلاً مهدی، یا خاله مریم") },
                singleLine = true,
                isError = tried && name.isBlank(),
                supportingText = if (tried && name.isBlank()) ({ FieldError("اسمش رو بنویس.") }) else null,
                shape = RoundedCornerShape(Radius.field),
                modifier = Modifier.fillMaxWidth(),
            )

            if (person == null) {
                SheetLabel("کی بدهکاره؟")
                SegmentedChoice(
                    options = listOf(1, 2),
                    selected = opening,
                    label = { if (it == 1) "بهم بدهکاره" else "بهش بدهکارم" },
                    onSelect = { opening = it },
                    fontSize = 13.sp,
                )
                Spacer(Modifier.height(Space.m))
                UnitChips(units, unit, type) { unit = it; amountText = "" }
                Spacer(Modifier.height(Space.m))
                AmountField(unit, amountText, { amountText = it }, type, missingAmountFa(unit).takeIf { tried && amountText.isBlank() })
            }

            SheetLabel("قرار پس دادن")
            PromisePicker(promise, today) { promise = it }

            Spacer(Modifier.height(Space.xl))
            CommitButton("ذخیره") {
                // Focus lets go first: the keyboard was covering the words the tap raises.
                focus.clearFocus()
                tried = true
                if (name.isBlank() || !openingOk) return@CommitButton
                val carried = when {
                    person != null -> null
                    unit.isBlank() -> rial?.let { LoanMove(id = "", personId = "", rial = if (opening == 1) it else -it, day = today) }
                    else -> amount?.let { LoanMove(id = "", personId = "", typeId = unit, amount = if (opening == 1) it else -it, day = today) }
                }
                close { onSave(name.trim(), promise, carried) }
            }
            if (onDelete != null && person != null) {
                SheetDelete("پاک کردن حساب") { close(onDelete) }
            }
        }
    }
}

/**
 * A promise date, in the steps people actually agree on — a week, a month, three — and then a
 * day at a time either way. The ledger's own [DayStepper] stops at today; a promise is all future.
 */
@Composable
private fun PromisePicker(promise: Long?, today: Long, onPromise: (Long?) -> Unit) {
    val quick = listOf(
        "بدون قرار" to null,
        "یه هفته" to today + 7,
        "یه ماه" to jalaliMonthsAfter(today, 1),
        "سه ماه" to jalaliMonthsAfter(today, 3),
    )
    FlowRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Space.s),
        verticalArrangement = Arrangement.spacedBy(Space.s),
    ) {
        quick.forEach { (label, day) -> Chip(label, promise == day) { onPromise(day) } }
    }
    if (promise != null) {
        Row(Modifier.padding(top = Space.m), verticalAlignment = Alignment.CenterVertically) {
            PillButton("روز قبل", { onPromise(promise - 1) })
            Text(
                faWeekdayDate(promise),
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.weight(1f),
            )
            PillButton("روز بعد", { onPromise(promise + 1) })
        }
    }
}

/**
 * Something handed over or taken back that no bank reported. When the unit is one she keeps in
 * دارایی, the same amount leaves or joins it — lending two coins is two coins fewer in the drawer —
 * and the switch says so before she saves, so the total moving is never a surprise.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LoanMoveSheet(
    person: LoanView?,
    people: List<LoanView>,
    giving: Boolean,
    preset: String?,
    holdings: List<Holding>,
    type: (String) -> AssetType,
    onSave: (personId: String?, newName: String?, typeId: String, rial: Long, amount: Double, moveHolding: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val focus = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    fun close(then: () -> Unit) = scope.hideThen(sheetState, then)
    var who by rememberSaveable { mutableStateOf(person?.person?.id) }
    var naming by rememberSaveable { mutableStateOf(person == null && people.isEmpty()) }
    var newName by rememberSaveable { mutableStateOf("") }
    val chosen = person ?: people.firstOrNull { it.person.id == who }
    val units = remember(chosen, holdings, preset) { loanUnits(chosen, holdings, preset, type) }
    var unit by rememberSaveable { mutableStateOf(preset ?: "") }
    var amountText by rememberSaveable { mutableStateOf("") }
    val rial = if (unit.isBlank()) tomanFieldToRial(amountText) else null
    val amount = if (unit.isBlank()) rial?.let { tomanOf(it) } else unitAmount(amountText)
    val holding = loanHoldingFor(holdings, unit, giving)
    val offerHolding = holding != null || (!giving && unit.isNotBlank())
    var moveHolding by rememberSaveable { mutableStateOf(true) }
    val short = giving && moveHolding && holding != null && amount != null && amount > holding.amount + 1e-9
    val error = if (short) "توی دارایی‌هات فقط ${loanAmountFa(type(holding.typeId), holding.amount)} هست." else null
    val hasWho = person != null || who != null || (naming && newName.isNotBlank())
    // Raised by a save tap that could not save: from then on every blank answer says so.
    var tried by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = Radius.sheet, topEnd = Radius.sheet),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            Modifier
                .nestedScroll(SheetFlingGuard)
                .navigationBarsPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Space.xl)
                .padding(bottom = Space.l),
        ) {
            SheetTitle(
                when {
                    person == null -> "قرض دادم"
                    giving -> "به ${person.person.name} دادی"
                    else -> "از ${person.person.name} گرفتی"
                },
            )

            if (person == null) {
                SheetLabel("به کی؟")
                FlowRow(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Space.s),
                    verticalArrangement = Arrangement.spacedBy(Space.s),
                ) {
                    people.forEach { v -> Chip(v.person.name, !naming && who == v.person.id) { who = v.person.id; naming = false } }
                    Chip("+ یه نفر تازه", naming) { naming = true; who = null }
                }
                if (tried && !naming && who == null) MissingText("یه نفر رو انتخاب کن.")
                if (naming) {
                    Spacer(Modifier.height(Space.m))
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it.take(MAX_LOAN_NAME) },
                        label = { Text("اسم") },
                        singleLine = true,
                        isError = tried && newName.isBlank(),
                        supportingText = if (tried && newName.isBlank()) ({ FieldError("اسمش رو بنویس.") }) else null,
                        shape = RoundedCornerShape(Radius.field),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            SheetLabel("چی؟")
            UnitChips(units, unit, type) { unit = it; amountText = "" }
            Spacer(Modifier.height(Space.m))
            AmountField(unit, amountText, { amountText = it }, type, error ?: missingAmountFa(unit).takeIf { tried && amountText.isBlank() })

            if (offerHolding) {
                Row(
                    Modifier
                        .padding(top = Space.s)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(Radius.card))
                        .toggleable(value = moveHolding, role = Role.Switch, onValueChange = { moveHolding = it })
                        .padding(Space.m),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        if (giving) "از دارایی‌هام کم کن" else "به دارایی‌هام اضافه کن",
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(checked = moveHolding, onCheckedChange = null)
                }
            }

            Spacer(Modifier.height(Space.l))
            CommitButton("ثبت") {
                focus.clearFocus()
                tried = true
                if (!hasWho || amount == null || short) return@CommitButton
                close {
                    onSave(
                        person?.person?.id ?: who.takeIf { !naming },
                        newName.trim().takeIf { naming && it.isNotEmpty() },
                        unit,
                        rial ?: 0L,
                        if (unit.isBlank()) 0.0 else amount ?: 0.0,
                        offerHolding && moveHolding,
                    )
                }
            }
        }
    }
}

// ─────────────────────────── from a transaction ───────────────────────────

/**
 * «به کی دادی؟» — asked the moment a row is filed as قرض, while the money is in front of her.
 * Nobody is asked whether it was a loan or a repayment: the preview under the chosen name says
 * what the arithmetic makes of it, and that sentence is the answer to the question not asked.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LoanLinkSheet(
    entry: LedgerEntry,
    views: List<LoanView>,
    current: String?,
    type: (String) -> AssetType,
    onLink: (String?) -> Unit,
    onCreate: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val focus = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    fun close(then: () -> Unit) = scope.hideThen(sheetState, then)
    val delta = loanLinkRial(entry) ?: 0L
    var picked by rememberSaveable(entry.txn.ref) { mutableStateOf(current) }
    var naming by rememberSaveable(entry.txn.ref) { mutableStateOf(views.isEmpty()) }
    var name by rememberSaveable(entry.txn.ref) { mutableStateOf("") }
    // Raised by a save tap that could not save: from then on every blank answer says so.
    var tried by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = Radius.sheet, topEnd = Radius.sheet),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            Modifier
                .nestedScroll(SheetFlingGuard)
                .navigationBarsPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Space.xl)
                .padding(bottom = Space.l),
        ) {
            SheetTitle(if (delta > 0L) "این پول رو به کی دادی؟" else "این پول رو کی داد؟")
            Text(
                txnTitleFa(entry.txn) + "، " +
                    (entry.txn.amountRial?.let { bidi(faCompact(tomanOf(it)) + " تومان") + "، " } ?: "") +
                    faDay(entry.txn.day),
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(Space.m))
            views.forEach { view ->
                val selected = !naming && picked == view.person.id
                Row(
                    Modifier
                        .fillMaxWidth()
                        .selectable(selected = selected, role = Role.RadioButton) { picked = view.person.id; naming = false }
                        .heightIn(min = 56.dp)
                        .padding(vertical = Space.s),
                    verticalAlignment = Alignment.Top,
                ) {
                    RadioButton(selected = selected, onClick = null)
                    Spacer(Modifier.width(Space.s))
                    Column(Modifier.weight(1f)) {
                        Text(view.person.name, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                        Text(
                            if (view.side == LoanSide.SETTLED) loanSideFa(view.side)
                            else "الان ${loanSideFa(view.side)}: ${loanWhatFa(view, type)}",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (selected && view.person.id != current) {
                            Text(
                                loanAfterFa(view, delta, type),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(top = Space.xs),
                            )
                        }
                    }
                }
            }
            if (tried && !naming && picked == null) MissingText("یه نفر رو انتخاب کن.")
            if (naming) {
                Spacer(Modifier.height(Space.s))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(MAX_LOAN_NAME) },
                    label = { Text("اسمش چیه؟") },
                    singleLine = true,
                    isError = tried && name.isBlank(),
                    supportingText = if (tried && name.isBlank()) ({ FieldError("اسمش رو بنویس.") }) else null,
                    shape = RoundedCornerShape(Radius.field),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Spacer(Modifier.height(Space.l))
            CommitButton(if (naming) "ساختن و وصل کردن" else "ثبت") {
                focus.clearFocus()
                tried = true
                val unanswered = if (naming) name.isBlank() else picked == null
                if (unanswered) return@CommitButton
                // The person it is already linked to, picked again, is nothing to write: the
                // answer stands, and the sheet just closes.
                close {
                    if (naming) onCreate(name.trim()) else if (picked != current) onLink(picked)
                    onDismiss()
                }
            }
            if (!naming) SheetPill("+ یه نفر تازه") { naming = true }
            if (current != null) SheetPill("جداش کن") { close { onLink(null); onDismiss() } }
        }
    }
}

/** The link as the transaction's own page shows it, and the way to change it. */
@Composable
internal fun LoanLinkRow(entry: LedgerEntry, view: LoanView?, type: (String) -> AssetType, onOpen: () -> Unit) {
    val out = entry.txn.direction == "out"
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.card))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(role = Role.Button, onClickLabel = if (view != null) "تغییر" else "وصل کردن", onClick = onOpen)
            .padding(Space.l),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                view?.let { (if (out) "به " else "از ") + it.person.name } ?: "به کسی وصل نیست",
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                view?.let {
                    if (it.side == LoanSide.SETTLED) loanSideFa(it.side) else "${loanSideFa(it.side)}: ${loanWhatFa(it, type)}"
                } ?: "وصلش کن تا توی طلب و بدهی حساب بشه.",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            if (view != null) "تغییر" else "وصل کن",
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = Space.s),
        )
    }
}

/**
 * The three sheets, wherever they were asked for. Adding a person lands on that person's page,
 * and deleting one leaves an undo, since a person
 * carries every move she wrote down about them.
 */
@Composable
internal fun LoanAsks(
    ask: LoanAsk?,
    state: UiState,
    vm: AppVm,
    notices: TransientNotices,
    type: (String) -> AssetType,
    onClose: () -> Unit,
    onPerson: (String) -> Unit,
    onPersonGone: () -> Unit,
) {
    when (ask) {
        null -> Unit
        LoanAsk.AddPerson -> LoanPersonSheet(
            person = null,
            holdings = state.holdings,
            type = type,
            onSave = { name, promise, opening ->
                onClose()
                vm.addLoanPerson(name, promise, opening)?.let(onPerson)
            },
            onDelete = null,
            onDismiss = onClose,
        )
        is LoanAsk.EditPerson -> {
            val person = state.loans.people.firstOrNull { it.id == ask.id }
            if (person == null) {
                androidx.compose.runtime.LaunchedEffect(ask) { onClose() }
                return
            }
            LoanPersonSheet(
                person = person,
                holdings = state.holdings,
                type = type,
                onSave = { name, promise, _ -> vm.editLoanPerson(person.id, name, promise); onClose() },
                onDelete = {
                    vm.deleteLoanPerson(person.id)?.let { (gone, moves) ->
                        onPersonGone()
                        notices.show("حساب ${gone.name} پاک شد", "برگردون") { vm.restoreLoanPerson(gone, moves) }
                    }
                    onClose()
                },
                onDismiss = onClose,
            )
        }
        is LoanAsk.Move -> LoanMoveSheet(
            person = ask.personId?.let { id -> state.loanViews.firstOrNull { it.person.id == id } },
            people = state.loanViews,
            giving = ask.giving,
            preset = ask.preset,
            holdings = state.holdings,
            type = type,
            onSave = { personId, newName, typeId, rial, amount, moveHolding ->
                val id = personId ?: newName?.let { vm.addLoanPerson(it, null, null) }
                if (id != null) vm.addLoanMove(id, typeId, rial, amount, ask.giving, moveHolding)
                onClose()
            },
            onDismiss = onClose,
        )
    }
}
