package com.drivescore.scoring

data class VoiceConfig(
    /** F-06 지난 감점 지점 몇 m 전에 예고할지. */
    val hazardLeadM: Double = 300.0,
    /** 다음 회전까지 이 시간 안이면 운전에 집중하도록 예고를 보류한다(DESIGN 9절). */
    val holdBeforeTurnSec: Double = 10.0,
)

/** 주행 중 발화 규칙: 한 문장, 숫자 금지(DESIGN 9절). */
object DrivingSpeech {
    fun allowed(text: String): Boolean {
        if (text.isBlank() || text.any(Char::isDigit)) return false
        return text.trim().trimEnd('.', '!', '?').none { it == '.' || it == '!' || it == '?' }
    }
}

/**
 * F-06 예고 판단. 누적거리로 지점마다 한 번만 알린다.
 * 예고 거리 안에 몰린 감점은 하나로 묶고, 보류된 예고는 지점을 지나면 버린다.
 */
class HazardAlerter(
    hazardOdos: List<Double>,
    turnOdos: List<Double>,
    private val config: VoiceConfig = VoiceConfig(),
) {
    private val turns = turnOdos.sorted()
    private val pending = ArrayDeque<Double>().apply {
        hazardOdos.sorted().forEach { if (isEmpty() || it - last() > config.hazardLeadM) addLast(it) }
    }

    fun poll(odoM: Double, speedKph: Double): Boolean {
        while (pending.isNotEmpty() && odoM >= pending.first()) pending.removeFirst()
        val hazard = pending.firstOrNull() ?: return false
        if (speedKph <= 0.0 || odoM < hazard - config.hazardLeadM) return false
        val nextTurn = turns.firstOrNull { it > odoM }
        if (nextTurn != null && (nextTurn - odoM) / (speedKph / 3.6) <= config.holdBeforeTurnSec) return false
        pending.removeFirst()
        return true
    }
}
