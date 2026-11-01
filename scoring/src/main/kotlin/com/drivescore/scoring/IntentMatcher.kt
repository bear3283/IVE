package com.drivescore.scoring

enum class Intent { YES, NO, MISSION, SWITCH_DRIVER, SKIP_RECORD, REPLAY, NEXT, WHY, COMPARE, GRADE }

data class IntentMatch(val intent: Intent, val driver: String? = null)

/** STT 문장을 정해진 의도로 매칭한다(SPEC 4). LLM을 쓰지 않는다. */
object IntentMatcher {
    private val RULES: List<Pair<Regex, Intent>> = listOf(
        Regex("왜.*감점|감점.*(왜|이유)") to Intent.WHY,
        Regex("비교") to Intent.COMPARE,
        Regex("등급") to Intent.GRADE,
        Regex("미션") to Intent.MISSION,
        Regex("기록(하지|안|말)") to Intent.SKIP_RECORD,
        Regex("운전(해|할|한다)") to Intent.SWITCH_DRIVER,
        Regex("리플레이|다시보여") to Intent.REPLAY,
        Regex("^다음|건너뛰") to Intent.NEXT,
        Regex("^(아니|괜찮|싫어)") to Intent.NO,
        // "네비"처럼 '네'로 시작하는 다른 말을 잡지 않도록 문장 전체를 맞춘다.
        Regex("^((네|예|응|그래|좋아|맞아)(요)?)+$") to Intent.YES,
    )
    private val DRIVER = Regex("^(?:오늘은|이번엔|지금은)?(.+?)(?:이|가)운전")

    /** @param allowed 현재 상태에서 받을 의도. 주행 중에는 YES, NO만 넘긴다. */
    fun match(text: String, allowed: Set<Intent> = Intent.entries.toSet()): IntentMatch? {
        val normalized = text.replace(Regex("[\\s.,!?~]"), "")
        if (normalized.isEmpty()) return null
        val intent = RULES.firstOrNull { it.first.containsMatchIn(normalized) }?.second ?: return null
        if (intent !in allowed) return null
        val driver = if (intent == Intent.SWITCH_DRIVER) {
            DRIVER.find(normalized)?.groupValues?.get(1)?.let { if (it == "내") "나" else it }
        } else {
            null
        }
        return IntentMatch(intent, driver)
    }
}
