package com.doxigo.muchtoman

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

class SyncCoordinationTest {
    @Test
    fun `foreground and background sync work cannot overlap`() = runBlocking {
        val running = AtomicInteger()
        val peak = AtomicInteger()
        withTimeout(5000) {
            (1..20).map {
                async {
                    withFamilySync {
                        val count = running.incrementAndGet()
                        peak.updateAndGet { previous -> maxOf(previous, count) }
                        yield()
                        running.decrementAndGet()
                    }
                }
            }.awaitAll()
        }
        assertEquals(1, peak.get())
        assertEquals(0, running.get())
    }

    @Test
    fun `a rotated token is current but a changed household identity is obsolete`() {
        val original = SyncSession("https://sync.test", "household.old", "device", "member", "scope", byteArrayOf(1))
        assertTrue(sameHouseholdSession(original, original.copy(token = "household.new")))
        assertFalse(sameHouseholdSession(original, original.copy(token = "other.old")))
        assertFalse(sameHouseholdSession(original, original.copy(member = "other")))
        assertFalse(sameHouseholdSession(original, original.copy(device = "other")))
        assertFalse(sameHouseholdSession(original, original.copy(scope = "other")))
        assertFalse(sameHouseholdSession(original, original.copy(key = byteArrayOf(2))))
    }

    @Test
    fun `rotation recovers using the saved replacement after server applied it`() = runBlocking {
        val pending = PendingSyncRotation("household.old", "household.new", 1)
        val attempted = mutableListOf<String>()
        finishSyncRotation(pending) { token, secret ->
            attempted += token
            assertEquals("new", secret)
            if (token == pending.oldToken) throw SyncHttpException(401)
        }
        assertEquals(listOf(pending.oldToken, pending.newToken), attempted)
    }

    @Test
    fun `rotation does not fall back on an ambiguous connection error`() = runBlocking {
        val pending = PendingSyncRotation("household.old", "household.new", 1)
        var attempted = 0
        val error = runCatching {
            finishSyncRotation(pending) { _, _ ->
                attempted++
                throw IOException("offline")
            }
        }.exceptionOrNull()
        assertTrue(error is IOException)
        assertEquals(1, attempted)
    }

    @Test
    fun `a detected transfer keeps its category across the encrypted payload schema`() {
        val entry = SyncEntry(
            at = 1, amountRial = 100, direction = "out", categoryId = "bank-fees",
            transfer = true,
        )
        val received = Json.decodeFromString<SyncEntry>(Json.encodeToString(entry))
        assertTrue(received.transfer)
        assertEquals("bank-fees", received.categoryId)
        val old = Json.decodeFromString<SyncEntry>("""{"at":1,"amountRial":100,"direction":"out"}""")
        assertFalse(old.transfer)
    }
}
