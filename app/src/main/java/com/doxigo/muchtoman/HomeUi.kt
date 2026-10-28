package com.doxigo.muchtoman

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.inset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.math.abs

/**
 * The top of خانه: her name and the door to تنظیمات, the deep-green hero with the total on it, and
 * the round actions under it.
 *
 * Everything in this file is about one number — how much, in dollars too, how fresh, whose, and
 * how it has moved in a month — and the controls beside it are the ones that act on that number.
 * The green panel itself is borrowed by the pages that open from here, so they arrive in the same
 * object. What the home screen says under the number lives in `Home.kt`.
 */

/**
 * The reason the app exists: the one deep-green object in the app, with the total set in
 * bright green across it. A card floating on the paper, all four corners round — the ground
 * around it is what gives it its weight, and everything below defers to it.
 */
@Composable
internal fun HeroPanel(
    modifier: Modifier = Modifier,
    /** Only دارایی's compact header passes a smaller one — see [HeroCard]'s `compact`. */
    vertical: Dp = Space.xl,
    /**
     * [HeroCard] ends on [FreshnessLine], whose 48dp touch target centres a 13sp line and so
     * already carries ~13dp of air under the words; the full [vertical] on top of that left
     * the card bottom-heavy.
     */
    bottom: Dp = vertical,
    content: @Composable ColumnScope.() -> Unit,
) {
    // On the dark theme the card is a neutral elevated surface a shade off the page, so the
    // hairline is what holds its edge — the forest card on white needs none.
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val shape = RoundedCornerShape(Radius.hero)
    Box(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Hero.field)
            .then(
                if (dark) Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
                else Modifier,
            ),
    ) {
        Column(
            Modifier.padding(start = Space.xl, end = Space.xl, top = vertical, bottom = bottom),
            content = content,
        )
    }
}

/**
 * Her, and the door to تنظیمات — one object at the top of the page instead of two.
 *
 * تنظیمات is not on the tab bar and should not be: [Tab] is five money questions she asks daily,
 * تنظیمات is a room she visits twice a year, and a slot on that bar is rent paid every day. The
 * greeting is the door — the disc, the name and the chevron are one target, the way every phone
 * on the planet gets you to your own account.
 *
 * In the disc, a drawn mark and not her initial: [CompanionGlyph] is the app's one drawing of a
 * person, in the same pen every category mark is drawn with. Only the label carries the state —
 * with a name it greets her; without one it reads «تنظیمات», because on a first run the app's
 * own name at the top of its own screen tells her nothing, while the empty state below is at
 * that very moment telling her to go «از تنظیمات» and turn the پیامک‌ها on.
 */
@Composable
internal fun HomeTopBar(name: String, onSettings: () -> Unit, modifier: Modifier = Modifier) {
    val her = name.trim()
    Row(
        modifier
            .clip(RoundedCornerShape(Radius.pill))
            .clickable(role = Role.Button, onClick = onSettings)
            .heightIn(min = 48.dp)
            .padding(end = Space.s)
            .semantics(mergeDescendants = true) {
                contentDescription = if (her.isNotBlank()) "$her، تنظیمات" else "تنظیمات"
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            CompanionGlyph(MaterialTheme.colorScheme.onSurface, size = 24.dp)
        }
        Spacer(Modifier.width(Space.m))
        Text(
            if (her.isNotBlank()) "سلام، $her" else "تنظیمات",
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            // `fill = false` so a short name keeps the chevron against the word rather than
            // pushed to the far edge: the arrow belongs to the door, not to the page.
            modifier = Modifier.weight(1f, fill = false),
        )
        Icon(
            Icons.AutoMirrored.Rounded.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
internal fun HeroCard(
    state: UiState,
    usdRate: Double?,
    /**
     * Which report, not just «the report». Every affordance on this card is a door to one of
     * the two, and the one it names is the one it has to open: a change pill that lands on
     * دخل و خرج is the card asking a question and the report answering a different one.
     */
    onReport: (ReportMode) -> Unit,
    /** Which screen this card is heading — it decides the door shown when there is no pill. */
    portfolio: Boolean,
    onFamilyTotal: (Boolean) -> Unit,
    /** The طلب و بدهی page, from the strip beside the total. */
    onLoans: () -> Unit = {},
    /**
     * دارایی's header rather than خانه's answer: the figure, its dollars, whose money and how fresh
     * — a third of the height, so the list the tab exists for starts above the fold. The words,
     * the exact digits, the loans and the change pill stay on خانه's card.
     */
    compact: Boolean = false,
    /** The freshness line under the total is the refresh control — see [FreshnessLine]. */
    onRefresh: () -> Unit,
) {
    // What the family shares in is on this screen but never in [UiState.totals]: her figure
    // stays hers everywhere else — the widget, the daily snapshot, the report. The household
    // total is a reading of the card, not a second total in the state.
    val familyMode = state.familyTotal && state.familyAssets.isNotEmpty()
    val total = state.totals.toman +
        (if (familyMode) state.familyAssets.sumOf { it.totalToman } else 0.0)

    // The same money in the one other unit everyone here already thinks in, off the *effective*
    // dollar rate so a hand-typed override moves this figure with everything else. [usdOf] is
    // what decides it is absent rather than zero when there is no rate — the report's own
    // dollar asides come through the same guard.
    val usd = usdOf(total, usdRate)

    // A month is the shortest window the daily snapshots can answer honestly, and the one she
    // is most likely to be asking about. Absent — not zeroed — until there is a snapshot that
    // old to compare against. Always against her own total: the history only ever recorded
    // hers, so on the household figure the pill has nothing honest to say and stays away.
    val change = remember(state.history, state.totals.toman) {
        changeOver(state.history, System.currentTimeMillis() / DAY_MS, 30, state.totals.toman)
    }.takeUnless { familyMode }

    HeroPanel(vertical = if (compact) Space.l else Space.xl, bottom = if (compact) Space.s else Space.m) {
        if (compact) {
            // Whose, how much, how fresh — the full card's order with its middle taken out. No
            // «جمع دارایی‌هات» label: under the دارایی tab, above its own breakdown, the figure
            // names itself, and a line of its own is the height this header exists to give back.
            if (state.familyAssets.isNotEmpty()) {
                HeroScopeToggle(
                    family = familyMode,
                    onSelect = onFamilyTotal,
                    modifier = Modifier.padding(bottom = Space.xs),
                )
            }
            // The figure has its line to itself and the dollars sit under it, as the exact digits do
            // on خانه's card. Beside it they took the width the figure shrinks into — 24sp on a
            // small phone — and sat on the headline's line as a second headline.
            HeroTotal(total, min = 20.sp, max = 34.sp)
            usd?.let { HeroUsd(it, Modifier.padding(top = 2.dp)) }
        } else {
            // Label and dollar figure share a line, so the top of the card reads as one
            // sentence — "جمع دارایی‌هات $۵۱٬۵۰۰" — and the dollars stay an aside rather
            // than a second headline.
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (state.familyAssets.isEmpty()) {
                    Text(
                        "جمع دارایی‌هات",
                        fontSize = 15.sp,
                        color = Hero.muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    // Once the family shares in, the label becomes the question it always
                    // quietly answered: whose money is this figure. Same slot, same line.
                    HeroScopeToggle(
                        family = familyMode,
                        onSelect = onFamilyTotal,
                        modifier = Modifier.weight(1f),
                    )
                }
                usd?.let { HeroUsd(it, Modifier.padding(start = Space.s)) }
            }

            Spacer(Modifier.height(Space.xs))
            HeroTotal(total, min = 28.sp, max = 60.sp)

            // Digits are quick to scan but easy to misread by a factor of ten. The words
            // are the check on that.
            faWordsToman(total)?.let { words ->
                Text(
                    words,
                    fontSize = 15.sp,
                    lineHeight = 25.sp,
                    color = Hero.strong.copy(alpha = 0.93f),
                    modifier = Modifier.padding(top = Space.s),
                )
            }
            Text(
                "${faNumber(shownOf(total))} $moneyUnit",
                fontSize = 13.sp,
                fontFamily = ModamFigures,
                color = Hero.muted,
                modifier = Modifier.padding(top = Space.xs),
            )

            when {
                change != null -> {
                    Spacer(Modifier.height(Space.m))
                    // A month's change in what she owns is the asset report's own opening
                    // figure, so that is the report it opens.
                    ChangePill(change, onClick = { onReport(ReportMode.ASSETS) })
                }
                // Before there are thirty days to compare against, the pill has nothing it can
                // honestly state. A door that only appears in the second month is a door nobody
                // finds, so the slot keeps its target and drops the figure.
                // It says «گزارش دارایی» on it, so that is where it goes.
                !portfolio -> ReportLink(onClick = { onReport(ReportMode.ASSETS) })
            }
            // Beside the answer and never in it: after the total's own story, the card's second
            // door. Hers alone, so not on the household's figure, where it would read as the family's.
            if (!familyMode && !state.loanTotals.isEmpty) HeroLoans(state.loanTotals, onLoans)

            Spacer(Modifier.height(Space.l))
            HorizontalDivider(color = Hero.hairline)
        }
        // Both readings of the card, compact or not: a header a third of the height still says
        // every caution the full card says, in the same words.
        FreshnessLine(state, onRefresh)
    }
}

/**
 * The headline figure. Must never wrap: "۳٫۲ میلیارد تومان" broken across two lines reads as a
 * layout bug, and the figure can grow by orders of magnitude. Shrink to fit instead. Three
 * decimals here, one everywhere else: the headline is the one figure she watches move, so
 * "۹٫۶۴۳ میلیارد" beats a ۹٫۶ that hides a day's change.
 *
 * Every refresh lifts the new figure into place from below. It is the visible receipt that the
 * fetch did something — and the reason the digits are tabular: without that, one changed digit
 * slides the whole number sideways as it lands.
 */
@Composable
private fun HeroTotal(total: Double, min: TextUnit, max: TextUnit, modifier: Modifier = Modifier) {
    AnimatedContent(
        targetState = faCompact(total, 2, pad = true),
        modifier = modifier,
        transitionSpec = {
            (
                slideInVertically(tween(Motion.medium, easing = Motion.enter)) { it / 3 } +
                    fadeIn(tween(Motion.medium))
                ).togetherWith(
                slideOutVertically(tween(Motion.fast, easing = Motion.exit)) { -it / 3 } +
                    fadeOut(tween(Motion.fast))
            )
        },
        label = "total",
    ) { figure ->
        BasicText(
            text = heroFigure(figure, Hero.accent),
            maxLines = 1,
            autoSize = TextAutoSize.StepBased(minFontSize = min, maxFontSize = max),
            style = figureStyle(Hero.accent, FontWeight.Black),
        )
    }
}

/**
 * The total in dollars, an aside rather than a second headline: at the end of the label's line on
 * خانه's card, under the figure on دارایی's.
 */
@Composable
private fun HeroUsd(usd: Double, modifier: Modifier = Modifier) {
    Text(
        faUsd(usd),
        fontSize = 14.sp,
        fontWeight = FontWeight.SemiBold,
        fontFamily = ModamFigures,
        color = Hero.muted,
        maxLines = 1,
        // "$" is read out as punctuation or skipped entirely; the unit has to
        // survive for anyone listening rather than looking.
        modifier = modifier.semantics { contentDescription = "${faRate(usd)} دلار" },
    )
}

/**
 * How old the rates under the total are — and the way to make them younger. The line is the
 * refresh control: the sentence that tells her whether she needs one is the thing she presses,
 * instead of a third circle in the action row. Same reach as the pull on this list: rates, held
 * نمادها and wallet balances ([AppVm.refreshAll]).
 *
 * One composable owning the tap, so any header that shows the freshness shows the control too.
 */
@Composable
internal fun FreshnessLine(state: UiState, onRefresh: () -> Unit, modifier: Modifier = Modifier) {
    // "همین الان" must not still say that half an hour later. A slow tick keeps the label
    // honest; the minute granularity of faAgo means nothing finer would ever show anyway.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(state.rates.updatedAt) {
        while (true) {
            now = System.currentTimeMillis()
            delay(30_000)
        }
    }
    // A day-old rate silently shown as current is the "quietly wrong total" this app is
    // built to avoid. Words carry the warning, not colour alone.
    val stale = state.rates.updatedAt > 0L && now - state.rates.updatedAt > 24 * 60 * 60_000L
    val failed = state.error != null && state.rates.updatedAt == 0L
    // Anything worth a caution sentence on this card dims the dot too — cached rates under a
    // failed fetch are usable, but a bright green all-clear beside «متصل نشد» is the colour
    // contradicting the words.
    val trouble = failed || stale || state.error != null
    val busy = state.refreshing
    val line = when {
        // Work in flight says so in words — the one case DESIGN.md lets a control dim for.
        busy -> "در حال تازه کردن…"
        failed -> "نرخ‌ها به‌روز نشدن. اینترنتت رو چک کن"
        stale -> "نرخ‌ها قدیمی‌ان؛ " + faAgo(state.rates.updatedAt, now)
        else -> "نرخ‌ها: " + faAgo(state.rates.updatedAt, now)
    }
    val caution = "متصل نشد. نرخ‌های قبلی نشون داده می‌شن."
        .takeIf { state.error != null && state.rates.updatedAt > 0L }

    // Only while it actually spins: an infinite transition left running costs a frame callback
    // for ever, on the one screen that is open all day.
    val angle = if (busy) {
        rememberInfiniteTransition(label = "spin").animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart),
            label = "angle",
        ).value
    } else {
        0f
    }

    Column(
        modifier
            .fillMaxWidth()
            .alpha(if (busy) 0.38f else 1f)
            .clip(RoundedCornerShape(Radius.field))
            .clickable(enabled = !busy, role = Role.Button, onClickLabel = "تازه کردن", onClick = onRefresh)
            // The dot and the mark say nothing aloud; the words are the whole of it.
            .semantics { contentDescription = listOfNotNull(line, caution).joinToString(". ") }
            .heightIn(min = 48.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Colour confirms; the words carry it. A dot on its own would be the one thing in
            // this app that says "something is wrong" in hue alone.
            Box(
                Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(if (trouble) Hero.warn else Hero.mint),
            )
            Spacer(Modifier.width(Space.s))
            Text(
                line,
                fontSize = 13.sp,
                // Not the accent: on this card green is the answer, and a warning wearing it
                // read as a second call to act.
                color = if ((stale || failed) && !busy) Hero.warn else Hero.muted,
                modifier = Modifier.weight(1f, fill = false),
            )
            // Beside the words rather than across the card: the mark is what turns a caption
            // into a control, so it has to read as part of the sentence. Counter-clockwise, like
            // every other spinner in this app — the arrow is drawn leading anticlockwise.
            Box(Modifier.padding(start = Space.s).graphicsLayer { rotationZ = -angle }) {
                ActIcon(ActGlyph.REFRESH, Hero.muted, size = 16.dp, stroke = 1.5.dp)
            }
        }
        caution?.let {
            Text(
                it,
                fontSize = 12.sp,
                lineHeight = 19.sp,
                color = Hero.muted,
                modifier = Modifier.padding(top = Space.xs),
            )
        }
    }
}

/**
 * Whose money the headline counts: hers, or the household's — hers plus everything the family
 * shares in. It stands in the label's own slot and speaks in the field's colours: the active
 * chip on [Hero.well], the idle one bare, because up here the bright green is spoken for by
 * the figure itself. Small pills, not the full segmented track — the choice only slices the
 * number below it.
 */
@Composable
private fun HeroScopeToggle(
    family: Boolean,
    onSelect: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) = PillToggle(
    options = listOf(false, true),
    selected = family,
    label = { if (it) "خانواده" else "دارایی‌هات" },
    onSelect = onSelect,
    modifier = modifier,
    fill = Hero.well,
    ink = Hero.strong,
    idle = Hero.muted,
)

/**
 * The way into the report when the change pill has nothing to say yet.
 *
 * Same slot, same target, no figure — because inventing one before there are thirty days to
 * compare against is exactly the kind of confident wrong number this app exists to not show.
 */
@Composable
private fun ReportLink(onClick: () -> Unit) {
    Row(
        Modifier
            .clip(RoundedCornerShape(Radius.pill))
            .clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = 48.dp)
            .padding(end = Space.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Weighted like the pill's own tail, which is what it stands in for. At `strong` and
        // SemiBold it read as a heading — the loudest line under a total it is subordinate to.
        Text("گزارش دارایی", fontSize = 13.sp, color = Hero.muted)
        Icon(
            Icons.AutoMirrored.Rounded.KeyboardArrowRight,
            contentDescription = null,
            tint = Hero.muted,
            modifier = Modifier.size(18.dp),
        )
    }
}

/**
 * One circular action under the hero card. Wise's device, and the reason it works: the things
 * she might do next sit directly under the number that prompts them, all the same size and all
 * on the paper, so none of them is the app arguing for itself.
 */
@Composable
internal fun ActionCircle(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    content: @Composable (Color) -> Unit = {},
) {
    // The circle takes the press. A ripple inside a 56dp disc is most of the disc, so the
    // action reads as flashing rather than as being pushed; the give is what makes it feel
    // like a button under the thumb.
    val press = remember { MutableInteractionSource() }
    val pressed by press.collectIsPressedAsState()
    val give by animateFloatAsState(
        if (pressed) 0.94f else 1f,
        Motion.press(),
        label = "press",
    )
    val ink = MaterialTheme.colorScheme.onPrimaryContainer

    Column(
        modifier = modifier
            .scale(give)
            .clip(RoundedCornerShape(Radius.card))
            .clickable(
                role = Role.Button,
                interactionSource = press,
                indication = LocalIndication.current,
                onClick = onClick,
            )
            .padding(vertical = Space.s)
            .semantics { contentDescription = label },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = null, tint = ink, modifier = Modifier.size(24.dp))
            } else {
                content(ink)
            }
        }
        Spacer(Modifier.height(Space.s))
        Text(
            label,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * How the total has moved in a month, and the way into the report. Percent leads because it is
 * the figure that survives being compared to last month's; the Toman amount is one tap away.
 */
@Composable
private fun ChangePill(change: Change, onClick: () -> Unit) {
    val flat = abs(change.delta) < 1
    val gained = change.delta > 0
    val tone = when {
        flat -> Hero.muted
        gained -> Hero.mint
        else -> Hero.warn
    }
    val figure = when {
        flat -> "بدون تغییر"
        change.percent != null -> "${faDecimal(abs(change.percent), 1)}٪"
        else -> faCompact(abs(change.delta))
    }
    val tail = when {
        flat -> "در ۳۰ روز گذشته"
        gained -> "بیشتر از ۳۰ روز پیش"
        else -> "کمتر از ۳۰ روز پیش"
    }

    Row(
        Modifier
            .clip(RoundedCornerShape(Radius.pill))
            .clickable(role = Role.Button, onClick = onClick)
            // The pill is 32dp tall on purpose — it must not compete with the action circles
            // below it — so the row around it carries the touch target.
            .heightIn(min = 48.dp)
            .padding(end = Space.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier
                .clip(RoundedCornerShape(Radius.pill))
                .background(tone.copy(alpha = 0.18f))
                .padding(horizontal = Space.m, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (!flat) {
                TrendCaret(up = gained, tint = tone)
                Spacer(Modifier.width(6.dp))
            }
            Text(
                figure,
                fontSize = 14.sp,
                fontFamily = ModamFigures,
                fontWeight = FontWeight.Bold,
                color = tone,
            )
        }
        Spacer(Modifier.width(Space.s))
        Text(tail, fontSize = 13.sp, color = Hero.muted, maxLines = 1)
        // On خانه this is the only way into the report, so it has to look like a way in. A
        // percentage sitting on a green field reads as a statistic; the chevron is what makes
        // it a door.
        Icon(
            Icons.AutoMirrored.Rounded.KeyboardArrowRight,
            contentDescription = null,
            tint = Hero.muted,
            modifier = Modifier.size(18.dp),
        )
    }
}

/** Modam has no ▲ or ▼, and a drawn one is crisper at this size than any font would be anyway. */
@Composable
internal fun TrendCaret(up: Boolean, tint: Color, box: Dp = 9.dp) {
    Canvas(Modifier.size(box)) {
        val w = size.width
        val h = size.height
        val path = Path().apply {
            if (up) {
                moveTo(w / 2f, 0f); lineTo(w, h); lineTo(0f, h)
            } else {
                moveTo(0f, 0f); lineTo(w, 0f); lineTo(w / 2f, h)
            }
            close()
        }
        drawPath(path, tint)
    }
}

/**
 * Three rising bars for the report button — drawn, because the icon set material3 already
 * ships has no chart glyph, and one emoji in a row of monochrome icons reads as a sticker.
 */
@Composable
internal fun BarsIcon(tint: Color) {
    // 24dp box, 2dp inset: Material icons keep ~2dp of built-in padding, so drawing
    // edge-to-edge at 22dp made the bars read heavier than the gear beside them.
    Canvas(
        Modifier
            .size(24.dp)
            .semantics { contentDescription = "گزارش دارایی" },
    ) {
        inset(2.dp.toPx()) {
            val w = size.width * 0.2f
            val r = CornerRadius(w / 2f)
            drawRoundRect(tint, Offset(0f, size.height * 0.45f), Size(w, size.height * 0.55f), r)
            drawRoundRect(tint, Offset((size.width - w) / 2f, size.height * 0.2f), Size(w, size.height * 0.8f), r)
            drawRoundRect(tint, Offset(size.width - w, 0f), Size(w, size.height), r)
        }
    }
}
