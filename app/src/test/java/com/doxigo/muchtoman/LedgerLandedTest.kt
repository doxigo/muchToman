package com.doxigo.muchtoman

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ledgerLanded] must not replay. A publish ends in [markFilingSeen], which takes the filing
 * note down — right while she is looking, wrong while she is not. A tick kept for the next
 * collector would fire when the screen next starts and cancel the very note the worker posted
 * for her to find. Give the flow a replay and the first test fails.
 */
class LedgerLandedTest {

    @Test
    fun `a tick nobody is collecting is dropped, not kept`() = runBlocking {
        assertTrue(ledgerLanded.tryEmit(Unit))
        assertTrue(ledgerLanded.replayCache.isEmpty())
        assertNull(withTimeoutOrNull(100) { ledgerLanded.first() })
    }

    @Test
    fun `a tick reaches whoever is collecting`() = runBlocking {
        val got = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeoutOrNull(2_000) { ledgerLanded.first() }
        }
        ledgerLanded.tryEmit(Unit)
        assertNotNull(got.await())
    }
}
