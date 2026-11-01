package com.drivescore.scoring

/** 등급과 무관한 분류·채점 값. 모두 초기값이며 실차 튜닝 대상이다. */
data class ScoringConfig(
    /** 왼쪽이 양수로 들어오는 차량은 -1. */
    val steeringSign: Int = 1,
    /** 이 값보다 작은 조향은 0 근처로 본다. */
    val centerDeg: Double = 5.0,
    /** 깜빡이 선행 시간을 잴 때 조향이 시작됐다고 보는 각도. */
    val steeringOnsetDeg: Double = 1.0,
    /** 0 근처가 이 시간 이상 이어져야 조향 구간이 끝난다. */
    val settleSec: Double = 0.5,
    /** 구간 최고 속도가 이보다 낮으면 평가하지 않는다(정차 중 조향). */
    val ignoreBelowKph: Double = 3.0,
    val naviMatchM: Double = 50.0,

    val uTurnMinDeg: Double = 450.0,
    val uTurnMaxKph: Double = 20.0,
    val uTurnNearMarkerMinDeg: Double = 360.0,
    val turnMinDeg: Double = 90.0,
    val turnNearMarkerMinDeg: Double = 60.0,
    val turnMaxKph: Double = 35.0,
    val laneChangeMinDeg: Double = 10.0,
    val laneChangeMaxDeg: Double = 45.0,
    val laneChangeMinSec: Double = 2.0,
    val laneChangeMaxSec: Double = 8.0,
    val laneChangeMaxReversals: Int = 1,
    val laneChangeMinKph: Double = 30.0,
    val curveMinSec: Double = 8.0,
    val curveMaxDeg: Double = 90.0,
    /** 허용치 초과 조향이 이 시간 이상이면 흔들림. */
    val wobbleMinSec: Double = 1.0,
    /** 허용치를 넘는 좌우 반전이 이 횟수 이상이면 시간과 무관하게 흔들림. */
    val wobbleMinReversals: Int = 3,

    val severityMissing: Double = 1.0,
    val severityLate: Double = 0.5,
    val severityNotCancelled: Double = 0.5,
    val severitySlowdown: Double = 0.5,
    val severityAbrupt: Double = 0.5,
    val severityGap: Double = 0.5,
    val severityHardEvent: Double = 1.0,
    val severityOverSpeed: Double = 1.0,
    val severityWobble: Double = 1.0,
    val severityLateDecel: Double = 1.0,
    val severityTooSlow: Double = 0.5,

    val laneCancelSec: Double = 3.0,
    val turnCancelSec: Double = 2.0,
    val turnEntryMaxKph: Double = 30.0,
    val abruptSteeringDegPerSec: Double = 720.0,
    val gapBrakeWindowSec: Double = 3.0,
    /** 감속도 측정 창. */
    val accelWindowSec: Double = 0.5,
    /** 급제동과 페달 ON을 대조할 때 앞으로 보는 시간. */
    val pedalLookbackSec: Double = 1.0,

    val decelPrepLeadM: Double = 50.0,
    val decelTurnTargetKph: Double = 30.0,
    val decelMarginKph: Double = 15.0,
    val tooSlowKph: Double = 15.0,
    val tooSlowMinSec: Double = 60.0,

    val pointsTurn: Double = 25.0,
    val pointsLaneChange: Double = 20.0,
    val pointsSmooth: Double = 20.0,
    val pointsCamera: Double = 15.0,
    val pointsStraight: Double = 10.0,
    val pointsDecelPrep: Double = 10.0,
    /** 급출발·급정지, 직선 안정성의 기회 수 = 주행 거리 / 이 값. */
    val kmPerOpportunity: Double = 2.0,

    /** 이 시간 안에 다시 기준을 넘으면 같은 급가속·급제동 한 건이다. */
    val hardEventMergeSec: Double = 3.0,
    val difficultyPerTurn: Double = 0.01,
    val difficultyPerCamera: Double = 0.005,
    val difficultyMaxBonus: Double = 0.25,

    val courseDestinationM: Double = 150.0,
    val courseMinSimilarity: Double = 0.8,
)

enum class Grade { BEGINNER, SKILLED, VETERAN, MASTER }

/** SPEC 3.3 등급별 기준. */
data class GradeRules(
    val signalLeadSec: Double,
    val straightToleranceDeg: Double,
    val hardBrakeMps2: Double,
    val hardAccelMps2: Double,
    val requireCancelAfterTurn: Boolean,
    val entryAverage: Int,
    val entryTrips: Int,
) {
    companion object {
        fun forGrade(grade: Grade): GradeRules = when (grade) {
            Grade.BEGINNER -> GradeRules(1.0, 15.0, 4.5, 4.0, false, 0, 0)
            Grade.SKILLED -> GradeRules(3.0, 10.0, 3.5, 3.0, false, 80, 5)
            Grade.VETERAN -> GradeRules(5.0, 7.0, 3.0, 2.5, false, 85, 10)
            Grade.MASTER -> GradeRules(5.0, 5.0, 2.5, 2.0, true, 90, 20)
        }
    }
}
