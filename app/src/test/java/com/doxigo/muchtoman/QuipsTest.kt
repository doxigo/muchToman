package com.doxigo.muchtoman

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.random.Random

/**
 * The lines on top of a note, and the note that asks after her. quips.ts is held to the same cases
 * in pwa/test/quips.test.ts; the key test pins the one value both must compute identically.
 */
class QuipsTest {

    private val witty = Quip(tone = "witty", text = "شوخی")
    private val roast = Quip(tone = "roast", text = "تیکه")
    private val dining = Quip(tone = "witty", category = "cat_dining", text = "گارسون")
    private val named = Quip(tone = "witty", text = "{name}، سلام")

    private fun pick(
        lines: List<Quip>,
        tone: QuipTone = QuipTone.ROAST,
        category: String? = null,
        vars: Map<String, String> = emptyMap(),
        seen: Set<String> = emptySet(),
    ) = pickQuip(mapOf("budget_over" to lines), "budget_over", tone, category, vars, seen, Random(7))

    @Test
    fun `a phone that never chose, an upgrade included, is witty, and an explicit plain stays plain`() {
        assertEquals(QuipTone.WITTY, QuipTone.of(null))
        assertEquals(QuipTone.WITTY, QuipTone.of("SAVAGE"))
        assertEquals(QuipTone.PLAIN, QuipTone.of("PLAIN"))
        assertEquals(QuipTone.ROAST, QuipTone.of("ROAST"))
    }

    @Test
    fun `plain says nothing, whatever there is to say`() {
        assertNull(pick(listOf(witty, roast), tone = QuipTone.PLAIN))
    }

    @Test
    fun `a tone hears its own lines and the gentler ones, never the harsher`() {
        repeat(20) { assertEquals("شوخی", pick(listOf(witty, roast), tone = QuipTone.WITTY)!!.first) }
        val heard = (0 until 40).map {
            pickQuip(mapOf("budget_over" to listOf(witty, roast)), "budget_over", QuipTone.ROAST, null, emptyMap(), emptySet(), Random(it))!!.first
        }.toSet()
        assertEquals(setOf("شوخی", "تیکه"), heard)
    }

    @Test
    fun `a line kept for a category is only said over that category's budget`() {
        assertEquals("شوخی", pick(listOf(witty, dining), tone = QuipTone.WITTY, category = "cat_groceries", seen = setOf(quipKey("گارسون")))!!.first)
        assertNull(pick(listOf(dining), category = "cat_groceries"))
        assertNull(pick(listOf(dining), category = null))
        assertEquals("گارسون", pick(listOf(dining), category = "cat_dining")!!.first)
    }

    @Test
    fun `a placeholder with nothing to fill it skips the line`() {
        assertNull(pick(listOf(named)))
        assertNull(pick(listOf(named), vars = mapOf("name" to "  ")))
        assertEquals("مریم، سلام", pick(listOf(named), vars = mapOf("name" to "مریم"))!!.first)
    }

    @Test
    fun `nothing is said twice until everything has been said once`() {
        val lines = (1..5).map { Quip(tone = "witty", text = "خط $it") }
        var seen = emptySet<String>()
        val round = (1..5).map {
            val (line, next) = pickQuip(mapOf("quiet" to lines), "quiet", QuipTone.WITTY, null, emptyMap(), seen, Random(it))!!
            seen = next
            line
        }
        assertEquals(lines.map { it.text }.toSet(), round.toSet())
        // The bag refills rather than going silent.
        val (_, refilled) = pickQuip(mapOf("quiet" to lines), "quiet", QuipTone.WITTY, null, emptyMap(), seen, Random(9))!!
        assertEquals(1, refilled.size)
    }

    @Test
    fun `the phone drops what it cannot place`() {
        assertNull(sanitizeQuips(null))
        val clean = sanitizeQuips(
            mapOf(
                "budget_over" to listOf(
                    witty,
                    Quip(tone = "plain", text = "ساده"),
                    Quip(tone = "savage", text = "نه"),
                    Quip(tone = "witty", category = "Dining; drop", text = "نه"),
                    Quip(tone = "witty", text = "خیلی".repeat(40)),
                    Quip(tone = "witty", text = "  "),
                    Quip(tone = "witty", text = "شوخی"),
                ),
                "someday" to listOf(witty),
            ),
        )!!
        assertEquals(mapOf("budget_over" to listOf(witty)), clean)
    }

    @Test
    fun `a malformed line costs that line, never the prices`() {
        val body = """{"updatedAt":1,"toman":{"usd":100000},"quips":{"quiet":[{"tone":"witty","text":"سلام"},{"tone":7,"text":["x"]},"nope"],"budget_over":{"not":"a list"}}}"""
        val rates = sanitizeRates(Json { ignoreUnknownKeys = true }.decodeFromString<Rates>(body), "https://rates.muchtoman.com/rates")
        assertEquals(100_000.0, rates.toman["usd"])
        assertEquals(mapOf("quiet" to listOf(Quip(tone = "witty", text = "سلام"))), rates.quips)
    }

    @Test
    fun `every line the Worker ships survives the phone`() {
        val shipped = Json.decodeFromString<Map<String, List<Quip>>>(File("../worker/src/quips.json").readText())
        assertEquals(shipped.mapValues { (_, lines) -> lines.size }, sanitizeQuips(shipped)!!.mapValues { (_, lines) -> lines.size })
    }

    @Test
    fun `a body from before quips keeps the lines already heard`() {
        val cached = Rates(quips = mapOf("quiet" to listOf(witty)))
        assertEquals(cached.quips, mergeRates(Rates(), cached).quips)
        assertEquals(emptyMap<String, List<Quip>>(), mergeRates(Rates(quips = emptyMap()), cached).quips)
    }

    @Test
    fun `the key is Java's string hash in base 36`() {
        assertEquals("r4mry2", quipKey("زنده‌ای؟"))
        // Negative hashes keep their sign, which JavaScript's toString(36) also does.
        assertEquals("-he7hu0", quipKey("گارسون"))
        assertEquals("0", quipKey(""))
    }

    @Test
    fun `the body keeps its facts under the line`() {
        assertEquals("تیکه\n۲ میلیون مونده", withQuip("تیکه", "۲ میلیون مونده"))
        assertEquals("۲ میلیون مونده", withQuip(null, "۲ میلیون مونده"))
    }

    // ─────────────────────────── the quiet note ───────────────────────────

    private val noon = tehranDayStart(20_000) + 12 * 3_600_000L

    private fun spend(at: Long, ref: String = "s:1:0", signed: Long = -500_000L, transfer: Boolean = false) = LedgerEntry(
        txn = Txn(
            ref = ref, srcHash = ref, seq = 0, at = at, day = tehranDay(at),
            bank = "SAMAN", accountId = "SAMAN", direction = if (signed < 0) "out" else "in",
            amountRial = kotlin.math.abs(signed), signedRial = signed,
            balanceRial = null, feeRial = null, mask = "", instrument = "unknown",
            merchant = "", merchantNorm = "", refNo = "", printedAt = "",
            channel = "unknown", unitPrinted = "none", inferred = false, parserVer = PARSER_VERSION,
        ),
        categoryId = CAT_UNCATEGORISED, categoryFa = "", confidence = Confidence.NONE,
        needsReview = false, duplicate = false, transfer = transfer,
    )

    @Test
    fun `her last spend is her own money going out`() {
        val mine = noon - 9 * DAY_MS
        val entries = listOf(
            spend(mine),
            spend(noon - DAY_MS, ref = "f:abc"), // the household's
            spend(noon - DAY_MS, ref = "s:2:0", signed = 9_000_000L), // salary
            spend(noon - DAY_MS, ref = "s:3:0", transfer = true),
        )
        assertEquals(mine, lastSpendAt(entries))
        assertNull(lastSpendAt(emptyList()))
    }

    @Test
    fun `asked once a spell, in waking hours, and never about a ledger she stopped keeping`() {
        val last = noon - 5 * DAY_MS
        assertEquals(5, quietDays(last, mark = 0L, now = noon))
        assertNull(quietDays(noon - 4 * DAY_MS, mark = 0L, now = noon))
        assertNull(quietDays(last, mark = last, now = noon))
        assertNull(quietDays(noon - 30 * DAY_MS, mark = 0L, now = noon))
        assertNull(quietDays(last, mark = 0L, now = noon - 9 * 3_600_000L)) // 03:00 in Tehran
        assertEquals(5, quietDays(last, mark = 0L, now = noon + 8 * 3_600_000L)) // 20:00
        assertNull(quietDays(null, mark = 0L, now = noon))
    }

    @Test
    fun `the dollar's rise is whole percent, and only a rise`() {
        val day = (noon - 5 * DAY_MS) / DAY_MS
        val history = mapOf(day - 1 to 100_000.0, day + 2 to 999_999.0)
        assertEquals("۴", usdRiseFa(history, noon - 5 * DAY_MS, 104_900.0))
        assertEquals("", usdRiseFa(history, noon - 5 * DAY_MS, 100_900.0))
        assertEquals("", usdRiseFa(history, noon - 5 * DAY_MS, 90_000.0))
        assertEquals("", usdRiseFa(emptyMap(), noon, 104_900.0))
        assertEquals("", usdRiseFa(history, noon - 5 * DAY_MS, null))
    }

    @Test
    fun `the quiet title is the fact`() {
        assertEquals("۵ روزه خرجی ندیدیم", quietTitle(5))
        assertTrue(quietBody().contains("وضعیت دفتر"))
    }
}
