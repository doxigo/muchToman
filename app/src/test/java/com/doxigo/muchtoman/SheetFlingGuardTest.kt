package com.doxigo.muchtoman

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SheetState
import androidx.compose.material3.SheetValue
import androidx.compose.ui.unit.Velocity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    // A back press mid-slide cancels the close's hide; the save queued behind it must still run.
    @OptIn(ExperimentalMaterial3Api::class)
    @Test
    fun aCutShortHideStillRunsWhatWasQueuedBehindIt() {
        val state = SheetState(false, { 0f }, { 0f }, SheetValue.Expanded)
        val scope = CoroutineScope(Job() + Dispatchers.Unconfined).apply { cancel() }
        var saved = false
        scope.hideThen(state) { saved = true }
        assertTrue(saved)
    }
}
