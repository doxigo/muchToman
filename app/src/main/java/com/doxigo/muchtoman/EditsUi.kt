package com.doxigo.muchtoman

import androidx.compose.foundation.background
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
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

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
    fun close(then: () -> Unit) = scope.launch { sheetState.hide(); then() }

    val txn = entry.txn
    val today = remember { tehranDay(System.currentTimeMillis()) }
    var amount by rememberSaveable(txn.ref) { mutableStateOf(txn.amountRial?.let(::rialToField).orEmpty()) }
    var day by rememberSaveable(txn.ref) { mutableLongStateOf(txn.day) }
    var member by rememberSaveable(txn.ref) { mutableStateOf(entry.ownerMemberId) }
    val rial = remember(amount) { tomanFieldToRial(amount) }

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
                voice = if (rial != null) ButtonVoice.PRIMARY else ButtonVoice.TONAL,
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
    fun close(then: () -> Unit) = scope.launch { sheetState.hide(); then() }

    val total = entry.txn.amountRial ?: 0L
    val choices = remember(categories, categoryUse) {
        categoryChoices(categories, entry.txn.direction, categoryUse).filter { it.id != CAT_TRANSFER }
    }
    val names = remember(categories) { categories.associate { it.id to it.nameFa } }
    // The first part's figure is never typed, so it is never stored here — see [firstRial].
    val ids = remember(entry.txn.ref) {
        mutableStateListOf<String?>().apply {
            if (entry.split.isNotEmpty()) addAll(entry.split.map { it.categoryId })
            else addAll(listOf(entry.categoryId.takeIf { it != CAT_UNCATEGORISED }, null))
        }
    }
    val amounts = remember(entry.txn.ref) {
        mutableStateListOf<String>().apply {
            if (entry.split.isNotEmpty()) addAll(entry.split.map { rialToField(it.rial) })
            else addAll(listOf("", ""))
        }
    }
    var picking by remember(entry.txn.ref) { mutableStateOf<Int?>(null) }

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
            Text(
                bidi("کل تراکنش ${faCompact(tomanOf(total))} تومان"),
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            ids.indices.forEach { i ->
                SheetLabel(if (i == 0) "بخش اول، باقی مبلغ" else "بخش ${faNumber((i + 1).toDouble())}")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PillButton(
                        ids[i]?.let { names[it] } ?: "انتخاب دسته",
                        { picking = if (picking == i) null else i },
                        voice = if (ids[i] == null) ButtonVoice.PRIMARY else ButtonVoice.TONAL,
                        modifier = Modifier.weight(1f),
                    )
                    if (i > 0 && ids.size > 2) {
                        Spacer(Modifier.width(Space.s))
                        PillButton("حذف", {
                            ids.removeAt(i); amounts.removeAt(i); picking = null
                        }, voice = ButtonVoice.DANGER)
                    }
                }
                if (picking == i) {
                    Spacer(Modifier.height(Space.m))
                    CategoryGrid(
                        choices = choices.filter { it.id == ids[i] || it.id !in chosen },
                        selectedId = ids[i],
                        onPick = { ids[i] = it.id; picking = null },
                        selectedLabel = "انتخاب‌شده",
                    )
                }
                Spacer(Modifier.height(Space.s))
                if (i == 0) {
                    Text(
                        if (firstRial > 0) bidi("${faCompact(tomanOf(firstRial))} تومان")
                        else "بخش‌های دیگه از کل تراکنش بیشتر شدن.",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (firstRial > 0) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(start = Space.xs),
                    )
                } else {
                    val parsed = rest[i - 1]
                    OutlinedTextField(
                        value = amounts[i],
                        onValueChange = { amounts[i] = it },
                        singleLine = true,
                        visualTransformation = GroupedNumber,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        label = { Text("چقدر، به تومان") },
                        supportingText = parsed?.let { { Text(faWordsToman(tomanOf(it)).orEmpty()) } },
                        shape = RoundedCornerShape(Radius.field),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            if (ids.size < MAX_SPLIT_PARTS) {
                Spacer(Modifier.height(Space.l))
                PillButton("یه بخش دیگه", { ids.add(null); amounts.add("") })
            }

            Spacer(Modifier.height(Space.xl))
            PillButton(
                "ذخیره تقسیم",
                {
                    if (!complete) return@PillButton
                    val parts = ids.mapIndexed { i, id ->
                        id!! to if (i == 0) firstRial else rest[i - 1]!!
                    }
                    close { onSave(parts); onDismiss() }
                },
                voice = if (complete) ButtonVoice.PRIMARY else ButtonVoice.TONAL,
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
