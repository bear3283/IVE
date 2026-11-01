package com.drivescore

import com.drivescore.domain.Session
import com.drivescore.domain.SessionEvent.*
import com.drivescore.domain.SessionStateMachine
import com.drivescore.scoring.Gear
import kotlin.test.Test
import kotlin.test.assertEquals

class SessionStateMachineTest {
    private val sm = SessionStateMachine
    private fun run(start: Session, vararg ev: com.drivescore.domain.SessionEvent) = ev.fold(start, sm::next)

    @Test fun `목적지가 있으면 PreDrive`() = assertEquals(Session.PRE_DRIVE, sm.next(Session.IDLE, DestinationSet))

    @Test fun `D에서 차속이 생겨야 주행 시작`() {
        assertEquals(Session.PRE_DRIVE, run(Session.PRE_DRIVE, Vehicle(Gear.D, 0.0)))
        assertEquals(Session.DRIVING, run(Session.PRE_DRIVE, Vehicle(Gear.D, 5.0)))
        assertEquals(Session.DRIVING, run(Session.IDLE, Vehicle(Gear.R, 2.0)))
    }

    @Test fun `신호 대기는 주행 중, P 진입이 주행 종료`() {
        assertEquals(Session.DRIVING, run(Session.DRIVING, Vehicle(Gear.D, 0.0), Vehicle(Gear.N, 0.0)))
        assertEquals(Session.SCORING, run(Session.DRIVING, Vehicle(Gear.P, 0.0)))
    }

    @Test fun `채점 후 도착, 닫으면 Idle, D로 바꾸면 다시 주행`() {
        assertEquals(Session.ARRIVED, run(Session.SCORING, ScoringDone))
        assertEquals(Session.IDLE, run(Session.ARRIVED, Dismissed))
        assertEquals(Session.DRIVING, run(Session.ARRIVED, Vehicle(Gear.D, 3.0)))
    }

    @Test fun `경로 취소`() = assertEquals(Session.IDLE, run(Session.PRE_DRIVE, DestinationCleared))
}
