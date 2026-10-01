package com.doxigo.muchtoman

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch

/**
 * The household lifecycle — claim, join by link, rejoin, leave, remove, renew — against a
 * scripted local server. Every test asserts both sides: the HTTP conversation the client held
 * and the durable state it left behind.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SyncLifecycleTest {

    private class RecordedRequest(
        val method: String,
        val path: String,
        val query: String?,
        val auth: String?,
        val body: String,
    )

    /**
     * Records every request and answers from canned lifecycle handlers, unless a scripted
     * response for the path has been queued (used to make one endpoint fail with a 503).
     */
    private class FakeSyncServer {
        val secret = "ab".repeat(32)
        val inviteCode = "deadbeefdeadbeefdeadbeefdeadbeef"
        private val requests = mutableListOf<RecordedRequest>()
        private val scripted = mutableMapOf<String, ArrayDeque<Pair<Int, String>>>()
        /** Pull pages, handed out one per `GET /v1/sync`; an empty pull once they run out. */
        val pulls = ArrayDeque<String>()
        /** A path whose answer waits: the first latch opens when the request arrives, the second lets it go. */
        val held = mutableMapOf<String, Pair<CountDownLatch, CountDownLatch>>()
        private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)

        init {
            server.createContext("/") { exchange ->
                val body = exchange.requestBody.use { it.readBytes() }.toString(Charsets.UTF_8)
                val path = exchange.requestURI.path
                val (status, response) = synchronized(this) {
                    requests.add(
                        RecordedRequest(
                            exchange.requestMethod,
                            path,
                            exchange.requestURI.query,
                            exchange.requestHeaders.getFirst("Authorization"),
                            body,
                        )
                    )
                    scripted[path]?.removeFirstOrNull()
                        ?: pulls.takeIf { path == "/v1/sync" && exchange.requestMethod == "GET" }
                            ?.removeFirstOrNull()?.let { 200 to it }
                } ?: when (path) {
                    "/v1/claim", "/v1/pair" -> 200 to JSONObject().put("secret", secret).toString()
                    "/v1/invite" -> 200 to JSONObject().put("code", inviteCode).toString()
                    else -> 200 to "{}"
                }
                held[path]?.let { (arrived, release) -> arrived.countDown(); release.await() }
                val bytes = response.toByteArray(Charsets.UTF_8)
                exchange.sendResponseHeaders(status, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            server.start()
        }

        val base get() = "http://127.0.0.1:${server.address.port}"

        fun script(path: String, status: Int, body: String = "{}") = synchronized(this) {
            scripted.getOrPut(path) { ArrayDeque() }.add(status to body)
        }

        fun requestsTo(path: String): List<RecordedRequest> =
            synchronized(this) { requests.filter { it.path == path } }

        fun stop() = server.stop(0)
    }

    private fun lifecycle(block: suspend (FakeSyncServer, DurableDb) -> Unit) = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val durable = Room.inMemoryDatabaseBuilder(context, DurableDb::class.java).build()
        val server = FakeSyncServer()
        try {
            block(server, durable)
        } finally {
            server.stop()
            durable.close()
        }
    }

    private fun secondDurable(): DurableDb = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext<Context>(), DurableDb::class.java
    ).build()

    private fun hidOf(session: SyncSession): String = session.token.substringBefore('.')

    private val oldMe = "d".repeat(32)
    private val oldThem = "b".repeat(32)

    private fun goal(id: String, shared: Boolean, owner: String) = Goal(
        id = id, nameFa = id, targetRial = 1_000_000, kind = GoalKind.CAP, period = GoalPeriod.MONTH,
        startsOn = 0, createdAt = 1, updatedAt = 1, shared = shared, ownerMemberId = owner,
    )

    /**
     * A paired phone's durable.db after an encrypted backup came back: the session is stripped
     * ([BACKUP_STRIPPED_META]) and everything else of the old household is still here.
     */
    private suspend fun restoredHousehold(durable: DurableDb, marks: List<SyncPublication>) {
        durable.familyMembers().put(FamilyMember(oldMe, "سهیل", updatedAt = 1000))
        durable.familyMembers().put(FamilyMember(oldThem, "رضا", updatedAt = 1000))
        durable.familyTxns().put(
            FamilyTxn(familyTxnId(oldThem, "m:1"), oldThem, "manual", at = 1000, day = 1, amountRial = -50_000, updatedAt = 1000)
        )
        durable.familyAssets().put(FamilyAsset(oldThem, "[]", 0.0, updatedAt = 1000))
        durable.syncPublications().putAll(marks)
        durable.goals().put(goal("hers-private", shared = false, owner = oldMe))
        durable.goals().put(goal("hers-shared", shared = true, owner = oldMe))
        durable.goals().put(goal("theirs", shared = true, owner = oldThem))
    }

    /** Syncs once and hands back every record pushed under [session]'s token. */
    private suspend fun pushedBy(server: FakeSyncServer, durable: DurableDb, session: SyncSession): List<JSONObject> {
        val derived = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(), DerivedDb::class.java
        ).build()
        try {
            derive(durable, derived, emptyMap())
            syncNow(durable, derived, session)
        } finally {
            derived.close()
        }
        return server.requestsTo("/v1/sync")
            .filter { it.method == "POST" && it.auth == "Bearer ${session.token}" }
            .flatMap { request -> JSONObject(request.body).getJSONArray("records").let { a -> (0 until a.length()).map(a::getJSONObject) } }
    }

    /** A record another phone pushed, sealed under [session]'s key, as a pull page carries it. */
    private fun sealedRecord(
        session: SyncSession,
        id: String,
        kind: String,
        owner: String,
        updatedAt: Long,
        payload: JSONObject,
        deleted: Boolean = false,
    ): JSONObject {
        val (nonce, body) = seal(session.key, payload.toString())
        return JSONObject().put("id", id).put("scope", session.scope).put("updatedAt", updatedAt)
            .put("device", "e".repeat(32)).put("kind", kind).put("ownerMemberId", owner)
            .put("authorMemberId", owner).put("deleted", deleted).put("nonce", nonce).put("body", body)
    }

    private fun memberRecord(session: SyncSession, member: String, name: String, updatedAt: Long) = sealedRecord(
        session, "member:$member", "member", member, updatedAt,
        JSONObject().put("memberId", member).put("name", name).put("sharesSms", false),
    )

    private fun txnRecord(session: SyncSession, owner: String, localRef: String, updatedAt: Long) = sealedRecord(
        session, familyTxnId(owner, localRef), "transaction", owner, updatedAt,
        JSONObject().put("ownerMemberId", owner).put("at", 1000).put("amountRial", 50_000).put("direction", "out"),
    )

    private fun tombstoneRecord(session: SyncSession, id: String, kind: String, owner: String, updatedAt: Long) =
        sealedRecord(session, id, kind, owner, updatedAt, JSONObject().put("v", 1).put("id", id).put("deleted", true), deleted = true)

    /** One sync whose pull hands back [records], in that order. */
    private suspend fun pullOnce(server: FakeSyncServer, durable: DurableDb, session: SyncSession, vararg records: JSONObject) {
        server.pulls.add(JSONObject().put("seq", records.size).put("records", JSONArray(records.toList())).put("hasMore", false).toString())
        pushedBy(server, durable, session)
    }

    /** Only the new own member is left, nothing pushed names the old household, and her goals stayed. */
    private suspend fun assertOldHouseholdBuried(server: FakeSyncServer, durable: DurableDb, session: SyncSession) {
        assertEquals(listOf(session.member), durable.familyMembers().all().filterNot { it.deleted }.map { it.id })
        val pushed = pushedBy(server, durable, session)
        assertTrue(pushed.isNotEmpty())
        for (record in pushed) {
            val id = record.getString("id")
            assertFalse(id, id.contains(oldMe) || id.contains(oldThem))
            if (record.getString("kind") in setOf("transaction", "asset", "member")) {
                assertEquals(id, session.member, record.getString("ownerMemberId"))
            }
        }
        for (id in listOf("hers-private", "hers-shared")) {
            val kept = durable.goals().anyById(id)!!
            assertFalse("$id was deleted", kept.deleted)
            assertFalse(kept.shared)
            assertEquals("", kept.ownerMemberId)
        }
    }

    @Test
    fun `claiming on a restored backup buries the old household`() = lifecycle { server, durable ->
        restoredHousehold(
            durable,
            listOf(
                SyncPublication(familyTxnId(oldMe, "m:1"), "manual", "h", updatedAt = 1000),
                SyncPublication("category:${"c".repeat(64)}", "category", "h", updatedAt = 1000),
            ),
        )

        val session = claimHousehold(server.base, durable, "سهیل")

        assertOldHouseholdBuried(server, durable, session)
        assertNull(durable.goals().anyById("theirs"))
        assertTrue(durable.familyTxns().all().all { it.deleted })
        assertTrue(durable.familyAssets().all().all { it.deleted })
    }

    @Test
    fun `joining from a restored backup buries the old household`() = lifecycle { server, durable ->
        // Only her دارایی mark left to say who she was.
        restoredHousehold(durable, listOf(SyncPublication("asset:$oldMe", "asset", "h", updatedAt = 1000)))
        val durable2 = secondDurable()
        try {
            val host = claimHousehold(server.base, durable2, "مریم")
            val link = pairingUrl(host, invite(host, durable2))

            val session = joinHousehold(link, durable, "سهیل", allowedBase = server.base)

            assertOldHouseholdBuried(server, durable, session)
            assertNull(durable.goals().anyById("theirs"))
        } finally {
            durable2.close()
        }
    }

    @Test
    fun `a restore with nothing naming her keeps every goal, private and hers`() = lifecycle { server, durable ->
        restoredHousehold(durable, emptyList())

        val session = claimHousehold(server.base, durable, "سهیل")

        assertOldHouseholdBuried(server, durable, session)
        val theirs = durable.goals().anyById("theirs")!!
        assertFalse(theirs.deleted)
        assertFalse(theirs.shared)
    }

    @Test
    fun `claim writes a resumable session`() = lifecycle { server, durable ->
        val session = claimHousehold(server.base, durable, "مریم")

        val claim = server.requestsTo("/v1/claim").single()
        assertEquals("POST", claim.method)
        assertNull(claim.auth)
        val hid = hidOf(session)
        assertTrue(hid.matches(Regex("[0-9a-f]{32}")))
        assertEquals("hid=$hid", claim.query)
        val claimBody = JSONObject(claim.body)
        assertEquals(1, claimBody.getJSONArray("scopes").length())
        assertEquals("family:$hid", claimBody.getJSONArray("scopes").getString(0))

        assertEquals("$hid.${server.secret}", session.token)
        val loaded = loadSession(durable)!!
        assertEquals(server.base, loaded.base)
        assertEquals(session.token, loaded.token)
        assertEquals("family:$hid", loaded.scope)
        val self = durable.familyMembers().get(session.member)!!
        assertEquals("مریم", self.name)
        assertFalse(self.sharesSms)
        assertFalse(self.deleted)
    }

    @Test
    fun `invite plus pairingUrl let a second phone join with the same scope key`() = lifecycle { server, durable ->
        val session = claimHousehold(server.base, durable, "مریم")
        val code = invite(session, durable)
        assertEquals(server.inviteCode, code)
        assertEquals("Bearer ${session.token}", server.requestsTo("/v1/invite").single().auth)

        val link = pairingUrl(session, code)
        val durable2 = secondDurable()
        try {
            val joined = joinHousehold(link, durable2, "رضا", allowedBase = server.base)

            val pair = server.requestsTo("/v1/pair").single()
            assertEquals("Bearer ${hidOf(session)}.${"0".repeat(64)}", pair.auth)
            assertEquals(code, JSONObject(pair.body).getString("code"))

            val loaded = loadSession(durable2)!!
            assertEquals(hidOf(session), hidOf(loaded))
            assertEquals(session.scope, loaded.scope)
            // The scope key rides the link, never the server: byte-equal on both phones.
            assertTrue(session.key.contentEquals(loaded.key))
            assertNotEquals(session.member, loaded.member)
            assertFalse(durable2.familyMembers().get(joined.member)!!.sharesSms)
        } finally {
            durable2.close()
        }
    }

    @Test
    fun `join refuses a pairing link from a foreign origin`() = lifecycle { server, durable ->
        val session = claimHousehold(server.base, durable, "مریم")
        val link = pairingUrl(session, invite(session, durable))

        val durable2 = secondDurable()
        try {
            val failure = runCatching {
                joinHousehold(link, durable2, "رضا", allowedBase = "https://sync.muchtoman.com")
            }.exceptionOrNull()
            assertTrue(failure is IllegalStateException)
            assertTrue(server.requestsTo("/v1/pair").isEmpty())
            assertNull(loadSession(durable2))
        } finally {
            durable2.close()
        }
    }

    @Test
    fun `leave clears the session only after the server said yes`() = lifecycle { server, durable ->
        val session = claimHousehold(server.base, durable, "مریم")

        leaveFamily(session, durable)

        val leave = server.requestsTo("/v1/leave").single()
        assertEquals("Bearer ${session.token}", leave.auth)
        val record = JSONObject(leave.body).getJSONObject("record")
        assertTrue(record.getBoolean("deleted"))
        assertEquals("member:${session.member}", record.getString("id"))
        assertNull(loadSession(durable))
    }

    @Test
    fun `leave keeps every goal she made while paired and drops only theirs`() = lifecycle { server, durable ->
        val session = claimHousehold(server.base, durable, "مریم")
        val other = "b".repeat(32)
        // Everything she makes while paired carries her member id, shared or not.
        fun goal(id: String, kind: String, shared: Boolean, owner: String) = Goal(
            id = id, nameFa = id, targetRial = 1_000_000, kind = kind, period = GoalPeriod.MONTH,
            startsOn = 0, createdAt = 1, updatedAt = 1, shared = shared, ownerMemberId = owner,
        )
        durable.goals().put(goal("cap-private", GoalKind.CAP, shared = false, owner = session.member))
        durable.goals().put(goal("save-shared", GoalKind.SAVE, shared = true, owner = session.member))
        durable.goals().put(goal("instalment", GoalKind.INSTALLMENT, shared = false, owner = session.member))
        durable.goals().put(goal("theirs", GoalKind.CAP, shared = true, owner = other))

        leaveFamily(session, durable)

        for (id in listOf("cap-private", "save-shared", "instalment")) {
            val kept = durable.goals().anyById(id)!!
            assertFalse("$id was deleted", kept.deleted)
            assertFalse(kept.shared)
            assertEquals("", kept.ownerMemberId)
        }
        assertNull(durable.goals().anyById("theirs"))
    }

    /**
     * The pull starts again at zero after a rejoin, and everything the household wrote before the
     * burial carries an older stamp. The buried copies must not outrank it, or the family view
     * stays empty until each of them is edited.
     */
    @Test
    fun `a rejoin lands the people its burial took away, older stamps and all`() = lifecycle { server, durable ->
        val session = claimHousehold(server.base, durable, "مریم")
        val other = "b".repeat(32)
        val ref = "m:1"
        durable.familyMembers().put(FamilyMember(other, "رضا", updatedAt = 1000))
        durable.familyTxns().put(
            FamilyTxn(familyTxnId(other, ref), other, "manual", at = 1000, day = 1, amountRial = -50_000, updatedAt = 1000)
        )
        val link = pairingUrl(session, invite(session, durable))
        leaveFamily(session, durable)

        val rejoined = joinHousehold(link, durable, "مریم", allowedBase = server.base)
        pullOnce(server, durable, rejoined, memberRecord(rejoined, other, "رضا", 1000), txnRecord(rejoined, other, ref, 1000))

        assertFalse(durable.familyMembers().get(other)!!.deleted)
        assertFalse(durable.familyTxns().get(familyTxnId(other, ref))!!.deleted)
        // Her own old id stays buried: a copy of who she was must not land as somebody else.
        assertTrue(durable.familyMembers().get(session.member)!!.deleted)
    }

    /**
     * Removed, or a leave whose answer was lost: the server no longer knows the token, and every
     * sync and every «خروج» would fail for ever if the phone refused to believe it.
     */
    @Test
    fun `a token the server no longer knows is a phone already out`() = lifecycle { server, durable ->
        val session = claimHousehold(server.base, durable, "مریم")
        assertFalse(sessionRejected(session, durable))
        server.script("/v1/sync", 503)
        assertFalse(sessionRejected(session, durable))
        server.script("/v1/sync", 401)
        assertTrue(sessionRejected(session, durable))

        server.script("/v1/leave", 401)
        leaveFamily(session, durable)

        assertNull(loadSession(durable))
        assertTrue(durable.familyMembers().get(session.member)!!.deleted)
    }

    @Test
    fun `her note on her own row reaches the household she pairs into next`() = lifecycle { server, durable ->
        val session = claimHousehold(server.base, durable, "مریم")
        val now = System.currentTimeMillis()
        durable.manual().put(ManualTxn("one", now, tehranDay(now), -60_000L, createdAt = now, updatedAt = now))
        val ref = manualRef("one")
        durable.decisions().put(
            TxnDecision(
                "n1", ref, DecisionKind.NOTE, "قسط", now, now,
                memberId = session.member, familyRef = familyTxnId(session.member, ref),
            )
        )
        val link = pairingUrl(session, invite(session, durable))
        leaveFamily(session, durable)

        val rejoined = joinHousehold(link, durable, "مریم", allowedBase = server.base)

        val notes = pushedBy(server, durable, rejoined).filter { it.getString("kind") == "note" }
        assertEquals(listOf(rejoined.member), notes.map { it.getString("ownerMemberId") })
    }

    @Test
    fun `a failed leave keeps the session`() = lifecycle { server, durable ->
        val session = claimHousehold(server.base, durable, "مریم")
        server.script("/v1/leave", 503)

        val failure = runCatching { leaveFamily(session, durable) }.exceptionOrNull()

        assertTrue(failure is SyncHttpException)
        assertEquals(1, server.requestsTo("/v1/leave").size)
        val loaded = loadSession(durable)!!
        assertEquals(session.token, loaded.token)
        assertEquals(session.scope, loaded.scope)
        // Nothing was buried: her member row is still alive.
        assertFalse(durable.familyMembers().get(session.member)!!.deleted)
    }

    @Test
    fun `remove tombstones the target and never the caller`() = lifecycle { server, durable ->
        val session = claimHousehold(server.base, durable, "مریم")
        val other = "b".repeat(32)
        durable.familyMembers().put(FamilyMember(other, "رضا", updatedAt = 1000))

        removeFamilyMember(session, durable, other)

        val remove = server.requestsTo("/v1/remove").single()
        assertEquals("Bearer ${session.token}", remove.auth)
        val body = JSONObject(remove.body)
        assertEquals(other, body.getString("member"))
        val record = body.getJSONObject("record")
        assertTrue(record.getBoolean("deleted"))
        assertEquals("member:$other", record.getString("id"))
        assertTrue(durable.familyMembers().get(other)!!.deleted)

        val failure = runCatching { removeFamilyMember(session, durable, session.member) }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
        // Refused locally: the server never saw a second remove.
        assertEquals(1, server.requestsTo("/v1/remove").size)
        assertFalse(durable.familyMembers().get(session.member)!!.deleted)
    }

    /**
     * A re-paired member re-publishes everything under a fresh id, so the copies under the old one
     * have to go wherever the tombstone lands — including on the remover's own phone, which never
     * pulls its own tombstone back.
     */
    @Test
    fun `a member who is gone takes their rows, and an older copy cannot bring them back`() = lifecycle { server, durable ->
        val session = claimHousehold(server.base, durable, "مریم")
        val left = "b".repeat(32)
        val removed = "c".repeat(32)
        val asset = { member: String -> sealedRecord(
            session, "asset:$member", "asset", member, 1000,
            JSONObject().put("memberId", member).put("totalToman", 100.0).put("items", JSONArray()),
        ) }
        pullOnce(
            server, durable, session,
            memberRecord(session, left, "رضا", 1000), txnRecord(session, left, "m:1", 1000), asset(left),
            memberRecord(session, removed, "سارا", 1000), txnRecord(session, removed, "m:1", 1000), asset(removed),
            tombstoneRecord(session, "member:$left", "member", left, 2000),
        )
        val revision = durable.meta().get(META_SYNC_DERIVE_REVISION)
        removeFamilyMember(session, durable, removed)

        for (member in listOf(left, removed)) {
            assertTrue(durable.familyMembers().get(member)!!.deleted)
            assertNull(durable.familyTxns().get(familyTxnId(member, "m:1")))
            assertNull(durable.familyAssets().get(member))
        }
        // The remover's phone asks for the re-derive its own pull will never ask for.
        assertNotEquals(revision, durable.meta().get(META_SYNC_DERIVE_REVISION))

        pullOnce(server, durable, session, txnRecord(session, left, "m:2", 3000), asset(left))
        assertNull(durable.familyTxns().get(familyTxnId(left, "m:2")))
        assertNull(durable.familyAssets().get(left))
    }

    @Test
    fun `a remove cancelled while the server answers still buries the member here`() = lifecycle { server, durable ->
        val session = claimHousehold(server.base, durable, "مریم")
        val other = "b".repeat(32)
        durable.familyMembers().put(FamilyMember(other, "رضا", updatedAt = 1000))
        val arrived = CountDownLatch(1)
        val release = CountDownLatch(1)
        server.held["/v1/remove"] = arrived to release

        val removal = CoroutineScope(Dispatchers.IO).launch { removeFamilyMember(session, durable, other) }
        arrived.await()
        removal.cancel()
        release.countDown()
        removal.join()

        assertTrue(durable.familyMembers().get(other)!!.deleted)
    }

    @Test
    fun `a failed remove leaves the local member row alive`() = lifecycle { server, durable ->
        val session = claimHousehold(server.base, durable, "مریم")
        val other = "b".repeat(32)
        durable.familyMembers().put(FamilyMember(other, "رضا", updatedAt = 1000))
        server.script("/v1/remove", 503)

        val failure = runCatching { removeFamilyMember(session, durable, other) }.exceptionOrNull()

        assertTrue(failure is SyncHttpException)
        assertEquals(1, server.requestsTo("/v1/remove").size)
        assertFalse(durable.familyMembers().get(other)!!.deleted)
    }

    @Test
    fun `renew claims a fresh household and keeps only the person`() = lifecycle { server, durable ->
        val session = claimHousehold(server.base, durable, "مریم")
        val other = "b".repeat(32)
        // She shares SMS in the old household; renewal must land her in the new one not sharing.
        durable.familyMembers().put(
            durable.familyMembers().get(session.member)!!.copy(sharesSms = true, updatedAt = 2000)
        )
        durable.familyMembers().put(FamilyMember(other, "رضا", updatedAt = 1000))
        val txnId = familyTxnId(other, "m:1")
        durable.familyTxns().put(
            FamilyTxn(txnId, other, "sms", at = 1000, day = 1, amountRial = -50_000, updatedAt = 1000)
        )
        durable.goals().put(
            Goal(
                id = "goal-hers", nameFa = "سقف خرج", targetRial = 1_000_000, kind = GoalKind.CAP,
                period = GoalPeriod.MONTH, startsOn = 0, createdAt = 1, updatedAt = 1,
                shared = true, ownerMemberId = session.member,
            )
        )
        durable.goals().put(
            Goal(
                id = "goal-theirs", nameFa = "پس‌انداز", targetRial = 2_000_000, kind = GoalKind.SAVE,
                period = GoalPeriod.ONCE, startsOn = 0, createdAt = 2, updatedAt = 2,
                shared = true, ownerMemberId = other,
            )
        )

        val renewed = renewHousehold(durable)

        assertEquals(2, server.requestsTo("/v1/claim").size)
        val claim = server.requestsTo("/v1/claim")[1]
        assertEquals(session.member, JSONObject(claim.body).getString("memberId"))
        assertEquals(session.device, JSONObject(claim.body).getString("deviceId"))

        // A fresh household under a fresh key — same person, same device.
        assertNotEquals(hidOf(session), hidOf(renewed))
        assertNotEquals(session.token, renewed.token)
        assertFalse(session.key.contentEquals(renewed.key))
        assertEquals(session.member, renewed.member)
        assertEquals(session.device, renewed.device)
        assertEquals(renewed.token, loadSession(durable)!!.token)

        assertNull(durable.familyMembers().get(other))
        val hers = durable.familyMembers().get(session.member)!!
        assertFalse(hers.deleted)
        assertFalse(hers.sharesSms)
        assertNull(durable.familyTxns().get(txnId))
        val herGoal = durable.goals().anyById("goal-hers")!!
        assertFalse(herGoal.deleted)
        assertFalse(herGoal.shared)
        assertEquals(session.member, herGoal.ownerMemberId)
        assertNull(durable.goals().anyById("goal-theirs"))
        assertEquals("false", durable.meta().get(META_SYNC_IDENTITY_OK))
    }

    @Test
    fun `rejoin buries the old household only after a live pair`() = lifecycle { server, durable ->
        val session = claimHousehold(server.base, durable, "مریم")
        // A different household, minted on a second phone, hands her a link.
        val durable2 = secondDurable()
        try {
            val sessionB = claimHousehold(server.base, durable2, "رضا")
            val link = pairingUrl(sessionB, invite(sessionB, durable2))

            // A dead code must leave the old household untouched.
            server.script("/v1/pair", 503)
            val failure = runCatching {
                rejoinHousehold(link, durable, "مریم", allowedBase = server.base)
            }.exceptionOrNull()
            assertTrue(failure is SyncHttpException)
            val kept = loadSession(durable)!!
            assertEquals(session.token, kept.token)
            assertEquals(session.scope, kept.scope)
            assertFalse(durable.familyMembers().get(session.member)!!.deleted)

            // A live pair replaces the household: old scope gone, new scope present.
            val rejoined = rejoinHousehold(link, durable, "مریم", allowedBase = server.base)
            val loaded = loadSession(durable)!!
            assertEquals(sessionB.scope, loaded.scope)
            assertEquals(hidOf(sessionB), hidOf(loaded))
            assertNotEquals(session.scope, loaded.scope)
            assertTrue(sessionB.key.contentEquals(loaded.key))
            // The pair minted a fresh member id; the old own row belongs to a household she left.
            assertNotEquals(session.member, rejoined.member)
            assertTrue(durable.familyMembers().get(session.member)!!.deleted)
            assertFalse(durable.familyMembers().get(rejoined.member)!!.deleted)
        } finally {
            durable2.close()
        }
    }
}
