package com.closedloop.app

import java.time.LocalDate

/** Runs against the real Android engine/cadence on a JVM; no phone or Android stubs. */
fun main() {
    val day = LocalDate.of(2026, 10, 22)
    fun engine(minutes: Int = 60) = Engine(
        Plan("test", "阅读", "15:00", minutes, mutableListOf("reader"), fuseRatio = 99.0), day,
    )
    val t0 = engine().plan.startEpoch(day)
    val midnight = day.plusDays(1).atStartOfDay(java.time.ZoneId.systemDefault()).toEpochSecond() * 1000
    var passed = 0
    fun test(name: String, body: () -> Unit) {
        body()
        passed++
        println("PASS $name")
    }
    test("idle skips app queries and waits 60 seconds") {
        val e = engine()
        check(!MonitorCadence.needsForeground(t0 - 3600, listOf(e)))
        check(MonitorCadence.delayMs((t0 - 3600) * 1000, true, listOf(e), midnight) == 60_000L)
    }
    test("slow idle stops at the scheduled time, including fractional seconds") {
        val e = engine()
        check(MonitorCadence.delayMs(t0 * 1000 - 1234, true, listOf(e), midnight) == 1234L)
        check(MonitorCadence.needsForeground(t0, listOf(e)))
    }
    test("active and cooldown retain 3 seconds; screen off uses 30 seconds") {
        val e = engine()
        e.tick(t0, "reader", false)
        check(MonitorCadence.delayMs(t0 * 1000, true, listOf(e), midnight) == 3000L)
        check(MonitorCadence.delayMs(t0 * 1000, false, listOf(e), midnight) == 30_000L)
        e.session.state = SState.COOLDOWN
        check(MonitorCadence.delayMs(t0 * 1000, true, listOf(e), midnight) == 3000L)
    }
    test("next waiting plan is not delayed by screen-off cadence") {
        val e = engine()
        val later = engine().also { it.session.state = SState.RUNNING }
        check(MonitorCadence.delayMs(t0 * 1000 - 2000, false, listOf(e, later), midnight) == 2000L)
    }
    test("settled and empty schedules don't query app usage") {
        val e = engine().also { it.session.state = SState.SETTLED }
        check(!MonitorCadence.needsForeground(t0, listOf(e)))
        check(!MonitorCadence.needsForeground(t0, emptyList()))
    }
    test("day boundary wakes idle service without a one-minute delay") {
        check(MonitorCadence.delayMs(midnight - 1200, false, emptyList(), midnight) == 1200L)
    }
    test("scheduled reminder starts while locked and only occurs once") {
        val e = engine()
        check(e.tick(t0 - 1, null, true).isEmpty())
        check(e.tick(t0, null, true).count { it is Act.Remind } == 1)
        check(e.session.state == SState.RUNNING && e.session.effectiveSec == 0.0)
        check(e.tick(t0 + 30, null, true).none { it is Act.Remind })
    }
    test("locked and unknown intervals never become effective on resume") {
        val e = engine()
        e.tick(t0, "reader", false)
        e.tick(t0 + 3, "reader", false)
        val before = e.session.effectiveSec
        e.tick(t0 + 6, null, true)
        e.tick(t0 + 36, null, true)
        e.tick(t0 + 50, "reader", false)
        check(e.session.effectiveSec == before)
        e.tick(t0 + 53, "reader", false)
        check(e.session.effectiveSec == before + 3)
    }
    test("screen-off time doesn't confirm or extend a deviation") {
        val e = engine()
        e.tick(t0, "reader", false)
        e.tick(t0 + 3, "reader", false)
        e.tick(t0 + 6, "other", false)
        e.tick(t0 + 30, null, true)
        e.tick(t0 + 60, null, true)
        val acts = e.tick(t0 + 90, "other", false)
        check(!e.session.devConfirmed && e.session.deviationSec == 0.0)
        check(acts.none { it is Act.Escalate })
    }
    test("locked missed plans converge from running and cooldown") {
        for (state in listOf(SState.RUNNING, SState.COOLDOWN)) {
            val e = engine()
            e.session.state = state
            val acts = e.tick(t0 + Engine.MISS_SETTLE_SEC, null, true)
            check(e.session.state == SState.SETTLED && e.session.reason == "未开始")
            check(acts.any { it is Act.Settle })
        }
    }
    test("long sleep gap never becomes effective time") {
        val e = engine()
        e.tick(t0, "reader", false)
        e.tick(t0 + 3, "reader", false)
        val before = e.session.effectiveSec
        val acts = e.tick(t0 + 1000, "reader", false)
        check(acts.any { it is Act.Gap })
        check(e.session.effectiveSec == before)
    }
    test("L2, L3, cooldown and return remain reachable at 3-second cadence") {
        val e = engine()
        val acts = (0L..603L step 3).flatMap { e.tick(t0 + it, "other", false) }
        val levels = acts.filterIsInstance<Act.Escalate>().map { it.level }
        check(levels.first() == 2 && 3 in levels)
        check(e.session.state == SState.COOLDOWN)
        e.tick(t0 + 606, null, true)
        check(e.tick(t0 + 630, "reader", false).any { it is Act.CooldownExit })
        check(e.session.state == SState.RUNNING)
    }
    test("late arrival under 60 seconds is not credited") {
        val e = engine()
        for (s in 0L..54L step 3) e.tick(t0 + s, "other", false)
        e.tick(t0 + 57, "reader", false)
        check(e.session.effectiveSec <= 3.0)
        check(e.session.deviationCount == 0)
    }
    test("continuous reading still completes at target duration") {
        val e = engine(3)
        val acts = (0L..180L step 3).flatMap { e.tick(t0 + it, "reader", false) }
        check(acts.filterIsInstance<Act.Settle>().single().result == Engine.RESULT_DONE)
        check(e.session.effectiveSec == 180.0)
    }
    test("opening 566 minutes late settles on first tick with no stale reminder") {
        for (locked in listOf(false, true)) {
            val e = engine()
            val acts = e.tick(t0 + 566 * 60, if (locked) null else "reader", locked)
            check(acts.none { it is Act.Remind })
            check(acts.filterIsInstance<Act.Settle>().single().reason == "未开始")
            check(e.session.state == SState.SETTLED)
            check(e.tick(t0 + 566 * 60 + 3, "reader", false).isEmpty())
        }
    }
    test("late cold start expires WAITING, RUNNING and COOLDOWN at the exact boundary") {
        for (state in listOf(SState.WAITING, SState.RUNNING, SState.COOLDOWN)) {
            val e = engine()
            e.session.state = state
            val acts = e.tick(t0 + Engine.MISS_SETTLE_SEC, null, true)
            check(acts.single() is Act.Settle)
        }
    }
    test("a plan with effective work is not discarded for being started hours ago") {
        val e = engine()
        e.session.state = SState.RUNNING
        e.session.effectiveSec = 60.0
        e.tick(t0 + Engine.MISS_SETTLE_SEC + 60, null, true)
        check(e.session.state != SState.SETTLED)
    }
    test("recurring plan is still scheduled on the following day") {
        val e = engine()
        e.tick(t0 + 566 * 60, null, true)
        val tomorrow = day.plusDays(1)
        val next = Engine(e.plan.forDay(tomorrow), tomorrow)
        check(next.plan.appliesOn(tomorrow))
        check(next.tick(next.plan.startEpoch(tomorrow), null, true).any { it is Act.Remind })
    }
    test("cancelling a plan edit or per-day edit leaves original slots intact") {
        val p = engine().plan.copy(slots = (1..7).associateWith { DaySlot(true, "15:00", 60) }.toMutableMap())
        val draft = ScheduleDraft(p, "20:00")
        draft.setBase("16:30", 25)
        check(p.start == "15:00" && p.slots!!.getValue(1).start == "15:00")
        val cancelled = draft.copySlots()
        cancelled.getValue(1).minutes = 17
        check(draft.slots.getValue(1).minutes == 25)
    }
    test("changing shared timing preserves separately customized days") {
        val p = engine().plan.copy(slots = (1..7).associateWith {
            if (it == 3) DaySlot(true, "19:20", 35) else DaySlot(true, "15:00", 60)
        }.toMutableMap())
        val draft = ScheduleDraft(p, "20:00")
        draft.setBase("16:00", 25)
        check(draft.slots.getValue(3) == DaySlot(true, "19:20", 35))
        check(draft.slots.getValue(2) == DaySlot(true, "16:00", 25))
    }
    test("per-day confirmation is copied and recurrence has one source of truth") {
        val draft = ScheduleDraft(null, "16:00")
        val days = draft.copySlots()
        days.getValue(1).on = false
        days.getValue(3).start = "19:00"
        draft.applyDays(days)
        days.getValue(3).start = "23:00"
        check(!draft.slots.getValue(1).on && draft.slots.getValue(3).start == "19:00")
        check(draft.customized == setOf(3))
        draft.weekMode = "odd"
        check("单周" in draft.summary() && "1 天单独安排" in draft.summary())
    }
    test("resuming an explicitly frozen observation never credits stopped time") {
        val e = engine()
        e.tick(t0, "reader", false)
        e.tick(t0 + 3, "reader", false)
        val before = e.session.effectiveSec
        e.session.lastObserved = false
        e.session.lastTick = t0 + 4
        e.tick(t0 + 80, "reader", false)
        check(e.session.effectiveSec == before)
        e.tick(t0 + 83, "reader", false)
        check(e.session.effectiveSec == before + 3)
    }
    passed += runEngineTimingRegression()
    passed += runSessionLifecycleRegression()
    passed += runUpdateRegression()
    println("$passed regression checks passed")
}
