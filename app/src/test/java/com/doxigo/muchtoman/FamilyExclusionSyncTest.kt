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

/**
 * The household's report exclusions as one shared record: any member may move the set, the
 * later stamp wins everywhere, and a phone that has never touched it adopts the family's answer
 * rather than talking over it. The same fake server [FamilyBudgetSyncTest] runs against, because
 * the record rides the same wire.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class FamilyExclusionSyncTest {
    private class Household : AutoCloseable {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val key = newScopeKey()
        val rows = linkedMapOf<String, JSONObject>()
        var seq = 0L
        /** Kinds an old deployment refuses — the upgrade path every new record kind must survive. */
        val rejectKinds = mutableSetOf<String>()
        val phones = mutableListOf<Phone>()

        init {
            server.createContext("/") { exchange ->
                val body = exchange.requestBody.use { it.readBytes().toString(Charsets.UTF_8) }
                val member = exchange.requestHeaders.getFirst("Authorization").removePrefix("Bearer ")
                var status = 200
                val response = when {
                    exchange.requestURI.path == "/v1/identity" -> JSONObject()
                    exchange.requestURI.path == "/v1/pair" -> JSONObject().put("secret", "f".repeat(64))
                    exchange.requestMethod == "POST" -> {
                        val records = JSONObject(body).getJSONArray("records")
                        if ((0 until records.length()).any { records.getJSONObject(it).optString("kind") in rejectKinds }) {
                            status = 400
                            JSONObject().put("code", "invalid_kind")
                        } else {
                            // The production Worker's stamp clamp, so the ack's `clamped` answers
                            // are as real as its LWW: a day past this server's clock, no further.
                            val maxStamp = System.currentTimeMillis() + 24L * 60 * 60 * 1000
                            val clamped = JSONArray()
                            for (i in 0 until records.length()) {
                                val row = records.getJSONObject(i)
                                val at = minOf(row.getLong("updatedAt"), maxStamp)
                                if (at != row.getLong("updatedAt")) {
                                    row.put("updatedAt", at)
                                    clamped.put(JSONObject().put("id", row.getString("id")).put("updatedAt", at))
                                }
                                val previous = rows[row.getString("id")]
                                if (previous != null && (previous.getLong("updatedAt") > at ||
                                    (previous.getLong("updatedAt") == at && previous.getString("authorMemberId") >= member))) continue
                                rows[row.getString("id")] = row.put("authorMemberId", member).put("seq", ++seq)
                            }
                            JSONObject().put("seq", seq).put("clamped", clamped)
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

        val base: String get() = "http://127.0.0.1:${server.address.port}"

        suspend fun phone(letter: Char): Phone {
            val member = letter.toString().repeat(32)
            val durable = Room.inMemoryDatabaseBuilder(context, DurableDb::class.java).build()
            val derived = Room.inMemoryDatabaseBuilder(context, DerivedDb::class.java).build()
            val session = SyncSession(base, member,
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

        /** The half [AppVm.setReportExcluded] stamps into meta, minus the screen it also updates. */
        suspend fun exclude(ids: Set<String>, at: Long) {
            val previous = readReportExclusions(durable)
            writeReportExclusions(durable, ReportExclusionsState(
                ids = safeExcludedCategoryIds(ids),
                updatedAt = maxOf(at, (previous?.updatedAt ?: 0L) + 1L),
                editedByMemberId = session.member,
            ))
        }

        suspend fun excluded(): Set<String>? = readReportExclusions(durable)?.ids?.toSet()
    }

    @Test
    fun `an exclusion edit reaches the family, and the fresh phone adopts it without echoing`() = runBlocking {
        Household().use { home ->
            val a = home.phone('a')
            val b = home.phone('b')
            b.sync()
            // b has never touched the set: nothing of b's may go out and talk over the family.
            assertNull(b.excluded())
            assertFalse(home.rows.containsKey(REPORT_EXCLUSIONS_RECORD_ID))

            a.exclude(setOf("cat_cafes", "cat_loan"), at = 1_000)
            a.sync(); b.sync()
            assertEquals(setOf("cat_cafes", "cat_loan"), b.excluded())
            assertEquals(a.session.member, readReportExclusions(b.durable)?.editedByMemberId)

            // Settled is silent: neither phone republishes a set it has only received or already
            // sent, so the record's server row does not move again.
            val settled = home.rows[REPORT_EXCLUSIONS_RECORD_ID]!!.getLong("seq")
            b.sync(); a.sync(); b.sync()
            assertEquals(settled, home.rows[REPORT_EXCLUSIONS_RECORD_ID]!!.getLong("seq"))

            // «Count everything» is an ordinary value, not a retraction: an emptied set lands on
            // the other phone as the empty set rather than resurrecting the default.
            b.exclude(emptySet(), at = 2_000)
            b.sync(); a.sync()
            assertEquals(emptySet<String>(), a.excluded())
        }
    }

    @Test
    fun `the later edit wins everywhere, and a same-instant draw breaks the same way on both phones`() = runBlocking {
        Household().use { home ->
            val a = home.phone('a')
            val b = home.phone('b')
            a.exclude(setOf("cat_one"), at = 5_000)
            b.exclude(setOf("cat_two"), at = 9_000)
            a.sync(); b.sync(); a.sync()
            assertEquals(setOf("cat_two"), a.excluded())
            assertEquals(setOf("cat_two"), b.excluded())

            // The same millisecond from both sides: the higher member id wins, in either arrival
            // order, because the server and every phone break the draw with the same rule.
            a.exclude(setOf("cat_a"), at = 20_000)
            b.exclude(setOf("cat_b"), at = 20_000)
            assertEquals(20_000L, readReportExclusions(a.durable)?.updatedAt)
            assertEquals(20_000L, readReportExclusions(b.durable)?.updatedAt)
            a.sync(); b.sync(); a.sync(); b.sync()
            assertEquals(setOf("cat_b"), a.excluded())
            assertEquals(setOf("cat_b"), b.excluded())
        }
    }

    @Test
    fun `an old server refuses the kind without blocking the rest, and the set lands after the upgrade`() = runBlocking {
        Household().use { home ->
            val a = home.phone('a')
            val b = home.phone('b')
            b.sync()
            a.durable.familyMembers().put(FamilyMember(a.session.member, "سهیل", avatar = AVATAR_MAN, updatedAt = 200))
            a.exclude(setOf("cat_cafes"), at = 1_000)
            home.rejectKinds += "exclusion"
            val refused = a.sync()
            assertEquals(setOf("exclusion"), refused.unsupportedKinds)
            // Nothing was marked sent, so the upgrade retry has something to send…
            assertFalse(a.durable.syncPublications().all().any { it.sourceKind == "exclusion" })
            // …and the profile still went through: one refused kind must not block the others.
            b.sync()
            assertEquals(AVATAR_MAN, b.durable.familyMembers().get(a.session.member)?.avatar)
            assertNull(b.excluded())

            home.rejectKinds.clear()
            assertTrue(a.sync().unsupportedKinds.isEmpty())
            b.sync()
            assertEquals(setOf("cat_cafes"), b.excluded())
        }
    }

    @Test
    fun `a forged exclusion tombstone is ignored, whatever it is stamped`() = runBlocking {
        Household().use { home ->
            val a = home.phone('a')
            val b = home.phone('b')
            a.exclude(setOf("cat_cafes"), at = 1_000)
            a.sync(); b.sync()
            assertEquals(setOf("cat_cafes"), b.excluded())
            // A compromised server — or a hostile push the real Worker now refuses — plants a
            // deleted envelope whose sealed body authenticates and names the record, stamped past
            // every honest edit. The whole no-tombstone design rests on phones refusing it.
            val (nonce, sealedBody) = seal(home.key, """{"v":1,"id":"$REPORT_EXCLUSIONS_RECORD_ID","deleted":true}""")
            home.rows[REPORT_EXCLUSIONS_RECORD_ID] = JSONObject()
                .put("id", REPORT_EXCLUSIONS_RECORD_ID)
                .put("scope", "family:test")
                .put("updatedAt", System.currentTimeMillis() + 60_000)
                .put("device", "z".repeat(32))
                .put("kind", "exclusion")
                .put("ownerMemberId", a.session.member)
                .put("authorMemberId", a.session.member)
                .put("deleted", true)
                .put("nonce", nonce)
                .put("body", sealedBody)
                .put("seq", ++home.seq)
            b.sync()
            assertEquals(setOf("cat_cafes"), b.excluded())
        }
    }

    @Test
    fun `a clamped stamp lands back on the state, so a fast clock cannot republish for ever`() = runBlocking {
        Household().use { home ->
            val a = home.phone('a')
            val b = home.phone('b')
            val farAhead = System.currentTimeMillis() + 30L * 24 * 60 * 60 * 1000
            a.exclude(setOf("cat_cafes"), at = farAhead)
            a.sync()
            // The server said what it stored, and the state takes its word alongside the
            // publication mark — left ahead of it, this phone would republish on every sync.
            assertTrue(readReportExclusions(a.durable)!!.updatedAt < farAhead)
            val settled = home.rows[REPORT_EXCLUSIONS_RECORD_ID]!!.getLong("seq")
            a.sync()
            assertEquals(settled, home.rows[REPORT_EXCLUSIONS_RECORD_ID]!!.getLong("seq"))
            b.sync()
            assertEquals(setOf("cat_cafes"), b.excluded())
        }
    }

    @Test
    fun `joining a household puts the local stamp down so the family's set is adopted`() = runBlocking {
        Household().use { home ->
            val a = home.phone('a')
            a.exclude(setOf("cat_mine"), at = 99_000_000_000_000)
            // The link a founder's QR carries, aimed at the fake server's /v1/pair.
            val link = "${home.base}/join#url=${android.net.Uri.encode(home.base)}" +
                "&hid=${"d".repeat(32)}&pair=code&scope=${android.net.Uri.encode("family:test")}" +
                "&k=${b64Url(home.key)}"
            joinHousehold(link, a.durable, "من", allowedBase = home.base)
            // However fresh her stamp was, a join is walking into a settled household: her set
            // stays hers to read, but it no longer speaks for the family she just joined.
            assertNull(readReportExclusions(a.durable))
        }
    }

    @Test
    fun `the wire form is canonical, so a received set republishes to the very same bytes`() {
        // Sorted, deduplicated, blanks and control characters out, each id capped — the exact
        // shape [applyReportExclusions] stores, so hash comparison is byte equality.
        assertEquals(
            listOf("cat_a", "cat_b"),
            safeExcludedCategoryIds(listOf("cat_b", "cat_a", "cat_b", " ", "cat\u0000_a")),
        )
        assertEquals(80, safeExcludedCategoryIds(listOf("x".repeat(200))).single().length)
        val received = listOf("cat_z", "cat_m")
        assertEquals(
            reportExclusionsPayload(safeExcludedCategoryIds(received), "m1"),
            reportExclusionsPayload(received, "m1"),
        )
        // Four hundred ids is the ceiling; the four-hundred-first cannot wedge the push.
        assertEquals(400, safeExcludedCategoryIds((1..500).map { "cat_%04d".format(it) }).size)
    }
}
