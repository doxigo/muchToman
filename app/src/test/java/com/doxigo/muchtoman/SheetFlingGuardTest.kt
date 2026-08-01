package com.doxigo.muchtoman

import androidx.compose.ui.unit.Velocity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class SheetFlingGuardTest {

    // The sign is the whole fix: a leftover fling pointing up (list hit its end) must never
    // reach the sheet, or its settle spring bounces the sheet off the expanded anchor. A
    // leftover pointing down (list hit its top) must reach it, or drag-to-close dies.
    @Test
    fun swallowsUpwardLeftoverKeepsDownward() = runBlocking {
        val up = Velocity(0f, -1200f)
        assertEquals(up, SheetFlingGuard.onPostFling(Velocity.Zero, up))

        assertEquals(Velocity.Zero, SheetFlingGuard.onPostFling(Velocity.Zero, Velocity(0f, 1200f)))
        assertEquals(Velocity.Zero, SheetFlingGuard.onPostFling(Velocity.Zero, Velocity.Zero))
    }
}
