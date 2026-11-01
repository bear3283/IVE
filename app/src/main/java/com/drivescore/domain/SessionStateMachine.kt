package com.drivescore.domain

import com.drivescore.scoring.Gear

enum class Session { IDLE, PRE_DRIVE, DRIVING, SCORING, ARRIVED }

sealed interface SessionEvent {
    data object DestinationSet : SessionEvent
    data object DestinationCleared : SessionEvent
    data class Vehicle(val gear: Gear, val speedKph: Double) : SessionEvent
    data object ScoringDone : SessionEvent
    data object Dismissed : SessionEvent
}

object SessionStateMachine {
    fun next(s: Session, e: SessionEvent): Session = when (e) {
        SessionEvent.DestinationSet -> if (s == Session.IDLE) Session.PRE_DRIVE else s
        SessionEvent.DestinationCleared -> if (s == Session.PRE_DRIVE) Session.IDLE else s
        SessionEvent.ScoringDone -> if (s == Session.SCORING) Session.ARRIVED else s
        SessionEvent.Dismissed -> if (s == Session.ARRIVED) Session.IDLE else s
        is SessionEvent.Vehicle -> {
            val moving = (e.gear == Gear.D || e.gear == Gear.R) && e.speedKph > 0.0
            when (s) {
                Session.IDLE, Session.PRE_DRIVE, Session.ARRIVED -> if (moving) Session.DRIVING else s
                Session.DRIVING -> if (e.gear == Gear.P) Session.SCORING else s
                Session.SCORING -> s
            }
        }
    }
}
