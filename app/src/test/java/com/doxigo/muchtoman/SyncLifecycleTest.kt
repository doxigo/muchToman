package com.doxigo.muchtoman

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
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
                } ?: when (path) {
                    "/v1/claim", "/v1/pair" -> 200 to JSONObject().put("secret", secret).toString()
                    "/v1/invite" -> 200 to JSONObject().put("code", inviteCode).toString()
                    else -> 200 to "{}"
                }
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
        assertTrue(durable.goals().anyById("theirs")!!.deleted)
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

        assertTrue(durable.familyMembers().get(other)!!.deleted)
        val hers = durable.familyMembers().get(session.member)!!
        assertFalse(hers.deleted)
        assertFalse(hers.sharesSms)
        assertTrue(durable.familyTxns().get(txnId)!!.deleted)
        val herGoal = durable.goals().anyById("goal-hers")!!
        assertFalse(herGoal.deleted)
        assertFalse(herGoal.shared)
        assertEquals(session.member, herGoal.ownerMemberId)
        assertTrue(durable.goals().anyById("goal-theirs")!!.deleted)
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
