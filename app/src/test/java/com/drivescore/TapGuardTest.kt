package com.drivescore

import com.drivescore.ui.TapGuard
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TapGuardTest {
    private var clock = 10_000L
    private val guard = TapGuard(repeatMs = 400, settleMs = 800, now = { clock })

    @Test fun firstTapPasses() = assertTrue(guard.allow())

    @Test fun doubleTapCountsOnce() {
        assertTrue(guard.allow())
        clock += 399
        assertFalse(guard.allow())
        clock += 1
        assertTrue(guard.allow())
    }

    @Test fun tapRightAfterScreenChangeIsDropped() {
        guard.screenChanged()
        clock += 799
        assertFalse(guard.allow())
        clock += 1
        assertTrue(guard.allow())
    }

    @Test fun droppedTapDoesNotExtendTheBlock() {
        guard.screenChanged()
        clock += 700
        assertFalse(guard.allow())
        clock += 100
        assertTrue(guard.allow())
    }
}
