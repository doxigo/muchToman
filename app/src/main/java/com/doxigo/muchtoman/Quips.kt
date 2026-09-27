package com.doxigo.muchtoman

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonObject
import kotlin.random.Random

/**
 * The line a notification can carry above its facts, in the voice she picked — and the one note
 * that exists only in that voice, the one that asks after her when the ledger goes quiet.
 *
 * ## Where the lines come from
 *
 * `worker/src/quips.json`, riding the /rates payload. A new joke is a deploy of the rates Worker,
 * not a release of this app, and reaches her on the next rates fetch; the phone keeps the last set
 * it heard with the rest of that payload. Before the first fetch there is simply no line, and every
 * note says what it said before this file existed.
 *
 * ## What never changes
 *
 * The title. It is always the plain sentence — «رستوران و کافه: از بودجهٔ مهر گذشتی» — and the plain
 * body stays under the line. The line goes on top because a collapsed note shows only its first
 * line and a joke nobody sees is not one; the figure is in the title and one pull away. And at
 * [QuipTone.PLAIN], the default, nothing here says anything at all.
 *
 * ## What the phone refuses
 *
 * Whatever it cannot place: a moment it does not know (a newer Worker's — so a new moment is safe
 * to add before any app says it), a tone it does not know, a category that is not an id, a text too
 * long for the shade. A line naming a placeholder this moment cannot fill — `{name}` when she never
 * gave one — is skipped when it would be said, not dropped here. `worker/test/quips.test.ts` holds
 * the file to all of this before it ships, so the refusals are a backstop, not the plan.
 */

/** How the notes talk. Ordered: a tone says every line at or below it. */
enum class QuipTone(val fa: String) {
    PLAIN("ساده"),
    WITTY("شوخ"),
    ROAST("بی‌تعارف");

    companion object {
        fun of(name: String?): QuipTone = entries.firstOrNull { it.name == name } ?: PLAIN

        /** quips.json spells them in lower case; «plain» is not a line's tone, it is the absence of one. */
        internal fun wire(tone: String): QuipTone? =
            entries.firstOrNull { it != PLAIN && it.name.lowercase() == tone }
    }
}

/** One line as the Worker sends it. [category] limits it to one category's budget; blank is any. */
@Serializable
data class Quip(val tone: String = "", val category: String = "", val text: String = "")

/**
 * The tone sheet's caption. It names the quiet note because that note only exists past ساده, and
 * says بی‌تعارف can sting because that is the choice being made.
 */
const val QUIP_TONE_CAPTION =
    "با «شوخ» کنار هر اعلان یه تیکه هم می‌ندازیم، با «بی‌تعارف» تیکه‌ها ممکنه بسوزونه. " +
        "با هر دوتاش، اگه چند روز خرجی نبینیم حالت رو می‌پرسیم."

/**
 * [Rates.quips] as the wire has it, decoded so that nothing in it can fail the prices around it.
 *
 * `/rates` is decoded strictly, and a strict decode of a hand-edited joke file would make one
 * malformed line — a number where a string goes, an object where a list goes — cost every phone
 * its prices until the next deploy. Here a moment that is not a list is dropped, a line that does
 * not decode is dropped, and the rest arrives; [sanitizeQuips] then judges what is left.
 */
internal object LenientQuips : KSerializer<Map<String, List<Quip>>?> {
    private val typed = MapSerializer(String.serializer(), ListSerializer(Quip.serializer())).nullable
    override val descriptor = typed.descriptor
    override fun serialize(encoder: Encoder, value: Map<String, List<Quip>>?) = typed.serialize(encoder, value)
    override fun deserialize(decoder: Decoder): Map<String, List<Quip>>? {
        val json = decoder as? JsonDecoder ?: return typed.deserialize(decoder)
        val raw = json.decodeJsonElement() as? JsonObject ?: return null
        return raw.mapNotNull { (event, lines) ->
            val list = lines as? JsonArray ?: return@mapNotNull null
            event to list.mapNotNull { runCatching { json.json.decodeFromJsonElement(Quip.serializer(), it) }.getOrNull() }
        }.toMap()
    }
}

/** The moments a line can be said at. Their placeholders are listed in the Worker's test. */
val QUIP_EVENTS = setOf("budget_near", "budget_over", "installment", "quiet")

private const val MAX_QUIPS_PER_EVENT = 200
private const val MAX_QUIP_LENGTH = 140
private val CATEGORY_ID = Regex("cat_[a-z_]{1,40}")
private val PLACEHOLDER = Regex("""\{([a-z]+)\}""")

/** Null stays null — «the Worker said nothing» — so [mergeRates] can keep the set it had. */
internal fun sanitizeQuips(raw: Map<String, List<Quip>>?): Map<String, List<Quip>>? = raw
    ?.filterKeys { it in QUIP_EVENTS }
    ?.mapValues { (_, lines) ->
        lines.asSequence()
            .map { it.copy(text = it.text.trim()) }
            .filter { quip ->
                QuipTone.wire(quip.tone) != null &&
                    (quip.category.isEmpty() || CATEGORY_ID.matches(quip.category)) &&
                    quip.text.isNotEmpty() &&
                    quip.text.length <= MAX_QUIP_LENGTH &&
                    quip.text.none { it.isISOControl() }
            }
            .distinctBy { it.text }
            .take(MAX_QUIPS_PER_EVENT)
            .toList()
    }

/**
 * What a line is remembered by: Java's own string hash, in base 36. quips.ts computes the same one,
 * so the two can be checked against each other; editing a line's text makes it a new line, which is
 * what it is.
 */
internal fun quipKey(text: String): String = text.hashCode().toString(36)

/** [text] with every `{placeholder}` filled, or null when one has no value to fill it with. */
internal fun fillQuip(text: String, vars: Map<String, String>): String? {
    var missing = false
    val filled = PLACEHOLDER.replace(text) { match ->
        vars[match.groupValues[1]]?.takeIf { it.isNotBlank() } ?: run { missing = true; "" }
    }
    return filled.takeUnless { missing }
}

/**
 * One line for [event], and the set of lines said to store afterwards — or null when she chose
 * [QuipTone.PLAIN] or nothing fits.
 *
 * A shuffle bag: nothing is said twice until everything that fits has been said once, and then the
 * bag refills. Jokes die on repeat faster than any other copy, and a random pick with no memory
 * repeats within a week on a pool of twenty.
 *
 * [category] is the budget's own category id, or null; a line kept for another category is out,
 * a line for every category is in. Both at once, rather than preferring the specific ones — a
 * restaurant budget would otherwise hear its two restaurant lines on a loop.
 */
internal fun pickQuip(
    quips: Map<String, List<Quip>>,
    event: String,
    tone: QuipTone,
    category: String?,
    vars: Map<String, String>,
    seen: Set<String>,
    random: Random = Random.Default,
): Pair<String, Set<String>>? {
    if (tone == QuipTone.PLAIN) return null
    val pool = quips[event].orEmpty().mapNotNull { quip ->
        val lineTone = QuipTone.wire(quip.tone) ?: return@mapNotNull null
        if (lineTone > tone || (quip.category.isNotEmpty() && quip.category != category)) return@mapNotNull null
        fillQuip(quip.text, vars)?.let { quipKey(quip.text) to it }
    }
    if (pool.isEmpty()) return null
    val fresh = pool.filter { it.first !in seen }
    val (key, line) = fresh.ifEmpty { pool }.random(random)
    val kept = if (fresh.isEmpty()) seen - pool.mapTo(HashSet()) { it.first } else seen
    return line to (kept + key)
}

/** The body with her line on top, or the body as it was. */
fun withQuip(quip: String?, body: String): String = if (quip == null) body else "$quip\n$body"

/**
 * [pickQuip] against what this phone holds, remembering the line as said. `{name}` is always on
 * offer, from the name she gave.
 *
 * [Store.quipsSeen] is get-then-set, so this runs under [ledgerGate] like the marks beside it —
 * every caller is an announce helper that already does.
 */
fun Store.takeQuip(event: String, category: String? = null, vars: Map<String, String> = emptyMap()): String? {
    val quips = cachedRates.quips ?: return null
    val (line, seen) = pickQuip(quips, event, quipTone, category, vars + ("name" to name), quipsSeen) ?: return null
    // Pruned to the lines that still exist, so a set that is edited for years never grows past it.
    val live = quips.values.flatten().mapTo(HashSet()) { quipKey(it.text) }
    quipsSeen = seen intersect live
    return line
}

// ─────────────────────────── the quiet note ───────────────────────────

/** Days without a spend before the app asks after her. */
const val QUIET_AFTER_DAYS = 5

/** A ledger quiet this long is one she stopped keeping, not a spell worth asking about. */
private const val QUIET_GIVE_UP_DAYS = 30

private const val HOUR_MS = 3_600_000L

/**
 * When she last spent, from her own rows. A household member's spend is theirs — it says nothing
 * about whether this phone is still hearing from her bank, which is half of what the note is for.
 */
fun lastSpendAt(entries: List<LedgerEntry>): Long? = entries
    .filter { !it.duplicate && !it.transfer && (it.txn.signedRial ?: 0L) < 0L && !it.txn.ref.startsWith("f:") }
    .maxOfOrNull { it.txn.at }

/**
 * The days since [lastSpend] when now is the moment to ask, else null.
 *
 * Once per quiet spell — [mark] is the spend it last asked about, so a new spend is a new spell —
 * and only between 10:00 and 21:00 in Tehran: the watch worker wakes every six hours, and «زنده‌ای؟»
 * at three in the morning is not a joke.
 */
fun quietDays(lastSpend: Long?, mark: Long, now: Long): Int? {
    if (lastSpend == null || lastSpend == mark) return null
    val days = ((now - lastSpend) / DAY_MS).toInt()
    val hour = Math.floorMod(now + TEHRAN_OFFSET_MS, DAY_MS) / HOUR_MS
    return days.takeIf { it in QUIET_AFTER_DAYS until QUIET_GIVE_UP_DAYS && hour in 10L..20L }
}

/**
 * How far the dollar has risen since [since], in whole percent and Persian digits — or blank unless
 * it rose at least one, so the line that uses it is skipped rather than saying «۰٪ رفته بالا».
 * [rateHistory] is [Store.rateHistory]: UTC day → Toman per dollar.
 */
fun usdRiseFa(rateHistory: Map<Long, Double>, since: Long, usdNow: Double?): String {
    val then = rateHistory.filterKeys { it <= since / DAY_MS }.maxByOrNull { it.key }?.value
    if (then == null || usdNow == null || then <= 0.0) return ""
    val rise = ((usdNow / then - 1) * 100).toInt() // truncated, like every figure she is shown
    return if (rise >= 1) faNumber(rise.toDouble()) else ""
}

/** The fact, which is also the title: «۵ روزه خرجی ندیدیم». True whether she stopped or the messages did. */
fun quietTitle(days: Int): String = "${faNumber(days.toDouble())} روزه خرجی ندیدیم"

/** The half that is not a joke: where to look when she did spend and it is not here. */
fun quietBody(): String = "اگه خرج کردی و اینجا نیست، «وضعیت دفتر» رو توی تنظیمات ببین."
