package com.doxigo.muchtoman

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.absoluteValue
import kotlin.math.roundToLong

/**
 * The golden corpus: real bank messages, kept verbatim, each with the reading it must produce.
 *
 * It lives as JSON under `src/test/resources/sms`, one file per bank, rather than in Kotlin, so
 * that a second implementation — the PWA's quick-paste parser — can be held to the same
 * expectations from the same file, and so that adding a bank is a new file rather than a merge
 * conflict.
 *
 * Two rules make it safe to grow:
 *
 *  - **An absent key is not asserted.** A case that says nothing about `mask` is silent about
 *    `mask`, so a new capability is added by adding keys, never by rewriting what is here. A
 *    key present with `null` is a real assertion that the field is empty.
 *  - **An unknown key fails.** Otherwise `"balanceRail"` would quietly assert nothing at all
 *    and the net would have a hole in it shaped exactly like a typo.
 *
 * The `why` on every case is the comment that used to sit above it in [MoneyTest], carried
 * along so it prints in the failure message. Knowing which real phone produced a shape is worth
 * more when a test breaks than a stack trace is.
 */
class SmsCorpusTest {

    @Test
    fun `every corpus case parses to its recorded expectation`() {
        var checked = 0
        for (file in corpusFiles()) {
            val corpus = load(file)
            assertEquals("${file.name}: unknown corpus version", 1, corpus.version)
            assertTrue("${file.name} holds no cases", corpus.cases.isNotEmpty())
            for (case in corpus.cases) {
                check(file.name, case)
                checked++
            }
        }
        // Not a target, a tripwire: the corpus is read off the classpath, and the failure mode
        // of that going wrong is an empty list and a green run.
        assertTrue("the corpus did not load — ran only $checked cases", checked >= 30)
    }

    @Test
    fun `every case id is unique across the whole corpus`() {
        // Ids are the cross-language contract. Two cases sharing one means the TypeScript side
        // cannot say which expectation it disagrees with.
        val ids = corpusFiles().flatMap { f -> load(f).cases.map { it.id } }
        val duplicated = ids.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        assertTrue("duplicate case ids: $duplicated", duplicated.isEmpty())
        assertTrue("no case ids at all", ids.isNotEmpty())
    }

    private fun check(fileName: String, case: CorpusCase) {
        val where = "$fileName#${case.id}\n  ${case.why}\n "
        val read = parseBankSms(
            case.sender,
            case.body.joinToString("\n"),
            case.at,
            extraLookup(case.extra),
        )

        val expect = case.expect
        if (expect == null) {
            assertNull("$where expected this message to be declined outright", read)
            return
        }
        assertNotNull("$where expected a reading, got none", read)
        read!!

        val unknown = expect.keys - EXPECT_KEYS
        assertTrue("$where asserts nothing: unknown keys $unknown", unknown.isEmpty())

        fun stated(key: String): JsonPrimitive? = (expect[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }
        fun string(key: String, actual: String?) {
            if (key in expect) assertEquals("$where ($key)", stated(key)?.content, actual)
        }
        fun rial(key: String, actual: Long?) {
            if (key in expect) assertEquals("$where ($key)", stated(key)?.content?.toLong(), actual)
        }
        fun flag(key: String, actual: Boolean) {
            if (key in expect) assertEquals("$where ($key)", stated(key)?.content?.toBooleanStrict(), actual)
        }

        string("bank", read.bank.name)
        string("mask", read.mask)
        string("direction", read.delta?.let { if (it > 0) "in" else "out" })
        rial("amountRial", read.amountRial)
        rial("balanceRial", read.balanceRial)
        rial("feeRial", read.feeRial)
        flag("inferred", read.inferred)
        string("merchant", read.merchant)
        string("refNo", read.refNo)
        string("printedAt", read.printedAt)
        string("channel", read.channel.name.lowercase())
        string("instrument", read.instrument.name.lowercase())
        string("unitPrinted", read.unitPrinted.name.lowercase())

        // **This is the guarantee that enrichment is read-only with respect to the money.**
        //
        // The integer Rial the ledger will store and the Toman double the balance fold has
        // always used must be the same number, on every case, for ever. A drift of more than
        // half a Rial fails — which is both the check worth having and the reason the ledger
        // stores Rial as a Long rather than Toman as a Double: 3,391,606.7 is a rounding
        // decision waiting to break exact-amount transfer matching.
        read.delta?.let {
            assertEquals("$where (delta moved)", (it.absoluteValue * 10).roundToLong(), read.amountRial)
        }
        read.balance?.let {
            assertEquals("$where (balance moved)", (it * 10).roundToLong(), read.balanceRial)
        }
    }

    private fun load(file: File): CorpusFile = CORPUS_JSON.decodeFromString(file.readText())

    private fun corpusFiles(): List<File> {
        val dir = checkNotNull(javaClass.getResource("/sms")) {
            "app/src/test/resources/sms is not on the test classpath"
        }
        val files = checkNotNull(File(dir.toURI()).listFiles { f: File -> f.name.endsWith(".json") }) {
            "no corpus files under $dir"
        }
        return files.sortedBy { it.name }
    }
}

/** Fields a case may assert. Anything else is a typo, and typos assert nothing silently. */
private val EXPECT_KEYS = setOf(
    "bank", "mask", "direction", "amountRial", "balanceRial", "inferred",
    "feeRial", "merchant", "refNo", "printedAt", "channel", "instrument", "unitPrinted",
)

private val CORPUS_JSON = Json {
    // Left strict on purpose: a misspelt field at the case level should fail the run rather
    // than drop an expectation on the floor.
    ignoreUnknownKeys = false
}

@Serializable
private data class CorpusFile(val version: Int, val cases: List<CorpusCase>)

@Serializable
private data class CorpusCase(
    val id: String,
    val sender: String,
    val body: List<String>,
    val why: String,
    val at: Long = 1L,
    /** Sender ids she confirmed herself, in the shape [extraLookup] takes. */
    val extra: Map<String, List<String>> = emptyMap(),
    /** `null` means the parser must decline this message. Required — never omitted. */
    val expect: JsonObject?,
)
