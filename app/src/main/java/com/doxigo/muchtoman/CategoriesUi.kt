package com.doxigo.muchtoman

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

@Composable
fun CategoriesScreen(
    categories: List<Category>,
    onAdd: (String, String, CategoryGlyph) -> Unit,
    onEdit: (Category, String, CategoryGlyph) -> Unit,
    onArchive: (Category) -> Unit,
    onBack: () -> Unit,
) {
    var side by rememberSaveable { mutableStateOf(CategoryKind.EXPENSE) }
    var adding by rememberSaveable { mutableStateOf(false) }
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }

    // «دسته‌بندی نشده» is the absence of an answer and «انتقال» is the escape hatch; neither is
    // a thing to manage. Everything else shows, shipped and hers alike, in the picker's order —
    // hers on both tabs, since the picker offers them both ways — and the ones she deleted in a
    // band of their own underneath, where they can still be brought back.
    val (deleted, live) = remember(categories, side) {
        categories.filter {
            it.id != CAT_UNCATEGORISED && it.id != "cat_send" && (it.kind == side || offeredBothWays(it))
        }.partition { it.archived }
    }

    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = Space.xl, vertical = Space.m),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ScreenTitle("دسته‌بندی‌ها", modifier = Modifier.weight(1f))
                PillButton("برگشت", onBack)
            }

            SegmentedChoice(
                options = listOf(CategoryKind.EXPENSE, CategoryKind.INCOME),
                selected = side,
                label = { if (it == CategoryKind.INCOME) "دخل" else "خرج" },
                onSelect = { side = it },
                role = Role.Tab,
                modifier = Modifier.padding(start = Space.xl, end = Space.xl, bottom = Space.m),
            )

            LazyColumn(
                Modifier.weight(1f),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = Space.xl, end = Space.xl, bottom = Space.l,
                ),
            ) {
                itemsIndexed(live, key = { _, c -> c.id }) { i, category ->
                    CategoryRow(
                        category = category,
                        shape = bandShape(i, live.size),
                        divided = i < live.size - 1,
                        onAction = { editingId = category.id },
                    )
                }
                if (deleted.isNotEmpty()) {
                    item(key = "deleted") {
                        Column(Modifier.padding(top = Space.xxl, bottom = Space.m, start = Space.xs)) {
                            Text(
                                "حذف‌شده‌ها",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = MaterialTheme.colorScheme.onBackground,
                                modifier = Modifier.semantics { heading() },
                            )
                            // Words, because «حذف» here is not what it is elsewhere: the rows she
                            // filed under it keep its name, and saying so is the difference
                            // between a tidy list and history that seems to have vanished.
                            Text(
                                "دیگه موقع دسته‌بندی پیشنهاد نمی‌شن. تراکنش‌های قبلی با همین اسم می‌مونن.",
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = Space.xs),
                            )
                        }
                    }
                    itemsIndexed(deleted, key = { _, c -> c.id }) { i, category ->
                        CategoryRow(
                            category = category,
                            shape = bandShape(i, deleted.size),
                            divided = i < deleted.size - 1,
                            onAction = { onArchive(category) },
                        )
                    }
                }
            }

            // The one loud control on the page, pinned where her thumb already is.
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Space.xl, vertical = Space.m)
                    .navigationBarsPadding(),
            ) {
                PillButton(
                    "افزودن دسته",
                    { adding = true },
                    voice = ButtonVoice.PRIMARY,
                    modifier = Modifier.fillMaxWidth(),
                    fontSize = 16.sp,
                    minHeight = 56.dp,
                )
            }
        }
    }

    if (adding) {
        CategorySheet(
            taken = categories.map { it.nameFa },
            initialKind = side,
            onSave = onAdd,
            onDismiss = { adding = false },
        )
    }

    categories.firstOrNull { it.id == editingId }?.let { editing ->
        CategorySheet(
            // Its own name is not a clash — keeping it while changing the mark is a real edit.
            taken = categories.filter { it.id != editing.id }.map { it.nameFa },
            initialKind = editing.kind,
            editing = editing,
            onSave = { name, _, glyph -> onEdit(editing, name, glyph) },
            onDelete = { onArchive(editing) },
            onDismiss = { editingId = null },
        )
    }
}

@Composable
private fun CategoryRow(
    category: Category,
    shape: androidx.compose.ui.graphics.Shape,
    divided: Boolean,
    onAction: () -> Unit,
) {
    Box(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface),
    ) {
        Row(
            Modifier.padding(horizontal = Space.l, vertical = Space.m),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val hue = categoryHue(category.nameFa)
            Box(
                Modifier.size(44.dp).clip(CircleShape).background(hue.copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center,
            ) { CategoryIcon(category.nameFa, hue, size = 22.dp) }
            Text(
                category.nameFa,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                color = if (category.archived) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = Space.m).weight(1f),
            )
            // This pill draws at 40dp but is hit and announced at 48: Compose expands any
            // smaller clickable to the minimum touch target. Growing its layout instead would
            // push every row 4dp taller for a target the finger already has.
            PillButton(
                if (category.archived) "برگردون" else "ویرایش",
                onAction,
                fontSize = 13.sp,
                minHeight = 40.dp,
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
 * A category of her own: a name and the mark it wears. No side of the ledger to pick — hers are
 * offered both ways ([customCategory]), so [initialKind] is only recorded, never asked.
 *
 * The same sheet edits one — any one, shipped or hers — with the name and mark filled in. Every
 * filed row names a category by id, so a new name reaches everything already under it, last year
 * included.
 *
 * The marks offered are the ones the app already draws ([PICKABLE_GLYPHS]) rather than an emoji
 * keyboard — one pen and one weight is what keeps a category she invented from looking like a
 * sticker stuck on top of the app, and each mark arrives with the hue the grid, the timeline
 * and the month's report already agree on.
 *
 * A sheet rather than a page, because it is reachable from three rooms now — this screen, the
 * transaction page's grid and the deck's — and a sheet returns her to whichever she came from.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategorySheet(
    taken: List<String>,
    initialKind: String,
    onSave: (String, String, CategoryGlyph) -> Unit,
    onDismiss: () -> Unit,
    editing: Category? = null,
    onDelete: () -> Unit = {},
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    fun close(then: () -> Unit) = scope.launch { sheetState.hide(); then() }

    // The mark it wears now, which on a shipped category is looked up by name rather than stored.
    val worn = editing?.let { glyphOf(it.nameFa) }
    var draft by rememberSaveable { mutableStateOf(editing?.nameFa.orEmpty()) }
    var glyph by rememberSaveable { mutableStateOf(worn ?: PICKABLE_GLYPHS.first()) }
    // Renaming one must not quietly swap the mark she already knows it by.
    var glyphChosen by rememberSaveable { mutableStateOf(editing != null) }

    // ZWNJ and spaces vary by keyboard, so «پس‌انداز» typed three ways is one name.
    fun key(s: String) = faLetters(s).replace("‌", "").replace(" ", "").trim()
    val clash = draft.isNotBlank() && taken.any { key(it) == key(draft) }
    val usable = draft.isNotBlank() && !clash

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
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Space.xl)
                .padding(bottom = Space.l),
        ) {
            SheetTitle(if (editing != null) "ویرایش دسته" else "دستهٔ تازه")

            SheetLabel("اسمش چی باشه؟")
            OutlinedTextField(
                value = draft,
                onValueChange = {
                    draft = it.take(24)
                    if (!glyphChosen) {
                        glyph = categoryGlyph(draft.trim()).takeUnless { it == CategoryGlyph.DOTS }
                            ?: PICKABLE_GLYPHS.first()
                    }
                },
                singleLine = true,
                isError = clash,
                placeholder = { Text("مثلاً باشگاه") },
                shape = RoundedCornerShape(Radius.field),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = "اسم دسته" },
            )
            if (clash) {
                Text(
                    "یه دسته با همین اسم داری.",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = Space.s, start = Space.xs),
                )
            }

            SheetLabel("نشونه‌اش")
            FlowRow(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Space.s),
                verticalArrangement = Arrangement.spacedBy(Space.s),
            ) {
                PICKABLE_GLYPHS.forEach { option ->
                    val chosen = option == glyph
                    val hue = glyphHue(option)
                    Box(
                        Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            // Selected wears the app's one «this one» colour, exactly as the
                            // grid does — and, as there, the hue steps aside rather than
                            // intensifying.
                            .background(
                                if (chosen) MaterialTheme.colorScheme.primary
                                else hue.copy(alpha = 0.18f),
                            )
                            .selectable(
                                selected = chosen,
                                role = Role.RadioButton,
                                onClick = { glyph = option; glyphChosen = true },
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        GlyphIcon(
                            option,
                            if (chosen) MaterialTheme.colorScheme.onPrimary else hue,
                            size = 22.dp,
                        )
                    }
                }
            }

            Spacer(Modifier.height(Space.xl))
            PillButton(
                if (editing != null) "ذخیره تغییرات" else "اضافه کن",
                {
                    if (usable) {
                        val name = draft.trim()
                        close {
                            onSave(name, initialKind, glyph)
                            onDismiss()
                        }
                    }
                },
                voice = if (usable) ButtonVoice.PRIMARY else ButtonVoice.TONAL,
                modifier = Modifier.fillMaxWidth(),
                fontSize = 16.sp,
                minHeight = 52.dp,
            )
            Spacer(Modifier.height(Space.s))
            PillButton(
                "انصراف",
                { close(onDismiss) },
                modifier = Modifier.fillMaxWidth(),
            )
            if (editing != null) SheetDelete("حذف این دسته") { close { onDelete(); onDismiss() } }
        }
    }
}
