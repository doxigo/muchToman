package com.doxigo.muchtoman

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.InetSocketAddress

/**
 * A row's figure, day and member corrected, and a row split between categories: laid over the
 * ledger where it is read, counted by every total, and carried to the rest of the household.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class EditsTest {

    private fun txn(signed: Long, day: Long = 20_000L, minute: Long = 14 * 60 + 3) = Txn(
        ref = "m:one", srcHash = "one", seq = 0, at = tehranDayStart(day) + minute * 60_000L, day = day,
        bank = "MANUAL", accountId = "MANUAL", direction = if (signed > 0) "in" else "out",
        amountRial = kotlin.math.abs(signed), signedRial = signed,
        balanceRial = null, feeRial = null, mask = "", instrument = "unknown",
        merchant = "", merchantNorm = "", refNo = "", printedAt = "",
        channel = "unknown", unitPrinted = "none", inferred = false, parserVer = PARSER_VERSION,
        sourceKind = "manual",
    )

    @Test
    fun `a split this app would not have written reads as no split at all`() {
        assertEquals(listOf("cat_groceries" to 40L, "cat_sweets" to 20L), parseSplit("cat_groceries:40,cat_sweets:20"))
        for (bad in listOf("cat_groceries:60", "cat_a:40,cat_a:20", "cat_a:40,cat_b:0", "cat_a:40,$CAT_TRANSFER:20", "cat_a40,cat_b:20", "")) {
            assertTrue(bad, parseSplit(bad).isEmpty())
        }
        // The row's figure moved after it was split: the parts no longer add up, and a total that
        // counted money the row does not have would be wrong, so it reads as one row again.
        val names = mapOf("cat_a" to "خواربار", "cat_b" to "شیرینی")
        assertEquals(2, splitOf("cat_a:40,cat_b:20", 60L, names).size)
        assertTrue(splitOf("cat_a:40,cat_b:20", 50L, names).isEmpty())
    }

    @Test
    fun `an edited row keeps its minute and its direction`() {
        val original = txn(-60_000_000L)
        val edited = editedTxn(original, 50_000_000L, original.day - 3)
        assertEquals(-50_000_000L, edited.signedRial)
        assertEquals(50_000_000L, edited.amountRial)
        assertEquals(original.day - 3, edited.day)
        assertEquals(original.at - tehranDayStart(original.day), edited.at - tehranDayStart(edited.day))
        assertEquals(original, editedTxn(original, null, null))
    }

    @Test
    fun `a split row counts under each part and is still one transaction`() {
        val row = LedgerEntry(
            txn = txn(-60_000_000L), categoryId = "cat_groceries", categoryFa = "خواربار",
            confidence = 100, needsReview = false, duplicate = false, transfer = false,
            split = listOf(SplitPart("cat_groceries", "خواربار", 40_000_000L), SplitPart("cat_sweets", "شیرینی", 20_000_000L)),
        )
        val month = reportMonthOf(row.txn.day)
        val report = periodReport(listOf(row), ReportRange(month, month))
        assertEquals(60_000_000L, report.spentRial)
        assertEquals(listOf("خواربار" to 40_000_000L, "شیرینی" to 20_000_000L), report.spendingByCategory)
        assertEquals(1, report.transactions)
        // Asked about as one purchase, not as two.
        assertEquals(listOf(row), spendableRows(listOf(row)))
    }

    // ── the household ──

    private class Household : AutoCloseable {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val key = newScopeKey()
        val rows = linkedMapOf<String, JSONObject>()
        var seq = 0L
        val phones = mutableListOf<Phone>()

        init {
            // The production Worker's last-write-wins, member id breaking a draw — nothing more.
            server.createContext("/") { exchange ->
                val body = exchange.requestBody.use { it.readBytes().toString(Charsets.UTF_8) }
                val member = exchange.requestHeaders.getFirst("Authorization").removePrefix("Bearer ")
                val response = when {
                    exchange.requestURI.path == "/v1/identity" -> JSONObject()
                    exchange.requestMethod == "POST" -> {
                        val records = JSONObject(body).getJSONArray("records")
                        for (i in 0 until records.length()) {
                            val row = records.getJSONObject(i)
                            val previous = rows[row.getString("id")]
                            if (previous != null && (previous.getLong("updatedAt") > row.getLong("updatedAt") ||
                                    (previous.getLong("updatedAt") == row.getLong("updatedAt") &&
                                        previous.getString("authorMemberId") >= member))) continue
                            rows[row.getString("id")] = row.put("authorMemberId", member).put("seq", ++seq)
                        }
                        JSONObject().put("seq", seq).put("clamped", JSONArray())
                    }
                    else -> {
                        val since = exchange.requestURI.query.substringAfter("since=").substringBefore('&').toLong()
                        val incoming = rows.values.filter { it.getLong("seq") > since }.sortedBy { it.getLong("seq") }
                        JSONObject().put("seq", seq).put("hasMore", false).put("records", JSONArray(incoming))
                    }
                }.toString().toByteArray()
                exchange.sendResponseHeaders(200, response.size.toLong())
                exchange.responseBody.use { it.write(response) }
            }
            server.start()
        }

        suspend fun phone(letter: Char): Phone {
            val member = letter.toString().repeat(32)
            val durable = Room.inMemoryDatabaseBuilder(context, DurableDb::class.java).build()
            val derived = Room.inMemoryDatabaseBuilder(context, DerivedDb::class.java).build()
            val session = SyncSession("http://127.0.0.1:${server.address.port}", member,
                (letter + 2).toString().repeat(32), member, "family:test", key)
            saveSession(durable, session)
            seedBuiltins(durable)
            return Phone(durable, derived, session).also { phones += it }
        }

        override fun close() {
            server.stop(0)
            phones.forEach { it.derived.close(); it.durable.close() }
        }
    }

    private class Phone(val durable: DurableDb, val derived: DerivedDb, val session: SyncSession) {
        val me get() = session.member

        suspend fun sync() {
            derive(durable, derived, emptyMap())
            syncNow(durable, derived, session)
            derive(durable, derived, emptyMap())
        }

        suspend fun only(): LedgerEntry = ledgerEntries(derived, durable).entries.single()
    }

    @Test
    fun `her corrections and her split reach the household, and the whole row comes back`() = runBlocking {
        Household().use { home ->
            val a = home.phone('a')
            val b = home.phone('b')
            for (p in listOf(a, b)) for (m in listOf(a, b)) {
                p.durable.familyMembers().put(FamilyMember(m.me, m.me.take(1), updatedAt = 1))
            }
            val now = System.currentTimeMillis()
            val day = tehranDay(now) - 5
            a.durable.manual().put(
                ManualTxn("one", tehranDayStart(day) + 60_000L, day, -60_000_000L, createdAt = now, updatedAt = now)
            )
            a.sync(); b.sync()
            assertEquals(60_000_000L, b.only().txn.amountRial)
            assertEquals(a.me, b.only().ownerMemberId)

            // A fixes the figure and the day, and says it was B's spending.
            derive(a.durable, a.derived, emptyMap())
            editRow(a.durable, a.only(), 50_000_000L, day - 2, b.me, a.me)
            val fixed = a.only()
            assertTrue(fixed.edited)
            assertEquals(50_000_000L, fixed.txn.amountRial)
            assertEquals(day - 2, fixed.txn.day)
            assertEquals(b.me, fixed.ownerMemberId)
            // The balance reads the row as it was typed: corrections are laid over the view only.
            assertEquals(-60_000_000L, a.derived.txn().forAccount("MANUAL").single().signedRial)

            a.sync(); b.sync()
            val there = b.only()
            assertEquals(-50_000_000L, there.txn.signedRial)
            assertEquals(day - 2, there.txn.day)
            assertEquals(b.me, there.ownerMemberId)

            // B splits A's row — filing is anybody's, as a category is.
            assertFalse(splitRow(b.durable, there, listOf("cat_groceries" to 40_000_000L, "cat_sweets" to 20_000_000L), b.me))
            assertTrue(splitRow(b.durable, there, listOf("cat_groceries" to 30_000_000L, "cat_sweets" to 20_000_000L), b.me))
            b.sync(); a.sync()
            val split = a.only()
            assertEquals("cat_groceries", split.categoryId)
            assertEquals(listOf("cat_groceries" to 30_000_000L, "cat_sweets" to 20_000_000L), split.split.map { it.categoryId to it.rial })

            // A files it whole again, and B's split goes with it.
            splitRow(a.durable, split, emptyList(), a.me)
            a.sync(); b.sync()
            assertTrue(a.only().split.isEmpty())
            assertTrue(b.only().split.isEmpty())

            // Put back to its own member and its own figure: nothing of the corrections remains.
            editRow(a.durable, a.only(), null, null, a.me, a.me)
            revertRow(a.durable, a.only(), a.me)
            a.sync(); b.sync()
            assertFalse(a.only().edited)
            assertEquals(a.me, b.only().ownerMemberId)
            assertEquals(60_000_000L, b.only().txn.amountRial)
        }
    }
}
