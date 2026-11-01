package com.drivescore.scoring

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GradeEngineTest {
    private val e = GradeEngine()

    @Test fun `시작은 초보`() = assertEquals(Grade.BEGINNER, e.evaluate(emptyList()).current)

    @Test fun `평균 80점 5회면 숙련, 4회면 아직 초보`() {
        assertEquals(Grade.BEGINNER, e.evaluate(List(4) { 90 }).current)
        assertEquals(Grade.SKILLED, e.evaluate(List(5) { 80 }).current)
    }

    @Test fun `베테랑과 마스터 진입`() {
        assertEquals(Grade.VETERAN, e.evaluate(List(10) { 85 }).current)
        assertEquals(Grade.MASTER, e.evaluate(List(20) { 90 }).current)
    }

    @Test fun `등급은 내려가지 않는다`() {
        assertEquals(Grade.SKILLED, e.evaluate(List(5) { 80 } + List(10) { 30 }).current)
    }

    @Test fun `다음 등급 조건을 알려준다`() {
        val p = e.evaluate(listOf(70, 90))
        assertEquals(Grade.SKILLED, p.next)
        assertEquals(80, p.requiredAverage)
        assertEquals(5, p.requiredTrips)
        assertEquals(2, p.countedTrips)
        assertEquals(80.0, p.currentAverage!!, 1e-9)
    }

    @Test fun `마스터는 다음 등급이 없다`() = assertNull(e.evaluate(List(20) { 95 }).next)

    @Test fun `등급이 오를수록 과제가 어려워진다`() {
        Grade.entries.map { GradeRules.forGrade(it) }.zipWithNext { a, b -> assert(a.signalLeadSec < b.signalLeadSec) }
    }
}
