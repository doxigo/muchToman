package com.doxigo.muchtoman

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.inset
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.decode.DataSource
import coil3.request.ImageRequest
import coil3.request.crossfade
import kotlinx.coroutines.delay

/**
 * دارایی on screen: a head per kind carrying that kind's own total, a row per holding, the band the
 * family shares in, and the two sheets that write to the list — the picker for a new kind and the
 * edit sheet for one holding.
 *
 * The row is the app's one anatomy for money she holds — a mark, a title that shrinks before it
 * truncates, and the Toman figure at the hero's three decimals — and the bank sheet borrows it, so
 * the same money reads the same wherever it shows.
 */

/**
 * A real logo where one exists, a drawn mark ([assetGlyph]) for the fixed assets, and the
 * ticker as a last resort. The letters sit underneath and are covered once the logo loads, so
 * a slow network shows an identifiable badge rather than a hole.
 *
 * [network] marks which chain the coin actually sits on. Tether on Tron and Tether on Ethereum
 * are the same logo and the same row, and until this the only thing telling them apart was a
 * 12sp line that a long address truncates.
 */
@Composable
private fun AssetIcon(type: AssetType, size: Dp = 44.dp, network: String? = null) {
    // Tinted by kind rather than one grey for all. It only ever shows behind the emoji and
    // letter fallbacks — a coin's own logo covers it — which is exactly where a list of
    // eleven identical grey discs was hardest to scan.
    val scheme = MaterialTheme.colorScheme
    val tint = when (type.kind) {
        Kind.CASH -> scheme.primaryContainer
        Kind.FIAT -> scheme.tertiaryContainer
        Kind.GOLD, Kind.SILVER, Kind.COIN -> scheme.secondaryContainer
        Kind.CRYPTO, Kind.STOCK -> scheme.surfaceVariant
        Kind.PROPERTY -> scheme.tertiaryContainer
    }
    // The disc's own «on» role, so the mark keeps contrast in both themes without a rule of
    // its own — the same pairing every container in the app already is.
    val ink = when (type.kind) {
        Kind.CASH -> scheme.onPrimaryContainer
        Kind.FIAT, Kind.PROPERTY -> scheme.onTertiaryContainer
        Kind.GOLD, Kind.SILVER, Kind.COIN -> scheme.onSecondaryContainer
        Kind.CRYPTO, Kind.STOCK -> scheme.onSurfaceVariant
    }
    val glyph = assetGlyph(type)
    Box(contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .size(size)
                .clip(CircleShape)
                .background(tint),
            contentAlignment = Alignment.Center,
        ) {
            val letters = @Composable {
                // Three letters at 12sp, never four at 9: this badge is the only identity a coin
                // has until its logo loads, and 9sp is below the floor an older eye can read.
                Text(
                    type.id.take(3).uppercase(),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            when {
                // The real currency mark for the one asset that IS the currency — an emoji bank
                // was always a stand-in.
                type.id == TOMAN_ID -> Icon(
                    painterResource(R.drawable.ic_toman),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(size * 0.52f),
                )
                type.iconUrl != null -> {
                    var success by remember(type.iconUrl) { mutableStateOf(false) }
                    var loaded by remember(type.iconUrl) { mutableStateOf(false) }
                    if (!loaded) letters()
                    // crossfade: Coil swaps hard by default, and a list of logos popping in
                    // over their letters reads as flicker.
                    AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current)
                            .data(type.iconUrl)
                            .crossfade(true)
                            .build(),
                        contentDescription = null,
                        modifier = Modifier.size(size * 0.62f),
                        // A row scrolled off is thrown away, so scrolling back composes this
                        // afresh with loaded=false and the letters underneath — which is
                        // invisible under an opaque logo, and exactly visible under a
                        // transparent one. Ethereum's diamond covers a third of its canvas, so
                        // every pass over the list restamped "ETH" through the glyph for a
                        // quarter second. From the memory cache there is no fade for the
                        // letters to cover — Coil skips the transition — so they can leave the
                        // moment the logo lands.
                        onState = { st ->
                            if (st is AsyncImagePainter.State.Success) {
                                if (st.result.dataSource == DataSource.MEMORY_CACHE) loaded = true
                                else success = true
                            }
                        },
                    )
                    // Success is when the fade STARTS; the letters leave after it lands, or
                    // the circle shows exactly the hole they exist to cover.
                    LaunchedEffect(success) {
                        if (success) {
                            delay(250)
                            loaded = true
                        }
                    }
                }
                glyph != null -> AssetGlyphIcon(glyph, ink, size = size * 0.55f)
                // Still reachable: an asset synced from a build that knows a kind this one
                // does not draw yet keeps its emoji rather than falling to bare letters.
                type.emoji != null -> Text(type.emoji, fontSize = (size.value * 0.52f).sp)
                else -> letters()
            }
        }
        // Bottom-end corner, sitting on the logo rather than beside it — the chain is a
        // property of that coin, and given its own slot in the row it reads as a second asset.
        // The ring is the row's own background, so the two discs stay two discs even where a
        // dark chain logo meets a dark coin logo.
        network?.let {
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(1.5.dp),
            ) { WalletNetworkLogo(it, size = size * 0.36f) }
        }
    }
}

/**
 * The band title for one kind, carrying that kind's own total — the question the flat list
 * could never answer: how much of this is dollars, how much is crypto.
 *
 * Type and air do the separating. No rule, no badge, no colour: the heads have to read as
 * quieter than the cards they introduce, or the list gains structure and loses its subject.
 * Same size, weight and muted colour as the picker's headings, so the two lists agree about
 * what a kind looks like; the number takes Medium so it reads as the payload without
 * competing with the label for the eye.
 */
@Composable
internal fun SectionHead(title: String, subtotal: Double?, modifier: Modifier = Modifier) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier
            .fillMaxWidth()
            .padding(edge)
            // A heading belongs to what follows it — more air above than below, as in the
            // picker. The 4dp inset is optical: flush against a 28dp corner radius, the label
            // reads as hanging outside the band it heads.
            .padding(top = Space.xxl, bottom = Space.m, start = Space.xs, end = Space.xs),
        verticalAlignment = Alignment.Bottom,
    ) {
        Text(
            title,
            fontSize = 15.sp,
            fontWeight = FontWeight.ExtraBold,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .semantics { heading() },
        )
        subtotal?.let {
            Text(
                // Three decimals, like the rows it sits above: a header reading ۱٫۴ over rows
                // that add up to ۱٫۴۰۹ looks like one of the two is wrong.
                "${faCompact(it, 2, pad = true)} $moneyUnit",
                fontSize = 13.sp,
                fontFamily = ModamFigures,
                fontWeight = FontWeight.SemiBold,
                color = muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * A row's value in Toman, at the same three decimals as the hero total — the same money
 * should not read as "۹٫۷ میلیارد" in one place and "۹٫۷۴۳ میلیارد" in the other.
 *
 * Shrink-to-fit like the hero, and bounded: three decimals make the figure noticeably wider,
 * and the asset's name (which shares the row) must not be the thing that gets ellipsised for
 * it on a narrow phone.
 */
/**
 * A row's title. Gives up a point or two of size before it gives up letters: the name of the
 * thing is what the row is for, and "حساب‌های بان…" is worse to read than the same words a
 * little smaller. Ellipsis stays as the last resort, below which shrinking would not help.
 */
@Composable
internal fun RowTitle(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
    max: TextUnit = 18.sp,
) {
    BasicText(
        text = text,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        autoSize = TextAutoSize.StepBased(minFontSize = 13.sp, maxFontSize = max),
        modifier = modifier,
        style = TextStyle(fontFamily = Modam, fontWeight = FontWeight.Bold, color = color),
    )
}

@Composable
internal fun RowAmount(
    toman: Double,
    color: Color,
    struck: Boolean = false,
    /**
     * Set inline, the way the hero card sets it, rather than stacked underneath. A caption on
     * its own line makes the figure two lines tall, and everything the row says about the
     * asset then starts a full line-height below the name it belongs to — the name ends up
     * stranded at the top of the card with a hole under it.
     */
    unit: String? = null,
) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    BasicText(
        text = buildAnnotatedString {
            append(faCompact(toman, 2, pad = true))
            // em, not sp: the whole line is shrink-to-fit, and the unit has to shrink with the
            // figure or it overtakes it on the row where the figure is smallest.
            unit?.let {
                // The space stays near full size, or it shrinks with the unit and the two
                // words touch. See the hero figure, which has the same seam.
                append(" ")
                withStyle(SpanStyle(fontSize = 0.7.em, fontWeight = FontWeight.Medium, color = muted)) {
                    append(it)
                }
            }
        },
        maxLines = 1,
        autoSize = TextAutoSize.StepBased(minFontSize = 13.sp, maxFontSize = 19.sp),
        // Bounded so the figure, not the asset's name, is what gives way on a narrow phone:
        // "۵۵۹٫۵۰۰ میلیون" is half again as wide as "۵۵۹٫۵ میلیون" was.
        modifier = Modifier.widthIn(max = if (unit == null) 116.dp else 140.dp),
        style = figureStyle(color, FontWeight.Bold).copy(
            textDecoration = if (struck) TextDecoration.LineThrough else null,
        ),
    )
}

private fun shortWalletAddress(address: String): String =
    if (address.length <= 15) address else "${address.take(7)}...${address.takeLast(5)}"

@Composable
internal fun HoldingRow(
    type: AssetType,
    /** Her own name for this holding where she gave it one, the asset's own where she did not. */
    name: String,
    amount: Double,
    rate: Double?,
    excluded: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    /** Replaces the amount-and-rate line, for a row whose amount did not come from her. */
    note: String? = null,
    wallet: WalletLink? = null,
    walletRefreshing: Boolean = false,
    walletError: String? = null,
    shape: Shape = RoundedCornerShape(Radius.group),
    divided: Boolean = false,
) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val strong = MaterialTheme.colorScheme.onSurface

    // No card, no elevation, no gap: a band of holdings is one object with rows inside it.
    // Individual cards gave every row the same weight as the band it belonged to, and eleven
    // of them read as eleven separate announcements rather than one list.
    //
    // No gesture either: setting an asset aside lives behind the row's edit sheet, as a
    // labelled switch. Swipes proved too easy to trigger scrolling the list and too hard
    // to discover on purpose.
    Box(
        modifier
            .padding(edge)
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface)
            .clickable(role = Role.Button, onClick = onClick),
    ) {
        // Set-aside dims by colour, not a blanket alpha: alpha over the whole row took the
        // 12sp caption that explains the state below every contrast floor.
        // Top, not centre: the badge belongs to the name, and centring it against a row that
        // may carry two more lines under that name left it floating beside the wrong one.
        // 44dp is the height of the name and the line below it, so it spans exactly the pair.
        Row(
            Modifier.padding(horizontal = Space.l, vertical = Space.l),
            verticalAlignment = Alignment.Top,
        ) {
            Box(Modifier.alpha(if (excluded) 0.55f else 1f)) {
                AssetIcon(type, network = wallet?.network)
            }
            Spacer(Modifier.width(Space.m))

            Column(Modifier.weight(1f)) {
                // The hero's own device, one size down: the name and the figure share the top
                // line, so the row reads as one sentence instead of two blocks that happen to
                // be next to each other. It also hands every line below it the full width of
                // the block rather than the strip left over beside the figure — which is what
                // the rate used to be truncated into.
                Row(verticalAlignment = Alignment.Top) {
                    RowTitle(
                        name,
                        color = if (excluded) muted else strong,
                        // One step under the figure. The money is the answer this row exists
                        // to give; the name only says which money.
                        max = 17.sp,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(Space.s))
                    // End, not Start: the figures are already flush with the card's edge, so
                    // this is the only alignment that keeps a set-aside row's caption in the
                    // same column instead of hanging it off the ragged inner end of a number.
                    Column(horizontalAlignment = Alignment.End) {
                        when {
                            excluded -> {
                                if (rate != null) {
                                    RowAmount(amount * rate, color = muted, struck = true)
                                }
                                // Stacked, not inline: this one is the row's state, not the
                                // figure's unit, and it has to survive being read on its own.
                                Text("توی جمع حساب نشده", fontSize = 12.sp, color = muted)
                            }
                            rate == null ->
                                Text("نرخش پیدا نشد", fontSize = 14.sp, color = MaterialTheme.colorScheme.error)
                            else -> RowAmount(amount * rate, color = strong, unit = moneyUnit)
                        }
                    }
                }

                val held = "${faHeld(amount, type.dec)} ${bidi(type.unitFa)}"
                // null when this line is a sentence rather than an amount-and-rate pair.
                val shownRate = rate?.takeIf { note == null && !type.valuedInToman }?.let { faRate(shownOf(it)) }
                if (shownRate == null) {
                    Text(
                        when {
                            note != null -> note
                            // Says how the figure got here, which is the whole difference
                            // between this row and the bank one right below it — and the only
                            // thing there is to say about a car nobody quotes a price for.
                            type.valuedInToman -> "دستی وارد شده"
                            else -> held
                        },
                        fontSize = 13.sp,
                        color = muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                } else {
                    // The rate carries no weight, so Row measures it first and gives it the
                    // width it asks for: when the line cannot hold both, the amount she typed
                    // herself is what gives way, never the rate she opened the app to read.
                    // One string, not two Texts — the label and its figure have to stay in one
                    // bidi run or the separator drifts off the end of the line.
                    Row(Modifier.padding(top = 2.dp)) {
                        Text(
                            held,
                            fontSize = 13.sp,
                            color = muted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        Text(
                            buildAnnotatedString {
                                // "نرخ" rather than "هر <unit>": one less latin run, and short
                                // enough that a long ticker cannot push the line onto a second.
                                withStyle(SpanStyle(color = muted)) { append("•  نرخ ") }
                                // Full strength and a weight up — the one figure on this line
                                // worth finding at a glance, and the reason the line exists.
                                withStyle(SpanStyle(color = strong, fontWeight = FontWeight.Medium)) {
                                    append(shownRate)
                                }
                            },
                            fontSize = 13.sp,
                            maxLines = 1,
                            modifier = Modifier.padding(start = Space.s),
                        )
                    }
                }

                wallet?.let {
                    // One line, not two: the address is a "this is tracked for you" mark, not
                    // something she can check by reading — and stacked, it was the third loose
                    // block that made the card read as pieces rather than a row.
                    val visibleStatus = when {
                        walletRefreshing -> "در حال گرفتن از ${it.networkFa}"
                        walletError != null -> "به‌روز نشد  •  موجودی قبلی نشون داده می‌شه"
                        else -> "${it.networkFa}  •  ${bidi(shortWalletAddress(it.address))}"
                    }
                    val spokenStatus = when {
                        walletRefreshing -> "در حال گرفتن موجودی از ${it.networkFa}"
                        walletError != null -> "موجودی به‌روز نشد؛ موجودی قبلی نشون داده می‌شه"
                        else -> "خوندن خودکار از ${it.networkFa}، آدرس ${it.address}"
                    }
                    Text(
                        visibleStatus,
                        fontSize = 12.sp,
                        color = if (walletError != null) MaterialTheme.colorScheme.error else muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .padding(top = 2.dp)
                            .semantics { contentDescription = spokenStatus },
                    )
                }
            }
        }
        // Inset to where the text starts, not to the card edge: a rule that runs under the
        // badge cuts the row in half instead of separating it from the next one. Only between
        // rows — a rule under the last one is the band's own bottom edge drawn twice.
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
 * It names the button rather than repeating it. The bar at the bottom is on screen the whole
 * time this hint is, and two identical green pills one above the other read as a mistake —
 * the reader's question becomes which of them is the real one.
 */
@Composable
internal fun FamilyAssetBand(shared: FamilyAssetView, modifier: Modifier = Modifier) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    // One band per member, rows inside — the same construction her own holdings wear. Nothing
    // here is tappable: the figures are the owner's to edit, on the owner's phone. The names
    // and the prices arrive as sent; this phone never re-derives someone else's money.
    Column(
        modifier
            .padding(edge)
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.group))
            .background(MaterialTheme.colorScheme.surface),
    ) {
        if (shared.items.isEmpty()) {
            Text(
                "فعلاً چیزی برای نمایش نفرستاده.",
                fontSize = 13.sp,
                color = muted,
                modifier = Modifier.padding(Space.l),
            )
        }
        shared.items.forEachIndexed { i, item ->
            Row(
                Modifier.padding(horizontal = Space.l, vertical = Space.m),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RowTitle(item.name, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(Space.s))
                RowAmount(item.toman, color = MaterialTheme.colorScheme.onSurface, unit = moneyUnit)
            }
            if (i < shared.items.lastIndex) {
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant,
                    modifier = Modifier.padding(start = Space.l),
                )
            }
        }
    }
}

/** [pill]: the add button is the «+ دارایی» pill up by the title, not the circle under the card. */
@Composable
internal fun EmptyHint(pill: Boolean) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(edge)
            .padding(top = Space.huge, bottom = Space.xxl),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // The tab set's own two coins, not an emoji: a sticker in the one empty moment the
        // screen has would be the app changing hands exactly where it introduces itself.
        val ink = MaterialTheme.colorScheme.onPrimaryContainer
        Box(
            Modifier
                .size(84.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(
                Modifier
                    .size(40.dp)
                    // The near coin knocks its hole out of the glyph, not the disc under it.
                    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen },
            ) { inset(3.dp.toPx()) { drawAssets(ink) } }
        }
        Spacer(Modifier.height(Space.xl))
        Text(
            "هنوز چیزی اضافه نکردی",
            fontSize = 22.sp,
            fontWeight = FontWeight.ExtraBold,
        )
        Spacer(Modifier.height(Space.s))
        Text(
            if (pill) "پول نقد، دلار، طلا، سکه یا رمزارز رو از دکمه «+ دارایی» بالای صفحه اضافه کن تا جمعشون رو ببینی."
            else "از دکمه پایین پول نقد، دلار، طلا، سکه یا رمزارز اضافه کن تا جمعشون رو ببینی.",
            fontSize = 15.sp,
            lineHeight = 25.sp,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
internal fun MissingNote(missing: List<String>, coins: List<Coin>, stocks: List<Stock>) {
    val names = missing.map { resolveType(it, coins, stocks).fa }.distinct().joinToString("، ")
    Row(
        Modifier
            .padding(edge)
            .padding(top = Space.l)
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.card))
            .background(MaterialTheme.colorScheme.errorContainer)
            .padding(Space.l),
    ) {
        Text(
            "نرخ $names پیدا نشد و توی جمع حساب نشده. می‌تونی نرخش رو دستی وارد کنی.",
            fontSize = 13.sp,
            lineHeight = 21.sp,
            color = MaterialTheme.colorScheme.onErrorContainer,
        )
    }
}

/**
 * The kinds too numerous to list, mapped to what to call them when saying so. These come from
 * the network by the hundred, so the picker shows a first handful and leaves the rest to
 * search; every other kind is a short fixed list that belongs on screen in full.
 */
private val BROWSABLE_BY_SEARCH = mapOf(
    Kind.CRYPTO to "رمزارزها",
    Kind.STOCK to "نمادها",
)

/**
 * A drag-to-dismiss sheet rather than a dialog: the list is long, the phone is held one
 * handed, and the controls belong near the thumb.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PickTypeSheet(
    all: List<AssetType>,
    already: Map<String, Int>,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val q = query.trim()

    val sections: List<Pair<String?, List<AssetType>>> = remember(q, all) {
        if (q.isNotEmpty()) {
            // Persian name, latin name or ticker — matchesSearch owns which of them count.
            listOf<Pair<String?, List<AssetType>>>(null to all.filter { matchesSearch(it, q) })
        } else {
            // Capped, or 250 tickers bury the assets she actually owns — and بورس is four
            // times that again.
            Kind.entries.map { kind ->
                val items = all.filter { it.kind == kind }
                kind.fa to if (kind in BROWSABLE_BY_SEARCH) items.take(12) else items
            }
        }
    }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    // Play the sheet out before removing it from composition, or the selection just blinks.
    fun close(then: () -> Unit) = scope.hideThen(sheetState, then)

    AppSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = Radius.sheet, topEnd = Radius.sheet),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            // A bounded height is what makes the drag smooth: with fillMaxSize on the list
            // the sheet grows to the whole screen, loses its corners and scrim, and every
            // drag re-measures the content.
            Modifier
                .nestedScroll(SheetFlingGuard)
                .fillMaxHeight(0.88f)
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = Space.xl)
        ) {
            SheetTitle("چی می‌خوای اضافه کنی؟")

            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                // No fontSize on the placeholder: it sits in the same slot as the value and
                // must not shrink the moment she starts typing.
                placeholder = { Text("جستجو...") },
                shape = RoundedCornerShape(Radius.field),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = Space.m, bottom = Space.xs)
                    .semantics { contentDescription = "جستجو" },
            )

            LazyColumn(
                contentPadding = PaddingValues(bottom = Space.xxl),
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
                if (q.isNotEmpty() && sections.all { it.second.isEmpty() }) {
                    item {
                        // Names the query and points at the exit: search also matches the
                        // latin name and the ticker, and this is the one moment to say so.
                        Text(
                            // bidi(): a mixed query like "gold 18" would otherwise render
                            // with its runs reordered inside the guillemets.
                            "برای «${bidi(q)}» چیزی پیدا نشد. اسم انگلیسی یا نمادش رو هم امتحان کن.",
                            fontSize = 15.sp,
                            lineHeight = 24.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = Space.xxl),
                        )
                    }
                }

                sections.forEach { (title, items) ->
                    if (items.isEmpty()) return@forEach
                    if (title != null) {
                        item(key = "h_$title") {
                            // The same heading the asset list uses, so a kind looks like the
                            // same kind of thing whether she is reading her money or adding to it.
                            Text(
                                title,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = MaterialTheme.colorScheme.onSurface,
                                // more air above a heading than below it
                                modifier = Modifier
                                    .padding(top = Space.xxl, bottom = Space.s, start = Space.xs)
                                    .semantics { heading() },
                            )
                        }
                    }
                    items(items, key = { it.id }) { type ->
                        PickRow(type, already[type.id] ?: 0) { close { onPick(type.id) } }
                    }
                    // The cue that more exist sits under the rows it discloses — at the
                    // bottom of the whole sheet, after gold and سکه, a capped section just
                    // looked complete.
                    val more = BROWSABLE_BY_SEARCH.entries.firstOrNull { it.key.fa == title }
                    if (q.isEmpty() && more != null) {
                        item(key = "more_hint_$title") {
                            Text(
                                "بقیه ${more.value} رو می‌خوای؟ اسمش رو جستجو کن.",
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = Space.s, start = Space.xs),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PickRow(type: AssetType, already: Int, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.field))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = Space.s, vertical = Space.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AssetIcon(type, size = 40.dp)
        Spacer(Modifier.width(Space.m))
        Column(Modifier.weight(1f)) {
            Text(
                type.fa,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // The ticker tells one coin from another where the Persian names blur together;
            // for a نماد the same job is done by the company behind it, since its own id is
            // an instrument code that means nothing to anyone.
            val subtitle = when (type.kind) {
                Kind.CRYPTO -> type.id.uppercase()
                Kind.STOCK -> type.en
                else -> ""
            }
            if (subtitle.isNotBlank()) {
                Text(
                    subtitle,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (already > 0) {
            // A chip, not loose grey words: it is a state this row is in, and it has to read
            // as attached to the row rather than as a second, quieter label.
            Text(
                if (already == 1) "اضافه شده" else "${faNumber(already.toDouble())} تا اضافه شده",
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .clip(RoundedCornerShape(Radius.pill))
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                    .padding(horizontal = Space.m, vertical = 5.dp),
            )
        }
    }
}

private enum class AmountSource { WALLET, MANUAL }

/**
 * Nine networks as nine full-width rows was half the sheet spent on one choice, and the logo —
 * the thing the eye actually looks for — sat after a label of variable length, so no two logos
 * shared an edge. Two columns, logo leading from a fixed inset, name after it: the column of
 * marks becomes the index, and the whole choice fits in a glance instead of a scroll.
 *
 * Chosen is a fill *and* a ring *and* a weight, because a fill alone is colour doing the work
 * of structure.
 */
@Composable
private fun WalletNetworkChoice(
    options: List<WalletOption>,
    selected: String,
    enabled: Boolean,
    onSelect: (String) -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.6f)
            .selectableGroup(),
        verticalArrangement = Arrangement.spacedBy(Space.m),
    ) {
        options.chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(Space.m)) {
                pair.forEach { option ->
                    val active = option.network == selected
                    Row(
                        Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(Radius.field))
                            .background(
                                if (active) MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surfaceContainer,
                            )
                            .border(
                                width = if (active) 2.dp else 0.dp,
                                color = if (active) MaterialTheme.colorScheme.primary
                                else Color.Transparent,
                                shape = RoundedCornerShape(Radius.field),
                            )
                            .selectable(
                                selected = active,
                                enabled = enabled,
                                role = Role.RadioButton,
                                onClick = { onSelect(option.network) },
                            )
                            .heightIn(min = 56.dp)
                            .padding(horizontal = Space.m),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        WalletNetworkLogo(option.network)
                        Spacer(Modifier.width(Space.s))
                        Text(
                            option.networkFa,
                            fontSize = 14.sp,
                            fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                // An odd count keeps the grid's columns rather than stretching the last tile
                // across both — the shared leading edge is the point of the layout.
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EditSheet(
    // Which holding this sheet is about, not which asset: two rows can share an asset, so the
    // fields below have to be re-seeded per holding.
    key: String,
    type: AssetType,
    holding: Holding?,
    rate: Double?,
    isOverridden: Boolean,
    excluded: Boolean,
    walletBusy: Boolean,
    walletError: String?,
    onWalletEdit: () -> Unit,
    onExcluded: (Boolean) -> Unit,
    onSaveManual: (Double) -> Unit,
    onSaveWallet: (WalletOption, String, () -> Unit) -> Unit,
    onDelete: () -> Unit,
    onRate: (Double?) -> Unit,
    onLabel: (String) -> Unit,
    onDismiss: () -> Unit,
    /** «به کسی قرض دادم» — null where this holding is not something one lends. See `Loans.kt`. */
    onLend: (() -> Unit)? = null,
) {
    // Cash, a bank and a house are typed as money, so in her unit ([showRial]). Everything below
    // works in what the field holds; only this seed and the save convert.
    val inMoney = type.valuedInToman
    val current = holding?.amount?.let { if (inMoney) shownOf(it) else it }
    val unitFa = if (inMoney) moneyUnit else type.unitFa
    val linkedWallet = holding?.wallet
    val linkedOption = linkedWallet?.let {
        WalletOption(it.network, it.networkFa, it.contract)
    }
    val walletOptions = remember(type.wallets, linkedOption) {
        (type.wallets + listOfNotNull(linkedOption)).distinctBy { it.network }
    }
    val startsWithWallet = type.kind == Kind.CRYPTO && walletOptions.isNotEmpty() &&
        (linkedWallet != null || holding == null)

    // Raw digits live in state; grouping is purely visual so parsing stays exact.
    var source by rememberSaveable(key, linkedWallet != null, walletOptions.isNotEmpty()) {
        mutableStateOf(if (startsWithWallet) AmountSource.WALLET else AmountSource.MANUAL)
    }
    var text by rememberSaveable(key) {
        mutableStateOf(current?.let(::fieldNumber).orEmpty())
    }
    // Seeded from the rate each time she opens the field, and written only once she has typed
    // over the seed: «ذخیره نرخ» on an untouched field must not freeze the live rate as hers.
    var rateSeed by rememberSaveable { mutableStateOf("") }
    var rateText by rememberSaveable { mutableStateOf("") }
    var editingRate by rememberSaveable { mutableStateOf(false) }
    var labelText by rememberSaveable(key) { mutableStateOf(holding?.label.orEmpty()) }
    var naming by rememberSaveable(key) {
        mutableStateOf(holding == null && type.kind == Kind.PROPERTY)
    }
    var nameFocusWanted by remember(key) { mutableStateOf(false) }
    var adjusting by rememberSaveable { mutableStateOf(false) }
    var deltaText by rememberSaveable { mutableStateOf("") }
    // Why the last ＋ / − tap did nothing, until the field changes. Likewise for ذخیره نرخ.
    var deltaRefusal by remember { mutableStateOf<String?>(null) }
    var rateRefusal by remember { mutableStateOf<String?>(null) }
    var manualSubmitted by remember { mutableStateOf(false) }
    var walletAddress by rememberSaveable(key, linkedWallet?.address) {
        mutableStateOf(linkedWallet?.address.orEmpty())
    }
    var selectedNetwork by rememberSaveable(key, linkedWallet?.network, walletOptions) {
        mutableStateOf(linkedWallet?.network ?: walletOptions.firstOrNull()?.network.orEmpty())
    }
    var localWalletError by remember { mutableStateOf<String?>(null) }

    val manualAmount = parseAmount(text)
    val selectedWallet = walletOptions.firstOrNull { it.network == selectedNetwork }
    val linkedSelection = linkedWallet != null &&
        linkedWallet.network == selectedNetwork &&
        linkedWallet.address == walletAddress.trim() &&
        linkedWallet.contract == selectedWallet?.contract
    val amount = if (source == AmountSource.MANUAL) manualAmount
    else current?.takeIf { linkedSelection }
    val typedRate = parseAmount(rateText)?.takeIf { it > 0 }?.let(::tomanOfShown)
    // While she is typing a manual rate, the "می‌شود …" preview follows what she types, so
    // the consequence of the override is visible before it is committed.
    val previewRate = if (editingRate) typedRate ?: rate else rate
    val muted = MaterialTheme.colorScheme.onSurfaceVariant

    val focus = LocalFocusManager.current
    val amountFocus = remember { FocusRequester() }
    if (current == null && source == AmountSource.MANUAL) {
        LaunchedEffect(source) {
            delay(250) // let the sheet finish sliding in before the IME starts moving it
            amountFocus.requestFocus()
        }
    }

    // The name she typed, written to the row — from ذخیره, from ذخیره اسم, and from closing the
    // sheet. Typing a name and swiping the sheet away is not "cancel", it is how anyone would
    // expect a name to be given, and until this ran on dismiss too it was thrown away in silence.
    // Nothing to write to while adding: there is no row until ذخیره makes one, and that path
    // calls this itself, in order.
    val nameIt = { if (labelText.trim() != holding?.label.orEmpty()) onLabel(labelText) }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    fun close(then: () -> Unit) = scope.hideThen(sheetState, then)

    AppSheet(
        // Nothing to write to while adding: there is no row until ذخیره makes one.
        onDismissRequest = { if (holding != null) nameIt(); onDismiss() },
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = Radius.sheet, topEnd = Radius.sheet),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            // Scrolls: with the keyboard up — and it comes up on its own when adding — the
            // expanded sections pushed ذخیره off the bottom of shorter phones with no way
            // to reach it.
            Modifier
                .nestedScroll(SheetFlingGuard)
                .navigationBarsPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Space.xl),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AssetIcon(type, size = 42.dp)
                Spacer(Modifier.width(Space.m))
                Column(Modifier.weight(1f)) {
                    Text(
                        holding?.nameOr(type.fa) ?: type.fa,
                        fontSize = 26.sp,
                        fontWeight = FontWeight.Black,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    // Only once the two differ: under "تتر" this line would just say "تتر".
                    if (!holding?.label.isNullOrBlank()) {
                        Text(
                            type.fa,
                            fontSize = 13.sp,
                            color = muted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            // Named, not renamed: the asset keeps its own name everywhere else, and this is
            // the label on this particular holding of it — "تتر شخصی" beside "تتر مشترک",
            // "پراید سفید" beside "ویلای شمال".
            //
            // Offered while adding too, not only when editing. The moment a name is needed is
            // the moment she is adding the *second* خودرو, and until now that meant saving a
            // row indistinguishable from the first one and coming back for it. Open from the
            // start for املاک و خودرو, where the asset's own name never tells two apart, and
            // behind a tap everywhere else, where it usually does.
            if (!naming) {
                PillButton(
                    if (labelText.isBlank()) "اسم دلخواه بذار" else "تغییر اسم",
                    { naming = true; nameFocusWanted = true },
                    Modifier.padding(top = Space.m),
                )
            } else {
                val nameFocus = remember { FocusRequester() }
                // Only when she asked for the field. Opened on its own for a new ملک the amount
                // is still the first thing to type, and the caret must not be taken off it.
                LaunchedEffect(nameFocusWanted) {
                    if (nameFocusWanted) {
                        nameFocus.requestFocus()
                        nameFocusWanted = false
                    }
                }
                SheetLabel("اسم دلخواه")
                OutlinedTextField(
                    value = labelText,
                    onValueChange = { labelText = it.take(32) },
                    singleLine = true,
                    // A label that stays, not a placeholder that vanishes at the first
                    // letter — the example is the whole explanation of what this is for.
                    placeholder = {
                        Text(
                            if (type.kind == Kind.PROPERTY) "مثلاً ${type.fa} دوم"
                            else "مثلاً ${type.fa} شخصی",
                        )
                    },
                    shape = RoundedCornerShape(Radius.field),
                    // While adding, the amount is the next thing to type and the keyboard says
                    // so; there is no row yet for a Done to write to.
                    keyboardOptions = KeyboardOptions(
                        imeAction = if (holding == null) ImeAction.Next else ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = { onLabel(labelText); naming = false },
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(nameFocus)
                        .semantics { contentDescription = "اسم دلخواه" },
                )
                // A name can be saved on its own only once there is a row to hang it on. While
                // adding, ذخیره at the bottom carries it in together with the amount.
                if (holding != null) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(Space.s),
                        modifier = Modifier.padding(top = Space.m),
                    ) {
                        PillButton("ذخیره اسم", { onLabel(labelText); naming = false })
                        if (holding.label.isNotBlank()) {
                            PillButton("اسم اصلی", {
                                labelText = ""
                                onLabel("")
                                naming = false
                            })
                        }
                        Spacer(Modifier.weight(1f))
                        PillButton("بستن", { labelText = holding.label; naming = false })
                    }
                }
            }

            if (type.kind == Kind.CRYPTO) {
                SheetLabel("موجودی رو چطور وارد می‌کنی؟")
                if (walletOptions.isNotEmpty()) {
                    SegmentedChoice(
                        options = listOf(AmountSource.WALLET, AmountSource.MANUAL),
                        selected = source,
                        label = { if (it == AmountSource.WALLET) "خودکار" else "دستی" },
                        enabled = { !walletBusy },
                        onSelect = {
                            source = it
                            adjusting = false
                            localWalletError = null
                            onWalletEdit()
                        },
                    )
                    // One line under the track instead of a caption inside each pill: the
                    // detail only ever describes the chosen one, and printing both put the
                    // answer to a question she had not asked next to the one she had.
                    Text(
                        if (source == AmountSource.WALLET)
                            "موجودی از آدرس عمومی کیف پول خونده می‌شه."
                        else "مقدار رو خودت وارد می‌کنی.",
                        fontSize = 13.sp,
                        lineHeight = 20.sp,
                        color = muted,
                        modifier = Modifier.padding(top = Space.s, start = Space.xs),
                    )
                } else {
                    Surface(
                        shape = RoundedCornerShape(Radius.field),
                        color = MaterialTheme.colorScheme.surfaceContainer,
                    ) {
                        Text(
                            "برای این رمزارز، شبکه‌ای برای خوندن خودکار پیدا نشد. مقدار رو دستی وارد کن.",
                            fontSize = 14.sp,
                            lineHeight = 22.sp,
                            color = muted,
                            modifier = Modifier.padding(Space.l),
                        )
                    }
                }
            }

            if (source == AmountSource.MANUAL) {
                // "چقدر تومان داری؟" is the right question for cash and the wrong one for a
                // house: what is being asked for there is a valuation, and she is the only one
                // who can give it.
                val prompt =
                    if (type.kind == Kind.PROPERTY) if (showRial) "به نظرت چند ریال می‌ارزه؟" else "به نظرت چند تومن می‌ارزه؟"
                    else "چقدر ${bidi(unitFa)} داری؟"
                SheetLabel(prompt)
                val amountInvalid = manualAmount == null && (text.isNotBlank() || manualSubmitted)
                OutlinedTextField(
                value = text,
                onValueChange = { text = it; manualSubmitted = false },
                singleLine = true,
                isError = amountInvalid,
                visualTransformation = GroupedNumber,
                textStyle = TextStyle(
                    fontFamily = ModamFigures,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                ),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                // A red outline is colour alone; the words say what to fix, and why ذخیره
                // did not save. Announced, since a save tap is what usually raises them.
                supportingText = if (amountInvalid) ({
                    Text(
                        if (text.isBlank()) "مقدار دارایی رو وارد کن."
                        else "این عدد قابل خوندن نیست. فقط عدد وارد کن.",
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }) else null,
                shape = RoundedCornerShape(Radius.field),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(amountFocus)
                    // The visible prompt above is not programmatically attached; this is.
                    .semantics { contentDescription = prompt },
                )

            // The figure she just typed, in words — the guard against an extra zero.
            if (manualAmount != null && manualAmount % 1.0 == 0.0 && manualAmount >= 1000) {
                Text(
                    "${faWords(manualAmount.toLong())} ${bidi(unitFa)}",
                    fontSize = 14.sp,
                    lineHeight = 22.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = Space.s, start = Space.xs, end = Space.xs),
                )
            }

            // Received some, spent some: she types the change and the field above becomes the
            // new total, instead of her doing the sum on paper. ذخیره commits it like any
            // other edit, so there is still exactly one moment money is written.
            if (current != null) {
                if (!adjusting) {
                    PillButton("اضافه یا کم کردن", { adjusting = true }, Modifier.padding(top = Space.m))
                } else {
                    val deltaFocus = remember { FocusRequester() }
                    LaunchedEffect(Unit) { deltaFocus.requestFocus() }
                    // A label that stays, not a placeholder that vanishes at the first digit.
                    Text(
                        "مقدار تغییر رو وارد کن",
                        fontSize = 14.sp,
                        color = muted,
                        modifier = Modifier.padding(top = Space.s),
                    )
                    Spacer(Modifier.height(Space.xs))
                    // A delta finer than the asset's own precision is rejected, never
                    // rounded: trimNumber rounds half-up, and half a سکه rounded to a whole
                    // one is money invented — the one direction this app must never err in.
                    val typedDelta = parseAmount(deltaText)?.takeIf { it > 0 }
                    val delta = typedDelta?.takeIf { parseAmount(trimNumber(it, type.dec)) == it }
                    OutlinedTextField(
                        value = deltaText,
                        onValueChange = { deltaText = it; deltaRefusal = null },
                        singleLine = true,
                        visualTransformation = GroupedNumber,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        supportingText = when {
                            typedDelta != null && delta == null -> ({
                                Text(
                                    if (type.dec == 0) "فقط عدد کامل وارد کن."
                                    else "حداکثر ${faNumber(type.dec.toDouble())} رقم اعشار وارد کن.",
                                )
                            })
                            deltaRefusal != null -> ({
                                Text(
                                    deltaRefusal.orEmpty(),
                                    color = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                                )
                            })
                            else -> null
                        },
                        shape = RoundedCornerShape(Radius.field),
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(deltaFocus)
                            .semantics {
                                contentDescription = "مقدار تغییر $unitFa"
                            },
                    )
                    val base = amount ?: current
                    fun apply(next: Double) {
                        // At the finer of the asset's places and the amount's own, which only
                        // erases binary noise (0.1 + 0.2): a dust balance past `dec` is kept.
                        text = trimNumber(next, maxOf(type.dec, fieldNumber(base).substringAfter('.', "").length))
                        deltaText = ""
                        adjusting = false
                    }
                    // Never greyed out — a dead button explains nothing — so a tap that cannot
                    // apply says why, with the keyboard out of the way of the words.
                    fun tryApply(sign: Int) {
                        focus.clearFocus()
                        val next = delta?.let { base + sign * it }
                        if (next != null && next >= 0) return apply(next)
                        deltaRefusal = when {
                            // A holding cannot go below nothing — taking out more than is there
                            // is a typo, not a request.
                            next != null -> "بیشتر از اونی که داری نمی‌شه کم کرد."
                            typedDelta != null -> null // the field already names the places
                            deltaText.isNotBlank() && parseAmount(deltaText) == null ->
                                "این عدد قابل خوندن نیست. فقط عدد وارد کن."
                            else -> "مقدار تغییر رو بنویس."
                        }
                    }
                    // Air between the two opposite intents: an edge mis-tap here flips the
                    // sign of a money adjustment.
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(Space.m),
                        modifier = Modifier.padding(top = Space.m),
                    ) {
                        PillButton("＋ اضافه کن", { tryApply(1) })
                        PillButton("− کم کن", { tryApply(-1) })
                        Spacer(Modifier.weight(1f))
                        PillButton("بستن", { adjusting = false; deltaText = ""; deltaRefusal = null })
                    }
                }
            }

            } else {
                SheetLabel("شبکه رمزارز")
                WalletNetworkChoice(
                    options = walletOptions,
                    selected = selectedNetwork,
                    enabled = !walletBusy,
                    onSelect = {
                        if (selectedNetwork != it) walletAddress = ""
                        selectedNetwork = it
                        localWalletError = null
                        onWalletEdit()
                    },
                )

                SheetLabel("آدرس عمومی کیف پول")
                OutlinedTextField(
                    value = walletAddress,
                    onValueChange = {
                        walletAddress = it.take(128)
                        localWalletError = null
                        onWalletEdit()
                    },
                    singleLine = true,
                    enabled = !walletBusy,
                    isError = localWalletError != null,
                    textStyle = TextStyle(
                        fontSize = 16.sp,
                        textAlign = TextAlign.Left,
                        textDirection = TextDirection.Ltr,
                    ),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                    supportingText = localWalletError?.let { message ->
                        {
                            Text(
                                message,
                                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                            )
                        }
                    },
                    shape = RoundedCornerShape(Radius.field),
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = "آدرس عمومی کیف پول" },
                )
                Text(
                    "فقط آدرس عمومی رو وارد کن. عبارت بازیابی یا کلید خصوصی رو هیچ‌وقت وارد نکن.",
                    fontSize = 13.sp,
                    lineHeight = 20.sp,
                    color = muted,
                    modifier = Modifier.padding(horizontal = Space.xs),
                )
                Text(
                    "برای خوندن موجودی، آدرس به سرویس عمومی همون شبکه فرستاده می‌شه.",
                    fontSize = 12.sp,
                    lineHeight = 19.sp,
                    color = muted,
                    modifier = Modifier.padding(horizontal = Space.xs, vertical = Space.xs),
                )

                if (linkedSelection && current != null) {
                    Spacer(Modifier.height(Space.l))
                    // Full width, like everything else in the sheet. Wrapping its content made
                    // it a narrower card directly above a wider one, which read as two
                    // unrelated objects rather than one answer in two units.
                    Surface(
                        shape = RoundedCornerShape(Radius.card),
                        color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(Modifier.padding(Space.l)) {
                            Text(
                                "موجودی فعلی",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                            BasicText(
                                // faHeld, not the asset's full precision: the row outside this
                                // sheet says ۱۰٬۷۰۹٫۱۳ and this said ۱۰٬۷۰۹٫۱۳۵۶۸۱ — one
                                // number in two lengths reads as two different numbers.
                                text = "${faHeld(current, type.dec)} ${bidi(type.unitFa)}",
                                maxLines = 1,
                                autoSize = TextAutoSize.StepBased(
                                    minFontSize = 18.sp,
                                    maxFontSize = 30.sp,
                                ),
                                modifier = Modifier.padding(top = 2.dp),
                                style = figureStyle(
                                    MaterialTheme.colorScheme.onPrimaryContainer,
                                    FontWeight.ExtraBold,
                                ),
                            )
                            // The same money in Toman, inside the same card rather than in a
                            // second one below it. It is the conversion of the figure above,
                            // not a separate finding.
                            previewRate?.let { rate ->
                                BasicText(
                                    text = "${faCompact(current * rate, 2, pad = true)} $moneyUnit",
                                    maxLines = 1,
                                    autoSize = TextAutoSize.StepBased(
                                        minFontSize = 13.sp,
                                        maxFontSize = 17.sp,
                                    ),
                                    modifier = Modifier.padding(top = Space.xs),
                                    style = figureStyle(
                                        MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                                        FontWeight.SemiBold,
                                    ),
                                )
                            }
                            Text(
                                when {
                                    walletBusy -> "در حال گرفتن موجودی"
                                    walletError != null -> walletError
                                    else -> "آخرین بار: ${faAgo(linkedWallet.updatedAt, System.currentTimeMillis())}"
                                },
                                fontSize = 12.sp,
                                lineHeight = 19.sp,
                                color = if (walletError != null) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.semantics {
                                    if (walletError != null) liveRegion = LiveRegionMode.Polite
                                },
                            )
                        }
                    }
                } else if (walletError != null) {
                    Text(
                        walletError,
                        fontSize = 13.sp,
                        lineHeight = 20.sp,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier
                            .padding(horizontal = Space.xs, vertical = Space.s)
                            .semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
            }

            // Not when the read-balance card is up: that card already carries the Toman line,
            // and the two of them said the same conversion twice, in two differently sized
            // boxes, one under the other.
            val balanceCardShown = source == AmountSource.WALLET && linkedSelection && current != null
            if (amount != null && previewRate != null && !type.valuedInToman && !balanceCardShown) {
                Spacer(Modifier.height(Space.l))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(Radius.card))
                        .background(MaterialTheme.colorScheme.primaryContainer)
                        .padding(Space.l),
                ) {
                    // Same figure the row shows, so it carries the same three decimals — and
                    // shrinks rather than wrapping, since it is one sentence.
                    BasicText(
                        text = "یعنی ${faCompact(amount * previewRate, 2, pad = true)} $moneyUnit",
                        maxLines = 1,
                        autoSize = TextAutoSize.StepBased(minFontSize = 14.sp, maxFontSize = 18.sp),
                        style = TextStyle(
                            fontFamily = ModamFigures,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        ),
                    )
                }
            }

            if (!type.valuedInToman) {
                Spacer(Modifier.height(Space.m))
                if (!editingRate) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (rate == null) "نرخ پیدا نشد"
                            else "هر ${bidi(type.unitFa)}: ${faNumber(shownOf(rate))} $moneyUnit" +
                                if (isOverridden) "  (دستی وارد شده)" else "",
                            fontSize = 14.sp,
                            color = muted,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(Space.s))
                        PillButton("تغییر نرخ", {
                            rateSeed = rate?.let { fieldNumber(shownOf(it)) }.orEmpty()
                            rateText = rateSeed
                            rateRefusal = null
                            editingRate = true
                        })
                    }
                } else {
                    // She tapped "تغییر نرخ" to type a rate; typing must land in this field,
                    // not silently keep appending to the amount above.
                    val rateFocus = remember { FocusRequester() }
                    LaunchedEffect(Unit) { rateFocus.requestFocus() }
                    Text("هر ${bidi(type.unitFa)} چند $moneyUnit؟", fontSize = 14.sp, color = muted)
                    Spacer(Modifier.height(Space.xs))
                    OutlinedTextField(
                        value = rateText,
                        onValueChange = { rateText = it; rateRefusal = null },
                        singleLine = true,
                        visualTransformation = GroupedNumber,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        supportingText = rateRefusal?.let { refusal ->
                            {
                                Text(
                                    refusal,
                                    color = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                                )
                            }
                        },
                        shape = RoundedCornerShape(Radius.field),
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(rateFocus)
                            .semantics { contentDescription = "هر ${type.unitFa} چند $moneyUnit؟" },
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(Space.s),
                        modifier = Modifier.padding(top = Space.m),
                    ) {
                        // Refuses, out loud, while the field does not read as a rate: saving null
                        // removed the override, and that is «برگشت به نرخ خودکار»'s job.
                        PillButton(
                            "ذخیره نرخ",
                            {
                                focus.clearFocus()
                                val typed = typedRate
                                if (typed == null) {
                                    rateRefusal = if (rateText.isNotBlank() && parseAmount(rateText) == null) {
                                        "این عدد قابل خوندن نیست. فقط عدد وارد کن."
                                    } else {
                                        "نرخ رو بنویس."
                                    }
                                } else {
                                    if (rateText != rateSeed) onRate(typed)
                                    editingRate = false
                                }
                            },
                        )
                        if (isOverridden) {
                            PillButton("برگشت به نرخ خودکار", {
                                onRate(null); editingRate = false
                            })
                        }
                    }
                }
            }

            if (current != null) {
                Spacer(Modifier.height(Space.m))
                // toggleable on the row: label and switch become one named control, and the
                // words are the hit target too.
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .toggleable(
                            value = !excluded,
                            role = Role.Switch,
                            onValueChange = { on -> onExcluded(!on) },
                        )
                        // With onCheckedChange = null the Switch no longer carries its own
                        // 48dp minimum; the row has to.
                        .heightIn(min = 48.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("توی جمع حساب بشه", fontSize = 15.sp)
                        Text(
                            // e.g. savings she does not want staring back from the total
                            "اگه فعلاً نمی‌خوای توی جمع بیاد، خاموشش کن.",
                            fontSize = 12.sp,
                            color = muted,
                        )
                    }
                    Switch(checked = !excluded, onCheckedChange = null)
                }
            }

            Spacer(Modifier.height(Space.xl))
            val fetching = source == AmountSource.WALLET && walletBusy
            PillButton(
                when {
                    fetching -> "در حال گرفتن موجودی…"
                    source == AmountSource.MANUAL && linkedWallet != null -> "ذخیره مقدار دستی"
                    source == AmountSource.MANUAL -> "ذخیره"
                    linkedSelection -> "گرفتن دوباره موجودی"
                    else -> "چک و ذخیره"
                },
                {
                    // Never greyed out for an unfinished form — a dead button explains nothing —
                    // so a tap that cannot save puts the reason under the field, with the
                    // keyboard out of its way.
                    focus.clearFocus()
                    // After the amount, never before: a name is written onto a row by its key,
                    // and while adding there is no such row until this save makes one.
                    if (source == AmountSource.MANUAL) {
                        manualSubmitted = true
                        if (manualAmount != null) close { onSaveManual(if (inMoney) tomanOfShown(manualAmount) else manualAmount); nameIt() }
                    } else {
                        // Never null here: the network choice always holds one of its options.
                        val option = selectedWallet ?: return@PillButton
                        if (walletAddress.isBlank()) {
                            localWalletError = "آدرس عمومی کیف پول رو وارد کن."
                        } else if (!isWalletAddressFormatValid(option.network, walletAddress)) {
                            localWalletError = "این آدرس با شبکه انتخاب‌شده جور نیست."
                        } else {
                            onSaveWallet(option, walletAddress.trim()) { nameIt(); close(onDismiss) }
                        }
                    }
                },
                Modifier.fillMaxWidth(),
                voice = ButtonVoice.PRIMARY,
                // Only while a balance is being fetched, and the label says so: a second tap
                // would send the same address again.
                enabled = !fetching,
                block = true,
            )

            onLend?.let { lend ->
                PillButton(
                    "به کسی قرض دادم",
                    { close(lend) },
                    Modifier
                        .fillMaxWidth()
                        .padding(top = Space.s),
                    block = true,
                )
            }

            // One stray tap must not erase a holding: the first tap only asks.
            if (current != null) SheetDelete("حذف این دارایی") { close(onDelete) }

            Spacer(Modifier.height(Space.l))
        }
    }
}
