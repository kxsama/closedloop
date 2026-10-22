package com.closedloop.app

/** An isolated editor draft. Saving/cancelling never changes a live plan by reference. */
class ScheduleDraft(existing: Plan?, defaultStart: String, defaultMinutes: Int = 10) {
    var start = existing?.start ?: defaultStart
        private set
    var minutes = existing?.targetMinutes ?: defaultMinutes
        private set
    var weekMode = existing?.weekMode ?: "all"
    val slots: MutableMap<Int, DaySlot> = (1..7).associateWith { day ->
        existing?.slots?.get(day)?.copy() ?: DaySlot(true, start, minutes)
    }.toMutableMap()
    val customized: Set<Int>
        get() = slots.filterValues { it.start != start || it.minutes != minutes }.keys

    fun setBase(newStart: String = start, newMinutes: Int = minutes) {
        require(newMinutes in 1..999)
        val keep = customized.toSet()
        start = newStart
        minutes = newMinutes
        for ((day, slot) in slots) {
            if (day !in keep) {
                slot.start = start
                slot.minutes = minutes
            }
        }
    }

    fun copySlots() = slots.mapValues { it.value.copy() }.toMutableMap()

    fun applyDays(confirmed: Map<Int, DaySlot>) {
        for (day in 1..7) confirmed[day]?.let { slots[day] = it.copy() }
    }

    fun summary(): String {
        val days = slots.filterValues { it.on }.keys.sorted()
        val names = listOf("一", "二", "三", "四", "五", "六", "日")
        val daysText = when (days) {
            (1..7).toList() -> "每天"
            (1..5).toList() -> "周一至周五"
            listOf(6, 7) -> "周末"
            emptyList<Int>() -> "请选择重复日期"
            else -> days.joinToString("、") { "周${names[it - 1]}" }
        }
        val week = when (weekMode) { "odd" -> " · 单周"; "even" -> " · 双周"; else -> "" }
        val custom = if (customized.isEmpty()) "" else " · ${customized.size} 天单独安排"
        return daysText + week + custom
    }
}
