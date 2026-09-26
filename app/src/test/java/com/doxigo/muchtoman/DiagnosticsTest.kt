package com.doxigo.muchtoman

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The crash report is the one thing the app sends that carries anything from the code path she was
 * on, so what is pinned here is what it must never carry: an exception's message. Run with:
 * ./gradlew testFullDebugUnitTest --tests '*DiagnosticsTest*'
 */
class DiagnosticsTest {

    @Test
    fun `a report is class names and frames, never a message`() {
        val e = IllegalStateException(
            "sms: بانک ملت برداشت 4,666,251,136",
            NumberFormatException("For input string: \"4,666,251,136\""),
        )
        val report = crashReport(e, "1.2.5 · Android 34 · Pixel 9")

        assertTrue(report.startsWith("1.2.5 · Android 34 · Pixel 9\n"))
        assertTrue(report.contains("java.lang.IllegalStateException\n\tat "))
        assertTrue(report.contains("Caused by: java.lang.NumberFormatException\n"))
        assertFalse(report.contains("4,666,251,136"))
        assertFalse(report.contains("ملت"))
    }

    @Test
    fun `a stack overflow keeps its cause and stays under the Worker's cap`() {
        val deep = RuntimeException("top", RuntimeException("root"))
        deep.stackTrace = Array(5_000) { StackTraceElement("a.b", "c", "SourceFile", it) }
        val report = crashReport(deep, "v")

        assertTrue(report.length <= 4_000)
        assertEquals(30, report.lines().count { it.startsWith("\tat a.b.c") })
        assertTrue(report.contains("Caused by: java.lang.RuntimeException"))
    }
}
