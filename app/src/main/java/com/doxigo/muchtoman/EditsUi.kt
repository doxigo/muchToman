package com.doxigo.muchtoman

import androidx.compose.foundation.background
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Slides a sheet away, then runs [then] — however the slide ends. A back press or a scrim tap
 * in those ~250 ms starts the sheet's own hide, which cancels this one, and a save queued behind
 * it in the same coroutine was dropped with the sheet. Every sheet's `close` comes through here.
 */
@OptIn(ExperimentalMaterial3Api::class)
internal fun CoroutineScope.hideThen(sheetState: SheetState, then: () -> Unit) {
    launch { sheetState.hide() }.invokeOnCompletion { then() }
}

/** A Rial figure as the amount field holds it: Toman digits, a tenth only when there is one. */
internal fun rialToField(rial: Long): String =
    if (rial % 10 == 0L) (rial / 10).toString() else "${rial / 10}.${rial % 10}"

/**
 * «ویرایش تراکنش» — the figure, the day, and in a household whose spending it was.
 *
 * Nothing is saved until she says so, and what she leaves alone is not written at all: a sheet
 * opened to fix the day must not pin the figure it happened to show. On a message's row the one
 * line under the fields says what the correction does *not* touch — the account's balance is the
 * bank's own مانده, and a figure she fixed here must not quietly disagree with it unannounced.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun EditTxnSheet(
    entry: LedgerEntry,
    members: List<FamilyMember>,
    onSave: (rial: Long?, day: Long?, memberId: String?) -> Unit,
    onRevert: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    fun close(then: () -> Unit) = scope.hideThen(sheetState, then)

    val txn = entry.txn
    val today = remember { tehranDay(System.currentTimeMillis()) }
    var amount by rememberSaveable(txn.ref) { mutableStateOf(txn.amountRial?.let(::rialToField).orEmpty()) }
    var day by rememberSaveable(txn.ref) { mutableLongStateOf(txn.day) }
    var member by rememberSaveable(txn.ref) { mutableStateOf(entry.ownerMemberId) }
    val rial = remember(amount) { tomanFieldToRial(amount) }
    val focus = LocalFocusManager.current

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
            SheetTitle("ویرایش تراکنش")

            SheetLabel("چقدر، به تومان")
            OutlinedTextField(
                value = amount,
                onValueChange = { amount = it },
                singleLine = true,
                isError = rial == null,
                visualTransformation = GroupedNumber,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                supportingText = {
                    Text(rial?.let { faWordsToman(tomanOf(it)) } ?: "مبلغ رو فقط با عدد بنویس.")
                },
                shape = RoundedCornerShape(Radius.field),
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = "مبلغ به تومان" },
            )

            SheetLabel("کِی؟")
            DayStepper(day, today) { day = it }

            if (members.size > 1) {
                SheetLabel("خرج کی بود؟")
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(Space.s),
                    verticalArrangement = Arrangement.spacedBy(Space.s),
                ) {
                    members.forEach { m ->
                        MemberChip(m, chosen = m.id == member) { member = m.id }
                    }
                }
            }

            if (txn.ref.startsWith("s:")) {
                Spacer(Modifier.height(Space.l))
                Text(
                    "گزارش‌ها و بودجه‌ها با عدد تو حساب می‌شن؛ موجودی حساب همون مانده‌ای می‌مونه که بانک گفته.",
                    fontSize = 13.sp,
                    lineHeight = 22.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(Space.xl))
            PillButton(
                "ذخیره",
                {
                    // Never greyed: the field already says what is wrong with the figure, and the
                    // tap takes the keyboard off those words.
                    focus.clearFocus()
                    val clean = rial ?: return@PillButton
                    close {
                        onSave(
                            clean.takeIf { it != txn.amountRial },
                            day.takeIf { it != txn.day },
                            member.takeIf { it != entry.ownerMemberId && members.size > 1 },
                        )
                        onDismiss()
                    }
                },
                voice = ButtonVoice.PRIMARY,
                block = true,
                modifier = Modifier.fillMaxWidth(),
            )
            if (onRevert != null) {
                Spacer(Modifier.height(Space.s))
                PillButton(
                    if (txn.ref.startsWith("s:")) "برگردون به عدد پیامک" else "برگردون به عدد اول",
                    { close { onRevert(); onDismiss() } },
                    block = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.height(Space.s))
            PillButton("انصراف", { close(onDismiss) }, block = true, modifier = Modifier.fillMaxWidth())
        }
    }
}

/** A household member as a pill: their face and name, `primary` when chosen. */
@Composable
private fun MemberChip(member: FamilyMember, chosen: Boolean, onPick: () -> Unit) {
    Row(
        Modifier
            .clip(RoundedCornerShape(Radius.pill))
            .background(
                if (chosen) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.surfaceVariant,
            )
            .selectable(selected = chosen, role = Role.RadioButton, onClick = onPick)
            .heightIn(min = 44.dp)
            .padding(horizontal = Space.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MemberFace(member.name, member.avatar, size = 24.dp)
        Spacer(Modifier.width(Space.s))
        Text(
            member.name,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (chosen) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** A typed list that outlives the lock (AppScreens' `unlocked`): saved as a plain list, restored live. */
private fun <T> draftListSaver() = listSaver<SnapshotStateList<T>, T>(
    save = { it.toList() },
    restore = { it.toMutableStateList() },
)

/**
 * «تقسیم بین دسته‌ها» — one payment, several things bought.
 *
 * The first part takes whatever the others leave, so the parts always add up to the row and
 * there is no remainder to reconcile: she types what the شیرینی cost and the rest stays خواربار.
 * The first part's category is the row's own to start with, because that is what she has already
 * said most of it was.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SplitSheet(
    entry: LedgerEntry,
    categories: List<Category>,
    categoryUse: Map<String, Double>,
    onSave: (List<Pair<String, Long>>) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    fun close(then: () -> Unit) = scope.hideThen(sheetState, then)

    val total = entry.txn.amountRial ?: 0L
    val choices = remember(categories, categoryUse) {
        categoryChoices(categories, entry.txn.direction, categoryUse).filter { it.id != CAT_TRANSFER }
    }
    val names = remember(categories) { categories.associate { it.id to it.nameFa } }
    // The first part's figure is never typed, so it is never stored here — see [firstRial].
    val ids = rememberSaveable(entry.txn.ref, saver = draftListSaver()) {
        mutableStateListOf<String?>().apply {
            if (entry.split.isNotEmpty()) addAll(entry.split.map { it.categoryId })
            else addAll(listOf(entry.categoryId.takeIf { it != CAT_UNCATEGORISED }, null))
        }
    }
    val amounts = rememberSaveable(entry.txn.ref, saver = draftListSaver()) {
        mutableStateListOf<String>().apply {
            if (entry.split.isNotEmpty()) addAll(entry.split.map { rialToField(it.rial) })
            else addAll(listOf("", ""))
        }
    }
    var picking by rememberSaveable(entry.txn.ref) { mutableStateOf<Int?>(null) }
    // Raised by a save tap: a part with no category or no figure is only wrong once she has asked
    // for the split to be saved — until then it is just not filled in yet.
    var missing by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current

    val rest = (1 until amounts.size).map { tomanFieldToRial(amounts[it]) }
    val firstRial = total - rest.sumOf { it ?: 0L }
    val chosen = ids.filterNotNull()
    val complete = ids.all { it != null } && chosen.toSet().size == chosen.size &&
        rest.all { it != null } && firstRial > 0

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
            SheetTitle("تقسیم بین دسته‌ها")
            Spacer(Modifier.height(Space.xs))
            // Exact, not compact: she is reconciling a receipt, and «۶ میلیون» over parts that
            // add to ۶٬۰۱۲٬۰۰۰ would read as a split that does not add up.
            Text(
                bidi("کل تراکنش ${faNumber(tomanOf(total))} تومان"),
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(Space.m))

            ids.indices.forEach { i ->
                if (i > 0) PartDivider()
                val over = i == 0 && firstRial <= 0
                Row(Modifier.fillMaxWidth().padding(vertical = Space.xs), verticalAlignment = Alignment.CenterVertically) {
                    SplitPartHead(
                        nameFa = ids[i]?.let { names[it] },
                        sub = when {
                            over -> "بخش‌های دیگه از کل تراکنش بیشتر شدن."
                            missing && ids[i] == null -> "دسته‌اش رو انتخاب کن."
                            i == 0 -> "باقی مبلغ"
                            else -> null
                        },
                        error = over || (missing && ids[i] == null),
                        open = picking == i,
                        modifier = Modifier.weight(1f),
                    ) { picking = if (picking == i) null else i }
                    if (i == 0 && !over) {
                        Text(
                            faNumber(tomanOf(firstRial)),
                            style = figureStyle(MaterialTheme.colorScheme.onSurface, FontWeight.Bold),
                            fontSize = 15.sp,
                            modifier = Modifier.padding(start = Space.s, end = Space.xs),
                        )
                    }
                    if (i > 0 && ids.size > 2) {
                        Spacer(Modifier.width(Space.s))
                        PillButton("حذف", {
                            ids.removeAt(i); amounts.removeAt(i); picking = null
                        }, voice = ButtonVoice.DANGER)
                    }
                }
                if (picking == i) {
                    Spacer(Modifier.height(Space.s))
                    CategoryGrid(
                        choices = choices.filter { it.id == ids[i] || it.id !in chosen },
                        selectedId = ids[i],
                        onPick = { ids[i] = it.id; picking = null },
                        selectedLabel = "انتخاب‌شده",
                    )
                    Spacer(Modifier.height(Space.s))
                }
                if (i > 0) {
                    val parsed = rest[i - 1]
                    val blank = amounts[i].isBlank()
                    OutlinedTextField(
                        value = amounts[i],
                        onValueChange = { amounts[i] = it },
                        singleLine = true,
                        isError = parsed == null && (!blank || missing),
                        visualTransformation = GroupedNumber,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        label = { Text("چقدر، به تومان") },
                        supportingText = when {
                            blank && missing -> ({
                                Text(
                                    "مبلغش رو بنویس.",
                                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                                )
                            })
                            // A figure it cannot read holds the save back, so it has to say so.
                            parsed == null && !blank -> ({ Text("مبلغ رو فقط با عدد بنویس.") })
                            parsed != null -> ({ Text(faWordsToman(tomanOf(parsed)).orEmpty()) })
                            else -> null
                        },
                        shape = RoundedCornerShape(Radius.field),
                        modifier = Modifier.fillMaxWidth().padding(start = PartInset),
                    )
                }
            }

            if (ids.size < MAX_SPLIT_PARTS) {
                PartDivider()
                // The grid opens with the part: a category is the first thing a new part needs.
                AddPartRow { ids.add(null); amounts.add(""); picking = ids.lastIndex }
            }

            Spacer(Modifier.height(Space.xl))
            PillButton(
                "ذخیره تقسیم",
                {
                    // Never greyed: a tap that cannot save marks every part still missing something,
                    // with the keyboard out of the way of the words.
                    focus.clearFocus()
                    missing = true
                    if (!complete) return@PillButton
                    val parts = ids.mapIndexed { i, id ->
                        id!! to if (i == 0) firstRial else rest[i - 1]!!
                    }
                    close { onSave(parts); onDismiss() }
                },
                voice = ButtonVoice.PRIMARY,
                block = true,
                modifier = Modifier.fillMaxWidth(),
            )
            if (entry.split.isNotEmpty()) {
                Spacer(Modifier.height(Space.s))
                PillButton(
                    "یکی‌اش کن",
                    { close { onSave(emptyList()); onDismiss() } },
                    block = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.height(Space.s))
            PillButton("انصراف", { close(onDismiss) }, block = true, modifier = Modifier.fillMaxWidth())
        }
    }
}

/** Where a part's text starts — past the row's own inset, the disc and the gap after it. */
private val PartInset = Space.xs + 44.dp + Space.m

/** Between two parts, from where the text starts: the discs stay one column. */
@Composable
private fun PartDivider() {
    HorizontalDivider(
        color = MaterialTheme.colorScheme.outlineVariant,
        modifier = Modifier.padding(start = PartInset, top = Space.s, bottom = Space.s),
    )
}

/**
 * A part's head in the anatomy دفتر gives the row it becomes — the category's disc, its name — so
 * the sheet already shows the rows a save will list. Unchosen, it is the grey dots of a row still
 * waiting. Disc and name are one target that opens the grid under it, and the chevron says so.
 */
@Composable
private fun SplitPartHead(
    nameFa: String?,
    sub: String?,
    error: Boolean,
    open: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val turn by animateFloatAsState(if (open) 180f else 0f, Motion.settle(), label = "chevron")
    Row(
        modifier
            .clip(RoundedCornerShape(Radius.field))
            .clickable(role = Role.Button, onClickLabel = if (open) "بستن دسته‌ها" else "انتخاب دسته", onClick = onClick)
            .padding(Space.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CategoryDisc(nameFa ?: "دسته‌بندی نشده")
        Spacer(Modifier.width(Space.m))
        Column(Modifier.weight(1f)) {
            // The chevron rides the name's own line, so a two-line sentence under it cannot push
            // it away from the word it belongs to.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    nameFa ?: "انتخاب دسته",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (nameFa == null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Icon(
                    Icons.Rounded.KeyboardArrowDown,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = Space.xs).size(20.dp).rotate(turn),
                )
            }
            if (sub != null) {
                Text(
                    sub,
                    fontSize = 13.sp,
                    lineHeight = 20.sp,
                    color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp).semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
        }
    }
}

/** The way to one more part, in the grid's own «add» voice: a `primary` plus on its wash. */
@Composable
private fun AddPartRow(onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = Space.xs)
            .clip(RoundedCornerShape(Radius.field))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(Space.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(44.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) { PlusMark(MaterialTheme.colorScheme.primary, size = 18.dp) }
        Spacer(Modifier.width(Space.m))
        Text("یه بخش دیگه", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
    }
}

/** The parts of a split row, as the transaction page lists them under its category. */
@Composable
internal fun SplitPanel(split: List<SplitPart>) {
    Column(Modifier.panel()) {
        split.forEach { part ->
            Row(
                Modifier.fillMaxWidth().padding(vertical = Space.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CategoryIcon(part.categoryFa, categoryHue(part.categoryFa), size = 18.dp)
                Spacer(Modifier.width(Space.s))
                Text(
                    part.categoryFa,
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    bidi("${faCompact(tomanOf(part.rial))} تومان"),
                    style = figureStyle(MaterialTheme.colorScheme.onSurface, FontWeight.Bold),
                    fontSize = 14.sp,
                )
            }
        }
    }
}

/**
 * The foot of a transaction's page: what can be done to the row, in the band تنظیمات speaks in.
 *
 * Down here because none of it is the page's job — the page is for reading a transaction and
 * filing it — and in a band because that is how this app already offers a short list of acts: a
 * mark on a disc, a name, where it stands, and the chevron to the sheet it opens. Grey discs,
 * because each acts on this row rather than leading anywhere (DESIGN.md: a green disc is a door).
 *
 * Delete is a band of its own under the others, never a row among them: the one act that loses
 * something must not sit a thumb's slip from the two that do not. Its two taps are the app's one
 * two-tap, worn by the row itself — the first turns the whole row red and says what the second
 * will do, and says it aloud.
 */
@Composable
internal fun TxnActs(
    entry: LedgerEntry,
    onEdit: (() -> Unit)?,
    onSplit: (() -> Unit)?,
    onDelete: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val acts = buildList<@Composable (Shape, Boolean) -> Unit> {
        onEdit?.let {
            add { shape, divided ->
                ActRow("ویرایش تراکنش", ActGlyph.PENCIL, shape, divided, it, "اصلاح‌شده".takeIf { entry.edited })
            }
        }
        onSplit?.let {
            add { shape, divided ->
                ActRow(
                    "تقسیم بین دسته‌ها", ActGlyph.SPLIT, shape, divided, it,
                    entry.split.takeIf { p -> p.isNotEmpty() }?.let { p -> "${faNumber(p.size.toDouble())} دسته" },
                )
            }
        }
    }
    Column(modifier) {
        acts.forEachIndexed { i, act -> act(bandShape(i, acts.size), i < acts.size - 1) }
        if (onDelete != null) {
            if (acts.isNotEmpty()) Spacer(Modifier.height(Space.l))
            DeleteRow(entry.txn.ref, onDelete)
        }
    }
}

/** One act in the band: [IndexRow]'s anatomy with the control's grey disc. */
@Composable
private fun ActRow(
    title: String,
    glyph: ActGlyph,
    shape: Shape,
    divided: Boolean,
    onClick: () -> Unit,
    value: String? = null,
) {
    Box(Modifier.fillMaxWidth().clip(shape).background(MaterialTheme.colorScheme.surface)) {
        Row(
            Modifier
                .clickable(role = Role.Button, onClick = onClick)
                .padding(Space.l),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(44.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHighest),
                contentAlignment = Alignment.Center,
            ) { ActIcon(glyph, MaterialTheme.colorScheme.onSurface) }
            Text(
                title,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = Space.m).weight(1f),
            )
            value?.let {
                Text(
                    it,
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
                modifier = Modifier.align(Alignment.BottomStart).padding(start = Space.l + 44.dp + Space.m),
            )
        }
    }
}

/**
 * The delete, as a row of its own band. Error ink on the band's own ground until the first tap;
 * then the row fills with error and the name becomes the question — the colour confirms, the
 * words carry it, and TalkBack hears it. Keyed by [ref], so a row reused for another transaction
 * never arrives armed.
 */
@Composable
private fun DeleteRow(ref: String, onConfirmed: () -> Unit) {
    var armed by remember(ref) { mutableStateOf(false) }
    val scheme = MaterialTheme.colorScheme
    val ground by animateColorAsState(
        if (armed) scheme.error else scheme.surface, tween(Motion.fast, easing = Motion.enter), label = "deleteGround",
    )
    val ink by animateColorAsState(
        if (armed) scheme.onError else scheme.error, tween(Motion.fast, easing = Motion.enter), label = "deleteInk",
    )
    val disc by animateColorAsState(
        if (armed) scheme.onError.copy(alpha = 0.16f) else scheme.errorContainer,
        tween(Motion.fast, easing = Motion.enter),
        label = "deleteDisc",
    )
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.group))
            .background(ground)
            .clickable(role = Role.Button) { if (armed) { armed = false; onConfirmed() } else armed = true }
            .semantics { liveRegion = LiveRegionMode.Polite }
            .padding(Space.l),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(44.dp).clip(CircleShape).background(disc), contentAlignment = Alignment.Center) {
            ActIcon(ActGlyph.TRASH, ink)
        }
        Text(
            if (armed) "مطمئنی؟ برای حذف دوباره بزن" else "حذف این تراکنش",
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold,
            color = ink,
            modifier = Modifier.padding(horizontal = Space.m).weight(1f),
        )
    }
}
