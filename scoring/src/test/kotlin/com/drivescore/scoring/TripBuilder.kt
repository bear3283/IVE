package com.drivescore.scoring

/** 10Hz 샘플을 구간 단위로 쌓는 테스트 도우미. 차속을 적분해 odoM을 만든다. */
class TripBuilder {
    val samples = mutableListOf<SignalSample>()
    private var t = 0.0
    private var odo = 0.0
    private var lastSpeed = 0.0
    private var sig = TurnSignal.OFF
    private val dt = 0.1

    val now get() = t
    val odoM get() = odo

    fun signal(s: TurnSignal) = apply { sig = s }

    fun hold(sec: Double, speed: Double, steer: Double = 0.0, brake: Boolean = false) =
        ramp(sec, speed, speed, steer, steer, brake)

    fun ramp(
        sec: Double,
        fromSpeed: Double,
        toSpeed: Double,
        fromSteer: Double = 0.0,
        toSteer: Double = fromSteer,
        brake: Boolean = false,
    ) = apply {
        val n = Math.round(sec / dt).toInt()
        repeat(n) { i ->
            val f = if (n <= 1) 1.0 else i.toDouble() / (n - 1)
            val v = fromSpeed + (toSpeed - fromSpeed) * f
            samples += SignalSample(
                t = t, odoM = odo, speedKph = v,
                steeringDeg = fromSteer + (toSteer - fromSteer) * f,
                turnSignal = sig, brakeOn = brake, gear = Gear.D,
            )
            odo += v / 3.6 * dt
            t += dt
            lastSpeed = v
        }
    }
}

fun trip(block: TripBuilder.() -> Unit): TripBuilder = TripBuilder().apply(block)

val CFG = ScoringConfig()
