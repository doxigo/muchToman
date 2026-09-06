package com.doxigo.muchtoman

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.InetSocketAddress

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class FamilyBudgetSyncTest {
    private class Household : AutoCloseable {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val key = newScopeKey()
        val rows = linkedMapOf<String, JSONObject>()
        var seq = 0L
        var rejectGoals = false
        val phones = mutableListOf<Phone>()

        init {
            server.createContext("/") { exchange ->
                val body = exchange.requestBody.use { it.readBytes().toString(Charsets.UTF_8) }
                val member = exchange.requestHeaders.getFirst("Authorization").removePrefix("Bearer ")
                var status = 200
                val response = when {
                    exchange.requestURI.path == "/v1/identity" -> JSONObject()
                    exchange.requestMethod == "POST" -> {
                        val records = JSONObject(body).getJSONArray("records")
                        if (rejectGoals && (0 until records.length()).any { records.getJSONObject(it).optString("kind") == "goal" }) {
                            status = 400
                            JSONObject().put("code", "invalid_kind")
                        } else {
                            for (i in 0 until records.length()) {
                                val row = records.getJSONObject(i)
                                val previous = rows[row.getString("id")]
                                val at = row.getLong("updatedAt")
                                if (previous != null && (previous.getLong("updatedAt") > at ||
                                    (previous.getLong("updatedAt") == at && previous.getString("authorMemberId") >= member))) continue
                                rows[row.getString("id")] = row.put("authorMemberId", member).put("seq", ++seq)
                            }
                            JSONObject().put("seq", seq)
                        }
                    }
                    else -> {
                        val since = exchange.requestURI.query.substringAfter("since=").substringBefore('&').toLong()
                        val incoming = rows.values.filter { it.getLong("seq") > since }.sortedBy { it.getLong("seq") }
                        JSONObject().put("seq", seq).put("hasMore", false).put("records", JSONArray(incoming))
                    }
                }.toString().toByteArray()
                exchange.sendResponseHeaders(status, response.size.toLong())
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
            durable.familyMembers().put(FamilyMember(member, member.take(1), updatedAt = 1))
            return Phone(durable, derived, session).also { phones += it }
        }

        override fun close() {
            server.stop(0)
            phones.forEach { it.derived.close(); it.durable.close() }
        }
    }

    private class Phone(val durable: DurableDb, val derived: DerivedDb, val session: SyncSession) {
        suspend fun sync(): SyncResult {
            derive(durable, derived, emptyMap())
            val result = syncNow(durable, derived, session)
            derive(durable, derived, emptyMap())
            return result
        }
        fun budget(id: String, cap: Long, category: String? = null) = Goal(
            id, category ?: BUDGET_TOTAL_FA, cap, GoalKind.CAP, category, GoalPeriod.MONTH,
            tehranDay(System.currentTimeMillis()), createdAt = 100, updatedAt = 100,
            shared = true, ownerMemberId = session.member, editedByMemberId = session.member,
        )
    }

    @Test
    fun `unsupported budgets do not block avatars or incoming records and retry after upgrade`() = runBlocking {
        Household().use { home ->
            val a = home.phone('a')
            val b = home.phone('b')
            b.sync()
            a.durable.familyMembers().put(FamilyMember(a.session.member, "سهیل", avatar = AVATAR_MAN, updatedAt = 200))
            a.durable.goals().put(a.budget("total", 200_000_000))
            home.rejectGoals = true
            val result = a.sync()
            assertEquals(setOf("goal"), result.unsupportedKinds)
            assertNotNull(a.durable.familyMembers().get(b.session.member))
            assertFalse(a.durable.syncPublications().all().any { it.sourceKind == "goal" })
            b.sync()
            assertEquals(AVATAR_MAN, b.durable.familyMembers().get(a.session.member)?.avatar)
            home.rejectGoals = false
            assertTrue(a.sync().unsupportedKinds.isEmpty())
            b.sync()
            assertEquals(200_000_000L, b.durable.goals().byId("total")?.targetRial)
        }
    }

    @Test
    fun `shared category and total budgets reach both phones and conflicts are resolved everywhere`() = runBlocking {
        Household().use { home ->
            val a = home.phone('a')
            val b = home.phone('b')
            val category = BUILTIN_CATEGORIES.first { it.kind == CategoryKind.EXPENSE && it.id !in PASS_THROUGH_CATEGORIES }.id
            a.durable.goals().put(a.budget("dining-a", 200_000_000, category))
            a.durable.goals().put(a.budget("total", 500_000_000))
            b.durable.goals().put(b.budget("dining-b", 100_000_000, category))
            val now = System.currentTimeMillis()
            a.durable.manual().put(ManualTxn("meal", now, tehranDay(now), -113_000_000,
                categoryId = category, createdAt = now, updatedAt = now))
            a.sync(); b.sync(); a.sync()
            assertEquals(3, a.durable.goals().active().size)
            assertEquals(3, b.durable.goals().active().size)
            val av = ledgerView(a.derived, a.durable)
            val bv = ledgerView(b.derived, b.durable)
            assertEquals(av.budgets.associate { it.goal.id to it.spentRial }, bv.budgets.associate { it.goal.id to it.spentRial })
            assertTrue(av.budgets.all { it.spentRial == 113_000_000L })
            keepBudget(b.durable, "dining-a", now + 10)
            b.sync(); a.sync()
            assertEquals(setOf("total", "dining-a"), a.durable.goals().active().map { it.id }.toSet())
            assertEquals(a.durable.goals().active(), b.durable.goals().active())
        }
    }

    @Test
    fun `simultaneous choices converge without deleting both budgets`() = runBlocking {
        Household().use { home ->
            val a = home.phone('a')
            val b = home.phone('b')
            a.durable.goals().put(a.budget("one", 100))
            b.durable.goals().put(b.budget("two", 200))
            a.sync(); b.sync(); a.sync()
            val now = System.currentTimeMillis()
            keepBudget(a.durable, "one", now)
            keepBudget(b.durable, "two", now)
            a.sync(); b.sync(); a.sync(); b.sync()
            assertEquals(listOf("two"), a.durable.goals().active().map { it.id })
            assertEquals(a.durable.goals().active(), b.durable.goals().active())
        }
    }

    @Test
    fun `excluding a bank and disabling SMS sharing retracts its spending on both phones`() = runBlocking {
        Household().use { home ->
            val a = home.phone('a')
            val b = home.phone('b')
            val now = System.currentTimeMillis()
            a.durable.meta().put(DurableMeta(META_SYNC_SHARE_SMS, "true"))
            a.durable.smsSource().insertAll(listOf(SmsSource(
                srcHash = "bank-spend", sender = "100031", addrKey = "100031",
                body = "بانک رفاه برداشت مبلغ 18,000,000 ریال مانده 10,000,000 ریال",
                at = now, ingestedAt = now,
            )))
            a.durable.goals().put(a.budget("total", 200_000_000))
            suspend fun spent(phone: Phone) = ledgerView(phone.derived, phone.durable).budgets.single().spentRial
            a.sync(); b.sync()
            assertEquals(18_000_000L, spent(a))
            assertEquals(spent(a), spent(b))
            a.durable.meta().put(DurableMeta(META_SYNC_EXCLUDED_BANKS, "REFAH"))
            a.sync(); b.sync()
            assertEquals(0L, spent(a))
            assertEquals(spent(a), spent(b))
            assertEquals(1, ledgerEntries(a.derived, a.durable).entries.size)
            a.durable.meta().put(DurableMeta(META_SYNC_EXCLUDED_BANKS, ""))
            a.sync(); b.sync()
            assertEquals(18_000_000L, spent(b))
            a.durable.meta().put(DurableMeta(META_SYNC_SHARE_SMS, "false"))
            a.sync(); b.sync()
            assertEquals(0L, spent(a))
            assertEquals(spent(a), spent(b))
        }
    }

    @Test
    fun `server rejection is not reported as no internet`() {
        val error = SyncHttpException(400, "{\"code\":\"invalid_kind\"}")
        assertEquals("invalid_kind", error.code)
        assertFalse(syncErrorFa(error).contains("اینترنت"))
        assertTrue(syncErrorFa(java.net.SocketTimeoutException()).contains("اینترنت"))
    }
}
