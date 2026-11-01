package com.drivescore

import com.drivescore.domain.Briefing
import com.drivescore.domain.VoiceKind
import com.drivescore.domain.VoiceLevel
import com.drivescore.domain.VoiceLines
import com.drivescore.domain.VoicePolicy
import com.drivescore.scoring.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VoiceLinesTest {
    private val lines = VoiceLines { id, args -> "$id" + args.joinToString(prefix = "(", postfix = ")") }
    private fun p(sev: Double, reason: PenaltyReason, cat: Category = Category.SMOOTH) = Penalty(cat, reason, 1.0, 1.0, sev, emptyMap(), null, null)

    @Test fun `디브리핑은 지난 점수가 없으면 첫 기록으로 말한다`() {
        assertTrue(lines.debrief(80, null, 0).startsWith("${R.string.voice_debrief_first}(80)"))
    }

    @Test fun `디브리핑은 점수 차이를 양수로 말한다`() {
        assertTrue(lines.debrief(70, 80, 1).startsWith("${R.string.voice_debrief_down}(70, 10)"))
    }

    @Test fun `감점이 있으면 이유를 들을지 묻는다`() {
        assertTrue(lines.debrief(70, 70, 2).endsWith("${R.string.voice_debrief_ask}(2)"))
        assertTrue(lines.debrief(100, 70, 0).endsWith("${R.string.voice_debrief_clean}()"))
    }

    @Test fun `이유는 큰 감점부터 세 개만 말한다`() {
        val why = lines.why(listOf(p(1.0, PenaltyReason.HARD_ACCEL), p(3.0, PenaltyReason.HARD_BRAKE), p(2.0, PenaltyReason.TOO_SLOW), p(0.5, PenaltyReason.OVER_SPEED)))
        assertEquals(listOf(R.string.reason_hard_brake, R.string.reason_too_slow, R.string.reason_hard_accel).joinToString(" ") { "$it()." }, why)
    }

    @Test fun `같은 이유는 한 번만 말한다`() {
        val why = lines.why(listOf(p(3.0, PenaltyReason.HARD_BRAKE), p(2.0, PenaltyReason.HARD_BRAKE)))
        assertEquals("${R.string.reason_hard_brake}().", why)
    }

    @Test fun `브리핑은 단골 코스면 지난 감점으로 미션을 고른다`() {
        val b = Briefing("회사", emptyList(), 1, 1, 1, true, 70, 80, p(1.0, PenaltyReason.SIGNAL_MISSING, Category.LANE_CHANGE))
        val text = lines.briefing(b)
        assertTrue(text.startsWith("${R.string.voice_brief_known}(회사, 70, 80)"))
        assertTrue(text.contains("${R.string.mission_lane}"))
    }

    @Test fun `브리핑은 새 코스면 회전과 카메라 수를 말한다`() {
        assertEquals("${R.string.voice_brief_new}(2, 3)", lines.briefing(Briefing("회사", emptyList(), 2, 3, 1, false, null, null, null)))
    }

    @Test fun `주행 중에는 예고만 나가고 정차 중에는 예고가 나가지 않는다`() {
        val hazard = "지난번 감점 구간이에요."
        assertTrue(VoicePolicy.allows(VoiceKind.HAZARD, VoiceLevel.ALL, Gear.D, hazard))
        assertFalse(VoicePolicy.allows(VoiceKind.HAZARD, VoiceLevel.ALL, Gear.P, hazard))
        assertFalse(VoicePolicy.allows(VoiceKind.DEBRIEF, VoiceLevel.ALL, Gear.D, "오늘 85점"))
        assertFalse(VoicePolicy.allows(VoiceKind.ANSWER, VoiceLevel.ALL, null, "답"))
    }

    @Test fun `설정에 따라 걸러진다`() {
        assertFalse(VoicePolicy.allows(VoiceKind.ANSWER, VoiceLevel.OFF, Gear.P, "답"))
        assertFalse(VoicePolicy.allows(VoiceKind.BRIEFING, VoiceLevel.KEY, Gear.P, "브리핑"))
        assertTrue(VoicePolicy.allows(VoiceKind.DEBRIEF, VoiceLevel.KEY, Gear.P, "디브리핑"))
    }
}
