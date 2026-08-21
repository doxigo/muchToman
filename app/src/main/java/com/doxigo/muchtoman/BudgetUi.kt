package com.doxigo.muchtoman

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlin.math.roundToLong

/**
 * آینده — the caps she keeps, the goals she is saving for, and her own verdict on her own spending.
 *
 * These three are the whole reward system. There are no points, no streaks, no confetti and no
 * leaderboard, because the FCA's own experiments found those change how people take financial
 * risks — and this app has no business doing that to anybody. What is left is the encouragement
 * with evidence behind it: a limit she chose, a figure she is working towards, and her own answer
 * to «آیا ارزشش را داشت؟».
 *
 * ## Why one screen and not two
 *
 * A budget and a goal are the same row read from either side (see `Budget.kt`), and on the screen
 * they are the same object seen from either side too: a figure she picked, how much of it has
 * happened, and how long is left. Two tabs would have split that in half to hold four cards, and
 * the bar has five slots for the whole app. So they are stacked, budgets first, because a budget is
 * a thing she checks and a goal is a thing she checks on.
 *
 * There is deliberately no mode switch between them. The report has one, and it is right there —
 * دخل و خرج and دارایی are two screens of charts, and only one can be on screen. Two short lists
 * are not alternatives; they are a page. What separates them instead is the assets tab's own
 * device: each section is a **band** — one grouped object, its rows divided by hairlines, its way
 * of growing (the «+» row) built in as its last row — under a heading in the same voice the
 * asset sections use. Two bands with air between them is a separation the eye reads before the
 * words do, and it costs no mode, no tab and no second screen.
 *
 * Every row opens its own edit sheet: the figure she picked is a decision, and a decision she
 * cannot revisit without deleting it is a decision the app has taken hostage. Delete lives inside
 * that sheet, behind the same two-tap confirm the asset rows use — the card's corner says the
 * common verb, and the rare destructive one no longer sits one stray tap from a list she scrolls.
 */

/** How the four [BudgetLevel]s speak. Everything on the card takes its colour from here. */
@Composable
private fun budgetTone(level: Int): Color = when (level) {
    // `error` and nothing else. Being over is the one state on this screen that is not a
    // reading of her month but a fact about it, and the app already has exactly one colour for
    // "this needs looking at".
    BudgetLevel.OVER -> MaterialTheme.colorScheme.error
    // Gold in the dark, deep gold on paper — the app's caution, and the one colour it owns that
    // is neither "the answer" nor "a fault". Both thresholds share it: the difference between 80
    // and 95 is what the sentence says, never a third hue nobody can rank at a glance.
    BudgetLevel.NEAR, BudgetLevel.CLOSE -> MaterialTheme.colorScheme.secondary
    else -> MaterialTheme.colorScheme.primary
}

@Composable
fun BudgetScreen(
    budgets: List<BudgetProgress>,
    goals: List<GoalProgress>,
    categories: List<Category>,
    summary: WorthItSummary,
    /** True when she keeps budgets and this phone would not be able to tell her about them. */
    notifyBlocked: Boolean,
    onAddBudget: (Category, BudgetPeriod, Long) -> Unit,
    onEditBudget: (String, BudgetPeriod, Long) -> Unit,
    onAddGoal: (String, Long, GoalHorizon) -> Unit,
    onEditGoal: (String, String, Long, GoalHorizon?) -> Unit,
    onDelete: (String) -> Unit,
    onAskNotify: () -> Unit,
    bottomInset: Dp,
) {
    var addingBudget by remember { mutableStateOf(false) }
    var addingGoal by remember { mutableStateOf(false) }
    // The id, not the progress: the ledger can republish under an open sheet — a message
    // arriving, a receipt refiled on the other phone — and a held snapshot would pin the sheet
    // to figures the screen behind it no longer shows. Resolved fresh each pass; a budget
    // deleted elsewhere resolves to nothing and the sheet simply closes.
    var editingBudget by remember { mutableStateOf<String?>(null) }
    var editingGoal by remember { mutableStateOf<String?>(null) }

    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(horizontal = Space.xl)
                .verticalScroll(rememberScrollState())
                .padding(bottom = bottomInset + Space.l),
        ) {
            Text(
                "آینده",
                fontSize = 28.sp,
                fontWeight = FontWeight.ExtraBold,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = Space.m)
                    .semantics { heading() },
            )

            SectionLabel("بودجه‌ها", top = Space.s)

            // Above the cards, not below them: it is the reason the cards would otherwise be
            // silent, and a warning about silence under the thing it is silencing is a warning
            // nobody reads. Only ever shown when there is something to be silent about.
            if (notifyBlocked) {
                NotifyBlockedCard(
                    "بودجه‌هات همین‌جا حساب می‌شن، ولی تا اعلان روشن نباشه بیرون از برنامه " +
                        "خبری بهت نمی‌رسه.",
                    onAskNotify,
                )
                Spacer(Modifier.height(Space.m))
            }

            if (budgets.isEmpty()) {
                // Not «هنوز بودجه‌ای نداری» — an empty screen's job is to say what the thing is
                // for, and this one has to carry the word «فصلی» too, because a quarterly budget
                // is the one shape nobody expects an app to offer.
                Text(
                    "برای هر دسته می‌تونی سقف خرج بذاری — هفتگی، ماهانه یا فصلی. " +
                        "وقتی به سقف نزدیک شدی یا از اون گذشتی، خبرت می‌کنیم.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 26.sp,
                )
                Spacer(Modifier.height(Space.l))
            }

            // One band per section, the «+» row as its last row: the section and the way to
            // grow it are one object, and the air between bands is the separation.
            val budgetRows = budgets.size + 1
            budgets.forEachIndexed { i, budget ->
                BudgetCard(
                    budget,
                    shape = bandShape(i, budgetRows),
                    divided = true,
                    onOpen = { editingBudget = budget.goal.id },
                )
            }
            AddRow(
                label = if (budgets.isEmpty()) "اولین بودجه" else "بودجهٔ تازه",
                shape = bandShape(budgets.size, budgetRows),
                onClick = { addingBudget = true },
            )

            SectionLabel("هدف‌ها")
            if (goals.isEmpty()) {
                Text(
                    "اینجا فقط هدف‌هایی رو می‌بینی که خودت انتخاب کردی. " +
                        "خبری از امتیاز، زنجیره یا مقایسه با بقیه نیست.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 26.sp,
                )
                Spacer(Modifier.height(Space.l))
            }
            val goalRows = goals.size + 1
            goals.forEachIndexed { i, progress ->
                GoalCard(
                    progress,
                    shape = bandShape(i, goalRows),
                    divided = true,
                    onOpen = { editingGoal = progress.goal.id },
                )
            }
            AddRow(
                label = if (goals.isEmpty()) "اولین هدف" else "هدف تازه",
                shape = bandShape(goals.size, goalRows),
                onClick = { addingGoal = true },
            )

            if (summary.total > 0) {
                SectionLabel("به نظر خودت")
                WorthItSummaryCard(summary)
            }
            Spacer(Modifier.height(Space.huge))
        }
    }

    if (addingBudget) {
        BudgetSheet(
            // Only what she can actually budget, and only once each. A second cap on one category
            // would be two cards counting the same receipts and disagreeing about whether it was
            // over — see [budgetSpent], which keys on the category and nothing else.
            choices = remember(categories, budgets) {
                categoryChoices(categories, direction = "out")
                    .filter { it.id != CAT_TRANSFER && budgets.none { b -> b.goal.categoryId == it.id } }
            },
            onSave = { category, period, cap ->
                addingBudget = false
                onAddBudget(category, period, cap)
            },
            onDismiss = { addingBudget = false },
        )
    }

    budgets.firstOrNull { it.goal.id == editingBudget }?.let { budget ->
        BudgetSheet(
            choices = emptyList(),
            onSave = { _, _, _ -> },
            onDismiss = { editingBudget = null },
            editing = budget,
            onUpdate = { period, cap ->
                editingBudget = null
                onEditBudget(budget.goal.id, period, cap)
            },
            onDelete = {
                editingBudget = null
                onDelete(budget.goal.id)
            },
        )
    }

    if (addingGoal) {
        GoalSheet(
            onSave = { name, target, horizon ->
                addingGoal = false
                onAddGoal(name, target, horizon)
            },
            onDismiss = { addingGoal = false },
        )
    }

    goals.firstOrNull { it.goal.id == editingGoal }?.let { progress ->
        GoalSheet(
            onSave = { _, _, _ -> },
            onDismiss = { editingGoal = null },
            editing = progress,
            onUpdate = { name, target, horizon ->
                editingGoal = null
                onEditGoal(progress.goal.id, name, target, horizon)
            },
            onDelete = {
                editingGoal = null
                onDelete(progress.goal.id)
            },
        )
    }
}

/**
 * The band title, in the same voice the asset sections use — type and air do the separating, no
 * rule and no colour, with the 4dp optical inset that keeps the label from hanging outside a
 * 28dp corner. One size and weight for all three sections, so the page reads as one document.
 */
@Composable
private fun SectionLabel(text: String, top: Dp = Space.xxl) {
    Text(
        text,
        fontSize = 15.sp,
        fontWeight = FontWeight.ExtraBold,
        color = MaterialTheme.colorScheme.onBackground,
        modifier = Modifier
            .padding(top = top, bottom = Space.m, start = Space.xs)
            .semantics { heading() },
    )
}

/**
 * One row of a section's band: the group shape on the outer rows, square in between, a hairline
 * under everything but the last — the assets list's own construction, so «a band is one object»
 * means the same thing on every tab.
 *
 * [onOpen] is the whole card: the edit sheet is where every figure on it can be changed, and a
 * row-sized target needs no second button to be findable. The corner's «ویرایش» is the label on
 * that target, not a target of its own.
 */
@Composable
private fun BandCard(
    shape: Shape,
    divided: Boolean,
    modifier: Modifier = Modifier,
    onOpen: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val open = onOpen
    Box(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .then(
                if (open != null) {
                    Modifier.clickable(role = Role.Button, onClickLabel = "ویرایش", onClick = open)
                } else {
                    Modifier
                },
            ),
    ) {
        Column(Modifier.padding(Space.l), content = content)
        // Inset to where the text starts, and only between rows — a rule under the last one
        // would draw the band's own bottom edge twice.
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
 * The way into each sheet: a quiet well with a «+» in it, not a filled button.
 *
 * There are two of these on one screen and neither is the answer to anything — a filled `primary`
 * slab is how this app says «press this», and two of them stacked would each be claiming it. A well
 * in the surface colour with the mark and the label in `primary` reads as a place to add one, which
 * is exactly what it is, and leaves the loud shape to the ذخیره inside each sheet.
 *
 * The shape is the band's: last row when the section has cards, the whole band when it does not.
 * A section and the way to grow it are one object either way.
 */
@Composable
private fun AddRow(label: String, shape: Shape, onClick: () -> Unit) {
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
        Text(
            "+",
            style = figureStyle(MaterialTheme.colorScheme.primary, FontWeight.ExtraBold),
            fontSize = 20.sp,
        )
        Spacer(Modifier.width(Space.s))
        Text(
            label,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

/**
 * Notifications are off and the screen has something it would otherwise have said out loud — the
 * one case where a screen has to admit it cannot do the thing it promised.
 *
 * Said once, with the fix on it, and never again once it is granted. Not an error colour: nothing
 * is broken and nothing about her money is wrong, the app has simply been told to stay quiet.
 *
 * The card is shared and the sentence is not, because the permission is one switch and the two
 * things behind it are not the same loss: a budget she cannot be warned about and a receipt she
 * will be filing from memory next week. Whichever screen she is standing on names its own.
 */
@Composable
internal fun NotifyBlockedCard(what: String, onAsk: () -> Unit) {
    BandCard(shape = RoundedCornerShape(Radius.group), divided = false) {
        Text(
            "اعلان‌ها خاموشن",
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(Space.xs))
        Text(
            what,
            fontSize = 13.sp,
            lineHeight = 22.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(Space.s))
        Box(
            Modifier
                .clip(RoundedCornerShape(Radius.pill))
                .background(MaterialTheme.colorScheme.primary)
                .clickable(role = Role.Button, onClick = onAsk)
                .heightIn(min = 48.dp)
                .padding(horizontal = Space.l),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "روشن کردن اعلان",
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimary,
            )
        }
    }
}

// ─────────────────────────── one budget ───────────────────────────

/**
 * One cap, and everything she can act on about it.
 *
 * The order is the order the question is asked in: which category, how much of it is gone, how much
 * is left against how long, and then — only when there is something to say — one sentence. The
 * figure leads with what was **spent**, not with what is left, because «۳۲ از ۵۰ میلیون» is the
 * shape every other figure in this app takes and because what is left is the thing the sentence
 * under the bar is for.
 *
 * The bar cannot overflow, which is why [BudgetProgress.overRial] exists: a bar drawn past its own
 * track is a bug on every other screen, so being over is said in words and in colour instead. The
 * percent beside it is *not* clamped — «۱۳۰٪» is exactly the number she needs.
 */
@Composable
private fun BudgetCard(budget: BudgetProgress, shape: Shape, divided: Boolean, onOpen: () -> Unit) {
    val hue = categoryHue(budget.categoryFa)
    // The one authored moment on the card: a receipt she has just refiled moves the bar rather
    // than replacing it, which is what makes the two screens read as one ledger. Both channels
    // animate together and everything tinted reads the animated value — a percentage that snaps
    // while the bar under it slides is two clocks on one card.
    val share by animateFloatAsState(budget.share, tween(Motion.medium, easing = Motion.enter), label = "cap")
    val tone by animateColorAsState(
        budgetTone(budget.level),
        tween(Motion.medium, easing = Motion.enter),
        label = "capTone",
    )
    val note = budgetNoteFa(budget)

    BandCard(
        shape = shape,
        divided = divided,
        onOpen = onOpen,
        modifier = Modifier.semantics(mergeDescendants = true) {
            contentDescription = "${budget.categoryFa}، بودجهٔ ${budget.period.everyFa}: " +
                "${faCompact(tomanOf(budget.spentRial))} از ${faCompact(tomanOf(budget.capRial))} تومان، " +
                "${faNumber(budget.percent.toDouble())} درصد. " +
                budgetDaysLeftFa(budget) + ". " + note
        },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // The category's own mark on its own disc, exactly as the picker draws it — this card
            // is what that tap produced, and the mark is what says so without repeating the name.
            Box(
                Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(hue.copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center,
            ) {
                CategoryIcon(budget.categoryFa, hue, size = 20.dp)
            }
            Spacer(Modifier.width(Space.m))
            Column(Modifier.weight(1f)) {
                Text(
                    budget.categoryFa,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    // «ماهانه • مرداد» — the shape of the budget and the window it is measuring.
                    // Both, because «ماهانه» alone does not say which month and «مرداد» alone does
                    // not say it comes back next month.
                    "${budget.period.everyFa} • ${budget.window.fa}",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            EditHint()
        }

        Spacer(Modifier.height(Space.m))
        Row(verticalAlignment = Alignment.Bottom) {
            // Steps down rather than wrapping, the same way both halves of the month band do.
            // «۲٫۹ میلیون از ۱ میلیون» broken over two lines puts the cap on a line of its own with
            // the percentage beside it, and the pair stops reading as one comparison — which is the
            // only thing this line is for.
            BasicText(
                text = bidi("${faCompact(tomanOf(budget.spentRial))} از ${faCompact(tomanOf(budget.capRial))}"),
                modifier = Modifier.weight(1f),
                maxLines = 1,
                autoSize = TextAutoSize.StepBased(minFontSize = 14.sp, maxFontSize = 20.sp),
                style = figureStyle(MaterialTheme.colorScheme.onSurface, FontWeight.ExtraBold),
            )
            Spacer(Modifier.width(Space.s))
            Text(
                "${faNumber(budget.percent.toDouble())}٪",
                style = figureStyle(tone, FontWeight.ExtraBold),
                fontSize = 16.sp,
            )
        }

        Spacer(Modifier.height(Space.s))
        Box(
            Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(MaterialTheme.colorScheme.surface),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(share)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(4.dp))
                    .background(tone),
            )
        }

        Spacer(Modifier.height(Space.s))
        Text(
            budgetDaysLeftFa(budget),
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (note.isNotEmpty()) {
            Spacer(Modifier.height(Space.xs))
            Text(
                note,
                fontSize = 13.sp,
                lineHeight = 22.sp,
                fontWeight = if (budget.over) FontWeight.Bold else FontWeight.Normal,
                color = if (budget.over) tone else MaterialTheme.colorScheme.onSurface,
            )
        }
        if (budget.partWindow) {
            // The one thing about a budget that could look like a mistake. The window is the whole
            // month either way — anything else would disagree with دخل و خرج — so a budget set on
            // the 20th opens at whatever the first nineteen days cost, and she is owed the sentence
            // that says so rather than left to work out why it started at 60%.
            Spacer(Modifier.height(Space.xs))
            Text(
                "از اول ${budget.window.fa} حساب شده، نه از روزی که بودجه رو گذاشتی.",
                fontSize = 12.sp,
                lineHeight = 20.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ─────────────────────────── one goal ───────────────────────────

@Composable
private fun GoalCard(progress: GoalProgress, shape: Shape, divided: Boolean, onOpen: () -> Unit) {
    val met = Color(0xFF2E9E5B)
    val tone = if (progress.done) met else MaterialTheme.colorScheme.primary
    BandCard(shape = shape, divided = divided, onOpen = onOpen) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                progress.goal.nameFa,
                Modifier.weight(1f),
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            EditHint()
        }
        Spacer(Modifier.height(Space.s))
        // Steps down rather than wrapping, as the budget figure does: «۱٫۲ میلیارد از ۳۲ میلیارد»
        // split across two lines reads as two amounts rather than as one out of the other.
        BasicText(
            text = bidi("${faCompact(tomanOf(progress.currentRial))} از ${faCompact(tomanOf(progress.targetRial))}"),
            maxLines = 1,
            autoSize = TextAutoSize.StepBased(minFontSize = 14.sp, maxFontSize = 20.sp),
            style = figureStyle(MaterialTheme.colorScheme.onSurface, FontWeight.ExtraBold),
        )
        Spacer(Modifier.height(Space.s))
        Box(
            Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(MaterialTheme.colorScheme.surface),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(progress.share)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(4.dp))
                    .background(tone),
            )
        }
        // What progress is counted from, always. A goal set on the 28th opens at whatever the month
        // had already netted, which is the behaviour she wants — this month's saving counts — and is
        // indistinguishable from the app inventing a number unless it says which day it started.
        Spacer(Modifier.height(Space.s))
        Text(
            goalWindowFa(progress),
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        goalNoteFa(progress)?.let { (text, loud) ->
            Spacer(Modifier.height(Space.xs))
            Text(
                text,
                fontSize = 13.sp,
                lineHeight = 22.sp,
                fontWeight = if (loud) FontWeight.Bold else FontWeight.Normal,
                color = if (loud) tone else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** «از ۱ مرداد ۱۴۰۵», and the deadline where there is one. */
private fun goalWindowFa(progress: GoalProgress): String {
    val from = "از ${faDate(progress.goal.startsOn)}"
    val until = progress.goal.endsOn?.let { " تا ${faDate(it)}" }.orEmpty()
    return from + until
}

/**
 * The one line a goal card is allowed, and whether it is said out loud.
 *
 * Ordered by what is true rather than by what is encouraging. Reaching it is stated once, with the
 * figure that earned it, and nothing animates. Under water is a fact and not a verdict — «you
 * failed» appears nowhere in this app — and the monthly rate is the only line here that asks her
 * to do something, which is why it is the one that gets to be about the future.
 */
private fun goalNoteFa(progress: GoalProgress): Pair<String, Boolean>? = when {
    progress.done -> "به هدفت رسیدی." to true
    progress.expired ->
        // Not «نرسیدی». What happened is that the date passed with something still to save, and
        // the useful half of that sentence is the figure, not the verdict.
        "مهلتش تموم شد و ${faCompact(tomanOf(progress.remainingRial))} تومان مونده بود." to false
    progress.underWater ->
        "از وقتی این هدف رو گذاشتی، هنوز چیزی پس‌انداز نشده." to false
    progress.perMonthRial != null ->
        "برای رسیدن به هدف، ماهی ${faCompact(tomanOf(progress.perMonthRial))} تومان لازمه." to false
    progress.daysLeft != null && progress.daysLeft > 0 ->
        "${faNumber(progress.daysLeft.toDouble())} روز مونده و " +
            "${faCompact(tomanOf(progress.remainingRial))} تومان باقیه." to false
    else -> null
}

/**
 * «ویرایش», the same size and place on every card — the word for what tapping the card does.
 *
 * Not a target of its own: the card already is one, row-sized, and a second focusable inside it
 * would be the same action announced twice. It replaced a «حذف» that sat here, one stray tap
 * from a list she scrolls; delete now lives inside the sheet this word leads to, behind the
 * same two-tap confirm the asset rows use.
 */
@Composable
private fun EditHint() {
    Text(
        "ویرایش",
        fontSize = 12.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = Space.xs, horizontal = Space.s),
    )
}

// ─────────────────────────── making one ───────────────────────────

/**
 * A budget, in three answers: which category, how long, how much.
 *
 * In that order on purpose. The category is the only one of the three with no sensible default, so
 * it is asked first and the rest of the sheet stays out of the way until it is answered — and the
 * amount is asked last because a cap is a figure she picks *for* something, and typing it before
 * choosing the something is how she ends up budgeting the wrong category.
 *
 * There is no name field. A budget's name is its category, and asking for one again would be asking
 * her to type «رستوران و کافه» underneath the tile she just tapped.
 *
 * Editing is the same sheet with the first answer already given: the category is the budget's
 * identity, so it is shown rather than asked, and the period and the cap arrive filled in with
 * what she keeps now. Delete lives here too, behind the asset sheet's own two-tap confirm — the
 * rare destructive verb belongs inside the room, not on the door.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BudgetSheet(
    choices: List<Category>,
    onSave: (Category, BudgetPeriod, Long) -> Unit,
    onDismiss: () -> Unit,
    editing: BudgetProgress? = null,
    onUpdate: (BudgetPeriod, Long) -> Unit = { _, _ -> },
    onDelete: () -> Unit = {},
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    fun close(then: () -> Unit) = scope.launch { sheetState.hide(); then() }

    var picked by remember { mutableStateOf<Category?>(null) }
    // A month, because that is what her salary, her rent and every bill she pays already run on.
    var period by remember { mutableStateOf(editing?.period ?: BudgetPeriod.MONTH) }
    // Whole Toman, exactly what she typed to make it: a cap is stored in Rial but was never
    // entered in Rial, and a field pre-filled with ten times the number she knows is a typo
    // she has to notice before she can change anything else.
    var amount by remember { mutableStateOf(editing?.let { (it.capRial / 10).toString() } ?: "") }
    val capRial = remember(amount) { tomanFieldToRial(amount) }

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
            Text(
                if (editing != null) "ویرایش بودجه" else "بودجهٔ تازه",
                fontSize = 24.sp,
                fontWeight = FontWeight.ExtraBold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.semantics { heading() },
            )

            if (editing == null && choices.isEmpty()) {
                // Every category already has one, which is a real state and not an error.
                Text(
                    "برای همهٔ دسته‌ها بودجه گذاشتی. برای تغییر یکی، بازش کن و ویرایشش کن.",
                    fontSize = 13.sp,
                    lineHeight = 22.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = Space.m),
                )
                Spacer(Modifier.height(Space.l))
                return@Column
            }

            if (editing != null) {
                // Which budget this sheet is open on, said the way its own card says it: the
                // category's mark on its disc, then the name. Not a field — a cap on a different
                // category is a different budget, and the sentence under it says the way there.
                val hue = categoryHue(editing.categoryFa)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = Space.l),
                ) {
                    Box(
                        Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(hue.copy(alpha = 0.18f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        CategoryIcon(editing.categoryFa, hue, size = 20.dp)
                    }
                    Spacer(Modifier.width(Space.m))
                    Text(
                        editing.categoryFa,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                Text(
                    "دسته‌اش عوض نمی‌شه — برای دستهٔ دیگه، بودجهٔ تازه بساز.",
                    fontSize = 12.sp,
                    lineHeight = 20.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = Space.s, start = Space.xs),
                )
            } else {
                SheetLabel("برای کدوم دسته؟")
                CategoryGrid(
                    choices = choices,
                    selectedId = picked?.id,
                    onPick = { picked = it },
                    selectedLabel = "انتخاب‌شده",
                )
            }

            SheetLabel("هر چه مدت؟")
            SegmentedChoice(
                options = BudgetPeriod.entries.toList(),
                selected = period,
                label = { it.everyFa },
                onSelect = { period = it },
            )

            SheetLabel("سقف خرج، به تومان")
            OutlinedTextField(
                value = amount,
                onValueChange = { amount = it },
                label = { Text("مثلاً ۵ میلیون") },
                singleLine = true,
                visualTransformation = GroupedNumber,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                supportingText = when {
                    amount.isNotBlank() && capRial == null -> ({
                        Text("این عدد قابل خوندن نیست. فقط عدد وارد کن.")
                    })
                    // Spelled out, for the same reason every other amount field in the app spells
                    // it out: digits are quick to scan and easy to misread by a factor of ten.
                    capRial != null -> ({
                        Text(faWordsToman(tomanOf(capRial)).orEmpty())
                    })
                    else -> null
                },
                shape = RoundedCornerShape(Radius.field),
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = "سقف خرج به تومان" },
            )

            Spacer(Modifier.height(Space.l))
            Button(
                onClick = {
                    val cap = capRial ?: return@Button
                    if (editing != null) {
                        close { onUpdate(period, cap) }
                    } else {
                        val category = picked ?: return@Button
                        close { onSave(category, period, cap) }
                    }
                },
                enabled = (editing != null || picked != null) && capRial != null,
                shape = RoundedCornerShape(Radius.pill),
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            ) {
                Text(
                    if (editing != null) "ذخیره تغییرات" else "ذخیره بودجه",
                    fontWeight = FontWeight.Bold,
                )
            }

            if (editing != null) SheetDelete("حذف این بودجه") { close(onDelete) }
        }
    }
}

/**
 * Delete, inside the sheet and nowhere else — the asset sheet's own device, verbatim: one stray
 * tap must not erase a decision, so the first tap only changes the label to a question.
 */
@Composable
internal fun SheetDelete(label: String, onConfirmed: () -> Unit) {
    var confirm by remember { mutableStateOf(false) }
    TextButton(
        onClick = { if (confirm) onConfirmed() else confirm = true },
        colors = ButtonDefaults.textButtonColors(
            contentColor = MaterialTheme.colorScheme.error,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = Space.xs),
    ) {
        Text(
            if (confirm) "مطمئنی؟ برای حذف دوباره بزن" else label,
            fontSize = 15.sp,
            fontWeight = if (confirm) FontWeight.Bold else FontWeight.Normal,
            // Announced, or the two-tap safeguard is invisible to TalkBack — a second
            // double-tap deletes with no confirmation ever perceived.
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
}

/**
 * A goal, in three answers: what for, how much, and by when.
 *
 * «تا کِی» is four pills rather than a date picker, and it is the field that turned this card from
 * a progress bar into a plan — [GoalProgress.perMonthRial] cannot exist without a deadline. A
 * Jalali date picker would be a screen, a keyboard and four ways to pick a date in the past, for an
 * answer that is «۶ ماه» nine times out of ten. «بی‌مهلت» is there because it is a real answer.
 *
 * Editing arrives with her answers filled in, and «تا کِی؟» filled with **nothing**: the pills
 * measure from today, so re-selecting «۶ ماه» on a four-month-old goal would quietly move its
 * deadline, and a control that changes the answer by restating it cannot be pre-selected. Left
 * alone, the deadline she has stands and the line under the pills keeps saying it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GoalSheet(
    onSave: (String, Long, GoalHorizon) -> Unit,
    onDismiss: () -> Unit,
    editing: GoalProgress? = null,
    /** The horizon is null when she left «تا کِی؟» alone and the deadline she has stands. */
    onUpdate: (String, Long, GoalHorizon?) -> Unit = { _, _, _ -> },
    onDelete: () -> Unit = {},
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    fun close(then: () -> Unit) = scope.launch { sheetState.hide(); then() }

    var name by remember { mutableStateOf(editing?.goal?.nameFa ?: "") }
    // Whole Toman, as in [BudgetSheet]: stored in Rial, never typed in it.
    var amount by remember { mutableStateOf(editing?.let { (it.targetRial / 10).toString() } ?: "") }
    var horizon by remember { mutableStateOf(if (editing == null) GoalHorizon.HALF else null) }
    val targetRial = remember(amount) { tomanFieldToRial(amount) }

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
            Text(
                if (editing != null) "ویرایش هدف" else "هدف تازه",
                fontSize = 24.sp,
                fontWeight = FontWeight.ExtraBold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.semantics { heading() },
            )

            SheetLabel("هدفت چیه؟")
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(40) },
                label = { Text("مثلاً سفر، یا پیش‌پرداخت خونه") },
                singleLine = true,
                shape = RoundedCornerShape(Radius.field),
                modifier = Modifier.fillMaxWidth(),
            )

            SheetLabel("چقدر، به تومان")
            OutlinedTextField(
                value = amount,
                onValueChange = { amount = it },
                label = { Text("مبلغ هدف") },
                singleLine = true,
                visualTransformation = GroupedNumber,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                supportingText = when {
                    amount.isNotBlank() && targetRial == null -> ({
                        Text("این عدد قابل خوندن نیست. فقط عدد وارد کن.")
                    })
                    targetRial != null -> ({
                        Text(faWordsToman(tomanOf(targetRial)).orEmpty())
                    })
                    else -> null
                },
                shape = RoundedCornerShape(Radius.field),
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = "مبلغ هدف به تومان" },
            )

            SheetLabel("تا کِی؟")
            SegmentedChoice(
                options = GoalHorizon.entries.toList(),
                selected = horizon,
                label = { it?.fa.orEmpty() },
                onSelect = { horizon = it },
                fontSize = 14.sp,
            )
            // The deadline written out, so «۶ ماه» is never a length she has to convert into a
            // date in her head — and so the answer she is about to store is on screen before she
            // stores it. The end of the month, not today plus ninety days: [jalaliMonthsAheadEnd].
            // While editing with the pills untouched, this is the deadline she already has.
            val chosen = horizon
            val deadline =
                if (chosen != null) chosen.endsOn(tehranDay(System.currentTimeMillis()))
                else editing?.goal?.endsOn
            Text(
                if (deadline == null) "بدون مهلت، هر وقت رسید." else "مهلت: ${faDate(deadline)}",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Space.s, start = Space.xs),
            )

            Spacer(Modifier.height(Space.l))
            Button(
                onClick = {
                    val target = targetRial ?: return@Button
                    if (name.isBlank()) return@Button
                    if (editing != null) {
                        close { onUpdate(name.trim(), target, horizon) }
                    } else {
                        close { onSave(name.trim(), target, horizon ?: GoalHorizon.HALF) }
                    }
                },
                enabled = name.isNotBlank() && targetRial != null,
                shape = RoundedCornerShape(Radius.pill),
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            ) {
                Text(
                    if (editing != null) "ذخیره تغییرات" else "ذخیره هدف",
                    fontWeight = FontWeight.Bold,
                )
            }

            if (editing != null) SheetDelete("حذف این هدف") { close(onDelete) }
        }
    }
}

/**
 * What she typed, as whole Rial — or null when it is not a figure this app will store.
 *
 * The ceiling is not decoration. `parseAmount` returns a Double, and a twenty-digit run of digits
 * multiplied by ten saturates `Long` on the way in; [MAX_PLAUSIBLE_RIAL] is the same bound the
 * parser applies to a bank message, and a cap or a target beyond it is a typo rather than money.
 */
internal fun tomanFieldToRial(text: String): Long? =
    parseAmount(text)
        ?.takeIf { it > 0.0 && it <= MAX_PLAUSIBLE_RIAL / 10.0 }
        ?.let { (it * 10.0).roundToLong() }

// ─────────────────────────── «آیا ارزشش را داشت؟» ───────────────────────────

@Composable
private fun WorthItSummaryCard(summary: WorthItSummary) {
    BandCard(shape = RoundedCornerShape(Radius.group), divided = false) {
        WorthItRow("ارزش داشت", summary.worth, Color(0xFF2E9E5B))
        WorthItRow("لازم بود", summary.needed, MaterialTheme.colorScheme.onSurfaceVariant)
        WorthItRow("ارزش نداشت", summary.regretted, MaterialTheme.colorScheme.primary)
        if (summary.regretted > 0) {
            Spacer(Modifier.height(Space.s))
            Text(
                // The whole reason for asking: "spend less" is advice nobody can act on.
                "این خرج‌ها رو می‌شه کم کرد، بدون اینکه چیزی از دست بدی.",
                fontSize = 13.sp,
                lineHeight = 22.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun WorthItRow(label: String, rial: Long, tone: Color) {
    Row(Modifier.fillMaxWidth().padding(vertical = Space.xs), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurface)
        Text(
            bidi(faCompact(tomanOf(rial))),
            style = figureStyle(tone, FontWeight.Bold),
            fontSize = 15.sp,
        )
    }
}

/**
 * The weekly question, asked about at most two large discretionary purchases.
 *
 * Never about rent, bills, fees or cash — "was it worth it" is not a question about the
 * electricity bill, and asking it would read as the app being smug.
 */
@Composable
fun WorthItCard(entry: LedgerEntry, onAnswer: (String) -> Unit, modifier: Modifier = Modifier) {
    Panel(modifier) {
        Text(
            faDay(entry.txn.day),
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(Space.xs))
        Text(
            entry.txn.merchant.ifBlank { entry.categoryFa },
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        entry.txn.amountRial?.let {
            Spacer(Modifier.height(Space.xs))
            Text(
                bidi(faCompact(tomanOf(it)) + " تومان"),
                style = figureStyle(MaterialTheme.colorScheme.onSurface, FontWeight.ExtraBold),
                fontSize = 24.sp,
            )
        }
        Spacer(Modifier.height(Space.l))
        Text(
            "ارزش داشت؟",
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(Space.s))
        Row(Modifier.fillMaxWidth()) {
            Answer("آره", Modifier.weight(1f)) { onAnswer(WorthIt.YES) }
            Answer("لازم بود", Modifier.weight(1f)) { onAnswer(WorthIt.NEEDED) }
            Answer("نه", Modifier.weight(1f)) { onAnswer(WorthIt.NO) }
        }
    }
}

@Composable
private fun Answer(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Text(
        label,
        textAlign = TextAlign.Center,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = modifier
            .padding(horizontal = Space.xs)
            .clip(RoundedCornerShape(Radius.pill))
            .background(MaterialTheme.colorScheme.surface)
            .clickable(role = Role.Button, onClick = onClick)
            // 44dp minimum: this is answered one-handed or it is not answered.
            .height(48.dp)
            .padding(vertical = Space.m),
    )
}
