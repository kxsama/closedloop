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
    println("$passed regression checks passed")
}
