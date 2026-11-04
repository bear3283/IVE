package com.drivescore.scoring

import kotlin.math.max
import kotlin.math.sqrt

/** 등급과 무관한 분류·채점 값. 모두 초기값이며 실차 튜닝 대상이다. */
data class ScoringConfig(
    /** 왼쪽이 양수로 들어오는 차량은 -1. */
    val steeringSign: Int = 1,
    /** 이 값보다 작은 조향은 0 근처로 본다. */
    val centerDeg: Double = 5.0,
    /** 깜빡이 선행 시간을 잴 때 조향이 시작됐다고 보는 각도. */
    val steeringOnsetDeg: Double = 1.0,
    /** 0 근처가 이 시간 이상 이어져야 조향 구간이 끝난다. */
    val settleSec: Double = 1.2,
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

    // 사건별 차감(점). 법규 > 안전 습관 순서로 둔 초기값이며 실측으로 조정한다.
    val deductSignalMissing: Double = 8.0,
    val deductSignalLate: Double = 4.0,
    val deductNotCancelled: Double = 2.0,
    val deductOverSpeed: Double = 10.0,
    val deductHardEvent: Double = 6.0,
    val deductSevereBrake: Double = 10.0,
    val deductWobble: Double = 5.0,
    val deductAbrupt: Double = 3.0,
    val deductSlowdown: Double = 5.0,
    val deductLateDecel: Double = 3.0,
    val deductTooSlow: Double = 3.0,

    // 항목별 차감 상한(점). 한 가지 습관이 점수 전체를 지우지 않게 한다.
    val capSignal: Double = 30.0,
    val capCamera: Double = 25.0,
    val capSmooth: Double = 25.0,
    val capSteering: Double = 15.0,
    val capDecel: Double = 10.0,
    /** 이 거리까지는 차감을 그대로 적용하고, 넘으면 √(기준/거리)로 줄인다. */
    val distanceReferenceKm: Double = 10.0,

    /** 도로교통법 시행령 별표2: 행위 지점 30m 전 신호. 고속도로(100m)는 도로 종류를 알 수 없어 아직 적용하지 않는다. */
    val signalLegalM: Double = 30.0,
    val hardBrakeMps2: Double = 3.0,
    val severeBrakeMps2: Double = 4.5,
    val hardAccelMps2: Double = 2.5,

    // 핸들 각도를 횡가속도로 환산하는 값. 조향비와 축거는 차종 가정이며 실차로 확인해야 한다.
    val steeringRatio: Double = 15.0,
    val wheelbaseM: Double = 2.9,
    /** 조향 구간 시작을 보는 횡가속도와 각도 범위. 최대값이 centerDeg다. */
    val centerLateralMps2: Double = 0.3,
    val centerMinDeg: Double = 2.0,
    /** 고속 차선변경으로 보는 횡가속도와 각도 하한. S자 조향일 때만 쓴다. */
    val laneChangeLateralMps2: Double = 0.4,
    val laneChangeFloorDeg: Double = 2.5,
    /** 직선 흔들림으로 보는 횡가속도와 각도 범위. */
    val wobbleLateralMps2: Double = 1.0,
    val wobbleMinDeg: Double = 3.0,
    val wobbleMaxDeg: Double = 30.0,

    /** 이보다 짧고 평가 기회도 적으면 표본이 부족한 주행으로 본다. */
    val lowSampleKm: Double = 3.0,
    val lowSampleOpportunities: Int = 3,

    val laneCancelSec: Double = 3.0,
    val turnCancelSec: Double = 2.0,
    val turnEntryMaxKph: Double = 30.0,
    val abruptSteeringDegPerSec: Double = 720.0,
    /** 조향 속도 측정 창. 샘플 간격 흔들림으로 값이 튀지 않게 한다. */
    val steeringRateWindowSec: Double = 0.3,
    val gapBrakeWindowSec: Double = 3.0,
    /** 감속도 측정 창. */
    val accelWindowSec: Double = 1.0,
    /** 급제동과 페달 ON을 대조할 때 앞으로 보는 시간. */
    val pedalLookbackSec: Double = 1.0,

    val decelPrepLeadM: Double = 50.0,
    val decelTurnTargetKph: Double = 30.0,
    val decelMarginKph: Double = 15.0,
    val tooSlowKph: Double = 15.0,
    val tooSlowMinSec: Double = 60.0,

    /** 이보다 짧은 주행은 평가할 것이 없어 저장하지 않는다(주차 중 기어 조작 등). */
    val minTripDistanceM: Double = 100.0,
    /** 이 시간 안에 다시 기준을 넘으면 같은 급가속·급제동 한 건이다. */
    val hardEventMergeSec: Double = 3.0,
    val courseDestinationM: Double = 150.0,
    val courseMinSimilarity: Double = 0.8,
) {
    /** 긴 주행의 차감 계수. 위험은 거리에 비례하지 않고 증가 폭이 줄어든다. */
    fun distanceFactor(km: Double): Double = if (km <= distanceReferenceKm) 1.0 else sqrt(distanceReferenceKm / km)

    /** 이 속도에서 횡가속도 [lateralMps2]를 내는 핸들 각도. */
    fun steeringDegFor(speedKph: Double, lateralMps2: Double): Double {
        val v = max(speedKph / 3.6, 1.0)
        return Math.toDegrees(lateralMps2 * wheelbaseM / (v * v)) * steeringRatio
    }
}

enum class Grade { BEGINNER, SKILLED, VETERAN, MASTER }

/** 등급은 점수 기준을 바꾸지 않는다. 등급 과제(깜빡이 여유 시간)와 진입 조건만 정한다. */
data class GradeRules(
    val signalLeadSec: Double,
    val entryAverage: Int,
    val entryTrips: Int,
) {
    companion object {
        fun forGrade(grade: Grade): GradeRules = when (grade) {
            Grade.BEGINNER -> GradeRules(3.0, 0, 0)
            Grade.SKILLED -> GradeRules(4.0, 80, 5)
            Grade.VETERAN -> GradeRules(5.0, 85, 10)
            Grade.MASTER -> GradeRules(6.0, 90, 20)
        }
    }
}
