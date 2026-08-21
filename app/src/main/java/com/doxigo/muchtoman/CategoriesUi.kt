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

/**
 * «دسته‌بندی‌ها» — the categories as a room of their own, not a strip at the bottom of تنظیمات.
 *
 * One list per side of the ledger, because that is how the picker itself offers them: a خرج
 * category will never be shown on money that arrived, so showing the two sides interleaved was
 * a list whose order nothing else in the app agreed with. The shipped rows are here to be seen
 * — where a transaction *can* go is the answer to «چرا این دسته پیشنهاد نشد» — and only the
 * ones she made herself carry «بردار», since a shipped category retires by a build, not a tap.
 */
@Composable
fun CategoriesScreen(
    categories: List<Category>,
    onAdd: (String, String, CategoryGlyph) -> Unit,
    onArchive: (Category) -> Unit,
    onBack: () -> Unit,
) {
    var side by rememberSaveable { mutableStateOf(CategoryKind.EXPENSE) }
    var adding by rememberSaveable { mutableStateOf(false) }
    // Archiving is one tap from irreversible here — there is no «برگردون» — so the row asks
    // once. Held by id, so opening a second row's question closes the first.
    var confirming by remember { mutableStateOf<String?>(null) }

    // «دسته‌بندی نشده» is the absence of an answer and «انتقال» is the escape hatch; neither is
    // a thing to manage. Everything else shows, shipped and hers alike, in the picker's order.
    val visible = remember(categories, side) {
        categories.filter {
            !it.archived && it.id != CAT_UNCATEGORISED && it.kind == side
        }
    }

    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = Space.xl, vertical = Space.m),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "دسته‌بندی‌ها",
                    fontSize = 28.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.weight(1f).semantics { heading() },
                )
                PillButton("برگشت", onBack)
            }

            SegmentedChoice(
                options = listOf(CategoryKind.EXPENSE, CategoryKind.INCOME),
                selected = side,
                label = { if (it == CategoryKind.INCOME) "دخل" else "خرج" },
                onSelect = { side = it; confirming = null },
                role = Role.Tab,
                modifier = Modifier.padding(start = Space.xl, end = Space.xl, bottom = Space.m),
            )

            LazyColumn(
                Modifier.weight(1f),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = Space.xl, end = Space.xl, bottom = Space.l,
                ),
            ) {
                itemsIndexed(visible, key = { _, c -> c.id }) { i, category ->
                    CategoryRow(
                        category = category,
                        shape = bandShape(i, visible.size),
                        divided = i < visible.size - 1,
                        confirming = confirming == category.id,
                        onAsk = { confirming = category.id },
                        onDismiss = { confirming = null },
                        onArchive = {
                            confirming = null
                            onArchive(category)
                        },
                    )
                }
            }

            // The one loud control on the page, pinned where her thumb already is. It names the
            // side it will add to, because that is the one thing the sheet cannot guess wrong.
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Space.xl, vertical = Space.m)
                    .navigationBarsPadding(),
            ) {
                PillButton(
                    if (side == CategoryKind.INCOME) "افزودن دستهٔ دخل" else "افزودن دستهٔ خرج",
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
        AddCategorySheet(
            taken = categories.map { it.nameFa },
            initialKind = side,
            onAdd = onAdd,
            onDismiss = { adding = false },
        )
    }
}

/**
 * One category: its mark on its disc, its name, and — only on hers — the way to retire it.
 * A shipped row says «پیش‌فرض» instead, so the asymmetry reads as a fact rather than a bug.
 */
@Composable
private fun CategoryRow(
    category: Category,
    shape: androidx.compose.ui.graphics.Shape,
    divided: Boolean,
    confirming: Boolean,
    onAsk: () -> Unit,
    onDismiss: () -> Unit,
    onArchive: () -> Unit,
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
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = Space.m).weight(1f),
            )
            when {
                category.builtin -> Text(
                    "پیش‌فرض",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                confirming -> Row {
                    PillButton("بی‌خیال", onDismiss, fontSize = 13.sp, minHeight = 40.dp)
                    Spacer(Modifier.width(Space.s))
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(Radius.pill))
                            .background(MaterialTheme.colorScheme.errorContainer)
                            .clickable(role = Role.Button, onClick = onArchive)
                            .heightIn(min = 40.dp)
                            .padding(horizontal = Space.l),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "مطمئنم",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                }
                else -> PillButton("بردار", onAsk, fontSize = 13.sp, minHeight = 40.dp)
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

/**
 * A category of her own: a name, which side of the ledger it lives on, and the mark it wears.
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
fun AddCategorySheet(
    taken: List<String>,
    initialKind: String,
    onAdd: (String, String, CategoryGlyph) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    fun close(then: () -> Unit) = scope.launch { sheetState.hide(); then() }

    var draft by rememberSaveable { mutableStateOf("") }
    var kind by rememberSaveable { mutableStateOf(initialKind) }
    var glyph by rememberSaveable { mutableStateOf(PICKABLE_GLYPHS.first()) }

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
            Text(
                "دستهٔ تازه",
                fontSize = 24.sp,
                fontWeight = FontWeight.ExtraBold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.semantics { heading() },
            )

            SheetLabel("اسمش چی باشه؟")
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it.take(24) },
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

            // Which side of the ledger it belongs to, and the only thing here she cannot change
            // later: the picker offers a category only on transactions that went that way.
            SheetLabel("کدوم طرف دفتر؟")
            SegmentedChoice(
                options = listOf(CategoryKind.EXPENSE, CategoryKind.INCOME),
                selected = kind,
                label = { if (it == CategoryKind.INCOME) "دخل" else "خرج" },
                onSelect = { kind = it },
            )

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
                                onClick = { glyph = option },
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
                "اضافه کن",
                {
                    if (usable) {
                        val name = draft.trim()
                        close {
                            onAdd(name, kind, glyph)
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
        }
    }
}
