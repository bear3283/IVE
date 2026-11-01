package com.drivescore.scoring

import com.drivescore.scoring.MarkerType.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CourseMatcherTest {
    private val m = CourseMatcher(CFG)
    private val home = CourseSignature(37.5665, 126.9780, listOf(TURN_RIGHT, TURN_LEFT, TURN_LEFT, TURN_RIGHT, U_TURN, TURN_RIGHT))

    @Test fun `같은 목적지와 대부분 같은 회전 순서면 같은 코스`() {
        val again = home.copy(turns = listOf(TURN_RIGHT, TURN_LEFT, TURN_LEFT, TURN_RIGHT, TURN_RIGHT))
        assertEquals("a", m.findMatch(again, mapOf("a" to home)))
    }

    @Test fun `목적지가 멀면 다른 코스`() {
        assertNull(m.findMatch(home.copy(destLat = 37.60), mapOf("a" to home)))
    }

    @Test fun `회전 순서가 많이 다르면 다른 코스`() {
        assertNull(m.findMatch(home.copy(turns = listOf(TURN_LEFT, U_TURN, TURN_LEFT)), mapOf("a" to home)))
    }

    @Test fun `가장 비슷한 코스를 고른다`() {
        val near = home.copy(turns = home.turns.dropLast(1))
        assertEquals("exact", m.findMatch(home, mapOf("near" to near, "exact" to home)))
    }
}
