package com.doxigo.muchtoman

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
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
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SyncRecoveryTest {
    @Test
    fun `a failed later pull leaves a rebuild pending even when the retry receives nothing`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val durable = Room.inMemoryDatabaseBuilder(context, DurableDb::class.java).build()
        val derived = Room.inMemoryDatabaseBuilder(context, DerivedDb::class.java).build()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        try {
            val member = "a".repeat(32)
            val other = "b".repeat(32)
            val device = "c".repeat(32)
            val key = newScopeKey()
            val session = SyncSession(
                "http://127.0.0.1:${server.address.port}", "household.secret", device, member, "family:home", key,
            )
            saveSession(durable, session)
            derive(durable, derived, emptyMap())
            assertFalse(needsDerive(derived, durable))
            val id = familyTxnId(other, "m:received")
            val entry = SyncEntry(ownerMemberId = other, at = 1000, amountRial = 100, direction = "out")
            val (nonce, body) = seal(key, Json.encodeToString(entry))
            val row = JSONObject()
                .put("id", id).put("scope", session.scope).put("updatedAt", 1000)
                .put("device", "d".repeat(32)).put("kind", "transaction")
                .put("ownerMemberId", other).put("authorMemberId", other)
                .put("nonce", nonce).put("body", body)
            val pulls = AtomicInteger()
            server.createContext("/") { exchange ->
                exchange.requestBody.use { it.readBytes() }
                var status = 200
                val response = if (exchange.requestMethod == "GET") {
                    when (pulls.incrementAndGet()) {
                        1 -> JSONObject().put("seq", 1).put("hasMore", true)
                            .put("records", JSONArray().put(row)).toString()
                        2 -> {
                            status = 503
                            "{}"
                        }
                        else -> JSONObject().put("seq", 1).put("hasMore", false)
                            .put("records", JSONArray()).toString()
                    }
                } else "{}"
                val bytes = response.toByteArray(Charsets.UTF_8)
                exchange.sendResponseHeaders(status, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            server.start()
            val failure = runCatching { syncNow(durable, derived, session) }.exceptionOrNull()
            assertTrue(failure is SyncHttpException)
            assertEquals("1", durable.meta().get(META_SYNC_SEQ))
            assertEquals(1, durable.familyTxns().all().size)
            assertTrue(derived.txn().newest(10).isEmpty())
            assertTrue(needsDerive(derived, durable))

            val retry = syncNow(durable, derived, session)
            assertEquals(0, retry.received)
            assertTrue(needsDerive(derived, durable))
            if (needsDerive(derived, durable)) derive(durable, derived, emptyMap())
            assertEquals(1, derived.txn().newest(10).size)
            assertFalse(needsDerive(derived, durable))
        } finally {
            server.stop(0)
            derived.close()
            durable.close()
        }
    }
}
